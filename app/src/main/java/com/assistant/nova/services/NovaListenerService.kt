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
import com.assistant.nova.core.command.InCallAction
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

    private enum class Trigger { WAKE, TALK, TEXT, DIRECT, PAUSE, MIC_ERROR, BLOCKED }

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
        }
    }

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
                listeningText = "Say “${models.keys.joinToString(" or ") { WakeWords.label(it) }}”" +
                    if (direct) ", or just say a command" else ""
                show(Status.LISTENING, listeningText)

                when (withContext(Dispatchers.Default) { waitForWake(engine, direct) }) {
                    Trigger.WAKE, Trigger.TALK -> {
                        if (handleCommand(engine)) followOnWindow(engine)
                    }
                    Trigger.DIRECT -> {
                        val text = pendingDirect
                        pendingDirect = null
                        if (text != null && handleCommand(engine, text, mode = Mode.DIRECT)) followOnWindow(engine)
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
        if (!prefs.directMode || !voiceId.isEnrolled || audioManager.isMusicActive || onCall) return false
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
    private inner class DirectListener(private val rec: Recognizer) {
        private val preroll = ArrayDeque<ShortArray>()
        private val audio = ArrayList<Short>()
        private var speaking = false
        private var quietMs = 0
        private var speechMs = 0

        fun step(buf: ShortArray, n: Int): String? {
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            val rms = sqrt(sum / n)
            val chunk = buf.copyOf(n)
            if (!speaking) {
                preroll.addLast(chunk); if (preroll.size > 4) preroll.removeFirst()
                if (rms < 350) return null
                speaking = true; quietMs = 0; speechMs = 0; audio.clear()
                for (c in preroll) { rec.acceptWaveForm(c, c.size); c.forEach { audio += it } }
                preroll.clear()
                return null
            }
            rec.acceptWaveForm(chunk, n)
            if (audio.size < 16_000 * 8) chunk.forEach { audio += it }
            speechMs += 80
            quietMs = if (rms < 250) quietMs + 80 else 0
            if (quietMs < 800 && speechMs < 7_000) return null
            // Sentence finished
            speaking = false
            // Top guess only: picking "any guess that is a command" would make room chatter trigger Nova
            val text = vosk.text(rec.finalResult)
            return if (text.isNotBlank() && isOwnersCommand(text, audio.toShortArray())) text else null
        }

        private fun isOwnersCommand(text: String, sound: ShortArray): Boolean {
            if (TextNormalizer.basic(text).split(' ').size < 2) return false // "next", "pause"… too easy to say by accident
            val commands = CommandParser.parseAll(text, prefs.unlockPhrase, routines.names())
            // Small talk ("thank you") is never acted on without the wake word: it may be meant for someone else
            if (commands.any { it is Command.Unknown || it is Command.Unlock || it is Command.Cancel || it is Command.Chat }) return false
            val score = runCatching { voiceId.score(sound) }.getOrNull()
            val ok = score != null && score >= voiceId.threshold
            Log.i(TAG, "Direct: ${if (verboseLogs) "“$text” " else ""}voice match ${score?.let { "%.2f".format(it) }} " +
                "(needs %.2f) -> ${if (ok) "run" else "ignore"}".format(voiceId.threshold))
            return ok
        }
    }

    /** Reads the mic and runs the wake-word engine until something happens. Runs off the main thread. */
    private suspend fun waitForWake(engine: WakeWordEngine, direct: Boolean = false): Trigger {
        val rec = if (direct) runCatching { vosk.newRecognizer() }.getOrNull() else null
        try {
            return waitForWakeLoop(engine, rec?.let { DirectListener(it) }, direct)
        } finally {
            rec?.close()
        }
    }

    private suspend fun waitForWakeLoop(engine: WakeWordEngine, directListener: DirectListener?, direct: Boolean): Trigger {
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
            if (chunks % 25 == 0) {
                // Screen turned off / music started: switch no-wake-word mode on or off
                if (direct != directActive()) return Trigger.PAUSE
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
            val heard = typed ?: when (mode) {
                // Follow-on uses the silent offline recognizer: no beep, no Google sound every few seconds
                Mode.FOLLOW_ON -> listenOffline(silenceStartMs = windowMs)
                else -> {
                    beep(); recognize()
                }
            }
            NovaState.partial.value = ""
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
            if (mode == Mode.DIRECT && commands.any(::risky)) {
                mic.close()
                speaker.speak("${heard.replaceFirstChar { it.uppercase() }}?")
                val answer = listenOffline(silenceStartMs = 2_500)
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
                val heard = recognize()
                if (!ringing) return
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
                        val texted = a.message?.let { msg ->
                            var to = number
                            // Number not passed while ringing? The rejected call is in the call log a moment later
                            repeat(8) { if (to == null) { delay(250); to = calls.lastCallNumber() } }
                            calls.text(to, msg)
                        } ?: false
                        NovaState.addExchange(heard.orEmpty(), buildString {
                            append(if (ok) "Rejected" else "Couldn't reject")
                            if (a.message != null) append(if (texted) " and texted “${a.message}”" else " (couldn't send the text)")
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

        NovaState.engine.value = if (online) "Phone recognizer (online)" else "Phone recognizer (offline)"
        mic.close() // the phone's recognizer needs the microphone to itself
        return when (val r = recognizer.listen(offline = !online) { NovaState.partial.value = safeText(it) }) {
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

    /** True when the last offline listen got digital silence (all samples ~0), not just a quiet room. */
    private fun lastUtteranceWasSilent(): Boolean {
        val a = lastUtterance ?: return false
        if (a.size < 8_000) return false
        return a.all { it in -2..2 }
    }

    /** The first of the recognizer's guesses that is a real command (else the top guess). */
    private fun bestGuess(guesses: List<String>): String? =
        CommandParser.bestOf(guesses, prefs.unlockPhrase, routines.names())

    /** Audio of the last offline-recognized sentence, kept briefly to check the speaker's voice. */
    private var lastUtterance: ShortArray? = null

    private suspend fun listenOffline(silenceStartMs: Long = 4_500): String? {
        NovaState.engine.value = "Vosk (offline)"
        if (!mic.open()) return null
        if (!vosk.isLoaded) show(Status.HEARING, "Loading offline speech…")
        val audio = ArrayList<Short>()
        return try {
            vosk.listen(
                mic,
                onPartial = { NovaState.partial.value = safeText(it, aggressive = prefs.voiceUnlock) },
                silenceStartMs = silenceStartMs,
                pick = ::bestGuess,
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
