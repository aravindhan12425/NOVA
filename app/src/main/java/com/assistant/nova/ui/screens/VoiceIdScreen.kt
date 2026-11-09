package com.assistant.nova.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.nova.core.NovaState
import com.assistant.nova.core.speech.Mic
import com.assistant.nova.core.voiceid.VoiceId
import com.assistant.nova.ui.theme.NeonBlue
import com.assistant.nova.ui.theme.Ok
import com.assistant.nova.ui.theme.SurfaceColor
import com.assistant.nova.ui.theme.TextSecondary
import com.assistant.nova.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private val SENTENCES = listOf(
    "Hey Jarvis, call my mother and tell her I will be late.",
    "The weather is pleasant today, so let's go for a walk.",
    "Please turn on the Wi-Fi and play my favourite songs.",
    "I usually wake up at six thirty and drink a cup of tea.",
    "Set an alarm for tomorrow morning and remind me about the meeting.",
    "Chennai, Bangalore and Mumbai are big cities in India.",
    "Open YouTube and search for the latest cricket highlights.",
    "My voice is my password, so only I can control this phone.",
)

/**
 * Records one sentence: starts when you start talking, stops after ~1.2 s of quiet
 * (or 8 s). [level] gets the live loudness (0..1) for the meter.
 */
private suspend fun recordUtterance(mic: Mic, level: (Float) -> Unit): ShortArray = withContext(Dispatchers.Default) {
    val buf = ShortArray(1600) // 100 ms
    val out = ArrayList<Short>()
    val preroll = ArrayDeque<ShortArray>()
    var started = false
    var quietMs = 0
    var totalMs = 0
    while (isActive && totalMs < 8_000) {
        val n = mic.read(buf)
        if (n <= 0) break
        totalMs += 100
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        val rms = sqrt(sum / n).toFloat()
        level((rms / 3000f).coerceIn(0f, 1f))
        val chunk = buf.copyOf(n)
        if (!started) {
            preroll.addLast(chunk); if (preroll.size > 3) preroll.removeFirst()
            if (rms > 350) {
                started = true
                preroll.forEach { c -> c.forEach { out += it } }
            } else if (totalMs > 5_000) break // nobody spoke
        } else {
            chunk.forEach { out += it }
            quietMs = if (rms < 250) quietMs + 100 else 0
            if (quietMs >= 1_200) break
        }
    }
    level(0f)
    out.toShortArray()
}

@Composable
fun VoiceIdScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val voiceId = remember { VoiceId(context) }
    val scope = rememberCoroutineScope()
    var enrolled by remember { mutableStateOf(voiceId.isEnrolled) }
    var step by remember { mutableIntStateOf(-1) } // -1 = not recording sentences
    val prints = remember { mutableListOf<FloatArray>() }
    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }

    // Nova's listening steps aside while this screen uses the microphone
    DisposableEffect(Unit) {
        onDispose {
            NovaState.enrolling.value = false
            voiceId.close()
        }
    }

    fun record(onAudio: suspend (ShortArray) -> Unit) {
        recording = true
        message = null
        scope.launch {
            NovaState.enrolling.value = true
            delay(400) // let the listening service release the mic
            val mic = Mic()
            val audio = if (mic.open()) try { recordUtterance(mic) { level = it } } finally { mic.close() } else ShortArray(0)
            recording = false
            onAudio(audio)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = NeonBlue) }
            Text("Voice ID", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text(
            "Nova learns what your voice sounds like so it can react to commands without “Hey Jarvis”, and only to you. " +
                "It keeps a voice print (256 numbers), never the recordings, and it never leaves your phone.",
            color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))

        if (step >= 0) {
            // ── Enrolment: read the sentences ───────────────────────────────
            LinearProgressIndicator(progress = { step / SENTENCES.size.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("Sentence ${step + 1} of ${SENTENCES.size}", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Box(
                Modifier.fillMaxWidth().padding(vertical = 16.dp).background(SurfaceColor, RoundedCornerShape(14.dp)).padding(20.dp),
            ) {
                Text("“${SENTENCES[step]}”", color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Text(
                if (recording) "Listening… read the sentence in your normal voice" else "Hold the phone as you normally do, tap Record and read it.",
                color = if (recording) NeonBlue else TextSecondary, fontSize = 14.sp,
            )
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Ok)
            message?.let { Text(it, color = Warn, fontSize = 14.sp) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !recording, onClick = {
                    record { audio ->
                        val p = withContext(Dispatchers.Default) { runCatching { voiceId.print(audio) }.getOrNull() }
                        if (p == null) {
                            message = "I didn't catch enough of that. Try again, a little louder."
                        } else {
                            prints += p
                            if (step + 1 < SENTENCES.size) {
                                step += 1
                            } else {
                                val t = withContext(Dispatchers.Default) { voiceId.enroll(prints) }
                                enrolled = true
                                step = -1
                                message = null
                                NovaState.enrolling.value = false
                                android.util.Log.i("NovaVoiceId", "Enrolled with ${prints.size} sentences, threshold %.2f".format(t))
                            }
                        }
                    }
                }) { Text(if (recording) "Recording…" else "Record") }
                OutlinedButton(enabled = !recording, onClick = { step = -1; prints.clear(); NovaState.enrolling.value = false }) { Text("Cancel") }
            }
        } else if (!enrolled) {
            // ── Not set up yet ──────────────────────────────────────────────
            Text("Takes about a minute: read ${SENTENCES.size} short sentences in a quiet place.", color = Color.White, fontSize = 15.sp)
            Button(onClick = { prints.clear(); step = 0 }, modifier = Modifier.padding(top = 12.dp)) { Text("Teach Nova my voice") }
        } else {
            // ── Set up: test, redo or delete ────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(Ok, CircleShape))
                Text("  Voice ID is set up", color = Ok, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Say anything for a few seconds to see how well Nova recognises you. Have someone else try too: they should fail.",
                color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp),
            )
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Ok)
            message?.let { Text(it, color = Color.White, fontSize = 15.sp) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !recording, onClick = {
                    record { audio ->
                        val s = withContext(Dispatchers.Default) { runCatching { voiceId.score(audio) }.getOrNull() }
                        NovaState.enrolling.value = false
                        message = when {
                            s == null -> "I didn't catch enough of that. Speak for 2–3 seconds."
                            s >= voiceId.threshold -> "✓ That's you (match %.0f%%, needs %.0f%%)".format(s * 100, voiceId.threshold * 100)
                            else -> "✗ Not recognised as you (match %.0f%%, needs %.0f%%)".format(s * 100, voiceId.threshold * 100)
                        }
                    }
                }) { Text(if (recording) "Listening…" else "Test my voice") }
                OutlinedButton(enabled = !recording, onClick = { prints.clear(); step = 0 }) { Text("Record again") }
            }
            TextButton(onClick = { voiceId.delete(); enrolled = false; message = null }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Delete my voice profile", color = Warn)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
