package com.assistant.nova.core.command

import java.util.Locale

/** What the user said while the phone is ringing. */
sealed interface CallAnswer {
    data object Answer : CallAnswer
    /** [message] = text to send to the caller ("I'm busy"), or null to just reject. */
    data class Reject(val message: String?) : CallAnswer
    data object Silence : CallAnswer

    companion object {
        private val ANSWER = Regex("^(?:answer|accept|pick up|pick it up|pickup|yes|yeah|take it|receive|attend|answer it|answer the call|connect)$")
        private val SILENCE = Regex("^(?:silence|mute|quiet|stop ringing|silent|shut up)$")
        private val REJECT = Regex("^(?:reject|decline|cut|cut it|cut the call|no|busy|ignore|hang up|end|end call|disconnect|not now)$")

        private const val WAKE = "^(?:(?:hey jarvis|jarvis|hey nova|nova|ok|okay|please) )*"
        private const val VERB = "(?:reject|decline|cut|end|hang up|disconnect|ignore|drop)"
        private const val OBJ = "(?: it| the call| this call| call| that| them)?"
        private const val JOIN = "(?: and| then|,)?"
        private const val SEND = "(?:send|text|message|sms|say|tell|reply|write)"
        private const val WHO = "(?: (?:them|him|her|back))?"
        private const val FILL = "(?: (?:a |an |the )?(?:message|text|sms|reply|msg))?(?: (?:saying|that|says|as))?"

        /** "reject and send I'm busy" -> "I'm busy" (verb first) */
        private val VERB_THEN_SEND = Regex("$WAKE$VERB$OBJ$JOIN $SEND$WHO$FILL (.+)$")
        /** "send I'm busy and reject" -> "I'm busy" (message first) */
        private val SEND_THEN_VERB = Regex("$WAKE$SEND$WHO$FILL (.+?)$JOIN $VERB$OBJ$")
        /** "tell them I'm driving" */
        private val TELL_THEM = Regex("$WAKE(?:tell them|text them|message them|reply)(?: that)? (.+)$")

        /** Lowercase, punctuation removed (apostrophes kept so "I'm" stays one word). */
        private fun lower(raw: String) =
            raw.lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("[^a-z0-9' ]"), " ").trim().replace(Regex("\\s+"), " ")

        /**
         * The message to send when the user rejects / ends a call with a text, in their own
         * words and capitals, or null if they didn't ask to send anything.
         */
        fun messageToSend(raw: String): String? {
            val l = lower(raw)
            val m = VERB_THEN_SEND.matchEntire(l) ?: SEND_THEN_VERB.matchEntire(l) ?: TELL_THEM.matchEntire(l) ?: return null
            val msgLower = m.groupValues[1].trim()
            if (msgLower.isEmpty()) return null
            return originalWords(raw, l, msgLower, m.groups[1]!!.range)
        }

        /** Picks the same words out of the original text, keeping "I'm", capitals and numbers as spoken. */
        private fun originalWords(raw: String, l: String, msgLower: String, range: IntRange): String {
            val rawTokens = raw.trim().replace('’', '\'').split(Regex("\\s+")).filter { t -> t.any { it.isLetterOrDigit() } }
            val before = l.substring(0, range.first).trim().split(' ').filter { it.isNotEmpty() }.size
            val count = msgLower.split(' ').size
            val words = rawTokens.drop(before).take(count)
            val text = if (words.size == count) words.joinToString(" ") else msgLower
            return text.trim().trimEnd('.', ',', '!').replaceFirstChar { it.uppercase() }
        }

        fun parse(raw: String): CallAnswer? {
            messageToSend(raw)?.let { return Reject(it) }
            val b = TextNormalizer.basic(raw)
            if (b.isEmpty()) return null
            return when {
                ANSWER.matches(b) -> Answer
                REJECT.matches(b) -> Reject(null)
                SILENCE.matches(b) -> Silence
                b.startsWith("answer") || b.startsWith("pick up") || b.startsWith("pick it up") || b.startsWith("accept") ||
                    b.startsWith("attend") || b.startsWith("receive") || b == "take the call" -> Answer
                b.startsWith("reject") || b.startsWith("decline") || b.startsWith("cut") -> Reject(null)
                else -> null
            }
        }
    }
}
