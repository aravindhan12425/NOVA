package com.assistant.nova.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live state shared between the listening service and the screens. */
object NovaState {

    enum class Status { OFF, STARTING, LISTENING, HEARING, THINKING, SPEAKING, PAUSED, ERROR }

    data class UiStatus(val status: Status, val detail: String = "")

    data class Exchange(val heard: String, val reply: String, val time: Long = System.currentTimeMillis())

    private val _status = MutableStateFlow(UiStatus(Status.OFF))
    val status: StateFlow<UiStatus> = _status.asStateFlow()

    /** Words recognized so far while the user is still talking. */
    val partial = MutableStateFlow("")

    /** Which speech engine handled the last command, e.g. "Google (offline)" or "Vosk (offline)". */
    val engine = MutableStateFlow("")

    private val _history = MutableStateFlow<List<Exchange>>(emptyList())
    val history: StateFlow<List<Exchange>> = _history.asStateFlow()

    fun set(status: Status, detail: String = "") {
        _status.value = UiStatus(status, detail)
    }

    fun addExchange(heard: String, reply: String) {
        _history.update { (it + Exchange(heard, reply)).takeLast(30) }
    }
}
