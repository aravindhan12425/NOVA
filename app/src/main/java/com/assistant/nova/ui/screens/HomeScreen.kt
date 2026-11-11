package com.assistant.nova.ui.screens

import android.Manifest
import com.assistant.nova.core.speech.AndroidRecognizer
import androidx.compose.runtime.LaunchedEffect
import android.widget.Toast
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
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
import com.assistant.nova.services.PrivacyGuardService
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
    var privacyGuard by remember { mutableStateOf(prefs.privacyGuard && PrivacyGuardService.isRunning) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            prefs.privacyGuard = true
            privacyGuard = PrivacyGuardService.start(context)
        } else {
            Toast.makeText(context, "The privacy guard needs the camera.", Toast.LENGTH_LONG).show()
        }
    }
    var handsFree by remember { mutableStateOf(prefs.directMode) }
    var voiceEnrolled by remember { mutableStateOf(VoiceId(context).isEnrolled) }
    // Google's on-device speech pack: lets Nova re-check what the small offline engine heard
    var speechPackMissing by remember { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }
    LaunchedEffect(resumes) {
        speechPackMissing = runCatching { AndroidRecognizer(context).hasOnDevicePack() }.getOrNull() == false
    }

    LifecycleResumeEffect(Unit) {
        handsFree = prefs.directMode
        voiceEnrolled = VoiceId(context).isEnrolled
        missing = DeviceProfile.missingRequired(context)
        alwaysOn = prefs.alwaysOn
        callAssistant = prefs.callAssistant
        privacyGuard = prefs.privacyGuard
        usage = UsageTracker.today(context)
        resumes++
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

    // One scrolling list for the whole page, so nothing is cut off on shorter screens
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "top") {
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
        }

        if (missing > 0) {
            item(key = "setup") {
                Row(
                    Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Warn.copy(alpha = 0.12f))
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
        }

        item(key = "orb") {
            Column(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp).animateContentSize(),
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
        }

        // ── The three switches, together ──
        item(key = "always") {
            SwitchCard(
                title = "Always listening",
                subtitle = "Hears the wake word 24/7, even offline",
                checked = alwaysOn,
                onChange = { on ->
                    if (on) ensureStarted() else {
                        prefs.alwaysOn = false
                        alwaysOn = false
                        NovaListenerService.stop(context)
                    }
                },
            )
        }
        // Hands-free (no wake word): quick on/off; needs Voice ID
        item(key = "handsfree") {
            SwitchCard(
                title = "Talk without “Hey Jarvis”",
                subtitle = when {
                    !voiceEnrolled -> "Set up Voice ID in Settings first"
                    handsFree -> when (prefs.directWhen) {
                        NovaPrefs.DIRECT_ALWAYS -> "On: always, only your voice"
                        NovaPrefs.DIRECT_CHARGING -> "On: while charging, only your voice"
                        else -> "On: while the screen is on, only your voice"
                    }
                    else -> "Off: say “Hey Jarvis” first"
                },
                checked = handsFree && voiceEnrolled,
                enabled = voiceEnrolled,
                onChange = { handsFree = it; prefs.directMode = it },
            )
        }
        // Call assistant: off = completely normal calls; on = announce callers, answer/reject, in-call commands
        item(key = "calls") {
            SwitchCard(
                title = "Call assistant",
                subtitle = if (callAssistant) "Announces callers; during a call just say “end the call”" else "Off: calls ring normally",
                checked = callAssistant,
                onChange = { callAssistant = it; prefs.callAssistant = it },
            )
        }

        // Privacy guard: front camera watches for someone else reading the screen
        item(key = "privacy") {
            SwitchCard(
                title = "Privacy guard",
                subtitle = if (privacyGuard) {
                    "On: warns you and darkens the screen if someone else looks at it. Uses more battery."
                } else {
                    "Off: turn on in crowded places (front camera, nothing is recorded)"
                },
                checked = privacyGuard,
                onChange = { on ->
                    if (!on) {
                        prefs.privacyGuard = false
                        privacyGuard = false
                        PrivacyGuardService.stop(context)
                    } else if (DeviceProfile.granted(context, Manifest.permission.CAMERA)) {
                        prefs.privacyGuard = true
                        privacyGuard = PrivacyGuardService.start(context)
                    } else {
                        cameraLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
            )
        }

        // Now playing: songs play in Nova's own player, so its controls live here (and in the notification / lock screen)
        nowPlaying?.let { np ->
            item(key = "playing") {
                fun send(action: String) = runCatching {
                    context.startService(Intent(context, NovaListenerService::class.java).setAction(action))
                }
                Row(
                    Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(SurfaceColor)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Now playing in Nova", color = NeonBlue, fontSize = 12.sp)
                        Text(np.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(np.artist, np.folder.takeIf { it.isNotBlank() }?.let { "from $it" }, "song ${np.position} of ${np.count}")
                                .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                            color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
        }

        if (speechPackMissing) {
            item(key = "speechpack") {
                LinkCard(
                    "Hear me better without “Hey Jarvis”",
                    "Tap to download Google's English (India) speech pack for this phone (one time). Then Nova " +
                        "double-checks what it heard, and the words stay on the phone.",
                    onClick = {
                        val started = AndroidRecognizer(context).downloadOnDevicePack()
                        Toast.makeText(
                            context,
                            if (started) "Download started. It can take a few minutes." else "Couldn't start the download on this phone.",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                ) { Icon(Icons.Default.Warning, contentDescription = null, tint = Warn) }
            }
        }
        item(key = "capabilities") {
            LinkCard("What Nova can do", "Every feature, with examples you can try", onCapabilities) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = NeonBlue)
            }
        }
        item(key = "routines") {
            LinkCard("Routines", "“Good night”, “I'm driving”… one phrase, many actions", onRoutines) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = NeonBlue)
            }
        }
        // Battery report (an estimate: Android doesn't share exact per-app numbers with apps)
        item(key = "battery") {
            val pct = usage.estimatedPercent(capacity)
            LinkCard(
                "Battery today: " + if (pct < 0.1) "under 0.1%" else "about ${"%.1f".format(pct)}%",
                "Listened ${"%.1f".format(usage.micMs / 3_600_000.0)} h · ${usage.wakes} wake-ups · ${usage.commands} commands. " +
                    "Tap for Android's exact figure (Battery usage).",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }

        item(key = "recent") {
            Text(
                "Recent",
                color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (history.isEmpty()) {
            item(key = "try") {
                Text(
                    "Try: “Hey Jarvis, turn on the flashlight”, “call mom”, “set an alarm for 6 30 am”, “what can you do”.",
                    color = TextSecondary, fontSize = 14.sp,
                )
            }
        }
        items(history.reversed(), key = { "h${it.time}" }) { ex ->
            Column(
                Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceColor)
                    .padding(12.dp),
            ) {
                Text("“${ex.heard}”", color = Color.White, fontSize = 15.sp)
                Text(ex.reply, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private val CardShape = RoundedCornerShape(14.dp)

/** A card with a title, a line of explanation and an on/off switch. Tapping anywhere on it toggles. */
@Composable
private fun SwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(SurfaceColor)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = TextSecondary, fontSize = 13.sp)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** A tappable card that opens something. */
@Composable
private fun LinkCard(title: String, subtitle: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(SurfaceColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = TextSecondary, fontSize = 13.sp)
        }
        trailing?.invoke()
    }
}
