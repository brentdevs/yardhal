package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.ui.components.DaySeparator
import dev.brentdevs.yardhal.ui.components.MessageRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface TranscriptEntry {
    public data class DayHeader(public val label: String) : TranscriptEntry
    public data class Message(public val value: ChatMessage) : TranscriptEntry
}

private fun buildTranscript(buffer: ConversationBuffer): List<TranscriptEntry> {
    val zone = ZoneId.systemDefault()
    val ordered = buffer.messages.asReversed()
    val entries = ArrayList<TranscriptEntry>(ordered.size + 4)
    var lastDate: LocalDate? = null
    for (message in ordered) {
        val date = Instant.ofEpochMilli(message.timestampMs).atZone(zone).toLocalDate()
        if (date != lastDate) {
            entries.add(TranscriptEntry.DayHeader(formatDayLabel(date)))
            lastDate = date
        }
        entries.add(TranscriptEntry.Message(message))
    }
    return entries
}

private fun formatDayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
    }
}

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🎉", "👀", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ConversationScreen(
    buffer: ConversationBuffer,
    networkName: String,
    connected: Boolean,
    onSend: (String) -> Unit,
    onOpenJoin: () -> Unit,
    onLoadHistory: () -> Unit,
    onReact: (String, String) -> Unit,
    onSetReplyDraft: (ChatMessage?) -> Unit,
    onDelete: (String) -> Unit,
    sharedDraft: String? = null,
    onSharedConsumed: () -> Unit = {},
    onPickFile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var actionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var membersVisible by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(buffer.displayName, style = MaterialTheme.typography.titleMedium)
                        if (!buffer.topic.isNullOrBlank()) {
                            Text(
                                buffer.topic!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    if (buffer.members.isNotEmpty()) {
                        TextButton(onClick = { membersVisible = true }) {
                            Text("${buffer.members.size}")
                        }
                    }
                    TextButton(onClick = onOpenJoin) { Text("Join") }
                },
            )
        },
        bottomBar = {
            Column {
                val typers = buffer.activeTypers(System.currentTimeMillis())
                if (typers.isNotEmpty()) {
                    Text(
                        text = when (typers.size) {
                            1 -> "${typers[0]} is typing…"
                            2 -> "${typers[0]} and ${typers[1]} are typing…"
                            else -> "several people are typing…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                    )
                }
                val reply = buffer.replyDraft
                if (reply != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "↩ Replying to ${reply.sender}: ${reply.text.take(48)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        TextButton(onClick = { onSetReplyDraft(null) }) { Text("Cancel") }
                    }
                }
                ComposerBar(
                    enabled = connected,
                    members = buffer.members,
                    onAttach = onPickFile,
                    initialDraft = sharedDraft,
                    onSend = { text ->
                        onSharedConsumed()
                        onSend(text)
                    },
                )
            }
        },
    ) { padding ->
        LaunchedEffectOnce(key = buffer.key, effect = onLoadHistory)
        if (buffer.messages.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (connected) "No messages yet — say hello." else "Connecting…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                val entries = buildTranscript(buffer)
                items(entries.size, key = { index ->
                    when (val entry = entries[index]) {
                        is TranscriptEntry.DayHeader -> "header-${entry.label}-$index"
                        is TranscriptEntry.Message -> "msg-${entry.value.localId}"
                    }
                }) { index ->
                    when (val entry = entries[index]) {
                        is TranscriptEntry.DayHeader -> DaySeparator(entry.label)
                        is TranscriptEntry.Message -> {
                            val message = entry.value
                            MessageRow(
                                message = message,
                                reactions = buffer.reactions[message.msgid].orEmpty()
                                    .filterValues { it.isNotEmpty() },
                                quotedText = message.replyToMsgid?.let { target ->
                                    buffer.messages.firstOrNull { it.msgid == target }?.let { "${it.sender}: ${it.text.take(60)}" }
                                },
                                onLongPress = {
                                    if (message.msgid != null || !message.sentByUs) actionTarget = message
                                },
                                onToggleReaction = { emoji ->
                                    message.msgid?.let { msgid -> onReact(msgid, emoji) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (actionTarget != null) {
        val target = actionTarget!!
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(target.sender.ifEmpty { "Message" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QUICK_REACTIONS.forEach { emoji ->
                            TextButton(onClick = {
                                target.msgid?.let { msgid -> onReact(msgid, emoji) }
                                actionTarget = null
                            }) { Text(emoji) }
                        }
                    }
                    TextButton(onClick = {
                        if (!target.sentByUs) onSetReplyDraft(target)
                        actionTarget = null
                    }) { Text("Reply") }
                    if (target.sentByUs && target.msgid != null) {
                        TextButton(onClick = {
                            onDelete(target.msgid!!)
                            actionTarget = null
                        }) { Text("Delete") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionTarget = null }) { Text("Close") }
            },
        )
    }

    if (membersVisible) {
        AlertDialog(
            onDismissRequest = { membersVisible = false },
            title = { Text("Members · ${buffer.members.size}") },
            text = {
                LazyColumn {
                    items(buffer.members.size) { index ->
                        val nick = buffer.members[index]
                        val presence = buffer.memberPresence[nick]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (presence?.away == true) "○" else "●",
                                color = if (presence?.away == true) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text(nick)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { membersVisible = false }) { Text("Done") }
            },
        )
    }
}

@Composable
private fun LaunchedEffectOnce(key: Any?, effect: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) { effect() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun NetworkOverviewScreen(
    buffers: List<ConversationBuffer>,
    networks: List<dev.brentdevs.yardhal.coordinator.UiNetwork>,
    onSelect: (String) -> Unit,
    onSelectServer: (String) -> Unit,
    onAddNetwork: () -> Unit,
    onRemoveNetwork: (String) -> Unit,
    onBrowseChannels: () -> Unit,
    channelList: List<dev.brentdevs.yardhal.coordinator.LiveCoordinator.ChannelListEntry>,
    onJoinFromList: (String) -> Unit,
    onOpenDebug: () -> Unit,
    rawLogVersion: Int,
    rawLogProvider: () -> List<dev.brentdevs.yardhal.coordinator.LiveCoordinator.RawFrame>,
    showBouncerButton: Boolean = false,
    onOpenBouncer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    var browseVisible by remember { mutableStateOf(false) }
    var debugVisible by remember { mutableStateOf(false) }

    if (debugVisible) {
        val frames = remember(rawLogVersion) { rawLogProvider() }
        AlertDialog(
            onDismissRequest = { debugVisible = false },
            title = { Text("Traffic · last ${frames.size}") },
            text = {
                LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                    items(frames.size) { index ->
                        val frame = frames[frames.size - 1 - index]
                        Text(
                            text = (if (frame.outbound) "→ " else "← ") + frame.line.take(160),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { debugVisible = false }) { Text("Close") }
            },
        )
    }

    if (browseVisible) {
        AlertDialog(
            onDismissRequest = { browseVisible = false },
            title = { Text("Channels · ${channelList.size}") },
            text = {
                if (channelList.isEmpty()) {
                    Text("Loading list…")
                } else {
                    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                        items(channelList.size) { index ->
                            val entry = channelList[index]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                    if (entry.topic.isNotBlank()) {
                                        Text(
                                            entry.topic,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                Badge { Text("${entry.users}") }
                                TextButton(onClick = { onJoinFromList(entry.name) }) { Text("Join") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { browseVisible = false }) { Text("Close") }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Yardhal") },
                actions = {
                    if (showBouncerButton) {
                        TextButton(onClick = onOpenBouncer) { Text("Bouncer") }
                    }
                    TextButton(onClick = { debugVisible = true; onOpenDebug() }) { Text("Debug") }
                    TextButton(onClick = { browseVisible = true; onBrowseChannels() }) { Text("List") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAddNetwork) {
                Text("+ Network")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(networks.size, key = { networks[it].id }) { index ->
                val network = networks[index]
                Card(
                    onClick = { onSelectServer(network.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(network.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                when (network.status) {
                                    dev.brentdevs.yardhal.coordinator.ConnectionStatus.REGISTERED ->
                                        "${network.ownNick} · connected"
                                    dev.brentdevs.yardhal.coordinator.ConnectionStatus.CONNECTING -> "connecting…"
                                    dev.brentdevs.yardhal.coordinator.ConnectionStatus.DISCONNECTED -> "offline"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { pendingRemoval = network.id }) { Text("Remove") }
                    }
                }
            }
            items(buffers.size, key = { buffers[it].key }) { index ->
                val buffer = buffers[index]
                Card(
                    onClick = { onSelect(buffer.key) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buffer.displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val last = buffer.messages.lastOrNull()
                        if (last != null && !last.sentByUs && buffer.hasUnread) {
                            Badge { Text("•") }
                        }
                    }
                }
            }
        }
    }

    if (pendingRemoval != null) {
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove network?") },
            text = { Text("This forgets the connection and its local transcript.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval?.let(onRemoveNetwork)
                    pendingRemoval = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Keep") }
            },
        )
    }
}
