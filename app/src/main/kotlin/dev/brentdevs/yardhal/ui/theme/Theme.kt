package dev.brentdevs.yardhal.ui.theme

import android.content.Context
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import dev.brentdevs.yardhal.core.data.ThemeDefinition
import dev.brentdevs.yardhal.core.data.ChatAppearancePreferences
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.ThemeFileParser
import dev.brentdevs.yardhal.core.data.ThemeLibraryState
import dev.brentdevs.yardhal.core.data.ThemeLibraryStore
import java.io.File

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
    themeDefinition: ThemeDefinition? = null,
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
                onPrimary = contrastingColor(c(def.colors.primary)),
                primary = c(def.colors.primary),
                onSecondary = contrastingColor(c(def.colors.secondary)),
                secondary = c(def.colors.secondary),
                onTertiary = contrastingColor(c(def.colors.tertiary)),
                tertiary = c(def.colors.tertiary),
                surfaceVariant = c(def.colors.surfaceVariant),
                onBackground = contrastingColor(c(def.colors.background)),
                onSurface = contrastingColor(c(def.colors.background)),
                onSurfaceVariant = contrastingColor(c(def.colors.surfaceVariant)),
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

        if ((themeDefinition?.dark ?: isDark) && amoledDark) {
            s = s.copy(
                background = Color.Black,
                surface = Color.Black,
                onBackground = Color.White,
                onSurface = Color.White,
                surfaceDim = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerLow = lerp(Color.Black, s.surfaceContainerLow, 0.4f),
                surfaceContainer = lerp(Color.Black, s.surfaceContainer, 0.55f),
                surfaceContainerHigh = lerp(Color.Black, s.surfaceContainerHigh, 0.7f),
                surfaceContainerHighest = lerp(Color.Black, s.surfaceContainerHighest, 0.85f),
            )
        }
        s
    }

    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}

private fun contrastingColor(color: Color): Color =
    if (color.luminance() > 0.179f) Color.Black else Color.White

public fun createThemeLibrary(context: Context, directory: File): ThemeLibraryStore {
    val bundled = context.assets.open("themes/night.toml").bufferedReader().use { reader ->
        requireNotNull(ThemeFileParser.parse(reader.readText())) { "Bundled theme is invalid" }
    }
    return ThemeLibraryStore(directory, listOf(bundled))
}

@Composable
public fun LibraryYardhalTheme(
    libraryState: ThemeLibraryState,
    appearance: ChatAppearancePreferences,
    conversation: ConversationRef? = null,
    content: @Composable () -> Unit,
) {
    val definition = libraryState.definition(isSystemInDarkTheme())
    YardhalTheme(
        themeDefinition = definition,
        dynamicColor = appearance.dynamicColor,
        amoledDark = appearance.amoledDark,
    ) {
        if (conversation == null) content() else ConversationAccent(libraryState, conversation, content)
    }
}

@Composable
public fun ConversationAccent(
    libraryState: ThemeLibraryState,
    conversation: ConversationRef,
    content: @Composable () -> Unit,
) {
    val accent = libraryState.accent(conversation)
    if (accent == null) {
        content()
    } else {
        val color = Color(accent)
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(primary = color, onPrimary = contrastingColor(color)),
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}
