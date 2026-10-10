package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.core.data.RelayConfiguration
import dev.brentdevs.yardhal.core.data.RelayConfigurationStore
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.RelayFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun RelaySettingsSheet(
    networkId: String,
    networkName: String,
    store: RelayConfigurationStore,
    mapping: CaseMapping,
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {},
) {
    var configurations by remember(networkId) { mutableStateOf(store.forNetwork(networkId)) }
    var sender by remember(networkId) { mutableStateOf("") }
    var format by remember(networkId) { mutableStateOf(RelayFormat.ANGLE) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    fun save(clearSender: Boolean, change: () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { change() }
                configurations = store.forNetwork(networkId)
                if (clearSender) sender = ""
                error = null
                onChanged()
            } catch (failure: IllegalArgumentException) {
                error = "Enter a single exact nickname without spaces, masks or host syntax. At most 64 relay senders per network."
            } catch (failure: java.io.IOException) {
                error = failure.message ?: "Could not save relay configuration"
            } catch (failure: SecurityException) {
                error = "Relay configuration storage is inaccessible"
            } finally {
                saving = false
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Relays · $networkName", style = MaterialTheme.typography.titleLarge)
            Text("Only exact configured IRC sender nicknames are parsed. Relay names are supplied by the bridge, not verified accounts; the original IRC sender remains visible. No wildcard or arbitrary regex matching.")
            configurations.forEach { item ->
                Text("${item.wireSender} · ${item.format}")
                TextButton(enabled = !saving, onClick = {
                    save(false) { store.update(networkId, configurations - item) }
                }) { Text("Remove ${item.wireSender}") }
            }
            OutlinedTextField(sender, { sender = it }, label = { Text("Exact relay bot nickname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            RelayFormat.entries.forEach { option ->
                val label = when (option) {
                    RelayFormat.ANGLE -> "<sender> message"
                    RelayFormat.BRACKET -> "[sender] message"
                    RelayFormat.COLON -> "sender: message"
                }
                FilterChip(selected = format == option, onClick = { format = option }, label = { Text(label) })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(enabled = !saving, onClick = {
                val configuration = RelayConfiguration(sender.trim(), format.name)
                save(true) { store.upsert(networkId, configuration, mapping) }
            }) { Text(if (saving) "Saving…" else "Add relay sender") }
        }
    }
}
