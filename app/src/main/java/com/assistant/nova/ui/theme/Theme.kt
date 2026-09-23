package com.assistant.nova.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NeonBlue = Color(0xFF00E5FF)
val NeonPurple = Color(0xFFD500F9)
val DarkBackground = Color(0xFF121212)
val SurfaceColor = Color(0xFF1E1E1E)

private val DarkColorScheme = darkColorScheme(
    primary = NeonBlue,
    secondary = NeonPurple,
    background = DarkBackground,
    surface = SurfaceColor,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun NovaAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
