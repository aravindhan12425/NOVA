package com.assistant.nova.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.nova.core.NovaPrefs
import com.assistant.nova.core.command.TextNormalizer
import com.assistant.nova.core.speech.Speaker
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.DisposableEffect
import java.util.Locale
import com.assistant.nova.core.wake.WakeWords
import com.assistant.nova.ui.theme.NeonBlue
import com.assistant.nova.ui.theme.SurfaceColor
import com.assistant.nova.ui.theme.TextSecondary
import com.assistant.nova.ui.theme.Warn

/** Settings take effect immediately; the listening service reads them on its next cycle. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { NovaPrefs(context) }
    val bundled = remember { WakeWords.bundled(context) }

    var jarvis by remember { mutableStateOf(prefs.wakeJarvis) }
    var nova by remember { mutableStateOf(prefs.wakeNova) }
    // Slider: 0 = strict (fewer false wake-ups), 1 = very sensitive
    var sensitivity by remember { mutableFloatStateOf(((0.8f - prefs.threshold) / 0.5f).coerceIn(0f, 1f)) }
    var engine by remember { mutableStateOf(prefs.engine) }
    var speak by remember { mutableStateOf(prefs.speakReplies) }
    var screenOff by remember { mutableStateOf(prefs.pauseScreenOff) }
    var lowBattery by remember { mutableStateOf(prefs.pauseLowBattery) }
    var boot by remember { mutableStateOf(prefs.startOnBoot) }
    var browser by remember { mutableStateOf(prefs.browser) }
    var afterCommand by remember { mutableStateOf(prefs.afterCommand) }
    var callAssistant by remember { mutableStateOf(prefs.callAssistant) }
    var announceCalls by remember { mutableStateOf(prefs.announceCalls) }
    var listenDuringCalls by remember { mutableStateOf(prefs.listenDuringCalls) }
    var voiceName by remember { mutableStateOf(prefs.voiceName) }
    var voiceRate by remember { mutableFloatStateOf(prefs.voiceRate) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var previewTts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var t: TextToSpeech? = null
        t = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                t?.let { tts ->
                    tts.language = Locale("en", "IN")
                    voices = Speaker.offlineVoices(tts).take(12)
                    previewTts = tts
                }
            }
        }
        onDispose { t.stop(); t.shutdown() }
    }
    fun testVoice() {
        val tts = previewTts ?: return
        Speaker.applyPrefs(tts, prefs)
        tts.speak("Hi, I'm Nova. Wi-Fi is on, and it's 7:45 PM.", TextToSpeech.QUEUE_FLUSH, null, "nova_test")
    }
    var voiceUnlock by remember { mutableStateOf(prefs.voiceUnlock) }
    var phrase by remember { mutableStateOf(prefs.unlockPhrase) }
    var blocked by remember { mutableStateOf(prefs.unlockBlocked) }
    var confirmUnlock by remember { mutableStateOf(false) }
    val phraseError = unlockPhraseProblem(phrase)

    if (confirmUnlock) {
        AlertDialog(
            onDismissRequest = { confirmUnlock = false },
            title = { Text("Turn on voice unlock?") },
            text = {
                Text(
                    "Nova can't tell your voice from anyone else's. Anyone who hears you say your PIN, " +
                        "or knows it, could unlock your phone by voice.\n\n" +
                        "Nova never saves your PIN. While the phone is locked it only uses offline speech " +
                        "recognition, so the PIN never leaves the phone. After 2 wrong attempts voice unlock " +
                        "pauses until you unlock by hand.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnlock = false
                    voiceUnlock = true
                    prefs.voiceUnlock = true
                }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { confirmUnlock = false }) { Text("Cancel") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NeonBlue)
            }
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Header("Wake words")
        Card {
            CheckRow(
                "Hey Jarvis", "Works offline", jarvis, enabled = WakeWords.JARVIS in bundled,
            ) { jarvis = it; prefs.wakeJarvis = it }
            CheckRow(
                "Hey Nova",
                if (WakeWords.NOVA in bundled) "Works offline" else "Not trained yet. See “Train Hey Nova” in the README.",
                nova && WakeWords.NOVA in bundled, enabled = WakeWords.NOVA in bundled,
            ) { nova = it; prefs.wakeNova = it }
        }

        Header("Wake-word sensitivity")
        Card {
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                onValueChangeFinished = { prefs.threshold = 0.8f - sensitivity * 0.5f },
            )
            Row(Modifier.fillMaxWidth()) {
                Text("Fewer false wake-ups", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Hears you more easily", color = TextSecondary, fontSize = 12.sp)
            }
        }

        Header("Speech recognition")
        Card {
            Radio(
                "Automatic (recommended)",
                "Phone’s recognizer when it works (best with names), built-in offline recognizer otherwise",
                engine == NovaPrefs.ENGINE_AUTO,
            ) { engine = NovaPrefs.ENGINE_AUTO; prefs.engine = engine }
            Radio(
                "Offline only",
                "Always use the built-in recognizer. Nothing ever goes online",
                engine == NovaPrefs.ENGINE_OFFLINE,
            ) { engine = NovaPrefs.ENGINE_OFFLINE; prefs.engine = engine }
        }

        Header("Nova's voice")
        Card {
            Text(
                "Speed: ${"%.2f".format(voiceRate)}×${if (voiceRate in 0.9f..1.0f) " (clear)" else ""}",
                color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp, top = 8.dp),
            )
            Slider(
                value = voiceRate,
                onValueChange = { voiceRate = it },
                valueRange = 0.6f..1.4f,
                onValueChangeFinished = { prefs.voiceRate = voiceRate },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Radio("Automatic", "Best-quality English voice that works offline", voiceName.isEmpty()) {
                voiceName = ""; prefs.voiceName = ""
            }
            if (voices.isEmpty()) {
                Text(
                    "Loading voices… If none appear, install English (India) in Settings → System → Text-to-speech.",
                    color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(8.dp),
                )
            }
            for (v in voices) {
                Radio(Speaker.label(v), "Works offline", voiceName == v.name) {
                    voiceName = v.name; prefs.voiceName = v.name; testVoice()
                }
            }
            TextButton(onClick = ::testVoice, modifier = Modifier.padding(start = 4.dp)) { Text("▶ Test voice") }
        }

        Header("Browser for searches")
        Card {
            Radio("Default browser", "Whatever browser opens links on your phone", browser == NovaPrefs.BROWSER_DEFAULT) {
                browser = NovaPrefs.BROWSER_DEFAULT; prefs.browser = browser
            }
            Radio("Chrome", "You can also say “search … on Chrome”", browser == NovaPrefs.BROWSER_CHROME) {
                browser = NovaPrefs.BROWSER_CHROME; prefs.browser = browser
            }
            Radio("Brave", "You can also say “search … on Brave”", browser == NovaPrefs.BROWSER_BRAVE) {
                browser = NovaPrefs.BROWSER_BRAVE; prefs.browser = browser
            }
        }

        Header("Voice unlock")
        Card {
            SwitchRow(
                "Unlock with your voice",
                "Say your trigger phrase and PIN while the phone is locked. Needs the accessibility service.",
                voiceUnlock,
            ) { on ->
                if (on) confirmUnlock = true else {
                    voiceUnlock = false; prefs.voiceUnlock = false
                }
            }
            OutlinedTextField(
                value = phrase,
                onValueChange = {
                    phrase = it
                    if (unlockPhraseProblem(it) == null) prefs.unlockPhrase = it
                },
                label = { Text("Trigger phrase") },
                singleLine = true,
                isError = phraseError != null,
                supportingText = {
                    Text(
                        phraseError ?: "Say: “Hey Jarvis, ${phrase.trim().lowercase()} 1 2 3 4”. Use ordinary English " +
                            "words: the offline recognizer only knows real words.",
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            if (blocked) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Paused after 2 wrong attempts.",
                        color = Warn, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        prefs.unlockBlocked = false; prefs.unlockFailures = 0; blocked = false
                    }) { Text("Turn back on") }
                }
            }
        }

        Header("After a command")
        Card {
            Radio("Silent", "“Turn on Bluetooth” just happens, no voice", afterCommand == NovaPrefs.AFTER_SILENT) {
                afterCommand = NovaPrefs.AFTER_SILENT; prefs.afterCommand = afterCommand
            }
            Radio("Short tone", "A soft beep when it's done", afterCommand == NovaPrefs.AFTER_TONE) {
                afterCommand = NovaPrefs.AFTER_TONE; prefs.afterCommand = afterCommand
            }
            Radio("Speak", "Nova says “Bluetooth on”", afterCommand == NovaPrefs.AFTER_VOICE) {
                afterCommand = NovaPrefs.AFTER_VOICE; prefs.afterCommand = afterCommand
            }
            Text(
                "Answers (time, battery, meanings) and problems (“I couldn't find that contact”) are always spoken.",
                color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(8.dp),
            )
        }

        Header("Phone calls")
        Card {
            SwitchRow(
                "Call assistant",
                if (callAssistant) "On: Nova helps with calls. Say “turn off call assistant” to stop."
                else "Off: calls ring and work normally. Say “turn on call assistant” when you want help.",
                callAssistant,
            ) { callAssistant = it; prefs.callAssistant = it }
            if (callAssistant) {
                SwitchRow(
                    "Announce incoming calls",
                    "Turns the ringtone down, says who's calling; say “answer”, “reject” or “reject and send I'm busy”",
                    announceCalls,
                ) { announceCalls = it; prefs.announceCalls = it }
                SwitchRow(
                    "Listen during calls",
                    "“End the call”, “record the call”, “mute”, “speaker on”. Only call commands work while you're on a call",
                    listenDuringCalls,
                ) { listenDuringCalls = it; prefs.listenDuringCalls = it }
            }
        }

        Header("Behaviour")
        Card {
            SwitchRow("Speak at all", "Turn off to keep Nova completely silent, even for answers", speak) {
                speak = it; prefs.speakReplies = it
            }
            SwitchRow("Start after the phone restarts", "Resume listening after a reboot", boot) { boot = it; prefs.startOnBoot = it }
        }

        Header("Save battery")
        Card {
            SwitchRow("Pause when the screen is off", "Saves the most battery, but Nova won’t hear you while the screen is off", screenOff) {
                screenOff = it; prefs.pauseScreenOff = it
            }
            SwitchRow("Pause below 15% battery", "Resumes when charging", lowBattery) { lowBattery = it; prefs.pauseLowBattery = it }
        }
        Text(
            "Nova always pauses during phone calls and stops running its wake-word model while the room is quiet.",
            color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

private val COMMAND_WORDS = setOf(
    "call", "dial", "open", "launch", "turn", "switch", "set", "message", "text", "whatsapp", "send",
    "sms", "lock", "volume", "play", "pause", "go", "take",
)

/** Why a trigger phrase can't be used, or null if it's fine. */
private fun unlockPhraseProblem(phrase: String): String? {
    val words = TextNormalizer.basic(phrase).split(' ').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "Enter at least one word"
        words.any { w -> w.any(Char::isDigit) } -> "Use words only, no numbers"
        words.first() in COMMAND_WORDS -> "Don’t start with a command word like “${words.first()}”"
        else -> null
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(SurfaceColor, RoundedCornerShape(14.dp)).padding(8.dp)) { content() }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text(subtitle, color = TextSecondary, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CheckRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else TextSecondary, fontSize = 15.sp)
            Text(subtitle, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Radio(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text(subtitle, color = TextSecondary, fontSize = 13.sp)
        }
    }
}
