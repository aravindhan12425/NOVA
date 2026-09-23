package com.assistant.nova.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.provider.Settings
import android.content.ComponentName
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assistant.nova.ui.components.GlowingMic
import com.assistant.nova.ui.theme.NeonBlue
import com.assistant.nova.viewmodels.AssistantViewModel
import com.assistant.nova.services.NovaAccessibilityService

@Composable
fun HomeScreen(
    wakeWord: String,
    onSettingsClick: () -> Unit,
    assistantViewModel: AssistantViewModel = viewModel()
) {
    val context = LocalContext.current
    val voiceState by assistantViewModel.voiceState.collectAsState()
    val lastResponse by assistantViewModel.lastResponse.collectAsState()
    
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasPermission = isGranted
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = NeonBlue)
        }
        
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("$wakeWord AI", color = Color.White, fontSize = 32.sp)
            
            Spacer(modifier = Modifier.height(64.dp))
            
            Box(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (hasPermission) {
                            assistantViewModel.toggleListening()
                        } else {
                            launcher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                GlowingMic(isListening = voiceState.isListening)
            }
            
            Spacer(modifier = Modifier.height(64.dp))
            
            val displayText = when {
                lastResponse.isNotEmpty() -> lastResponse
                voiceState.spokenText.isNotEmpty() -> voiceState.spokenText
                voiceState.isListening -> "Listening..."
                else -> "Tap to speak"
            }

            val displayColor = if (lastResponse.isNotEmpty()) NeonBlue else Color.White

            Text(
                text = displayText,
                color = displayColor,
                fontSize = if (lastResponse.isNotEmpty()) 24.sp else 20.sp,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            if (voiceState.error != null) {
                Text(
                    text = voiceState.error ?: "",
                    color = Color.Red,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Force Open Accessibility Button
            Button(
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray.copy(alpha = 0.5f)),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text("Fix Automation (Accessibility)", color = NeonBlue, fontSize = 12.sp)
            }
        }
    }
}
