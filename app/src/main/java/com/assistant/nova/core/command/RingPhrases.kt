package com.assistant.nova.core.command

/**
 * What Nova listens for while the phone rings: answer, reject, silence. Listening for only
 * these (with the offline engine) starts at once and works over the ringtone; open listening
 * with Google's recognizer heard nothing for 12 s on a real call.
 * Every word must be in the offline model's vocabulary (unit test).
 */
object RingPhrases {
    val ANSWER = listOf(
        "answer", "answer it", "answer the call", "pick up", "pick it up", "pick up the call", "accept", "accept the call",
        "attend", "attend the call", "attend it", "take the call", "receive", "receive the call", "yes",
    )
    val REJECT = listOf(
        "reject", "reject it", "reject the call", "decline", "decline the call", "cut", "cut it", "cut the call",
        "busy", "no",
    )
    /** Reject, then Nova asks what message to send. */
    val REJECT_AND_SEND = listOf("reject and send", "cut and send", "decline and send", "reject and message", "reject and text")
    val SILENCE = listOf("silence", "silent", "mute", "stop ringing", "quiet")

    val ALL = ANSWER + REJECT + REJECT_AND_SEND + SILENCE

    private val WAKE = listOf("hey jarvis", "hey nova", "nova", "jarvis", "please")

    fun grammarJson(): String = (ALL + WAKE + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }

    /** The phrase heard, or null. A couple of unknown words before it are fine (the ringtone). */
    fun accept(heard: String): String? {
        var words = heard.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var unknownBefore = 0
        while (words.firstOrNull() == "[unk]") {
            words = words.drop(1); unknownBefore++
        }
        if (words.isEmpty() || "[unk]" in words || unknownBefore > 2) return null
        var text = words.joinToString(" ")
        WAKE.firstOrNull { text.startsWith("$it ") }?.let { text = text.removePrefix("$it ") }
        return text.takeIf { it in ALL }
    }
}
