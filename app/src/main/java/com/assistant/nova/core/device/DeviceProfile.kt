package com.assistant.nova.core.device

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.assistant.nova.core.NovaPrefs
import com.assistant.nova.core.Requirement
import com.assistant.nova.core.action.CallAssistant
import com.assistant.nova.core.media.MusicLibrary
import com.assistant.nova.core.wake.WakeWords
import com.assistant.nova.services.NovaAccessibilityService

/**
 * Looks at this particular phone (brand, Android version, what's installed and allowed)
 * and works out the best way Nova can do each thing on it, plus what still needs setting up.
 */
object DeviceProfile {

    enum class State { OK, NEEDED, RECOMMENDED, MANUAL, INFO }

    sealed interface Fix {
        data class Permissions(val permissions: List<String>) : Fix
        data class Open(val intent: Intent) : Fix
    }

    data class Check(
        val title: String,
        val detail: String,
        val state: State,
        val fix: Fix? = null,
        val fixLabel: String = "Fix",
    )

    data class Section(val title: String, val checks: List<Check>)

    data class Phone(
        val brand: String,
        val model: String,
        val android: String,
        val api: Int,
        val build: String,
        /** OnePlus, OPPO and realme share the same battery and background rules (ColorOS / OxygenOS). */
        val isOnePlusFamily: Boolean,
    )

    /** Marketing names for model codes (Build.MODEL only gives the code, e.g. "CPH2465"). */
    private val MODEL_NAMES = mapOf(
        "CPH2465" to "Nord CE 3 Lite 5G",
        "CPH2467" to "Nord CE 3 Lite 5G",
    )

    fun phone(): Phone {
        val maker = Build.MANUFACTURER.orEmpty()
        val family = listOf(maker, Build.BRAND.orEmpty()).any {
            it.equals("oneplus", true) || it.equals("oppo", true) || it.equals("realme", true)
        }
        val code = Build.MODEL.orEmpty()
        return Phone(
            brand = maker.replaceFirstChar { it.uppercase() },
            model = MODEL_NAMES[code.uppercase()]?.let { "$it ($code)" } ?: code,
            android = Build.VERSION.RELEASE.orEmpty(),
            api = Build.VERSION.SDK_INT,
            build = Build.DISPLAY.orEmpty(),
            isOnePlusFamily = family,
        )
    }

    // ── Individual checks ───────────────────────────────────────────────────

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isAccessibilityEnabled(context: Context): Boolean {
        if (NovaAccessibilityService.instance != null) return true
        val me = ComponentName(context, NovaAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Nova is the phone's digital assistant app. */
    fun isAssistant(context: Context): Boolean =
        context.getSystemService(android.app.role.RoleManager::class.java)
            ?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true

    fun isIgnoringBatteryOptimizations(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun hasOnDeviceSpeech(context: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun hasFlash(context: Context) = try {
        val cm = context.getSystemService(CameraManager::class.java)
        cm.cameraIdList.any { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (_: Exception) {
        false
    }

    private fun isInstalled(context: Context, pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) {
        false
    }

    private fun appSettings(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Whether a feature's requirement is set up on this phone right now. */
    fun isMet(context: Context, r: Requirement): Boolean = when (r) {
        Requirement.MIC -> granted(context, Manifest.permission.RECORD_AUDIO)
        Requirement.ACCESSIBILITY -> isAccessibilityEnabled(context)
        Requirement.CONTACTS -> granted(context, Manifest.permission.READ_CONTACTS) &&
            granted(context, Manifest.permission.CALL_PHONE)
        Requirement.MUSIC -> granted(context, MusicLibrary.PERMISSION)
        Requirement.CALLS -> CallAssistant(context).permissions.all { granted(context, it) }
        Requirement.CALL_ASSISTANT -> NovaPrefs(context).callAssistant
        Requirement.WRITE_SETTINGS -> Settings.System.canWrite(context)
        Requirement.VOICE_UNLOCK -> NovaPrefs(context).voiceUnlock
    }

    /** Items marked NEEDED that aren't done yet: shown as a badge on the home screen. */
    fun missingRequired(context: Context): Int =
        sections(context).sumOf { s -> s.checks.count { it.state == State.NEEDED } }

    /** How Nova stays out of other apps' way: shown so the user knows what to expect. */
    private fun sharing(prefs: NovaPrefs) = listOf(
        Check(
            "Microphone",
            "Nova lets go of the mic during calls and whenever another app records (voice notes, camera video, " +
                "voice typing, screen recorder) and starts listening again after.",
            State.OK,
        ),
        Check(
            "Music and videos",
            "Other apps only get quieter for the few seconds you're giving a command. While something plays out loud, " +
                "Nova needs a clearer “Hey Jarvis” so a movie can't wake it.",
            State.OK,
        ),
        Check(
            "Screen",
            "Nova never draws over other apps and only touches the screen when you give a command " +
                "(Quick Settings tiles, WhatsApp Send, PIN pad${if (prefs.voiceUnlock) "" else " if voice unlock is on"}).",
            State.OK,
        ),
        Check(
            "Banking & UPI apps",
            "Some show a warning while any accessibility app is turned on. That's expected. Those apps can hide their " +
                "screens from Nova, and Nova never needs to read them.",
            State.INFO,
        ),
    )

    // ── Full report ─────────────────────────────────────────────────────────

    fun sections(context: Context): List<Section> {
        val phone = phone()
        val prefs = NovaPrefs(context)
        val mic = granted(context, Manifest.permission.RECORD_AUDIO)
        val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)
        val battery = isIgnoringBatteryOptimizations(context)
        val access = isAccessibilityEnabled(context)
        val contacts = granted(context, Manifest.permission.READ_CONTACTS) && granted(context, Manifest.permission.CALL_PHONE)
        val sms = granted(context, Manifest.permission.SEND_SMS)
        val writeSettings = Settings.System.canWrite(context)
        val dnd = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        val overlay = Settings.canDrawOverlays(context)
        val online = isOnline(context)
        val onDevice = hasOnDeviceSpeech(context)
        val wake = WakeWords.bundled(context)

        val listening = buildList {
            add(
                Check(
                    "Microphone", "Needed to hear the wake word and your commands. Audio stays on the phone.",
                    if (mic) State.OK else State.NEEDED,
                    Fix.Permissions(listOf(Manifest.permission.RECORD_AUDIO)), "Allow",
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    Check(
                        "Notifications", "Android only keeps Nova running while its notification is showing.",
                        if (notif) State.OK else State.NEEDED,
                        Fix.Permissions(listOf(Manifest.permission.POST_NOTIFICATIONS)), "Allow",
                    ),
                )
            }
            add(
                Check(
                    "Battery optimization off",
                    "Otherwise Android stops Nova in the background and it stops hearing you.",
                    if (battery) State.OK else State.NEEDED,
                    Fix.Open(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    ),
                    "Allow",
                ),
            )
            if (phone.isOnePlusFamily) {
                add(
                    Check(
                        "${phone.brand}: background activity & auto launch",
                        "${phone.brand} closes background apps on its own. In App info → Battery usage, turn on " +
                            "“Allow background activity” and “Allow auto launch”. Also lock Nova in Recents " +
                            "(open Recents, tap ⋮ or long-press Nova’s card → Lock).",
                        State.MANUAL, Fix.Open(appSettings(context)), "Open App info",
                    ),
                )
            } else {
                add(
                    Check(
                        "Background activity",
                        "If your phone has its own battery manager, set Nova to “Unrestricted” / “No restrictions”.",
                        State.MANUAL, Fix.Open(appSettings(context)), "Open App info",
                    ),
                )
            }
            add(
                Check(
                    "Long-press power → Nova",
                    if (isAssistant(context)) {
                        "Nova is your digital assistant. Long-press power to talk, and listening restarts by itself after a reboot."
                    } else {
                        "Optional. Make Nova your digital assistant: long-press power talks to Nova, and listening restarts after " +
                            "a reboot without a tap. Then on OnePlus: Settings → Additional settings → Power button → " +
                            "Press and hold → Voice assistant. Note: “Hey Google” stops working while Nova is your assistant."
                    },
                    if (isAssistant(context)) State.OK else State.RECOMMENDED,
                    Fix.Open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)), "Choose",
                ),
            )
            add(
                Check(
                    "Accessibility service",
                    "Lets Nova switch Wi-Fi, Bluetooth, mobile data and airplane mode, press WhatsApp’s Send, " +
                        "open apps, lock the screen and take screenshots. In Accessibility → Downloaded/Installed apps → " +
                        "Nova AI → On. If it’s greyed out: App info → ⋮ → “Allow restricted settings” first.",
                    if (access) State.OK else State.NEEDED,
                    Fix.Open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)), "Open",
                ),
            )
        }

        val musicAllowed = granted(context, MusicLibrary.PERMISSION)
        val chrome = isInstalled(context, NovaPrefs.CHROME_PACKAGE)
        val brave = isInstalled(context, NovaPrefs.BRAVE_PACKAGE)

        val media = listOf(
            Check(
                "Music and audio",
                "Play songs stored on the phone and SD card (“play my songs”, “play Naruto opening”). " +
                    "Nova only reads song titles and artists, not your documents.",
                if (musicAllowed) State.OK else State.RECOMMENDED,
                Fix.Permissions(listOf(MusicLibrary.PERMISSION)), "Allow",
            ),
            Check(
                "Browser for searches",
                "Installed: " + listOfNotNull("Chrome".takeIf { chrome }, "Brave".takeIf { brave }).ifEmpty { listOf("neither Chrome nor Brave") }
                    .joinToString(", ") + ". Using: " + when (prefs.browser) {
                    NovaPrefs.BROWSER_CHROME -> "Chrome"
                    NovaPrefs.BROWSER_BRAVE -> "Brave"
                    else -> "your default browser"
                } + " (change in Settings).",
                State.INFO,
            ),
            Check("Offline dictionary", "About 155,000 English words, spoken meanings with no internet.", State.OK),
        )

        val callPerms = CallAssistant(context).permissions
        val calls = listOf(
            Check(
                "Incoming calls by voice",
                "With Call assistant on (home screen switch), Nova says who's calling and you answer with " +
                    "“answer”, “reject” or “reject and send I'm busy”. Off by default so calls ring normally.",
                if (callPerms.all { granted(context, it) }) State.OK else State.RECOMMENDED,
                Fix.Permissions(callPerms), "Allow",
            ),
            Check(
                "Contacts & phone calls", "Call people by name, e.g. “call mom”. Contacts are read on the phone only.",
                if (contacts) State.OK else State.RECOMMENDED,
                Fix.Permissions(listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)), "Allow",
            ),
            Check(
                "Send SMS", "Send texts without opening the messages app (“text dad that I’m late”).",
                if (sms) State.OK else State.RECOMMENDED,
                Fix.Permissions(listOf(Manifest.permission.SEND_SMS)), "Allow",
            ),
        )

        val controls = listOf(
            Check(
                "Modify system settings", "Brightness and auto-rotate without going through Quick Settings.",
                if (writeSettings) State.OK else State.RECOMMENDED,
                Fix.Open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))), "Allow",
            ),
            Check(
                "Do Not Disturb access", "Silent mode and Do Not Disturb.",
                if (dnd) State.OK else State.RECOMMENDED,
                Fix.Open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)), "Allow",
            ),
            Check(
                "Display over other apps", "Backup permission so Nova can open apps and calls while you’re in another app.",
                if (overlay) State.OK else State.RECOMMENDED,
                Fix.Open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))), "Allow",
            ),
        )

        val speech = buildList {
            add(
                Check(
                    "Internet", if (online) "Connected: the phone’s recognizer is used for best accuracy." else
                        "Offline: Nova uses offline speech recognition.",
                    State.INFO,
                ),
            )
            add(
                Check(
                    "Phone’s offline speech pack",
                    if (onDevice) "Available: accurate offline recognition, good with contact names." else
                        "Not available. Download English offline speech in Google app → Settings → Voice → " +
                        "Offline speech recognition (or Settings → System → Languages → On-device recognition).",
                    if (onDevice) State.OK else State.RECOMMENDED,
                    Fix.Open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)), "Open",
                ),
            )
            add(
                Check(
                    "Built-in offline recognizer (Vosk)",
                    "Bundled with Nova: commands always work with no internet at all.",
                    State.OK,
                ),
            )
            add(
                Check(
                    "Offline voice", "Nova speaks with the phone’s text-to-speech. Install the English (India) voice so it works offline.",
                    State.INFO, Fix.Open(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)), "Voice data",
                ),
            )
            add(
                Check(
                    "Wake word “Hey Jarvis”",
                    if (WakeWords.JARVIS in wake) "Ready, runs fully offline." else "Model missing from the app.",
                    if (WakeWords.JARVIS in wake) State.OK else State.NEEDED,
                ),
            )
            add(
                Check(
                    "Wake word “Hey Nova”",
                    if (WakeWords.NOVA in wake) "Ready, runs fully offline." else
                        "Not trained yet. Train it once (free, see the README) and rebuild Nova.",
                    if (WakeWords.NOVA in wake) State.OK else State.INFO,
                ),
            )
        }

        // How each feature is done on THIS phone
        val tile = if (access) State.OK else State.NEEDED
        val qs = "Taps the Quick Settings tile (Android ${phone.android} doesn’t let apps switch this directly)"
        val features = listOf(
            Check("Calls by name", "Direct call", if (contacts) State.OK else State.RECOMMENDED),
            Check("WhatsApp messages", "Opens the chat and presses Send", if (access && contacts) State.OK else State.RECOMMENDED),
            Check("SMS", if (sms) "Sent directly" else "Opens the message for you to send", if (sms) State.OK else State.INFO),
            Check("Wi-Fi, Bluetooth, mobile data, airplane mode, hotspot, location, NFC, battery saver, dark mode", qs, tile),
            Check(
                "Flashlight", if (hasFlash(context)) "Direct (camera flash)" else "No flash on this phone",
                if (hasFlash(context)) State.OK else State.INFO,
            ),
            Check("Auto-rotate", if (writeSettings) "Direct" else qs, if (writeSettings || access) State.OK else State.RECOMMENDED),
            Check("Do Not Disturb / silent", if (dnd) "Direct" else "Quick Settings tile / needs DND access", if (dnd) State.OK else State.RECOMMENDED),
            Check("Brightness", if (writeSettings) "Direct" else "Needs “Modify system settings”", if (writeSettings) State.OK else State.RECOMMENDED),
            Check("Volume, battery, time", "Direct", State.OK),
            Check("Pause / play / next in any music or video app", "Media buttons, like earphone controls", State.OK),
            Check(
                "Play your own songs", if (musicAllowed) "Nova’s built-in player (phone + SD card)" else "Needs “Music and audio”",
                if (musicAllowed) State.OK else State.RECOMMENDED,
            ),
            Check("Web & YouTube search", "Opens your browser / YouTube", State.OK),
            Check("Word meanings", "Offline dictionary, spoken", State.OK),
            Check("Alarms & timers", "Through your clock app", State.OK),
            Check("Open apps", if (access || overlay) "Direct" else "Only while Nova is on screen", if (access || overlay) State.OK else State.RECOMMENDED),
            Check("Lock screen, screenshot, home, back, notifications", "Accessibility actions", tile),
            Check(
                "Listening while locked",
                if (prefs.pauseScreenOff) "Off: “Pause when the screen is off” is on in Settings" else
                    "On: Nova hears the wake word on the lock screen and with the screen off",
                if (prefs.pauseScreenOff) State.INFO else State.OK,
            ),
            Check(
                "Voice unlock",
                when {
                    !prefs.voiceUnlock -> "Off. Turn it on in Settings to unlock by saying “${prefs.unlockPhrase}” and your PIN"
                    prefs.unlockBlocked -> "Paused after wrong attempts. Unlock by hand once to re-enable"
                    else -> "Say “${prefs.unlockPhrase}” and your PIN. Nova types it on the lock-screen keypad; the PIN is never saved"
                },
                when {
                    !prefs.voiceUnlock -> State.INFO
                    access && !prefs.unlockBlocked -> State.OK
                    else -> State.RECOMMENDED
                },
            ),
        )

        return listOf(
            Section("Needed for 24/7 listening", listening),
            Section("Calls & messages", calls),
            Section("Music, search & dictionary", media),
            Section("Extra controls", controls),
            Section("Speech & offline", speech),
            Section("Sharing the phone with other apps", sharing(prefs)),
            Section("What Nova can control on this phone", features),
        )
    }
}
