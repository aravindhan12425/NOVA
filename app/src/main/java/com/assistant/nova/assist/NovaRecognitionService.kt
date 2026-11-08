package com.assistant.nova.assist

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.assistant.nova.core.speech.Mic
import com.assistant.nova.core.speech.VoskRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android requires an assistant app to provide speech recognition to other apps. When
 * Nova is the assistant, apps that ask for "the phone's speech recognizer" get Nova's
 * fully offline Vosk recognizer. (Nova itself still prefers Google's recognizer when
 * it's installed, for accuracy with names.)
 */
class NovaRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mic = Mic()
    private lateinit var vosk: VoskRecognizer
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        vosk = VoskRecognizer(this, scope)
    }

    private fun bundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        job?.cancel()
        job = scope.launch {
            if (!mic.open()) {
                runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) }
                return@launch
            }
            try {
                runCatching { listener.readyForSpeech(Bundle()) }
                val text = vosk.listen(mic, onPartial = { p -> runCatching { listener.partialResults(bundle(p)) } })
                runCatching { listener.endOfSpeech() }
                runCatching {
                    if (text.isBlank()) listener.error(SpeechRecognizer.ERROR_NO_MATCH) else listener.results(bundle(text))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            } finally {
                mic.close()
            }
        }
    }

    /** The app says the user finished: closing the mic makes Vosk return what it heard. */
    override fun onStopListening(listener: Callback) = mic.close()

    override fun onCancel(listener: Callback) {
        job?.cancel()
        mic.close()
    }

    override fun onDestroy() {
        scope.cancel()
        mic.close()
        vosk.close()
        super.onDestroy()
    }
}
