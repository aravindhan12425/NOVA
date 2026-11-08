package com.assistant.nova.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NeonBlue = Color(0xFF00E5FF)
val NeonPurple = Color(0xFFB388FF)
val DarkBackground = Color(0xFF0B0D12)
val SurfaceColor = Color(0xFF161A22)
val SurfaceHigh = Color(0xFF1F2430)
val TextSecondary = Color(0xFF9AA3B2)
val Ok = Color(0xFF4ADE80)
val Warn = Color(0xFFFBBF24)
val Bad = Color(0xFFF87171)

private val DarkColorScheme = darkColorScheme(
    primary = NeonBlue,
    onPrimary = Color(0xFF00131A),
    secondary = NeonPurple,
    onSecondary = Color.Black,
    background = DarkBackground,
    onBackground = Color.White,
    surface = SurfaceColor,
    onSurface = Color.White,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    error = Bad,
)

@Composable
fun NovaAITheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, content = content)
}
