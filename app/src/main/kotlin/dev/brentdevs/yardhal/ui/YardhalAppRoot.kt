package dev.brentdevs.yardhal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.ui.screens.AddNetworkSheet
import dev.brentdevs.yardhal.ui.screens.ConversationScreen
import dev.brentdevs.yardhal.ui.screens.NetworkDraft
import dev.brentdevs.yardhal.ui.screens.NetworkOverviewScreen
import dev.brentdevs.yardhal.ui.screens.NetworkPresetUi
import dev.brentdevs.yardhal.ui.screens.WelcomeScreen
import dev.brentdevs.yardhal.ui.theme.YardhalTheme

private sealed interface AppDestination {
    public data object Overview : AppDestination
    public data class Conversation(public val storageKey: String) : AppDestination
    public data object AddNetwork : AppDestination
}

@Composable
public fun YardhalAppRoot(
    coordinator: LiveCoordinator,
    presets: List<NetworkPresetUi>,
    onNetworkSaved: (NetworkDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val networks by coordinator.networks.collectAsStateWithLifecycle()
    val buffers by coordinator.buffers.collectAsStateWithLifecycle()

    var destination by remember { mutableStateOf<AppDestination>(AppDestination.Overview) }
    var joinDialogVisible by remember { mutableStateOf(false) }
    var pendingJoinNetworkId by remember { mutableStateOf<String?>(null) }
    var joinDraft by remember { mutableStateOf("") }

    fun activeConversation(): Pair<String?, dev.brentdevs.yardhal.coordinator.ConversationBuffer?> {
        val current = destination as? AppDestination.Conversation ?: return null to null
        return current.storageKey to buffers[current.storageKey]
    }

    Surface(modifier = modifier.fillMaxSize()) {
        when (val dest = destination) {
            is AppDestination.AddNetwork -> AddNetworkSheet(
                presets = presets,
                onSave = {
                    onNetworkSaved(it)
                    destination = AppDestination.Overview
                },
                onDismiss = { destination = AppDestination.Overview },
            )

            is AppDestination.Conversation -> {
                val key = dest.storageKey
                val buffer = buffers[key]
                val networkId = key.substringBefore("|")
                val network = networks.firstOrNull { it.id == networkId }
                if (buffer == null || network == null) {
                    destination = AppDestination.Overview
                } else {
                    BackHandler { destination = AppDestination.Overview }
                    ConversationScreen(
                        buffer = buffer,
                        networkName = network.name,
                        connected = network.status == ConnectionStatus.REGISTERED,
                        onSend = { text ->
                            coordinator.sendText(networkId, key, text)
                            coordinator.sendTyping(networkId, key)
                        },
                        onOpenJoin = {
                            pendingJoinNetworkId = networkId
                            joinDialogVisible = true
                        },
                        onLoadHistory = { coordinator.loadPersistedHistory(key) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            AppDestination.Overview -> {
                val conversationBuffers = buffers.values
                    .filter { it.ref.kind != dev.brentdevs.yardhal.core.data.ConversationKind.SERVER }
                    .sortedBy { it.displayName.lowercase() }
                if (networks.isEmpty()) {
                    WelcomeScreen(onAddNetwork = { destination = AppDestination.AddNetwork })
                } else {
                    NetworkOverviewScreen(
                        buffers = conversationBuffers,
                        networks = networks,
                        onSelect = { key ->
                            coordinator.markRead(key)
                            destination = AppDestination.Conversation(key)
                        },
                        onAddNetwork = { destination = AppDestination.AddNetwork },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (joinDialogVisible) {
        AlertDialog(
            onDismissRequest = { joinDialogVisible = false },
            title = { Text("Join channel") },
            text = {
                OutlinedTextField(
                    value = joinDraft,
                    onValueChange = { joinDraft = it },
                    placeholder = { Text("#channel") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = joinDraft.startsWith("#") && joinDraft.length > 1,
                    onClick = {
                        val networkId = pendingJoinNetworkId
                        val currentKey = (destination as? AppDestination.Conversation)?.storageKey
                            ?: "${networkId}|*server*"
                        if (networkId != null) {
                            coordinator.sendText(networkId, currentKey, "/join $joinDraft")
                        }
                        joinDraft = ""
                        joinDialogVisible = false
                    },
                ) { Text("Join") }
            },
            dismissButton = {
                TextButton(onClick = { joinDialogVisible = false }) { Text("Cancel") }
            },
        )
    }
}
