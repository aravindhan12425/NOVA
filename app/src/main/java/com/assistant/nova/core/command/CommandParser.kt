package com.assistant.nova.core.command

/**
 * Turns recognized speech into [Command]s. Pure Kotlin, works fully offline and
 * is unit tested in CommandParserTest.
 */
object CommandParser {

    private val CANCEL = setOf(
        "cancel", "stop", "never mind", "nevermind", "nothing", "forget it", "leave it",
        "shut up", "quiet", "be quiet", "no", "nope", "exit",
    )

    private val ON_WORDS = setOf("on", "enable", "activate", "start", "connect", "open")
    private val OFF_WORDS = setOf("off", "disable", "deactivate", "stop", "disconnect", "kill", "close")

    private val FEATURE_TOKENS = mapOf(
        "wifi" to Feature.WIFI,
        "bluetooth" to Feature.BLUETOOTH,
        "mobiledata" to Feature.MOBILE_DATA,
        "airplane" to Feature.AIRPLANE,
        "hotspot" to Feature.HOTSPOT,
        "autorotate" to Feature.AUTO_ROTATE,
        "dnd" to Feature.DND,
        "flashlight" to Feature.FLASHLIGHT,
        "location" to Feature.LOCATION,
        "batterysaver" to Feature.BATTERY_SAVER,
        "nfc" to Feature.NFC,
        "darkmode" to Feature.DARK_MODE,
    )

    private val MEDIA_NOUNS = setOf(
        "music", "song", "songs", "track", "video", "media", "playback", "audio", "player",
        "movie", "film", "show", "episode",
    )

    /**
     * Parses one utterance, which may hold several commands ("turn on wifi and bluetooth").
     * [unlockPhrase] is the user's voice-unlock trigger from Settings ("unlock" by default).
     */
    fun parseAll(raw: String, unlockPhrase: String? = null, routines: List<String> = emptyList()): List<Command> {
        val basic = TextNormalizer.basic(raw)
        if (basic.isBlank()) return listOf(Command.Unknown(raw.trim()))
        parseUnlock(basic, unlockPhrase)?.let { return listOf(it) }
        parseRoutine(basic, routines)?.let { return listOf(it) }
        // "end the call and send I'm busy": keep the message in the user's own words
        CallAnswer.messageToSend(raw)?.let { return listOf(Command.CallControl(InCallAction.END, it)) }
        val whole = parseBasic(basic)
        // Names and message text may legitimately contain "and"
        if (whole is Command.Message || whole is Command.Call) return listOf(whole)

        val parts = basic.split(Regex("\\s+(?:and then|and also|and|then|also)\\s+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return listOf(whole)

        val commands = mutableListOf<Command>()
        var lastState: OnOff? = null
        for (part in parts) {
            var c = parseBasic(part)
            if (c is Command.SetFeature) {
                // "turn on wifi and bluetooth": the second part inherits "on"
                if (c.state == OnOff.TOGGLE && lastState != null) c = c.copy(state = lastState)
                lastState = c.state
            }
            commands += c
        }
        return if (commands.none { it is Command.Unknown }) commands else listOf(whole)
    }

    /**
     * The speech recognizer's guesses for one sentence, best first ("turn on why fi",
     * "turn on wifi", …). Returns the first guess that is a command Nova understands, so a
     * near miss in the top guess doesn't lose the command. Falls back to the top guess.
     */
    fun bestOf(guesses: List<String>, unlockPhrase: String? = null, routines: List<String> = emptyList()): String? {
        val clean = guesses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return clean.firstOrNull { g -> parseAll(g, unlockPhrase, routines).none { it is Command.Unknown } } ?: clean.firstOrNull()
    }

    /** First command of [parseAll]; handy for tests. */
    fun parse(raw: String, unlockPhrase: String? = null, routines: List<String> = emptyList()): Command =
        parseAll(raw, unlockPhrase, routines).first()

    // ── Routines ────────────────────────────────────────────────────────────

    /**
     * "good night", "start good night", "run my driving routine", "movie time mode" ->
     * RunRoutine. [routines] are the user's trigger phrases.
     */
    private fun parseRoutine(b: String, routines: List<String>): Command? {
        if (routines.isEmpty()) return null
        val stripped = b
            .replace(Regex("^(?:start|run|begin|activate|do|turn on|switch to|enable)(?: the| my)? "), "")
            .replace(Regex(" (?:routine|mode)$"), "")
            .trim()
        var best: Pair<String, Double>? = null
        for (r in routines) {
            val rb = TextNormalizer.basic(r).replace(Regex(" (?:routine|mode)$"), "")
            if (rb.isEmpty()) continue
            // Both ways, so a single word ("night") doesn't trigger "good night"
            fun both(x: String) = minOf(FuzzyMatcher.score(x, rb), FuzzyMatcher.score(rb, x))
            val s = maxOf(both(b), both(stripped))
            if (s >= 0.9 && (best == null || s > best.second)) best = r to s
        }
        return best?.let { Command.RunRoutine(it.first) }
    }

    // ── Voice unlock ────────────────────────────────────────────────────────

    // Speech recognizers often write digits as sound-alike words ("for" = 4, "ate" = 8)
    private val PIN_WORDS = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "one" to "1", "won" to "1", "two" to "2", "to" to "2",
        "too" to "2", "three" to "3", "tree" to "3", "four" to "4", "for" to "4", "fore" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "ate" to "8", "nine" to "9",
    )
    private val PIN_FILLER = setOf("pin", "code", "password", "passcode", "is", "my", "the", "with", "and", "uh", "um", "number")

    /** "<trigger phrase> 1 2 3 4" -> Unlock("1234"). Null if this isn't an unlock request. */
    private fun parseUnlock(b: String, phrase: String?): Command.Unlock? {
        if (phrase.isNullOrBlank()) return null
        val p = TextNormalizer.basic(phrase)
        if (p.isBlank()) return null
        val pw = p.split(' ')
        val bw = b.split(' ')
        if (bw.size < pw.size) return null
        val head = bw.take(pw.size).joinToString(" ")
        if (head != p && FuzzyMatcher.score(head, p) < 0.85) return null
        val rest = bw.drop(pw.size)
        if (rest.isEmpty()) return Command.Unlock("")
        // Words that aren't digits mean it's some other command that happens to start the same way
        return pinDigits(rest)?.let { Command.Unlock(it) }
    }

    internal fun pinDigits(tokens: List<String>): String? {
        val sb = StringBuilder()
        var repeat = 1
        for (tk in tokens) {
            when {
                tk in PIN_FILLER -> {}
                tk == "double" -> repeat = 2
                tk == "triple" -> repeat = 3
                tk.isNotEmpty() && tk.all(Char::isDigit) -> {
                    repeat(repeat) { sb.append(tk) }; repeat = 1
                }
                PIN_WORDS.containsKey(tk) -> {
                    repeat(repeat) { sb.append(PIN_WORDS.getValue(tk)) }; repeat = 1
                }
                else -> return null
            }
        }
        return sb.toString().ifEmpty { null }
    }

    /** "show unknown calls", "unknown numbers", "who called me from unknown numbers" */
    private val UNKNOWN_CALLS = Regex(
        "^(?:show|open|list|see|find|check)?(?: me)?(?: my| the| all)? ?(?:unknown|unsaved|new|strange|random) " +
            "(?:calls|callers|numbers|number|call list|phone numbers)(?: list| page)?$",
    )

    // ── Small talk ──────────────────────────────────────────────────────────

    private val CHAT: List<Pair<Regex, String>> = listOf(
        "thank you|thanks|thank u|thanks a lot|thanks a ton|thank you so much|thank you very much|thanks so much|" +
            "cheers|nandri|romba nandri|thanks da|thank you da" to "You're welcome!",
        "good morning" to "Good morning!",
        "good afternoon" to "Good afternoon!",
        "good evening" to "Good evening!",
        "good night" to "Good night! Sleep well.",
        "how are you|how are you doing|how r u|hows it going|how is it going|how do you do|whats up|sup" to
            "I'm doing great, thanks for asking. What can I do for you?",
        "who are you|what is your name|whats your name|what are you|tell me about yourself" to "I'm Nova, your assistant.",
        "hello|hello there|hi there|hai|vanakkam|hello hello" to "Hi! What can I do for you?",
        "good job|well done|nice work|great job|awesome|super|you are awesome|youre awesome|good girl|good boy" to "Thank you!",
        "sorry|my bad" to "No problem.",
    ).map { (alts, reply) -> Regex("^(?:$alts)$") to reply }

    /** "thank you", "good morning"… Null if [b] is anything else. */
    fun parseChat(b: String): Command.Chat? = CHAT.firstOrNull { it.first.matches(b) }?.let { Command.Chat(it.second) }

    private fun parseBasic(b: String): Command {
        if (b in CANCEL) return Command.Cancel
        parseChat(b)?.let { return it }
        if (b == "help" || b.contains("what can you do") || b.contains("what all can you do") ||
            b == "commands" || b.contains("what are your commands")
        ) return Command.Help

        if (IDENTIFY_RES.any { it.matches(b) }) return Command.IdentifySong
        if (UNKNOWN_CALLS.containsMatchIn(b)) return Command.ShowUnknownCalls
        parseCallAssistantSwitch(b)?.let { return it }
        parseHandsFreeSwitch(b)?.let { return it }
        parseCamera(b)?.let { return it }
        parseCallControl(b)?.let { return it }
        parseNotes(b)?.let { return it }
        parseSpell(b)?.let { return it }
        parseReminder(b)?.let { return it }
        parseDefine(b)?.let { return it }
        parseSearch(b)?.let { return it }
        parseMessage(b)?.let { return it }
        parseCall(b)?.let { return it }

        val t = TextNormalizer.canonical(b)
        val tokens = t.split(' ')
        val w = tokens.toSet()

        parseConvert(t)?.let { return it }
        parseCalculation(t)?.let { return it }
        parseDaysUntil(t)?.let { return it }
        if (NEXT_ALARM_RES.any { it.matches(t) }) return Command.Info(InfoQuery.NEXT_ALARM)
        parseTimer(t, w)?.let { return it }
        parseAlarm(t, tokens, w)?.let { return it }
        parseNavigation(t, w)?.let { return it }
        parseOpen(b)?.let { return it }
        parseSpotify(b)?.let { return it }
        parsePlayMusic(b)?.let { return it }
        parseVolume(tokens, w)?.let { return it }
        parseRinger(w)?.let { return it }
        parseFeature(tokens, w)?.let { return it }
        parseBrightness(tokens, w)?.let { return it }
        parseMedia(t, w)?.let { return it }
        parseInfo(w)?.let { return it }
        return Command.Unknown(b)
    }

    // ── Controlling a connected call ────────────────────────────────────────

    private const val THE_CALL = "(?: (?:the|this|my|current|ongoing))?(?: phone)? call"
    private val CALL_CONTROL: List<Pair<Regex, InCallAction>> = listOf(
        Regex("^(?:end|cut|hang up|disconnect|finish|drop|close|stop|terminate)$THE_CALL(?: now)?$|^(?:hang up|hangup|disconnect|end it|cut it|cut it off|call cut|call end|cut the phone|end the phone)$") to InCallAction.END,
        Regex("^(?:stop|end|pause|turn off|switch off|disable|finish) (?:the )?(?:call )?recording(?: the call| this call)?$") to InCallAction.RECORD_STOP,
        Regex(
            "^(?:start )?(?:recording|record)(?: the| this| my)?(?: call| conversation)?$|" +
                "^(?:start|turn on|switch on|enable|begin) (?:the )?(?:call )?recording(?: the call| this call)?$",
        ) to InCallAction.RECORD_START,
        Regex("^unmute(?: the| my)?(?: call| mic| microphone| myself| me)?$|^turn on (?:my |the )?(?:mic|microphone)$") to InCallAction.UNMUTE,
        Regex("^mute(?: the| my)? (?:call|mic|microphone|myself|me)$|^turn off (?:my |the )?(?:mic|microphone)$") to InCallAction.MUTE,
        Regex("^(?:turn on|switch on|enable|put on|use)(?: the)? (?:speaker|speakerphone|loudspeaker|loud speaker)$|^(?:speaker|speakerphone|loudspeaker)(?: on| mode)?$|^put (?:it |the call |this )?on (?:speaker|loudspeaker)$") to InCallAction.SPEAKER_ON,
        Regex("^(?:turn off|switch off|disable)(?: the)? (?:speaker|speakerphone|loudspeaker)$|^(?:speaker|speakerphone|loudspeaker) off$|^take (?:it )?off speaker$") to InCallAction.SPEAKER_OFF,
        Regex("^(?:put|place)(?: the| this)?(?: call| it)? on hold$|^hold(?: the| this)?(?: call)?$") to InCallAction.HOLD,
        Regex("^(?:unhold|take off hold|un hold)(?: the| this)?(?: call)?$|^(?:resume|continue) (?:the |this )?call$|^take (?:the call |it )?off hold$") to InCallAction.UNHOLD,
    )

    // ── Camera ──────────────────────────────────────────────────────────────

    private val SELFIE_RE = Regex("^(?:take|click|capture|snap|shoot)?(?: me)?(?: a| an| my)? ?selfie(?: photo| picture| pic)?$")
    private val PHOTO_RE = Regex("^(?:take|click|capture|snap|shoot)(?: me)?(?: a| an| the)? (?:photo|picture|pic|snap|photograph)(?: now)?$")
    private val VIDEO_RE = Regex("^(?:take|record|start|shoot|capture|make)(?: a| an| the)? (?:video|clip)(?: recording)?(?: for (.+))?$|^start (?:video|recording video|video recording)(?: for (.+))?$")

    private fun parseCamera(b: String): Command? {
        if (SELFIE_RE.matches(b)) return Command.Camera(CameraAction.SELFIE)
        if (PHOTO_RE.matches(b)) return Command.Camera(CameraAction.PHOTO)
        VIDEO_RE.matchEntire(b)?.let { m ->
            val length = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            val secs = if (length.isEmpty()) null else durationSeconds(TextNormalizer.canonical(length)).takeIf { it > 0 }
            return Command.Camera(CameraAction.VIDEO, secs?.coerceAtMost(300))
        }
        return null
    }

    private val CALL_ASSISTANT = Regex("\\b(?:call assistant|call mode|call features|calls? by voice|call announcements?|announce calls)\\b")

    /** "turn on call assistant", "call mode off", "disable call announcements". */
    private fun parseCallAssistantSwitch(b: String): Command? {
        if (!CALL_ASSISTANT.containsMatchIn(b)) return null
        val w = b.split(' ')
        val off = w.any { it in setOf("off", "disable", "stop", "deactivate") }
        val on = w.any { it in setOf("on", "enable", "start", "activate") }
        return Command.SetCallAssistant(
            when {
                off && !on -> OnOff.OFF
                on && !off -> OnOff.ON
                else -> OnOff.TOGGLE
            },
        )
    }

    private val HANDS_FREE = Regex("\\b(?:hands free|handsfree|hands-free|no wake word|without (?:hey )?jarvis|direct mode|direct commands)\\b")

    /** "hands-free on", "turn off hands free mode", "listen without hey jarvis". */
    private fun parseHandsFreeSwitch(b: String): Command? {
        if (!HANDS_FREE.containsMatchIn(b)) return null
        val w = b.split(' ')
        val off = w.any { it in setOf("off", "disable", "stop", "deactivate") }
        val on = w.any { it in setOf("on", "enable", "start", "activate", "listen") }
        return Command.SetHandsFree(
            when {
                off && !on -> OnOff.OFF
                on && !off -> OnOff.ON
                else -> OnOff.TOGGLE
            },
        )
    }

    private fun parseCallControl(b: String): Command? =
        CALL_CONTROL.firstOrNull { it.first.matches(b) }?.let { Command.CallControl(it.second) }

    /** True for commands that are safe to run when heard during a call (the other person might be on speaker). */
    fun allowedDuringCall(c: Command): Boolean = c is Command.CallControl || c is Command.Cancel ||
        (c is Command.Volume) || (c is Command.Info && c.query != InfoQuery.NEXT_ALARM)

    // ── Song recognition ────────────────────────────────────────────────────

    private const val SONG = "(?:song|music|track|tune)"
    private val IDENTIFY_RES = listOf(
        // "what's this song", "what is that song called", "which song is this"
        Regex("^(?:whats|what is|which|what)(?: is)? (?:this|that|the) $SONG(?: playing| called| is (?:this|that|it)| now)?$"),
        Regex("^(?:what|which) $SONG is (?:this|that|playing|it|on)(?: now| right now)?$"),
        // "find the song", "identify this song", "search this song", "shazam this"
        Regex("^(?:find|identify|recognise|recognize|name|search|detect|catch|get)(?: me)?(?: (?:this|that|the|current))? $SONG(?: (?:playing|now|name))?$"),
        Regex("^(?:song search|search song|find song|shazam|shazam (?:this|it|that)(?: song)?|whats playing|what is playing|name that tune|what song)$"),
        // First word cut off by the recognizer: "(find) the song"
        Regex("^(?:this|that|the) song$"),
    )

    // ── Dictionary ──────────────────────────────────────────────────────────

    private val DEFINE_RES = listOf(
        Regex("^(?:what is |whats |what are )?(?:the )?(?:meaning|meanings|definition) of (?:the word )?(.+)$"),
        Regex("^(?:tell me|give me|say|explain) (?:the )?(?:meaning|definition) of (?:the word )?(.+)$"),
        Regex("^(?:look up|search|find) (?:the )?(?:meaning|definition) of (?:the word )?(.+)$"),
        Regex("^what does (?:the word )?(.+?) mean$"),
        Regex("^what is meant by (?:the word )?(.+)$"),
        Regex("^(?:define|meaning|definition) (?:the word )?(.+)$"),
        Regex("^(?:the word )?(\\S+(?: \\S+)?) meaning$"),
    )

    private fun parseDefine(b: String): Command? {
        for (re in DEFINE_RES) {
            val m = re.matchEntire(b) ?: continue
            val word = m.groupValues[1].removeSuffix(" in english").removeSuffix(" in tamil").trim()
            if (word.isNotEmpty()) return Command.Define(word)
        }
        return null
    }

    // ── Web search ──────────────────────────────────────────────────────────

    private const val TARGETS = "google|chrome|brave|youtube|the internet|internet|the web|web|browser|online"

    private val SEARCH_RES = listOf(
        Regex("^search (google|youtube|chrome|brave|the internet|internet|the web|web) for (.+)$"),
        Regex("^(?:search|google|look up|lookup|browse)(?: for| about)? (.+?)(?: (?:on|in|using|with) ($TARGETS))?$"),
        Regex("^(?:give|show|get|find) me (?:a |the |some )?(?:search )?results? (?:on|for|about|of) (.+?)(?: (?:on|in|using) ($TARGETS))?$"),
        Regex("^find (.+?) (?:on|in) ($TARGETS)$"),
        Regex("^play (.+?) on (youtube)$"),
    )

    private fun parseSearch(b: String): Command? {
        for ((i, re) in SEARCH_RES.withIndex()) {
            val m = re.matchEntire(b) ?: continue
            val (query, target) = if (i == 0) m.groupValues[2] to m.groupValues[1] else m.groupValues[1] to m.groupValues.getOrElse(2) { "" }
            val q = query.trim()
            if (q.isEmpty()) continue
            return when (target) {
                "youtube" -> Command.Search(q, SearchSite.YOUTUBE)
                "chrome" -> Command.Search(q, browser = "chrome")
                "brave" -> Command.Search(q, browser = "brave")
                else -> Command.Search(q)
            }
        }
        return null
    }

    // ── Spotify ─────────────────────────────────────────────────────────────

    private val ON_SPOTIFY = Regex("\\s*\\b(?:on|from|in|using|with|via|through) spotify\\b\\s*|^spotify\\s+|\\s+spotify$")
    private val LIKED = Regex("\\b(?:liked|favourite|favorite|saved|loved) (?:songs?|list|music|tracks|playlist)\\b|\\bmy likes\\b|\\bliked list\\b")
    private const val MOOD_WORDS = "party|dance|dancing|energetic|energy|high energy|hype|workout|gym|peppy|upbeat|kuthu|mass|" +
        "chill|chilled|relax|relaxing|calm|lofi|lo fi|sleep|sleepy|soft|sad|emotional|breakup|love|romantic|melody|focus|study"
    private val MOOD = listOf(
        Regex("\\b($MOOD_WORDS) (?:songs?|music|tracks|playlist|vibes?|mood|hits|numbers|beats)\\b"),
        Regex("\\bsomething(?: \\w+){0,2} ($MOOD_WORDS)\\b"),
        Regex("\\b(?:songs?|music) for (?:a |the )?($MOOD_WORDS)\\b"),
    )
    private val TOP = Regex("\\b(?:top|hit|trending|latest|new|popular|chart) (?:\\w+ )?(?:songs|hits|music|tracks)\\b|\\btop hits\\b|\\bhits\\b")

    /** Words for a kind of mood -> what to ask Spotify for. */
    private fun moodQuery(mood: String): String = when (mood) {
        "party", "dance", "dancing", "kuthu", "mass" -> "party hits"
        "energetic", "energy", "high energy", "hype", "workout", "gym", "peppy", "upbeat" -> "energetic hits"
        "chill", "chilled", "relax", "relaxing", "calm", "lofi", "lo fi", "soft" -> "chill songs"
        "sleep", "sleepy" -> "sleep music"
        "sad", "emotional", "breakup" -> "sad songs"
        "love", "romantic", "melody" -> "love songs"
        else -> "focus music"
    }

    /**
     * "play my liked songs", "play believer on spotify", "I'm in party mood, play something
     * energetic", "play top tamil hits". Plain "play a tamil song" stays on the phone's music.
     */
    private fun parseSpotify(b: String): Command? {
        val named = ON_SPOTIFY.containsMatchIn(b)
        if (LIKED.containsMatchIn(b)) return Command.Spotify(liked = true)
        val language = b.split(' ').firstNotNullOfOrNull { com.assistant.nova.core.media.SongTags.SPOKEN[it] }
            ?.takeIf { it != com.assistant.nova.core.media.SongTags.ANIME }
        val lang = language?.replaceFirstChar { it.uppercase() }?.let { "$it " }.orEmpty()
        MOOD.firstNotNullOfOrNull { it.find(b) }?.let { m ->
            return Command.Spotify(lang + moodQuery(m.groupValues[1]))
        }
        // "play top tamil hits", "play anirudh hits", "play top hits from any language"
        val playWords = Regex("^(?:play|put on|shuffle|give me)(?: me)?(?: some| the)? ")
        if (playWords.containsMatchIn(b) && TOP.containsMatchIn(b)) {
            val q = ON_SPOTIFY.replace(playWords.replace(b, ""), " ")
                .replace(Regex("\\b(?:from |in |of )?any language\\b"), "")
                .trim()
            return Command.Spotify(q)
        }
        if (!named) return null
        // "play believer on spotify", "spotify play anirudh songs"
        val what = ON_SPOTIFY.replace(" $b ", " ").trim()
            .replace(Regex("^(?:play|put on|start playing|start|shuffle|open)(?: me)?\\b"), "").trim()
            .removePrefix("some ").trim()
        return Command.Spotify(what.takeUnless { it.isEmpty() || it in setOf("music", "songs", "something", "anything", "it") })
    }

    // ── Music on the phone ──────────────────────────────────────────────────

    private val PLAY_RE = Regex("^(?:play|put on|start playing|shuffle)(?: me)?(?: (.+))?$")
    private val RESUME_WORDS = Regex("^(?:the |some )?(?:music|song|songs|track|video|movie|media|playback|audio|it|again|that|this)$")
    private val LIBRARY_RE = Regex(
        "^(?:some |my |all |all my |all of my |the |any )*(?:downloaded |local |offline |saved |favourite |favorite |own )*" +
            "(?:music|songs|tracks|playlist|mp3s?)(?: (?:on|from|in) (?:my |the )?(?:phone|sd card|storage|memory card|device))?$",
    )
    private val NOT_A_TITLE = setOf("next", "previous", "last", "the next", "the previous")

    private fun parsePlayMusic(b: String): Command? {
        val m = PLAY_RE.matchEntire(b) ?: return null
        var rest = m.groupValues[1].trim()
        // "play believer again", "play it one more time"
        val again = Regex("(?:^| )(?:again|once again|one more time|once more)$")
        if (again.containsMatchIn(rest)) {
            rest = again.replace(rest, "").trim()
            // "play it again", "play that song again": start the current song over
            if (Regex("^(?:it|that|this|the same|the same song|same song|(?:the|that|this) (?:song|track|one))?$").matches(rest)) {
                return Command.Media(MediaAction.PREVIOUS)
            }
        }
        if (rest.isEmpty()) return if (b.startsWith("shuffle")) Command.PlayMusic() else null
        if (RESUME_WORDS.matches(rest)) return null // "play music" = press play
        if (NOT_A_TITLE.any { rest == it || rest.startsWith("$it ") }) return null // "play next song"
        if (LIBRARY_RE.matches(rest)) return Command.PlayMusic()

        // "play music from Files audio English music", "play songs in my Tamil folder", "play English music folder"
        Regex("^(?:(?:my |the |all |all my |some )?(?:songs|music|musics|tracks|audio|mp3s?|files) )?(?:from|in|inside|of) (?:my |the )?(?:files? )?(?:audio )?(.+?)(?: folder)?$")
            .matchEntire(rest)?.let { return Command.PlayMusic(folder = it.groupValues[1].trim()) }
        Regex("^(?:my |the |all )?(.+?) folder$").matchEntire(rest)?.let { return Command.PlayMusic(folder = it.groupValues[1].trim()) }

        Regex("^(?:songs|music|something|anything|tracks) by (.+)$").matchEntire(rest)?.let {
            return Command.PlayMusic(artist = it.groupValues[1].trim())
        }
        rest = rest.removePrefix("the song ").removePrefix("song ").removeSuffix(" song").trim()
        Regex("^(.+?) by (.+)$").matchEntire(rest)?.let {
            return Command.PlayMusic(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        return Command.PlayMusic(rest)
    }

    // ── Messages ────────────────────────────────────────────────────────────

    private val MESSAGE_STARTS: List<Pair<String, Channel?>> = listOf(
        "send a whatsapp message to" to Channel.WHATSAPP,
        "send whatsapp message to" to Channel.WHATSAPP,
        "send a whatsapp to" to Channel.WHATSAPP,
        "send whatsapp to" to Channel.WHATSAPP,
        "whatsapp message to" to Channel.WHATSAPP,
        "message on whatsapp to" to Channel.WHATSAPP,
        "whatsapp" to Channel.WHATSAPP,
        "send a text message to" to Channel.SMS,
        "send text message to" to Channel.SMS,
        "send a text to" to Channel.SMS,
        "send text to" to Channel.SMS,
        "send an sms to" to Channel.SMS,
        "send sms to" to Channel.SMS,
        "send a sms to" to Channel.SMS,
        "sms" to Channel.SMS,
        "text" to Channel.SMS,
        "send a message to" to null,
        "send message to" to null,
        "message" to null,
    ).sortedByDescending { it.first.length }

    private val MESSAGE_SEPARATOR = Regex("\\b(?:saying|that says|that|telling|and say|to say|say|with message|message)\\b")

    private fun parseMessage(b: String): Command? {
        for ((start, startChannel) in MESSAGE_STARTS) {
            if (b != start && !b.startsWith("$start ")) continue
            var rest = b.removePrefix(start).trim().removePrefix("to ").trim()
            val viaWhatsapp = Regex("\\b(?:on|via|through|in|using) whatsapp\\b").containsMatchIn(rest)
            val viaSms = Regex("\\b(?:on|via|through|using|by) (?:sms|text)\\b").containsMatchIn(rest)
            rest = rest.replace(Regex("\\b(?:on|via|through|in|using|by) (?:whatsapp|sms|text)\\b"), " ")
            rest = TextNormalizer.collapse(rest)
            val channel = when {
                viaWhatsapp -> Channel.WHATSAPP
                startChannel != null -> startChannel
                viaSms -> Channel.SMS
                else -> Channel.WHATSAPP // plain "message mom ..." -> WhatsApp
            }

            val sep = MESSAGE_SEPARATOR.find(rest)
            val (who, msg) = if (sep != null && sep.range.first > 0) {
                rest.substring(0, sep.range.first).trim() to rest.substring(sep.range.last + 1).trim()
            } else {
                rest to null
            }
            return Command.Message(cleanName(who), msg?.ifBlank { null }, channel)
        }
        return null
    }

    // ── Calls ───────────────────────────────────────────────────────────────

    private val CALL_RE = Regex("^(?:make (?:a )?)?(?:phone |voice |video )?(?:call|dial)(?: up)?(?: to)?(?: (.+))?$")

    private fun parseCall(b: String): Command? {
        val m = CALL_RE.matchEntire(b) ?: return null
        var who = m.groupValues[1].trim()
        if (who.startsWith("volume")) return null // "call volume up"
        who = who.replace(Regex("\\s+(?:on|from|using) (?:phone|mobile|whatsapp|speaker|sim \\w+)$"), "").trim()
        spokenDigits(who)?.let { return Command.Call(it) }
        return Command.Call(cleanName(who))
    }

    /** Words a misheard name often turns into; never treated as a contact ("call that" -> "Who should I call?"). */
    private val NOT_NAMES = setOf(
        "me", "that", "this", "it", "him", "her", "them", "there", "those", "these", "what", "who", "the", "a",
        "back", "now", "us", "you", "here", "then", "him back", "her back", "someone", "somebody", "anyone",
    )

    private fun cleanName(s: String): String =
        s.removePrefix("my ").removePrefix("the ").trim().takeUnless { it in NOT_NAMES }.orEmpty()

    private val DIGIT_WORDS = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "one" to "1", "two" to "2", "three" to "3",
        "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
    )

    /** "nine eight double seven 6 5" -> "987765"; null if it isn't a phone number. */
    internal fun spokenDigits(s: String): String? {
        if (s.isBlank()) return null
        val sb = StringBuilder()
        var repeat = 1
        for (tk in s.split(' ')) {
            when {
                tk == "plus" && sb.isEmpty() -> sb.append('+')
                tk == "double" -> repeat = 2
                tk == "triple" -> repeat = 3
                tk.isNotEmpty() && tk.all(Char::isDigit) -> {
                    repeat(repeat) { sb.append(tk) }; repeat = 1
                }
                DIGIT_WORDS.containsKey(tk) -> {
                    repeat(repeat) { sb.append(DIGIT_WORDS.getValue(tk)) }; repeat = 1
                }
                else -> return null
            }
        }
        return sb.toString().takeIf { it.count(Char::isDigit) >= 3 }
    }

    // ── Timers and alarms ───────────────────────────────────────────────────

    private val DURATION_RE = Regex("(\\d+)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b")

    private fun durationSeconds(t: String): Int =
        DURATION_RE.findAll(t).sumOf { m ->
            val n = m.groupValues[1].toInt()
            when (m.groupValues[2].first()) {
                'h' -> n * 3600
                'm' -> n * 60
                else -> n
            }
        }

    private val TIMER_STOP = setOf("stop", "cancel", "off", "end", "delete", "remove", "dismiss", "kill", "reset", "clear", "close")
    private val TIMER_RESUME = setOf("resume", "continue", "unpause")

    private fun parseTimer(t: String, w: Set<String>): Command? {
        if ("timer" !in w && "timers" !in w && "countdown" !in w && !t.startsWith("remind me in")) return null
        // "turn off / cancel / stop the timer", "pause the timer", "resume the timer"
        return when {
            w.any { it in TIMER_STOP } -> Command.TimerControl(TimerAction.STOP)
            "pause" in w -> Command.TimerControl(TimerAction.PAUSE)
            w.any { it in TIMER_RESUME } -> Command.TimerControl(TimerAction.RESUME)
            else -> Command.Timer(durationSeconds(t))
        }
    }

    // ── Follow-up answers ───────────────────────────────────────────────────

    /** Commands that are missing something, so Nova would have to ask again. */
    fun isIncomplete(c: Command): Boolean = when (c) {
        is Command.Timer -> c.seconds <= 0
        is Command.Alarm -> c.hour < 0
        is Command.AlarmIn -> c.seconds <= 0
        is Command.Call -> c.who.isBlank()
        is Command.Message -> c.who.isBlank() || c.message.isNullOrBlank()
        is Command.Search -> c.query.isBlank()
        is Command.AddNote -> c.text.isBlank()
        is Command.Spell -> c.word.isBlank()
        is Command.Unknown -> true
        else -> false
    }

    private val NEW_INSTRUCTION = Regex("\\b(?:turn off|switch off|stop|cancel|dismiss|delete|remove|dont|do not|forget it|instead)\\b")

    /**
     * What to run after Nova asked a question ("For how long?") and the user answered.
     * Normally the question's [prefix] + the answer ("set a timer for " + "five minutes"),
     * but if the answer is clearly a new instruction ("turn off the timer") it runs on its
     * own instead, so it can't be glued into the wrong command.
     */
    fun resolveFollowUp(prefix: String, answer: String, unlockPhrase: String? = null, routines: List<String> = emptyList()): String {
        val alone = parseAll(answer, unlockPhrase, routines)
        val aloneKnown = alone.none { it is Command.Unknown }
        if (aloneKnown && NEW_INSTRUCTION.containsMatchIn(TextNormalizer.basic(answer))) return answer
        val combined = parseAll(prefix + answer, unlockPhrase, routines)
        if (combined.any { isIncomplete(it) } && aloneKnown && alone.none { isIncomplete(it) }) return answer
        return prefix + answer
    }

    private fun parseAlarm(t: String, tokens: List<String>, w: Set<String>): Command? {
        if ("volume" in w) return null
        if ("alarm" !in w && !t.contains("wake me")) return null

        // "set an alarm in 20 minutes"
        if (Regex("\\bin \\d+\\s*(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b").containsMatchIn(t)) {
            return Command.AlarmIn(durationSeconds(t))
        }

        val (hour, minute) = clockTime(tokens, w) ?: return Command.Alarm(-1, -1)
        return Command.Alarm(hour, minute)
    }

    /** "7 30 pm", "730", "7 point 30 in the morning" -> 19:30 / 7:30. (-1, -1) if invalid, null if no number. */
    private fun clockTime(tokens: List<String>, w: Set<String>): Pair<Int, Int>? {
        val i = tokens.indexOfFirst { it.all(Char::isDigit) && it.isNotEmpty() }
        if (i < 0) return null
        var n = tokens[i].toInt()
        var hour: Int
        var minute = 0
        if (n >= 100) { // "730" -> 7:30
            hour = n / 100; minute = n % 100
        } else {
            hour = n
            var j = i + 1
            if (tokens.getOrNull(j) == "point") j++ // "7.30" spoken/written as "7 point 30"
            val next = tokens.getOrNull(j)
            if (next != null && next.all(Char::isDigit) && next.isNotEmpty()) {
                n = next.toInt(); if (n in 0..59) minute = n
            }
        }
        val pm = "pm" in w || "evening" in w || "night" in w || "afternoon" in w || "tonight" in w
        val am = "am" in w || "morning" in w
        if (pm && hour in 1..11) hour += 12
        if (am && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return -1 to -1
        return hour to minute
    }

    // ── Reminders ───────────────────────────────────────────────────────────

    private const val DURATION_WORDS = "\\d+\\s*(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)(?:\\s+\\d+\\s*(?:minutes?|mins?|seconds?|secs?))*"

    /** "remind me to call mom at 6 pm", "remind me in 20 minutes to drink water". */
    private fun parseReminder(b: String): Command? {
        if (!b.startsWith("remind me")) return null
        val t = TextNormalizer.canonical(b)
        fun label(s: String) = s.removePrefix("to ").removePrefix("about ").removePrefix("that ")
            .replace("mobiledata", "data").replace("flashlight", "torch").trim().ifEmpty { null }

        // "remind me in 20 minutes to drink water"
        Regex("^remind me in ($DURATION_WORDS)(?: to| about| that)? (.+)$").matchEntire(t)?.let {
            return Command.AlarmIn(durationSeconds(it.groupValues[1]), label(it.groupValues[2]))
        }
        // "remind me to drink water in 20 minutes"
        Regex("^remind me (.+?) in ($DURATION_WORDS)$").matchEntire(t)?.let {
            return Command.AlarmIn(durationSeconds(it.groupValues[2]), label(it.groupValues[1]))
        }
        // "remind me to call mom at 6 pm" (last "at" wins, so "look at the stars at 9" works)
        Regex("^remind me (.+) (?:at|by) (.+)$").matchEntire(t)?.let {
            val timeTokens = it.groupValues[2].split(' ')
            val time = clockTime(timeTokens, timeTokens.toSet())
            if (time != null && time.first >= 0) return Command.Alarm(time.first, time.second, label(it.groupValues[1]))
        }
        // "remind me to call mom" without a time
        Regex("^remind me (.+)$").matchEntire(t)?.let { return Command.Alarm(-1, -1, label(it.groupValues[1])) }
        return Command.Alarm(-1, -1)
    }

    // ── Calculator, conversions, countdowns ─────────────────────────────────

    private val ASK_PREFIX = Regex("^(?:whats|what is|what are|calculate|compute|how much is|how much are|tell me|solve|convert)\\s+")

    private fun parseCalculation(t: String): Command? {
        val expr = ASK_PREFIX.replace(t, "").trim()
        return if (Calculator.evaluate(expr) != null) Command.Calculate(expr) else null
    }

    private fun parseConvert(t: String): Command? {
        val s = ASK_PREFIX.replace(t, "").replace(Regex("(\\d+) point (\\d+)"), "$1.$2")
        val num = "(-?\\d+(?:\\.\\d+)?)"
        Regex("^$num (?:degrees? )?(\\w+) (?:to|in|into) (?:degrees? )?(\\w+)$").matchEntire(s)?.let {
            return convertOf(it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }
        Regex("^how many (\\w+) (?:is|are|in|make|makes|equals?) (?:a |an |one )?$num? ?(?:degrees? )?(\\w+)$").matchEntire(s)?.let {
            return convertOf(it.groupValues[2].ifEmpty { "1" }, it.groupValues[3], it.groupValues[1])
        }
        return null
    }

    private fun convertOf(value: String, from: String, to: String): Command? {
        val f = Units.unit(from) ?: return null
        val t = Units.unit(to) ?: return null
        if (f.kind != t.kind || f == t) return null
        return Command.Convert(value.toDouble(), f.name, t.name)
    }

    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
    )
    private val ORDINALS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8,
        "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14,
        "fifteenth" to 15, "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19,
        "twentieth" to 20, "thirtieth" to 30,
    )
    private val SPECIAL_DAYS = mapOf(
        "christmas" to (12 to 25), "new year" to (1 to 1), "new years" to (1 to 1), "new years day" to (1 to 1),
        "new years eve" to (12 to 31), "valentines day" to (2 to 14), "independence day" to (8 to 15),
        "republic day" to (1 to 26), "childrens day" to (11 to 14), "teachers day" to (9 to 5), "halloween" to (10 to 31),
    )

    private fun parseDaysUntil(t: String): Command? {
        val m = Regex("^(?:how many days|days|how long|how much time)(?: are| is)?(?: there)?(?: left| remaining)? (?:until|till|til|to|for|before|left for|left until|remaining for|is it until) (.+)$")
            .matchEntire(t) ?: return null
        val target = m.groupValues[1].removePrefix("the ").removeSuffix(" day").trim()
        SPECIAL_DAYS[target]?.let { return Command.DaysUntil(it.first, it.second, target) }
        SPECIAL_DAYS[m.groupValues[1].removePrefix("the ").trim()]?.let {
            return Command.DaysUntil(it.first, it.second, m.groupValues[1].removePrefix("the ").trim())
        }
        val tokens = target.replace(Regex("(\\d+)(?:st|nd|rd|th)\\b"), "$1").split(' ').filter { it != "of" && it != "the" }
        val month = tokens.firstNotNullOfOrNull { tk -> MONTHS.indexOfFirst { it == tk || (tk.length >= 3 && it.startsWith(tk)) }.takeIf { it >= 0 } }
            ?: return null
        var day: Int? = tokens.firstOrNull { it.all(Char::isDigit) && it.isNotEmpty() }?.toInt()
        val ordinal = tokens.withIndex().firstNotNullOfOrNull { (i, tk) ->
            ORDINALS[tk]?.let { o -> o + (tokens.getOrNull(i - 1)?.toIntOrNull()?.takeIf { it == 20 || it == 30 } ?: 0) }
        }
        if (ordinal != null && (day == null || day == 20 || day == 30)) day = ordinal
        if (day == null || day !in 1..31) return null
        return Command.DaysUntil(month + 1, day, target)
    }

    private val NEXT_ALARM_RES = listOf(
        Regex("^(?:when is|whats|what is|what time is|show|tell me|show me) (?:my |the )?(?:next )?alarms?(?: set for| at| set)?$"),
        Regex("^(?:do i have|is there|have i set|did i set) (?:an |any )?alarms?(?: set)?(?: for)?(?: tomorrow)?$"),
        Regex("^(?:next alarm|my alarm|my next alarm|what alarms do i have|what alarms are set|when will my alarm ring)$"),
    )

    // ── Notes ───────────────────────────────────────────────────────────────

    private fun parseNotes(b: String): Command? {
        if (Regex("^(?:read|show|what are|tell me|list|open)(?: me)?(?: all)?(?: of)? (?:my |the )?notes$|^(?:my notes|what did i note|what are my notes)$").matches(b)) {
            return Command.ReadNotes
        }
        if (Regex("^(?:clear|delete|remove|erase)(?: all)?(?: of)? (?:my |the )?notes$").matches(b)) return Command.ClearNotes
        Regex("^(?:take|make|add|write)(?: a| down a)? note(?: that| saying| down)?(?: (.+))?$").matchEntire(b)?.let {
            return Command.AddNote(it.groupValues[1].trim())
        }
        Regex("^note (?:down |that )?(.+)$").matchEntire(b)?.let { return Command.AddNote(it.groupValues[1].trim()) }
        Regex("^(?:add|put) (.+?) (?:to|in) (?:my )?notes$").matchEntire(b)?.let { return Command.AddNote(it.groupValues[1].trim()) }
        Regex("^remember (?:that )?(.+)$").matchEntire(b)?.let { return Command.AddNote(it.groupValues[1].trim()) }
        return null
    }

    // ── Spelling ────────────────────────────────────────────────────────────

    private fun parseSpell(b: String): Command? {
        val m = Regex("^(?:how (?:do you|do i|to|would you) spell|spell|spelling of|what is the spelling of|whats the spelling of) (?:the word )?(.+)$")
            .matchEntire(b) ?: return null
        return Command.Spell(m.groupValues[1].trim())
    }

    // ── Navigation / system actions ─────────────────────────────────────────

    private fun parseNavigation(t: String, w: Set<String>): Command? = when {
        "screenshot" in w || ("capture" in w && "screen" in w) -> Command.Navigate(NavAction.SCREENSHOT)
        "lock" in w && ("phone" in w || "screen" in w || "device" in w || "mobile" in w || w.size <= 2) ->
            Command.Navigate(NavAction.LOCK)
        "notifications" in w || "notification" in w -> Command.Navigate(NavAction.NOTIFICATIONS)
        ("recent" in w || "recents" in w || "multitasking" in w) && w.size <= 4 -> Command.Navigate(NavAction.RECENTS)
        t == "home" || t == "go home" || t == "go to home" || t.contains("home screen") -> Command.Navigate(NavAction.HOME)
        t == "back" || t == "go back" -> Command.Navigate(NavAction.BACK)
        else -> null
    }

    // ── Open an app ─────────────────────────────────────────────────────────

    private val OPEN_RE = Regex("^(?:open|launch|run|go to)(?: the)? (.+?)(?: app| application)?$")

    private fun parseOpen(b: String): Command? {
        val m = OPEN_RE.matchEntire(b) ?: return null
        return Command.OpenApp(m.groupValues[1].trim())
    }

    // ── Volume, ringer, brightness ──────────────────────────────────────────

    private fun numberIn(tokens: List<String>): Int? =
        tokens.firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

    /** Numbers up to 10 without "percent" are read as a level out of 10 ("volume 7" = 70%). */
    private fun percentFrom(n: Int, w: Set<String>): Int =
        if ("percent" in w || n > 10) n.coerceIn(0, 100) else (n * 10).coerceIn(0, 100)

    private val VOLUME_CHANGE = setOf(
        "up", "down", "increase", "decrease", "reduce", "raise", "lower", "less", "more", "max", "maximum",
        "minimum", "louder", "quieter", "softer", "higher",
    )

    private fun parseVolume(tokens: List<String>, w: Set<String>): Command? {
        // "decrease the sound", "reduce the music", "song volume up": sound words + a change word
        val soundWord = "sound" in w || "audio" in w || "music" in w || "song" in w
        val changeWord = w.any { it in VOLUME_CHANGE }
        val isVolume = "volume" in w || (soundWord && changeWord) ||
            "louder" in w || "quieter" in w || "softer" in w || "mute" in w || "unmute" in w
        if (!isVolume) return null
        val stream = when {
            "ring" in w || "ringtone" in w || "ringer" in w || "call" in w -> AudioStream.RING
            "alarm" in w -> AudioStream.ALARM
            else -> AudioStream.MEDIA
        }
        val n = numberIn(tokens)
        val action = when {
            "unmute" in w -> Adjust.UNMUTE
            "mute" in w || "silence" in w -> Adjust.MUTE
            "max" in w || "maximum" in w || "full" in w || "highest" in w -> Adjust.MAX
            "min" in w || "minimum" in w || "lowest" in w -> Adjust.MIN
            n != null -> return Command.Volume(Adjust.SET, stream, percentFrom(n, w))
            "up" in w || "increase" in w || "raise" in w || "louder" in w || "higher" in w || "more" in w -> Adjust.UP
            "down" in w || "decrease" in w || "lower" in w || "reduce" in w || "quieter" in w ||
                "softer" in w || "less" in w -> Adjust.DOWN
            "off" in w -> Adjust.MUTE
            "on" in w -> Adjust.UNMUTE
            else -> return null
        }
        return Command.Volume(action, stream)
    }

    private fun parseRinger(w: Set<String>): Command? {
        val off = w.any { it in OFF_WORDS }
        return when {
            "silent" in w -> Command.Ringer(if (off) RingerMode.NORMAL else RingerMode.SILENT)
            "vibrate" in w || "vibration" in w -> Command.Ringer(if (off) RingerMode.NORMAL else RingerMode.VIBRATE)
            "mode" in w && ("ring" in w || "ringer" in w || "normal" in w || "general" in w || "sound" in w) ->
                Command.Ringer(RingerMode.NORMAL)
            else -> null
        }
    }

    private fun parseFeature(tokens: List<String>, w: Set<String>): Command? {
        val feature = tokens.firstNotNullOfOrNull { FEATURE_TOKENS[it] } ?: return null
        val lastOn = tokens.indexOfLast { it in ON_WORDS }
        val lastOff = tokens.indexOfLast { it in OFF_WORDS }
        val state = when {
            lastOn < 0 && lastOff < 0 -> OnOff.TOGGLE
            lastOff > lastOn -> OnOff.OFF
            else -> OnOff.ON
        }
        return Command.SetFeature(feature, state)
    }

    private fun parseBrightness(tokens: List<String>, w: Set<String>): Command? {
        val isBrightness = "brightness" in w || "bright" in w || "brighter" in w || "dim" in w ||
            "dimmer" in w || "darker" in w
        if (!isBrightness) return null
        val n = numberIn(tokens)
        val action = when {
            "max" in w || "maximum" in w || "full" in w || "highest" in w -> Adjust.MAX
            "min" in w || "minimum" in w || "lowest" in w -> Adjust.MIN
            n != null -> return Command.Brightness(Adjust.SET, percentFrom(n, w))
            "up" in w || "increase" in w || "raise" in w || "brighter" in w || "higher" in w || "more" in w -> Adjust.UP
            "down" in w || "decrease" in w || "lower" in w || "reduce" in w || "dim" in w ||
                "dimmer" in w || "darker" in w || "less" in w -> Adjust.DOWN
            else -> return null
        }
        return Command.Brightness(action)
    }

    // ── Media ───────────────────────────────────────────────────────────────

    private fun parseMedia(t: String, w: Set<String>): Command? {
        val noun = w.any { it in MEDIA_NOUNS }
        val short = w.size <= 2
        return when {
            "pause" in w || ("stop" in w && noun) -> Command.Media(MediaAction.PAUSE)
            ("next" in w || "skip" in w) && (noun || short) -> Command.Media(MediaAction.NEXT)
            "previous" in w || (("last" in w || "back" in w) && noun) -> Command.Media(MediaAction.PREVIOUS)
            "play" in w || "resume" in w || ("start" in w && noun) -> Command.Media(MediaAction.PLAY)
            else -> null
        }
    }

    // ── Info ────────────────────────────────────────────────────────────────

    private fun parseInfo(w: Set<String>): Command? = when {
        "storage" in w || "space" in w || ("memory" in w && "left" in w) -> Command.Info(InfoQuery.STORAGE)
        "battery" in w || "charge" in w || "charging" in w -> Command.Info(InfoQuery.BATTERY)
        "time" in w -> Command.Info(InfoQuery.TIME)
        "date" in w || "today" in w || "todays" in w || ("day" in w && "what" in w) -> Command.Info(InfoQuery.DATE)
        else -> null
    }
}
