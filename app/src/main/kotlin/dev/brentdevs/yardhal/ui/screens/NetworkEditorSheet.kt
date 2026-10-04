package dev.brentdevs.yardhal.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.core.data.NetworkConfig

@Composable
public fun WelcomeScreen(
    onAddNetwork: () -> Unit,
    presets: List<NetworkPresetUi>,
    onPickPreset: (NetworkPresetUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Sailing,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Yardhal", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Your networks sail with you.",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Add an IRC network to get started. Yardhal keeps your connection " +
                "alive and notifies you when your name comes up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (presets.isNotEmpty()) {
            Text(
                "Quick start",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { candidate ->
                    OutlinedButton(
                        onClick = { onPickPreset(candidate) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(candidate.name)
                            Text(
                                "${candidate.host}:${candidate.port}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        TextButton(onClick = onAddNetwork) { Text("Add network manually") }
    }
}

@Composable
public fun NetworkEditorSheet(
    presets: List<NetworkPresetUi>,
    initialPreset: NetworkPresetUi? = null,
    initialConfig: NetworkConfig? = null,
    onSave: (draft: NetworkDraft) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    key(initialConfig?.id, initialPreset?.id) {
        val editing = initialConfig != null
        val hasSavedPassword = initialConfig?.saslPasswordRef != null
        var presetId by rememberSaveable { mutableStateOf(initialPreset?.id) }
        var host by rememberSaveable { mutableStateOf(initialConfig?.host ?: initialPreset?.host ?: "") }
        var port by rememberSaveable {
            mutableStateOf((initialConfig?.port ?: initialPreset?.port ?: 6697).toString())
        }
        var tls by rememberSaveable { mutableStateOf(initialConfig?.tls ?: initialPreset?.tls ?: true) }
        var nick by rememberSaveable { mutableStateOf(initialConfig?.nick ?: "") }
        var account by rememberSaveable { mutableStateOf(initialConfig?.saslAuthcid ?: "") }
        var password by remember { mutableStateOf("") }
        var clearPassword by rememberSaveable { mutableStateOf(false) }
        var channels by rememberSaveable { mutableStateOf(initialConfig?.autojoin?.joinToString(",") ?: "") }
        var name by rememberSaveable { mutableStateOf(initialConfig?.name ?: initialPreset?.name ?: "") }

        fun applyPreset(selected: NetworkPresetUi) {
            presetId = selected.id
            host = selected.host
            port = selected.port.toString()
            tls = selected.tls
            if (name.isBlank()) name = selected.name
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (editing) "Edit network" else "New network", style = MaterialTheme.typography.titleLarge)
            if (editing) {
                Text(
                    "Changing connection settings reconnects this network. Existing conversations and history remain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { candidate ->
                        FilterChip(
                            selected = presetId == candidate.id,
                            onClick = { applyPreset(candidate) },
                            label = { Text(candidate.name) },
                        )
                    }
                }
            }
            Text("Connection", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = tls,
                    onClick = { tls = !tls },
                    label = { Text(if (tls) "TLS" else "Plain") },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text("Identity", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = nick,
                onValueChange = { nick = it },
                label = { Text("Nickname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text("SASL account (optional)") },
                supportingText = {
                    Text("Leave blank to use your nickname for SASL.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("SASL password (optional)") },
                supportingText = {
                    if (hasSavedPassword) {
                        Text(
                            if (clearPassword) "The saved password will be removed."
                            else "Leave blank to keep the saved password, or enter a new password to replace it.",
                        )
                    }
                },
                enabled = !clearPassword,
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasSavedPassword) {
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = clearPassword,
                        role = Role.Checkbox,
                        onValueChange = {
                            clearPassword = it
                            if (it) password = ""
                        },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = clearPassword, onCheckedChange = null)
                    Text("Clear saved SASL password")
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Channels", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = channels,
                onValueChange = { channels = it },
                label = { Text("Autojoin channels (#a,#b)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                val portValid = port.toIntOrNull()?.let { it in 1..65535 } == true
                Button(
                    enabled = host.isNotBlank() && nick.isNotBlank() && portValid,
                    onClick = {
                        onSave(
                            NetworkDraft(
                                host = host.trim(),
                                port = port.toInt(),
                                tls = tls,
                                nick = nick.trim(),
                                saslPassword = if (clearPassword) null else password.takeIf { it.isNotBlank() },
                                autojoin = channels.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) },
                                displayName = name.trim().ifBlank { host.trim() },
                                networkId = initialConfig?.id,
                                saslAuthcid = account.trim().takeIf(String::isNotEmpty),
                                clearSaslPassword = clearPassword,
                            ),
                        )
                    },
                ) { Text(if (editing) "Save changes" else "Connect") }
            }
        }
    }
}

public data class NetworkPresetUi(public val id: String, public val name: String, public val host: String, public val port: Int, public val tls: Boolean)

public data class NetworkDraft(
    public val host: String,
    public val port: Int,
    public val tls: Boolean,
    public val nick: String,
    public val saslPassword: String?,
    public val autojoin: List<String>,
    public val displayName: String,
    public val networkId: String? = null,
    public val saslAuthcid: String? = null,
    public val clearSaslPassword: Boolean = false,
)
