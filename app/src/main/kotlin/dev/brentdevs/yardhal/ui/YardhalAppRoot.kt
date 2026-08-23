package dev.brentdevs.yardhal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.ui.screens.AddNetworkSheet
import dev.brentdevs.yardhal.ui.screens.ConversationScreen
import dev.brentdevs.yardhal.ui.screens.NetworkDraft
import dev.brentdevs.yardhal.ui.screens.NetworkOverviewScreen
import dev.brentdevs.yardhal.ui.screens.NetworkPresetUi
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
    sharedTextProvider: () -> String? = { null },
    onSharedConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val networks by coordinator.networks.collectAsStateWithLifecycle()
    val buffers by coordinator.buffers.collectAsStateWithLifecycle()
    val whoisInfo by coordinator.whois.collectAsStateWithLifecycle()
    val channelList by coordinator.channelList.collectAsStateWithLifecycle()
    val rawLogVersion by coordinator.rawLogVersion.collectAsStateWithLifecycle()

    var addNetworkVisible by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var joinDialogVisible by remember { mutableStateOf(false) }
    var joinDraft by remember { mutableStateOf("") }

    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val wide = configuration.screenWidthDp >= 600
    val sharedDraft = sharedTextProvider()
    if (sharedDraft != null && selectedKey == null) {
        val first = buffers.values
            .filter { it.ref.kind != dev.brentdevs.yardhal.core.data.ConversationKind.SERVER }
            .minByOrNull { it.displayName.lowercase() }
        selectedKey = first?.key
        onSharedConsumed()
    }

    val pickLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && selectedKey != null) {
            val key = selectedKey!!
            val networkId = key.substringBefore("|")
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = queryDisplayName(resolver, uri)
            val bytes: ByteArray? = runCatching {
                resolver.openInputStream(uri)?.use { input -> input.readBytes() }
            }.getOrNull()
            if (bytes != null) {
                coordinator.uploadAndShare(networkId, key, name, mime, bytes)
            }
        }
    }

    fun launchAttachmentPicker() {
        pickLauncher.launch(arrayOf("*/*"))
    }

    fun conversationBufferFor(key: String?): ConversationBuffer? = key?.let { buffers[it] }

    Surface(modifier = modifier.fillMaxSize()) {
        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(0.38f)) {
                    NetworkOverviewScreen(
                        buffers = buffers.values
                            .filter { it.ref.kind != dev.brentdevs.yardhal.core.data.ConversationKind.SERVER }
                            .sortedBy { it.displayName.lowercase() },
                        networks = networks,
                        onSelect = { key ->
                            coordinator.markRead(key)
                            selectedKey = key
                        },
                        onAddNetwork = { addNetworkVisible = true },
                        onRemoveNetwork = { coordinator.removeNetwork(it) },
                        onBrowseChannels = {
                            networks.firstOrNull()?.let { coordinator.startChannelList(it.id) }
                        },
                        channelList = channelList,
                        onJoinFromList = { channel ->
                            val networkId = networks.firstOrNull()?.id
                            if (networkId != null) {
                                coordinator.sendText(
                                    networkId,
                                    ConversationRef.server(networkId).storageKey,
                                    "/join $channel",
                                )
                            }
                        },
                        onOpenDebug = {},
                        rawLogVersion = rawLogVersion,
                        rawLogProvider = { coordinator.rawLog(networks.firstOrNull()?.id ?: "") },
                    )
                }
                val key = selectedKey
                val buffer = conversationBufferFor(key)
                if (key != null && buffer != null) {
                    Box(modifier = Modifier.weight(0.62f)) {
                        val networkId = key.substringBefore("|")
                        val network = networks.firstOrNull { it.id == networkId }
                        ConversationScreen(
                            buffer = buffer,
                            networkName = network?.name ?: "",
                            connected = network?.status == ConnectionStatus.REGISTERED,
                            onSend = { text ->
                                coordinator.sendText(networkId, key, text)
                                coordinator.sendTyping(networkId, key)
                            },
                            onOpenJoin = { joinDialogVisible = true },
                            onLoadHistory = { coordinator.loadPersistedHistory(key) },
                            onReact = { msgid, emoji -> coordinator.react(networkId, key, msgid, emoji) },
                            onSetReplyDraft = { message -> coordinator.setReplyDraft(networkId, key, message) },
                            onDelete = { msgid -> coordinator.deleteMessage(networkId, key, msgid) },
                            sharedDraft = sharedDraft,
                            onSharedConsumed = onSharedConsumed,
                            onPickFile = { launchAttachmentPicker() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        } else {
            when {
                addNetworkVisible -> AddNetworkSheet(
                    presets = presets,
                    onSave = {
                        onNetworkSaved(it)
                        addNetworkVisible = false
                    },
                    onDismiss = { addNetworkVisible = false },
                )

                selectedKey != null -> {
                    val key = selectedKey!!
                    val buffer = conversationBufferFor(key)
                    val networkId = key.substringBefore("|")
                    val network = networks.firstOrNull { it.id == networkId }
                    if (buffer == null || network == null) {
                        selectedKey = null
                    } else {
                        BackHandler { selectedKey = null }
                        ConversationScreen(
                            buffer = buffer,
                            networkName = network.name,
                            connected = network.status == ConnectionStatus.REGISTERED,
                            onSend = { text ->
                                coordinator.sendText(networkId, key, text)
                                coordinator.sendTyping(networkId, key)
                            },
                            onOpenJoin = { joinDialogVisible = true },
                            onLoadHistory = { coordinator.loadPersistedHistory(key) },
                            onReact = { msgid, emoji -> coordinator.react(networkId, key, msgid, emoji) },
                            onSetReplyDraft = { message -> coordinator.setReplyDraft(networkId, key, message) },
                            onDelete = { msgid -> coordinator.deleteMessage(networkId, key, msgid) },
                            sharedDraft = sharedDraft,
                            onSharedConsumed = onSharedConsumed,
                            onPickFile = { launchAttachmentPicker() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                else -> {
                    if (networks.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Welcome to Yardhal", style = MaterialTheme.typography.headlineSmall)
                            Text("Your networks sail with you.", style = MaterialTheme.typography.titleMedium)
                            Button(onClick = { addNetworkVisible = true }) { Text("Add a network") }
                        }
                    } else {
                        NetworkOverviewScreen(
                            buffers = buffers.values
                                .filter { it.ref.kind != dev.brentdevs.yardhal.core.data.ConversationKind.SERVER }
                                .sortedBy { it.displayName.lowercase() },
                            networks = networks,
                            onSelect = { key ->
                                coordinator.markRead(key)
                                selectedKey = key
                            },
                            onAddNetwork = { addNetworkVisible = true },
                            onRemoveNetwork = { coordinator.removeNetwork(it) },
                            onBrowseChannels = {
                                networks.firstOrNull()?.let { coordinator.startChannelList(it.id) }
                            },
                            channelList = channelList,
                            onJoinFromList = { channel ->
                                val networkId = networks.firstOrNull()?.id
                                if (networkId != null) {
                                    coordinator.sendText(
                                        networkId,
                                        ConversationRef.server(networkId).storageKey,
                                        "/join $channel",
                                    )
                                }
                            },
                            onOpenDebug = {},
                            rawLogVersion = rawLogVersion,
                            rawLogProvider = { coordinator.rawLog(networks.firstOrNull()?.id ?: "") },
                        )
                    }
                }
            }
        }
    }

    if (whoisInfo != null) {
        val info = whoisInfo!!
        AlertDialog(
            onDismissRequest = coordinator::dismissWhois,
            title = { Text("Whois · ${info.nick}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    info.realName?.let { Text(it) }
                    if (info.user != null || info.host != null) {
                        Text("${info.user ?: "?"}@${info.host ?: "?"}", style = MaterialTheme.typography.bodySmall)
                    }
                    info.account?.let { Text("Account: $it", style = MaterialTheme.typography.bodySmall) }
                    info.server?.let { Text("Server: $it ${info.serverInfo.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
                    info.idleSeconds?.let { Text("Idle: ${it / 60} min", style = MaterialTheme.typography.bodySmall) }
                    if (info.channels.isNotEmpty()) {
                        Text(info.channels.joinToString(" "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = coordinator::dismissWhois) { Text("Close") }
            },
        )
    }

    if (joinDialogVisible) {
        val activeNetworkId = selectedKey?.substringBefore("|") ?: networks.firstOrNull()?.id
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
                    enabled = joinDraft.startsWith("#") && joinDraft.length > 1 && activeNetworkId != null,
                    onClick = {
                        val networkId = activeNetworkId
                        if (networkId != null) {
                            coordinator.sendText(
                                networkId,
                                ConversationRef.server(networkId).storageKey,
                                "/join $joinDraft",
                            )
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

private fun queryDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String =
    runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "upload.bin"
