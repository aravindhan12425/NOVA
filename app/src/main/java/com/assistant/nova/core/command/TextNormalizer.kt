package com.assistant.nova.core.command

import java.util.Locale

/**
 * Cleans up what the speech recognizer heard so the parser can match it.
 *
 * Everything matches WHOLE words only. The old version replaced letters inside
 * words ("i" -> "wi-fi"), which turned "flashlight" into "flashlwi-fight" and made
 * almost every command toggle Wi-Fi.
 */
object TextNormalizer {

    // Polite ways of asking go first (longest first): "would you mind turning on wifi" -> "turn on wifi"
    private val fillerPrefixes = listOf(
        "i was wondering if you could", "i wonder if you could", "would you be so kind as to",
        "would you be kind enough to", "could you be kind enough to", "be kind enough to",
        "is it possible for you to", "is it possible to", "do me a favour and", "do me a favor and",
        "would you mind", "do you mind", "could you possibly", "can you possibly",
        "i would like you to", "id like you to", "if you dont mind", "if you can",
        "would you please", "will you please", "please can you", "please could you",
        "dei nova", "dey nova", "day nova",
        "hey jarvis", "hi jarvis", "ok jarvis", "okay jarvis", "jarvis",
        "hey nova", "hi nova", "ok nova", "okay nova", "nova",
        "i want you to", "i need you to", "i want to", "can you please", "could you please",
        "can you", "could you", "would you", "will you", "can u",
        "hey", "ok", "okay", "please", "kindly", "just", "now",
    )
    private val fillerSuffixes = listOf(
        "please", "for me", "right now", "now", "thanks", "thank you", "if you dont mind", "if you can",
        "if possible", "nova", "jarvis", "dei", "da", "bro", "buddy",
    )

    private val WAKE_NAMES = setOf("nova", "jarvis")
    private val MESSAGE_START = Regex("^(?:send|message|text|whatsapp|sms|reply|msg)\\b")
    private val MESSAGE_WORDS = Regex("\\b(?:saying|say|that says|with message|telling)\\b")
    private val WAKE_MENTION = Regex("\\b(?:hey|hi|ok|okay|dei|dey|day|without|say|saying) (?:nova|jarvis)$")
    private fun isMessage(s: String) = MESSAGE_START.containsMatchIn(s) || MESSAGE_WORDS.containsMatchIn(s)

    /** "would you mind PLAYING believer" -> "play believer": the command verb as the parser expects it. */
    private val gerunds = mapOf(
        "playing" to "play", "calling" to "call", "turning" to "turn", "switching" to "switch",
        "setting" to "set", "opening" to "open", "sending" to "send", "putting" to "put", "making" to "make",
        "taking" to "take", "stopping" to "stop", "pausing" to "pause", "reading" to "read", "telling" to "tell",
        "giving" to "give", "searching" to "search", "increasing" to "increase", "decreasing" to "decrease",
        "reducing" to "reduce", "raising" to "raise", "lowering" to "lower", "skipping" to "skip",
        "starting" to "start", "enabling" to "enable", "disabling" to "disable", "messaging" to "message",
        "texting" to "text", "dialing" to "dial", "dialling" to "dial", "ringing" to "ring", "closing" to "close",
        "launching" to "launch", "showing" to "show", "finding" to "find", "checking" to "check",
        "adding" to "add", "noting" to "note", "writing" to "write", "navigating" to "navigate", "going" to "go",
        "unlocking" to "unlock", "resuming" to "resume", "shuffling" to "shuffle", "muting" to "mute",
        "unmuting" to "unmute", "answering" to "answer", "rejecting" to "reject", "ending" to "end",
        "recording" to "record", "defining" to "define", "spelling" to "spell", "calculating" to "calculate",
        "converting" to "convert", "looking" to "look", "googling" to "google", "identifying" to "identify",
        "capturing" to "capture", "cancelling" to "cancel", "canceling" to "cancel", "deleting" to "delete",
        "clearing" to "clear", "running" to "run", "dimming" to "dim", "keeping" to "keep", "getting" to "get",
        "bringing" to "bring", "reminding" to "remind", "silencing" to "silence",
    )

    /** Lowercase, punctuation removed, "hey jarvis"/"please"-style filler removed. */
    fun basic(raw: String): String {
        var s = raw.lowercase(Locale.ROOT)
            .replace("'", "")
            .replace("’", "")
            .replace("%", " percent ")
            // Keep decimals ("2.5") and arithmetic signs, which are about to be stripped as punctuation
            .replace(Regex("(\\d)\\.(\\d)"), "$1 point $2")
            .replace(Regex("(\\d)\\s*\\+\\s*(\\d)"), "$1 plus $2")
            .replace(Regex("(\\d)\\s*[×*]\\s*(\\d)"), "$1 times $2")
            .replace(Regex("(\\d)\\s*[÷/]\\s*(\\d)"), "$1 divided by $2")
            .replace(Regex("(\\d)\\s+-\\s+(\\d)"), "$1 minus $2")
            .replace(Regex("[^a-z0-9: ]"), " ")
        s = collapse(s)
        s = s.replace(Regex("\\bwhats app\\b"), "whatsapp")
        s = s.replace(Regex("\\bgood(night|morning|evening|afternoon)\\b"), "good $1")
        s = s.replace(Regex("\\bwhat sapp\\b"), "whatsapp")

        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            for (p in fillerPrefixes) {
                if (s == p) {
                    s = ""; changed = true; break
                }
                if (s.startsWith("$p ")) {
                    s = s.removePrefix("$p ").trim(); changed = true; break
                }
            }
            // A message keeps its own ending: "message Krishna saying see you da" sends "see you da"
            if (isMessage(s)) continue
            for (p in fillerSuffixes) {
                // "listen without hey jarvis" talks about the wake word: keep it
                if (p in WAKE_NAMES && WAKE_MENTION.containsMatchIn(s)) continue
                if (s.endsWith(" $p")) {
                    s = s.removeSuffix(" $p").trim(); changed = true
                }
            }
        }
        // The offline engine often hears "turn on" as "run on"
        s = s.replace(Regex("^(?:run|ton|tan) (on|off) "), "turn $1 ")
        val first = s.substringBefore(' ')
        gerunds[first]?.let { s = it + s.removePrefix(first) }
        return s
    }

    // Order matters: longer phrases first inside each group.
    private val synonyms: List<Pair<Regex, String>> = listOf(
        "half an hour" to "30 minutes",
        "half a minute" to "30 seconds",
        "an hour" to "1 hour",
        "a minute" to "1 minute",
        "wi fi|why fi|wy fi|y fi|wifi|wlan" to "wifi",
        "mobile hotspot|wifi hotspot|personal hotspot|hot spot|hotspot|tethering" to "hotspot",
        "blue tooth|blutooth|bluetooth" to "bluetooth",
        "aeroplane mode|airplane mode|aero plane mode|flight mode|aeroplane|airplane|aero plane" to "airplane",
        "torch light|torchlight|flash light|flashlight|torch|flash" to "flashlight",
        "mobile data|cellular data|mobile internet|data connection|cellular|internet|data|net" to "mobiledata",
        "auto rotate|autorotate|auto rotation|screen rotation|rotation|rotate" to "autorotate",
        "do not disturb|dont disturb|dnd" to "dnd",
        "location|gps" to "location",
        "battery saver mode|battery saving mode|battery saving|battery saver|power saving mode|power saving|power saver" to "batterysaver",
        "dark mode|dark theme|night mode" to "darkmode",
        "n f c|nfc" to "nfc",
        "o clock|oclock" to "oclock",
        "a m" to "am",
        "p m" to "pm",
        "screen shot" to "screenshot",
    ).map { (alts, target) -> Regex("\\b(?:$alts)\\b") to target }

    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    /**
     * [basic] text with feature names unified ("wi fi" -> "wifi", "torch" -> "flashlight")
     * and number words turned into digits ("seven thirty" -> "7 30").
     */
    fun canonical(basicText: String): String {
        var s = basicText.replace(':', ' ')
        s = numberWordsToDigits(collapse(s))
        for ((re, target) in synonyms) s = re.replace(s, target)
        return collapse(s)
    }

    fun numberWordsToDigits(s: String): String {
        val tokens = s.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val ten = tens[t]
            val unit = units[t]
            when {
                ten != null -> {
                    val next = tokens.getOrNull(i + 1)?.let { units[it] }
                    if (next != null && next in 1..9) {
                        out += (ten + next).toString(); i += 2
                    } else {
                        out += ten.toString(); i++
                    }
                }
                unit != null -> {
                    if (tokens.getOrNull(i + 1) == "hundred") {
                        out += (unit * 100).toString(); i += 2
                    } else {
                        out += unit.toString(); i++
                    }
                }
                t == "hundred" -> {
                    out += "100"; i++
                }
                else -> {
                    out += t; i++
                }
            }
        }
        return out.joinToString(" ")
    }

    fun collapse(s: String): String = s.trim().replace(Regex("\\s+"), " ")
}
