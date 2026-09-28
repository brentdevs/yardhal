package dev.brentdevs.yardhal.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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
public fun AddNetworkSheet(
    presets: List<NetworkPresetUi>,
    initialPreset: NetworkPresetUi? = null,
    onSave: (draft: NetworkDraft) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var preset by remember(initialPreset) { mutableStateOf<NetworkPresetUi?>(initialPreset) }
    var host by remember(initialPreset) { mutableStateOf(initialPreset?.host ?: "") }
    var port by remember(initialPreset) { mutableStateOf((initialPreset?.port ?: 6697).toString()) }
    var tls by remember(initialPreset) { mutableStateOf(initialPreset?.tls ?: true) }
    var nick by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf("") }
    var name by remember(initialPreset) { mutableStateOf(initialPreset?.name ?: "") }

    fun applyPreset(selected: NetworkPresetUi) {
        preset = selected
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
        Text("New network", style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { candidate ->
                FilterChip(
                    selected = preset?.id == candidate.id,
                    onClick = { applyPreset(candidate) },
                    label = { Text(candidate.name) },
                )
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
            value = password,
            onValueChange = { password = it },
            label = { Text("SASL password (optional)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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
                            saslPassword = password.takeIf { it.isNotBlank() },
                            autojoin = channels.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) },
                            displayName = name.ifBlank { host.trim() },
                        ),
                    )
                },
            ) { Text("Connect") }
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
)
