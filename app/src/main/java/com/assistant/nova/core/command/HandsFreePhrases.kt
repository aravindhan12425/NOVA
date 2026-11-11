package com.assistant.nova.core.command

/**
 * Everyday commands for no-wake-word ("hands-free") mode. The small offline engine is far more
 * accurate when it only has to choose among these phrases (it heard "turn on the flashlight"
 * as "plastic", "catholic", "cleft lip" in open listening), and anything else comes out as
 * "[unk]". Requests with names or song titles still go through open listening.
 *
 * Every word must exist in the offline model's vocabulary (checked by a unit test): Wi-Fi is
 * written "why fi" because the model has no "wi" or "wifi"; it sounds the same.
 */
object HandsFreePhrases {

    private val FEATURES = listOf(
        "the flashlight", "flashlight", "the torch", "torch", "why fi", "the why fi", "blue tooth", "the blue tooth",
        "mobile data", "the mobile data", "data", "the internet", "hotspot", "the hotspot", "hot spot",
        "airplane mode", "flight mode", "do not disturb", "location", "the location", "auto rotate",
        "dark mode", "battery saver", "the battery saver",
    )
    private val ON_OFF = listOf("turn on", "turn off", "switch on", "switch off")

    val FIXED: List<String> = buildList {
        for (v in ON_OFF) for (f in FEATURES) add("$v $f")
        addAll(listOf("flashlight on", "flashlight off", "torch on", "torch off"))
        // Music
        addAll(
            listOf(
                "pause", "play", "stop", "next song", "previous song", "skip this song", "play next song",
                "stop the music", "pause the music", "play the music", "resume the music", "next", "previous",
                "volume up", "volume down", "increase the volume", "decrease the volume",
                "increase the sound", "decrease the sound", "mute",
                "play my songs", "play some music", "play a tamil song", "play tamil songs", "play some tamil songs",
                "play an english song", "play english songs", "play hindi songs", "play my liked songs",
                "play party songs", "play some party songs",
            ),
        )
        // Questions
        addAll(
            listOf(
                "what time is it", "what is the time", "whats the time", "what is the date", "whats the date",
                "what is the day today", "how much battery", "battery",
            ),
        )
        // Phone
        addAll(
            listOf(
                "take a photo", "take a picture", "open the camera", "open camera", "go home", "go back",
                "take a screenshot", "lock the phone", "show notifications", "show unknown calls",
            ),
        )
    }

    /** Allowed before a phrase so they aren't heard as unknown words. */
    private val WAKE = listOf("hey jarvis", "hey nova", "nova", "jarvis", "please")

    /** The phrase list for Vosk (fixed commands + the user's routine names + "[unk]"). */
    fun grammarJson(routines: List<String>): String =
        (FIXED + routineNames(routines) + WAKE + "[unk]").distinct().joinToString(",", "[", "]") { "\"${it.replace("\"", "")}\"" }

    private fun routineNames(routines: List<String>) =
        routines.map { TextNormalizer.basic(it) }.filter { it.isNotBlank() }

    /**
     * The phrase heard, or null when it wasn't one of them. A short word before a longer phrase is
     * fine ("okay turn on the flashlight"); unknown words after or in between are not.
     */
    fun accept(heard: String, routines: List<String> = emptyList()): String? {
        var words = heard.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var unknownBefore = 0
        while (words.firstOrNull() == "[unk]") {
            words = words.drop(1); unknownBefore++
        }
        if (words.isEmpty() || "[unk]" in words || unknownBefore > 1) return null
        var text = words.joinToString(" ")
        WAKE.firstOrNull { text.startsWith("$it ") }?.let { text = text.removePrefix("$it ") }
        if (text !in FIXED && text !in routineNames(routines)) return null
        if (unknownBefore > 0 && !text.contains(' ')) return null
        return text
    }
}
