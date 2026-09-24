package dev.brentdevs.yardhal.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.core.data.MessageKind
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
    reactions: Map<String, Set<String>>,
    quotedText: String?,
    onLongPress: () -> Unit,
    onToggleReaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message.kind) {
        MessageKind.SYSTEM, MessageKind.JOIN, MessageKind.PART -> SystemLine(message, modifier)
        else -> ChatLine(message, groupedWithPrevious, reactions, quotedText, onLongPress, onToggleReaction, modifier)
    }
}

@Composable
private fun SystemLine(message: ChatMessage, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(14.dp),
        )
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatLine(
    message: ChatMessage,
    groupedWithPrevious: Boolean,
    reactions: Map<String, Set<String>>,
    quotedText: String?,
    onLongPress: () -> Unit,
    onToggleReaction: (String) -> Unit,
    modifier: Modifier,
) {
    HighlightedSurface(
        highlighted = message.highlightsMe,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = if (groupedWithPrevious) 1.dp else 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 2.dp, vertical = 1.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (groupedWithPrevious) {
                Spacer(modifier = Modifier.size(38.dp))
            } else {
                NickAvatar(nick = message.sender)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (!groupedWithPrevious) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = message.sender,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = nickColor(message.sender),
                    )
                    Text(
                        text = formatTime(message.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (message.replyToMsgid != null) {
                Text(
                    text = "↩ ${quotedText ?: "earlier message"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            if (message.attachmentUrl != null) {
                Text(
                    text = "📎 ${message.attachmentUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            if (message.kind == MessageKind.ACTION) {
                Text(
                    text = "✦ ${message.text}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = nickColor(message.sender),
                )
            } else {
                Text(
                    text = formattedMessage(message.text),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (reactions.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 3.dp),
                ) {
                    for ((emoji, nicks) in reactions) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            onClick = { onToggleReaction(emoji) },
                        ) {
                            Text(
                                text = "$emoji ${nicks.size}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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

@Composable
public fun DayPill(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

public fun formatTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
