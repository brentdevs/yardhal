package dev.brentdevs.yardhal.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.TimestampFormat
import dev.brentdevs.yardhal.core.protocol.IrcFormatting
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

public data class MessageAccessibilityActions(
    public val onReply: (() -> Unit)? = null,
    public val onCopy: (() -> Unit)? = null,
    public val onChooseReaction: (() -> Unit)? = null,
    public val onDelete: (() -> Unit)? = null,
    public val canToggleReaction: Boolean = false,
)

internal enum class AccessibleMessageAction { REPLY, COPY, REACTION, DELETE }

internal fun eligibleMessageActions(message: ChatMessage, requested: Set<AccessibleMessageAction>): Set<AccessibleMessageAction> =
    requested.filterTo(linkedSetOf()) { action ->
        !message.redacted && when (action) {
            AccessibleMessageAction.REPLY -> message.kind in conversationalKinds
            AccessibleMessageAction.COPY -> plainMessageText(message.relayedBody ?: message.text).isNotBlank()
            AccessibleMessageAction.REACTION -> message.kind in conversationalKinds && message.msgid != null
            AccessibleMessageAction.DELETE -> message.sentByUs && message.msgid != null && !message.pendingEcho
        }
    }

private val conversationalKinds = setOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)
private val timestampFormatters = mapOf(
    TimestampFormat.TIME_24 to DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT),
    TimestampFormat.TIME_12 to DateTimeFormatter.ofPattern("h:mm a", Locale.US),
    TimestampFormat.TIME_SECONDS to DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT),
    TimestampFormat.ISO_DATE_TIME to DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX", Locale.ROOT),
)

public fun formatTime(epochMs: Long, format: TimestampFormat = TimestampFormat.TIME_24, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(timestampFormatters.getValue(format))

internal fun messageAccessibilitySummary(
    message: ChatMessage,
    timestamp: String,
    quotedText: String?,
    reactions: Map<String, Set<String>>,
    emojiNames: Map<String, String> = emptyMap(),
): String = buildList {
    add(message.relayedSender ?: message.sender.ifBlank { "Server" })
    add(timestamp)
    if (message.redacted) {
        add("Message deleted")
        return@buildList
    }
    add(when (message.kind) {
        MessageKind.PRIVMSG -> "Message"
        MessageKind.NOTICE -> "Notice"
        MessageKind.ACTION -> "Action"
        MessageKind.SYSTEM -> "System event"
        MessageKind.JOIN -> "Join event"
        MessageKind.PART -> "Part event"
        MessageKind.QUIT -> "Quit event"
        MessageKind.NICK_CHANGE -> "Nickname change"
        MessageKind.MODE -> "Mode change"
        MessageKind.TOPIC -> "Topic change"
        MessageKind.KICK -> "Kick event"
    })
    add(plainMessageText(message.relayedBody ?: message.text))
    message.relayedSender?.let { add("Relayed via ${message.relaySource ?: message.sender}") }
    if (message.relayedSender == null) message.senderAccount?.let { add("Authenticated account $it") }
    message.channelContext?.let { add("Channel context $it") }
    if (message.replyToMsgid != null || message.localReplyParentRowId != null || quotedText != null) {
        add("Reply to ${quotedText?.let(::plainMessageText) ?: "earlier message"}")
    }
    message.attachmentUrl?.let {
        add("Attachment: ${message.attachmentName?.takeIf(String::isNotBlank) ?: "linked file"}")
        message.attachmentMimeType?.let { type -> add(type) }
        message.attachmentSizeBytes?.let { bytes -> add("$bytes bytes") }
    }
    if (message.pendingEcho) add("Delivery unconfirmed")
    if (message.playback) add("History message")
    if (message.historyContext) add("Historical context")
    if (message.highlightsMe) add("Mentions you")
    if (message.reactionsTruncated) add("Partial reaction history, retained members only")
    for ((emoji, nicks) in reactions) {
        if (nicks.isNotEmpty()) add("${emojiAccessibilityLabel(emoji, emojiNames)}: ${if (message.reactionsTruncated) "at least " else ""}${nicks.size} ${if (nicks.size == 1) "reaction" else "reactions"}")
    }
}.filter(String::isNotBlank).joinToString(". ")

internal enum class AccessibleLinkKind { URL, CHANNEL, NICK }
internal data class AccessibleMessageLink(val kind: AccessibleLinkKind, val target: String) {
    val label: String get() = when (kind) {
        AccessibleLinkKind.URL -> "Open link $target"
        AccessibleLinkKind.CHANNEL -> "Open channel $target"
        AccessibleLinkKind.NICK -> "Open conversation with $target"
    }
}

internal fun messageAccessibilityLinks(message: ChatMessage): List<AccessibleMessageLink> {
    if (message.redacted) return emptyList()
    val body = buildFormattedMessage(
        text = message.relayedBody ?: message.text,
        linkColor = Color.Unspecified,
        mentionColor = Color.Unspecified,
        onOpenChannel = {},
        onOpenNick = {},
        onOpenUrl = {},
    )
    return buildList {
        for (range in body.getLinkAnnotations(0, body.length)) {
            when (val link = range.item) {
                is LinkAnnotation.Url -> add(AccessibleMessageLink(AccessibleLinkKind.URL, link.url))
                is LinkAnnotation.Clickable -> add(AccessibleMessageLink(
                    if (body.text[range.start] == '@') AccessibleLinkKind.NICK else AccessibleLinkKind.CHANNEL, link.tag,
                ))
            }
        }
        message.attachmentUrl?.let { add(AccessibleMessageLink(AccessibleLinkKind.URL, it)) }
        message.channelContext?.let { add(AccessibleMessageLink(AccessibleLinkKind.CHANNEL, it)) }
    }.distinct()
}

private fun plainMessageText(text: String): String = IrcFormatting.plainText(IrcFormatting.parse(text))
