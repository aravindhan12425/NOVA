package com.assistant.nova.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.assistant.nova.core.device.DeviceProfile
import com.assistant.nova.core.device.DeviceProfile.State
import androidx.compose.runtime.rememberCoroutineScope
import com.assistant.nova.core.command.Feature
import com.assistant.nova.services.NovaAccessibilityService
import kotlinx.coroutines.launch
import com.assistant.nova.ui.theme.NeonBlue
import com.assistant.nova.ui.theme.Ok
import com.assistant.nova.ui.theme.SurfaceColor
import com.assistant.nova.ui.theme.TextSecondary
import com.assistant.nova.ui.theme.Warn

/** Shows this phone's details, what Nova still needs, and how each feature works on it. */
@Composable
fun DeviceCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val phone = remember { DeviceProfile.phone() }
    var sections by remember { mutableStateOf(DeviceProfile.sections(context)) }

    LifecycleResumeEffect(Unit) {
        sections = DeviceProfile.sections(context)
        onPauseOrDispose {}
    }

    val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // If Android didn't show the dialog (denied before), send the user to App info instead
        if (result.values.any { !it }) runCatching { context.startActivity(appSettings) }
        sections = DeviceProfile.sections(context)
    }

    fun runFix(fix: DeviceProfile.Fix) {
        when (fix) {
            is DeviceProfile.Fix.Permissions -> launcher.launch(fix.permissions.toTypedArray())
            is DeviceProfile.Fix.Open -> {
                val ok = runCatching { context.startActivity(Intent(fix.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (!ok) runCatching { context.startActivity(appSettings) }
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NeonBlue)
                }
                Text("Device check", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().background(SurfaceColor, RoundedCornerShape(14.dp)).padding(16.dp),
            ) {
                Text("${phone.brand} ${phone.model}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Android ${phone.android} (API ${phone.api})", color = TextSecondary, fontSize = 14.sp)
                Text(phone.build, color = TextSecondary, fontSize = 12.sp)
                Text(
                    if (phone.isOnePlusFamily) "Tested on this family: Quick Settings tapping and background steps are tuned for it."
                    else "Setup steps below are for ${phone.family.label}. Run the scan to check which switches Nova can use.",
                    color = NeonBlue, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item { ScanCard(phone) }
        for (section in sections) {
            item(key = section.title) {
                Text(
                    section.title, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            items(section.checks, key = { section.title + it.title }) { check ->
                CheckRow(check, onFix = { check.fix?.let(::runFix) })
            }
        }
        item { Spacer(Modifier.padding(bottom = 24.dp)) }
    }
}

/**
 * "Scan this phone": Nova opens Quick Settings, reads every tile's name without tapping,
 * and shows which features it can switch here. The report can be shared, so problems on
 * other brands can be fixed without the phone in hand.
 */
@Composable
private fun ScanCard(phone: DeviceProfile.Phone) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { context.getSharedPreferences("nova_scan", android.content.Context.MODE_PRIVATE) }
    var report by remember { mutableStateOf(store.getString("report", null)) }
    var lines by remember { mutableStateOf<List<Pair<String, String?>>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    fun scan() {
        val svc = NovaAccessibilityService.instance
        if (svc == null) {
            note = "Turn on Nova's accessibility service first (below), then scan."
            return
        }
        scanning = true
        scope.launch {
            val r = runCatching { svc.scanQuickSettings() }.getOrNull()
            scanning = false
            if (r == null) { note = "The scan didn't work. Try again."; return@launch }
            lines = Feature.entries.filter { it != Feature.FLASHLIGHT }.map { f ->
                f.spoken.replaceFirstChar { it.uppercase() } to r.found[f]
            }
            note = buildString {
                append("${r.found.size - (if (Feature.FLASHLIGHT in r.found) 1 else 0)} of ${lines.size} switches found on ${r.pages} page(s).")
                if (r.internetTile) append(" Wi-Fi and mobile data share one “Internet” tile: Nova switches them inside its panel.")
                if (r.missing.any { it != Feature.FLASHLIGHT }) append(" Missing tiles can usually be added: Quick Settings → ✏ Edit.")
            }
            report = buildString {
                appendLine("Nova scan: ${phone.brand} ${phone.model}, Android ${phone.android} (API ${phone.api})")
                appendLine("Build: ${phone.build}")
                appendLine("Found: " + r.found.entries.joinToString { "${it.key.spoken} = “${it.value}”" })
                appendLine("Missing: " + r.missing.joinToString { it.spoken }.ifEmpty { "none" })
                appendLine("Internet tile: ${if (r.internetTile) "yes" else "no"}; pages: ${r.pages}")
                append("All tile labels: " + r.labels.joinToString(" | "))
            }.also { store.edit().putString("report", it).apply() }
        }
    }

    Column(Modifier.fillMaxWidth().background(SurfaceColor, RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text("Scan this phone", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Nova opens Quick Settings, reads the name of every tile (without tapping anything) and closes it, " +
                "to check which switches it can use on this phone.",
            color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::scan, enabled = !scanning) { Text(if (scanning) "Scanning…" else "Scan") }
            if (report != null) {
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                    runCatching { context.startActivity(Intent.createChooser(send, "Share scan report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Share report") }
            }
        }
        note?.let { Text(it, color = NeonBlue, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        for ((name, label) in lines) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (label != null) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                    tint = if (label != null) Ok else Warn, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (label != null) "$name: “$label”" else "$name: tile not found",
                    color = if (label != null) Color.White else TextSecondary, fontSize = 13.sp,
                )
            }
        }
        if (lines.isEmpty() && report != null) {
            Text("Last scan saved. Tap Share report to send it.", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun CheckRow(check: DeviceProfile.Check, onFix: () -> Unit) {
    val (icon, tint) = when (check.state) {
        State.OK -> Icons.Default.CheckCircle to Ok
        State.NEEDED -> Icons.Default.Warning to Warn
        State.RECOMMENDED -> Icons.Default.Info to NeonBlue
        State.MANUAL -> Icons.Default.Info to Warn
        State.INFO -> Icons.Default.Info to TextSecondary
    }
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = check.state.name, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(check.title, color = Color.White, fontSize = 15.sp)
            Text(check.detail, color = TextSecondary, fontSize = 13.sp)
        }
        if (check.fix != null && check.state != State.OK) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onFix) { Text(check.fixLabel, fontSize = 13.sp) }
        }
    }
}
