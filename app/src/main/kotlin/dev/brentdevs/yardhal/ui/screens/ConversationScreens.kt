package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.JoinState
import dev.brentdevs.yardhal.core.data.ChatAppearancePreferences
import dev.brentdevs.yardhal.ui.components.DayPill
import dev.brentdevs.yardhal.ui.components.MessageRow
import dev.brentdevs.yardhal.ui.components.NewMessagesDivider
import dev.brentdevs.yardhal.ui.components.StatusDot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface TranscriptEntry {
    public data class DayHeader(public val label: String) : TranscriptEntry
    public data class UnreadDivider(public val label: String) : TranscriptEntry
    public data class Message(public val value: ChatMessage, public val groupedWithPrevious: Boolean) : TranscriptEntry
}

public enum class MemberAction { MESSAGE, WHOIS, KICK, BAN, IGNORE }

private fun buildTranscript(buffer: ConversationBuffer): List<TranscriptEntry> {
    val zone = ZoneId.systemDefault()
    val ordered = buffer.messages.asReversed()
    val grouped = dev.brentdevs.yardhal.core.data.MessageGrouper.group(
        messages = ordered.map { it.timestampMs },
        isGroupable = { index ->
            when (ordered[index].kind) {
                dev.brentdevs.yardhal.core.data.MessageKind.SYSTEM,
                dev.brentdevs.yardhal.core.data.MessageKind.JOIN,
                dev.brentdevs.yardhal.core.data.MessageKind.PART,
                -> false
                else -> true
            }
        },
        senderOf = { index -> ordered[index].sender },
    )
    val entries = ArrayList<TranscriptEntry>(ordered.size + 4)
    val unreadFrom = buffer.unreadFromTimestampMs
    var lastDate: LocalDate? = null
    var previousTimestamp = Long.MAX_VALUE
    var renderedUnreadDivider = false
    for (index in ordered.indices) {
        val message = ordered[index]
        val timestamp = message.timestampMs
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        if (date != lastDate) {
            entries.add(TranscriptEntry.DayHeader(formatDayLabel(date)))
            lastDate = date
            previousTimestamp = Long.MAX_VALUE
        }
        if (unreadFrom != null && timestamp < unreadFrom && previousTimestamp >= unreadFrom) {
            entries.add(TranscriptEntry.UnreadDivider("New messages"))
            renderedUnreadDivider = true
        }
        entries.add(TranscriptEntry.Message(message, grouped[index].groupedWithPrevious))
        previousTimestamp = timestamp
    }
    if (unreadFrom != null && !renderedUnreadDivider) {
        entries.add(TranscriptEntry.UnreadDivider("New messages"))
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
private val HTTP_LINK = Regex("https?://\\S+")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ConversationScreen(
    buffer: ConversationBuffer,
    networkName: String,
    channels: List<String> = emptyList(),
    connected: Boolean,
    canSendOffline: (String) -> Boolean = { false },
    onSend: (String) -> Boolean,
    onOpenJoin: () -> Unit,
    onLoadHistory: () -> Unit,
    onReact: (String, String) -> Unit,
    onSetReplyDraft: (ChatMessage?) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenBuffers: (() -> Unit)? = null,
    onRetryJoin: () -> Unit = {},
    searchTargetRowId: Long? = null,
    onSearchTargetShown: () -> Unit = {},
    appearance: ChatAppearancePreferences = ChatAppearancePreferences(),
    onOpenAppearance: () -> Unit = {},
    onMemberAction: (MemberAction, String) -> Unit = { _, _ -> },
    onOpenDm: (String) -> Unit = {},
    sharedDraft: String? = null,
    onSharedConsumed: () -> Unit = {},
    onPickFile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var actionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var membersVisible by remember { mutableStateOf(false) }
    var memberTarget by remember { mutableStateOf<String?>(null) }
    var overflowVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun openLink(url: String) {
        val uri = android.net.Uri.parse(url)
        if (uri.scheme !in setOf("http", "https")) return
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val entries = remember(buffer) { buildTranscript(buffer) }
    val unreadIndex = entries.indexOfFirst { it is TranscriptEntry.UnreadDivider }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var focusedRowId by remember { mutableStateOf<Long?>(null) }

    androidx.compose.runtime.LaunchedEffect(searchTargetRowId, entries) {
        if (searchTargetRowId != null) {
            val index = entries.indexOfFirst { entry ->
                entry is TranscriptEntry.Message && entry.value.storedRowId == searchTargetRowId
            }
            if (index >= 0) {
                listState.scrollToItem(index)
                focusedRowId = searchTargetRowId
                onSearchTargetShown()
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(focusedRowId) {
        if (focusedRowId != null) {
            delay(2500)
            focusedRowId = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(buffer.displayName, style = MaterialTheme.typography.titleMedium)
                        val topic = buffer.topic
                        Text(
                            text = if (topic.isNullOrBlank()) networkName else "$networkName · $topic",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    if (onOpenBuffers != null) {
                        IconButton(onClick = onOpenBuffers) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open conversations")
                        }
                    }
                },
                actions = {
                    if (buffer.members.isNotEmpty()) {
                        IconButton(onClick = { membersVisible = true }) {
                            Icon(Icons.Filled.People, contentDescription = "Members · ${buffer.members.size}")
                        }
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search messages")
                    }
                    Box {
                        IconButton(onClick = { overflowVisible = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Conversation options")
                        }
                        DropdownMenu(expanded = overflowVisible, onDismissRequest = { overflowVisible = false }) {
                            DropdownMenuItem(text = { Text("Join channel") }, onClick = {
                                overflowVisible = false
                                onOpenJoin()
                            })
                            if (unreadIndex >= 0) {
                                DropdownMenuItem(text = { Text("Jump to unread") }, onClick = {
                                    overflowVisible = false
                                    coroutineScope.launch { listState.animateScrollToItem(unreadIndex) }
                                })
                            }
                            DropdownMenuItem(text = { Text("Chat appearance") }, onClick = {
                                overflowVisible = false
                                onOpenAppearance()
                            })
                        }
                    }
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
                    canSendOffline = canSendOffline,
                    members = buffer.members.map { it.nick },
                    channels = channels,
                    onAttach = onPickFile,
                    initialDraft = sharedDraft,
                    onInitialDraftCaptured = onSharedConsumed,
                    onSend = onSend,
                )
            }
        },
    ) { padding ->
        LaunchedEffectOnce(key = buffer.key, effect = onLoadHistory)
        if (buffer.messages.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            buffer.joinState == JoinState.FAILED -> "Could not join ${buffer.displayName}"
                            connected -> "No messages yet — say hello."
                            else -> "Offline · waiting for a connection"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (buffer.joinState == JoinState.FAILED) {
                        TextButton(onClick = onRetryJoin) { Text("Retry join") }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                val statusText = when {
                    buffer.joinState == JoinState.FAILED -> "Could not join ${buffer.displayName}"
                    !connected -> "Offline · messages will resume after reconnection"
                    buffer.joinState == JoinState.JOINING -> "Joining ${buffer.displayName}…"
                    else -> null
                }
                if (statusText != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (buffer.joinState == JoinState.FAILED) {
                            TextButton(onClick = onRetryJoin) { Text("Retry") }
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(entries.size, key = { index ->
                        when (val entry = entries[index]) {
                            is TranscriptEntry.DayHeader -> "header-${entry.label}-$index"
                            is TranscriptEntry.UnreadDivider -> "unread-$index"
                            is TranscriptEntry.Message -> "msg-${entry.value.localId}"
                        }
                    }) { index ->
                        when (val entry = entries[index]) {
                            is TranscriptEntry.DayHeader -> DayPill(entry.label)
                            is TranscriptEntry.UnreadDivider -> NewMessagesDivider(entry.label)
                            is TranscriptEntry.Message -> {
                                val message = entry.value
                                MessageRow(
                                    message = message,
                                    groupedWithPrevious = entry.groupedWithPrevious,
                                    focused = message.storedRowId != null && message.storedRowId == focusedRowId,
                                    appearance = appearance,
                                    reactions = buffer.reactions[message.msgid].orEmpty()
                                        .filterValues { it.isNotEmpty() },
                                    quotedText = message.replyToMsgid?.let { target ->
                                        buffer.messages.firstOrNull { it.msgid == target }?.let { "${it.sender}: ${it.text.take(60)}" }
                                    },
                                    onLongPress = {
                                        actionTarget = message
                                    },
                                    onToggleReaction = { emoji ->
                                        message.msgid?.let { msgid -> onReact(msgid, emoji) }
                                    },
                                    onOpenAttachment = ::openLink,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (actionTarget != null) {
        val target = actionTarget ?: return
        ModalBottomSheet(onDismissRequest = { actionTarget = null }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(target.sender.ifEmpty { "Message" }, style = MaterialTheme.typography.titleMedium)
                Text(target.text, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                if (target.msgid != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        QUICK_REACTIONS.forEach { emoji ->
                            TextButton(onClick = {
                                target.msgid?.let { msgid -> onReact(msgid, emoji) }
                                actionTarget = null
                            }) { Text(emoji) }
                        }
                    }
                }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Message", target.text))
                    actionTarget = null
                }) { Text("Copy message") }
                val firstLink = HTTP_LINK.find(target.text)?.value?.trimEnd('.', ',', ';', ')')
                if (firstLink != null) {
                    TextButton(onClick = {
                        openLink(firstLink)
                        actionTarget = null
                    }) { Text("Open link") }
                }
                if (!target.sentByUs && target.msgid != null) {
                    TextButton(onClick = {
                        onSetReplyDraft(target)
                        actionTarget = null
                    }) { Text("Reply") }
                }
                if (target.sentByUs && target.msgid != null) {
                    TextButton(onClick = {
                        target.msgid?.let(onDelete)
                        actionTarget = null
                    }) { Text("Delete message") }
                }
            }
        }
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
        val nick = memberTarget ?: return
        AlertDialog(
            onDismissRequest = { memberTarget = null },
            title = { Text(nick) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { onOpenDm(nick); memberTarget = null }) {
                        Text("Message")
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

    public data class SectionHeader(public val label: String) : OverviewEntry

    public data class BufferRow(
        public val buffer: ConversationBuffer,
        public val pinned: Boolean,
        public val muted: Boolean,
        public val inGroup: Boolean,
    ) : OverviewEntry
}

private fun buildOverviewEntries(
    networks: List<dev.brentdevs.yardhal.coordinator.UiNetwork>,
    buffers: List<ConversationBuffer>,
    mutedKeys: Set<String>,
    order: dev.brentdevs.yardhal.core.data.ChannelOrderState,
): List<OverviewEntry> {
    val entries = ArrayList<OverviewEntry>()
    val comparison = compareByDescending<ConversationBuffer> { it.hasUnread }
        .thenBy { it.ref.kind != dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL }
        .thenBy { it.displayName.lowercase() }
    for (network in networks.sortedBy { it.name.lowercase() }) {
        entries.add(OverviewEntry.NetworkHeader(network))
        val own = buffers.filter {
            it.ref.networkId == network.id && it.ref.kind != dev.brentdevs.yardhal.core.data.ConversationKind.SERVER
        }
        val channels = own.filter { it.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL }
        val directs = own.filter { it.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.DIRECT_MESSAGE }

        fun rowFor(buffer: ConversationBuffer): OverviewEntry.BufferRow =
            OverviewEntry.BufferRow(
                buffer = buffer,
                pinned = buffer.key in order.pinnedKeys,
                muted = buffer.key in mutedKeys,
                inGroup = order.groupOf(buffer.key) != null,
            )

        val displayedKeys = HashSet<String>()
        val pinnedBuffers = order.pinnedKeys.mapNotNull { key -> own.firstOrNull { it.key == key } }
            .filter { displayedKeys.add(it.key) }
        if (pinnedBuffers.isNotEmpty()) {
            entries.add(OverviewEntry.SectionHeader("Pinned"))
            pinnedBuffers.forEach { entries.add(rowFor(it)) }
        }

        for (groupId in order.groupOrder) {
            val group = order.groups.firstOrNull { it.id == groupId } ?: continue
            val members = group.memberKeys.mapNotNull { key -> own.firstOrNull { it.key == key } }
                .filter { displayedKeys.add(it.key) }
            if (members.isEmpty()) continue
            entries.add(OverviewEntry.SectionHeader(group.name))
            members.forEach { entries.add(rowFor(it)) }
        }

        val looseChannels = channels.filterNot { it.key in displayedKeys }.sortedWith(comparison)
        if (looseChannels.isNotEmpty()) {
            entries.add(OverviewEntry.SectionHeader("Channels"))
            looseChannels.forEach { entries.add(rowFor(it)) }
        }
        val looseDirects = directs.filterNot { it.key in displayedKeys }.sortedWith(comparison)
        if (looseDirects.isNotEmpty()) {
            entries.add(OverviewEntry.SectionHeader("Direct Messages"))
            looseDirects.forEach { entries.add(rowFor(it)) }
        }
    }
    return entries
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun NetworkOverviewScreen(
    buffers: List<ConversationBuffer>,
    networks: List<dev.brentdevs.yardhal.coordinator.UiNetwork>,
    mutedKeys: Set<String>,
    orderState: dev.brentdevs.yardhal.core.data.ChannelOrderState,
    onSelect: (String) -> Unit,
    onSelectServer: (String) -> Unit,
    onAddNetwork: () -> Unit,
    onJoinChannel: (String?) -> Unit,
    onRemoveNetwork: (String) -> Unit,
    onBrowseChannels: (String) -> Unit,
    channelList: List<dev.brentdevs.yardhal.coordinator.LiveCoordinator.ChannelListEntry>,
    onJoinFromList: (String, String) -> Boolean,
    rawLogVersion: Int,
    rawLogProvider: (String) -> List<dev.brentdevs.yardhal.coordinator.LiveCoordinator.RawFrame>,
    showBouncerButton: Boolean = false,
    onOpenBouncer: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onMarkRead: (String) -> Unit = {},
    onToggleMute: (String) -> Unit = {},
    onLeave: (String) -> Unit = {},
    onTogglePin: (String) -> Unit = {},
    onRetryJoin: (String) -> Unit = {},
    onMoveToGroup: (String, String?) -> Unit = { _, _ -> },
    onCreateGroup: (String, (String) -> Unit) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    var browseVisible by remember { mutableStateOf(false) }
    var browseJoinFailed by remember { mutableStateOf(false) }
    var debugVisible by remember { mutableStateOf(false) }
    var browseNetworkId by remember { mutableStateOf<String?>(null) }
    var browseQuery by remember { mutableStateOf("") }
    var debugNetworkId by remember { mutableStateOf<String?>(null) }
    var networkMenuFor by remember { mutableStateOf<String?>(null) }
    var appMenuVisible by remember { mutableStateOf(false) }
    var rowMenuFor by remember { mutableStateOf<String?>(null) }
    var pendingLeave by remember { mutableStateOf<ConversationBuffer?>(null) }
    var moveTarget by remember { mutableStateOf<String?>(null) }
    var newGroupName by remember { mutableStateOf("") }

    val entries = remember(networks, buffers, mutedKeys, orderState) {
        buildOverviewEntries(networks, buffers, mutedKeys, orderState)
    }

    if (debugVisible) {
        val frames = remember(rawLogVersion, debugNetworkId) { debugNetworkId?.let(rawLogProvider).orEmpty() }
        ModalBottomSheet(onDismissRequest = { debugVisible = false }) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Text(
                    "${networks.firstOrNull { it.id == debugNetworkId }?.name.orEmpty()} traffic · ${frames.size}",
                    style = MaterialTheme.typography.titleLarge,
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(frames.size) { index ->
                        val frame = frames[frames.size - 1 - index]
                        Text(
                            text = (if (frame.outbound) "→ " else "← ") + frame.line.take(160),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (browseVisible) {
        val browseReady = networks.any { it.id == browseNetworkId && it.status == ConnectionStatus.REGISTERED }
        val filtered = channelList.filter { entry ->
            entry.name.contains(browseQuery, ignoreCase = true) || entry.topic.contains(browseQuery, ignoreCase = true)
        }
        ModalBottomSheet(onDismissRequest = { browseVisible = false }) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Text(
                    "${networks.firstOrNull { it.id == browseNetworkId }?.name.orEmpty()} channels · ${channelList.size}",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (!browseReady || browseJoinFailed) {
                    Text(
                        if (browseJoinFailed) "Could not send Join. Try again when connected."
                        else "Connect to this network to join a channel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = browseQuery,
                    onValueChange = { browseQuery = it },
                    label = { Text("Filter channels") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text(
                        if (channelList.isEmpty()) "Waiting for the channel list…" else "No channels match.",
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filtered.size) { index ->
                            val entry = filtered[index]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
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
                                TextButton(
                                    enabled = browseReady,
                                    onClick = {
                                        browseJoinFailed = browseNetworkId?.let { onJoinFromList(it, entry.name) } != true
                                    },
                                ) { Text("Join") }
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Conversations") },
                actions = {
                    if (networks.isNotEmpty() || showBouncerButton) {
                        Box {
                            IconButton(onClick = { appMenuVisible = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "App options")
                            }
                            DropdownMenu(expanded = appMenuVisible, onDismissRequest = { appMenuVisible = false }) {
                                DropdownMenuItem(text = { Text("Add network") }, onClick = {
                                    appMenuVisible = false
                                    onAddNetwork()
                                })
                                if (showBouncerButton) {
                                    DropdownMenuItem(text = { Text("Bouncer networks") }, onClick = {
                                        appMenuVisible = false
                                        onOpenBouncer()
                                    })
                                }
                                DropdownMenuItem(text = { Text("Chat appearance") }, onClick = {
                                    appMenuVisible = false
                                    onOpenAppearance()
                                })
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (networks.isEmpty()) onAddNetwork()
                    else onJoinChannel(networks.singleOrNull()?.id)
                },
            ) {
                Text(if (networks.isEmpty()) "+ Network" else "+ Channel")
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
                    is OverviewEntry.SectionHeader -> "section-${entry.label}-$index"
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
                                .defaultMinSize(minHeight = 56.dp)
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
                            if (network.status != ConnectionStatus.REGISTERED) {
                                Text(
                                    if (network.status == ConnectionStatus.CONNECTING) "Connecting" else "Offline",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box {
                                IconButton(onClick = { networkMenuFor = network.id }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "${network.name} options")
                                }
                                DropdownMenu(
                                    expanded = networkMenuFor == network.id,
                                    onDismissRequest = { networkMenuFor = null },
                                ) {
                                    DropdownMenuItem(text = { Text("Join channel") }, onClick = {
                                        networkMenuFor = null
                                        onJoinChannel(network.id)
                                    })
                                    DropdownMenuItem(text = { Text("Browse channels") }, onClick = {
                                        networkMenuFor = null
                                        browseNetworkId = network.id
                                        browseJoinFailed = false
                                        browseVisible = true
                                        onBrowseChannels(network.id)
                                    })
                                    DropdownMenuItem(text = { Text("Traffic console") }, onClick = {
                                        networkMenuFor = null
                                        debugNetworkId = network.id
                                        debugVisible = true
                                    })
                                    DropdownMenuItem(text = { Text("Remove network") }, onClick = {
                                        networkMenuFor = null
                                        pendingRemoval = network.id
                                    })
                                }
                            }
                        }
                    }
                    is OverviewEntry.SectionHeader -> {
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 42.dp, top = 10.dp, bottom = 2.dp),
                        )
                    }
                    is OverviewEntry.BufferRow -> {
                        val buffer = entry.buffer
                        val last = buffer.messages.lastOrNull()
                        val isChannel = buffer.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL
                        val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                when (value) {
                                    androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> onTogglePin(buffer.key)
                                    androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> {
                                        pendingLeave = buffer
                                    }
                                    else -> Unit
                                }
                                false
                            },
                        )
                        androidx.compose.material3.SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = true,
                            enableDismissFromEndToStart = true,
                            backgroundContent = {
                                if (dismissState.dismissDirection != androidx.compose.material3.SwipeToDismissBoxValue.Settled) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 44.dp, vertical = 4.dp),
                                        contentAlignment = if (dismissState.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) {
                                            Alignment.CenterEnd
                                        } else {
                                            Alignment.CenterStart
                                        },
                                    ) {
                                        if (dismissState.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) {
                                            Text("Leave", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                        } else {
                                            Text(
                                                text = if (entry.pinned) "Unpin" else "Pin",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                    }
                                }
                            },
                        ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickableCompat(
                                    onClick = { onSelect(buffer.key) },
                                    onLongClick = { rowMenuFor = buffer.key },
                                )
                                .defaultMinSize(minHeight = 56.dp)
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
                                    if (buffer.joinState == dev.brentdevs.yardhal.coordinator.JoinState.FAILED) {
                                        Text(
                                            text = "  ! failed",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    } else if (buffer.joinState == dev.brentdevs.yardhal.coordinator.JoinState.JOINING) {
                                        Text(
                                            text = "  joining…",
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
                                Badge { Text("New") }
                            }
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
                    text = { Text(if (buffer.pinned) "Unpin" else "Pin") },
                    onClick = { onTogglePin(buffer.buffer.key); rowMenuFor = null },
                )
                DropdownMenuItem(
                    text = { Text(if (buffer.muted) "Unmute" else "Mute") },
                    onClick = { onToggleMute(buffer.buffer.key); rowMenuFor = null },
                )
                DropdownMenuItem(
                    text = { Text("Move to group…") },
                    onClick = { moveTarget = buffer.buffer.key; rowMenuFor = null },
                )
                if (buffer.buffer.joinState == dev.brentdevs.yardhal.coordinator.JoinState.FAILED) {
                    DropdownMenuItem(
                        text = { Text("Retry join") },
                        onClick = { onRetryJoin(buffer.buffer.key); rowMenuFor = null },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Leave") },
                    onClick = { pendingLeave = buffer.buffer; rowMenuFor = null },
                )
            }
        }
    }

    if (moveTarget != null) {
        AlertDialog(
            onDismissRequest = { moveTarget = null; newGroupName = "" },
            title = { Text("Move to group") },
            text = {
                val key = moveTarget ?: return@AlertDialog
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (group in orderState.groups) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onMoveToGroup(key, group.id); moveTarget = null },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(group.name, modifier = Modifier.weight(1f))
                            if (orderState.groupOf(key)?.id == group.id) {
                                Text("current", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (orderState.groupOf(key) != null) {
                        TextButton(onClick = {
                            onMoveToGroup(key, null)
                            moveTarget = null
                        }) { Text("Remove from group") }
                    }
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text("New group name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newGroupName.isNotBlank(),
                    onClick = {
                        val key = moveTarget ?: return@TextButton
                        val name = newGroupName.trim()
                        onCreateGroup(name) { groupId ->
                            onMoveToGroup(key, groupId)
                        }
                        moveTarget = null
                        newGroupName = ""
                    },
                ) { Text("Create & move") }
            },
            dismissButton = {
                TextButton(onClick = { moveTarget = null; newGroupName = "" }) { Text("Cancel") }
            },
        )
    }

    if (pendingLeave != null) {
        val buffer = pendingLeave ?: return
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
