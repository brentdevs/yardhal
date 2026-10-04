package dev.brentdevs.yardhal.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

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
    0xFF80CBC4, 0xFFFFCC80, 0xFFBCAAA4, 0xFFB0BEC5, 0xFFCE93D8,
    0xFF9FA8DA, 0xFF80DEEA, 0xFFA5D6A7, 0xFFE6EE9C, 0xFFFFF59D,
    0xFFFFAB91, 0xFFB39DDB, 0xFF90CAF9, 0xFF80CBC4, 0xFFEF9A9A,
)

private val LIGHT_NICK_PALETTE = listOf(
    0xFFB71C1C, 0xFFAD1457, 0xFF6A1B9A, 0xFF283593, 0xFF0D47A1,
    0xFF006064, 0xFF004D40, 0xFF33691E, 0xFF7A4F00, 0xFF9A3412,
    0xFF37474F, 0xFF4E342E, 0xFF3E5C00, 0xFF4527A0, 0xFF880E4F,
    0xFF1B5E20, 0xFFBF360C, 0xFF01579B, 0xFF4A148C, 0xFF880E4F,
    0xFF1A237E, 0xFF004D40, 0xFFE65100, 0xFF311B92, 0xFF263238,
    0xFF3E2723, 0xFF006064, 0xFF827717, 0xFFB71C1C, 0xFF4A148C,
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
    dynamicColor: Boolean = false,
    amoledDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val scheme = remember(themeDefinition, dynamicColor, amoledDark, isDark, context) {
        val custom = themeDefinition?.let { def ->
            fun c(value: Long): Color = Color(value.toULong().toLong())
            val base = if (def.dark) darkColorScheme() else lightColorScheme()
            base.copy(
                background = c(def.colors.background),
                surface = c(def.colors.background),
                primary = c(def.colors.primary),
                secondary = c(def.colors.secondary),
                tertiary = c(def.colors.tertiary),
                surfaceVariant = c(def.colors.surfaceVariant),
            )
        }

        val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        var s = when {
            custom != null -> custom
            dynamicColor && dynamicAvailable && isDark -> dynamicDarkColorScheme(context)
            dynamicColor && dynamicAvailable && !isDark -> dynamicLightColorScheme(context)
            isDark -> DarkColors
            else -> LightColors
        }

        if (isDark && amoledDark) {
            s = s.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainer = Color(0xFF0C0E11),
                surfaceContainerLow = Color(0xFF060709),
                surfaceContainerHigh = Color(0xFF14171C),
                surfaceContainerHighest = Color(0xFF1C2026),
            )
        }
        s
    }

    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
