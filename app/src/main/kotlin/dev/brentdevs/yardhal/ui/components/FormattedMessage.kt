package dev.brentdevs.yardhal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.brentdevs.yardhal.core.protocol.IrcFormatting
import dev.brentdevs.yardhal.ui.theme.IrcPalette

private val URL_REGEX = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""", RegexOption.IGNORE_CASE)
private val CHANNEL_REGEX = Regex("""(?<![^\s(\[<])([#&][a-zA-Z][a-zA-Z0-9_\-+.~/]*)(?=[,\s)\]>.:;!?]|$)""")
private val MENTION_REGEX = Regex("""(?<![^\s(\[<])@([a-zA-Z0-9_\\\[\]\{\}\^`|-]+)""")

@Composable
public fun formattedMessage(
    text: String,
    onOpenChannel: ((String) -> Unit)? = null,
    onOpenNick: ((String) -> Unit)? = null,
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val mentionColor = MaterialTheme.colorScheme.tertiary

    return remember(text, linkColor, mentionColor, onOpenChannel, onOpenNick) {
        buildFormattedMessage(
            text = text,
            linkColor = linkColor,
            mentionColor = mentionColor,
            onOpenChannel = onOpenChannel,
            onOpenNick = onOpenNick,
        )
    }
}

public fun buildFormattedMessage(
    text: String,
    linkColor: Color,
    mentionColor: Color,
    onOpenChannel: ((String) -> Unit)? = null,
    onOpenNick: ((String) -> Unit)? = null,
): AnnotatedString {
    val spans = IrcFormatting.parse(text)
    if (spans.isEmpty()) return AnnotatedString("")

    val spanRanges = ArrayList<Pair<IntRange, dev.brentdevs.yardhal.core.protocol.TextStyle>>(spans.size)
    val sb = StringBuilder()
    for (span in spans) {
        val start = sb.length
        sb.append(span.text)
        val end = sb.length
        spanRanges.add(start until end to span.style)
    }
    val fullText = sb.toString()
    val builder = AnnotatedString.Builder(fullText)

    for ((range, style) in spanRanges) {
        if (range.isEmpty()) continue
        val decorations = buildList {
            if (style.underline) add(TextDecoration.Underline)
            if (style.strikethrough) add(TextDecoration.LineThrough)
        }
        builder.addStyle(
            SpanStyle(
                color = style.foreground?.let { IrcPalette.resolve(it) } ?: Color.Unspecified,
                background = style.background?.let { IrcPalette.resolve(it) } ?: Color.Unspecified,
                fontWeight = if (style.bold) FontWeight.Bold else null,
                fontStyle = if (style.italic) FontStyle.Italic else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            ),
            start = range.first,
            end = range.last + 1,
        )
    }

    val linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    val channelStyles = TextLinkStyles(style = SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold))
    val mentionStyles = TextLinkStyles(style = SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold))

    var cursor = 0
    while (cursor < fullText.length) {
        val urlMatch = URL_REGEX.find(fullText, cursor)
        val channelMatch = CHANNEL_REGEX.find(fullText, cursor)
        val mentionMatch = MENTION_REGEX.find(fullText, cursor)

        val nextMatch = listOfNotNull(urlMatch, channelMatch, mentionMatch).minByOrNull { it.range.first }
        if (nextMatch == null) break

        val matchStart = nextMatch.range.first
        val matchEnd = nextMatch.range.last + 1

        when (nextMatch) {
            urlMatch -> {
                val (cleanUrl, _) = stripTrailingPunctuation(nextMatch.value)
                val end = matchStart + cleanUrl.length
                builder.addLink(LinkAnnotation.Url(cleanUrl, styles = linkStyles), matchStart, end)
                builder.addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), matchStart, end)
                cursor = matchEnd
            }
            channelMatch -> {
                val channel = nextMatch.groupValues[1]
                val end = matchStart + channel.length
                val isHtmlEntity = channel.startsWith("&") && (channel == "&amp" || channel == "&lt" || channel == "&gt" || channel == "&quot")
                if (!isHtmlEntity && onOpenChannel != null) {
                    builder.addLink(
                        LinkAnnotation.Clickable(
                            tag = channel,
                            styles = channelStyles,
                            linkInteractionListener = { onOpenChannel(channel) },
                        ),
                        matchStart,
                        end,
                    )
                    builder.addStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold), matchStart, end)
                }
                cursor = matchEnd
            }
            mentionMatch -> {
                val nick = nextMatch.groupValues[1]
                val end = matchStart + nextMatch.value.length
                val isBroadcast = nick.equals("here", ignoreCase = true) || nick.equals("everyone", ignoreCase = true)
                if (!isBroadcast && onOpenNick != null) {
                    builder.addLink(
                        LinkAnnotation.Clickable(
                            tag = nick,
                            styles = mentionStyles,
                            linkInteractionListener = { onOpenNick(nick) },
                        ),
                        matchStart,
                        end,
                    )
                    builder.addStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold), matchStart, end)
                }
                cursor = matchEnd
            }
        }
    }

    return builder.toAnnotatedString()
}

private fun stripTrailingPunctuation(url: String): Pair<String, String> {
    var clean = url
    var trailing = ""
    while (clean.isNotEmpty()) {
        val last = clean.last()
        if (last == '.' || last == ',' || last == ';' || last == '?' || last == '!' || last == ':' || last == '"' || last == '\'') {
            trailing = last + trailing
            clean = clean.dropLast(1)
        } else if (last == ')' && clean.count { it == '(' } < clean.count { it == ')' }) {
            trailing = last + trailing
            clean = clean.dropLast(1)
        } else if (last == ']' && clean.count { it == '[' } < clean.count { it == ']' }) {
            trailing = last + trailing
            clean = clean.dropLast(1)
        } else if (last == '}' && clean.count { it == '{' } < clean.count { it == '}' }) {
            trailing = last + trailing
            clean = clean.dropLast(1)
        } else {
            break
        }
    }
    return clean to trailing
}
