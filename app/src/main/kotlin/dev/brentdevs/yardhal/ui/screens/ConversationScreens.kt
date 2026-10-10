package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.JoinState
import dev.brentdevs.yardhal.coordinator.HistoryGap
import dev.brentdevs.yardhal.coordinator.HistoryLoadState
import dev.brentdevs.yardhal.coordinator.HistoryLoadStatus
import dev.brentdevs.yardhal.coordinator.NetworkProfiles
import dev.brentdevs.yardhal.core.data.ChatAppearancePreferences
import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.ui.components.DayPill
import dev.brentdevs.yardhal.ui.metadataFreshnessLabel
import dev.brentdevs.yardhal.ui.components.MessageRow
import dev.brentdevs.yardhal.ui.components.NetworkBadge
import dev.brentdevs.yardhal.ui.components.NewMessagesDivider
import dev.brentdevs.yardhal.ui.components.NickAvatar
import dev.brentdevs.yardhal.ui.components.StatusDot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal sealed interface TranscriptEntry {
    public val key: String

    public data class DayHeader(public val date: LocalDate) : TranscriptEntry {
        override val key: String get() = "day-$date"
    }
    public data class UnreadDivider(public val timestampMs: Long) : TranscriptEntry {
        override val key: String get() = "unread-$timestampMs"
    }
    public data class CollapsedEvents(public val events: List<ChatMessage>, public val id: String) : TranscriptEntry {
        override val key: String get() = id
    }
    public data class Message(public val value: ChatMessage, public val groupedWithPrevious: Boolean) : TranscriptEntry {
        override val key: String get() = "msg-${value.localId}"
    }
    public data class Gap(public val value: HistoryGap) : TranscriptEntry {
        override val key: String get() = "gap-${value.id}"
    }
}

public enum class MemberAction { MESSAGE, WHOIS, KICK, BAN, BAN_ACCOUNT, IGNORE }

internal fun buildTranscript(
    buffer: ConversationBuffer,
    zone: ZoneId = ZoneId.systemDefault(),
    messages: List<ChatMessage> = buffer.messages,
): List<TranscriptEntry> {
    val ordered = messages.asReversed()
    val gapsAtBoundary = buffer.history.gaps.groupBy { gap ->
        val fromIndex = gap.from.msgid?.let { msgid -> ordered.indexOfFirst { it.msgid == msgid } }
            ?.takeIf { it >= 0 }
        val toIndex = gap.to.msgid?.let { msgid -> ordered.indexOfFirst { it.msgid == msgid } }
            ?.takeIf { it >= 0 }
        fromIndex ?: toIndex?.plus(1)
            ?: ordered.indexOfFirst { it.timestampMs <= gap.from.timestampMs }.takeIf { it >= 0 }
            ?: ordered.size
    }
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
        senderOf = { index ->
            val message = ordered[index]
            message.relayedSender?.let { "${message.sender}\u0000${message.relaySource}\u0000$it" } ?: message.sender
        },
    )
    val entries = ArrayList<TranscriptEntry>(ordered.size + 4)
    val unreadFrom = buffer.unreadFromTimestampMs
    var lastDate: LocalDate? = null
    var previousTimestamp = Long.MAX_VALUE
    var renderedUnreadDivider = false

    val pendingEvents = ArrayList<Pair<Int, ChatMessage>>()
    fun flushEvents() {
        if (pendingEvents.isEmpty()) return
        if (pendingEvents.size == 1) {
            val (origIndex, single) = pendingEvents[0]
            val isGrouped = grouped[origIndex].groupedWithPrevious
            entries.add(TranscriptEntry.Message(single, isGrouped))
        } else {
            val eventsInChronologicalOrder = pendingEvents.map { it.second }.reversed()
            entries.add(
                TranscriptEntry.CollapsedEvents(eventsInChronologicalOrder, "collapsed-${pendingEvents.minOf { it.second.localId }}"),
            )
        }
        pendingEvents.clear()
    }

    for (index in ordered.indices) {
        val message = ordered[index]
        val gaps = gapsAtBoundary[index].orEmpty()
        if (gaps.isNotEmpty()) {
            flushEvents()
            gaps.forEach { entries.add(TranscriptEntry.Gap(it)) }
        }
        val timestamp = message.timestampMs
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        if (lastDate != null && date != lastDate) {
            flushEvents()
            entries.add(TranscriptEntry.DayHeader(lastDate))
        }
        lastDate = date
        if (unreadFrom != null && !renderedUnreadDivider && timestamp < unreadFrom && previousTimestamp >= unreadFrom) {
            flushEvents()
            entries.add(TranscriptEntry.UnreadDivider(unreadFrom))
            renderedUnreadDivider = true
        }

        val isEvent = message.kind == dev.brentdevs.yardhal.core.data.MessageKind.JOIN ||
            message.kind == dev.brentdevs.yardhal.core.data.MessageKind.PART
        if (isEvent) {
            pendingEvents.add(index to message)
        } else {
            flushEvents()
            entries.add(TranscriptEntry.Message(message, gaps.isEmpty() && grouped[index].groupedWithPrevious))
        }
        previousTimestamp = timestamp
    }
    flushEvents()
    gapsAtBoundary[ordered.size].orEmpty().forEach { entries.add(TranscriptEntry.Gap(it)) }

    if (lastDate != null) {
        entries.add(TranscriptEntry.DayHeader(lastDate))
    }

    if (unreadFrom != null && !renderedUnreadDivider) {
        entries.add(TranscriptEntry.UnreadDivider(unreadFrom))
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

private class TranscriptViewport(public var pinned: Boolean) {
    public var navigationVersion: Long = 0
}

internal class TranscriptHistoryAutoLoader private constructor(
    private var wasConnected: Boolean,
    private var olderBoundary: Long?,
    private val filledGapIds: MutableSet<String>,
) {
    public constructor(connected: Boolean) : this(connected, null, mutableSetOf())

    public fun loadVisible(
        buffer: ConversationBuffer,
        visibleKeys: List<Any>,
        connected: Boolean,
        searchTargetRowId: Long?,
        loadOlder: () -> Unit,
        fillGap: (String) -> Unit,
    ) {
        if (connected && !wasConnected) {
            olderBoundary = null
            filledGapIds.clear()
        }
        wasConnected = connected
        if (buffer.history.older.canRearmAutoLoad()) olderBoundary = null
        filledGapIds.removeAll { id ->
            buffer.history.gaps.firstOrNull { it.id == id }?.state?.canRearmAutoLoad() != false
        }
        if (searchTargetRowId != null) return
        if ("history-older" in visibleKeys &&
            buffer.history.initial.status == HistoryLoadStatus.IDLE &&
            buffer.history.older.status == HistoryLoadStatus.IDLE &&
            !buffer.history.older.requiresReconnect
        ) {
            val boundary = buffer.messages.firstOrNull()?.localId
            if (boundary != null && boundary != olderBoundary) {
                olderBoundary = boundary
                loadOlder()
            }
        }
        if (!connected) return
        for (gap in buffer.history.gaps) {
            if ("gap-${gap.id}" in visibleKeys && gap.state.status == HistoryLoadStatus.IDLE &&
                !gap.state.requiresReconnect && filledGapIds.add(gap.id)
            ) {
                fillGap(gap.id)
            }
        }
    }

    private fun HistoryLoadState.canRearmAutoLoad(): Boolean =
        requiresReconnect || status == HistoryLoadStatus.FAILED || status == HistoryLoadStatus.CANCELLED

    public companion object {
        public val Saver = listSaver<TranscriptHistoryAutoLoader, String>(
            save = { listOf(it.wasConnected.toString(), it.olderBoundary?.toString().orEmpty()) + it.filledGapIds },
            restore = { TranscriptHistoryAutoLoader(it[0].toBooleanStrict(), it[1].toLongOrNull(), it.drop(2).toMutableSet()) },
        )
    }
}

@Composable
private fun HistoryStateRow(
    label: String,
    state: HistoryLoadState,
    completeText: String,
    unsupportedText: String,
    onRetry: () -> Unit,
    onLoad: (() -> Unit)? = null,
    loadText: String = "Load older history",
    gap: Boolean = false,
) {
    val text = when (state.status) {
        HistoryLoadStatus.IDLE -> if (gap) "Messages may be missing in this interval" else label
        HistoryLoadStatus.LOADING -> "$label · loading…"
        HistoryLoadStatus.EXHAUSTED -> completeText
        HistoryLoadStatus.FAILED -> "$label · failed"
        HistoryLoadStatus.CANCELLED -> "$label · cancelled"
        HistoryLoadStatus.UNSUPPORTED -> unsupportedText
        HistoryLoadStatus.WINDOW_EMPTY -> if (gap) {
            "No messages in this gap window · the gap remains"
        } else {
            "No messages in this playback window · older windows may still contain history"
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.error?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.requiresReconnect) {
            Text(
                "Reconnect to retry server history. Stored history remains available offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) { Text("Reconnect and retry history") }
        }
        when {
            state.requiresReconnect && onLoad != null && !gap -> {
                TextButton(onClick = onLoad) { Text("Load stored history") }
            }
            state.requiresReconnect -> Unit
            state.status == HistoryLoadStatus.FAILED || state.status == HistoryLoadStatus.CANCELLED -> {
                TextButton(onClick = onRetry) { Text(if (gap) "Retry gap" else "Retry history") }
            }
            state.status == HistoryLoadStatus.WINDOW_EMPTY && onLoad != null -> {
                TextButton(onClick = onLoad) { Text(if (gap) "Continue filling gap" else "Continue older") }
            }
            onLoad != null && (state.status == HistoryLoadStatus.IDLE ||
                (gap && state.status == HistoryLoadStatus.EXHAUSTED)) -> {
                TextButton(onClick = onLoad) { Text(loadText) }
            }
        }
    }
}

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🎉", "👀", "🙏")
private val HTTP_LINK = Regex("https?://\\S+")

internal fun replyPreviewText(message: ChatMessage, buffer: ConversationBuffer): String? {
    val preview = message.replyPreview
    if (preview != null) {
        if (preview.redacted) return "Deleted message"
        val text = preview.text.take(60).ifBlank { if (preview.attachmentUrl != null) "Attachment" else "Message" }
        return "${preview.sender}: $text"
    }
    return message.replyToMsgid?.let { target ->
        buffer.messages.firstOrNull { it.msgid == target }?.let {
            if (it.redacted) "Deleted message" else "${it.sender}: ${it.text.take(60)}"
        }
    }
}

internal fun conversationUnreadCount(buffer: ConversationBuffer): Int = buffer.unreadCount

internal fun conversationMentionCount(buffer: ConversationBuffer): Int = buffer.mentionCount

internal fun mentionBadgeLabel(count: Int, known: Boolean): String? = when {
    !known && count == 0 -> "@?"
    !known -> "@≥${count.coerceAtMost(99)}"
    count > 99 -> "@99+"
    count > 0 -> "@$count"
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
public fun ConversationScreen(
    buffer: ConversationBuffer,
    networkName: String,
    channels: List<String> = emptyList(),
    connected: Boolean,
    network: dev.brentdevs.yardhal.coordinator.UiNetwork?,
    onConnectNetwork: () -> Unit,
    onDisconnectNetwork: () -> Unit,
    onEditNetwork: () -> Unit,
    onTrustCertificate: (dev.brentdevs.yardhal.core.client.CertificateInspection) -> Boolean,
    onRemoveCertificateTrust: () -> Boolean,
    canSendOffline: (String) -> Boolean = { false },
    onSend: (String) -> Boolean,
    onOpenJoin: () -> Unit,
    onLoadHistory: () -> Unit,
    onLoadOlderHistory: () -> Unit,
    onRetryHistory: () -> Unit,
    onFillHistoryGap: (String) -> Unit,
    onLoadMembers: () -> Unit,
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
    onOpenChannelSettings: () -> Unit,
    onOpenCatchUp: () -> Unit,
    onOpenBugReport: () -> Unit,
    onOpenNotifications: () -> Unit,
    onMemberAction: (MemberAction, String) -> Unit = { _, _ -> },
    onOpenDm: (String) -> Unit = {},
    hasBotMode: Boolean = false,
    accountBanAvailable: Boolean = false,
    onOpenChannel: (String) -> Unit = {},
    sharedDraft: String? = null,
    sharedDraftToken: String? = null,
    onSharedConsumed: () -> Unit = {},
    profiles: NetworkProfiles = NetworkProfiles.EMPTY,
    onPickFile: () -> Unit = {},
    stagingContent: @Composable () -> Unit = {},
    relayPresentation: (ChatMessage) -> ChatMessage = { it },
    modifier: Modifier = Modifier,
) {
    var actionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var emojiTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var membersVisible by remember { mutableStateOf(false) }
    var memberTarget by remember { mutableStateOf<String?>(null) }
    var memberQuery by rememberSaveable { mutableStateOf("") }
    var metadataVisible by remember { mutableStateOf(false) }
    val staleRoster = buffer.cachedRoster || !connected
    val liveMemberActions = !staleRoster && buffer.joinState == JoinState.JOINED
    var overflowVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun openLink(url: String) {
        val uri = android.net.Uri.parse(url).normalizeScheme()
        if (uri.scheme != "http" && uri.scheme != "https") return
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val visibleMediaKeys by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.filter { it.size > 0 }.map { it.key }.toSet() }
    }
    val entries = remember(buffer, relayPresentation) {
        buildTranscript(buffer, messages = buffer.messages.map(relayPresentation))
    }
    val unreadIndex = entries.indexOfFirst { it is TranscriptEntry.UnreadDivider }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var focusedRowId by remember { mutableStateOf<Long?>(null) }
    val autoHistoryLoader = rememberSaveable(saver = TranscriptHistoryAutoLoader.Saver) {
        TranscriptHistoryAutoLoader(connected)
    }
    val currentBuffer by rememberUpdatedState(buffer)
    val currentConnected by rememberUpdatedState(connected)
    val currentSearchTarget by rememberUpdatedState(searchTargetRowId)
    val loadOlder by rememberUpdatedState(onLoadOlderHistory)
    val fillGap by rememberUpdatedState(onFillHistoryGap)
    val viewport = remember {
        TranscriptViewport(listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0)
    }
    val keepPinned = viewport.pinned && !listState.isScrollInProgress && searchTargetRowId == null
    val navigationVersion = viewport.navigationVersion

    LaunchedEffect(buffer.key, listState) {
        snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress)
        }.collect { (index, offset, scrolling) ->
            viewport.pinned = index == 0 && offset == 0
            if (scrolling) viewport.navigationVersion += 1
        }
    }
    LaunchedEffect(buffer.messages.lastOrNull()?.localId) {
        if (keepPinned && viewport.navigationVersion == navigationVersion &&
            !listState.isScrollInProgress && currentSearchTarget == null && currentBuffer.messages.lastOrNull()?.playback != true
        ) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(buffer.key, listState) {
        snapshotFlow {
            Triple(listState.layoutInfo.visibleItemsInfo.map { it.key }, currentBuffer, currentSearchTarget) to currentConnected
        }.collect { (visible, isConnected) ->
            val (visibleKeys, latest, searchTarget) = visible
            autoHistoryLoader.loadVisible(latest, visibleKeys, isConnected, searchTarget, loadOlder, fillGap)
        }
    }

    androidx.compose.runtime.LaunchedEffect(buffer.key, buffer.joinState, connected) {
        if (buffer.ref.kind == ConversationKind.CHANNEL) {
            onLoadMembers()
        }
    }

    androidx.compose.runtime.LaunchedEffect(searchTargetRowId, entries) {
        if (searchTargetRowId != null) {
            val index = entries.indexOfFirst { entry ->
                when (entry) {
                    is TranscriptEntry.Message -> entry.value.storedRowId == searchTargetRowId
                    is TranscriptEntry.CollapsedEvents -> entry.events.any { it.storedRowId == searchTargetRowId }
                    else -> false
                }
            }
            if (index >= 0) {
                viewport.navigationVersion += 1
                viewport.pinned = false
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
                            text = if (topic.isNullOrBlank()) networkName else {
                                "${metadataFreshnessLabel(buffer.cachedTopic, connected, buffer.cachedStateAtMs)} · $networkName · $topic"
                            },
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
                    if (buffer.ref.kind == ConversationKind.CHANNEL) {
                        IconButton(onClick = { membersVisible = true; onLoadMembers() }) {
                            Icon(Icons.Filled.People, contentDescription = "Members · ${buffer.members.size} · ${metadataFreshnessLabel(buffer.cachedRoster, connected, buffer.cachedStateAtMs)}")
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
                            if (buffer.ref.kind == ConversationKind.CHANNEL) {
                                DropdownMenuItem(text = { Text("Channel information") }, onClick = {
                                    overflowVisible = false
                                    metadataVisible = true
                                    onLoadMembers()
                                })
                                DropdownMenuItem(text = { Text("Channel settings") }, onClick = {
                                    overflowVisible = false
                                    onOpenChannelSettings()
                                })
                            }
                            DropdownMenuItem(text = { Text("Join channel") }, onClick = {
                                overflowVisible = false
                                onOpenJoin()
                            })
                            if (unreadIndex >= 0) {
                                DropdownMenuItem(text = { Text("Jump to unread") }, onClick = {
                                    overflowVisible = false
                                    viewport.navigationVersion += 1
                                    viewport.pinned = false
                                    coroutineScope.launch { listState.animateScrollToItem(unreadIndex) }
                                })
                            }
                            DropdownMenuItem(text = { Text("Chat appearance") }, onClick = {
                                overflowVisible = false
                                onOpenAppearance()
                            })
                            DropdownMenuItem(text = { Text("Catch Up") }, onClick = {
                                overflowVisible = false
                                onOpenCatchUp()
                            })
                            DropdownMenuItem(text = { Text("Notifications") }, onClick = {
                                overflowVisible = false
                                onOpenNotifications()
                            })
                            DropdownMenuItem(text = { Text("Bug report") }, onClick = {
                                overflowVisible = false
                                onOpenBugReport()
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
                            text = if (reply.redacted) "↩ Deleted message" else
                                "↩ ${if (reply.msgid != null && network?.taggedRepliesAvailable == true) "Replying" else "Quoting"} ${reply.sender}: ${reply.text.take(48)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        TextButton(onClick = { onSetReplyDraft(null) }) { Text("Cancel") }
                    }
                }
                stagingContent()
                ComposerBar(
                    enabled = connected,
                    canSendOffline = canSendOffline,
                    members = nicknameSuggestions(buffer, appearance.nicknameSuggestionOrder),
                    channels = channels,
                    showAvatars = appearance.showAvatars,
                    onAttach = onPickFile,
                    initialDraft = sharedDraft,
                    initialDraftToken = sharedDraftToken,
                    onInitialDraftCaptured = onSharedConsumed,
                    onSend = onSend,
                )
            }
        },
    ) { padding ->
        LaunchedEffectOnce(key = buffer.key, effect = onLoadHistory)
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                network?.let {
                    dev.brentdevs.yardhal.ui.components.NetworkConnectionPanel(
                        network = it,
                        onConnect = onConnectNetwork,
                        onDisconnect = onDisconnectNetwork,
                        onEdit = onEditNetwork,
                        onTrustCertificate = onTrustCertificate,
                        onRemoveCertificateTrust = onRemoveCertificateTrust,
                    )
                }
                if (!buffer.mentionCountKnown) {
                    Text(
                        "Mention count is incomplete for older stored messages. Shown mentions are a known lower bound.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                val statusText = when {
                    buffer.joinState == JoinState.FAILED -> "Could not join ${buffer.displayName}"
                    !connected -> "Browsing stored history; server history resumes after reconnection"
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
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                ) {
                    if (buffer.history.initial.status != HistoryLoadStatus.IDLE) {
                        HistoryStateRow(
                            label = "Recent history",
                            state = buffer.history.initial,
                            completeText = if (buffer.messages.isEmpty()) "No recent history found" else "Recent history loaded",
                            unsupportedText = "Server history unavailable · stored history remains available",
                            onRetry = onRetryHistory,
                        )
                    }
                    if (buffer.history.catchUp.status != HistoryLoadStatus.IDLE) {
                        HistoryStateRow(
                            label = "Reconnect catch-up",
                            state = buffer.history.catchUp,
                            completeText = "Reconnect catch-up complete",
                            unsupportedText = "Server catch-up unavailable · browsing stored history",
                            onRetry = onRetryHistory,
                        )
                    }
                    if (buffer.history.discovery.status != HistoryLoadStatus.IDLE) {
                        HistoryStateRow(
                            label = "Offline conversation discovery",
                            state = buffer.history.discovery,
                            completeText = "Offline conversation discovery complete for the bounded window",
                            unsupportedText = "Offline conversation discovery is not supported by this server",
                            onRetry = onRetryHistory,
                        )
                    }
                    if (buffer.history.discoveryTruncated) {
                        Text(
                            "Discovery was limited · additional offline conversations may be missing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    if (buffer.messages.isEmpty()) {
                        item(key = "history-empty") {
                            Text(
                                text = if (connected) "No messages loaded yet." else "No stored messages loaded yet.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                    items(entries.size, key = { index -> entries[index].key }) { index ->
                        when (val entry = entries[index]) {
                            is TranscriptEntry.DayHeader -> DayPill(formatDayLabel(entry.date))
                            is TranscriptEntry.UnreadDivider -> NewMessagesDivider("New messages")
                            is TranscriptEntry.Gap -> HistoryStateRow(
                                label = "Missing messages",
                                state = entry.value.state,
                                completeText = "History gap remains · the response did not fill the entire interval",
                                unsupportedText = "This server cannot fill this history gap",
                                onRetry = if (entry.value.state.requiresReconnect) onRetryHistory else {
                                    { onFillHistoryGap(entry.value.id) }
                                },
                                onLoad = { onFillHistoryGap(entry.value.id) },
                                loadText = "Fill remaining gap",
                                gap = true,
                            )
                            is TranscriptEntry.CollapsedEvents -> CollapsedEventsRow(
                                entry = entry,
                                appearance = appearance,
                                focusedRowId = focusedRowId,
                            )
                            is TranscriptEntry.Message -> {
                                val message = entry.value
                                MessageRow(
                                    message = message,
                                    mediaIdentity = dev.brentdevs.yardhal.coordinator.messageMediaIdentity(buffer, message),
                                    mediaVisible = entry.key in visibleMediaKeys,
                                    groupedWithPrevious = entry.groupedWithPrevious,
                                    focused = message.storedRowId != null && message.storedRowId == focusedRowId,
                                    appearance = appearance,
                                    reactions = buffer.reactions[message.msgid].orEmpty()
                                        .filterValues { it.isNotEmpty() },
                                    quotedText = replyPreviewText(message, buffer),
                                    onLongPress = {
                                        actionTarget = message
                                    },
                                    onToggleReaction = { emoji ->
                                        if (connected && network?.reactionsAvailable == true) message.msgid?.let { msgid -> onReact(msgid, emoji) }
                                    },
                                    onOpenAttachment = ::openLink,
                                    onOpenChannel = onOpenChannel,
                                    onOpenNick = onOpenDm,
                                    onOpenUrl = ::openLink,
                                    profile = message.sender.takeIf { it.isNotEmpty() && message.relayedSender == null }?.let(profiles::forNick),
                                    accessibilityActions = dev.brentdevs.yardhal.ui.components.MessageAccessibilityActions(
                                        onReply = if (message.redacted) null else ({ onSetReplyDraft(message) }),
                                        onCopy = {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Message", message.text))
                                        },
                                        onChooseReaction = if (!message.redacted && message.msgid != null && connected && network?.reactionsAvailable == true) ({ emojiTarget = message }) else null,
                                        onDelete = if (message.sentByUs && !message.redacted && message.msgid != null && connected && network?.messageDeletionAvailable == true) ({ onDelete(message.msgid) }) else null,
                                        canToggleReaction = !message.redacted && message.msgid != null && connected && network?.reactionsAvailable == true,
                                    ),
                                )
                            }
                        }
                    }
                    item(key = "history-older") {
                        HistoryStateRow(
                            label = "Older history",
                            state = buffer.history.older,
                            completeText = "Beginning of available history",
                            unsupportedText = "No more stored history · server history is unavailable",
                            onRetry = onRetryHistory,
                            onLoad = onLoadOlderHistory,
                        )
                    }
                }
            }
    }

    if (metadataVisible) {
        ModalBottomSheet(onDismissRequest = { metadataVisible = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(buffer.displayName, style = MaterialTheme.typography.titleLarge)
                Text("Topic · ${metadataFreshnessLabel(buffer.cachedTopic, connected, buffer.cachedStateAtMs)}", style = MaterialTheme.typography.labelMedium)
                Text(buffer.topic ?: "No observed topic")
                Text("Modes · ${metadataFreshnessLabel(buffer.cachedModes, connected, buffer.cachedStateAtMs)}", style = MaterialTheme.typography.labelMedium)
                Text(
                    buffer.channelModes.entries.sortedBy { it.key }.joinToString(" ") { (mode, parameters) ->
                        if (mode == "k") "+k (key set)" else "+$mode ${parameters.joinToString(" ")}".trim()
                    }.ifBlank { "No observed modes" },
                )
                Text("Roster · ${metadataFreshnessLabel(buffer.cachedRoster, connected, buffer.cachedStateAtMs)}", style = MaterialTheme.typography.labelMedium)
                Text("${buffer.members.size} ${if (staleRoster) "previously observed members" else "members"}")
                if (buffer.rosterTruncated) {
                    Text("Only a retained subset of the cached roster is shown. Reconnect and refresh for the full roster.", style = MaterialTheme.typography.bodySmall)
                }
                if (buffer.cachedTopic || buffer.cachedModes || staleRoster) {
                    Text("Cached information is historical. Roles, membership and modes do not establish current server permissions.", style = MaterialTheme.typography.bodySmall)
                }
                if (connected) {
                    TextButton(onClick = onLoadMembers) { Text("Refresh channel information") }
                }
            }
        }
    }

    if (actionTarget != null) {
        val target = actionTarget ?: return
        ModalBottomSheet(onDismissRequest = { actionTarget = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (appearance.showAvatars) NickAvatar(nick = target.sender, size = 36.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = target.relayedSender?.let { "$it · via ${target.relaySource}" } ?: target.sender.ifEmpty { "Message" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = target.relayedBody ?: target.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                if (target.sender.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            onMemberAction(MemberAction.WHOIS, target.sender)
                            actionTarget = null
                        },
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (connected) "WHOIS" else "Stored WHOIS")
                    }
                }
                if (target.msgid != null && !target.redacted && connected && network?.reactionsAvailable == true) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            QUICK_REACTIONS.forEach { emoji ->
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .semantics { contentDescription = "React with ${dev.brentdevs.yardhal.ui.components.emojiAccessibilityLabel(emoji)}" }
                                        .clip(CircleShape)
                                        .clickable {
                                            target.msgid?.let { msgid -> onReact(msgid, emoji) }
                                            actionTarget = null
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(emoji, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                    TextButton(onClick = {
                        emojiTarget = target
                        actionTarget = null
                    }) { Text("All emoji · search, categories & recent") }
                }
                if (target.msgid == null || !connected || network?.reactionsAvailable != true) {
                    Text("Reactions unavailable · requires a message ID and permitted server client tags",
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!target.redacted) {
                        FilledTonalButton(
                            onClick = {
                                onSetReplyDraft(target)
                                actionTarget = null
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (target.msgid != null && network?.taggedRepliesAvailable == true) "Reply" else "Quote reply", maxLines = 1)
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Message", target.text))
                            actionTarget = null
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = {
                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, target.text)
                                type = "text/plain"
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, null))
                            actionTarget = null
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share", maxLines = 1)
                    }
                }
                val firstLink = HTTP_LINK.find(target.text)?.value?.trimEnd('.', ',', ';', ':', ')', ']', '}')
                if (firstLink != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openLink(firstLink)
                                actionTarget = null
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Open link", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (target.sentByUs && !target.redacted && target.msgid != null && connected && network?.messageDeletionAvailable == true) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                target.msgid?.let(onDelete)
                                actionTarget = null
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text("Delete message", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    emojiTarget?.let { target ->
        dev.brentdevs.yardhal.ui.components.EmojiPicker(
            onDismiss = { emojiTarget = null },
            onSelect = { emoji ->
                if (connected && network?.reactionsAvailable == true) target.msgid?.let { onReact(it, emoji) }
                emojiTarget = null
            },
        )
    }

    if (membersVisible) {
        val operators = buffer.members.filter { it.isOperator }
        val voices = buffer.members.filter { it.isVoice }
        val isBot: (dev.brentdevs.yardhal.core.data.ChannelMember) -> Boolean = { member ->
            buffer.memberPresence[member.nick]?.isBot == true || (!hasBotMode && member.looksLikeBot)
        }
        val bots = buffer.members.filter { isBot(it) }
        val plain = buffer.members.filter { member -> !member.isOperator && !member.isVoice && !isBot(member) }

        val filterPred: (dev.brentdevs.yardhal.core.data.ChannelMember) -> Boolean = { m ->
            if (memberQuery.isBlank()) true
            else m.nick.contains(memberQuery, ignoreCase = true) ||
                profiles.forNick(m.nick)?.displayName?.contains(memberQuery, ignoreCase = true) == true
        }

        val filteredOps = operators.filter(filterPred)
        val filteredVoices = voices.filter(filterPred)
        val filteredBots = bots.filter(filterPred)
        val filteredPlain = plain.filter(filterPred)

        ModalBottomSheet(onDismissRequest = { membersVisible = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Members",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = "${buffer.members.size}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Text(
                    metadataFreshnessLabel(buffer.cachedRoster, connected, buffer.cachedStateAtMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (staleRoster) {
                    Text("Previously observed members and roles; not current presence or permissions.", style = MaterialTheme.typography.bodySmall)
                }
                if (buffer.rosterTruncated) {
                    Text("Cached roster truncated · showing ${buffer.members.size} retained members, not the full roster.", style = MaterialTheme.typography.bodySmall)
                }
                if (connected) {
                    TextButton(onClick = onLoadMembers) { Text("Refresh members") }
                }
                OutlinedTextField(
                    value = memberQuery,
                    onValueChange = { memberQuery = it },
                    placeholder = { Text("Filter members…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    shape = RoundedCornerShape(24.dp),
                )
            }
            LazyColumn(modifier = Modifier.padding(horizontal = 8.dp)) {
                val sections = listOf(
                    Triple("ops", "${if (staleRoster) "Previously observed operators" else "Operators"} (${filteredOps.size})", filteredOps),
                    Triple("voices", "${if (staleRoster) "Previously observed voices" else "Voices"} (${filteredVoices.size})", filteredVoices),
                    Triple("bots", "Bots (${filteredBots.size})", filteredBots),
                    Triple("users", "Users (${filteredPlain.size})", filteredPlain),
                )
                for ((id, title, section) in sections) {
                    if (section.isEmpty()) continue
                    item(key = "section-$id") {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(section.size, key = { index -> "member-$id-${section[index].nick}" }) { index ->
                        val member = section[index]
                        val presence = buffer.memberPresence[member.nick]
                        val memberProfile = profiles.forNick(member.nick)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { memberTarget = member.nick }
                                .defaultMinSize(minHeight = 48.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (staleRoster) "?" else if (presence?.away == true) "○" else "●",
                                color = if (staleRoster || presence?.away == true) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            if (appearance.showAvatars) {
                                NickAvatar(
                                    nick = member.nick,
                                    size = 28.dp,
                                    avatarUrl = memberProfile?.avatarUrl,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                            if (member.symbol != null) {
                                Text(
                                    text = member.symbol.toString(),
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                            Text(member.nick, style = MaterialTheme.typography.bodyLarge)
                            memberProfile?.displayName?.let { displayName ->
                                if (displayName != member.nick) {
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                            presence?.account?.let { account ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp),
                                ) {
                                    Text(
                                        text = if (staleRoster) "Previously: $account" else "✓ $account",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (memberTarget != null) {
        val nick = memberTarget ?: return
        val member = buffer.members.firstOrNull { it.nick == nick }
        val presence = buffer.memberPresence[nick]
        val memberProfile = profiles.forNick(nick)
        val isOp = member?.isOperator == true
        val isVoice = member?.isVoice == true
        val isBot = presence?.isBot == true || member?.looksLikeBot == true
        val account = presence?.account
        val realName = presence?.realName
        val userHost = listOfNotNull(presence?.user, presence?.host).joinToString("@").takeIf { it.isNotEmpty() }
        val away = presence?.away == true
        val awayMsg = presence?.awayMessage

        ModalBottomSheet(onDismissRequest = { memberTarget = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (appearance.showAvatars) {
                        Box {
                            NickAvatar(
                                nick = nick,
                                size = 54.dp,
                                avatarUrl = memberProfile?.avatarUrl,
                            )
                            StatusDot(
                                status = if (staleRoster) ConnectionStatus.DISCONNECTED else if (away) ConnectionStatus.CONNECTING else ConnectionStatus.REGISTERED,
                                modifier = Modifier.align(Alignment.BottomEnd),
                                size = 14.dp,
                            )
                        }
                    } else {
                        StatusDot(
                            status = if (staleRoster) ConnectionStatus.DISCONNECTED else if (away) ConnectionStatus.CONNECTING else ConnectionStatus.REGISTERED,
                            size = 14.dp,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (member?.symbol != null) {
                                Text(
                                    text = member.symbol.toString(),
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                text = nick,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            if (account != null) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        text = if (staleRoster) "Previously: $account" else "✓ $account",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        memberProfile?.displayName?.let { displayName ->
                            if (displayName != nick) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (realName != null) {
                            Text(
                                text = realName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (userHost != null) {
                            Text(
                                text = userHost,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                        }
                    }
                }
                Text(
                    metadataFreshnessLabel(buffer.cachedRoster, connected, buffer.cachedStateAtMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (staleRoster) {
                    Text("Cached roles and presence are not current server permissions.", style = MaterialTheme.typography.bodySmall)
                    if (connected) TextButton(onClick = onLoadMembers) { Text("Refresh members") }
                }

                if (isOp || isVoice || isBot || away) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isOp) {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                                Text(if (staleRoster) "Previously operator" else "Operator", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (isVoice) {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(if (staleRoster) "Previously voice" else "Voice", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (isBot) {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text("Bot", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (away) {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(if (staleRoster) "Previously away" else awayMsg?.let { "Away: $it" } ?: "Away", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onOpenDm(nick); memberTarget = null; membersVisible = false },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Mail, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Message")
                    }
                    OutlinedButton(
                        onClick = { onMemberAction(MemberAction.WHOIS, nick); memberTarget = null },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("WHOIS")
                    }
                }

                if (buffer.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL) {
                    Text(
                        if (liveMemberActions) "Channel moderation · server authorizes actions" else "Refresh live membership before moderation",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(enabled = liveMemberActions, onClick = { onMemberAction(MemberAction.KICK, nick); memberTarget = null }) {
                            Text("Kick", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(enabled = liveMemberActions, onClick = { onMemberAction(MemberAction.BAN, nick); memberTarget = null }) {
                            Text("Ban", color = MaterialTheme.colorScheme.error)
                        }
                        if (accountBanAvailable && account != null) {
                            TextButton(enabled = liveMemberActions, onClick = { onMemberAction(MemberAction.BAN_ACCOUNT, nick); memberTarget = null }) {
                                Text("Ban account", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(onClick = { onMemberAction(MemberAction.IGNORE, nick); memberTarget = null }) {
                            Text("Ignore")
                        }
                    }
                } else {
                    TextButton(
                        onClick = { onMemberAction(MemberAction.IGNORE, nick); memberTarget = null },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Ignore user")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun LaunchedEffectOnce(key: Any?, effect: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) { effect() }
}

@Composable
private fun CollapsedEventsRow(
    entry: TranscriptEntry.CollapsedEvents,
    appearance: dev.brentdevs.yardhal.core.data.ChatAppearancePreferences,
    focusedRowId: Long? = null,
) {
    val containsTarget = focusedRowId != null && entry.events.any { it.storedRowId == focusedRowId }
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    var autoExpandedTarget by rememberSaveable(entry.id) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(containsTarget) {
        if (containsTarget && !autoExpandedTarget) {
            expanded = true
            autoExpandedTarget = true
        }
    }
    val fontFamily = when (appearance.font) {
        dev.brentdevs.yardhal.core.data.MessageFont.SYSTEM -> null
        dev.brentdevs.yardhal.core.data.MessageFont.SANS -> androidx.compose.ui.text.font.FontFamily.SansSerif
        dev.brentdevs.yardhal.core.data.MessageFont.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
        dev.brentdevs.yardhal.core.data.MessageFont.MONOSPACE -> androidx.compose.ui.text.font.FontFamily.Monospace
    }
    val baseStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = MaterialTheme.typography.bodySmall.fontSize * appearance.textScale,
        fontFamily = fontFamily,
    )
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = MaterialTheme.typography.labelSmall.fontSize * appearance.textScale,
        fontFamily = fontFamily,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (appearance.compact) 1.dp else 2.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            onClick = { expanded = !expanded },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = labelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${entry.events.size} user events",
                    style = labelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 12.dp, top = 2.dp)) {
                for (event in entry.events) {
                    val isFocused = event.storedRowId != null && event.storedRowId == focusedRowId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "·",
                            style = baseStyle,
                            color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(14.dp),
                        )
                        Text(
                            text = event.text,
                            style = baseStyle,
                            color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic,
                            fontWeight = if (isFocused) FontWeight.Bold else null,
                        )
                    }
                }
            }
        }
    }
}

internal sealed interface OverviewEntry {
    public data class NetworkHeader(
        public val network: dev.brentdevs.yardhal.coordinator.UiNetwork,
        public val isCollapsed: Boolean,
        public val hasUnread: Boolean,
        public val unreadCount: Int,
        public val mentionCount: Int,
        public val mentionCountKnown: Boolean,
    ) : OverviewEntry

    public data class ServerRow(
        public val networkId: String,
        public val networkName: String,
    ) : OverviewEntry

    public data class SectionHeader(public val label: String, public val id: String = label) : OverviewEntry

    public data class BufferRow(
        public val buffer: ConversationBuffer,
        public val pinned: Boolean,
        public val muted: Boolean,
        public val inGroup: Boolean,
    ) : OverviewEntry
}


internal fun buildOverviewEntries(
    networks: List<dev.brentdevs.yardhal.coordinator.UiNetwork>,
    buffers: List<ConversationBuffer>,
    mutedKeys: Set<String>,
    order: dev.brentdevs.yardhal.core.data.ChannelOrderState,
    collapsedNetworkIds: Set<String>,
): List<OverviewEntry> {
    val entries = ArrayList<OverviewEntry>()
    val channelRanks = order.channelOrder.withIndex().associate { it.value to it.index }
    val networkRanks = order.networkOrder.withIndex().associate { it.value to it.index }
    val manualComparison = compareBy<ConversationBuffer> { channelRanks[it.key] ?: Int.MAX_VALUE }
        .thenBy { it.displayName.lowercase(java.util.Locale.ROOT) }.thenBy { it.key }
    val comparison = if (order.sortUnreadFirst) compareByDescending<ConversationBuffer> { it.hasUnread }.then(manualComparison) else manualComparison
    for (network in networks.sortedWith(compareBy<dev.brentdevs.yardhal.coordinator.UiNetwork> { networkRanks[it.id] ?: Int.MAX_VALUE }
        .thenBy { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.id })) {
        val own = buffers.filter {
            it.ref.networkId == network.id && it.ref.kind != dev.brentdevs.yardhal.core.data.ConversationKind.SERVER
        }
        val isCollapsed = network.id in collapsedNetworkIds
        var netHasUnread = false
        var netUnreadCount = 0
        var netMentionCount = 0
        var netMentionCountKnown = true
        for (buffer in own) {
            if (buffer.key in mutedKeys) continue
            netHasUnread = netHasUnread || buffer.hasUnread
            netUnreadCount += conversationUnreadCount(buffer)
            netMentionCount += conversationMentionCount(buffer)
            netMentionCountKnown = netMentionCountKnown && buffer.mentionCountKnown
        }
        entries.add(
            OverviewEntry.NetworkHeader(
                network, isCollapsed, netHasUnread, netUnreadCount,
                netMentionCount,
                netMentionCountKnown,
            ),
        )

        entries.add(OverviewEntry.ServerRow(network.id, network.name))

        if (isCollapsed) continue

        val channels = own.filter { it.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL }
        val directs = own.filter { it.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.DIRECT_MESSAGE }

        fun rowFor(buffer: ConversationBuffer): OverviewEntry.BufferRow =
            OverviewEntry.BufferRow(
                buffer = buffer,
                pinned = buffer.key in order.pinnedKeys,
                muted = buffer.key in mutedKeys,
                inGroup = order.groupOf(buffer.key) != null,
            )

        val ownByKey = own.associateBy { it.key }
        val displayedKeys = HashSet<String>()
        val pinnedBuffers = order.pinnedKeys.mapNotNull(ownByKey::get)
            .filter { displayedKeys.add(it.key) }
        if (pinnedBuffers.isNotEmpty()) {
            entries.add(OverviewEntry.SectionHeader("Pinned"))
            pinnedBuffers.forEach { entries.add(rowFor(it)) }
        }

        for (groupId in (order.groupOrder + order.groups.map { it.id }).distinct()) {
            val group = order.groups.firstOrNull { it.id == groupId } ?: continue
            val members = group.memberKeys.mapNotNull(ownByKey::get)
                .filter { displayedKeys.add(it.key) }
            if (members.isEmpty()) continue
            entries.add(OverviewEntry.SectionHeader(group.name, "group-${group.id}"))
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
    collapsedNetworkIds: Set<String>,
    onToggleNetwork: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSelectServer: (String) -> Unit,
    onAddNetwork: () -> Unit,
    onEditNetwork: (String) -> Unit,
    onConnectNetwork: (String) -> Unit,
    onDisconnectNetwork: (String) -> Unit,
    onTrustCertificate: (String, dev.brentdevs.yardhal.core.client.CertificateInspection) -> Boolean,
    onRemoveCertificateTrust: (String) -> Boolean,
    onJoinChannel: (String?) -> Unit,
    onRemoveNetwork: (String) -> Unit,
    onBrowseChannels: (String) -> Unit,
    channelList: List<dev.brentdevs.yardhal.coordinator.LiveCoordinator.ChannelListEntry>,
    onJoinFromList: (String, String) -> Boolean,
    rawLogVersion: Int,
    rawLogProvider: (String) -> List<dev.brentdevs.yardhal.coordinator.LiveCoordinator.RawFrame>,
    onReorder: (dev.brentdevs.yardhal.core.data.OrderMove) -> Boolean,
    onSetUnreadSorting: (Boolean) -> Boolean,
    appearance: ChatAppearancePreferences = ChatAppearancePreferences(),
    showBouncerButton: Boolean = false,
    onOpenBouncer: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenCatchUp: () -> Unit,
    onOpenBugReport: () -> Unit,
    onOpenNotifications: () -> Unit,
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
    var orderingVisible by remember { mutableStateOf(false) }

    val entries = remember(networks, buffers, mutedKeys, orderState, collapsedNetworkIds) {
        buildOverviewEntries(networks, buffers, mutedKeys, orderState, collapsedNetworkIds)
    }
    if (orderingVisible) {
        val expandedEntries = remember(networks, buffers, mutedKeys, orderState) {
            buildOverviewEntries(networks, buffers, mutedKeys, orderState, emptySet())
        }
        OrderingSheet(
            sections = orderingSections(expandedEntries, orderState),
            sortUnreadFirst = orderState.sortUnreadFirst,
            onReorder = onReorder,
            onSetUnreadSorting = onSetUnreadSorting,
            onDismiss = { orderingVisible = false },
        )
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
                                DropdownMenuItem(text = { Text("Reorder networks and conversations") }, onClick = {
                                    appMenuVisible = false
                                    orderingVisible = true
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
                                DropdownMenuItem(text = { Text("Catch Up") }, onClick = {
                                    appMenuVisible = false
                                    onOpenCatchUp()
                                })
                                DropdownMenuItem(text = { Text("Notifications") }, onClick = {
                                    appMenuVisible = false
                                    onOpenNotifications()
                                })
                                DropdownMenuItem(text = { Text("Bug report") }, onClick = {
                                    appMenuVisible = false
                                    onOpenBugReport()
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
                    is OverviewEntry.ServerRow -> "server-${entry.networkId}"
                    is OverviewEntry.SectionHeader -> "section-${entry.id}-$index"
                    is OverviewEntry.BufferRow -> "buf-${entry.buffer.key}"
                }
            }) { index ->
                when (val entry = entries[index]) {
                    is OverviewEntry.NetworkHeader -> {
                        val network = entry.network
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = if (entry.isCollapsed) "Expand ${network.name}" else "Collapse ${network.name}",
                                    onClick = { onToggleNetwork(network.id) },
                                )
                                .defaultMinSize(minHeight = 56.dp)
                                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NetworkBadge(network.connectionPhase, network.iconUrl)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = network.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            if (entry.isCollapsed && entry.hasUnread) {
                                val netBadgeText = overviewBadgeLabel(entry.unreadCount, entry.mentionCount, entry.mentionCountKnown, appearance.showUnreadCounts)
                                Badge(
                                    containerColor = if (entry.mentionCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    contentColor = if (entry.mentionCount > 0) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                                ) {
                                    Text(
                                        netBadgeText,
                                        modifier = if (entry.mentionCountKnown) Modifier else Modifier.semantics {
                                            stateDescription = "Mention count incomplete; ${entry.mentionCount} known mentions"
                                        },
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Icon(
                                imageVector = if (entry.isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp).padding(4.dp),
                            )
                            Box {
                                IconButton(onClick = { networkMenuFor = network.id }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "${network.name} options")
                                }
                                DropdownMenu(
                                    expanded = networkMenuFor == network.id,
                                    onDismissRequest = { networkMenuFor = null },
                                ) {
                                    DropdownMenuItem(text = { Text("Edit network") }, onClick = {
                                        networkMenuFor = null
                                        onEditNetwork(network.id)
                                    })
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
                        dev.brentdevs.yardhal.ui.components.NetworkConnectionPanel(
                            network = network,
                            onConnect = { onConnectNetwork(network.id) },
                            onDisconnect = { onDisconnectNetwork(network.id) },
                            onEdit = { onEditNetwork(network.id) },
                            onTrustCertificate = { inspection -> onTrustCertificate(network.id, inspection) },
                            onRemoveCertificateTrust = { onRemoveCertificateTrust(network.id) },
                        )
                    }
                    is OverviewEntry.ServerRow -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectServer(entry.networkId) }
                                .defaultMinSize(minHeight = 48.dp)
                                .padding(start = 24.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Terminal,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Server Console",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "Status, raw log, system notices",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    is OverviewEntry.SectionHeader -> {
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    is OverviewEntry.BufferRow -> {
                        val buffer = entry.buffer
                        val last = buffer.messages.lastOrNull()
                        val isChannel = buffer.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL
                        val unreadCount = conversationUnreadCount(buffer)
                        val mentionCount = conversationMentionCount(buffer)
                        val hasMention = mentionCount > 0
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
                                            .padding(horizontal = 24.dp, vertical = 4.dp),
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
                                    .padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isChannel) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (buffer.hasUnread) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Filled.Tag,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = if (buffer.hasUnread) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                } else if (appearance.showAvatars) {
                                    NickAvatar(
                                        nick = buffer.displayName,
                                        size = 32.dp,
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = buffer.displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (buffer.hasUnread) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
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
                                                text = when {
                                                    networks.any { it.id == buffer.ref.networkId && it.status == ConnectionStatus.REGISTERED } -> "  joining…"
                                                    buffer.cachedTopic || buffer.cachedModes || buffer.cachedRoster || buffer.cachedStateAtMs != null -> "  cached"
                                                    else -> "  not joined"
                                                },
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
                                    val badgeText = overviewBadgeLabel(unreadCount, mentionCount, buffer.mentionCountKnown, appearance.showUnreadCounts)
                                    if (hasMention) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError,
                                        ) {
                                            Text(
                                                badgeText,
                                                modifier = if (buffer.mentionCountKnown) Modifier else Modifier.semantics {
                                                    stateDescription = "Mention count incomplete; $mentionCount known mentions"
                                                },
                                            )
                                        }
                                    } else {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ) {
                                            Text(
                                                badgeText,
                                                modifier = if (buffer.mentionCountKnown) Modifier else Modifier.semantics {
                                                    stateDescription = "Mention count incomplete; $mentionCount known mentions"
                                                },
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
    }

    if (rowMenuFor != null) {
        val entry = entries.filterIsInstance<OverviewEntry.BufferRow>().firstOrNull { it.buffer.key == rowMenuFor }
        if (entry != null) {
            val buf = entry.buffer
            ModalBottomSheet(onDismissRequest = { rowMenuFor = null }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = buf.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onMarkRead(buf.key); rowMenuFor = null }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Filled.MarkChatRead, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Mark as read", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onTogglePin(buf.key); rowMenuFor = null }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Filled.PushPin, contentDescription = null)
                        Text(if (entry.pinned) "Unpin" else "Pin to top", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggleMute(buf.key); rowMenuFor = null }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Filled.NotificationsOff, contentDescription = null)
                        Text(if (entry.muted) "Unmute notifications" else "Mute notifications", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { moveTarget = buf.key; rowMenuFor = null }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null)
                        Text("Move to group…", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (buf.joinState == dev.brentdevs.yardhal.coordinator.JoinState.FAILED) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onRetryJoin(buf.key); rowMenuFor = null }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Retry join", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { pendingLeave = buf; rowMenuFor = null }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text("Leave", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
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
