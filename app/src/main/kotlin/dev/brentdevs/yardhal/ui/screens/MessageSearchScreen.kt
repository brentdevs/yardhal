package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.coordinator.UiNetwork
import dev.brentdevs.yardhal.core.data.FtsHit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SEARCH_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d · HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun MessageSearchScreen(
    query: String,
    results: List<FtsHit>,
    searching: Boolean,
    error: Boolean,
    networks: List<UiNetwork>,
    buffers: List<ConversationBuffer>,
    networkScope: String?,
    conversationScope: String?,
    onQueryChange: (String) -> Unit,
    onNetworkScopeChange: (String?) -> Unit,
    onConversationScopeChange: (String?) -> Unit,
    onRetry: () -> Unit,
    onOpenHit: (FtsHit) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var conversationsExpanded by remember { mutableStateOf(false) }
    val matchingBuffers = buffers.filter { networkScope == null || it.ref.networkId == networkScope }
        .distinctBy { it.ref.storageKey }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Search messages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to conversation")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text("Words to find") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = networkScope == null,
                    onClick = { onNetworkScopeChange(null) },
                    label = { Text("All networks") },
                )
                networks.forEach { network ->
                    FilterChip(
                        selected = networkScope == network.id,
                        onClick = { onNetworkScopeChange(network.id) },
                        label = { Text(network.name) },
                    )
                }
            }
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { conversationsExpanded = true }) {
                    Text(
                        conversationScope?.let { scope ->
                            matchingBuffers.firstOrNull { it.ref.normalizedTarget == scope }?.displayName
                        } ?: "All conversations",
                    )
                }
                DropdownMenu(
                    expanded = conversationsExpanded,
                    onDismissRequest = { conversationsExpanded = false },
                ) {
                    DropdownMenuItem(text = { Text("All conversations") }, onClick = {
                        onConversationScopeChange(null)
                        conversationsExpanded = false
                    })
                    matchingBuffers.forEach { buffer ->
                        DropdownMenuItem(text = { Text(buffer.displayName) }, onClick = {
                            onConversationScopeChange(buffer.ref.normalizedTarget)
                            conversationsExpanded = false
                        })
                    }
                }
            }
            if (searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            when {
                query.length < 2 -> Text(
                    "Enter at least two characters.",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error -> Column(modifier = Modifier.padding(20.dp)) {
                    Text("Search is unavailable right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
                results.isEmpty() && !searching -> Text(
                    "No matches in this scope.",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn {
                    items(results, key = { it.rowId }) { hit ->
                        val networkName = networks.firstOrNull { it.id == hit.networkId }?.name ?: hit.networkId
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenHit(hit) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text("${hit.sender} · ${hit.conversation}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "$networkName · ${Instant.ofEpochMilli(hit.timestampMs).atZone(ZoneId.systemDefault()).format(SEARCH_TIME_FORMAT)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(hit.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                        }
                    }
                }
            }
        }
    }
}
