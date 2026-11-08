package com.assistant.nova.ui.screens

import android.Manifest
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.assistant.nova.core.Routine
import com.assistant.nova.core.Routines
import com.assistant.nova.core.command.Command
import com.assistant.nova.core.command.CommandParser
import com.assistant.nova.core.device.DeviceProfile
import com.assistant.nova.services.NovaListenerService
import com.assistant.nova.ui.theme.NeonBlue
import com.assistant.nova.ui.theme.Ok
import com.assistant.nova.ui.theme.SurfaceColor
import com.assistant.nova.ui.theme.TextSecondary
import com.assistant.nova.ui.theme.Warn

private val SUGGESTIONS = listOf(
    "turn on do not disturb", "turn off do not disturb", "turn off wifi", "turn on wifi", "turn on bluetooth",
    "brightness 20 percent", "volume 5", "silent mode", "turn off mobile data", "turn on battery saver",
    "play my songs", "pause", "what's the time", "how much battery", "when is my next alarm", "read my notes",
)

/** True when Nova understands [action] and it's allowed inside a routine. */
private fun actionOk(action: String): Boolean =
    action.isNotBlank() && CommandParser.parseAll(action).none {
        it is Command.Unknown || it is Command.Unlock || it is Command.RunRoutine
    }

@Composable
fun RoutinesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Routines(context) }
    var routines by remember { mutableStateOf(repo.all()) }
    var editing by remember { mutableStateOf<Routine?>(null) }
    var editingOriginal by remember { mutableStateOf<String?>(null) }

    fun run(r: Routine) {
        if (!DeviceProfile.granted(context, Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(context, "Allow the microphone first (Device check)", Toast.LENGTH_LONG).show()
            return
        }
        NovaListenerService.runText(context, r.name)
        Toast.makeText(context, "Running “${r.name}”", Toast.LENGTH_SHORT).show()
    }

    editing?.let { r ->
        RoutineEditor(
            initial = r,
            existingNames = routines.map { it.name.lowercase() }.filter { it != editingOriginal?.lowercase() },
            onCancel = { editing = null },
            onSave = { saved ->
                repo.upsert(editingOriginal, saved)
                routines = repo.all(); editing = null
            },
            onDelete = editingOriginal?.let { name ->
                { repo.delete(name); routines = repo.all(); editing = null }
            },
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = NeonBlue) }
                Text("Routines", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Text(
                "Say “Hey Jarvis, good night” and Nova runs every action of that routine in order. " +
                    "Actions are written like normal commands.",
                color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        items(routines, key = { it.name }) { r ->
            Column(Modifier.fillMaxWidth().background(SurfaceColor, RoundedCornerShape(14.dp)).padding(14.dp)) {
                Text("“${r.name}”", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                r.actions.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(
                            if (actionOk(a)) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                            tint = if (actionOk(a)) Ok else Warn, modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(a, color = TextSecondary, fontSize = 14.sp)
                    }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { run(r) }) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Run")
                    }
                    TextButton(onClick = { editingOriginal = r.name; editing = r }) { Text("Edit") }
                }
            }
        }
        item {
            Button(
                onClick = { editingOriginal = null; editing = Routine("", listOf("")) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("New routine")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineEditor(
    initial: Routine,
    existingNames: List<String>,
    onCancel: () -> Unit,
    onSave: (Routine) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial.name) }
    val actions = remember { mutableStateListOf<String>().apply { addAll(initial.actions.ifEmpty { listOf("") }) } }
    val nameError = when {
        name.isBlank() -> "Give it a name you'll say, e.g. “good night”"
        name.trim().lowercase() in existingNames -> "You already have a routine with this name"
        else -> null
    }
    // Routines win over normal commands, so a clash just hides that command behind the routine
    val nameNote = if (nameError == null && CommandParser.parse(name) !is Command.Unknown) {
        "Note: this phrase is also a normal command; saying it will run the routine instead"
    } else {
        null
    }
    val valid = nameError == null && actions.any { it.isNotBlank() } && actions.filter { it.isNotBlank() }.all(::actionOk)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = NeonBlue) }
            Text(if (onDelete == null) "New routine" else "Edit routine", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("What you'll say") }, singleLine = true,
            isError = nameError != null && name.isNotEmpty(),
            supportingText = { Text(nameError ?: nameNote ?: "Say: “Hey Jarvis, ${name.trim().lowercase()}”") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Actions, in order", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        actions.forEachIndexed { i, a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = a, onValueChange = { actions[i] = it }, singleLine = true,
                    placeholder = { Text("e.g. turn off wifi") },
                    isError = a.isNotBlank() && !actionOk(a),
                    supportingText = {
                        if (a.isNotBlank()) Text(if (actionOk(a)) "✓ Nova understands this" else "⚠ Nova doesn't understand this")
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { if (actions.size > 1) actions.removeAt(i) else actions[0] = "" }) {
                    Icon(Icons.Default.Close, "Remove action", tint = TextSecondary)
                }
            }
        }
        TextButton(onClick = { actions.add("") }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add action") }

        Text("Tap to add:", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SUGGESTIONS.forEach { s ->
                Text(
                    s, color = NeonBlue, fontSize = 12.sp,
                    modifier = Modifier
                        .background(NeonBlue.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .clickable {
                            val blank = actions.indexOfFirst { it.isBlank() }
                            if (blank >= 0) actions[blank] = s else actions.add(s)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onSave(Routine(name.trim().lowercase(), actions.map { it.trim() }.filter { it.isNotEmpty() })) },
                enabled = valid,
            ) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = Warn) }
        }
        Spacer(Modifier.height(24.dp))
    }
}
