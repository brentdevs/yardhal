package dev.brentdevs.yardhal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

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

private val DARK_NICK_PALETTE = listOf(
    0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF7986CB, 0xFF64B5F6,
    0xFF4DD0E1, 0xFF4DB6AC, 0xFFAED581, 0xFFFFD54F, 0xFFFF8A65,
    0xFF90A4AE, 0xFFA1887F, 0xFF81C784, 0xFF9575CD, 0xFFF48FB1,
)

private val LIGHT_NICK_PALETTE = listOf(
    0xFFB71C1C, 0xFFAD1457, 0xFF6A1B9A, 0xFF283593, 0xFF0D47A1,
    0xFF006064, 0xFF004D40, 0xFF33691E, 0xFF7A4F00, 0xFF9A3412,
    0xFF37474F, 0xFF4E342E, 0xFF3E5C00, 0xFF4527A0, 0xFF880E4F,
)

@Composable
public fun nickColor(nick: String): Color {
    if (nick.isEmpty()) return Color.Gray
    val palette = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
        LIGHT_NICK_PALETTE
    } else {
        DARK_NICK_PALETTE
    }
    var hash = 0
    for (ch in nick) hash = hash * 31 + ch.code
    return Color(palette[(hash and Int.MAX_VALUE) % palette.size])
}

@Composable
public fun YardhalTheme(
    themeDefinition: dev.brentdevs.yardhal.core.data.ThemeDefinition? = null,
    content: @Composable () -> Unit,
) {
    val custom = themeDefinition?.let { def ->
        fun c(value: Long): Color = Color(value.toULong().toLong())
        val scheme = if (def.dark) darkColorScheme() else lightColorScheme()
        scheme.copy(
            background = c(def.colors.background),
            surface = c(def.colors.background),
            primary = c(def.colors.primary),
            secondary = c(def.colors.secondary),
            tertiary = c(def.colors.tertiary),
            surfaceVariant = c(def.colors.surfaceVariant),
        )
    }
    MaterialTheme(
        colorScheme = when {
            custom != null -> custom
            isSystemInDarkTheme() -> DarkColors
            else -> LightColors
        },
        content = content,
    )
}
