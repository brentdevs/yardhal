package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.core.data.CatchUpActivity
import dev.brentdevs.yardhal.core.data.CatchUpController
import dev.brentdevs.yardhal.core.data.CatchUpFilter
import dev.brentdevs.yardhal.core.data.CatchUpReason
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val CATCH_UP_TIME = DateTimeFormatter.ofPattern("MMM d · HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun CatchUpSheet(
    controller: CatchUpController,
    onDismiss: () -> Unit,
    networkName: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var linksPane by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
            Text("Catch Up", style = MaterialTheme.typography.headlineSmall)
            Text("Retained message activity, without AI", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !linksPane, onClick = { linksPane = false }, label = { Text("Activity") })
                FilterChip(selected = linksPane, onClick = { linksPane = true }, label = { Text("Links") })
                TextButton(onClick = { scope.launch { controller.restoreDismissed() } }, enabled = !state.loading) {
                    Text("Restore dismissed")
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CatchUpFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { controller.setFilter(filter) },
                        label = { Text(filter.label()) },
                    )
                }
            }
            Text(state.coverage.label, style = MaterialTheme.typography.bodySmall)
            val oldest = state.coverage.queriedOldestTimestampMs
            val newest = state.coverage.queriedNewestTimestampMs
            if (oldest != null && newest != null) {
                Text(
                    "Queried message times: ${catchUpTime(oldest)} – ${catchUpTime(newest)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f)) {
                if (linksPane) {
                    if (state.links.isEmpty() && !state.loading) item { Text("No links in the queried messages with this filter.", Modifier.padding(vertical = 16.dp)) }
                    items(state.links, key = { it.canonicalUrl }) { link ->
                        TextButton(onClick = {
                            scope.launch {
                                controller.openLink(link.anchor, link.canonicalUrl) { url ->
                                    try {
                                        uriHandler.openUri(url)
                                        linkError = null
                                    } catch (_: IllegalArgumentException) {
                                        linkError = "No application can open this link."
                                    }
                                }
                            }
                        }) { Text(link.canonicalUrl) }
                        Text("${link.occurrences.size} retained source message(s)", style = MaterialTheme.typography.labelSmall)
                        link.occurrences.forEach { activity ->
                            CatchUpActivityRow(activity, controller, onDismiss, networkName)
                        }
                        HorizontalDivider()
                    }
                } else {
                    if (state.groups.isEmpty() && !state.loading) item { Text("No activity in the queried messages with this filter.", Modifier.padding(vertical = 16.dp)) }
                    state.groups.forEach { group ->
                        item(key = "group:${group.conversation.storageKey}") {
                            Text(
                                "${group.conversation.rawTarget} · ${networkName(group.conversation.networkId)}",
                                Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        items(group.activities, key = { it.anchor.key }) { activity ->
                            CatchUpActivityRow(activity, controller, onDismiss, networkName)
                            HorizontalDivider()
                        }
                    }
                }
                item {
                    Row {
                        TextButton(onClick = { scope.launch { controller.reload() } }, enabled = !state.loading) { Text("Refresh") }
                        if (state.coverage.hasMore && !state.coverage.limitReached) {
                            TextButton(onClick = { scope.launch { controller.loadMore() } }, enabled = !state.loading) { Text("Query older messages") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatchUpActivityRow(activity: CatchUpActivity, controller: CatchUpController, onDismiss: () -> Unit, networkName: (String) -> String) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("${activity.reason.label()} · ${activity.message.senderNick}", style = MaterialTheme.typography.titleSmall)
        Text(
            "${activity.message.conversation.rawTarget} · ${networkName(activity.message.networkId)} · ${catchUpTime(activity.message.timestampMs)}",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(activity.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
        if (activity.reactions.isNotEmpty()) {
            Text(
                activity.reactions.joinToString(" · ") { "${it.sender}: ${it.emoji}" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row {
            TextButton(onClick = { scope.launch { if (controller.jump(activity.anchor)) onDismiss() } }) { Text("Jump to message") }
            TextButton(onClick = { scope.launch { controller.markRead(activity.anchor) } }) { Text("Read through message") }
            TextButton(onClick = { scope.launch { controller.dismiss(activity.anchor) } }) { Text("Dismiss") }
        }
    }
}

private fun CatchUpFilter.label(): String = when (this) {
    CatchUpFilter.ALL -> "All"
    CatchUpFilter.UNREAD -> "Unread"
    CatchUpFilter.MENTIONS -> "Mentions"
    CatchUpFilter.DIRECT_MESSAGES -> "DMs"
    CatchUpFilter.REPLIES -> "Replies to me"
    CatchUpFilter.REACTIONS -> "Reactions to mine"
}

private fun CatchUpReason.label(): String = when (this) {
    CatchUpReason.MENTION -> "Mention"
    CatchUpReason.REPLY -> "Reply to you"
    CatchUpReason.DIRECT_MESSAGE -> "Direct message"
    CatchUpReason.REACTION -> "Reaction to your message"
    CatchUpReason.UNREAD -> "Unread message"
    CatchUpReason.MESSAGE -> "Message"
}

private fun catchUpTime(timestampMs: Long): String = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).format(CATCH_UP_TIME)
