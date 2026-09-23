package com.assistant.nova.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.nova.utils.CommandProcessor
import com.assistant.nova.utils.VoiceToTextParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class AssistantState {
    IDLE, LISTENING, PROCESSING, EXECUTING, COMPLETED
}

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    
    private val voiceParser = VoiceToTextParser(application)
    val voiceState = voiceParser.state

    private val commandProcessor = CommandProcessor(application)

    private val _uiState = MutableStateFlow(AssistantState.IDLE)
    val uiState: StateFlow<AssistantState> = _uiState

    private val _lastResponse = MutableStateFlow("")
    val lastResponse: StateFlow<String> = _lastResponse

    init {
        viewModelScope.launch {
            voiceState.collectLatest { state ->
                if (state.allPhrases.isNotEmpty() && !state.isListening) {
                    processVoiceResults(state.allPhrases)
                }
                
                if (state.isListening) {
                    _uiState.value = AssistantState.LISTENING
                    _lastResponse.value = "" // Clear previous response
                } else if (_uiState.value == AssistantState.LISTENING) {
                    _uiState.value = AssistantState.IDLE
                }
            }
        }
    }

    private fun processVoiceResults(phrases: List<String>) {
        viewModelScope.launch {
            _uiState.value = AssistantState.PROCESSING
            val response = commandProcessor.processCommand(phrases)
            _lastResponse.value = response
            _uiState.value = AssistantState.EXECUTING
            
            delay(3000) // Show response for 3 seconds
            _uiState.value = AssistantState.IDLE
        }
    }

    fun toggleListening() {
        if (voiceState.value.isListening) {
            voiceParser.stopListening()
        } else {
            voiceParser.startListening()
        }
    }
}


