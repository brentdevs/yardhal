package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.ui.components.DayPill
import dev.brentdevs.yardhal.ui.components.MessageRow
import dev.brentdevs.yardhal.ui.components.StatusDot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface TranscriptEntry {
    public data class DayHeader(public val label: String) : TranscriptEntry
    public data class Message(public val value: ChatMessage, public val groupedWithPrevious: Boolean) : TranscriptEntry
}

private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

public enum class MemberAction { MESSAGE, WHOIS, KICK, BAN, IGNORE }

private fun buildTranscript(buffer: ConversationBuffer): List<TranscriptEntry> {
    val zone = ZoneId.systemDefault()
    val ordered = buffer.messages.asReversed()
    val entries = ArrayList<TranscriptEntry>(ordered.size + 4)
    var lastDate: LocalDate? = null
    var previousSender: String? = null
    var previousTimestamp = 0L
    for (message in ordered) {
        val timestamp = message.timestampMs
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        if (date != lastDate) {
            entries.add(TranscriptEntry.DayHeader(formatDayLabel(date)))
            lastDate = date
            previousSender = null
        }
        val groupable = message.kind != dev.brentdevs.yardhal.core.data.MessageKind.SYSTEM &&
            message.kind != dev.brentdevs.yardhal.core.data.MessageKind.JOIN &&
            message.kind != dev.brentdevs.yardhal.core.data.MessageKind.PART
        val grouped = groupable &&
            previousSender == message.sender &&
            timestamp - previousTimestamp < GROUP_WINDOW_MS
        entries.add(TranscriptEntry.Message(message, grouped))
        previousSender = message.sender
        previousTimestamp = timestamp
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
    onMemberAction: (MemberAction, String) -> Unit = { _, _ -> },
    sharedDraft: String? = null,
    onSharedConsumed: () -> Unit = {},
    onPickFile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var actionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var membersVisible by remember { mutableStateOf(false) }
    var memberTarget by remember { mutableStateOf<String?>(null) }

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
                    members = buffer.members.map { it.nick },
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
                        is TranscriptEntry.DayHeader -> DayPill(entry.label)
                        is TranscriptEntry.Message -> {
                            val message = entry.value
                            MessageRow(
                                message = message,
                                groupedWithPrevious = entry.groupedWithPrevious,
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
        val operators = buffer.members.filter { it.isOperator }
        val voices = buffer.members.filter { it.isVoice }
        val bots = buffer.members.filter { it.looksLikeBot }
        val plain = buffer.members.filter { member -> !member.isOperator && !member.isVoice && !member.looksLikeBot }
        ModalBottomSheet(onDismissRequest = { membersVisible = false }) {
            Text(
                text = "Members · ${buffer.members.size}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            LazyColumn(modifier = Modifier.padding(horizontal = 8.dp)) {
                val sections = listOf(
                    "Operators" to operators,
                    "Voices" to voices,
                    "Bots & Relays" to bots,
                    "Users" to plain,
                )
                for ((label, section) in sections) {
                    if (section.isEmpty()) continue
                    item(key = "section-$label") {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(section.size, key = { index -> "member-${label}-${section[index].nick}" }) { index ->
                        val member = section[index]
                        val presence = buffer.memberPresence[member.nick]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { memberTarget = member.nick }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (presence?.away == true) "○" else "●",
                                color = if (presence?.away == true) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            if (member.symbol != null) {
                                Text(
                                    text = member.symbol.toString(),
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                            Text(member.nick, style = MaterialTheme.typography.bodyLarge)
                            presence?.account?.let { account ->
                                Text(
                                    text = account,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (memberTarget != null && membersVisible) {
        val nick = memberTarget!!
        AlertDialog(
            onDismissRequest = { memberTarget = null },
            title = { Text(nick) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { onMemberAction(MemberAction.MESSAGE, nick); memberTarget = null }) {
                        Text("Send message")
                    }
                    TextButton(onClick = { onMemberAction(MemberAction.WHOIS, nick); memberTarget = null }) {
                        Text("WHOIS")
                    }
                    if (buffer.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL) {
                        TextButton(onClick = { onMemberAction(MemberAction.KICK, nick); memberTarget = null }) {
                            Text("Kick")
                        }
                        TextButton(onClick = { onMemberAction(MemberAction.BAN, nick); memberTarget = null }) {
                            Text("Ban")
                        }
                    }
                    TextButton(onClick = { onMemberAction(MemberAction.IGNORE, nick); memberTarget = null }) {
                        Text("Ignore")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { memberTarget = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun LaunchedEffectOnce(key: Any?, effect: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) { effect() }
}

private sealed interface OverviewEntry {
    public data class NetworkHeader(
        public val network: dev.brentdevs.yardhal.coordinator.UiNetwork,
    ) : OverviewEntry

    public data class BufferRow(
        public val buffer: ConversationBuffer,
        public val muted: Boolean,
    ) : OverviewEntry
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun NetworkOverviewScreen(
    buffers: List<ConversationBuffer>,
    networks: List<dev.brentdevs.yardhal.coordinator.UiNetwork>,
    mutedKeys: Set<String>,
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
    onMarkRead: (String) -> Unit = {},
    onToggleMute: (String) -> Unit = {},
    onLeave: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    var browseVisible by remember { mutableStateOf(false) }
    var debugVisible by remember { mutableStateOf(false) }
    var rowMenuFor by remember { mutableStateOf<String?>(null) }
    var pendingLeave by remember { mutableStateOf<ConversationBuffer?>(null) }

    val entries = remember(networks, buffers, mutedKeys) {
        buildList {
            for (network in networks.sortedBy { it.name.lowercase() }) {
                add(OverviewEntry.NetworkHeader(network))
                val own = buffers
                    .filter { it.ref.networkId == network.id && it.ref.kind != dev.brentdevs.yardhal.core.data.ConversationKind.SERVER }
                    .sortedWith(
                        compareByDescending<ConversationBuffer> { it.hasUnread }
                            .thenBy { it.ref.kind != dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL }
                            .thenBy { it.displayName.lowercase() },
                    )
                for (buffer in own) {
                    add(OverviewEntry.BufferRow(buffer, buffer.key in mutedKeys))
                }
            }
        }
    }

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
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            items(entries.size, key = { index ->
                when (val entry = entries[index]) {
                    is OverviewEntry.NetworkHeader -> "net-${entry.network.id}"
                    is OverviewEntry.BufferRow -> "buf-${entry.buffer.key}"
                }
            }) { index ->
                when (val entry = entries[index]) {
                    is OverviewEntry.NetworkHeader -> {
                        val network = entry.network
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectServer(network.id) }
                                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusDot(network.status)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = network.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { pendingRemoval = network.id }) { Text("Remove") }
                        }
                    }
                    is OverviewEntry.BufferRow -> {
                        val buffer = entry.buffer
                        val last = buffer.messages.lastOrNull()
                        val isChannel = buffer.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickableCompat(
                                    onClick = { onSelect(buffer.key) },
                                    onLongClick = { rowMenuFor = buffer.key },
                                )
                                .padding(start = 42.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = buffer.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (buffer.hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    if (entry.muted) {
                                        Text(
                                            text = "  muted",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (last != null) {
                                    Text(
                                        text = if (last.sentByUs) last.text else "${last.sender}: ${last.text}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                } else {
                                    Text(
                                        text = "No messages yet",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (buffer.hasUnread) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (rowMenuFor != null) {
        val buffer = entries.filterIsInstance<OverviewEntry.BufferRow>().firstOrNull { it.buffer.key == rowMenuFor }
        if (buffer != null) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { rowMenuFor = null },
            ) {
                DropdownMenuItem(
                    text = { Text("Mark as read") },
                    onClick = { onMarkRead(buffer.buffer.key); rowMenuFor = null },
                )
                DropdownMenuItem(
                    text = { Text(if (buffer.muted) "Unmute" else "Mute") },
                    onClick = { onToggleMute(buffer.buffer.key); rowMenuFor = null },
                )
                DropdownMenuItem(
                    text = { Text("Leave") },
                    onClick = { pendingLeave = buffer.buffer; rowMenuFor = null },
                )
            }
        }
    }

    if (pendingLeave != null) {
        val buffer = pendingLeave!!
        val isChannel = buffer.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL
        AlertDialog(
            onDismissRequest = { pendingLeave = null },
            title = { Text(if (isChannel) "Leave ${buffer.displayName}?" else "Close conversation?") },
            text = { Text(if (isChannel) "You will part the channel." else "The conversation is removed from the list; history stays on disk.") },
            confirmButton = {
                TextButton(onClick = {
                    onLeave(buffer.key)
                    pendingLeave = null
                }) { Text(if (isChannel) "Leave" else "Close") }
            },
            dismissButton = {
                TextButton(onClick = { pendingLeave = null }) { Text("Keep") }
            },
        )
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.then(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
