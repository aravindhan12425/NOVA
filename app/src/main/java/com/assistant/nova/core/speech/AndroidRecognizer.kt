package com.assistant.nova.core.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/**
 * The phone's own speech recognizer (Google on OnePlus). Best accuracy, especially for
 * contact names and Indian accents. Works offline too when the offline English pack is installed.
 */
class AndroidRecognizer(private val context: Context) {

    sealed interface Result {
        /** [text] = top guess; [alternatives] = all guesses, best first (includes [text]). */
        data class Text(val text: String, val alternatives: List<String> = listOf(text)) : Result
        data object NoSpeech : Result
        /** The recognizer couldn't run (no network, no offline pack, busy…): use Vosk instead. */
        data class Failed(val code: Int) : Result
    }

    /** "en-IN": understands Indian accents and names far better than the phone's default (often US English). */
    var language: String = "en-IN"

    companion object {
        private const val TAG = "NovaRecognizer"

        /** Words Nova expects, so the recognizer leans towards them (on-device recognizer, Android 13+). */
        val HINTS = listOf(
            "Nova", "Jarvis", "Wi-Fi", "Bluetooth", "hotspot", "torch", "flashlight", "mobile data", "airplane mode",
            "do not disturb", "volume", "brightness", "alarm", "timer", "speaker", "mute", "record the call",
            "end the call", "answer", "reject", "play", "pause", "next song", "previous song", "WhatsApp", "message",
            "Tamil", "Hindi", "English", "Spotify", "liked songs",
        )
    }

    /**
     * Google's recognizer, named explicitly. When Nova is the phone's assistant, the
     * "default" recognizer becomes Nova's own offline one, so asking for the default would
     * make Nova talk to itself.
     */
    private val googleRecognizer: ComponentName? by lazy {
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        (listOfNotNull(preferredPackage) + listOf("com.google.android.tts", "com.google.android.googlequicksearchbox")).firstNotNullOfOrNull { pkg ->
            services.firstOrNull { it.serviceInfo.packageName == pkg }
                ?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        }
    }

    /** Test hook: try this recognizer package first (set before first use). */
    var preferredPackage: String? = null

    fun isAvailable(): Boolean = googleRecognizer != null || SpeechRecognizer.isRecognitionAvailable(context)

    fun isOnDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun create(onDevice: Boolean): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            googleRecognizer?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
                ?: SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun baseIntent(offline: Boolean, lang: String = language) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
        // Several guesses: Nova picks the first one that is a real command
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Finish sooner after the user stops talking (recognizers may round this up)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(HINTS))
        }
    }

    /** Runs one recognition with [sr] and [intent]; [started] is called once it's listening. */
    private suspend fun recognizeOnce(
        sr: SpeechRecognizer,
        intent: Intent,
        timeoutMs: Long,
        onPartial: (String) -> Unit,
        trace: Boolean = false,
        started: () -> Unit = {},
    ): Result = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Result> { cont ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    if (trace) Log.i(TAG, "  results: " + results?.keySet()?.joinToString { k -> "$k=${results.get(k)}" })
                    val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        .filter { it.isNotBlank() }
                    if (cont.isActive) cont.resume(if (all.isEmpty()) Result.NoSpeech else Result.Text(all.first(), all))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
                }

                override fun onError(error: Int) {
                    if (trace) Log.i(TAG, "  recognizer error $error")
                    val r = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Result.NoSpeech
                        else -> Result.Failed(error)
                    }
                    if (cont.isActive) cont.resume(r)
                }

                override fun onReadyForSpeech(params: Bundle?) { if (trace) Log.i(TAG, "  ready") }
                override fun onBeginningOfSpeech() { if (trace) Log.i(TAG, "  speech began") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (trace) Log.i(TAG, "  speech ended") }
                override fun onEvent(eventType: Int, params: Bundle?) { if (trace) Log.i(TAG, "  event $eventType") }
            })
            sr.startListening(intent)
            started()
        }
    } ?: Result.NoSpeech.also { if (trace) Log.i(TAG, "  no answer within ${timeoutMs / 1000} s") }

    private val speechPrefs by lazy { context.getSharedPreferences("nova_speech", Context.MODE_PRIVATE) }

    /**
     * Whether one of Google's offline English packs works (null = not tried yet). With it,
     * Nova hears accurately with no internet and nothing leaves the phone.
     */
    val offlinePackOk: Boolean?
        get() = if (speechPrefs.contains("pack_ok")) speechPrefs.getBoolean("pack_ok", false) else null

    /** The offline pack that worked last time, e.g. "en-GB" when English (India) isn't downloaded. */
    val offlineLanguage: String? get() = speechPrefs.getString("offline_lang", null)

    /**
     * Listens on the microphone for one sentence with Google's engine. [offline] = use its
     * downloaded pack (fast, private). (Android's own on-device service isn't used: on some
     * phones it has no languages at all and just returns nothing.)
     */
    suspend fun listen(offline: Boolean, onPartial: (String) -> Unit): Result = withContext(Dispatchers.Main) {
        if (!offline) return@withContext listenOnce(false, language, onPartial)
        // Offline: the pack that worked last time first, then English (India), UK, US. A missing
        // pack answers "language unavailable" within ~0.1 s, so trying the next one costs nothing.
        for (lang in listOfNotNull(offlineLanguage, language, "en-GB", "en-US").distinct()) {
            var r = listenOnce(true, lang, onPartial)
            // Google's service drops the connection if asked again straight away: wait and retry once
            if (r is Result.Failed && r.code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                kotlinx.coroutines.delay(400)
                r = listenOnce(true, lang, onPartial)
            }
            Log.i(TAG, "Offline $lang: ${r::class.simpleName}${if (r is Result.Failed) " ${r.code}" else ""}")
            if (r is Result.Failed && r.code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                kotlinx.coroutines.delay(250)
                continue
            }
            if (r !is Result.Failed && (offlinePackOk != true || offlineLanguage != lang)) {
                speechPrefs.edit().putBoolean("pack_ok", true).putString("offline_lang", lang).apply()
                Log.i(TAG, "Offline speech pack in use: $lang")
            }
            return@withContext r
        }
        if (offlinePackOk != false) {
            speechPrefs.edit().putBoolean("pack_ok", false).remove("offline_lang").apply()
            Log.i(TAG, "No offline English speech pack on this phone")
        }
        Result.Failed(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
    }

    private suspend fun listenOnce(offline: Boolean, lang: String, onPartial: (String) -> Unit): Result {
        val sr = create(onDevice = false)
        return try {
            recognizeOnce(sr, baseIntent(offline, lang).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true), 15_000, onPartial)
        } finally {
            sr.destroy()
        }
    }

    /**
     * Whether the phone's on-device recognizer has [language] installed (Android 13+).
     * Null if that can't be checked. Without it, recordings can't be re-checked on the phone.
     */
    suspend fun hasOnDevicePack(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isOnDeviceAvailable()) return null
        return withContext(Dispatchers.Main) {
            val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            try {
                withTimeoutOrNull(3_000) {
                    suspendCancellableCoroutine<Boolean?> { cont ->
                        sr.checkRecognitionSupport(
                            baseIntent(offline = true),
                            context.mainExecutor,
                            object : android.speech.RecognitionSupportCallback {
                                override fun onSupportResult(support: android.speech.RecognitionSupport) {
                                    val installed = support.installedOnDeviceLanguages.map { it.lowercase() }
                                    Log.i(TAG, "On-device speech packs: installed=${support.installedOnDeviceLanguages} " +
                                        "pending=${support.pendingOnDeviceLanguages} supported=${support.supportedOnDeviceLanguages.size}")
                                    if (cont.isActive) cont.resume(language.lowercase() in installed)
                                }

                                override fun onError(error: Int) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            },
                        )
                    }
                }
            } finally {
                sr.destroy()
            }
        }
    }

    /** Which offline languages each engine reports (for diagnosis). Android 13+. */
    suspend fun supportReport(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "needs Android 13"
        val out = StringBuilder()
        for (onDevice in listOf(true, false)) {
            if (onDevice && !isOnDeviceAvailable()) continue
            val name = if (onDevice) "Android on-device" else "Google ($googleRecognizer)"
            val line = withContext(Dispatchers.Main) {
                val sr = create(onDevice)
                try {
                    withTimeoutOrNull(4_000) {
                        suspendCancellableCoroutine<String> { cont ->
                            sr.checkRecognitionSupport(
                                baseIntent(offline = true), context.mainExecutor,
                                object : android.speech.RecognitionSupportCallback {
                                    override fun onSupportResult(s: android.speech.RecognitionSupport) {
                                        if (cont.isActive) cont.resume(
                                            "installed=${s.installedOnDeviceLanguages} pending=${s.pendingOnDeviceLanguages} " +
                                                "supported=${s.supportedOnDeviceLanguages.take(12)} online=${s.onlineLanguages.size}",
                                        )
                                    }
                                    override fun onError(error: Int) { if (cont.isActive) cont.resume("error $error") }
                                },
                            )
                        }
                    } ?: "no answer"
                } finally {
                    sr.destroy()
                }
            }
            out.append(name).append(": ").append(line).appendLine()
        }
        return out.toString()
    }

    /** Asks Android to download the on-device speech pack for [language] (Android 13+). */
    fun downloadOnDevicePack(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isOnDeviceAvailable()) return false
        val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return try {
            sr.triggerModelDownload(baseIntent(offline = true))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the speech pack download", e)
            false
        } finally {
            sr.destroy()
        }
    }

    // Engines that turned out not to accept recordings, so they aren't tried every time
    @Volatile private var onDeviceClips: Boolean? = null
    @Volatile private var onlineClips: Boolean? = null
    @Volatile private var packMissingUntil = 0L

    /** Off by default: see [transcribe]. The debug test hook turns it on to try a phone. */
    var clipsEnabled = false

    /** Pause after each 100 ms piece of a recording (25 = 4x faster than real time). */
    var feedDelayMs = 25L

    /**
     * Writes down an already-recorded sentence (16 kHz mono): used to double-check what the
     * small offline engine thought it heard, with the phone's on-device recognizer (the audio
     * stays on the phone). Needs its speech pack for [language] ([downloadOnDevicePack]).
     * (Google's online recognizer was tried too, but it ignores the recording and listens to
     * the microphone instead, so [onlineAllowed] is kept only for future engines.) Android 13+.
     */
    suspend fun transcribe(audio: ShortArray, onlineAllowed: Boolean): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || audio.size < 4_000) return Result.Failed(-1)
        // Tested on the owner's OnePlus (Android 15): Android's on-device service has no languages,
        // and Google's engines refuse a recording offline (error 13). Only try where it can work.
        if (!clipsEnabled) return Result.Failed(-1)
        val engines = buildList {
            if (isOnDeviceAvailable() && onDeviceClips != false && SystemClock.elapsedRealtime() > packMissingUntil) add(true)
            // Google's own engine with its offline pack ("Speech Services by Google"): the pack
            // the user downloads lives here, not in Android's built-in on-device service
            if (googleRecognizer != null && onlineClips != false) add(false)
        }
        var last: Result = Result.Failed(-1)
        for (onDevice in engines) {
            val r = transcribeWith(onDevice, audio)
            // (A missing language pack isn't remembered: it may be downloaded any minute)
            val unsupported = r is Result.Failed &&
                r.code in listOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
            if (onDevice) onDeviceClips = !unsupported else onlineClips = !unsupported
            // Speech pack not downloaded: skip the re-check for a while instead of losing time on every command
            if (onDevice && r is Result.Failed && r.code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                packMissingUntil = SystemClock.elapsedRealtime() + 10 * 60_000L
            }
            Log.i(
                TAG,
                "Recording re-check (${if (onDevice) "on-device" else "online"}): ${r::class.simpleName}" +
                    if (r is Result.Failed) " ${r.code}" else "",
            )
            // No words from this engine: try the next one (an engine without a speech pack still
            // "listens" but returns nothing)
            if (r is Result.Text) return r
            last = r
        }
        return last
    }

    private suspend fun transcribeWith(onDevice: Boolean, audio: ShortArray): Result = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext Result.Failed(-1)
        val sr = create(onDevice)
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            // Always offline: the recording stays on the phone (Google's engine uses its offline pack)
            val intent = baseIntent(offline = true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            // Recording + 0.6 s of silence: the recognizer decides the sentence ended when it hears quiet
            val padded = audio.copyOf(audio.size + 16_000 * 6 / 10)
            val bytes = ByteBuffer.allocate(padded.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                .also { it.asShortBuffer().put(padded) }.array()
            recognizeOnce(sr, intent, 8_000, onPartial = {}, trace = true) {
                // Feed it like a microphone would (100 ms pieces), only 4x faster than real time
                Thread {
                    runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                            val piece = 3_200 // 100 ms of 16 kHz 16-bit audio
                            var i = 0
                            while (i < bytes.size) {
                                val n = minOf(piece, bytes.size - i)
                                out.write(bytes, i, n)
                                out.flush()
                                i += n
                                Thread.sleep(feedDelayMs)
                            }
                            Log.i(TAG, "  fed ${bytes.size / 32} ms of audio")
                        }
                    }
                }.start()
            }
        } finally {
            sr.destroy()
            runCatching { pipe[0].close() }
        }
    }
}
