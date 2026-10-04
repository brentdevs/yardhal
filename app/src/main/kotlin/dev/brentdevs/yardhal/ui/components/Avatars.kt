package dev.brentdevs.yardhal.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.ui.image.LocalRemoteImageLoader
import dev.brentdevs.yardhal.ui.image.rememberRemoteImage
import dev.brentdevs.yardhal.ui.theme.nickColor

@Composable
public fun NickAvatar(
    nick: String,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    avatarUrl: String? = null,
) {
    val image by rememberRemoteImage(LocalRemoteImageLoader.current, avatarUrl, size.roundToPxInt())
    val loaded = image
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
        return
    }
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
public fun NetworkBadge(
    status: ConnectionStatus,
    iconUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val image by rememberRemoteImage(LocalRemoteImageLoader.current, iconUrl, size.roundToPxInt())
    val loaded = image
    if (loaded == null) {
        StatusDot(status, modifier)
        return
    }
    Box(modifier = modifier.size(size)) {
        Image(
            bitmap = loaded,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.25f)),
        )
        StatusDot(status, Modifier.align(Alignment.BottomEnd), size = size * 0.4f)
    }
}

@Composable
private fun Dp.roundToPxInt(): Int = with(LocalDensity.current) { roundToPx() }

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
    val accent = MaterialTheme.colorScheme.primary
    val tint = accent.copy(alpha = 0.08f)
    val highlightModifier = if (highlighted) {
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint)
            .drawBehind {
                val barWidth = 3.5.dp.toPx()
                val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - barWidth else 0f
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
            }
    } else {
        Modifier
    }
    Box(modifier = modifier.then(highlightModifier)) {
        content()
    }
}
