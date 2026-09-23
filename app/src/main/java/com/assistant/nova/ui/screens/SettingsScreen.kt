package com.assistant.nova.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.nova.ui.theme.NeonBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentWakeWord: String,
    onWakeWordSaved: (String) -> Unit,
    onBack: () -> Unit
) {
    var textValue by remember { mutableStateOf(currentWakeWord) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", fontSize = 28.sp, color = Color.White)
        Spacer(modifier = Modifier.height(32.dp))

        Text("Wake Word", fontSize = 18.sp, color = NeonBlue)
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = textValue,
            onValueChange = { textValue = it },
            label = { Text("Assistant Name") },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = NeonBlue,
                unfocusedIndicatorColor = Color.Gray,
                focusedLabelColor = NeonBlue,
                unfocusedLabelColor = Color.Gray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = { onWakeWordSaved(textValue) },
            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
        ) {
            Text("Save & Go Back", color = Color.Black)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextButton(onClick = onBack) {
            Text("Cancel", color = Color.Gray)
        }
    }
}
