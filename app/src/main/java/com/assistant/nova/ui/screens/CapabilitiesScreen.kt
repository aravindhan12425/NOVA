package com.assistant.nova.ui.screens

import android.Manifest
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.nova.core.Capabilities
import com.assistant.nova.core.Capability
import com.assistant.nova.core.NovaPrefs
import com.assistant.nova.core.NovaState
import com.assistant.nova.core.Requirement
import com.assistant.nova.core.device.DeviceProfile
import com.assistant.nova.services.NovaListenerService
import com.assistant.nova.ui.theme.NeonBlue
import com.assistant.nova.ui.theme.Ok
import com.assistant.nova.ui.theme.SurfaceColor
import com.assistant.nova.ui.theme.SurfaceHigh
import com.assistant.nova.ui.theme.TextSecondary
import com.assistant.nova.ui.theme.Warn

/** "What Nova can do": every feature with examples, whether it's ready on this phone, and safe "Try" buttons. */
@Composable
fun CapabilitiesScreen(onBack: () -> Unit, onDeviceCheck: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { NovaPrefs(context) }
    val groups = remember { Capabilities.all(prefs.unlockPhrase) }
    var met by remember { mutableStateOf(Requirement.entries.associateWith { DeviceProfile.isMet(context, it) }) }
    var query by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf<String?>(null) }
    val status by NovaState.status.collectAsStateWithLifecycle()
    val history by NovaState.history.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        met = Requirement.entries.associateWith { DeviceProfile.isMet(context, it) }
        onPauseOrDispose {}
    }

    fun ready(c: Capability) = c.requires.all { met[it] == true }
    val q = query.trim().lowercase()
    val filtered = groups.map { g ->
        g.copy(
            items = g.items.filter { c ->
                q.isEmpty() || g.title.lowercase().contains(q) || c.title.lowercase().contains(q) ||
                    c.description.lowercase().contains(q) || c.examples.any { it.text.lowercase().contains(q) }
            },
        )
    }.filter { it.items.isNotEmpty() }
    val all = groups.flatMap { it.items }

    fun tryIt(text: String) {
        if (!DeviceProfile.granted(context, Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(context, "Allow the microphone first (Device check)", Toast.LENGTH_LONG).show()
            return
        }
        if (NovaListenerService.runText(context, text)) tried = text
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NeonBlue)
            }
            Text("What Nova can do", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().background(SurfaceColor, RoundedCornerShape(14.dp)).padding(16.dp)) {
                    Text(
                        "Say “Hey Jarvis”, wait for the beep, then any sentence below. Words don't have to match exactly.",
                        color = Color.White, fontSize = 15.sp,
                    )
                    Text(
                        "Tap ▶ Try to run a safe example without speaking.",
                        color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "${all.count { ready(it) }} of ${all.size} features ready on your phone",
                        color = NeonBlue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search features, e.g. music, wifi, unlock") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (filtered.isEmpty()) {
                item { Text("Nothing matches “$query”.", color = TextSecondary, modifier = Modifier.padding(8.dp)) }
            }
            for (group in filtered) {
                item(key = "g_${group.title}") {
                    Text(
                        "${group.emoji}  ${group.title}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                }
                items(group.items, key = { "c_${group.title}_${it.title}" }) { cap ->
                    CapabilityCard(
                        cap = cap,
                        ready = ready(cap),
                        missing = cap.requires.filter { met[it] != true },
                        onTry = ::tryIt,
                        onSetUp = onDeviceCheck,
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        // Result of the last "Try"
        tried?.let { t ->
            val reply = when (status.status) {
                NovaState.Status.THINKING -> "Working on it…"
                NovaState.Status.SPEAKING -> status.detail
                else -> history.lastOrNull { it.heard == t }?.reply ?: "…"
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(SurfaceHigh)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("You: “$t”", color = TextSecondary, fontSize = 13.sp)
                Text("Nova: $reply", color = NeonBlue, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityCard(
    cap: Capability,
    ready: Boolean,
    missing: List<Requirement>,
    onTry: (String) -> Unit,
    onSetUp: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(SurfaceColor, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text(cap.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Chip(if (ready) "Ready" else "Needs setup", if (ready) Ok else Warn)
            Chip(if (cap.offline) "Works offline" else "Needs internet", if (cap.offline) NeonBlue else TextSecondary)
            if (cap.worksLocked) Chip("Works when locked", NeonBlue)
        }
        Text(cap.description, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))

        Column(Modifier.padding(top = 8.dp)) {
            for (ex in cap.examples) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("“${ex.text}”", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (ex.tryable && ready) {
                        TextButton(onClick = { onTry(ex.text) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Try", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (!ready) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Needs: " + missing.joinToString(", ") { it.label },
                    color = Warn, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onSetUp) { Text("Set up", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text,
        color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
