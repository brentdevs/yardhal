package dev.brentdevs.yardhal.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.coordinator.UserProfile
import dev.brentdevs.yardhal.core.data.ChatAppearancePreferences
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.ui.image.LocalRemoteImageLoader
import dev.brentdevs.yardhal.ui.image.RemoteImageState
import dev.brentdevs.yardhal.ui.image.rememberRemoteImage
import dev.brentdevs.yardhal.ui.theme.nickColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

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
    onOpenAttachment: (String) -> Unit = {},
    onOpenChannel: (String) -> Unit = {},
    onOpenNick: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    profile: UserProfile? = null,
) {
    when (message.kind) {
        MessageKind.SYSTEM, MessageKind.JOIN, MessageKind.PART -> SystemLine(message, appearance, modifier)
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
        )
    }
}

@Composable
private fun SystemLine(message: ChatMessage, appearance: ChatAppearancePreferences, modifier: Modifier) {
    val chatFontFamily = if (appearance.monospaceFont) FontFamily.Monospace else null
    val scaledFontSize = MaterialTheme.typography.bodySmall.fontSize * appearance.textScale
    val scaledLineHeight = MaterialTheme.typography.bodySmall.lineHeight * appearance.textScale
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (appearance.compact) 1.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = scaledFontSize,
                lineHeight = scaledLineHeight,
                fontFamily = chatFontFamily,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(14.dp),
        )
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = scaledFontSize,
                lineHeight = scaledLineHeight,
                fontFamily = chatFontFamily,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
        )
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
    onOpenAttachment: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenNick: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier,
) {
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
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 2.dp, vertical = 1.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (groupedWithPrevious) {
                Spacer(modifier = Modifier.size(38.dp))
            } else {
                NickAvatar(nick = message.sender, avatarUrl = profile?.avatarUrl)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (!groupedWithPrevious) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val displayName = profile?.displayName
                        Text(
                            text = displayName ?: message.sender,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = nickColor(message.sender),
                        )
                        if (message.senderAccount != null) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (displayName != null && displayName != message.sender) {
                            Text(
                                text = message.sender,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = formatTime(message.timestampMs),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                message.channelContext?.let { channel ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .defaultMinSize(minHeight = 48.dp),
                        onClick = { onOpenChannel(channel) },
                    ) {
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "re: $channel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                if (message.replyToMsgid != null || message.localReplyParentRowId != null || quotedText != null) {
                    Text(
                        text = "↩ ${quotedText ?: "earlier message"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                if (message.attachmentUrl != null) {
                    AttachmentPreview(
                        url = message.attachmentUrl,
                        name = message.attachmentName,
                        mimeType = message.attachmentMimeType,
                        sizeBytes = message.attachmentSizeBytes,
                        onOpen = { onOpenAttachment(message.attachmentUrl) },
                    )
                }
                val chatFontFamily = if (appearance.monospaceFont) FontFamily.Monospace else null
                val scaledFontSize = MaterialTheme.typography.bodyMedium.fontSize * appearance.textScale
                val scaledLineHeight = MaterialTheme.typography.bodyMedium.lineHeight * appearance.textScale
                if (message.kind == MessageKind.ACTION) {
                    Text(
                        text = formattedMessage(
                            text = "✦ ${message.text}",
                            defaultColor = nickColor(message.sender),
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
                        text = formattedMessage(
                            text = message.text,
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
                if (message.reactionsTruncated) {
                    Text(
                        text = "Partial reaction history · retained members only",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (reactions.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 3.dp),
                    ) {
                        for ((emoji, nicks) in reactions) {
                            Surface(
                                onClick = { onToggleReaction(emoji) },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = if (message.reactionsTruncated) "$emoji ≥${nicks.size}" else "$emoji ${nicks.size}",
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

internal fun attachmentDisplayName(url: String, name: String?, sizeBytes: Long?): String {
    val displayName = name?.takeIf { it.isNotBlank() }
        ?: url.substringBefore('?').substringBefore('#').substringAfterLast('/').ifEmpty { "Attachment" }
    return if (sizeBytes == null) displayName else "$displayName · $sizeBytes B"
}

internal fun isImageAttachment(url: String, mimeType: String?): Boolean {
    if (mimeType != null) return mimeType.startsWith("image/", ignoreCase = true)
    val cleanUrl = url.substringBefore('?').substringBefore('#')
    return cleanUrl.endsWith(".png", true) || cleanUrl.endsWith(".jpg", true) ||
        cleanUrl.endsWith(".jpeg", true) || cleanUrl.endsWith(".gif", true) || cleanUrl.endsWith(".webp", true)
}

@Composable
private fun AttachmentPreview(
    url: String,
    name: String?,
    mimeType: String?,
    sizeBytes: Long?,
    onOpen: () -> Unit,
) {
    val isImage = isImageAttachment(url, mimeType)
    val displayName = attachmentDisplayName(url, name, sizeBytes)

    if (isImage) {
        var loadPreview by rememberSaveable(url) { mutableStateOf(false) }
        if (!loadPreview) {
            Surface(
                onClick = { loadPreview = true },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .defaultMinSize(minHeight = 48.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Load image: $displayName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            val image = rememberRemoteImage(LocalRemoteImageLoader.current, url, 512).value
            Surface(
                onClick = onOpen,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                if (image is RemoteImageState.Success) {
                    Image(
                        bitmap = image.bitmap,
                        contentDescription = "Image attachment: $displayName",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .defaultMinSize(minHeight = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (image == RemoteImageState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (image == RemoteImageState.Unavailable) {
                                Text(
                                    text = "Preview unavailable · Open externally",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        Surface(
            onClick = onOpen,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .padding(vertical = 3.dp)
                .defaultMinSize(minHeight = 48.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
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

public fun formatTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

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
