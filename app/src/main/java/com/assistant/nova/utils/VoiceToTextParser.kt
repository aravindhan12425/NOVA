package com.assistant.nova.utils

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

class VoiceToTextParser(private val app: Application) : RecognitionListener {

    private val _state = MutableStateFlow(VoiceToTextParserState())
    val state = _state.asStateFlow()

    private val recognizer = SpeechRecognizer.createSpeechRecognizer(app)
    private var externalListener: RecognitionListener? = null

    fun setRecognitionListener(listener: RecognitionListener) {
        this.externalListener = listener
    }

    open class RecognitionListenerAdapter : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {}
        override fun onResults(results: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun startListening(languageCode: String? = null) {
        _state.update { VoiceToTextParserState(isListening = true) }

        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            _state.update { it.copy(error = "Speech recognition is not available") }
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            
            // Use provided language or fallback to system default
            val lang = languageCode ?: Locale.getDefault().toLanguageTag()
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            
            // Crucial for some devices to identify the app
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            
            // Add a hint to support partial results for a smoother feel
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        recognizer.setRecognitionListener(this)
        recognizer.startListening(intent)
    }

    fun stopListening() {
        _state.update { it.copy(isListening = false) }
        recognizer.stopListening()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        _state.update { it.copy(error = null) }
        externalListener?.onReadyForSpeech(params)
    }

    override fun onBeginningOfSpeech() {
        externalListener?.onBeginningOfSpeech()
    }

    override fun onRmsChanged(rmsdB: Float) {
        externalListener?.onRmsChanged(rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        externalListener?.onBufferReceived(buffer)
    }

    override fun onEndOfSpeech() {
        _state.update { it.copy(isListening = false) }
        externalListener?.onEndOfSpeech()
    }

    override fun onError(error: Int) {
        if (error != SpeechRecognizer.ERROR_CLIENT) {
            _state.update { it.copy(error = "Error: $error", isListening = false) }
        }
        externalListener?.onError(error)
    }

    override fun onResults(results: Bundle?) {
        val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
        _state.update { it.copy(spokenText = phrases.getOrNull(0) ?: "", allPhrases = phrases) }
        externalListener?.onResults(results)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val phrases = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
        _state.update { it.copy(spokenText = phrases.getOrNull(0) ?: "") }
        externalListener?.onPartialResults(partialResults)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
        externalListener?.onEvent(eventType, params)
    }
}

data class VoiceToTextParserState(
    val spokenText: String = "",
    val allPhrases: List<String> = emptyList(),
    val isListening: Boolean = false,
    val error: String? = null
)
