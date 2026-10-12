package dev.brentdevs.yardhal.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.sync.SyncManager
import dev.brentdevs.yardhal.sync.SyncUiState
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun SyncSettingsSheet(manager: SyncManager, state: SyncUiState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf("") }
    var enrollmentSecret by remember { mutableStateOf("") }
    var deleteSecret by remember { mutableStateOf("") }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDisconnect = false },
            title = { Text("Disconnect this device?") },
            text = { Text("This device's access will be revoked on the server. Your local IRC settings and conversations stay here. If the server is offline, disconnect cannot be confirmed; try again later.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            if (manager.disconnect(revokeDevice = true)) {
                                confirmDisconnect = false
                                actionError = null
                            } else {
                                actionError = "Device revocation or local cleanup was not confirmed. Check the status above and retry."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            actionError = "Device revocation or local cleanup was not confirmed. Check the status above and retry."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Revoke device") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!busy) { confirmDelete = false; deleteSecret = "" } },
            title = { Text("Delete cloud account for all devices?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This permanently deletes the synchronized account and revokes access for ALL devices. Local IRC settings and conversations on each device are not deleted. Enter the account enrollment secret to confirm.")
                    OutlinedTextField(
                        value = deleteSecret,
                        onValueChange = { deleteSecret = it },
                        label = { Text("Account enrollment secret") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && deleteSecret.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            if (manager.deleteAccount(deleteSecret.trim())) {
                                confirmDelete = false
                                deleteSecret = ""
                                actionError = null
                            } else {
                                actionError = "Cloud deletion or local cleanup was not confirmed. Check the status above; if cloud access ended, revoke this device to finish local cleanup."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            actionError = "Cloud deletion or local cleanup was not confirmed. Check the status above; if cloud access ended, revoke this device to finish local cleanup."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Delete for all devices") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmDelete = false; deleteSecret = "" }) { Text("Cancel") } },
        )
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Account sync", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Optional sync of app settings through your self-hosted server. Create an account with the server CLI first, then enter its URL, account ID and enrollment secret. The server can read synchronized settings: this is not end-to-end encrypted. Local IRC continues to work offline.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Status: ${state.phase}")
            state.endpoint?.let { Text("Server: $it") }
            state.accountId?.let { Text("Account: $it") }
            state.deviceId?.let { Text("Device: $it") }
            Text("Pending changes: ${state.pending}")
            Text("Last successful sync: ${state.lastSuccessMs?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Never"}")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!state.enabled) {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://sync.example.org") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = accountId,
                    onValueChange = { accountId = it },
                    label = { Text("Account ID (UUID)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = enrollmentSecret,
                    onValueChange = { enrollmentSecret = it },
                    label = { Text("Enrollment secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !busy && endpoint.isNotBlank() && accountId.isNotBlank() && enrollmentSecret.isNotBlank(),
                    onClick = {
                        busy = true
                        actionError = null
                        scope.launch {
                            try {
                                if (manager.enable(endpoint.trim(), accountId.trim(), enrollmentSecret.trim())) {
                                    enrollmentSecret = ""
                                    endpoint = ""
                                    accountId = ""
                                } else {
                                    actionError = "Could not enable sync. Check the server URL and account credentials, then retry."
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                actionError = "Could not enable sync. Check the server URL and account credentials, then retry."
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "Connecting…" else "Enable sync") }
            } else {
                Button(enabled = !busy, onClick = { actionError = null; manager.retry() }) { Text("Retry sync") }
                OutlinedButton(enabled = !busy, onClick = { actionError = null; confirmDisconnect = true }) {
                    Text("Disconnect and revoke this device")
                }
                TextButton(enabled = !busy, onClick = { actionError = null; confirmDelete = true }) {
                    Text("Delete cloud account for all devices")
                }
            }
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") }
        }
    }
}
