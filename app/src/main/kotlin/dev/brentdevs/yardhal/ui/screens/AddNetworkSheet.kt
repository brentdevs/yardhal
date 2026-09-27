package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
public fun WelcomeScreen(
    onAddNetwork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Welcome to Yardhal", style = MaterialTheme.typography.headlineSmall)
        Text("Your networks sail with you.", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add an IRC network to get started. Yardhal keeps your connection " +
                "alive and notifies you when your name comes up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAddNetwork) { Text("Add a network") }
    }
}

@Composable
public fun AddNetworkSheet(
    presets: List<NetworkPresetUi>,
    onSave: (draft: NetworkDraft) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var preset by remember { mutableStateOf<NetworkPresetUi?>(null) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("6697") }
    var tls by remember { mutableStateOf(true) }
    var nick by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("New network", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { candidate ->
                FilterChip(
                    selected = preset?.id == candidate.id,
                    onClick = { applyPreset(candidate) },
                    label = { Text(candidate.name) },
                )
            }
        }
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
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = channels,
            onValueChange = { channels = it },
            label = { Text("Autojoin channels (#a,#b)") },
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
