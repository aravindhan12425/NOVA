package com.assistant.nova.core.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The phone's own speech recognizer (Google on OnePlus). Best accuracy, especially for
 * contact names. Works offline too when the offline English pack is installed.
 */
class AndroidRecognizer(private val context: Context) {

    sealed interface Result {
        /** [text] = top guess; [alternatives] = all guesses, best first (includes [text]). */
        data class Text(val text: String, val alternatives: List<String> = listOf(text)) : Result
        data object NoSpeech : Result
        /** The recognizer couldn't run (no network, no offline pack, busy…): use Vosk instead. */
        data class Failed(val code: Int) : Result
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

    /** "en-IN" unless the phone is set to another English (e.g. en-GB) the user chose on purpose. */
    var language: String = "en-IN"

    companion object {
        /** Words Nova expects, so the recognizer leans towards them (on-device recognizer, Android 13+). */
        val HINTS = listOf(
            "Nova", "Jarvis", "Wi-Fi", "Bluetooth", "hotspot", "torch", "flashlight", "mobile data", "airplane mode",
            "do not disturb", "volume", "brightness", "alarm", "timer", "speaker", "mute", "record the call",
            "end the call", "answer", "reject", "play", "pause", "next song", "previous song", "WhatsApp", "message",
        )
    }

    fun isAvailable(): Boolean = googleRecognizer != null || SpeechRecognizer.isRecognitionAvailable(context)

    fun isOnDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun listen(offline: Boolean, onPartial: (String) -> Unit): Result = withContext(Dispatchers.Main) {
        val sr = if (offline && isOnDeviceAvailable() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            googleRecognizer?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
                ?: SpeechRecognizer.createSpeechRecognizer(context)
        }
        try {
            withTimeoutOrNull(15_000) {
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
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        // Indian English understands Indian accents and names far better than the
                        // phone's default (often US English)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        // Several guesses: Nova picks the first one that is a real command
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(HINTS))
                        }
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    }
                    sr.startListening(intent)
                }
            } ?: Result.NoSpeech
        } finally {
            sr.destroy()
        }
    }
}
