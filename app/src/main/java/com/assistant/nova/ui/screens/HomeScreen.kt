package com.assistant.nova.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.filled.PlayArrow
import com.assistant.nova.core.UsageTracker
import com.assistant.nova.core.voiceid.VoiceId
import com.assistant.nova.R
import androidx.compose.ui.res.painterResource
import com.assistant.nova.core.NovaPrefs
import com.assistant.nova.core.NovaState
import com.assistant.nova.core.NovaState.Status
import com.assistant.nova.core.device.DeviceProfile
import com.assistant.nova.services.NovaListenerService
import com.assistant.nova.ui.components.NovaOrb
import com.assistant.nova.ui.theme.NeonBlue
import com.assistant.nova.ui.theme.SurfaceColor
import com.assistant.nova.ui.theme.TextSecondary
import com.assistant.nova.ui.theme.Warn

@Composable
fun HomeScreen(onDeviceCheck: () -> Unit, onSettings: () -> Unit, onCapabilities: () -> Unit, onRoutines: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { NovaPrefs(context) }
    val status by NovaState.status.collectAsStateWithLifecycle()
    val partial by NovaState.partial.collectAsStateWithLifecycle()
    val history by NovaState.history.collectAsStateWithLifecycle()
    val nowPlaying by NovaState.nowPlaying.collectAsStateWithLifecycle()
    var alwaysOn by remember { mutableStateOf(prefs.alwaysOn) }
    var missing by remember { mutableIntStateOf(0) }

    var usage by remember { mutableStateOf(UsageTracker.today(context)) }
    val capacity = remember { UsageTracker.batteryCapacityMah(context) }

    var callAssistant by remember { mutableStateOf(prefs.callAssistant) }
    var handsFree by remember { mutableStateOf(prefs.directMode) }
    var voiceEnrolled by remember { mutableStateOf(VoiceId(context).isEnrolled) }

    LifecycleResumeEffect(Unit) {
        handsFree = prefs.directMode
        voiceEnrolled = VoiceId(context).isEnrolled
        missing = DeviceProfile.missingRequired(context)
        alwaysOn = prefs.alwaysOn
        callAssistant = prefs.callAssistant
        usage = UsageTracker.today(context)
        onPauseOrDispose {}
    }

    val permissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun startListening() {
        prefs.alwaysOn = true
        alwaysOn = true
        NovaListenerService.start(context)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startListening()
        missing = DeviceProfile.missingRequired(context)
    }
    fun ensureStarted() {
        if (DeviceProfile.granted(context, Manifest.permission.RECORD_AUDIO)) startListening()
        else launcher.launch(permissions.toTypedArray())
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Nova", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCapabilities) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "What Nova can do", tint = NeonBlue)
            }
            IconButton(onClick = onDeviceCheck) {
                BadgedBox(badge = { if (missing > 0) Badge { Text("$missing") } }) {
                    Icon(Icons.Default.Build, contentDescription = "Device check", tint = NeonBlue)
                }
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = NeonBlue)
            }
        }

        if (missing > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(Warn.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .clickable(onClick = onDeviceCheck)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Warn, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "$missing setup step${if (missing > 1) "s" else ""} needed for Nova to work 24/7. Tap to fix.",
                    color = Warn, fontSize = 14.sp,
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NovaOrb(
                status = status.status,
                onClick = {
                    if (!NovaListenerService.isRunning) ensureStarted()
                    else NovaListenerService.start(context, NovaListenerService.ACTION_TALK)
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (status.status) {
                    Status.OFF -> "Nova is off"
                    Status.STARTING -> "Starting…"
                    Status.LISTENING -> status.detail.ifBlank { "Listening for the wake word" }
                    Status.HEARING -> "I'm listening…"
                    Status.THINKING -> "Working on it…"
                    Status.SPEAKING -> status.detail
                    Status.PAUSED -> status.detail
                    Status.ERROR -> status.detail
                },
                color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center,
            )
            val sub = when {
                partial.isNotBlank() -> "“$partial”"
                status.status == Status.THINKING -> "“${status.detail}”"
                status.status == Status.OFF -> "Tap the circle or turn on Always listening"
                else -> "Tap the circle to talk without the wake word"
            }
            Text(sub, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .background(SurfaceColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Always listening", color = Color.White, fontSize = 16.sp)
                Text("Hears the wake word 24/7, even offline", color = TextSecondary, fontSize = 13.sp)
            }
            Switch(
                checked = alwaysOn,
                onCheckedChange = { on ->
                    if (on) ensureStarted() else {
                        prefs.alwaysOn = false
                        alwaysOn = false
                        NovaListenerService.stop(context)
                    }
                },
            )
        }

        // Now playing: songs play in Nova's own player, so its controls live here (and in the notification / lock screen)
        nowPlaying?.let { np ->
            fun send(action: String) = runCatching {
                context.startService(Intent(context, NovaListenerService::class.java).setAction(action))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(SurfaceColor, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Now playing in Nova", color = NeonBlue, fontSize = 12.sp)
                    Text(np.title, color = Color.White, fontSize = 16.sp, maxLines = 1)
                    Text(
                        listOf(np.artist, np.folder.takeIf { it.isNotBlank() }?.let { "from $it" }, "song ${np.position} of ${np.count}")
                            .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                        color = TextSecondary, fontSize = 12.sp, maxLines = 1,
                    )
                }
                IconButton(onClick = { send(NovaListenerService.ACTION_MEDIA_PREVIOUS) }) {
                    Icon(painterResource(R.drawable.ic_media_previous), "Previous song", tint = Color.White)
                }
                IconButton(onClick = { send(NovaListenerService.ACTION_MEDIA_PLAY_PAUSE) }) {
                    Icon(
                        painterResource(if (np.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                        if (np.playing) "Pause" else "Play", tint = NeonBlue,
                    )
                }
                IconButton(onClick = { send(NovaListenerService.ACTION_MEDIA_NEXT) }) {
                    Icon(painterResource(R.drawable.ic_media_next), "Next song", tint = Color.White)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(SurfaceColor, RoundedCornerShape(14.dp))
                .clickable(onClick = onCapabilities)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("What Nova can do", color = Color.White, fontSize = 16.sp)
                Text("Every feature, with examples you can try", color = TextSecondary, fontSize = 13.sp)
            }
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = NeonBlue)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(SurfaceColor, RoundedCornerShape(14.dp))
                .clickable(onClick = onRoutines)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Routines", color = Color.White, fontSize = 16.sp)
                Text("“Good night”, “I'm driving”… one phrase, many actions", color = TextSecondary, fontSize = 13.sp)
            }
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = NeonBlue)
        }

        // Battery report (an estimate: Android doesn't share exact per-app numbers with apps)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(SurfaceColor, RoundedCornerShape(14.dp))
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val pct = usage.estimatedPercent(capacity)
                Text(
                    "Battery today: " + if (pct < 0.1) "under 0.1%" else "about ${"%.1f".format(pct)}%",
                    color = Color.White, fontSize = 16.sp,
                )
                Text(
                    "Listened ${"%.1f".format(usage.micMs / 3_600_000.0)} h · ${usage.wakes} wake-ups · ${usage.commands} commands. " +
                        "Tap for Android's exact figure (Battery usage).",
                    color = TextSecondary, fontSize = 13.sp,
                )
            }
        }

        // Hands-free (no wake word): quick on/off; needs Voice ID
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(SurfaceColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Talk without “Hey Jarvis”", color = Color.White, fontSize = 16.sp)
                Text(
                    when {
                        !voiceEnrolled -> "Set up Voice ID in Settings first"
                        handsFree -> when (prefs.directWhen) {
                            NovaPrefs.DIRECT_ALWAYS -> "On: always, only your voice"
                            NovaPrefs.DIRECT_CHARGING -> "On: while charging, only your voice"
                            else -> "On: while the screen is on, only your voice"
                        }
                        else -> "Off: say “Hey Jarvis” first"
                    },
                    color = TextSecondary, fontSize = 13.sp,
                )
            }
            Switch(
                checked = handsFree && voiceEnrolled,
                enabled = voiceEnrolled,
                onCheckedChange = { handsFree = it; prefs.directMode = it },
            )
        }

        // Call assistant: off = completely normal calls; on = announce callers, answer/reject, in-call commands
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(SurfaceColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Call assistant", color = Color.White, fontSize = 16.sp)
                Text(
                    if (callAssistant) "Announces callers, answer / reject by voice" else "Off: calls ring normally",
                    color = TextSecondary, fontSize = 13.sp,
                )
            }
            Switch(checked = callAssistant, onCheckedChange = { callAssistant = it; prefs.callAssistant = it })
        }

        Text(
            "Recent",
            color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        )
        if (history.isEmpty()) {
            Text(
                "Try: “Hey Jarvis, turn on the flashlight”, “call mom”, “set an alarm for 6 30 am”, “what can you do”.",
                color = TextSecondary, fontSize = 14.sp,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            items(history.reversed(), key = { it.time }) { ex ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(SurfaceColor, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Text("“${ex.heard}”", color = Color.White, fontSize = 15.sp)
                    Text(ex.reply, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
