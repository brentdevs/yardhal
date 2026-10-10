package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.ChannelAccessListStatus
import dev.brentdevs.yardhal.coordinator.ChannelModeSetting
import dev.brentdevs.yardhal.coordinator.ChannelSettingsRequestStatus
import dev.brentdevs.yardhal.coordinator.ChannelSettingsState
import java.time.Instant

private data class ChannelSettingsConfirmation(val title: String, val detail: String, val operatorRequired: Boolean = true, val apply: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ChannelSettingsSheet(
    state: ChannelSettingsState,
    onDismiss: () -> Unit,
    onModeChange: (Char, Boolean, String) -> Unit,
    onTopicChange: (String) -> Unit,
    onRefreshList: (Char) -> Unit,
    onListChange: (Char, String, Boolean) -> Unit,
) {
    var topic by remember(state.storageKey, state.topic) { mutableStateOf(state.topic) }
    var listMode by remember(state.storageKey, state.accessLists.keys) { mutableStateOf(state.accessLists.keys.firstOrNull() ?: 'b') }
    var mask by remember(state.storageKey, listMode) { mutableStateOf("") }
    var confirmation by remember(state.storageKey) { mutableStateOf<ChannelSettingsConfirmation?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Channel settings · ${state.channel}", style = MaterialTheme.typography.titleLarge)
            state.unavailableReason?.let { Text(it) }
            Text("Server-confirmed values are shown. Edits remain pending until the server confirms or refuses them.")
            when (state.requestStatus) {
                ChannelSettingsRequestStatus.PENDING -> Text("Waiting for server: ${state.requestDescription.orEmpty()}")
                ChannelSettingsRequestStatus.CONFIRMED -> Text("Server confirmed ${state.requestDescription.orEmpty()}")
                ChannelSettingsRequestStatus.TIMEOUT -> Text("Request timed out; no confirmation received")
                ChannelSettingsRequestStatus.DISCONNECTED -> Text("Disconnected; pending changes are unconfirmed")
                else -> Unit
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(topic, { topic = it }, label = { Text("Topic for ${state.channel}") },
                enabled = state.canEditTopic && !state.busy, modifier = Modifier.fillMaxWidth(),
                supportingText = { state.topicLengthLimit?.let { Text("Server limit: $it UTF-8 bytes") } })
            TextButton(enabled = state.canEditTopic && !state.busy && topic != state.topic,
                modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    val proposed = topic
                    confirmation = ChannelSettingsConfirmation("Change topic for ${state.channel}?", proposed.ifEmpty { "Remove the current topic" }, operatorRequired = false) {
                        onTopicChange(proposed)
                    }
                }) { Text("Save topic for ${state.channel}") }
            HorizontalDivider()
            Text("Supported channel modes", style = MaterialTheme.typography.titleMedium)
            state.modes.forEach { mode ->
                ChannelModeEditor(mode, state.channel, state.canEditModes && !state.busy) { enabled, parameter ->
                    confirmation = ChannelSettingsConfirmation("Change mode ${if (enabled) "+" else "-"}${mode.mode} for ${state.channel}?",
                        if (parameter.isEmpty()) "Request this mode change from the server" else "Parameter: $parameter") {
                        onModeChange(mode.mode, enabled, parameter)
                    }
                }
            }
            HorizontalDivider()
            Text("Access lists", style = MaterialTheme.typography.titleMedium)
            if (state.accessLists.isEmpty()) Text("This server has not advertised a supported access list")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.accessLists.keys.forEach { mode ->
                    FilterChip(selected = listMode == mode, onClick = { listMode = mode },
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text(accessListName(mode)) })
                }
            }
            val access = state.accessLists[listMode] ?: state.accessLists.values.firstOrNull()
            if (access != null) {
                val mode = access.mode
                TextButton(onClick = { onRefreshList(mode) }, enabled = state.available && !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Fetch ${accessListName(mode)} for ${state.channel}") }
                Text(when (access.status) {
                    ChannelAccessListStatus.IDLE -> "Not fetched"
                    ChannelAccessListStatus.LOADING -> "Loading; waiting for the server's end-of-list reply"
                    ChannelAccessListStatus.COMPLETE -> "Complete · ${access.entries.size} entries"
                    ChannelAccessListStatus.EMPTY -> "Complete · empty list"
                    ChannelAccessListStatus.ERROR -> "Server error; list may be incomplete"
                    ChannelAccessListStatus.TIMEOUT -> "Timed out; list is incomplete"
                    ChannelAccessListStatus.DISCONNECTED -> "Disconnected; list is incomplete"
                })
                access.limit?.let { Text("Server list limit: $it") }
                access.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                access.entries.forEach { entry ->
                    Column {
                        Text(entry.mask)
                        Text("Set by ${entry.setter ?: "unknown"} · ${entry.setAtEpochSeconds?.let { runCatching { Instant.ofEpochSecond(it).toString() }.getOrNull() } ?: "date unavailable"}",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = state.canEditModes && !state.busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                            confirmation = ChannelSettingsConfirmation("Remove ${accessListName(mode)} entry from ${state.channel}?", entry.mask) {
                                onListChange(mode, entry.mask, false)
                            }
                        }) { Text("Remove ${entry.mask}") }
                    }
                }
                OutlinedTextField(mask, { mask = it }, label = { Text("New ${accessListName(mode)} mask") }, singleLine = true,
                    enabled = state.canEditModes && !state.busy, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = state.canEditModes && !state.busy && mask.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    val proposed = mask
                    confirmation = ChannelSettingsConfirmation("Add ${accessListName(mode)} entry to ${state.channel}?", proposed) {
                        onListChange(mode, proposed, true)
                    }
                }) { Text("Add mask to ${state.channel}") }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close channel settings") }
        }
    }
    confirmation?.let { pending ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(pending.title) }, text = { Text(pending.detail) },
            confirmButton = { TextButton(enabled = !state.busy && (if (pending.operatorRequired) state.canEditModes else state.canEditTopic), onClick = {
                pending.apply()
                confirmation = null
            }) { Text("Send change to server") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel change") } })
    }
}

@Composable
private fun ChannelModeEditor(mode: ChannelModeSetting, channel: String, editable: Boolean, onChange: (Boolean, String) -> Unit) {
    var parameter by remember(channel, mode.mode, mode.parameter) { mutableStateOf(mode.parameter) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${mode.mode} · ${channelModeName(mode.mode)}", modifier = Modifier.weight(1f))
            Switch(checked = mode.enabled, enabled = editable && (!mode.parameterOnEnable || mode.enabled || parameter.isNotBlank()),
                onCheckedChange = { enabled -> onChange(enabled, if (enabled) parameter else if (mode.parameterOnDisable) mode.parameter else "") },
                modifier = Modifier.semantics {
                    contentDescription = "Mode ${mode.mode} for $channel"
                    stateDescription = if (mode.enabled) "Enabled on server" else "Disabled on server"
                })
        }
        if (mode.parameterOnEnable) {
            OutlinedTextField(parameter, { parameter = it }, singleLine = true, enabled = editable,
                label = { Text("Parameter for mode ${mode.mode} on $channel") }, modifier = Modifier.fillMaxWidth())
            if (mode.enabled) TextButton(enabled = editable && parameter.isNotBlank() && parameter != mode.parameter,
                modifier = Modifier.heightIn(min = 48.dp), onClick = { onChange(true, parameter) }) { Text("Change mode ${mode.mode} parameter") }
        }
    }
}

private fun accessListName(mode: Char): String = when (mode) {
    'b' -> "Bans"
    'e' -> "Ban exceptions"
    'I' -> "Invite exceptions"
    else -> "List $mode"
}

private fun channelModeName(mode: Char): String = when (mode) {
    'i' -> "Invite only"
    'm' -> "Moderated"
    'n' -> "No external messages"
    'p' -> "Private"
    's' -> "Secret"
    't' -> "Operator-only topic"
    'k' -> "Channel key"
    'l' -> "Member limit"
    else -> "Server mode"
}
