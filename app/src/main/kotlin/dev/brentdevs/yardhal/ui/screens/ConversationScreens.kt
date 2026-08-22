package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.ui.components.MessageRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ConversationScreen(
    buffer: ConversationBuffer,
    networkName: String,
    connected: Boolean,
    onSend: (String) -> Unit,
    onOpenJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    androidx.compose.material3.TextButton(onClick = onOpenJoin) { Text("Join") }
                },
            )
        },
        bottomBar = { ComposerBar(enabled = connected, onSend = onSend) },
    ) { padding ->
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
            ) {
                items(buffer.messages.asReversed(), key = { it.localId }) { message: ChatMessage ->
                    MessageRow(message = message)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun NetworkOverviewScreen(
    buffers: List<ConversationBuffer>,
    networks: List<dev.brentdevs.yardhal.coordinator.UiNetwork>,
    onSelect: (String) -> Unit,
    onAddNetwork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Yardhal") }) },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(onClick = onAddNetwork) {
                Text("+ Network")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(networks, key = { it.id }) { network ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
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
                    }
                }
            }
            items(buffers, key = { it.key }) { buffer ->
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
                        val last: ChatMessage? = buffer.messages.lastOrNull()
                        if (last != null && last.sentByUs == false && buffer.hasUnread) {
                            androidx.compose.material3.Badge { Text("•") }
                        }
                    }
                }
            }
        }
    }
}
