package dev.brentdevs.yardhal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.ui.theme.nickColor

@Composable
public fun NickAvatar(
    nick: String,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
) {
    val color = nickColor(nick)
    val initial = nick.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

@Composable
public fun StatusDot(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    val color = when (status) {
        ConnectionStatus.REGISTERED -> Color(0xFF27AE60)
        ConnectionStatus.CONNECTING -> Color(0xFFF1C40F)
        ConnectionStatus.DISCONNECTED -> Color(0xFF6E7681)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
public fun HighlightedSurface(
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val background =
        if (highlighted) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f) else Color.Transparent
    val border =
        if (highlighted) {
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f))
        } else {
            null
        }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = background,
        border = border,
    ) {
        content()
    }
}
