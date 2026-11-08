package com.assistant.nova.core.command

import java.util.Locale

/** Hides numbers in text shown on screen or in notifications, so a spoken PIN never appears anywhere. */
object Redact {
    private val STRICT = setOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val SOUND_ALIKE = setOf("oh", "o", "won", "to", "too", "tree", "for", "fore", "ate")

    /**
     * Replaces digits and digit words with "•". [aggressive] also hides sound-alike words
     * ("for", "to", "ate"…), used for live text while a PIN may be being spoken.
     */
    fun digits(text: String, aggressive: Boolean = false): String =
        text.split(' ').joinToString(" ") { w ->
            val l = w.lowercase(Locale.ROOT).trim { !it.isLetterOrDigit() }
            when {
                l in STRICT || (aggressive && l in SOUND_ALIKE) -> "•"
                w.any(Char::isDigit) -> w.map { if (it.isDigit()) '•' else it }.joinToString("")
                else -> w
            }
        }
}
