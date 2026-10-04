package dev.brentdevs.yardhal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import dev.brentdevs.yardhal.core.protocol.IrcFormatting
import dev.brentdevs.yardhal.ui.theme.IrcPalette

import androidx.compose.material3.MaterialTheme

private val URL_REGEX = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
private val CHANNEL_REGEX = Regex("""(?<=^|[\s(])([#&][a-zA-Z0-9_\-+]+)""")
private val MENTION_REGEX = Regex("""(?<=^|[\s(])@([a-zA-Z0-9_\\\[\]\{\}\^`|-]+)""")

@Composable
public fun formattedMessage(
    text: String,
    onOpenChannel: ((String) -> Unit)? = null,
    onOpenNick: ((String) -> Unit)? = null,
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val mentionColor = MaterialTheme.colorScheme.tertiary
    val linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    val channelStyles = TextLinkStyles(style = SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold))
    val mentionStyles = TextLinkStyles(style = SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold))

    return buildAnnotatedString {
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
            appendInteractiveText(
                raw = span.text,
                linkStyles = linkStyles,
                channelStyles = channelStyles,
                mentionStyles = mentionStyles,
                onOpenChannel = onOpenChannel,
                onOpenNick = onOpenNick,
            )
            pop()
        }
    }
}

private fun AnnotatedString.Builder.appendInteractiveText(
    raw: String,
    linkStyles: TextLinkStyles,
    channelStyles: TextLinkStyles,
    mentionStyles: TextLinkStyles,
    onOpenChannel: ((String) -> Unit)?,
    onOpenNick: ((String) -> Unit)?,
) {
    var cursor = 0
    while (cursor < raw.length) {
        val remaining = raw.substring(cursor)
        val urlMatch = URL_REGEX.find(remaining)
        val channelMatch = CHANNEL_REGEX.find(remaining)
        val mentionMatch = MENTION_REGEX.find(remaining)

        val nextMatch = listOfNotNull(urlMatch, channelMatch, mentionMatch).minByOrNull { it.range.first }
        if (nextMatch == null) {
            append(remaining)
            break
        }

        if (nextMatch.range.first > 0) {
            append(remaining.substring(0, nextMatch.range.first))
        }

        when (nextMatch) {
            urlMatch -> {
                val (cleanUrl, trailing) = stripTrailingPunctuation(nextMatch.value)
                pushLink(LinkAnnotation.Url(cleanUrl, styles = linkStyles))
                append(cleanUrl)
                pop()
                if (trailing.isNotEmpty()) append(trailing)
            }
            channelMatch -> {
                val channel = nextMatch.value
                if (onOpenChannel != null) {
                    pushLink(LinkAnnotation.Clickable(tag = channel, styles = channelStyles, linkInteractionListener = { onOpenChannel(channel) }))
                    append(channel)
                    pop()
                } else {
                    append(channel)
                }
            }
            mentionMatch -> {
                val nick = nextMatch.groupValues[1]
                val fullMention = nextMatch.value
                if (onOpenNick != null) {
                    pushLink(LinkAnnotation.Clickable(tag = nick, styles = mentionStyles, linkInteractionListener = { onOpenNick(nick) }))
                    append(fullMention)
                    pop()
                } else {
                    append(fullMention)
                }
            }
        }
        cursor += nextMatch.range.first + nextMatch.value.length
    }
}

private fun stripTrailingPunctuation(url: String): Pair<String, String> {
    var clean = url
    var trailing = ""
    while (clean.isNotEmpty()) {
        val last = clean.last()
        if (last == '.' || last == ',' || last == ';' || last == '?' || last == '!') {
            trailing = last + trailing
            clean = clean.dropLast(1)
        } else if (last == ')' && clean.count { it == '(' } < clean.count { it == ')' }) {
            trailing = last + trailing
            clean = clean.dropLast(1)
        } else {
            break
        }
    }
    return clean to trailing
}
