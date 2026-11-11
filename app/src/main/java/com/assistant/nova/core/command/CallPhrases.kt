package com.assistant.nova.core.command

/**
 * What Nova listens for during a call, without a wake word: only these short phrases.
 *
 * The offline recognizer is limited to this list (a "grammar"), which makes it far more
 * accurate than open speech, and anything else said on the call comes out as "[unk]"
 * (unknown) and is ignored.
 */
object CallPhrases {

    val PHRASES: List<String> = listOf(
        // End
        "end the call", "end call", "end this call", "end it", "cut the call", "cut call", "cut this call", "cut it",
        "cut it off", "call cut", "call end", "hang up", "hang up the call", "disconnect", "disconnect the call",
        "disconnect call", "finish the call", "drop the call", "stop the call", "cut the phone",
        // Speaker
        "speaker", "speaker on", "turn on speaker", "turn on the speaker", "switch on speaker", "switch on the speaker",
        "put on speaker", "speaker off", "turn off speaker", "turn off the speaker", "switch off speaker",
        "switch off the speaker",
        // Microphone
        // (The offline model has no word "unmute": "turn on my mic" instead)
        "mute me", "mute myself", "mute the call", "mute call", "mute my mic", "mute the mic",
        "turn off my mic", "turn off the mic", "turn on my mic", "turn on the mic",
        // Recording
        "record the call", "record call", "record this call", "start recording", "start call recording",
        "turn on call recording", "turn on the call recording", "start the recording",
        "stop recording", "stop call recording", "stop the recording", "turn off call recording",
        "turn off the call recording",
        // Hold
        "hold the call", "put the call on hold", "put on hold", "resume the call", "take off hold",
    )

    /** Allowed before a phrase ("hey jarvis end the call"), so they aren't heard as unknown words. */
    // Every word here must be in the offline model's vocabulary ("dei" isn't)
    private val WAKE = listOf("hey jarvis", "hey nova", "nova", "jarvis")

    private val phraseSet = PHRASES.toSet()

    /** The phrase list for Vosk's recognizer, as a JSON array (plus "[unk]" for everything else). */
    fun grammarJson(): String =
        (PHRASES + WAKE + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }

    /**
     * Decides whether something heard during a call is a call command. Returns the
     * phrase to run, or null.
     *
     * - The whole sentence is a phrase ("end the call", "hey jarvis cut the call"): yes.
     * - A couple of other words first ("okay end the call"): yes for phrases of 2+ words,
     *   since a lone word like "disconnect" could just be part of the conversation.
     * - Unknown words after or in between ("end the call later"): no.
     */
    fun accept(heard: String): String? {
        var words = heard.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var unknownBefore = 0
        while (words.firstOrNull() == "[unk]") {
            words = words.drop(1); unknownBefore++
        }
        if (words.isEmpty() || "[unk]" in words || unknownBefore > 2) return null
        var text = words.joinToString(" ")
        WAKE.firstOrNull { text.startsWith("$it ") }?.let { text = text.removePrefix("$it ") }
        if (text in phraseSet) {
            if (unknownBefore > 0 && !text.contains(' ')) return null
            return text
        }
        // The recognizer can join listed words in a new order ("turn on the call recording"):
        // fine if it's still clearly a call command of 2+ words
        if (text.contains(' ') && CommandParser.parse(text) is Command.CallControl) return text
        return null
    }
}
