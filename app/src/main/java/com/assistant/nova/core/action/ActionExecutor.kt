package com.assistant.nova.core.action

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.os.Environment
import android.os.StatFs
import com.assistant.nova.core.command.Calculator
import com.assistant.nova.core.command.Units
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.assistant.nova.UnlockActivity
import com.assistant.nova.core.NovaPrefs
import com.assistant.nova.core.Routines
import com.assistant.nova.core.command.CommandParser
import com.assistant.nova.core.command.Adjust
import com.assistant.nova.core.command.AudioStream
import com.assistant.nova.core.command.Channel
import com.assistant.nova.core.command.Command
import com.assistant.nova.core.command.Feature
import com.assistant.nova.core.command.FuzzyMatcher
import com.assistant.nova.core.command.InfoQuery
import com.assistant.nova.core.command.MediaAction
import com.assistant.nova.core.command.NavAction
import com.assistant.nova.core.command.OnOff
import com.assistant.nova.core.command.RingerMode
import com.assistant.nova.core.command.SearchSite
import com.assistant.nova.core.command.TimerAction
import com.assistant.nova.core.command.InCallAction
import com.assistant.nova.core.command.CameraAction
import android.provider.MediaStore
import com.assistant.nova.core.command.TextNormalizer
import com.assistant.nova.core.device.DeviceProfile
import com.assistant.nova.core.dictionary.Dictionary
import com.assistant.nova.core.media.MusicLibrary
import com.assistant.nova.core.media.NovaPlayer
import com.assistant.nova.services.NovaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What Nova says back, plus anything that must happen after it has finished speaking.
 * [confirmation] = just "done" feedback ("Bluetooth on"): silent unless the user chose to
 * hear confirmations. Answers, questions and problems are always spoken.
 */
data class Reply(
    val speech: String,
    val display: String = speech,
    val after: (suspend () -> Unit)? = null,
    val confirmation: Boolean = false,
    /**
     * Set when Nova asked a question ("For how long?"). Nova then listens again without the
     * wake word and runs [followUp] + the answer, e.g. "set a timer for " + "five minutes".
     */
    val followUp: String? = null,
)

/** A successful "done" reply (see [Reply.confirmation]). */
private fun ok(speech: String, display: String = speech, after: (suspend () -> Unit)? = null) =
    Reply(speech, display, after, confirmation = true)

/** Carries out a [Command] on the phone. Every action here works without internet. */
class ActionExecutor(private val context: Context, private val player: NovaPlayer) {

    private val contacts = ContactsRepo(context)
    private val apps = AppsRepo(context)
    private val music = MusicLibrary(context)
    private val dictionary = Dictionary(context)
    private val prefs = NovaPrefs(context)
    private val notes = Notes(context)
    private val routines = Routines(context)
    private val calls = CallAssistant(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)

    private val torchId: String? = try {
        camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (_: Exception) {
        null
    }
    @Volatile private var torchOn = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) torchOn = enabled
        }
    }

    init {
        try { camera.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper())) } catch (_: Exception) {}
    }

    fun close() {
        try { camera.unregisterTorchCallback(torchCallback) } catch (_: Exception) {}
    }

    suspend fun execute(cmd: Command): Reply = when (cmd) {
        is Command.Call -> call(cmd.who)
        is Command.Message -> message(cmd)
        is Command.SetFeature -> setFeature(cmd.feature, cmd.state)
        // During a call "mute" means your microphone, and volume means the call volume
        is Command.Volume -> when {
            calls.inCall() && cmd.action == Adjust.MUTE -> callControl(InCallAction.MUTE)
            calls.inCall() && cmd.action == Adjust.UNMUTE -> callControl(InCallAction.UNMUTE)
            else -> volume(cmd, inCall = calls.inCall())
        }
        is Command.CallControl -> callControl(cmd.action, cmd.message)
        is Command.Camera -> camera(cmd)
        is Command.SetCallAssistant -> {
            val on = when (cmd.state) {
                OnOff.ON -> true
                OnOff.OFF -> false
                OnOff.TOGGLE -> !prefs.callAssistant
            }
            prefs.callAssistant = on
            // Spoken (not a silent confirmation): it changes how every call behaves
            Reply(
                if (on) "Call assistant is on. I'll announce callers and help during calls."
                else "Call assistant is off. Calls will ring normally.",
            )
        }
        is Command.Ringer -> ringer(cmd.mode)
        is Command.Brightness -> brightness(cmd)
        is Command.Media -> media(cmd.action)
        is Command.Alarm -> alarm(cmd.hour, cmd.minute, cmd.label)
        is Command.AlarmIn -> alarmIn(cmd.seconds, cmd.label)
        is Command.Timer -> timer(cmd.seconds)
        is Command.TimerControl -> timerControl(cmd.action)
        is Command.Calculate -> calculate(cmd.expression)
        is Command.Convert -> convert(cmd)
        is Command.DaysUntil -> daysUntil(cmd)
        is Command.AddNote -> addNote(cmd.text)
        Command.ReadNotes -> readNotes()
        Command.ClearNotes -> { notes.clear(); Reply("I deleted all your notes.") }
        is Command.Spell -> spell(cmd.word)
        is Command.RunRoutine -> runRoutine(cmd.name)
        is Command.OpenApp -> openApp(cmd.name)
        is Command.Info -> info(cmd.query)
        is Command.Navigate -> navigate(cmd.action)
        is Command.Unlock -> unlock(cmd.pin)
        is Command.Search -> search(cmd)
        is Command.Define -> define(cmd.word)
        is Command.PlayMusic -> playMusic(cmd)
        Command.IdentifySong -> identifySong()
        Command.Help -> Reply(
            "You can say things like: call mom, turn on Wi-Fi, set an alarm for 6 30 am, volume up, " +
                "play my songs, search Black Clover, what's the meaning of vilest, or message mom saying I'm on my way.",
        )
        // "Stop" while something is playing means stop the music
        Command.Cancel -> if (player.isPlaying || audio.isMusicActive) media(MediaAction.PAUSE) else Reply("", "Okay")
        is Command.Unknown -> Reply(
            "Sorry, I can't do that yet.",
            if (cmd.text.isBlank()) "Sorry, I didn't catch that." else "I can't do “${cmd.text}” yet.",
        )
    }

    // ── Launching other apps ────────────────────────────────────────────────

    /**
     * Starts an activity from the background. Android blocks this for most apps; it is
     * allowed while Nova's accessibility service is on (or with "display over other apps").
     */
    private fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ctx: Context = NovaAccessibilityService.instance ?: context
        return try {
            ctx.startActivity(intent); true
        } catch (_: Exception) {
            false
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ── Calls ───────────────────────────────────────────────────────────────

    private suspend fun call(who: String): Reply {
        if (who.isBlank()) return Reply("Who should I call?", followUp = "call ")
        val number: String
        val label: String
        if (who.all { it.isDigit() || it == '+' }) {
            number = who
            label = who.toList().joinToString(" ")
        } else {
            if (!contacts.hasPermission()) return needContacts()
            val match = withContext(Dispatchers.IO) { contacts.find(who) }
                ?: return Reply("I couldn't find $who in your contacts.")
            number = match.item.number
            label = match.label
        }
        val direct = granted(Manifest.permission.CALL_PHONE)
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        return ok("Calling $label", after = { launch(intent) })
    }

    private fun needContacts() =
        Reply("I need permission to read your contacts. Open Nova and go to Device check.")

    // ── Messages ────────────────────────────────────────────────────────────

    private suspend fun message(cmd: Command.Message): Reply {
        if (cmd.who.isBlank()) return Reply("Who should I message?", followUp = "message ")
        if (!contacts.hasPermission()) return needContacts()

        val (match, text) = withContext(Dispatchers.IO) {
            if (cmd.message != null) {
                contacts.find(cmd.who) to cmd.message
            } else {
                // "whatsapp mom I am coming": try the first 1-3 words as the name
                val words = cmd.who.split(' ')
                var best: FuzzyMatcher.Match<ContactEntry>? = null
                var used = 0
                for (k in 1..minOf(3, words.size - 1)) {
                    val m = contacts.find(words.take(k).joinToString(" "), 0.8) ?: continue
                    if (best == null || m.score >= best.score) {
                        best = m; used = k
                    }
                }
                best to words.drop(used).joinToString(" ")
            }
        }
        if (match == null) {
            return if (cmd.message == null) {
                Reply("I couldn't tell who that's for. Say, message mom saying I'm on my way.")
            } else {
                Reply("I couldn't find ${cmd.who} in your contacts.")
            }
        }
        if (text.isBlank()) {
            val verb = if (cmd.channel == Channel.SMS) "text" else "whatsapp"
            return Reply("What should the message say?", followUp = "$verb ${match.label} saying ")
        }
        return when (cmd.channel) {
            Channel.WHATSAPP -> whatsapp(match.item, match.label, text)
            Channel.SMS -> sms(match.item, match.label, text)
        }
    }

    private suspend fun whatsapp(contact: ContactEntry, label: String, text: String): Reply {
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { apps.isInstalled(it) }
            ?: return Reply("WhatsApp isn't installed.")
        val phone = whatsappNumber(contact.number)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(text)}")
        if (!launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))) return Reply("I couldn't open WhatsApp.")
        val svc = NovaAccessibilityService.instance
            ?: return Reply("I opened the chat with $label. Tap send. Turn on Nova in Accessibility settings so I can send it myself.")
        return if (svc.pressWhatsAppSend(pkg)) {
            ok("Sent to $label on WhatsApp.")
        } else {
            Reply("I opened the chat with $label, but couldn't press send.")
        }
    }

    /** WhatsApp needs the number with country code and no "+" or spaces. */
    private fun whatsappNumber(raw: String): String {
        val plus = raw.trim().startsWith("+")
        var digits = raw.filter { it.isDigit() }
        if (plus) return digits
        if (digits.startsWith("00")) return digits.drop(2)
        val cc = countryCallingCode()
        if (digits.startsWith("0") && digits.length == 11) digits = digits.drop(1)
        return if (digits.length == 10 && cc != null) cc + digits else digits
    }

    private fun countryCallingCode(): String? {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val iso = (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso ?: "").lowercase(Locale.ROOT)
        return mapOf(
            "in" to "91", "us" to "1", "ca" to "1", "gb" to "44", "ae" to "971", "sg" to "65", "my" to "60",
            "au" to "61", "lk" to "94", "sa" to "966", "qa" to "974", "kw" to "965", "om" to "968",
            "bh" to "973", "np" to "977", "bd" to "880", "pk" to "92", "de" to "49", "fr" to "33",
        )[iso] ?: if (iso.isEmpty()) "91" else null
    }

    @Suppress("DEPRECATION")
    private fun sms(contact: ContactEntry, label: String, text: String): Reply {
        if (!granted(Manifest.permission.SEND_SMS)) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.number}")).putExtra("sms_body", text)
            launch(intent)
            return Reply("I opened the message to $label. Tap send.")
        }
        return try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            manager.sendMultipartTextMessage(contact.number, null, manager.divideMessage(text), null, null)
            ok("Text sent to $label.")
        } catch (e: Exception) {
            Reply("I couldn't send the text to $label.")
        }
    }

    // ── Toggles ─────────────────────────────────────────────────────────────

    /** Current on/off state read straight from the system, or null if Android won't tell us. */
    @Suppress("DEPRECATION")
    private fun currentState(f: Feature): Boolean? = try {
        when (f) {
            Feature.WIFI -> context.getSystemService(WifiManager::class.java).isWifiEnabled
            Feature.BLUETOOTH -> context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
            Feature.MOBILE_DATA -> context.getSystemService(TelephonyManager::class.java)?.isDataEnabled
            Feature.AIRPLANE -> Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            Feature.LOCATION -> context.getSystemService(LocationManager::class.java).isLocationEnabled
            Feature.BATTERY_SAVER -> context.getSystemService(PowerManager::class.java).isPowerSaveMode
            Feature.DND -> notifications.currentInterruptionFilter.let {
                it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }
            Feature.AUTO_ROTATE ->
                Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            Feature.DARK_MODE -> (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            Feature.NFC -> NfcAdapter.getDefaultAdapter(context)?.isEnabled
            Feature.FLASHLIGHT -> torchOn
            Feature.HOTSPOT -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"
    private fun Feature.title() = spoken.replaceFirstChar { it.uppercase() }

    private suspend fun setFeature(f: Feature, state: OnOff): Reply {
        val current = currentState(f)
        val desired: Boolean? = when (state) {
            OnOff.ON -> true
            OnOff.OFF -> false
            OnOff.TOGGLE -> current?.not()
        }
        if (desired != null && current == desired) return ok("${f.title()} is already ${onOff(desired)}.")

        when (f) {
            Feature.FLASHLIGHT -> return torch(desired ?: !torchOn)
            Feature.DND -> if (notifications.isNotificationPolicyAccessGranted) {
                val on = desired ?: true
                notifications.setInterruptionFilter(
                    if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
                )
                return ok("Do not disturb is ${onOff(on)}.")
            }
            Feature.AUTO_ROTATE -> if (Settings.System.canWrite(context)) {
                val on = desired ?: true
                Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
                return ok("Auto-rotate is ${onOff(on)}.")
            }
            else -> {}
        }
        return viaQuickSettings(f, desired)
    }

    /**
     * Since Android 10 apps may not switch Wi-Fi, mobile data, airplane mode etc. directly,
     * so Nova pulls down Quick Settings and taps the tile, like you would.
     */
    private suspend fun viaQuickSettings(f: Feature, desired: Boolean?): Reply {
        val svc = NovaAccessibilityService.instance
        if (svc == null) {
            launch(settingsIntent(f))
            return Reply(
                "I need Nova's accessibility service for that, so I opened ${f.spoken} settings for you.",
                "Opened ${f.spoken} settings. Turn on Nova in Accessibility so I can switch it myself.",
            )
        }
        return when (svc.setQuickSetting(f, desired) { currentState(f) }) {
            NovaAccessibilityService.QsResult.ALREADY ->
                ok(if (desired != null) "${f.title()} is already ${onOff(desired)}." else "${f.title()} is unchanged.")
            NovaAccessibilityService.QsResult.NOT_FOUND ->
                Reply("I couldn't find the ${f.spoken} tile. Add it to your Quick Settings panel and try again.")
            NovaAccessibilityService.QsResult.NO_EFFECT -> Reply(
                "I tapped the ${f.spoken} tile but it didn't ${if (desired == false) "turn off" else "turn on"}." +
                    if (f == Feature.WIFI && desired != false) " If your hotspot is on, try turning it off first." else "",
            )
            NovaAccessibilityService.QsResult.DONE -> {
                if (desired == null) return ok("Toggled ${f.spoken}.")
                // Confirm with the system where we can (Wi-Fi takes a moment to switch)
                var now = currentState(f)
                var tries = 0
                while (now != null && now != desired && tries++ < 8) {
                    delay(300)
                    now = currentState(f)
                }
                if (now != null && now != desired) {
                    Reply("I tapped the ${f.spoken} tile but it still looks ${onOff(now)}.")
                } else {
                    ok("${f.title()} ${onOff(desired)}.")
                }
            }
        }
    }

    fun settingsIntent(f: Feature): Intent {
        val panel = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        return Intent(
            when (f) {
                Feature.WIFI -> if (panel) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                Feature.MOBILE_DATA -> if (panel) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
                Feature.NFC -> if (panel) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
                Feature.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
                Feature.AIRPLANE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                Feature.HOTSPOT -> Settings.ACTION_WIRELESS_SETTINGS
                Feature.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                Feature.BATTERY_SAVER -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                Feature.DND -> Settings.ACTION_SOUND_SETTINGS
                Feature.AUTO_ROTATE, Feature.DARK_MODE, Feature.FLASHLIGHT -> Settings.ACTION_DISPLAY_SETTINGS
            },
        )
    }

    private fun torch(on: Boolean): Reply {
        val id = torchId ?: return Reply("This phone has no flashlight I can use.")
        return try {
            camera.setTorchMode(id, on)
            torchOn = on
            ok("Flashlight ${onOff(on)}.")
        } catch (_: Exception) {
            Reply("The flashlight is busy. Close the camera and try again.")
        }
    }

    // ── Sound ───────────────────────────────────────────────────────────────

    private fun volume(cmd: Command.Volume, inCall: Boolean = false): Reply {
        val stream = when (cmd.stream) {
            AudioStream.MEDIA -> if (inCall) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            AudioStream.RING -> AudioManager.STREAM_RING
            AudioStream.ALARM -> AudioManager.STREAM_ALARM
        }
        val name = when (cmd.stream) {
            AudioStream.MEDIA -> "Volume"
            AudioStream.RING -> "Ring volume"
            AudioStream.ALARM -> "Alarm volume"
        }
        val max = audio.getStreamMaxVolume(stream)
        val flags = AudioManager.FLAG_SHOW_UI
        try {
            when (cmd.action) {
                Adjust.UP -> repeat(2) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags) }
                Adjust.DOWN -> repeat(2) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags) }
                Adjust.MUTE -> {
                    audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
                    return Reply("", "$name muted")
                }
                Adjust.UNMUTE -> {
                    audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
                    if (audio.getStreamVolume(stream) == 0) audio.setStreamVolume(stream, (max * 0.3).roundToInt(), flags)
                }
                Adjust.MAX -> audio.setStreamVolume(stream, max, flags)
                Adjust.MIN -> audio.setStreamVolume(stream, 0, flags)
                Adjust.SET -> audio.setStreamVolume(stream, ((cmd.percent ?: 50) * max / 100.0).roundToInt(), flags)
            }
        } catch (_: SecurityException) {
            return needDndAccess()
        }
        val pct = (audio.getStreamVolume(stream) * 100.0 / max).roundToInt()
        return ok("$name $pct percent.")
    }

    private fun needDndAccess(): Reply {
        launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        return Reply("I need Do Not Disturb access for that. I opened the setting: allow Nova, then ask again.")
    }

    private fun ringer(mode: RingerMode): Reply = try {
        audio.ringerMode = when (mode) {
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        }
        ok(
            when (mode) {
                RingerMode.SILENT -> "Phone is on silent."
                RingerMode.VIBRATE -> "Phone is on vibrate."
                RingerMode.NORMAL -> "Ringer is back on."
            },
        )
    } catch (_: SecurityException) {
        needDndAccess()
    }

    // ── Brightness ──────────────────────────────────────────────────────────

    /** OnePlus and some other phones use a bigger brightness scale than the usual 0-255. */
    private val brightnessMax: Int by lazy {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        (if (id != 0) res.getInteger(id) else 255).takeIf { it > 0 } ?: 255
    }

    private fun brightness(cmd: Command.Brightness): Reply {
        if (!Settings.System.canWrite(context)) {
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            return Reply("I need permission to change system settings. I opened it: allow Nova, then ask again.")
        }
        val cr = context.contentResolver
        val max = brightnessMax
        // Android can only set an exact level with adaptive brightness off: say so instead of changing it silently
        val wasAdaptive = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, max / 2)
        val step = max / 5
        val target = when (cmd.action) {
            Adjust.UP -> cur + step
            Adjust.DOWN -> cur - step
            Adjust.MAX -> max
            Adjust.MIN -> 1
            Adjust.SET -> (cmd.percent ?: 50) * max / 100
            else -> cur
        }.coerceIn(1, max)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        val level = "Brightness ${(target * 100.0 / max).roundToInt()} percent."
        // Turning off adaptive brightness is worth saying out loud; a plain level change isn't
        return if (wasAdaptive) Reply("$level I turned off adaptive brightness to do that.") else ok(level)
    }

    // ── Media ───────────────────────────────────────────────────────────────

    private fun media(action: MediaAction): Reply {
        val code = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        // No spoken reply: talking over the music would be annoying
        val label = when (action) {
            MediaAction.PLAY -> "Playing"
            MediaAction.PAUSE -> "Paused"
            MediaAction.NEXT -> "Next track"
            MediaAction.PREVIOUS -> "Previous track"
        }
        return Reply("", label)
    }

    // ── Alarms and timers ───────────────────────────────────────────────────

    private fun timeLabel(hour: Int, minute: Int): String {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }
        return SimpleDateFormat("h:mm a", Locale.ENGLISH).format(c.time)
    }

    // Alarms, timers and reminders always say the time back (not a silent confirmation),
    // so a misheard "6 30" vs "7 30" is noticed straight away.
    private fun alarm(hour: Int, minute: Int, label: String? = null): Reply {
        if (hour < 0) {
            return Reply(
                if (label != null) "At what time?" else "For what time?",
                followUp = if (label != null) "remind me to $label at " else "set an alarm for ",
            )
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label?.replaceFirstChar { it.uppercase() } ?: "Nova")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!launch(intent)) return Reply("I couldn't find a clock app.")
        return Reply(
            if (label != null) "Okay, I'll remind you to $label at ${timeLabel(hour, minute)}."
            else "Alarm set for ${timeLabel(hour, minute)}.",
        )
    }

    private fun alarmIn(seconds: Int, label: String? = null): Reply {
        if (seconds <= 0) return Reply("In how long?", followUp = "set an alarm in ")
        val c = Calendar.getInstance().apply { add(Calendar.SECOND, seconds) }
        return alarm(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), label)
    }

    private fun durationLabel(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour${if (it > 1) "s" else ""}" },
            m.takeIf { it > 0 }?.let { "$it minute${if (it > 1) "s" else ""}" },
            s.takeIf { it > 0 }?.let { "$it second${if (it > 1) "s" else ""}" },
        ).joinToString(" ")
    }

    private fun timer(seconds: Int): Reply {
        if (seconds <= 0) return Reply("For how long?", followUp = "set a timer for ")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtMost(86_400))
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Nova")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(intent)) Reply("Timer set for ${durationLabel(seconds)}.") else Reply("I couldn't find a clock app.")
    }

    /**
     * Android has no standard way to stop a running timer (only one that's ringing), so Nova
     * opens the clock app's timer screen and presses its button: "Reset" on OnePlus Clock,
     * "Delete"/"Stop" on Google Clock. A ringing timer is dismissed directly.
     */
    private suspend fun timerControl(action: TimerAction): Reply {
        val svc = NovaAccessibilityService.instance
        if (svc == null) {
            if (action == TimerAction.STOP) launch(Intent(AlarmClock.ACTION_DISMISS_TIMER))
            return Reply("I need Nova's accessibility service to control timers. Turn it on in Device check.")
        }
        // A ringing timer shows its own Stop / Dismiss button
        if (action == TimerAction.STOP && svc.hasLabel(listOf("stop", "dismiss"))) {
            if (svc.waitAndClick(listOf("stop", "dismiss"), 500)) return ok("Timer stopped.")
        }
        if (!launch(Intent(AlarmClock.ACTION_SHOW_TIMERS))) return Reply("I couldn't open your clock app.")
        delay(900)
        val stopLabels = listOf("reset", "delete", "cancel", "stop", "remove", "dismiss", "delete timer")
        // No Reset/Delete button means there's no timer at all
        var hasTimer = false
        val deadline = android.os.SystemClock.uptimeMillis() + 2_000
        while (!hasTimer && android.os.SystemClock.uptimeMillis() < deadline) {
            hasTimer = svc.hasLabel(stopLabels)
            if (!hasTimer) delay(250)
        }
        val reply = when {
            !hasTimer -> Reply("You don't have a timer running.")
            action == TimerAction.STOP ->
                if (svc.waitAndClick(stopLabels, 1_500)) ok("Timer stopped.") else Reply("I couldn't find the stop button for the timer.")
            action == TimerAction.PAUSE ->
                if (svc.waitAndClick(listOf("pause"), 1_500)) ok("Timer paused.") else Reply("The timer isn't running.")
            else ->
                if (svc.waitAndClick(listOf("resume", "start", "continue"), 1_500)) ok("Timer resumed.") else Reply("The timer isn't paused.")
        }
        delay(400)
        svc.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) // back to where you were
        return reply
    }

    // ── Apps ────────────────────────────────────────────────────────────────

    private suspend fun openApp(name: String): Reply {
        val canon = TextNormalizer.canonical(name).removeSuffix(" settings").removeSuffix(" setting").trim()
        if (canon == "settings" || canon == "setting") {
            launch(Intent(Settings.ACTION_SETTINGS)); return ok("Opening settings.")
        }
        featureByToken(canon)?.let { f ->
            launch(settingsIntent(f)); return ok("Opening ${f.spoken} settings.")
        }
        if (canon == "dialer" || canon == "phone" || canon == "dial pad" || canon == "keypad") {
            launch(Intent(Intent.ACTION_DIAL)); return ok("Opening the dialer.")
        }
        val match = withContext(Dispatchers.IO) { apps.find(name) }
            ?: return Reply("I couldn't find an app called $name.")
        val intent = context.packageManager.getLaunchIntentForPackage(match.item.packageName)
            ?: return Reply("I can't open ${match.label}.")
        return if (launch(intent)) ok("Opening ${match.label}.") else Reply("I couldn't open ${match.label}.")
    }

    private fun featureByToken(token: String): Feature? = when (token) {
        "wifi" -> Feature.WIFI
        "bluetooth" -> Feature.BLUETOOTH
        "mobiledata" -> Feature.MOBILE_DATA
        "airplane" -> Feature.AIRPLANE
        "hotspot" -> Feature.HOTSPOT
        "location" -> Feature.LOCATION
        "batterysaver" -> Feature.BATTERY_SAVER
        "nfc" -> Feature.NFC
        "dnd" -> Feature.DND
        "darkmode" -> Feature.DARK_MODE
        "autorotate" -> Feature.AUTO_ROTATE
        else -> null
    }

    // ── Info ────────────────────────────────────────────────────────────────

    private fun info(q: InfoQuery): Reply = when (q) {
        InfoQuery.TIME -> Reply("It's ${SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())}.")
        InfoQuery.DATE -> Reply("Today is ${SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(Date())}.")
        InfoQuery.BATTERY -> {
            val bm = context.getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            Reply("Battery is at $level percent${if (bm.isCharging) " and charging" else ""}.")
        }
        InfoQuery.STORAGE -> {
            val stat = StatFs(Environment.getDataDirectory().path)
            val gb = 1024.0 * 1024 * 1024
            val free = stat.availableBytes / gb
            val total = stat.totalBytes / gb
            Reply("You have ${Calculator.format(free)} GB free out of ${Calculator.format(total)} GB.")
        }
        InfoQuery.NEXT_ALARM -> {
            val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock
            if (next == null) {
                Reply("You don't have any alarms set.")
            } else {
                val at = Calendar.getInstance().apply { timeInMillis = next.triggerTime }
                val today = Calendar.getInstance()
                val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
                fun sameDay(a: Calendar, b: Calendar) =
                    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
                val day = when {
                    sameDay(at, today) -> "today"
                    sameDay(at, tomorrow) -> "tomorrow"
                    else -> "on " + SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(at.time)
                }
                Reply("Your next alarm is $day at ${SimpleDateFormat("h:mm a", Locale.ENGLISH).format(at.time)}.")
            }
        }
    }

    // ── Camera ──────────────────────────────────────────────────────────────

    private val shutterLabels = listOf("shutter button", "shutter", "take photo", "take picture", "capture", "record", "start recording")

    /**
     * Opens your camera app (visible on screen), picks the front camera for selfies, then
     * presses the shutter after a spoken "3, 2, 1". Only works with the phone unlocked.
     * Videos stop after the length you said, or when you tap stop (while recording, the
     * camera app has the microphone, so Nova can't hear "stop").
     */
    private suspend fun camera(cmd: Command.Camera): Reply {
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            return Reply("Unlock your phone first, then ask again.")
        }
        val svc = NovaAccessibilityService.instance
            ?: return Reply("I need Nova's accessibility service to press the camera button. Turn it on in Device check.")
        val action = if (cmd.action == CameraAction.VIDEO) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        if (!launch(Intent(action))) return Reply("I couldn't open your camera app.")

        // Wait for the camera to be ready (its shutter button appears)
        var ready = false
        repeat(16) { if (!ready) { delay(250); ready = svc.hasLabel(shutterLabels) } }
        if (!ready) return Reply("The camera opened, but I couldn't find its shutter button. Tap it to take the picture.")

        if (cmd.action == CameraAction.SELFIE) {
            // OnePlus Camera labels its switch button with the camera in use ("Rear Camera")
            val switch = svc.findButton(listOf("rear camera", "front camera", "switch camera", "flip camera"))
            if (switch?.label == "rearcamera" || switch?.label == "switchcamera" || switch?.label == "flipcamera") {
                svc.clickByLabel(listOf("rear camera", "switch camera", "flip camera"))
                repeat(8) { if (svc.findButton(listOf("front camera"))?.label != "frontcamera") delay(250) }
            }
        }

        return when (cmd.action) {
            CameraAction.PHOTO, CameraAction.SELFIE -> Reply(
                "3, 2, 1.", "Photo",
                after = { svc.clickByLabel(shutterLabels) },
            )
            CameraAction.VIDEO -> {
                val secs = cmd.seconds
                Reply(
                    if (secs != null) "Recording for ${durationLabel(secs)} in 3, 2, 1." else "Recording in 3, 2, 1. Tap stop when you're done.",
                    "Video",
                    after = {
                        if (svc.clickByLabel(shutterLabels) && secs != null) {
                            delay(secs * 1000L)
                            svc.clickByLabel(listOf("shutter button", "shutter", "stop recording", "stop", "record"))
                        }
                    },
                )
            }
        }
    }

    // ── Controlling a connected call ────────────────────────────────────────

    /**
     * End, record, mute, speaker, hold. Ending uses Android's own call API; the rest press
     * the phone app's buttons (Google Phone's Record button uses the phone's own recorder,
     * which records both sides). Each button's on/off state is read first, so "stop
     * recording" can never start a recording.
     */
    private suspend fun callControl(a: InCallAction, message: String? = null): Reply {
        if (!calls.inCall()) {
            // "start recording" with no call means a video
            return when (a) {
                InCallAction.RECORD_START -> camera(Command.Camera(CameraAction.VIDEO))
                InCallAction.RECORD_STOP -> Reply("Tap stop on the camera screen.")
                else -> Reply("You're not on a call.")
            }
        }
        if (!prefs.callAssistant) return Reply("Call assistant is off. Say, turn on call assistant.")
        val svc = NovaAccessibilityService.instance
        if (a == InCallAction.END) {
            val ended = calls.reject() || svc?.clickByLabel(listOf("end call", "hang up", "end")) == true
            if (!ended) return Reply("I couldn't end the call.")
            if (message == null) return ok("Call ended.")
            // The call log gets the call a moment after it ends
            var number: String? = null
            repeat(8) { if (number == null) { delay(250); number = calls.lastCallNumber() } }
            return if (calls.text(number, message)) ok("Call ended. Sent “$message”.") else Reply("Call ended, but I couldn't send the text.")
        }
        if (svc == null) return Reply("I need Nova's accessibility service for that. Turn it on in Device check.")
        calls.showCallScreen()
        delay(700)

        val (labels, desired, name) = when (a) {
            InCallAction.RECORD_START, InCallAction.RECORD_STOP -> Triple(
                listOf("stop recording", "record", "record call", "start recording", "recording", "call recording"),
                a == InCallAction.RECORD_START, "record",
            )
            InCallAction.MUTE, InCallAction.UNMUTE -> Triple(listOf("mute", "unmute"), a == InCallAction.MUTE, "mute")
            InCallAction.SPEAKER_ON, InCallAction.SPEAKER_OFF ->
                Triple(listOf("speaker", "speakerphone", "loudspeaker"), a == InCallAction.SPEAKER_ON, "speaker")
            InCallAction.HOLD, InCallAction.UNHOLD -> Triple(listOf("hold", "unhold", "resume"), a == InCallAction.HOLD, "hold")
            InCallAction.END -> error("handled above")
        }
        val button = svc.findButton(labels)
        if (button == null) {
            android.util.Log.w("NovaCall", "No $name button. Call screen shows: ${svc.visibleLabels("dialer")}")
            // Direct fallbacks where Android allows them
            when (a) {
                InCallAction.MUTE, InCallAction.UNMUTE -> {
                    audio.isMicrophoneMute = desired; return ok(if (desired) "Muted." else "Unmuted.")
                }
                InCallAction.SPEAKER_ON, InCallAction.SPEAKER_OFF -> {
                    @Suppress("DEPRECATION")
                    audio.isSpeakerphoneOn = desired; return ok(if (desired) "Speaker on." else "Speaker off.")
                }
                else -> return Reply(
                    if (name == "record") "Your phone app doesn't show a Record button on this call." else "I couldn't find the $name button.",
                )
            }
        }
        // Current state: the button's own on/off, or its label ("Stop recording", "Unmute", "Resume" mean it's on)
        val on: Boolean? = button.checked ?: when (button.label) {
            "stoprecording", "recording", "unmute", "unhold", "resume" -> true
            "startrecording" -> false
            else -> null
        }
        val done = when (a) {
            InCallAction.RECORD_START -> "Recording the call."
            InCallAction.RECORD_STOP -> "Recording stopped."
            InCallAction.MUTE -> "Muted."
            InCallAction.UNMUTE -> "Unmuted."
            InCallAction.SPEAKER_ON -> "Speaker on."
            InCallAction.SPEAKER_OFF -> "Speaker off."
            InCallAction.HOLD -> "Call on hold."
            InCallAction.UNHOLD -> "Call resumed."
            InCallAction.END -> ""
        }
        if (on == desired) {
            return ok(
                when (a) {
                    InCallAction.RECORD_START -> "The call is already being recorded."
                    InCallAction.MUTE -> "You're already muted."
                    InCallAction.SPEAKER_ON -> "Speaker is already on."
                    InCallAction.HOLD -> "The call is already on hold."
                    else -> "It's already off."
                },
            )
        }
        if (on == null && !desired) {
            // Can't tell whether it's on: pressing could turn it ON instead of off
            return Reply("I can't tell if the $name is on. Check the call screen.")
        }
        return if (svc.clickByLabel(listOf(button.label))) ok(done) else Reply("I couldn't press the $name button.")
    }

    // ── Routines ────────────────────────────────────────────────────────────

    /**
     * Runs every action of a routine in order. Quiet like any confirmation, but answers
     * ("when is my next alarm") and problems ("couldn't find the Wi-Fi tile") are spoken.
     */
    private suspend fun runRoutine(name: String): Reply {
        val routine = routines.find(name) ?: return Reply("I couldn't find the $name routine.")
        val replies = ArrayList<Reply>()
        for (action in routine.actions) {
            for (c in CommandParser.parseAll(action)) {
                if (c is Command.Unlock || c is Command.RunRoutine) continue // never from a routine
                replies += try {
                    execute(c)
                } catch (e: Exception) {
                    Reply("“$action” didn't work.")
                }
            }
        }
        val title = name.replaceFirstChar { it.uppercase() }
        val spoken = replies.filter { !it.confirmation }.map { it.speech }.filter { it.isNotBlank() }
        val display = "$title: " + replies.map { it.display }.filter { it.isNotBlank() }.joinToString(" · ")
        val afters = replies.mapNotNull { it.after }
        val after: (suspend () -> Unit)? = if (afters.isEmpty()) null else ({ afters.forEach { it() } })
        return if (spoken.isEmpty()) ok("$title done.", display, after) else Reply(spoken.joinToString(" "), display, after)
    }

    // ── Calculator, conversions, countdowns, notes, spelling (all offline) ──

    private fun calculate(expr: String): Reply {
        val v = Calculator.evaluate(expr) ?: return Reply("I couldn't work that out.")
        val spoken = expr.replace(" point ", ".").replace(" percent", "%")
        return Reply("$spoken is ${Calculator.format(v)}.", "$spoken = ${Calculator.format(v)}")
    }

    private fun convert(cmd: Command.Convert): Reply {
        val from = Units.unit(cmd.from) ?: return Reply("I don't know that unit.")
        val to = Units.unit(cmd.to) ?: return Reply("I don't know that unit.")
        val v = Units.convert(cmd.value, from, to) ?: return Reply("I can't convert ${from.name} to ${to.name}.")
        val deg = if (from.kind == Units.Kind.TEMPERATURE) " degrees" else ""
        return Reply("${Calculator.format(cmd.value)}$deg ${from.name} is ${Calculator.format(v)}$deg ${to.name}.")
    }

    private fun daysUntil(cmd: Command.DaysUntil): Reply {
        val today = LocalDate.now()
        var target = runCatching { LocalDate.of(today.year, cmd.month, cmd.day) }.getOrNull()
            ?: return Reply("That date doesn't exist.")
        if (target.isBefore(today)) target = target.plusYears(1)
        val days = ChronoUnit.DAYS.between(today, target)
        val name = cmd.label.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        val date = target.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))
        return Reply(
            when (days) {
                0L -> "$name is today!"
                1L -> "$name is tomorrow, $date."
                else -> "$name is in $days days, on $date."
            },
        )
    }

    private fun addNote(text: String): Reply {
        if (text.isBlank()) return Reply("What should the note say?", followUp = "take a note ")
        notes.add(text)
        return ok("Noted: $text.")
    }

    private fun readNotes(): Reply {
        val all = notes.all()
        if (all.isEmpty()) return Reply("You don't have any notes yet.")
        val recent = all.takeLast(5).reversed()
        val spoken = recent.joinToString(". ") { it.text }
        val more = if (all.size > 5) " That's your latest 5 of ${all.size}." else ""
        return Reply(
            "You have ${all.size} note${if (all.size > 1) "s" else ""}. $spoken.$more",
            recent.joinToString("\n") { "• ${it.text}" },
        )
    }

    private fun spell(word: String): Reply {
        val letters = word.filter { it.isLetter() }
        if (letters.isEmpty()) return Reply("Which word should I spell?", followUp = "spell ")
        return Reply(
            "${word.replaceFirstChar { it.uppercase() }} is spelled ${letters.uppercase().toList().joinToString(", ")}.",
            "$word: ${letters.uppercase().toList().joinToString(" ")}",
        )
    }

    // ── Web search ──────────────────────────────────────────────────────────

    /** Chrome or Brave if chosen (by voice or in Settings) and installed, otherwise the default browser. */
    private fun browserPackage(spoken: String?): String? = when (spoken ?: prefs.browser) {
        NovaPrefs.BROWSER_CHROME -> NovaPrefs.CHROME_PACKAGE.takeIf { apps.isInstalled(it) }
        NovaPrefs.BROWSER_BRAVE -> NovaPrefs.BRAVE_PACKAGE.takeIf { apps.isInstalled(it) }
        else -> null
    }

    /** Opens the search in a browser: Nova itself has no internet access, the browser does the browsing. */
    private fun search(cmd: Command.Search): Reply {
        val q = cmd.query.trim()
        if (q.isEmpty()) return Reply("What should I search for?", followUp = "search ")
        val offline = !DeviceProfile.isOnline(context)
        if (cmd.site == SearchSite.YOUTUBE) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}"))
            if (apps.isInstalled("com.google.android.youtube")) intent.setPackage("com.google.android.youtube")
            if (!launch(intent)) return Reply("I couldn't open YouTube.")
            return if (offline) Reply("Opening YouTube, but you're offline right now.") else ok("Searching YouTube for $q.")
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val pkg = browserPackage(cmd.browser)
        val opened = launch(if (pkg != null) Intent(intent).setPackage(pkg) else intent) || launch(intent)
        if (!opened) return Reply("I couldn't find a browser.")
        val where = when (pkg) {
            NovaPrefs.CHROME_PACKAGE -> " in Chrome"
            NovaPrefs.BRAVE_PACKAGE -> " in Brave"
            else -> ""
        }
        return if (offline) Reply("I opened the search$where, but you're offline right now.") else ok("Searching for $q$where.")
    }

    // ── Song recognition ────────────────────────────────────────────────────

    /**
     * Opens Google's song search (the same as the "Song search" Quick Settings tile), or
     * Shazam if the Google app is missing. Nova stays silent so its voice doesn't confuse
     * the recognition, and it lets go of the microphone automatically while the song
     * search is listening.
     */
    private fun identifySong(): Reply {
        if (!DeviceProfile.isOnline(context)) {
            return Reply("Finding a song needs the internet, and you're offline right now.")
        }
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val google = Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH")
            .setPackage("com.google.android.googlequicksearchbox")
        val canGoogle = context.packageManager.resolveActivity(google, 0) != null
        val shazam = context.packageManager.getLaunchIntentForPackage("com.shazam.android")
        val intent = when {
            canGoogle -> google
            shazam != null -> shazam
            else -> return Reply("To find songs I need the Google app or Shazam. Install one from the Play Store.")
        }
        val note = if (locked) "Unlock your phone to see the song." else "Listening for the song…"
        return Reply(if (locked) note else "", note, after = { launch(intent) })
    }

    // ── Dictionary ──────────────────────────────────────────────────────────

    private suspend fun define(word: String): Reply {
        val entry = withContext(Dispatchers.IO) { runCatching { dictionary.lookup(word) }.getOrNull() }
        if (entry == null) {
            search(Command.Search("meaning of $word"))
            return Reply("I don't have $word in my offline dictionary, so I searched for it.")
        }
        return Reply(entry.spoken(), entry.display())
    }

    // ── Music on the phone ──────────────────────────────────────────────────

    private suspend fun playMusic(cmd: Command.PlayMusic): Reply {
        if (!music.hasPermission()) {
            return Reply("I need permission to see your music. Open Nova, go to Device check and allow Music and audio.")
        }
        val queue = withContext(Dispatchers.IO) { music.find(cmd.query, cmd.artist) }
        if (queue.isEmpty()) {
            val what = listOfNotNull(cmd.query, cmd.artist?.let { "by $it" }).joinToString(" ")
            return Reply(if (what.isEmpty()) "I couldn't find any songs on your phone." else "I couldn't find $what in your music.")
        }
        val first = queue.first()
        val speech = when {
            cmd.query == null && cmd.artist == null -> "Shuffling your music."
            cmd.query == null -> "Playing songs by ${first.artist}."
            first.artist.isNotBlank() -> "Playing ${first.title} by ${first.artist}."
            else -> "Playing ${first.title}."
        }
        // Start the music after Nova has finished talking
        return ok(speech, after = { player.play(queue) })
    }

    // ── Voice unlock ────────────────────────────────────────────────────────

    private suspend fun unlock(pin: String): Reply {
        val km = context.getSystemService(KeyguardManager::class.java)
        if (!prefs.voiceUnlock) return Reply("Voice unlock is off. You can turn it on in Nova's settings.")
        if (!km.isKeyguardLocked) return Reply("Your phone is already unlocked.")
        if (prefs.unlockBlocked) {
            return Reply("Voice unlock is paused after wrong attempts. Unlock by hand once to turn it back on.")
        }
        val svc = NovaAccessibilityService.instance
            ?: return Reply("I need Nova's accessibility service to unlock the phone.")
        if (km.isDeviceSecure && pin.length < 4) {
            return Reply("Say your PIN right after ${prefs.unlockPhrase}, like: ${prefs.unlockPhrase} 1 2 3 4.")
        }

        // Wake the screen and bring up the PIN pad, then type the digits
        launch(Intent(context, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        val result = svc.enterPin(pin) { km.isKeyguardLocked }
        if (result != NovaAccessibilityService.UnlockResult.UNLOCKED) UnlockActivity.finishIfOpen()

        return when (result) {
            NovaAccessibilityService.UnlockResult.UNLOCKED -> {
                prefs.unlockFailures = 0
                ok("Unlocked.")
            }
            NovaAccessibilityService.UnlockResult.NO_KEYPAD ->
                Reply("I couldn't find the PIN pad on your lock screen.")
            NovaAccessibilityService.UnlockResult.WRONG_PIN -> {
                val failures = prefs.unlockFailures + 1
                prefs.unlockFailures = failures
                if (failures >= NovaPrefs.MAX_UNLOCK_FAILURES) {
                    prefs.unlockBlocked = true
                    Reply("That PIN didn't work. Voice unlock is paused until you unlock the phone by hand.")
                } else {
                    Reply("That PIN didn't work.")
                }
            }
        }
    }

    // ── System navigation ───────────────────────────────────────────────────

    private fun navigate(a: NavAction): Reply {
        val svc = NovaAccessibilityService.instance
            ?: return Reply("I need Nova's accessibility service for that. Turn it on in Device check.")
        val action = when (a) {
            NavAction.HOME -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            NavAction.BACK -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            NavAction.RECENTS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            NavAction.NOTIFICATIONS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            NavAction.LOCK -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            NavAction.SCREENSHOT -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
        }
        return when (a) {
            // Lock after speaking; screenshot right away and silently so Nova isn't in it
            NavAction.LOCK -> ok("Locking.", after = { svc.global(action) })
            NavAction.SCREENSHOT -> {
                svc.global(action); Reply("", "Screenshot taken")
            }
            else -> {
                svc.global(action); Reply("", "Done")
            }
        }
    }
}
