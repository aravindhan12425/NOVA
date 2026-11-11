package com.assistant.nova.services

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.assistant.nova.core.NovaPrefs
import com.assistant.nova.core.NovaState
import com.assistant.nova.core.NovaState.Status
import com.assistant.nova.core.action.ActionExecutor
import com.assistant.nova.core.action.Reply
import android.telephony.TelephonyManager
import com.assistant.nova.core.Routines
import com.assistant.nova.core.UsageTracker
import com.assistant.nova.core.action.CallAssistant
import com.assistant.nova.core.command.CallAnswer
import com.assistant.nova.core.command.CallPhrases
import com.assistant.nova.core.command.RingPhrases
import com.assistant.nova.core.command.HandsFreePhrases
import com.assistant.nova.core.command.InCallAction
import com.assistant.nova.core.command.MediaAction
import com.assistant.nova.core.command.OnOff
import com.assistant.nova.core.command.TextNormalizer
import com.assistant.nova.core.voiceid.VoiceId
import org.vosk.Recognizer
import kotlin.math.sqrt
import com.assistant.nova.core.command.Command
import com.assistant.nova.core.command.CommandParser
import com.assistant.nova.core.command.Redact
import com.assistant.nova.core.device.DeviceProfile
import com.assistant.nova.core.media.NovaPlayer
import com.assistant.nova.core.speech.AndroidRecognizer
import com.assistant.nova.core.speech.Mic
import com.assistant.nova.core.speech.Speaker
import com.assistant.nova.core.speech.VoskRecognizer
import com.assistant.nova.core.wake.WakeWordEngine
import com.assistant.nova.core.wake.WakeWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nova's always-on part: a foreground service that keeps the microphone open, runs the
 * tiny offline wake-word model, and when it hears "Hey Jarvis" / "Hey Nova" listens for
 * a command, carries it out and answers.
 *
 * Battery: between commands only the wake-word engine runs (one CPU thread, and nothing
 * at all while the room is quiet). The big speech recognizer only runs for the few
 * seconds after the wake word. Listening pauses during phone calls, and optionally when
 * the screen is off or the battery is low.
 */
class NovaListenerService : Service() {

    companion object {
        const val ACTION_START = "com.assistant.nova.action.START"
        const val ACTION_STOP = "com.assistant.nova.action.STOP"
        const val ACTION_TALK = "com.assistant.nova.action.TALK"
        const val ACTION_BOOT = "com.assistant.nova.action.BOOT"
        const val ACTION_MEDIA_PLAY_PAUSE = "com.assistant.nova.action.MEDIA_PLAY_PAUSE"
        const val ACTION_MEDIA_NEXT = "com.assistant.nova.action.MEDIA_NEXT"
        const val ACTION_MEDIA_PREVIOUS = "com.assistant.nova.action.MEDIA_PREVIOUS"
        const val ACTION_MEDIA_STOP = "com.assistant.nova.action.MEDIA_STOP"
        /** Run a typed command (the "Try" buttons) as if it had been spoken. */
        const val ACTION_TEXT = "com.assistant.nova.action.TEXT"
        const val EXTRA_TEXT = "text"
        private val MEDIA_ACTIONS = setOf(ACTION_MEDIA_PLAY_PAUSE, ACTION_MEDIA_NEXT, ACTION_MEDIA_PREVIOUS, ACTION_MEDIA_STOP)

        private const val TAG = "NovaListener"

        /** 4 s of pure digital silence means Android is blocking our microphone. */
        private const val SILENCED_SAMPLES = WakeWordEngine.SAMPLE_RATE * 4

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context, action: String = ACTION_START): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, NovaListenerService::class.java).setAction(action))
            true
        } catch (e: Exception) {
            // Android 12+ refuses some background starts
            Log.w(TAG, "Could not start the listening service", e)
            false
        }

        fun runText(context: Context, text: String): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NovaListenerService::class.java).setAction(ACTION_TEXT).putExtra(EXTRA_TEXT, text),
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not run typed command", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NovaListenerService::class.java))
        }
    }

    private enum class Trigger { WAKE, TALK, TEXT, DIRECT, CALL, PAUSE, MIC_ERROR, BLOCKED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private lateinit var prefs: NovaPrefs
    private lateinit var speaker: Speaker
    private lateinit var executor: ActionExecutor
    private lateinit var player: NovaPlayer
    private lateinit var vosk: VoskRecognizer
    private lateinit var recognizer: AndroidRecognizer
    private val mic = Mic()
    private var wake: WakeWordEngine? = null
    private var wakeKey = ""
    private var listeningText = ""
    private var lastNotification = ""

    @Volatile private var talkRequested = false
    @Volatile private var pendingText: String? = null
    @Volatile private var startedInBackground = false
    private var phoneRecognizerMisses = 0
    private var preferOfflineUntil = 0L
    private lateinit var keyguard: KeyguardManager

    /** The user unlocked the phone (by hand or by voice): reset the voice-unlock safety counter. */
    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            prefs.unlockFailures = 0
            prefs.unlockBlocked = false
        }
    }

    private lateinit var audioManager: AudioManager
    private lateinit var calls: CallAssistant
    private lateinit var voiceId: VoiceId
    private lateinit var routines: Routines
    private lateinit var usage: UsageTracker

    @Volatile private var ringing = false
    @Volatile private var ringingNumber: String? = null
    @Volatile private var callAnnounced = false

    /** Phone call state (ringing / answered / ended) and the caller's number. */
    private val phoneState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    @Suppress("DEPRECATION")
                    intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.let { ringingNumber = it }
                    ringing = true
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    ringing = false
                    // A call is connected: get the offline recognizer ready for "end the call" etc.
                    if (prefs.callAssistant && prefs.listenDuringCalls) scope.launch(Dispatchers.IO) { runCatching { vosk.load() } }
                }
                else -> {
                    ringing = false
                    callAnnounced = false
                    ringingNumber = null
                }
            }
        }
    }

    /** True while Nova itself is handling a command (its own speech recognizer may be recording). */
    @Volatile private var inCommand = false

    /** Another app (voice note, camera video, voice typing, screen recorder…) started recording. */
    @Volatile private var otherAppRecording = false

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            otherAppRecording = othersRecording(configs)
            // Is Android giving Nova silence instead of the microphone (it does this to most apps
            // during calls)? Logged so call problems can be diagnosed.
            val mine = configs.firstOrNull { it.clientAudioSessionId == mic.sessionId }
            val silenced = mine?.isClientSilenced == true
            if (silenced != micSilenced) {
                micSilenced = silenced
                Log.i(TAG, "Microphone ${if (silenced) "MUTED by Android" else "available"} (call: $onCall, mode ${audioManager.mode})")
                if (silenced && onCall) show(Status.PAUSED, "The phone mutes Nova's mic in this call. Open Nova and tap the mic.")
            }
        }
    }

    /** Android is currently feeding Nova silence instead of the microphone. */
    @Volatile private var micSilenced = false

    /** When the last command finished: the phone's speech recognizer may still be letting go of the mic. */
    @Volatile private var commandEndedAt = 0L
    private var lastPause: String? = null

    private fun othersRecording(configs: List<AudioRecordingConfiguration>): Boolean {
        if (inCommand) return false
        if (SystemClock.elapsedRealtime() - commandEndedAt < 2_000) return false
        // During a call the phone app itself records the microphone; that's expected
        if (::audioManager.isInitialized && onCall) return false
        val mine = mic.sessionId
        return configs.any { it.clientAudioSessionId != mine }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = NovaPrefs(this)
        keyguard = getSystemService(KeyguardManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        audioManager.registerAudioRecordingCallback(recordingCallback, android.os.Handler(mainLooper))
        speaker = Speaker(this)
        player = NovaPlayer(this)
        player.onStateChanged = { track, playing ->
            if (track == null) {
                Notifications.cancelMusic(this)
                NovaState.nowPlaying.value = null
            } else {
                Notifications.showMusic(this, player.sessionToken, track.title, track.artist, playing)
                NovaState.nowPlaying.value = NovaState.NowPlaying(
                    track.title, track.artist, track.folder, playing, player.position, player.queueSize,
                )
            }
        }
        executor = ActionExecutor(this, player)
        vosk = VoskRecognizer(this, scope)
        recognizer = AndroidRecognizer(this)
        calls = CallAssistant(this)
        voiceId = VoiceId(this)
        routines = Routines(this)
        usage = UsageTracker(this)
        // USER_PRESENT and PHONE_STATE are protected system broadcasts: only Android itself can send them
        ContextCompat.registerReceiver(this, userPresent, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(
            this, phoneState, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        // Battery report: add up Nova's CPU and microphone time every few minutes
        scope.launch {
            while (true) {
                delay(5 * 60_000L)
                usage.sample(mic)
            }
        }
        NovaState.set(Status.STARTING, "Starting…")
    }

    private val isLocked: Boolean get() = keyguard.isKeyguardLocked

    private val verboseLogs: Boolean by lazy {
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /** Text that is safe to show on screen / in the notification while the phone is locked. */
    private fun safeText(text: String, aggressive: Boolean = false): String =
        if (isLocked) Redact.digits(text, aggressive) else text

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means Android restarted Nova by itself after stopping it
        val action = intent?.action ?: ACTION_BOOT
        if (action == ACTION_STOP) {
            prefs.alwaysOn = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (action in MEDIA_ACTIONS) {
            // Buttons in the music notification / on the lock screen
            when (action) {
                ACTION_MEDIA_PLAY_PAUSE -> player.togglePlayPause()
                ACTION_MEDIA_NEXT -> player.next()
                ACTION_MEDIA_PREVIOUS -> player.previous()
                ACTION_MEDIA_STOP -> player.stop()
            }
            if (loopJob?.isActive == true) return START_STICKY
            // A leftover notification was tapped after Nova had been stopped
            if (player.current == null) Notifications.cancelMusic(this)
            if (!prefs.alwaysOn) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!goForeground()) {
            Notifications.showTapToStart(this)
            stopSelf()
            return START_NOT_STICKY
        }
        Notifications.clearTapToStart(this)
        if (action == ACTION_BOOT) startedInBackground = true
        if (action == ACTION_TALK) talkRequested = true
        if (action == ACTION_TEXT) pendingText = intent?.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (loopJob?.isActive != true) loopJob = scope.launch { runLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { usage.sample(mic) }
        runCatching { unregisterReceiver(userPresent) }
        runCatching { unregisterReceiver(phoneState) }
        runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
        loopJob?.cancel()
        scope.cancel()
        speaker.shutdown()
        player.release()
        executor.close()
        vosk.close()
        voiceId.close()
        NovaState.set(Status.OFF)
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, Notifications.ID_LISTENING, Notifications.listening(this, "Starting…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "Android refused to start listening in the foreground", e)
        false
    }

    private fun show(status: Status, text: String) {
        NovaState.set(status, text)
        if (text != lastNotification) {
            lastNotification = text
            Notifications.update(this, text)
        }
    }

    // ── Main loop ───────────────────────────────────────────────────────────

    private suspend fun runLoop() {
        try {
            if (!DeviceProfile.isOnline(this) || prefs.engine == NovaPrefs.ENGINE_OFFLINE) {
                // Get the offline speech model ready before it's needed
                scope.launch(Dispatchers.IO) { runCatching { vosk.load() } }
            }
            while (currentCoroutineContext().isActive) {
                // Phone ringing: announce the caller once and listen for answer / reject
                if (ringing && !callAnnounced && prefs.callAssistant && prefs.announceCalls) {
                    callAnnounced = true
                    runCatching { handleIncomingCall() }.onFailure { Log.e(TAG, "Call handling failed", it) }
                    continue
                }

                // Typed command from a "Try" button: runs even while listening is paused
                val typed = pendingText
                if (typed != null) {
                    pendingText = null
                    handleCommand(wake, typed)
                    if (!prefs.alwaysOn) {
                        // Nova was only started to run this one command
                        stopSelf()
                        return
                    }
                    continue
                }

                val pause = pauseReason()
                // A tap on the mic ("Talk now", Nova's screen, power-button assistant) is a clear request:
                // take one command even while listening is paused, e.g. "end the call" with the call
                // assistant switched off. (Not while Voice ID is recording or the phone is ringing.)
                if (pause != null && talkRequested && !NovaState.enrolling.value && audioManager.mode != AudioManager.MODE_RINGTONE) {
                    talkRequested = false
                    Log.i(TAG, "Talk requested while paused ($pause)")
                    if (onCall && !vosk.isLoaded) {
                        show(Status.HEARING, "Loading offline speech…")
                        withContext(Dispatchers.IO) { runCatching { vosk.load() } }
                    }
                    if (mic.open()) handleCommand(wake) else speaker.speak("I can't use the microphone right now.")
                    mic.close()
                    continue
                }
                if (pause != null) {
                    mic.close()
                    if (lastPause != pause) Log.i(TAG, "Paused: $pause")
                    lastPause = pause
                    show(Status.PAUSED, "Paused: $pause")
                    // Short pauses (another app recording) are rechecked quickly so Nova is back fast
                    // …but a tap on the mic is answered straight away
                    val until = SystemClock.elapsedRealtime() + if (pause.contains("microphone")) 700 else 3_000
                    while (SystemClock.elapsedRealtime() < until && !talkRequested && pendingText == null) delay(150)
                    continue
                }
                if (lastPause != null) {
                    Log.i(TAG, "Resumed after: $lastPause")
                    lastPause = null
                }

                val models = WakeWords.enabled(this, prefs)
                if (models.isEmpty()) {
                    mic.close()
                    show(Status.LISTENING, "Wake word is off. Tap “Talk now” to speak.")
                    while (!talkRequested && pendingText == null && currentCoroutineContext().isActive) delay(300)
                    if (pendingText != null) continue
                    talkRequested = false
                    if (mic.open()) handleCommand(null)
                    continue
                }

                val engine = wakeEngine(models)
                if (engine == null) {
                    show(Status.ERROR, "Couldn't load the wake-word model")
                    delay(10_000)
                    continue
                }
                if (!mic.open()) {
                    show(Status.ERROR, "Microphone unavailable")
                    delay(5_000)
                    continue
                }
                val direct = directActive()
                if (direct && !vosk.isLoaded) withContext(Dispatchers.IO) { runCatching { vosk.load() } }
                listeningText = if (callListening()) {
                    "On a call: just say “end the call”, “speaker on”, “mute me”…"
                } else {
                    "Say “${models.keys.joinToString(" or ") { WakeWords.label(it) }}”" +
                        if (direct) ", or just say a command" else ""
                }
                if (callListening() && !vosk.isLoaded) withContext(Dispatchers.IO) { runCatching { vosk.load() } }
                show(Status.LISTENING, listeningText)

                when (withContext(Dispatchers.Default) { waitForWake(engine, direct) }) {
                    Trigger.WAKE, Trigger.TALK -> {
                        if (handleCommand(engine)) followOnWindow(engine)
                    }
                    Trigger.DIRECT -> {
                        val rough = pendingDirect
                        pendingDirect = null
                        if (rough != null) {
                            val text = recheckOffline(rough)
                            if (directAllowed(text) && handleCommand(engine, text, mode = Mode.DIRECT)) followOnWindow(engine)
                        }
                    }
                    Trigger.CALL -> {
                        val phrase = pendingCall
                        pendingCall = null
                        if (phrase != null) handleCommand(engine, phrase)
                    }
                    Trigger.PAUSE, Trigger.TEXT -> {}
                    Trigger.MIC_ERROR -> {
                        mic.close(); delay(1_000)
                    }
                    Trigger.BLOCKED -> {
                        // Started from the background without microphone access (e.g. after a reboot)
                        Notifications.showTapToStart(this)
                        stopSelf()
                        return
                    }
                }
            }
        } finally {
            mic.close()
            wake?.close()
            wake = null
            wakeKey = ""
        }
    }

    private suspend fun wakeEngine(models: Map<String, String>): WakeWordEngine? {
        val key = models.keys.sorted().joinToString(",")
        wake?.let { if (key == wakeKey) return it }
        wake?.close()
        wake = null
        return withContext(Dispatchers.IO) {
            runCatching { WakeWords.load(this@NovaListenerService, models) }
                .onFailure { Log.e(TAG, "Wake-word model failed to load", it) }
                .getOrNull()
        }?.also {
            wake = it
            wakeKey = key
        }
    }

    /** A command heard in no-wake-word mode, waiting to be run. */
    @Volatile private var pendingDirect: String? = null

    /**
     * No-wake-word mode is on, a voice profile exists, it's the chosen time (screen on /
     * always / while charging), and no music or call is going on.
     */
    private fun directActive(): Boolean {
        // (While music plays it stays on, but only for music commands: see isOwnersCommand)
        if (!prefs.directMode || !voiceId.isEnrolled || onCall) return false
        return when (prefs.directWhen) {
            NovaPrefs.DIRECT_ALWAYS -> true
            NovaPrefs.DIRECT_CHARGING -> getSystemService(BatteryManager::class.java).isCharging
            else -> getSystemService(PowerManager::class.java).isInteractive
        }
    }

    /**
     * No-wake-word mode: cuts the audio into sentences (by loudness, so silence costs
     * nothing), transcribes each one offline, and returns it only if it's a clear command
     * in the owner's voice.
     */
    private inner class DirectListener(
        private val rec: Recognizer,
        /** Same audio, but only the everyday commands in [HandsFreePhrases]: far fewer mistakes. */
        private val fixed: Recognizer?,
    ) {
        private val preroll = ArrayDeque<ShortArray>()
        private val audio = ArrayList<Short>()
        private var speaking = false
        private var quietMs = 0
        private var speechMs = 0
        private var loudSum = 0.0
        /** Phrases Vosk already finalised inside this sentence (it ends a phrase after ~0.5 s of quiet). */
        private val finished = ArrayList<String>()
        private val finishedFixed = ArrayList<String>()
        /** Words seen so far in this sentence (open recognizer), to tell speech from mere noise. */
        private var heardWords = false
        /** Background loudness (fan, traffic, TV): "quiet" means quiet compared with this. */
        private var noise = 120.0
        private var noiseSkips = 0

        /** Returns true when the open recognizer finished a phrase with this chunk. */
        private fun feed(c: ShortArray, n: Int): Boolean {
            fixed?.let { f -> if (f.acceptWaveForm(c, n)) vosk.text(f.result).takeIf { it.isNotBlank() }?.let { finishedFixed += it } }
            if (!rec.acceptWaveForm(c, n)) {
                if (!heardWords && vosk.text(rec.partialResult, "partial").isNotBlank()) heardWords = true
                return false
            }
            vosk.text(rec.result).takeIf { it.isNotBlank() }?.let { finished += it; heardWords = true }
            return true
        }

        /** The everyday command the fixed-list recognizer heard, if it heard exactly one. */
        private fun fixedCommand(extra: String = ""): String? =
            HandsFreePhrases.accept((finishedFixed + extra).filter { it.isNotBlank() }.joinToString(" "), routines.names())

        private fun reset() {
            speaking = false
            finished.clear(); finishedFixed.clear()
            runCatching { rec.reset() }
            fixed?.let { runCatching { it.reset() } }
        }

        fun step(buf: ShortArray, n: Int): String? {
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            val rms = sqrt(sum / n)
            val chunk = buf.copyOf(n)
            val startLevel = maxOf(300.0, noise * 2.2)
            val quietLevel = maxOf(220.0, noise * 1.4)
            if (!speaking) {
                preroll.addLast(chunk); if (preroll.size > 4) preroll.removeFirst()
                if (rms < startLevel) {
                    noise = noise * 0.95 + rms * 0.05 // learn the room while nobody speaks
                    return null
                }
                speaking = true; quietMs = 0; speechMs = 0; loudSum = 0.0; heardWords = false
                audio.clear(); finished.clear(); finishedFixed.clear()
                for (c in preroll) { feed(c, c.size); c.forEach { audio += it } }
                preroll.clear()
                return null
            }
            val phraseDone = feed(chunk, n)
            if (audio.size < 16_000 * 8) chunk.forEach { audio += it }
            speechMs += 80
            loudSum += rms
            quietMs = if (rms < quietLevel) quietMs + 80 else 0
            // Loud but no words after 1.5 s: a fan, traffic, a TV in another language… not speech.
            // Stop here (the engine was running non-stop before) and learn that this is the room's level.
            if (!heardWords && finished.isEmpty() && speechMs >= 1_500) {
                noise = noise * 0.6 + (loudSum * 80 / speechMs) * 0.4
                if (++noiseSkips % 20 == 1) Log.i(TAG, "Direct: just noise, ignoring (room level now %.0f)".format(noise))
                reset()
                return null
            }
            val maxSpeech = if (audioManager.isMusicActive) 3_500 else 5_000
            // A whole command is already there: act now instead of waiting for silence
            val complete = (phraseDone && finished.isNotEmpty() && isWholeCommand(finished.joinToString(" "))) ||
                fixedCommand() != null
            if (!complete && quietMs < 800 && speechMs < maxSpeech) return null
            // Sentence finished
            val endedBy = if (complete) "a whole command" else if (quietMs >= 800) "quiet" else "the time limit"
            val fixedHeard = fixedCommand(fixed?.let { vosk.text(it.finalResult) }.orEmpty())
            val phrases = (finished + vosk.text(rec.finalResult)).filter { it.isNotBlank() }
            reset()
            Log.i(TAG, "Direct: sentence of %.1f s, ended by %s".format(speechMs / 1000.0, endedBy))
            // An everyday command from the fixed list beats the open guess ("plastic" -> "flashlight")
            val text = fixedHeard ?: run {
                if (phrases.isEmpty()) return null
                // The whole thing if it's one command ("turn off … bluetooth"), else the last phrase
                // that is one ("good night … good morning" -> "good morning")
                val whole = phrases.joinToString(" ")
                if (isCommand(whole)) whole else phrases.lastOrNull { isCommand(it) } ?: whole
            }
            if (fixedHeard != null && verboseLogs) Log.i(TAG, "Direct: fixed list heard “$fixedHeard” (open: “${phrases.joinToString(" ")}”)")
            val sound = audio.toShortArray()
            if (!isOwnersCommand(text, sound)) return null
            NovaState.engine.value = if (fixedHeard != null) "Vosk (hands-free, command list)" else "Vosk (hands-free)"
            lastHeardOffline = true
            lastUtterance = sound
            return text
        }

        private fun isCommand(text: String): Boolean =
            CommandParser.parseAll(text, prefs.unlockPhrase, routines.names()).none { it is Command.Unknown }

        /**
         * First pass, on the small offline engine's rough words: is this the owner, saying
         * something that sounds like a command? The exact words are checked later, after the
         * phone's recognizer has re-heard the recording ([directAllowed]).
         */
        private fun isOwnersCommand(text: String, sound: ShortArray): Boolean {
            val basic = TextNormalizer.basic(text)
            val commands = CommandParser.parseAll(text, prefs.unlockPhrase, routines.names())
            val commandLike = commands.none { it is Command.Unknown } || COMMAND_START.containsMatchIn(basic)
            if (!commandLike) {
                Log.i(TAG, "Direct: ${if (verboseLogs) "“$text” " else ""}not a command")
                return false
            }
            // One word ("next", "pause") is too easy to say by accident, except while music plays
            val words = basic.split(' ').size
            if (words < 2 && !audioManager.isMusicActive && commands.none { it is Command.RunRoutine }) return false
            val score = runCatching { voiceId.score(sound) }.getOrNull()
            val ok = score != null && score >= voiceId.threshold
            Log.i(TAG, "Direct: ${if (verboseLogs) "“$text” " else ""}voice match ${score?.let { "%.2f".format(it) }} " +
                "(needs %.2f) -> ${if (ok) "run" else "ignore"}".format(voiceId.threshold))
            return ok
        }
    }

    /** How commands start (after "can you please" etc. is removed): play, call, turn, open… */
    private val COMMAND_START = Regex(
        "^(?:play|call|turn|switch|open|set|send|message|text|whatsapp|volume|increase|decrease|reduce|lower|" +
            "raise|pause|stop|next|previous|skip|search|google|what|whats|how|tell|start|shuffle|take|put|show|read|" +
            "remind|mute|unmute|answer|reject|end|cut|good|define|spell|navigate|go|lock|enable|disable|close|" +
            "resume|find|identify|note|add|clear|wake|lights?|torch|flashlight|wifi|bluetooth|hotspot)(?: |$)",
    )

    /**
     * Second pass for no-wake-word mode, on the words the phone's recognizer heard: only real
     * commands, never small talk or unlocking, and only music commands while music plays.
     */
    private fun directAllowed(text: String): Boolean {
        val commands = CommandParser.parseAll(text, prefs.unlockPhrase, routines.names())
        val words = TextNormalizer.basic(text).split(' ').size
        val music = audioManager.isMusicActive
        val musicOnly = commands.all { it is Command.Media || it is Command.Volume || it is Command.Cancel }
        val why = when {
            music && !musicOnly -> "music playing, only music commands"
            words < 2 && !(music && musicOnly) && commands.none { it is Command.RunRoutine } -> "one word"
            // "stop" is fine while music plays: it pauses the music
            music && commands.all { it is Command.Cancel } -> null
            // "the data", "wifi": without a clear on/off, a misheard word must not flip a switch
            commands.any { it is Command.SetFeature && it.state == OnOff.TOGGLE } -> "no on or off said"
            // Small talk ("thank you") may be meant for someone else; unlocking needs the wake word
            commands.any { it is Command.Unknown || it is Command.Unlock || it is Command.Cancel || it is Command.Chat } ->
                "not a command"
            else -> null
        }
        if (why != null) Log.i(TAG, "Direct: ${if (verboseLogs) "“$text” " else ""}ignored ($why)")
        return why == null
    }

    /**
     * The small offline engine often mishears ("play a Tamil song" -> "play some congress and").
     * Give the same recording to the phone's recognizer (on-device first, so it stays on the
     * phone) and use its words instead. Falls back to the offline words.
     */
    private suspend fun recheckOffline(offlineText: String): String {
        val audio = lastUtterance ?: return offlineText
        // A spoken PIN never leaves the phone
        val online = DeviceProfile.isOnline(this) && prefs.engine != NovaPrefs.ENGINE_OFFLINE &&
            !(prefs.voiceUnlock && isLocked)
        val r = runCatching { recognizer.transcribe(audio, onlineAllowed = online) }.getOrNull()
        if (r !is AndroidRecognizer.Result.Text) return offlineText
        val best = bestGuess(r.alternatives) ?: r.text
        Log.i(TAG, "Re-heard by the phone's recognizer" + if (verboseLogs) ": “$offlineText” -> “$best”" else "")
        return best
    }

    /** Reads the mic and runs the wake-word engine until something happens. Runs off the main thread. */
    private suspend fun waitForWake(engine: WakeWordEngine, direct: Boolean = false): Trigger {
        val rec = if (direct) runCatching { vosk.newRecognizer() }.getOrNull() else null
        val fixedRec = if (rec != null) {
            runCatching { vosk.newGrammarRecognizer(HandsFreePhrases.grammarJson(routines.names())) }.getOrNull()
        } else {
            null
        }
        val callRec = if (callListening()) {
            runCatching { vosk.newGrammarRecognizer(CallPhrases.grammarJson()) }
                .onFailure { Log.e(TAG, "Couldn't start listening for call commands", it) }
                .getOrNull()
        } else {
            null
        }
        try {
            return waitForWakeLoop(engine, rec?.let { DirectListener(it, fixedRec) }, direct, callRec?.let { CallListener(it) })
        } finally {
            rec?.close()
            fixedRec?.close()
            callRec?.close()
        }
    }

    /** During a call with the call assistant on: listen for "end the call" etc. without a wake word. */
    private fun callListening(): Boolean = onCall && prefs.callAssistant && prefs.listenDuringCalls

    /** A call command heard without a wake word, waiting to be run. */
    @Volatile private var pendingCall: String? = null

    /**
     * Listens through the whole call for the short call phrases only ([CallPhrases]).
     * Anything else said on the call is ignored.
     */
    private inner class CallListener(private val rec: org.vosk.Recognizer) {
        /** The previous piece heard, kept briefly: "cut" … "the call" often arrive as two pieces. */
        private var previous = ""
        private var previousAt = 0L

        fun step(buf: ShortArray, n: Int, engine: WakeWordEngine): String? {
            if (!rec.acceptWaveForm(buf, n)) return null
            val heard = vosk.text(rec.result)
            if (heard.isBlank()) return null
            val now = SystemClock.elapsedRealtime()
            val joined = if (previous.isNotEmpty() && now - previousAt < 2_500) {
                previous + " " + heard.trim().split(' ').dropWhile { it == "[unk]" }.joinToString(" ")
            } else {
                null
            }
            val phrase = joined?.let { CallPhrases.accept(it) } ?: CallPhrases.accept(heard)
            if (phrase == null) {
                if (verboseLogs) Log.i(TAG, "In call, not a command: “$heard”" + (joined?.let { " (with the piece before: “$it”)" } ?: ""))
                previous = heard.trim().split(' ').filter { it != "[unk]" }.takeLast(3).joinToString(" ")
                previousAt = now
                return null
            }
            previous = ""
            // On speaker the other person's voice reaches the mic too: then it must be the owner's voice
            if (onSpeaker() && voiceId.isEnrolled) {
                val score = runCatching { voiceId.score(engine.recentAudio(2.5)) }.getOrNull()
                val needed = voiceId.threshold - 0.1f
                if (score == null || score < needed) {
                    Log.i(TAG, "In call: “$phrase” ignored, voice match ${score?.let { "%.2f".format(it) }} (needs %.2f)".format(needed))
                    return null
                }
            }
            Log.i(TAG, "In call: “$phrase”")
            return phrase
        }
    }

    @Suppress("DEPRECATION")
    private fun onSpeaker(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            audioManager.isSpeakerphoneOn
        }

    private suspend fun waitForWakeLoop(
        engine: WakeWordEngine,
        directListener: DirectListener?,
        direct: Boolean,
        callListener: CallListener? = null,
    ): Trigger {
        engine.reset()
        val buf = ShortArray(WakeWordEngine.CHUNK)
        var zeros = 0
        var chunks = 0
        var nearMiss = 0f
        var busyShown = false
        val normal = prefs.threshold
        // While music or a video plays out loud, its own sound could contain "Jarvis":
        // ask for a slightly clearer wake word then. Kept small: real voices on the phone
        // score around 0.5, well below the ~1.0 of clean recordings.
        val strict = minOf(normal + 0.08f, 0.9f)
        var threshold = if (audioManager.isMusicActive) strict else normal
        while (currentCoroutineContext().isActive) {
            if (talkRequested) {
                talkRequested = false
                return Trigger.TALK
            }
            if (pendingText != null) return Trigger.TEXT
            if (ringing || NovaState.enrolling.value) return Trigger.PAUSE
            // Give the microphone back the moment another app wants to record
            if (otherAppRecording) return Trigger.PAUSE
            val n = mic.read(buf)
            if (n < 0) return Trigger.MIC_ERROR
            if (n == 0) continue
            chunks++

            var allZero = true
            for (i in 0 until n) if (buf[i].toInt() != 0) { allZero = false; break }
            if (allZero) {
                zeros += n
                if (zeros >= SILENCED_SAMPLES) {
                    if (startedInBackground) return Trigger.BLOCKED
                    if (!busyShown) {
                        busyShown = true
                        if (onCall) Log.w(TAG, "Microphone is silent during this call: Android isn't sharing it with Nova")
                        show(Status.PAUSED, if (onCall) "Can't hear you during this call" else "Microphone is being used by another app")
                    }
                }
            } else {
                zeros = 0
                if (busyShown) {
                    busyShown = false
                    show(Status.LISTENING, listeningText)
                }
                if (chunks > 30) startedInBackground = false // real audio: we do have the mic
            }

            val scores = engine.process(buf, n)
            for ((word, v) in scores) {
                if (v >= threshold) {
                    Log.i(TAG, "Wake word $word (score %.2f, needed %.2f)".format(v, threshold))
                    // Short or everyday wake words: only wake for the owner's voice
                    if (prefs.wakeNeedsVoiceId && voiceId.isEnrolled) {
                        val match = runCatching { voiceId.score(engine.recentAudio(1.6)) }.getOrNull()
                        // A wake word is short, so allow a little less than for full sentences
                        val needed = voiceId.threshold - 0.1f
                        if (match == null || match < needed) {
                            Log.i(TAG, "Wake word ignored: voice match ${match?.let { "%.2f".format(it) }} (needs %.2f)".format(needed))
                            engine.reset()
                            break
                        }
                    }
                    usage.countWake()
                    return Trigger.WAKE
                }
                if (v > nearMiss) nearMiss = v
            }
            directListener?.step(buf, n)?.let { text ->
                pendingDirect = text
                return Trigger.DIRECT
            }
            callListener?.step(buf, n, engine)?.let { phrase ->
                pendingCall = phrase
                return Trigger.CALL
            }
            if (chunks % 25 == 0) {
                // Screen turned off / music started: switch no-wake-word mode on or off
                if (direct != directActive()) return Trigger.PAUSE
                // A call started or ended: start or stop listening for call commands
                if ((callListener != null) != callListening()) return Trigger.PAUSE
                // "Near miss": something like the wake word in the last 2 s, but below the bar
                if (nearMiss >= 0.15f) Log.i(TAG, "Near miss: score %.2f (needed %.2f)".format(nearMiss, threshold))
                nearMiss = 0f
                if (pauseReason() != null) return Trigger.PAUSE
                threshold = if (audioManager.isMusicActive) strict else normal
            }
        }
        return Trigger.PAUSE
    }

    // ── One command ─────────────────────────────────────────────────────────

    /** How a command arrived: after the wake word, in the 15 s follow-on window, or with no wake word. */
    private enum class Mode { NORMAL, FOLLOW_ON, DIRECT }

    /** Commands that could cause trouble if misheard; in no-wake-word mode Nova lets you cancel them first. */
    private fun risky(c: Command) = c is Command.Call || c is Command.Message ||
        (c is Command.CallControl && c.action == InCallAction.END) || c is Command.Camera

    private val cancelWords = Regex("\\b(no|cancel|stop|wait|dont|never mind)\\b")

    /**
     * Handles one command: listens for it, or uses [typed] text (a "Try" button, a follow-up
     * answer, or a sentence heard in no-wake-word mode) instead. [depth] limits follow-up
     * questions to two in a row. Returns true when Nova actually did something.
     */
    private suspend fun handleCommand(
        engine: WakeWordEngine?,
        typed: String? = null,
        depth: Int = 0,
        mode: Mode = Mode.NORMAL,
        windowMs: Long = 15_000,
    ): Boolean {
        val quiet = mode != Mode.NORMAL // follow-on / direct: never complain about things that weren't commands
        if (mode != Mode.FOLLOW_ON) usage.countCommand()
        show(
            if (typed == null) Status.HEARING else Status.THINKING,
            when {
                typed != null -> safeText(typed)
                mode == Mode.FOLLOW_ON -> "Still listening…"
                else -> "Listening…"
            },
        )
        NovaState.partial.value = ""
        inCommand = true
        val focus = requestFocus()
        try {
            val heardRaw = typed ?: when (mode) {
                // Follow-on: no beep. Google's offline pack when it works (accurate), else the small engine
                Mode.FOLLOW_ON -> listenFollowOn(windowMs)
                else -> {
                    beep(); recognize()
                }
            }
            NovaState.partial.value = ""
            val heard = if (typed == null && !heardRaw.isNullOrBlank()) {
                val better = if (lastHeardOffline && !onCall) recheckOffline(heardRaw) else heardRaw
                // Still the offline engine's words: at least check a heard name against the contacts
                if (better == heardRaw) checkContactName(heardRaw) else better
            } else {
                heardRaw
            }
            if (heard.isNullOrBlank()) {
                if (!quiet) {
                    Log.i(TAG, "Nothing heard (${NovaState.engine.value})")
                    show(Status.LISTENING, "Didn't hear anything")
                    if (onCall && lastUtteranceWasSilent()) {
                        // Android gave Nova pure silence: it mutes other apps' microphones during calls
                        Log.i(TAG, "Microphone muted by Android during the call")
                        NovaState.addExchange("(no sound)", "Android muted Nova's microphone during the call")
                        speaker.speak("The phone is muting my microphone during this call. Open Nova's screen and tap the mic.")
                    }
                }
                return false
            }
            val commands = CommandParser.parseAll(heard, prefs.unlockPhrase, routines.names())
            if (quiet && commands.all { it is Command.Unknown || it is Command.Cancel }) {
                // Probably just talking, not a command: ignore it silently
                if (verboseLogs) Log.i(TAG, "($mode) not a command: “${safeText(heard)}”")
                return false
            }
            if (quiet && commands == listOf(Command.Media(MediaAction.PLAY)) && player.current == null &&
                TextNormalizer.basic(heard) in setOf("play", "resume")
            ) {
                // Without a wake word a lone "play" is usually a cut-off "play a Tamil song": don't
                // resume whatever app played last (it started Spotify once)
                Log.i(TAG, "($mode) lone “play” ignored")
                return false
            }
            if (quiet && commands.any { it is Command.SetFeature && it.state == OnOff.TOGGLE }) {
                // A switch flipped by a misheard word turned mobile data off once: needs "on" or "off"
                Log.i(TAG, "($mode) switch without on/off ignored")
                return false
            }
            if (mode == Mode.FOLLOW_ON) usage.countCommand()
            // During a call only call commands are allowed: the other person might be on speaker
            if (onCall && commands.any { !CommandParser.allowedDuringCall(it) }) {
                Log.i(TAG, "Ignored during call: ${commands.map { it::class.simpleName }}")
                NovaState.addExchange(safeText(heard), "Only call commands work during a call")
                speaker.speak("During a call I can end it, record it, mute you, or switch the speaker.")
                return false
            }
            // Voice unlock also needs the owner's voice when a voice profile exists
            if (commands.any { it is Command.Unlock } && voiceId.isEnrolled && prefs.unlockNeedsVoiceId && isLocked) {
                val audio = lastUtterance
                val score = audio?.let { withContext(Dispatchers.Default) { runCatching { voiceId.score(it) }.getOrNull() } }
                Log.i(TAG, "Voice unlock: voice match ${score?.let { "%.2f".format(it) }} (needs %.2f)".format(voiceId.threshold))
                if (score == null || score < voiceId.threshold) {
                    val failures = prefs.unlockFailures + 1
                    prefs.unlockFailures = failures
                    if (failures >= NovaPrefs.MAX_UNLOCK_FAILURES) prefs.unlockBlocked = true
                    NovaState.addExchange("${prefs.unlockPhrase} ••••", "Voice not recognised")
                    speaker.speak("That didn't sound like you, so I won't unlock.")
                    return false
                }
            }
            // No wake word: give a moment to cancel anything that could cause trouble if misheard
            // (Also in the follow-on window: no wake word was said there either)
            if (mode != Mode.NORMAL && commands.any(::risky)) {
                mic.close()
                speaker.speak("${heard.replaceFirstChar { it.uppercase() }}?")
                val answer = listenOffline(silenceStartMs = 1_500)
                if (answer != null && cancelWords.containsMatchIn(TextNormalizer.basic(answer))) {
                    NovaState.addExchange(safeText(heard), "Cancelled")
                    speaker.speak("Cancelled.")
                    return false
                }
            }
            // Never show a spoken PIN: not on screen, in history, or in the lock-screen notification
            val shownHeard = if (commands.any { it is Command.Unlock }) {
                "${prefs.unlockPhrase} ••••"
            } else {
                safeText(heard)
            }
            show(Status.THINKING, shownHeard)
            // Test builds log what was heard to help debugging; release builds only log command types
            if (verboseLogs) {
                Log.i(TAG, "($mode) Heard “$shownHeard” via ${NovaState.engine.value} -> $commands")
            } else {
                Log.i(TAG, "($mode) Commands: ${commands.map { it::class.simpleName }}")
            }
            val replies = commands.map { cmd ->
                try {
                    executor.execute(cmd)
                } catch (e: Exception) {
                    Log.e(TAG, "Command failed: $cmd", e)
                    Reply("Sorry, that didn't work.")
                }
            }
            // Confirmations ("Bluetooth on") follow the user's "After a command" choice;
            // answers, questions and problems are always spoken.
            val after = prefs.afterCommand
            val speech = replies
                .filter { !it.confirmation || after == NovaPrefs.AFTER_VOICE }
                .map { it.speech }.filter { it.isNotBlank() }.joinToString(" ")
            val toneOnly = speech.isBlank() && after == NovaPrefs.AFTER_TONE && replies.any { it.confirmation }
            val display = safeText(replies.map { it.display }.filter { it.isNotBlank() }.joinToString(" "))
            NovaState.addExchange(shownHeard, display.ifBlank { "Done" })
            if (verboseLogs) Log.i(TAG, "Replied “$display”")
            if (speech.isNotBlank() && prefs.speakReplies) {
                mic.close() // so Nova doesn't hear itself
                show(Status.SPEAKING, display)
                speaker.speak(speech)
            } else if (toneOnly) {
                doneTone()
            }
            for (r in replies) r.after?.let { runCatching { it() } }

            // Nova asked a question ("For how long?"): listen for the answer, no wake word needed
            val followUp = replies.firstNotNullOfOrNull { it.followUp }
            if (followUp != null && depth < 2 && speech.isNotBlank() && prefs.speakReplies) {
                show(Status.HEARING, "Listening for your answer…")
                beep()
                val answer = recognize()
                if (!answer.isNullOrBlank() && CommandParser.parse(answer) != Command.Cancel) {
                    val next = CommandParser.resolveFollowUp(followUp, answer, prefs.unlockPhrase, routines.names())
                    handleCommand(engine, next, depth + 1)
                }
            }
            return commands.any { it !is Command.Unknown && it !is Command.Cancel && it !is Command.Chat }
        } finally {
            abandonFocus(focus)
            engine?.reset()
            commandEndedAt = SystemClock.elapsedRealtime()
            inCommand = false
        }
    }

    /**
     * After a command, keep listening for up to 15 s (no wake word) so several commands can
     * be given in a row. Every command that works restarts the 15 s.
     */
    private suspend fun followOnWindow(engine: WakeWordEngine?) {
        if (!prefs.followOn || onCall || ringing) return
        var until = SystemClock.elapsedRealtime() + 15_000
        while (currentCoroutineContext().isActive) {
            val left = until - SystemClock.elapsedRealtime()
            if (left < 1_500 || pauseReason() != null || pendingText != null || ringing) break
            val acted = handleCommand(engine, mode = Mode.FOLLOW_ON, windowMs = left)
            if (acted) until = SystemClock.elapsedRealtime() + 15_000
        }
    }

    // ── Incoming calls ──────────────────────────────────────────────────────

    /**
     * The phone is ringing: say who it is, then listen (no wake word needed) for
     * "answer", "reject", "reject and text I'll call back" or "silence".
     */
    private suspend fun handleIncomingCall() {
        mic.close()
        inCommand = true
        val number = ringingNumber
        val name = calls.callerName(number)
        val canAnswer = calls.canAnswer()
        Log.i(TAG, "Incoming call announced (answer by voice: $canAnswer)")
        // Turn the ringtone down so the announcement and your answer can be heard
        val ringBefore = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val lowered = try {
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxOf(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_RING) / 5), 0)
            true
        } catch (_: Exception) {
            false
        }
        try {
            show(Status.SPEAKING, "${safeText(name)} is calling")
            speaker.speak("$name is calling." + if (canAnswer) " Answer or reject?" else "")
            if (!canAnswer) return
            for (attempt in 0..1) {
                if (!ringing) return
                show(Status.HEARING, "Answer or reject?")
                beep()
                // Only answer / reject / silence: starts at once and works over the ringtone
                // (open listening heard nothing for 12 s on a real call). Free text as a last try.
                val heard = listenWhileRinging(20_000) ?: if (attempt == 1 && ringing) recognize() else null
                if (!ringing) return
                val wantsText = heard != null && heard in RingPhrases.REJECT_AND_SEND
                val parsed = heard?.let { CallAnswer.parse(it) }
                Log.i(TAG, if (verboseLogs) "During ringing heard “$heard” -> $parsed" else "During ringing -> ${parsed?.let { it::class.simpleName }}")
                when (val a = parsed) {
                    CallAnswer.Answer -> {
                        val ok = calls.answer() || NovaAccessibilityService.instance?.clickByLabel(listOf("answer", "accept")) == true
                        NovaState.addExchange(heard.orEmpty(), if (ok) "Answered" else "Couldn't answer")
                        return
                    }
                    is CallAnswer.Reject -> {
                        val ok = calls.reject() || NovaAccessibilityService.instance?.clickByLabel(listOf("decline", "reject")) == true
                        // "reject and send": ask what to send now that the ringing has stopped
                        val message = a.message ?: if (wantsText) {
                            speaker.speak("What should I send?")
                            beep()
                            recognize()?.trim()?.takeIf { it.isNotEmpty() }
                        } else {
                            null
                        }
                        val texted = message?.let { msg ->
                            var to = number
                            // Number not passed while ringing? The rejected call is in the call log a moment later
                            repeat(8) { if (to == null) { delay(250); to = calls.lastCallNumber() } }
                            calls.text(to, msg)
                        } ?: false
                        NovaState.addExchange(heard.orEmpty(), buildString {
                            append(if (ok) "Rejected" else "Couldn't reject")
                            if (message != null) append(if (texted) " and texted “$message”" else " (couldn't send the text)")
                        })
                        return
                    }
                    CallAnswer.Silence -> {
                        runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0) }
                        return
                    }
                    null -> if (attempt == 0) speaker.speak("Say answer or reject.")
                }
            }
        } finally {
            if (lowered && ringing) runCatching { audioManager.setStreamVolume(AudioManager.STREAM_RING, ringBefore, 0) }
            inCommand = false
        }
    }

    /** While the phone rings: the first answer / reject / silence phrase heard (offline), or null. */
    private suspend fun listenWhileRinging(timeoutMs: Long): String? = withContext(Dispatchers.Default) {
        if (!mic.open()) return@withContext null
        val rec = runCatching { vosk.newGrammarRecognizer(RingPhrases.grammarJson()) }.getOrNull() ?: return@withContext null
        try {
            val buf = ShortArray(1600)
            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (currentCoroutineContext().isActive && ringing && SystemClock.elapsedRealtime() < end) {
                val n = mic.read(buf)
                if (n <= 0) break
                if (rec.acceptWaveForm(buf, n)) {
                    val heard = vosk.text(rec.result)
                    RingPhrases.accept(heard)?.let { return@withContext it }
                    if (verboseLogs && heard.isNotBlank()) Log.i(TAG, "Ringing, not an answer: “$heard”")
                }
            }
            RingPhrases.accept(vosk.text(rec.finalResult))
        } finally {
            rec.close()
        }
    }

    /**
     * Picks the best speech engine right now: the phone's recognizer (best with names) when
     * online or when it has an offline pack, otherwise the bundled Vosk model.
     */
    private suspend fun recognize(): String? {
        val online = DeviceProfile.isOnline(this)
        // While locked with voice unlock on, the user may say their PIN: keep it on the phone
        val pinMayBeSpoken = prefs.voiceUnlock && isLocked
        // During a call: use Nova's own microphone stream (the one that just heard the wake word)
        val offlineOnly = pinMayBeSpoken || onCall || prefs.engine == NovaPrefs.ENGINE_OFFLINE ||
            SystemClock.elapsedRealtime() < preferOfflineUntil ||
            !recognizer.isAvailable() ||
            (!online && !recognizer.isOnDeviceAvailable())
        if (offlineOnly) return listenOffline()

        lastHeardOffline = false
        mic.close() // the phone's recognizer needs the microphone to itself
        // Google's offline pack when it's there: quicker than going online and nothing leaves the phone
        val packFirst = recognizer.offlinePackOk != false || !online
        NovaState.engine.value = if (packFirst) "Phone recognizer (offline pack)" else "Phone recognizer (online)"
        var result = recognizer.listen(offline = packFirst) { NovaState.partial.value = safeText(it) }
        if (packFirst && online && result is AndroidRecognizer.Result.Failed) {
            NovaState.engine.value = "Phone recognizer (online)"
            result = recognizer.listen(offline = false) { NovaState.partial.value = safeText(it) }
        }
        return when (val r = result) {
            is AndroidRecognizer.Result.Text -> {
                phoneRecognizerMisses = 0
                val best = bestGuess(r.alternatives) ?: r.text
                if (verboseLogs && best != r.text) Log.i(TAG, "Heard “${r.text}”, used “$best”")
                best
            }
            AndroidRecognizer.Result.NoSpeech -> {
                // On some phones the system recognizer hears nothing while Nova is in the
                // background. If that keeps happening, use the offline engine for a while.
                // (Only briefly: the offline engine is much less accurate.)
                if (++phoneRecognizerMisses >= 3) {
                    phoneRecognizerMisses = 0
                    preferOfflineUntil = SystemClock.elapsedRealtime() + 5 * 60_000L
                }
                null
            }
            is AndroidRecognizer.Result.Failed -> {
                Log.i(TAG, "Phone recognizer failed (error ${r.code}); switching to the offline engine")
                speaker.speak("Say that again.")
                beep()
                listenOffline()
            }
        }
    }

    /**
     * The 15 s after a command, no wake word. Uses Google's offline speech pack when it works:
     * the small offline engine misheard too much ("turn off … bluetooth" -> "turn off").
     */
    private suspend fun listenFollowOn(windowMs: Long): String? {
        val pinMayBeSpoken = prefs.voiceUnlock && isLocked
        if (recognizer.offlinePackOk != true || onCall || pinMayBeSpoken || prefs.engine == NovaPrefs.ENGINE_OFFLINE) {
            return listenOffline(silenceStartMs = windowMs)
        }
        NovaState.engine.value = "Phone recognizer (offline pack)"
        lastHeardOffline = false
        mic.close()
        return when (val r = recognizer.listen(offline = true) { NovaState.partial.value = safeText(it) }) {
            is AndroidRecognizer.Result.Text -> bestGuess(r.alternatives) ?: r.text
            AndroidRecognizer.Result.NoSpeech -> null
            is AndroidRecognizer.Result.Failed -> listenOffline(silenceStartMs = windowMs)
        }
    }

    /** True when the last offline listen got digital silence (all samples ~0), not just a quiet room. */
    private fun lastUtteranceWasSilent(): Boolean {
        val a = lastUtterance ?: return false
        if (a.size < 8_000) return false
        return a.all { it in -2..2 }
    }

    /** True when the last sentence came from the offline engine (weak with names). */
    @Volatile private var lastHeardOffline = false

    private val contacts by lazy { com.assistant.nova.core.action.ContactsRepo(this) }

    /**
     * The offline engine doesn't know the user's contacts, so "call dad" can come out as
     * "call that". When it heard a call, listen to the same recording again allowing only
     * "call <a contact>" - "that" isn't a contact, "dad" is.
     */
    private suspend fun checkContactName(heard: String): String {
        if (!lastHeardOffline) return heard
        val call = CommandParser.parseAll(heard, prefs.unlockPhrase, routines.names()).singleOrNull() as? Command.Call
            ?: return heard
        if (call.who.isNotEmpty() && call.who.all { it.isDigit() }) return heard // a number
        val audio = lastUtterance ?: return heard
        val names = withContext(Dispatchers.IO) { runCatching { contacts.all() }.getOrDefault(emptyList()) }
            .flatMap { c ->
                val full = c.name.lowercase().replace(Regex("[^a-z ]"), " ").trim().replace(Regex("\\s+"), " ")
                listOf(full, full.substringBefore(' '))
            }
            .filter { it.length >= 3 }
            .distinct()
            .take(600)
        if (names.isEmpty()) return heard
        val grammar = (names.map { "call $it" } + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }
        val again = runCatching { vosk.recognizeWith(grammar, audio) }.getOrNull()?.firstOrNull() ?: return heard
        val name = again.removePrefix("call ").trim()
        if (!again.startsWith("call ") || "[unk]" in again || name.length < 3) return heard
        // Must really be one of the contacts (Vosk skips name words it doesn't know)
        if (contacts.find(name, 0.85) == null) return heard
        if (again != heard.lowercase()) Log.i(TAG, "Name check: “${safeText(heard)}” -> “${safeText(again)}”")
        return again
    }

    /** "turn off bluetooth" yes; "turn off", "set a timer", "call" not yet (more may be coming). */
    private fun isWholeCommand(text: String): Boolean =
        CommandParser.parseAll(text, prefs.unlockPhrase, routines.names()).none { CommandParser.isIncomplete(it) }

    /** The first of the recognizer's guesses that is a real command (else the top guess). */
    private fun bestGuess(guesses: List<String>): String? =
        CommandParser.bestOf(guesses, prefs.unlockPhrase, routines.names())

    /** Audio of the last offline-recognized sentence, kept briefly to check the speaker's voice. */
    private var lastUtterance: ShortArray? = null

    private suspend fun listenOffline(silenceStartMs: Long = 4_500): String? {
        NovaState.engine.value = "Vosk (offline)"
        lastHeardOffline = true
        if (!mic.open()) return null
        if (!vosk.isLoaded) show(Status.HEARING, "Loading offline speech…")
        val audio = ArrayList<Short>()
        return try {
            vosk.listen(
                mic,
                onPartial = { NovaState.partial.value = safeText(it, aggressive = prefs.voiceUnlock) },
                silenceStartMs = silenceStartMs,
                pick = ::bestGuess,
                isComplete = ::isWholeCommand,
                capture = { b, n -> if (audio.size < 16_000 * 10) for (i in 0 until n) audio += b[i] },
            ).also { lastUtterance = audio.toShortArray() }
        } catch (e: Exception) {
            Log.e(TAG, "Offline recognition failed", e)
            null
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private val onCall: Boolean
        get() = audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun pauseReason(): String? {
        if (NovaState.enrolling.value) return "learning your voice"
        when (audioManager.mode) {
            // During a call Nova keeps listening, but only for call commands (see handleCommand)
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION ->
                return if (prefs.callAssistant && prefs.listenDuringCalls) null else "on a call"
            AudioManager.MODE_RINGTONE -> return "phone is ringing"
        }
        if (othersRecording(audioManager.activeRecordingConfigurations)) {
            otherAppRecording = true
            return "another app is using the microphone"
        }
        otherAppRecording = false
        if (prefs.pauseScreenOff && !getSystemService(PowerManager::class.java).isInteractive) return "screen is off"
        if (prefs.pauseLowBattery) {
            val bm = getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (!bm.isCharging && level in 1..14) return "battery below 15%"
        }
        return null
    }

    private suspend fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            delay(180)
            tone.release()
        } catch (_: Exception) {
        }
    }

    /** Short, soft two-note "done" sound for the "Short tone" setting. */
    private suspend fun doneTone() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            delay(200)
            tone.release()
        } catch (_: Exception) {
        }
    }

    /** Lowers music volume while Nova listens and speaks. */
    private fun requestFocus(): AudioFocusRequest? {
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        return if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) req else null
    }

    private fun abandonFocus(req: AudioFocusRequest?) {
        req?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
    }
}
