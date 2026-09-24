package dev.brentdevs.yardhal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.brentdevs.yardhal.core.protocol.IrcFormatting
import dev.brentdevs.yardhal.ui.theme.IrcPalette

@Composable
public fun formattedMessage(text: String): AnnotatedString = buildAnnotatedString {
    for (span in IrcFormatting.parse(text)) {
        val style = span.style
        val decorations = buildList {
            if (style.underline) add(TextDecoration.Underline)
            if (style.strikethrough) add(TextDecoration.LineThrough)
        }
        pushStyle(
            SpanStyle(
                color = style.foreground?.let { IrcPalette.resolve(it) } ?: Color.Unspecified,
                background = style.background?.let { IrcPalette.resolve(it) } ?: Color.Unspecified,
                fontWeight = if (style.bold) FontWeight.Bold else null,
                fontStyle = if (style.italic) FontStyle.Italic else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            ),
        )
        append(span.text)
        pop()
    }
}
