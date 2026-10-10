package dev.brentdevs.yardhal.ui.components

import android.view.View
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.RecoveryPhase
import dev.brentdevs.yardhal.ui.image.DefaultMediaSignals
import dev.brentdevs.yardhal.ui.image.ImageCacheCategory
import dev.brentdevs.yardhal.ui.image.LocalMediaEnvironment
import dev.brentdevs.yardhal.ui.image.LocalRemoteImageLoader
import dev.brentdevs.yardhal.ui.image.RemoteImageState
import dev.brentdevs.yardhal.ui.image.rememberRemoteImage
import dev.brentdevs.yardhal.ui.theme.nickColor

@Composable
public fun NickAvatar(
    nick: String,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    avatarUrl: String? = null,
    mediaVisible: Boolean = true,
) {
    val environment = LocalMediaEnvironment.current
    val preferences by (environment?.preferences?.preferences ?: DefaultMediaSignals.preferences).collectAsState()
    val view = LocalView.current
    val viewport = remember(view) { AvatarViewport(view) }
    var inViewport by remember { mutableStateOf(false) }
    val visibleModifier = modifier.onGloballyPositioned { coordinates ->
        inViewport = coordinates.isAttached && viewport.intersects(coordinates.boundsInWindow())
    }
    val image by rememberRemoteImage(
        LocalRemoteImageLoader.current, avatarUrl, size.roundToPxInt(),
        category = ImageCacheCategory.AVATAR, enabled = preferences.loadAvatars, visible = mediaVisible && inViewport,
    )
    val loaded = (image as? RemoteImageState.Success)?.bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = visibleModifier
                .size(size)
                .clip(CircleShape),
        )
        return
    }
    val color = nickColor(nick)
    val initial = nick.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    Box(
        modifier = visibleModifier
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
    phase: RecoveryPhase,
    iconUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    mediaVisible: Boolean = true,
) {
    val environment = LocalMediaEnvironment.current
    val preferences by (environment?.preferences?.preferences ?: DefaultMediaSignals.preferences).collectAsState()
    val view = LocalView.current
    val viewport = remember(view) { AvatarViewport(view) }
    var inViewport by remember { mutableStateOf(false) }
    val visibleModifier = modifier.onGloballyPositioned { coordinates ->
        inViewport = coordinates.isAttached && viewport.intersects(coordinates.boundsInWindow())
    }
    val image by rememberRemoteImage(
        LocalRemoteImageLoader.current, iconUrl, size.roundToPxInt(),
        category = ImageCacheCategory.AVATAR, enabled = preferences.loadAvatars, visible = mediaVisible && inViewport,
    )
    val loaded = (image as? RemoteImageState.Success)?.bitmap
    if (loaded == null) {
        RecoveryStatusDot(phase, visibleModifier)
        return
    }
    Box(modifier = visibleModifier.size(size)) {
        Image(
            bitmap = loaded,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.25f)),
        )
        RecoveryStatusDot(phase, Modifier.align(Alignment.BottomEnd), size = size * 0.4f)
    }
}

private class AvatarViewport(private val view: View) {
    private val visibleFrame = android.graphics.Rect()
    private val screenLocation = IntArray(2)
    private val windowLocation = IntArray(2)

    fun intersects(bounds: Rect): Boolean {
        view.getWindowVisibleDisplayFrame(visibleFrame)
        view.getLocationOnScreen(screenLocation)
        view.getLocationInWindow(windowLocation)
        val offsetX = screenLocation[0] - windowLocation[0]
        val offsetY = screenLocation[1] - windowLocation[1]
        return avatarIntersectsViewport(bounds, Rect(
            (visibleFrame.left - offsetX).toFloat(),
            (visibleFrame.top - offsetY).toFloat(),
            (visibleFrame.right - offsetX).toFloat(),
            (visibleFrame.bottom - offsetY).toFloat(),
        ))
    }
}

internal fun avatarIntersectsViewport(bounds: Rect, viewport: Rect): Boolean =
    bounds.width > 0 && bounds.height > 0 && viewport.width > 0 && viewport.height > 0 &&
        bounds.left < viewport.right && bounds.right > viewport.left &&
        bounds.top < viewport.bottom && bounds.bottom > viewport.top

@Composable
private fun RecoveryStatusDot(phase: RecoveryPhase, modifier: Modifier, size: Dp = 10.dp) {
    val color = when (phase) {
        RecoveryPhase.REGISTERED -> Color(0xFF27AE60)
        RecoveryPhase.CONNECTING, RecoveryPhase.IDENTIFYING -> Color(0xFFF1C40F)
        RecoveryPhase.AUTHENTICATION_REJECTED, RecoveryPhase.CERTIFICATE_REJECTED -> MaterialTheme.colorScheme.error
        RecoveryPhase.SERVER_UNREACHABLE -> Color(0xFFE67E22)
        RecoveryPhase.DISCONNECTED, RecoveryPhase.USER_DISCONNECTED, RecoveryPhase.OFFLINE -> Color(0xFF6E7681)
    }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
    )
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
