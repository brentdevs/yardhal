package dev.brentdevs.yardhal.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.coordinator.UserProfile
import dev.brentdevs.yardhal.core.data.ChatAppearancePreferences
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.MessageFont
import dev.brentdevs.yardhal.core.data.TimestampPosition
import dev.brentdevs.yardhal.core.data.TimestampStyle
import dev.brentdevs.yardhal.ui.image.MediaDescriptor
import dev.brentdevs.yardhal.ui.image.MediaPolicy
import dev.brentdevs.yardhal.ui.image.MediaPreview
import dev.brentdevs.yardhal.ui.theme.nickColor
import java.time.ZoneId

internal enum class MessageDeliveryStatus { UNCONFIRMED }

internal fun messageDeliveryStatus(message: ChatMessage): MessageDeliveryStatus? =
    if (message.pendingEcho) MessageDeliveryStatus.UNCONFIRMED else null

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
public fun MessageRow(
    message: ChatMessage,
    groupedWithPrevious: Boolean,
    focused: Boolean = false,
    appearance: ChatAppearancePreferences = ChatAppearancePreferences(),
    reactions: Map<String, Set<String>>,
    quotedText: String?,
    onLongPress: () -> Unit,
    onToggleReaction: (String) -> Unit,
    onOpenAttachment: ((String) -> Unit)? = null,
    onOpenChannel: ((String) -> Unit)? = null,
    onOpenNick: ((String) -> Unit)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    profile: UserProfile? = null,
    mediaIdentity: String? = null,
    mediaVisible: Boolean = true,
    accessibilityActions: MessageAccessibilityActions = MessageAccessibilityActions(),
) {
    when (message.kind) {
        MessageKind.SYSTEM, MessageKind.JOIN, MessageKind.PART -> SystemLine(message, appearance, modifier, accessibilityActions.onCopy)
        else -> ChatLine(
            message = message,
            profile = profile,
            groupedWithPrevious = groupedWithPrevious,
            focused = focused,
            appearance = appearance,
            reactions = reactions,
            quotedText = quotedText,
            onLongPress = onLongPress,
            onToggleReaction = onToggleReaction,
            onOpenAttachment = onOpenAttachment,
            onOpenChannel = onOpenChannel,
            onOpenNick = onOpenNick,
            onOpenUrl = onOpenUrl,
            modifier = modifier,
            mediaIdentity = mediaIdentity,
            mediaVisible = mediaVisible,
            accessibilityActions = accessibilityActions,
        )
    }
}

@Composable
private fun SystemLine(
    message: ChatMessage,
    appearance: ChatAppearancePreferences,
    modifier: Modifier,
    onCopy: (() -> Unit)?,
) {
    val chatFontFamily = messageFontFamily(appearance.font)
    val timestampZone = ZoneId.systemDefault()
    val timestamp = remember(message.timestampMs, appearance.timestampFormat, timestampZone) { formatTime(message.timestampMs, appearance.timestampFormat, timestampZone) }
    val summary = remember(message, timestamp) { messageAccessibilitySummary(message, timestamp, null, emptyMap()) }
    val actions = remember(message, onCopy) {
        if (onCopy != null && AccessibleMessageAction.COPY in eligibleMessageActions(message, setOf(AccessibleMessageAction.COPY))) {
            listOf(CustomAccessibilityAction("Copy message") { onCopy(); true })
        } else {
            emptyList()
        }
    }
    val scaledFontSize = MaterialTheme.typography.bodySmall.fontSize * appearance.textScale
    val scaledLineHeight = MaterialTheme.typography.bodySmall.lineHeight * appearance.textScale
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (appearance.compact) 1.dp else 2.dp)
            .clearAndSetSemantics {
                contentDescription = summary
                customActions = actions
            },
    ) {
        if (appearance.timestampPosition == TimestampPosition.ABOVE) MessageTimestamp(timestamp, appearance)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (appearance.timestampPosition == TimestampPosition.INLINE) {
                MessageTimestamp(timestamp, appearance)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = "·",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(14.dp),
            )
            Text(
                text = if (message.redacted) "Message deleted" else message.text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = scaledFontSize,
                    lineHeight = scaledLineHeight,
                    fontFamily = chatFontFamily,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
            )
        }
        if (appearance.timestampPosition == TimestampPosition.BELOW) MessageTimestamp(timestamp, appearance)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatLine(
    message: ChatMessage,
    profile: UserProfile?,
    groupedWithPrevious: Boolean,
    focused: Boolean,
    appearance: ChatAppearancePreferences,
    reactions: Map<String, Set<String>>,
    quotedText: String?,
    onLongPress: () -> Unit,
    onToggleReaction: (String) -> Unit,
    onOpenAttachment: ((String) -> Unit)?,
    onOpenChannel: ((String) -> Unit)?,
    onOpenNick: ((String) -> Unit)?,
    onOpenUrl: ((String) -> Unit)?,
    modifier: Modifier,
    mediaIdentity: String?,
    mediaVisible: Boolean,
    accessibilityActions: MessageAccessibilityActions,
) {
    val sender = message.relayedSender ?: message.sender
    val body = if (message.redacted) "Message deleted" else message.relayedBody ?: message.text
    val trustedProfile = profile.takeIf { message.relayedSender == null }
    val linkedMedia = remember(body, message.attachmentUrl, message.redacted) {
        if (message.redacted) emptyList() else MediaPolicy.linkedMedia(body, message.attachmentUrl)
    }
    val emojiNames = rememberEmojiNames()
    val timestampZone = ZoneId.systemDefault()
    val timestamp = remember(message.timestampMs, appearance.timestampFormat, timestampZone) { formatTime(message.timestampMs, appearance.timestampFormat, timestampZone) }
    val summary = remember(message, timestamp, quotedText, reactions, emojiNames) {
        messageAccessibilitySummary(message, timestamp, quotedText, reactions, emojiNames)
    }
    val links = remember(message) { messageAccessibilityLinks(message) }
    val actions = remember(message, accessibilityActions, links, onOpenUrl, onOpenChannel, onOpenNick, onOpenAttachment) {
        val callbacks = mapOf(
            AccessibleMessageAction.REPLY to ("Reply" to accessibilityActions.onReply),
            AccessibleMessageAction.COPY to ("Copy message" to accessibilityActions.onCopy),
            AccessibleMessageAction.REACTION to ("Choose reaction" to accessibilityActions.onChooseReaction),
            AccessibleMessageAction.DELETE to ("Delete message" to accessibilityActions.onDelete),
        )
        val allowed = eligibleMessageActions(message, callbacks.filterValues { it.second != null }.keys)
        buildList {
            for (action in allowed) {
                val (label, callback) = callbacks.getValue(action)
                if (callback != null) add(CustomAccessibilityAction(label) { callback(); true })
            }
            for (link in links) {
                val callback = when (link.kind) {
                    AccessibleLinkKind.URL -> if (link.target == message.attachmentUrl) onOpenAttachment ?: onOpenUrl else onOpenUrl
                    AccessibleLinkKind.CHANNEL -> onOpenChannel
                    AccessibleLinkKind.NICK -> onOpenNick
                }
                if (callback != null) add(CustomAccessibilityAction(link.label) { callback(link.target); true })
            }
        }
    }
    HighlightedSurface(
        highlighted = message.highlightsMe || focused,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = if (appearance.compact) 1.dp else if (groupedWithPrevious) 2.dp else 5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onLongPress,
                    onClickLabel = "Message actions",
                    onLongClick = onLongPress,
                    onLongClickLabel = "Message actions",
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = summary
                    customActions = actions
                }
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 2.dp, vertical = 1.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (appearance.showAvatars) {
                if (groupedWithPrevious) {
                    Spacer(modifier = Modifier.size(38.dp))
                } else {
                    NickAvatar(nick = sender, avatarUrl = trustedProfile?.avatarUrl, mediaVisible = mediaVisible, description = null)
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                if (appearance.timestampPosition == TimestampPosition.ABOVE) MessageTimestamp(timestamp, appearance)
                if (!groupedWithPrevious) {
                    Row(
                        modifier = Modifier.clearAndSetSemantics {},
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val displayName = trustedProfile?.displayName
                        Text(
                            text = displayName ?: sender,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = nickColor(sender),
                        )
                        if (message.senderAccount != null && message.relayedSender == null) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (displayName != null && displayName != sender) {
                            Text(
                                text = sender,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (appearance.timestampPosition == TimestampPosition.INLINE) MessageTimestamp(timestamp, appearance)
                    }
                    if (message.relayedSender != null) {
                        Text(
                            modifier = Modifier.clearAndSetSemantics {},
                            text = "Relayed via ${message.relaySource ?: message.sender}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (groupedWithPrevious && appearance.timestampPosition == TimestampPosition.INLINE) MessageTimestamp(timestamp, appearance)
                message.channelContext?.let { channel ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .defaultMinSize(minHeight = 48.dp)
                            .then(if (onOpenChannel != null) Modifier.clickable(onClickLabel = "Open channel $channel", onClick = { onOpenChannel(channel) }) else Modifier)
                            .clearAndSetSemantics {},
                    ) {
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                modifier = Modifier.clearAndSetSemantics {},
                                text = "re: $channel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                if (!message.redacted && (message.replyToMsgid != null || message.localReplyParentRowId != null || quotedText != null)) {
                    Text(
                        text = "↩ ${quotedText ?: "earlier message"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp).clearAndSetSemantics {},
                    )
                }
                if (!message.redacted && message.attachmentUrl != null) {
                    MediaPreview(
                        descriptor = MediaDescriptor(
                            url = message.attachmentUrl,
                            name = message.attachmentName,
                            mimeType = message.attachmentMimeType,
                            sizeBytes = message.attachmentSizeBytes,
                        ),
                        mediaIdentity = mediaIdentity,
                        visible = mediaVisible,
                        onOpen = (onOpenAttachment ?: onOpenUrl)?.let { open -> { open(message.attachmentUrl) } },
                        metadataInMessageSummary = true,
                    )
                }
                val chatFontFamily = messageFontFamily(appearance.font)
                val scaledFontSize = MaterialTheme.typography.bodyMedium.fontSize * appearance.textScale
                val scaledLineHeight = MaterialTheme.typography.bodyMedium.lineHeight * appearance.textScale
                if (message.kind == MessageKind.ACTION) {
                    Text(
                        modifier = Modifier.clearAndSetSemantics {},
                        text = formattedMessage(
                            text = "✦ $body",
                            defaultColor = nickColor(sender),
                            defaultFontStyle = FontStyle.Italic,
                            onOpenChannel = onOpenChannel,
                            onOpenNick = onOpenNick,
                            onOpenUrl = onOpenUrl,
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = scaledFontSize,
                            lineHeight = scaledLineHeight,
                            fontFamily = chatFontFamily,
                        ),
                    )
                } else {
                    Text(
                        modifier = Modifier.clearAndSetSemantics {},
                        text = formattedMessage(
                            text = body,
                            onOpenChannel = onOpenChannel,
                            onOpenNick = onOpenNick,
                            onOpenUrl = onOpenUrl,
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = scaledFontSize,
                            lineHeight = scaledLineHeight,
                            fontFamily = chatFontFamily,
                        ),
                    )
                }
                if (appearance.timestampPosition == TimestampPosition.BELOW) MessageTimestamp(timestamp, appearance)
                for (media in linkedMedia) {
                    MediaPreview(
                        descriptor = media,
                        mediaIdentity = mediaIdentity,
                        visible = mediaVisible,
                        onOpen = onOpenUrl?.let { open -> { open(media.url) } },
                    )
                }
                when (messageDeliveryStatus(message)) {
                    MessageDeliveryStatus.UNCONFIRMED -> Text(
                        text = "Delivery unconfirmed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp).clearAndSetSemantics {},
                    )
                    null -> Unit
                }
                if (message.reactionsTruncated) {
                    Text(
                        text = "Partial reaction history · retained members only",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp).clearAndSetSemantics {},
                    )
                }
                if (!message.redacted && reactions.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 3.dp),
                    ) {
                        for ((emoji, nicks) in reactions) {
                            val canReact = accessibilityActions.canToggleReaction && !message.redacted && message.msgid != null
                            val reactionLabel = remember(emoji, emojiNames) { emojiAccessibilityLabel(emoji, emojiNames) }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)
                                    .then(if (canReact) Modifier.clickable(onClickLabel = "Toggle $reactionLabel reaction", onClick = { onToggleReaction(emoji) }) else Modifier)
                                    .semantics { contentDescription = "${if (canReact) "Toggle" else "View"} $reactionLabel reaction" },
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = if (message.reactionsTruncated) "$emoji ≥${nicks.size}" else "$emoji ${nicks.size}",
                                        modifier = Modifier.clearAndSetSemantics {},
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun messageFontFamily(font: MessageFont): FontFamily? = when (font) {
    MessageFont.SYSTEM -> null
    MessageFont.SANS -> FontFamily.SansSerif
    MessageFont.SERIF -> FontFamily.Serif
    MessageFont.MONOSPACE -> FontFamily.Monospace
}

@Composable
private fun MessageTimestamp(timestamp: String, appearance: ChatAppearancePreferences) {
    Text(
        text = timestamp,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = messageFontFamily(appearance.font),
        color = if (appearance.timestampStyle == TimestampStyle.MUTED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clearAndSetSemantics {},
    )
}



@Composable
public fun DayPill(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 0.5.dp,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 0.5.dp,
        )
    }
}


@Composable
public fun NewMessagesDivider(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
            thickness = 1.dp,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
            thickness = 1.dp,
        )
    }
}
