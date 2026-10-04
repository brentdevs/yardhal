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
private val CHANNEL_REGEX = Regex("""(?<![^\s\u0007,:(\[<])([#&][^ \r\n\u0007,:]+)""")
private val MENTION_REGEX = Regex("""(?<![^\s(\[<])@([a-zA-Z_\\\[\]\{\}\^`|][a-zA-Z0-9_\\\[\]\{\}\^`|-]*)""")
private val HEX_COLOR_REGEX = Regex("""^#[0-9a-fA-F]{3,8}$""")
private val HTML_ENTITY_REGEX = Regex("""^&(?:amp|lt|gt|quot|apos|nbsp);?$""", RegexOption.IGNORE_CASE)

@Composable
public fun formattedMessage(
    text: String,
    onOpenChannel: ((String) -> Unit)? = null,
    onOpenNick: ((String) -> Unit)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    defaultColor: Color = Color.Unspecified,
    defaultFontStyle: FontStyle? = null,
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val mentionColor = MaterialTheme.colorScheme.tertiary

    return remember(text, linkColor, mentionColor, onOpenChannel, onOpenNick, onOpenUrl, defaultColor, defaultFontStyle) {
        buildFormattedMessage(
            text = text,
            linkColor = linkColor,
            mentionColor = mentionColor,
            onOpenChannel = onOpenChannel,
            onOpenNick = onOpenNick,
            onOpenUrl = onOpenUrl,
            defaultColor = defaultColor,
            defaultFontStyle = defaultFontStyle,
        )
    }
}

public fun buildFormattedMessage(
    text: String,
    linkColor: Color,
    mentionColor: Color,
    onOpenChannel: ((String) -> Unit)? = null,
    onOpenNick: ((String) -> Unit)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    defaultColor: Color = Color.Unspecified,
    defaultFontStyle: FontStyle? = null,
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

    if (defaultColor != Color.Unspecified || defaultFontStyle != null) {
        builder.addStyle(
            SpanStyle(
                color = defaultColor,
                fontStyle = defaultFontStyle,
            ),
            0,
            fullText.length,
        )
    }

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
                val linkAnnotation = if (onOpenUrl != null) {
                    LinkAnnotation.Url(
                        cleanUrl,
                        styles = linkStyles,
                        linkInteractionListener = { onOpenUrl(cleanUrl) },
                    )
                } else {
                    LinkAnnotation.Url(cleanUrl, styles = linkStyles)
                }
                builder.addLink(linkAnnotation, matchStart, end)
                builder.addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), matchStart, end)
                cursor = end
            }
            channelMatch -> {
                val (cleanChannel, _) = stripTrailingPunctuation(nextMatch.groupValues[1], stripAngleBrackets = true)
                val isHex = HEX_COLOR_REGEX.matches(cleanChannel)
                val isEntity = HTML_ENTITY_REGEX.matches(cleanChannel)
                val hasLetter = cleanChannel.any { it.isLetter() }
                if (!isHex && !isEntity && hasLetter && '\u0000' !in cleanChannel) {
                    val end = matchStart + cleanChannel.length
                    if (onOpenChannel != null) {
                        builder.addLink(
                            LinkAnnotation.Clickable(
                                tag = cleanChannel,
                                styles = channelStyles,
                                linkInteractionListener = { onOpenChannel(cleanChannel) },
                            ),
                            matchStart,
                            end,
                        )
                        builder.addStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold), matchStart, end)
                    }
                    cursor = end
                } else {
                    cursor = matchEnd
                }
            }
            mentionMatch -> {
                val (cleanNick, _) = stripTrailingBrackets(nextMatch.groupValues[1])
                if (cleanNick.isNotEmpty()) {
                    val end = matchStart + 1 + cleanNick.length
                    val isBroadcast = cleanNick.equals("here", ignoreCase = true) || cleanNick.equals("everyone", ignoreCase = true)
                    if (!isBroadcast && onOpenNick != null) {
                        builder.addLink(
                            LinkAnnotation.Clickable(
                                tag = cleanNick,
                                styles = mentionStyles,
                                linkInteractionListener = { onOpenNick(cleanNick) },
                            ),
                            matchStart,
                            end,
                        )
                        builder.addStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold), matchStart, end)
                    }
                    cursor = end
                } else {
                    cursor = matchEnd
                }
            }
        }
    }

    return builder.toAnnotatedString()
}

private fun stripTrailingBrackets(token: String): Pair<String, String> {
    var clean = token
    var trailing = ""
    while (clean.isNotEmpty()) {
        val last = clean.last()
        if (last == ')' && clean.count { it == '(' } < clean.count { it == ')' }) {
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

private fun stripTrailingPunctuation(url: String, stripAngleBrackets: Boolean = false): Pair<String, String> {
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
        } else if (stripAngleBrackets && last == '>' && clean.count { it == '<' } < clean.count { it == '>' }) {
            trailing = last + trailing
            clean = clean.dropLast(1)
        } else {
            break
        }
    }
    return clean to trailing
}
