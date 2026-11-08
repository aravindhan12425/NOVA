package com.assistant.nova.core

/** What a feature needs before it works. Checked live on the phone by DeviceProfile.isMet(). */
enum class Requirement(val label: String) {
    MIC("Microphone"),
    ACCESSIBILITY("Accessibility service"),
    CONTACTS("Contacts & phone"),
    MUSIC("Music and audio"),
    CALLS("Phone calls by voice"),
    CALL_ASSISTANT("Call assistant switch (home screen)"),
    WRITE_SETTINGS("Modify system settings"),
    VOICE_UNLOCK("Voice unlock (Settings)"),
}

/** One example sentence. [tryable] = safe to run from the "Try" button (no calls, messages, unlocking…). */
data class Example(val text: String, val tryable: Boolean = false)

data class Capability(
    val title: String,
    val description: String,
    val examples: List<Example>,
    val requires: List<Requirement> = emptyList(),
    val offline: Boolean = true,
    val worksLocked: Boolean = true,
)

data class CapabilityGroup(val title: String, val emoji: String, val items: List<Capability>)

/** Everything Nova can do, shown on the "What Nova can do" screen. Keep in sync with CommandParser. */
object Capabilities {

    private fun ex(vararg s: String) = s.map { Example(it) }
    private fun tryEx(vararg s: String) = s.map { Example(it, tryable = true) }

    fun all(unlockPhrase: String): List<CapabilityGroup> = listOf(
        CapabilityGroup(
            "Talking to Nova", "🎙️",
            listOf(
                Capability(
                    "Wake word",
                    "Say “Hey Jarvis” (or “Hey Nova” once it's trained), wait for the beep, then say a command. " +
                        "You can also tap the circle on the home screen, or “Talk now” in the notification.",
                    ex("Hey Jarvis") + tryEx("what can you do") + ex("never mind"),
                    listOf(Requirement.MIC),
                ),
                Capability(
                    "Several things at once",
                    "Join commands with “and” or “then”.",
                    ex("turn on wifi and bluetooth", "torch on then volume up"),
                    listOf(Requirement.ACCESSIBILITY),
                ),
                Capability(
                    "Follow-up questions",
                    "If something's missing, Nova asks (“For how long?”) and you just answer: no wake word needed.",
                    ex("set a timer", "call", "remind me to pay the bill"),
                ),
                Capability(
                    "Routines",
                    "One phrase runs several actions. Create and edit them on the Routines screen.",
                    ex("good night", "good morning", "I'm driving", "movie time"),
                ),
                Capability(
                    "One-tap shortcuts",
                    "Add the “Talk to Nova” tile (Quick Settings → ✏ Edit → drag it in) or the home-screen widget " +
                        "(long-press the home screen → Widgets → Nova AI). One tap and Nova listens.",
                    emptyList(),
                ),
            ),
        ),
        CapabilityGroup(
            "Calls & messages", "📞",
            listOf(
                Capability(
                    "Call assistant on / off",
                    "Off by default, so calls ring normally. Turn it on when you want Nova to announce callers and " +
                        "help during calls: by voice, with the switch on the home screen, or in Settings.",
                    tryEx("turn on call assistant", "turn off call assistant"),
                ),
                Capability(
                    "Answer calls by voice",
                    "When the phone rings Nova says who's calling. Say “answer”, “reject”, “silence” or " +
                        "“reject and text I'll call back”. No wake word needed, works while locked.",
                    ex("answer", "reject", "reject and send I'm in a meeting"),
                    listOf(Requirement.CALL_ASSISTANT, Requirement.CALLS),
                ),
                Capability(
                    "During a call",
                    "Say “Hey Jarvis” on a call, then: end the call, record the call (uses your phone app's own recorder), " +
                        "stop recording, mute, unmute, speaker on or off, hold. Only call commands work during a call.",
                    ex("end the call", "record the call", "stop recording", "mute the call", "speaker on", "put the call on hold"),
                    listOf(Requirement.CALL_ASSISTANT, Requirement.CALLS, Requirement.ACCESSIBILITY),
                ),
                Capability(
                    "Call by name or number",
                    "Finds the contact even if the name is heard slightly wrong. Family words work too: “mom” finds Amma or Mummy.",
                    ex("call mom", "call Aravind", "dial 9 8 7 6 5 4 3 2 1 0"),
                    listOf(Requirement.CONTACTS),
                ),
                Capability(
                    "WhatsApp messages",
                    "Opens the chat with your message filled in and presses Send for you.",
                    ex("message mom saying I'm on my way", "WhatsApp Rahul I'll be late"),
                    listOf(Requirement.CONTACTS, Requirement.ACCESSIBILITY),
                ),
                Capability(
                    "SMS",
                    "Sends the text directly if you allow “Send SMS” in Device check, otherwise opens it for you to send.",
                    ex("text dad that I'm home", "send sms to Priya saying call me"),
                    listOf(Requirement.CONTACTS),
                ),
            ),
        ),
        CapabilityGroup(
            "Phone switches", "📶",
            listOf(
                Capability(
                    "Wi-Fi, Bluetooth, mobile data and more",
                    "Nova taps the Quick Settings tile for you (Android doesn't let apps switch these directly). " +
                        "The tile must be in your Quick Settings panel.",
                    tryEx("turn on Wi-Fi", "Bluetooth off") +
                        ex("mobile data on", "airplane mode on", "hotspot on", "location off") +
                        tryEx("dark mode on") + ex("battery saver on", "NFC on"),
                    listOf(Requirement.ACCESSIBILITY),
                ),
                Capability(
                    "Flashlight",
                    "Switched directly, no setup needed.",
                    tryEx("flashlight on", "torch off"),
                ),
                Capability(
                    "Auto-rotate & Do Not Disturb",
                    "Direct with “Modify system settings” and “Do Not Disturb access”, otherwise through Quick Settings.",
                    ex("auto rotate off", "turn on do not disturb"),
                ),
            ),
        ),
        CapabilityGroup(
            "Sound & screen", "🔊",
            listOf(
                Capability(
                    "Volume",
                    "Media, ring and alarm volume. Numbers up to 10 mean steps out of 10 (“volume 7” = 70%).",
                    tryEx("volume up", "volume down", "volume 5") + ex("mute", "ring volume down", "alarm volume up"),
                ),
                Capability(
                    "Silent & vibrate",
                    "Needs “Do Not Disturb access” in Device check.",
                    ex("silent mode", "vibrate mode", "turn off silent mode"),
                ),
                Capability(
                    "Brightness",
                    "Setting a level turns off adaptive brightness; Nova tells you when it does.",
                    tryEx("brightness 50 percent", "increase brightness") + ex("dim the screen"),
                    listOf(Requirement.WRITE_SETTINGS),
                ),
            ),
        ),
        CapabilityGroup(
            "Music & videos", "🎵",
            listOf(
                Capability(
                    "Control any player",
                    "Works with VLC, MX Player, YouTube, the OnePlus music app and others, locked or unlocked. " +
                        "“Stop” pauses whatever is playing.",
                    tryEx("pause", "play", "next song", "previous song") + ex("stop"),
                ),
                Capability(
                    "Find a song playing nearby",
                    "Hear a song on another device or in a shop? Nova opens Google's song search (or Shazam) " +
                        "and shows the song's page. Nova stays quiet so it doesn't confuse the search.",
                    tryEx("what's this song") + ex("find the song", "which song is this", "what's that song called"),
                    offline = false,
                    worksLocked = false,
                ),
                Capability(
                    "Play your own songs",
                    "Finds music on the phone and the SD card by title, artist or album. Controls appear in the " +
                        "notification and on the lock screen.",
                    tryEx("play my songs") + ex("play Naruto opening", "play Blue Bird by Ikimonogakari", "play songs by Anirudh"),
                    listOf(Requirement.MUSIC),
                ),
            ),
        ),
        CapabilityGroup(
            "Search & words", "🔎",
            listOf(
                Capability(
                    "Web search",
                    "Opens Google results in your browser (choose Chrome or Brave in Settings, or say it).",
                    tryEx("search Black Clover", "search Black Clover on Brave", "play lofi music on YouTube"),
                    offline = false,
                    worksLocked = false,
                ),
                Capability(
                    "Word meanings",
                    "Built-in dictionary of about 155,000 words, answered out loud with no internet.",
                    tryEx("what's the meaning of vilest", "define ephemeral", "what does serendipity mean"),
                ),
            ),
        ),
        CapabilityGroup(
            "Time & info", "⏰",
            listOf(
                Capability(
                    "Quick answers",
                    "Always spoken, even when “After a command” is silent.",
                    tryEx("what's the time", "what's the date", "how much battery", "how much storage is left", "when is my next alarm"),
                ),
                Capability(
                    "Alarms, timers & reminders",
                    "Set through your clock app. Nova says the time back so you know it heard you right.",
                    ex(
                        "set an alarm for 6 30 am", "wake me up at 7", "set a timer for 10 minutes",
                        "remind me to call mom at 6 pm", "remind me in 20 minutes to drink water",
                    ) + tryEx("turn off the timer", "pause the timer", "resume the timer"),
                ),
                Capability(
                    "Calculator",
                    "Say the sum. Works with percent, square roots, powers, lakh and crore.",
                    tryEx("what's 25 times 4", "15 percent of 2000", "square root of 144", "1 lakh divided by 12"),
                ),
                Capability(
                    "Unit conversion",
                    "Length, weight, volume and temperature.",
                    tryEx("convert 5 km to miles", "100 fahrenheit in celsius", "how many feet in 2 metres", "2 kg in pounds"),
                ),
                Capability(
                    "Countdowns",
                    "Days until a date or a holiday (Christmas, New Year, Diwali isn't fixed so say the date).",
                    tryEx("how many days until Christmas", "how many days until 25 December", "days until new year"),
                ),
                Capability(
                    "Notes",
                    "Quick notes saved on your phone only.",
                    ex("take a note buy milk", "remember that the car is parked on level 2") + tryEx("read my notes") + ex("clear my notes"),
                ),
                Capability(
                    "Spelling",
                    "Spells a word letter by letter.",
                    tryEx("how do you spell necessary"),
                ),
            ),
        ),
        CapabilityGroup(
            "Apps & navigation", "📱",
            listOf(
                Capability(
                    "Open apps",
                    "Any app on your phone by name, or a settings page.",
                    tryEx("open YouTube", "open Wi-Fi settings", "open settings"),
                    listOf(Requirement.ACCESSIBILITY),
                    worksLocked = false,
                ),
                Capability(
                    "Camera",
                    "Opens your camera app on screen, then presses the shutter after a spoken “3, 2, 1”. Selfies switch to " +
                        "the front camera. Videos stop after the time you say, or when you tap stop. Phone must be unlocked.",
                    ex("take a photo", "take a selfie", "record a video for 10 seconds", "record a video"),
                    listOf(Requirement.ACCESSIBILITY),
                    worksLocked = false,
                ),
                Capability(
                    "Long-press power → Nova",
                    "Make Nova your digital assistant (Device check → Long-press power → Choose). Then long-press the power " +
                        "button and Nova listens straight away. “Hey Google” stops working while Nova is the assistant.",
                    emptyList(),
                ),
                Capability(
                    "Move around the phone",
                    "Home, back, notifications, recent apps, screenshot and locking.",
                    tryEx("show notifications", "take a screenshot") + ex("go home", "go back", "recent apps", "lock the phone"),
                    listOf(Requirement.ACCESSIBILITY),
                ),
            ),
        ),
        CapabilityGroup(
            "Lock screen", "🔒",
            listOf(
                Capability(
                    "Voice unlock",
                    "Say your trigger phrase and your PIN while the phone is locked. Your PIN is never saved and never " +
                        "shown; after 2 wrong tries it pauses until you unlock by hand.",
                    ex("Hey Jarvis, $unlockPhrase 1 2 3 4"),
                    listOf(Requirement.VOICE_UNLOCK, Requirement.ACCESSIBILITY),
                ),
                Capability(
                    "Works while locked",
                    "Nova keeps listening on the lock screen and with the screen off (unless you pause it in Settings). " +
                        "Music, calls, switches, info and meanings all work without unlocking.",
                    ex("Hey Jarvis, pause", "Hey Jarvis, call mom", "Hey Jarvis, what's the time"),
                ),
            ),
        ),
    )
}
