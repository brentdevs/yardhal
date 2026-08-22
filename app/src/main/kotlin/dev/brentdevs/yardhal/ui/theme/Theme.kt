package dev.brentdevs.yardhal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF00639A),
    secondary = Color(0xFF526069),
    tertiary = Color(0xFF63577E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ACBFF),
    secondary = Color(0xFFB9C8D2),
    tertiary = Color(0xFFCDBEEA),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
)

private val NICK_PALETTE = listOf(
    0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF7986CB, 0xFF64B5F6,
    0xFF4DD0E1, 0xFF4DB6AC, 0xFFAED581, 0xFFFFD54F, 0xFFFF8A65,
    0xFF90A4AE, 0xFFA1887F, 0xFF81C784, 0xFF9575CD, 0xFFF48FB1,
)

public fun nickColor(nick: String): Color {
    if (nick.isEmpty()) return Color.Gray
    var hash = 0
    for (ch in nick) hash = hash * 31 + ch.code
    return Color(NICK_PALETTE[(hash and Int.MAX_VALUE) % NICK_PALETTE.size])
}

@Composable
public fun YardhalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
