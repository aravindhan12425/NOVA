package com.assistant.nova.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.nova.core.calls.NumberLabels
import com.assistant.nova.core.calls.UnknownCaller
import com.assistant.nova.core.calls.UnknownCallers
import com.assistant.nova.ui.theme.Bad
import com.assistant.nova.ui.theme.NeonBlue
import com.assistant.nova.ui.theme.SurfaceColor
import com.assistant.nova.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Numbers from the call history that aren't saved contacts: one row per number, newest first,
 * searchable, with Call / WhatsApp / Save / Name it. Everything is read on the phone.
 */
@Composable
fun UnknownCallsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val labels = remember { NumberLabels(context) }
    var all by remember { mutableStateOf<List<UnknownCaller>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(UnknownCallers.Sort.RECENT) }
    var naming by remember { mutableStateOf<UnknownCaller?>(null) }
    var allowed by remember { mutableStateOf(UnknownCallers.canRead(context)) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = UnknownCallers.canRead(context)
        reload++
    }
    LaunchedEffect(reload, allowed) {
        all = if (allowed) withContext(Dispatchers.IO) { runCatching { UnknownCallers.load(context) }.getOrDefault(emptyList()) } else emptyList()
    }
    val shown = all?.let { UnknownCallers.search(UnknownCallers.sort(it, sort), query) }

    fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "top") {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NeonBlue)
                }
                Column {
                    Text("Unknown callers", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Numbers you talked to that aren't in your contacts", fontSize = 13.sp, color = TextSecondary)
                }
            }
        }
        if (!allowed) {
            item(key = "perm") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceColor).padding(16.dp)) {
                    Text("Nova needs to read your call history and contacts for this page.", color = Color.White)
                    TextButton(onClick = {
                        permissions.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS))
                    }) { Text("Allow", color = NeonBlue) }
                }
            }
            return@LazyColumn
        }
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                placeholder = { Text("Digits, a name you gave, or a place") },
            )
        }
        item(key = "sort") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    UnknownCallers.Sort.RECENT to "Recent",
                    UnknownCallers.Sort.MOST_CALLS to "Most calls",
                    UnknownCallers.Sort.LONGEST_TALK to "Longest talk",
                ).forEach { (s, label) ->
                    FilterChip(
                        selected = sort == s,
                        onClick = { sort = s },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonBlue.copy(alpha = 0.25f)),
                    )
                }
            }
        }
        when {
            shown == null -> item(key = "loading") { Text("Reading your calls…", color = TextSecondary) }
            shown.isEmpty() -> item(key = "none") {
                Text(if (query.isBlank()) "No unknown numbers in your call history." else "Nothing matches “$query”.", color = TextSecondary)
            }
            else -> items(shown, key = { it.key }) { u ->
                CallerRow(
                    u,
                    onCall = { open(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", u.number, null))) },
                    onWhatsApp = {
                        // wa.me needs the country code: assume India for a plain 10-digit number
                        val digits = u.number.filter { it.isDigit() }
                        val full = if (digits.length == 10) "91$digits" else digits.trimStart('0')
                        open(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$full")))
                    },
                    onSave = {
                        open(
                            Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                                .putExtra(ContactsContract.Intents.Insert.PHONE, u.number)
                                .apply { u.label?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) } },
                        )
                    },
                    onName = { naming = u },
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Phone number", u.number))
                        Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    naming?.let { u ->
        var text by remember(u.key) { mutableStateOf(u.label.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text("Name this number") },
            text = {
                Column {
                    Text(u.number, color = TextSecondary, fontSize = 13.sp)
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        placeholder = { Text("e.g. Phone repair shop") },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Only kept in Nova. Then search for it here, or say “call the ${text.ifBlank { "repair shop" }.lowercase()}”.",
                        color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { labels.set(u.number, text); naming = null; reload++ }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CallerRow(
    u: UnknownCaller,
    onCall: () -> Unit,
    onWhatsApp: () -> Unit,
    onSave: () -> Unit,
    onName: () -> Unit,
    onCopy: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceColor)
            .clickable(onClick = onCopy).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
    ) {
        Text(
            u.label ?: u.number,
            color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (u.label != null) Text(u.number, color = TextSecondary, fontSize = 13.sp)
        val parts = buildList {
            add("${u.calls} call${if (u.calls > 1) "s" else ""}")
            val kinds = listOfNotNull(
                u.incoming.takeIf { it > 0 }?.let { "$it in" },
                u.outgoing.takeIf { it > 0 }?.let { "$it out" },
                u.missed.takeIf { it > 0 }?.let { "$it missed" },
            )
            if (kinds.isNotEmpty()) add(kinds.joinToString(", "))
            if (u.talkSec > 0) add(talk(u.talkSec))
            u.location?.let { add(it) }
        }
        Text(parts.joinToString(" · "), color = if (u.missed == u.calls) Bad else TextSecondary, fontSize = 13.sp)
        Text(
            "Last: " + DateUtils.getRelativeDateTimeString(context, u.lastCall, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0),
            color = TextSecondary, fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Action(Icons.Default.Call, "Call", onCall)
            Action(Icons.Default.Send, "WhatsApp", onWhatsApp)
            Action(Icons.Default.Add, "Save", onSave)
            Action(Icons.Default.Edit, if (u.label == null) "Name it" else "Rename", onName)
        }
    }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(icon, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = NeonBlue, fontSize = 13.sp)
    }
}

private fun talk(sec: Long): String = when {
    sec < 60 -> "${sec}s talk"
    sec < 3600 -> "${sec / 60} min talk"
    else -> "${sec / 3600} h ${(sec % 3600) / 60} min talk"
}
