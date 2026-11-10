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
        listOf("com.google.android.tts", "com.google.android.googlequicksearchbox").firstNotNullOfOrNull { pkg ->
            services.firstOrNull { it.serviceInfo.packageName == pkg }
                ?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        }
    }

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

    private fun baseIntent(offline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
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
    private suspend fun run(
        sr: SpeechRecognizer,
        intent: Intent,
        timeoutMs: Long,
        onPartial: (String) -> Unit,
        started: () -> Unit = {},
    ): Result = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Result> { cont ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        .filter { it.isNotBlank() }
                    if (cont.isActive) cont.resume(if (all.isEmpty()) Result.NoSpeech else Result.Text(all.first(), all))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
                }

                override fun onError(error: Int) {
                    val r = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Result.NoSpeech
                        else -> Result.Failed(error)
                    }
                    if (cont.isActive) cont.resume(r)
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            sr.startListening(intent)
            started()
        }
    } ?: Result.NoSpeech

    /** Listens on the microphone for one sentence. */
    suspend fun listen(offline: Boolean, onPartial: (String) -> Unit): Result = withContext(Dispatchers.Main) {
        val sr = create(onDevice = offline && isOnDeviceAvailable())
        try {
            run(sr, baseIntent(offline).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true), 15_000, onPartial)
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

    /**
     * Writes down an already-recorded sentence (16 kHz mono): used to double-check what the
     * small offline engine thought it heard, with the phone's on-device recognizer (the audio
     * stays on the phone). Needs its speech pack for [language] ([downloadOnDevicePack]).
     * (Google's online recognizer was tried too, but it ignores the recording and listens to
     * the microphone instead, so [onlineAllowed] is kept only for future engines.) Android 13+.
     */
    suspend fun transcribe(audio: ShortArray, onlineAllowed: Boolean): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || audio.size < 4_000) return Result.Failed(-1)
        val engines = buildList {
            if (isOnDeviceAvailable() && onDeviceClips != false && SystemClock.elapsedRealtime() > packMissingUntil) add(true)
        }
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
            if (r !is Result.Failed) return r
        }
        return Result.Failed(-1)
    }

    private suspend fun transcribeWith(onDevice: Boolean, audio: ShortArray): Result = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext Result.Failed(-1)
        val sr = create(onDevice)
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val intent = baseIntent(offline = onDevice)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            val bytes = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                .also { it.asShortBuffer().put(audio) }.array()
            run(sr, intent, 8_000, onPartial = {}) {
                // Feed the recording, then close it: the end of the stream ends the sentence
                Thread {
                    runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } }
                }.start()
            }
        } finally {
            sr.destroy()
            runCatching { pipe[0].close() }
        }
    }
}
