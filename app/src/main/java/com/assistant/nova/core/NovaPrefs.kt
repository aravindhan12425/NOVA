package com.assistant.nova.core

import android.content.Context

/** User settings, stored on the phone. */
class NovaPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)

    /** The user wants Nova listening 24/7 (restored after reboots and app restarts). */
    var alwaysOn: Boolean
        get() = p.getBoolean("always_on", false)
        set(v) = p.edit().putBoolean("always_on", v).apply()

    var startOnBoot: Boolean
        get() = p.getBoolean("start_on_boot", true)
        set(v) = p.edit().putBoolean("start_on_boot", v).apply()

    var wakeJarvis: Boolean
        get() = p.getBoolean("wake_jarvis", true)
        set(v) = p.edit().putBoolean("wake_jarvis", v).apply()

    var wakeNova: Boolean
        get() = p.getBoolean("wake_nova", true)
        set(v) = p.edit().putBoolean("wake_nova", v).apply()

    /** Wake-word score needed to trigger (0.3 = very sensitive … 0.8 = strict). */
    var threshold: Float
        get() = p.getFloat("threshold", 0.5f)
        set(v) = p.edit().putFloat("threshold", v.coerceIn(0.2f, 0.9f)).apply()

    /** "auto": phone's recognizer when it works, offline Vosk otherwise. "offline": always Vosk. */
    var engine: String
        get() = p.getString("engine", ENGINE_AUTO) ?: ENGINE_AUTO
        set(v) = p.edit().putString("engine", v).apply()

    /**
     * What happens after a command succeeds ("Bluetooth on"): [AFTER_SILENT] (default),
     * [AFTER_TONE] (short beep) or [AFTER_VOICE] (spoken). Answers and problems are always spoken.
     */
    var afterCommand: String
        get() = p.getString("after_command", AFTER_SILENT) ?: AFTER_SILENT
        set(v) = p.edit().putString("after_command", v).apply()

    /**
     * Master switch for everything call-related. Off by default: calls ring and behave
     * exactly as normal, and Nova stays out of the way.
     */
    var callAssistant: Boolean
        get() = p.getBoolean("call_assistant", false)
        set(v) = p.edit().putBoolean("call_assistant", v).apply()

    /** Say who is calling and listen for "answer" / "reject". */
    var announceCalls: Boolean
        get() = p.getBoolean("announce_calls", true)
        set(v) = p.edit().putBoolean("announce_calls", v).apply()

    /** Keep listening during a call for call commands ("end the call", "record the call"). */
    var listenDuringCalls: Boolean
        get() = p.getBoolean("listen_during_calls", true)
        set(v) = p.edit().putBoolean("listen_during_calls", v).apply()

    var speakReplies: Boolean
        get() = p.getBoolean("speak_replies", true)
        set(v) = p.edit().putBoolean("speak_replies", v).apply()

    var pauseScreenOff: Boolean
        get() = p.getBoolean("pause_screen_off", false)
        set(v) = p.edit().putBoolean("pause_screen_off", v).apply()

    var pauseLowBattery: Boolean
        get() = p.getBoolean("pause_low_battery", true)
        set(v) = p.edit().putBoolean("pause_low_battery", v).apply()

    /** Text-to-speech voice name chosen in Settings; empty = pick the best offline voice automatically. */
    var voiceName: String
        get() = p.getString("voice_name", "") ?: ""
        set(v) = p.edit().putString("voice_name", v).apply()

    /** Speaking speed: 1.0 = normal. A little slower than normal is clearer. */
    var voiceRate: Float
        get() = p.getFloat("voice_rate", 0.95f)
        set(v) = p.edit().putFloat("voice_rate", v.coerceIn(0.5f, 1.6f)).apply()

    /** Browser for web searches: [BROWSER_DEFAULT], [BROWSER_CHROME] or [BROWSER_BRAVE]. */
    var browser: String
        get() = p.getString("browser", BROWSER_DEFAULT) ?: BROWSER_DEFAULT
        set(v) = p.edit().putString("browser", v).apply()

    /** Voice unlock: off until the user turns it on. The PIN itself is never stored. */
    var voiceUnlock: Boolean
        get() = p.getBoolean("voice_unlock", false)
        set(v) = p.edit().putBoolean("voice_unlock", v).apply()

    /** Words said before the PIN, e.g. "unlock" -> "Hey Jarvis, unlock 1 2 3 4". */
    var unlockPhrase: String
        get() = p.getString("unlock_phrase", DEFAULT_UNLOCK_PHRASE)?.takeIf { it.isNotBlank() } ?: DEFAULT_UNLOCK_PHRASE
        set(v) = p.edit().putString("unlock_phrase", v.trim()).apply()

    /** Wrong voice-unlock attempts in a row. */
    var unlockFailures: Int
        get() = p.getInt("unlock_failures", 0)
        set(v) = p.edit().putInt("unlock_failures", v).apply()

    /** Set after 2 wrong attempts; cleared when the phone is unlocked by hand. */
    var unlockBlocked: Boolean
        get() = p.getBoolean("unlock_blocked", false)
        set(v) = p.edit().putBoolean("unlock_blocked", v).apply()

    companion object {
        const val AFTER_SILENT = "silent"
        const val AFTER_TONE = "tone"
        const val AFTER_VOICE = "voice"
        const val BROWSER_DEFAULT = "default"
        const val BROWSER_CHROME = "chrome"
        const val BROWSER_BRAVE = "brave"
        const val CHROME_PACKAGE = "com.android.chrome"
        const val BRAVE_PACKAGE = "com.brave.browser"
        const val DEFAULT_UNLOCK_PHRASE = "unlock"
        const val MAX_UNLOCK_FAILURES = 2
        const val ENGINE_AUTO = "auto"
        const val ENGINE_OFFLINE = "offline"
    }
}
