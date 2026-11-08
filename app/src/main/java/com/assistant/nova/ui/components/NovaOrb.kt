package com.assistant.nova.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.assistant.nova.R
import com.assistant.nova.core.NovaState.Status
import com.assistant.nova.ui.theme.Bad
import com.assistant.nova.ui.theme.NeonBlue
import com.assistant.nova.ui.theme.NeonPurple
import com.assistant.nova.ui.theme.Ok

/** The big animated circle: its colour and pulse show what Nova is doing. Tap to talk. */
@Composable
fun NovaOrb(status: Status, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        when (status) {
            Status.LISTENING -> NeonBlue
            Status.HEARING -> Color.White
            Status.THINKING -> NeonPurple
            Status.SPEAKING -> Ok
            Status.ERROR -> Bad
            Status.STARTING -> NeonBlue.copy(alpha = 0.6f)
            Status.PAUSED, Status.OFF -> Color(0xFF4B5563)
        },
        label = "orbColor",
    )
    val active = status == Status.HEARING || status == Status.THINKING || status == Status.SPEAKING
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (active) 700 else 2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val idle = status == Status.OFF || status == Status.PAUSED

    Box(
        modifier = modifier
            .size(220.dp)
            .semantics { contentDescription = "Talk to Nova" }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(220.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val base = size.minDimension * 0.30f
            val glow = if (idle) 0f else pulse
            drawCircle(
                Brush.radialGradient(
                    listOf(color.copy(alpha = 0.35f * (0.5f + glow / 2)), Color.Transparent),
                    center = c, radius = base * (1.55f + 0.25f * glow),
                ),
                radius = base * (1.55f + 0.25f * glow), center = c,
            )
            drawCircle(color.copy(alpha = 0.25f), radius = base * (1.15f + 0.1f * glow), center = c, style = Stroke(2.dp.toPx()))
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.55f)), center = c, radius = base),
                radius = base, center = c,
            )
        }
        Icon(
            painterResource(R.drawable.ic_stat_nova),
            contentDescription = null,
            tint = Color(0xFF00131A),
            modifier = Modifier.size(56.dp),
        )
    }
}
