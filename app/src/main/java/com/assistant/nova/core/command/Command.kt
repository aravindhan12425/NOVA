package com.assistant.nova.core.command

/** Phone features Nova can switch on or off. */
enum class Feature(val spoken: String) {
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth"),
    MOBILE_DATA("mobile data"),
    AIRPLANE("airplane mode"),
    HOTSPOT("hotspot"),
    AUTO_ROTATE("auto-rotate"),
    DND("do not disturb"),
    FLASHLIGHT("flashlight"),
    LOCATION("location"),
    BATTERY_SAVER("battery saver"),
    NFC("NFC"),
    DARK_MODE("dark mode"),
}

enum class OnOff { ON, OFF, TOGGLE }

enum class Adjust { UP, DOWN, MAX, MIN, SET, MUTE, UNMUTE }

enum class AudioStream { MEDIA, RING, ALARM }

enum class RingerMode { SILENT, VIBRATE, NORMAL }

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

enum class NavAction { HOME, BACK, RECENTS, NOTIFICATIONS, LOCK, SCREENSHOT }

enum class InfoQuery { TIME, DATE, BATTERY, STORAGE, NEXT_ALARM }

enum class Channel { WHATSAPP, SMS }

enum class SearchSite { WEB, YOUTUBE }

enum class TimerAction { STOP, PAUSE, RESUME }

enum class CameraAction { PHOTO, SELFIE, VIDEO }

/** Things Nova can do to a call that's already connected. */
enum class InCallAction { END, RECORD_START, RECORD_STOP, MUTE, UNMUTE, SPEAKER_ON, SPEAKER_OFF, HOLD, UNHOLD }

/** One thing the user asked Nova to do. Produced by [CommandParser]. */
sealed interface Command {
    /** [who] is a contact name ("mom") or a phone number ("9876543210"). Empty = not said. */
    data class Call(val who: String) : Command

    /**
     * [message] is null when the user didn't use a separator word ("saying", "that"…),
     * e.g. "whatsapp mom I am coming": then [who] holds "mom i am coming" and the
     * executor splits it using the contact list.
     */
    data class Message(val who: String, val message: String?, val channel: Channel) : Command

    data class SetFeature(val feature: Feature, val state: OnOff) : Command

    /** [percent] is set only for [Adjust.SET]. */
    data class Volume(val action: Adjust, val stream: AudioStream = AudioStream.MEDIA, val percent: Int? = null) : Command

    data class Ringer(val mode: RingerMode) : Command

    data class Brightness(val action: Adjust, val percent: Int? = null) : Command

    data class Media(val action: MediaAction) : Command

    /** hour = -1 when no time was given. [label] is set for reminders ("call mom"). */
    data class Alarm(val hour: Int, val minute: Int, val label: String? = null) : Command

    data class AlarmIn(val seconds: Int, val label: String? = null) : Command

    /** Spoken arithmetic, e.g. "25 * 4" in canonical words ("25 times 4"). */
    data class Calculate(val expression: String) : Command

    data class Convert(val value: Double, val from: String, val to: String) : Command

    /** Days from today until the next [day]/[month]; [label] is what the user called it ("Christmas"). */
    data class DaysUntil(val month: Int, val day: Int, val label: String) : Command

    data class AddNote(val text: String) : Command
    data object ReadNotes : Command
    data object ClearNotes : Command

    data class Spell(val word: String) : Command

    /** End / record / mute / speaker / hold the current call. */
    data class CallControl(val action: InCallAction, val message: String? = null) : Command

    /**
     * Open the camera app on screen and press its shutter after a spoken countdown.
     * [seconds] = video length; null = you stop the video yourself.
     */
    data class Camera(val action: CameraAction, val seconds: Int? = null) : Command

    /** "hands-free on / off": the no-wake-word mode. */
    data class SetHandsFree(val state: OnOff) : Command

    /** "turn on / off call assistant". */
    data class SetCallAssistant(val state: OnOff) : Command

    /** Run one of the user's routines ("good night"). */
    data class RunRoutine(val name: String) : Command

    /** seconds = 0 when no duration was given. */
    data class Timer(val seconds: Int) : Command

    /** Stop, pause or resume a running timer. */
    data class TimerControl(val action: TimerAction) : Command

    data class OpenApp(val name: String) : Command

    /** Web search. [browser] is "chrome"/"brave" when the user named one, else null (use Settings). */
    data class Search(val query: String, val site: SearchSite = SearchSite.WEB, val browser: String? = null) : Command

    /** Look up a word in the offline dictionary. */
    data class Define(val word: String) : Command

    /** Recognise a song playing nearby (Google song search / Shazam). */
    data object IdentifySong : Command

    /** Play songs stored on the phone or SD card. Both null = shuffle the whole library. */
    data class PlayMusic(val query: String? = null, val artist: String? = null, val folder: String? = null) : Command

    data class Info(val query: InfoQuery) : Command

    data class Navigate(val action: NavAction) : Command

    /**
     * Unlock the screen by typing [pin] on the lock-screen keypad. Empty pin = the trigger
     * phrase was said without a PIN. toString() hides the PIN so it never reaches logs.
     */
    class Unlock(val pin: String) : Command {
        override fun toString() = "Unlock(pin=${"•".repeat(pin.length)})"
        override fun equals(other: Any?) = other is Unlock && other.pin == pin
        override fun hashCode() = pin.hashCode()
    }

    data object Help : Command

    data object Cancel : Command

    data class Unknown(val text: String) : Command
}
