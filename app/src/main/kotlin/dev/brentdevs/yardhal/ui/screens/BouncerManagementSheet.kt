package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brentdevs.yardhal.coordinator.BouncerAccountState
import dev.brentdevs.yardhal.coordinator.BouncerManagement
import dev.brentdevs.yardhal.coordinator.BouncerOperationStatus
import dev.brentdevs.yardhal.coordinator.BouncerServiceAvailability
import dev.brentdevs.yardhal.coordinator.SojuChannelSettings
import dev.brentdevs.yardhal.coordinator.ZncConnectionAction
import dev.brentdevs.yardhal.core.data.BouncerNetworkDraft
import dev.brentdevs.yardhal.core.data.BouncerServCommand
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkMode
import dev.brentdevs.yardhal.core.data.ZncChannelDraft
import dev.brentdevs.yardhal.core.data.ZncNetworkDraft
import dev.brentdevs.yardhal.core.data.ZncServerDraft
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun BouncerManagementSheet(
    management: BouncerManagement,
    configs: List<NetworkConfig>,
    initialNetworkId: String? = null,
    onDismiss: () -> Unit,
) {
    val accounts by management.accounts.collectAsStateWithLifecycle()
    var selectedId by remember(initialNetworkId) { mutableStateOf(initialNetworkId) }
    val availableIds = configs.filter {
        it.mode != NetworkMode.DIRECT || accounts[it.id]?.mode?.let { mode -> mode != NetworkMode.DIRECT } == true
    }.map { it.id }
    val id = selectedId?.takeIf { it in availableIds } ?: availableIds.firstOrNull()
    val state = id?.let(accounts::get)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp).imePadding()
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Bouncer management", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                availableIds.forEach { candidate ->
                    FilterChip(
                        selected = id == candidate,
                        onClick = { selectedId = candidate },
                        label = { Text(configs.firstOrNull { it.id == candidate }?.name ?: candidate) },
                    )
                }
            }
            if (id == null) {
                Text("Add a soju or ZNC account to manage a bouncer.")
            } else if (state == null || !state.connected) {
                Text("This bouncer connection is offline. Connect it before changing server settings.")
            } else {
                val busy = state.operation?.status == BouncerOperationStatus.RUNNING
                TextButton(enabled = !busy, onClick = { scope.launch { management.refresh(id) } }) { Text("Refresh bouncer") }
                state.operation?.let { outcome ->
                    Text(
                        "${outcome.status.name.lowercase().replaceFirstChar(Char::uppercase)} · ${outcome.applied}/${outcome.total} changes applied",
                        color = if (outcome.status == BouncerOperationStatus.ERROR || outcome.status == BouncerOperationStatus.PARTIAL) {
                            MaterialTheme.colorScheme.error
                        } else MaterialTheme.colorScheme.onSurface,
                    )
                    outcome.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    outcome.refreshError?.let { Text("Refresh failed: $it", color = MaterialTheme.colorScheme.error) }
                }
                key(id) {
                    if (state.mode == NetworkMode.SOJU) SojuManagement(management, state, busy)
                    if (state.mode == NetworkMode.ZNC) ZncManagement(management, state, busy)
                }
            }
        }
    }
}

@Composable
private fun SojuManagement(management: BouncerManagement, state: BouncerAccountState, busy: Boolean) {
    val scope = rememberCoroutineScope()
    var editingId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    Text("BouncerServ: ${state.sojuService.name.lowercase()}")
    if (state.boundNetwork == null) {
        state.sojuNetworks.forEach { (netId, attributes) ->
            Text(attributes.name ?: netId, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(attributes.host, attributes.state?.wireName, attributes.error).joinToString(" · "))
            attributes.unknown["enabled"]?.let { Text(if (it == "0") "Disabled upstream" else "Enabled upstream") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { editingId = netId; adding = false }) { Text("Edit ${attributes.name ?: netId}") }
                TextButton(enabled = !busy, onClick = { deletingId = netId }) { Text("Delete ${attributes.name ?: netId}") }
            }
        }
        TextButton(enabled = !busy, onClick = { editingId = null; adding = true }) { Text("Add upstream network") }
        if (adding || editingId != null) {
            val baseline = editingId?.let(state.sojuNetworks::get)
            if (adding || baseline != null) {
                key(editingId, adding) {
                    SojuNetworkEditor(
                        initial = baseline?.let(BouncerNetworkDraft::fromAttributes) ?: BouncerNetworkDraft(addr = "ircs://"),
                        enabled = !busy,
                        onCancel = { editingId = null; adding = false },
                        onApply = { draft ->
                            val netId = editingId
                            scope.launch {
                                if (netId == null) management.addSojuNetwork(state.networkId, draft)
                                else management.updateSojuNetwork(state.networkId, netId, draft)
                            }
                        },
                    )
                }
            } else Text("This upstream was removed. Refresh before editing another network.")
        }
    } else {
        Text("Bound upstream ${state.boundNetwork}. Network settings are managed from the parent account.")
        BouncerChannelLookup(!busy, "soju") { channel -> scope.launch { management.fetchSojuChannel(state.networkId, channel) } }
        state.sojuChannels.values.forEach { baseline ->
            key(baseline.name, baseline) {
                SojuChannelEditor(baseline, !busy && state.sojuService == BouncerServiceAvailability.AVAILABLE) { draft ->
                    scope.launch { management.updateSojuChannel(state.networkId, baseline, draft) }
                }
            }
        }
    }
    deletingId?.let { netId ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("Delete upstream?") },
            text = { Text("Delete ${state.sojuNetworks[netId]?.name ?: netId} from soju. Its bound connection and local conversation state will also be removed.") },
            confirmButton = { TextButton(onClick = { deletingId = null; scope.launch { management.removeSojuNetwork(state.networkId, netId) } }) { Text("Delete upstream") } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SojuNetworkEditor(initial: BouncerNetworkDraft, enabled: Boolean, onCancel: () -> Unit, onApply: (BouncerNetworkDraft) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    HorizontalDivider()
    Text("Upstream settings", style = MaterialTheme.typography.titleMedium)
    BouncerField("Upstream name", draft.name) { draft = draft.copy(name = it) }
    BouncerField("Upstream address (ircs:// or irc+insecure://)", draft.addr) { draft = draft.copy(addr = it) }
    BouncerField("Upstream nickname", draft.nick) { draft = draft.copy(nick = it) }
    BouncerField("Upstream username", draft.username) { draft = draft.copy(username = it) }
    BouncerField("Upstream real name", draft.realname) { draft = draft.copy(realname = it) }
    BouncerToggle("Upstream enabled", draft.enabled) { draft = draft.copy(enabled = it) }
    BouncerToggle("Replace or clear upstream password", draft.passwordChanged) { draft = draft.copy(passwordChanged = it, password = "") }
    if (draft.passwordChanged) BouncerField("New upstream password (empty clears)", draft.password, secret = true) { draft = draft.copy(password = it) }
    draft.addrValidationError()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel) { Text("Close editor") }
        Button(enabled = enabled && draft.isValid(), onClick = { onApply(draft) }) { Text("Apply upstream changes") }
    }
}

@Composable
private fun SojuChannelEditor(baseline: SojuChannelSettings, enabled: Boolean, onApply: (SojuChannelSettings) -> Unit) {
    var draft by remember { mutableStateOf(baseline) }
    var timer by remember { mutableStateOf(baseline.detachAfterSeconds?.toString().orEmpty()) }
    HorizontalDivider()
    Text(baseline.name, style = MaterialTheme.typography.titleMedium)
    Text(baseline.status.ifEmpty { "Channel status not reported" })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = enabled, onClick = { onApply(baseline.copy(detached = true)) }) { Text("Detach") }
        Button(enabled = enabled, onClick = { onApply(baseline.copy(detached = false)) }) { Text("Reattach") }
    }
    BouncerField("Detach after seconds (blank keeps current)", timer) {
        timer = it
        draft = draft.copy(detachAfterSeconds = it.toLongOrNull())
    }
    Text("Relay detached messages${if (baseline.relayDetached == null) " · current value not reported" else ""}")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BouncerServCommand.RelayMode.entries.forEach { mode ->
            FilterChip(selected = draft.relayDetached == mode, onClick = { draft = draft.copy(relayDetached = mode) }, label = { Text(mode.wireName) })
        }
    }
    Text("Reattach on${if (baseline.reattachOn == null) " · current value not reported" else ""}")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BouncerServCommand.RelayMode.entries.forEach { mode ->
            FilterChip(selected = draft.reattachOn == mode, onClick = { draft = draft.copy(reattachOn = mode) }, label = { Text(mode.wireName) })
        }
    }
    Button(enabled = enabled && (timer.isBlank() || timer.toLongOrNull()?.let { it >= 0 } == true), onClick = { onApply(draft) }) { Text("Apply channel changes") }
}

@Composable
private fun ZncManagement(management: BouncerManagement, state: BouncerAccountState, busy: Boolean) {
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }
    var editingName by remember { mutableStateOf<String?>(null) }
    var deletingName by remember { mutableStateOf<String?>(null) }
    Text("*status: ${state.zncStatus.name.lowercase()} · *controlpanel: ${state.zncControlPanel.name.lowercase()}")
    if (state.zncControlPanel != BouncerServiceAvailability.AVAILABLE) {
        Text("Detailed settings require the ZNC controlpanel module. Network creation, deletion and connection controls use *status independently.")
    }
    val editable = !busy && state.zncControlPanel == BouncerServiceAvailability.AVAILABLE
    val statusEditable = !busy && state.zncStatus == BouncerServiceAvailability.AVAILABLE
    state.zncNetworks.forEach { network ->
        Text(network.name, style = MaterialTheme.typography.titleMedium)
        Text("${if (network.onIrc) "Connected" else "Disconnected"} · ${network.ircServer} · ${network.channelCount ?: "unknown"} channels")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = editable, onClick = { editingName = network.name; scope.launch { management.fetchZncNetwork(state.networkId, network.name) } }) { Text("Edit ${network.name}") }
            TextButton(enabled = statusEditable, onClick = { deletingName = network.name }) { Text("Delete ${network.name}") }
        }
        if (state.boundNetwork == network.name) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZncConnectionAction.entries.forEach { action ->
                    TextButton(
                        enabled = !busy && state.zncStatus == BouncerServiceAvailability.AVAILABLE,
                        onClick = { scope.launch { management.setZncConnection(state.networkId, network.name, action) } },
                    ) { Text(action.name.lowercase().replaceFirstChar(Char::uppercase)) }
                }
            }
        }
    }
    BouncerField("New ZNC network name", newName) { newName = it }
    Button(enabled = statusEditable && newName.isNotBlank(), onClick = { scope.launch { management.addZncNetwork(state.networkId, newName.trim()) } }) { Text("Add ZNC network") }
    editingName?.let { name ->
        val baseline = state.zncNetworkDetails[name]
        if (baseline != null) {
            key(name, baseline) {
                ZncNetworkEditor(baseline, editable, state.boundNetwork == name, { editingName = null }) { draft ->
                    scope.launch { management.applyZncNetwork(state.networkId, baseline, draft) }
                }
            }
        } else Text("Network settings have not been retrieved.")
    }
    if (state.boundNetwork != null) {
        BouncerChannelLookup(!busy, "ZNC") { channel -> scope.launch { management.fetchZncChannel(state.networkId, channel) } }
        state.zncChannels.values.forEach { baseline ->
            key(baseline.name, baseline) {
                ZncChannelEditor(baseline, editable) { draft -> scope.launch { management.applyZncChannel(state.networkId, baseline, draft) } }
            }
        }
    } else Text("Select a ZNC network in connection settings to manage its channels and servers.")
    deletingName?.let { name ->
        AlertDialog(
            onDismissRequest = { deletingName = null },
            title = { Text("Delete ZNC network?") },
            text = { Text("Delete $name and its server/channel configuration from ZNC?") },
            confirmButton = { TextButton(onClick = { deletingName = null; scope.launch { management.removeZncNetwork(state.networkId, name) } }) { Text("Delete network") } },
            dismissButton = { TextButton(onClick = { deletingName = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ZncNetworkEditor(baseline: ZncNetworkDraft, enabled: Boolean, serversEditable: Boolean, onCancel: () -> Unit, onApply: (ZncNetworkDraft) -> Unit) {
    var draft by remember { mutableStateOf(baseline) }
    HorizontalDivider()
    Text("ZNC settings · ${baseline.name}", style = MaterialTheme.typography.titleMedium)
    baseline.settings.forEach { (variable, value) ->
        BouncerField(variable.name, draft.settings[variable] ?: value) { draft = draft.copy(settings = draft.settings + (variable to it)) }
    }
    if (serversEditable) {
        Text("Servers", style = MaterialTheme.typography.titleSmall)
        draft.servers.forEachIndexed { index, server ->
            key(index) {
                ZncServerEditor(server) { replacement ->
                    draft = draft.copy(servers = if (replacement == null) draft.servers.filterIndexed { position, _ -> position != index }
                        else draft.servers.mapIndexed { position, entry -> if (position == index) replacement else entry })
                }
            }
        }
        TextButton(onClick = { draft = draft.copy(servers = draft.servers + ZncServerDraft("")) }) { Text("Add server") }
    } else Text("Connect to ${baseline.name} before fetching or editing its server list.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel) { Text("Close settings") }
        Button(enabled = enabled && draft.servers.all { it.host.isNotBlank() && it.port in 1..65535 }, onClick = { onApply(draft) }) { Text("Apply ZNC changes") }
    }
}

@Composable
private fun ZncServerEditor(server: ZncServerDraft, onChange: (ZncServerDraft?) -> Unit) {
    var port by remember(server.host, server.port) { mutableStateOf(server.port.toString()) }
    BouncerField("Server host", server.host) { onChange(server.copy(host = it)) }
    BouncerField("Server port", port) { port = it; onChange(server.copy(port = it.toIntOrNull() ?: 0)) }
    BouncerToggle("Server TLS", server.tls) { onChange(server.copy(tls = it)) }
    BouncerToggle("Replace or clear server password", server.passwordChanged) {
        onChange(server.copy(passwordChanged = it, password = if (it) "" else null))
    }
    if (server.passwordChanged) BouncerField("New server password (empty clears)", server.password.orEmpty(), secret = true) { onChange(server.copy(password = it)) }
    TextButton(onClick = { onChange(null) }) { Text("Remove server") }
}

@Composable
private fun ZncChannelEditor(baseline: ZncChannelDraft, enabled: Boolean, onApply: (ZncChannelDraft) -> Unit) {
    var draft by remember { mutableStateOf(baseline) }
    var size by remember { mutableStateOf(baseline.bufferSize?.toString().orEmpty()) }
    HorizontalDivider()
    Text("ZNC channel · ${baseline.name}", style = MaterialTheme.typography.titleMedium)
    BouncerToggle("Detached", draft.detached) { draft = draft.copy(detached = it) }
    BouncerToggle("Disabled channel", draft.disabled) { draft = draft.copy(disabled = it) }
    BouncerField("Buffer size (blank inherits)", size) { size = it; draft = draft.copy(bufferSize = it.toIntOrNull()) }
    Text("Clear buffer automatically")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = draft.autoClearChanBuffer == null, onClick = { draft = draft.copy(autoClearChanBuffer = null) }, label = { Text("Inherit") })
        FilterChip(selected = draft.autoClearChanBuffer == true, onClick = { draft = draft.copy(autoClearChanBuffer = true) }, label = { Text("Yes") })
        FilterChip(selected = draft.autoClearChanBuffer == false, onClick = { draft = draft.copy(autoClearChanBuffer = false) }, label = { Text("No") })
    }
    Button(enabled = enabled && (size.isBlank() || size.toIntOrNull()?.let { it >= 0 } == true), onClick = { onApply(draft) }) { Text("Apply ZNC channel changes") }
}

@Composable
private fun BouncerChannelLookup(enabled: Boolean, mode: String, onFetch: (String) -> Unit) {
    var channel by remember { mutableStateOf("") }
    HorizontalDivider()
    BouncerField("$mode channel", channel) { channel = it }
    Button(enabled = enabled && channel.isNotBlank(), onClick = { onFetch(channel.trim()) }) { Text("Fetch channel status") }
}

@Composable
private fun BouncerField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BouncerToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row {
        Checkbox(checked = checked, onCheckedChange = onChange)
        TextButton(onClick = { onChange(!checked) }) { Text(label) }
    }
}
