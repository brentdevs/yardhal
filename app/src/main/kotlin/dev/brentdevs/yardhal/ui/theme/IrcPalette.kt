package dev.brentdevs.yardhal.ui.theme

import androidx.compose.ui.graphics.Color
import dev.brentdevs.yardhal.core.protocol.IrcColor

public object IrcPalette {

    private val STANDARD = listOf(
        Color(0xFFE8E8E8),
        Color(0xFF2B2B2B),
        Color(0xFF2E7CF6),
        Color(0xFF27AE60),
        Color(0xFFE24E42),
        Color(0xFF8B4A2B),
        Color(0xFF9B59B6),
        Color(0xFFE67E22),
        Color(0xFFF1C40F),
        Color(0xFF62D97F),
        Color(0xFF2AA7A1),
        Color(0xFF67D5E5),
        Color(0xFF7FB3F5),
        Color(0xFFF194C4),
        Color(0xFF8E8E8E),
        Color(0xFFD3D3D3),
    )

    public fun resolve(color: IrcColor): Color = when (color) {
        is IrcColor.Standard -> STANDARD.getOrElse(color.index) { Color.Unspecified }
        is IrcColor.Rgb -> Color(
            red = color.red / 255f,
            green = color.green / 255f,
            blue = color.blue / 255f,
            alpha = 1f,
        )
        is IrcColor.Rgba -> Color(
            red = color.red / 255f,
            green = color.green / 255f,
            blue = color.blue / 255f,
            alpha = (color.alpha / 255f).coerceIn(0f, 1f),
        )
    }
}
