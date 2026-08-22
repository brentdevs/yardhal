package dev.brentdevs.yardhal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
public fun MessageRow(
    message: ChatMessage,
    reactions: Map<String, Set<String>>,
    quotedText: String?,
    onLongPress: () -> Unit,
    onToggleReaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message.kind) {
        MessageKind.SYSTEM, MessageKind.JOIN, MessageKind.PART -> SystemLine(message.text, modifier)
        else -> ChatLine(message, reactions, quotedText, onLongPress, onToggleReaction, modifier)
    }
}

@Composable
private fun SystemLine(text: String, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontStyle = FontStyle.Italic,
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatLine(
    message: ChatMessage,
    reactions: Map<String, Set<String>>,
    quotedText: String?,
    onLongPress: () -> Unit,
    onToggleReaction: (String) -> Unit,
    modifier: Modifier,
) {
    val highlightBackground =
        if (message.highlightsMe) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
        } else {
            Color.Transparent
        }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(highlightBackground)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        if (message.replyToMsgid != null) {
            Text(
                text = "↩ ${quotedText ?: "earlier message"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = formatTime(message.timestampMs),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message.kind != MessageKind.ACTION) {
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = nickColor(message.sender),
                )
            }
        }
        val bodyStyle =
            if (message.kind == MessageKind.ACTION) {
                MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic, color = nickColor(message.sender))
            } else {
                MaterialTheme.typography.bodyMedium
            }
        Text(text = message.text, style = bodyStyle)
        if (reactions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                reactions.forEach { (emoji, nicks) ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = { onToggleReaction(emoji) },
                    ) {
                        Text(
                            text = "$emoji ${nicks.size}",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

public fun formatTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

@Composable
public fun DaySeparator(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
