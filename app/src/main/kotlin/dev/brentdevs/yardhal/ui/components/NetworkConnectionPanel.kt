package dev.brentdevs.yardhal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.RecoveryPhase
import dev.brentdevs.yardhal.coordinator.UiNetwork
import dev.brentdevs.yardhal.core.client.CertificateInspection
import java.time.Instant

public fun connectionPhaseLabel(phase: RecoveryPhase): String = when (phase) {
    RecoveryPhase.DISCONNECTED -> "Not connected"
    RecoveryPhase.USER_DISCONNECTED -> "Disconnected by you · automatic recovery paused"
    RecoveryPhase.OFFLINE -> "Device offline · waiting for an internet connection"
    RecoveryPhase.CONNECTING -> "Connecting…"
    RecoveryPhase.REGISTERED -> "Connected"
    RecoveryPhase.SERVER_UNREACHABLE -> "Server unreachable · waiting to retry"
    RecoveryPhase.AUTHENTICATION_REJECTED -> "Authentication or client identity rejected · correct settings to reconnect"
    RecoveryPhase.CERTIFICATE_REJECTED -> "Server certificate rejected · automatic recovery paused"
    RecoveryPhase.IDENTIFYING -> "Waiting for NickServ identification…"
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
public fun NetworkConnectionPanel(
    network: UiNetwork,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onTrustCertificate: (CertificateInspection) -> Boolean,
    onRemoveCertificateTrust: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var inspected by remember(network.id) { mutableStateOf<CertificateInspection?>(null) }
    var removeConfirmation by remember(network.id) { mutableStateOf(false) }
    var actionError by remember(network.id, network.rejectedCertificate, network.hasCertificatePin) { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(connectionPhaseLabel(network.connectionPhase), style = MaterialTheme.typography.labelMedium)
        network.connectionError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        actionError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (network.connectionPhase != RecoveryPhase.REGISTERED &&
                network.connectionPhase != RecoveryPhase.CONNECTING &&
                network.connectionPhase != RecoveryPhase.IDENTIFYING
            ) {
                TextButton(onClick = onConnect) { Text("Connect") }
            }
            if (network.connectionPhase != RecoveryPhase.USER_DISCONNECTED || network.disconnectSavePending) {
                TextButton(onClick = onDisconnect) {
                    Text(if (network.disconnectSavePending) "Retry saving Disconnect" else "Disconnect")
                }
            }
            TextButton(onClick = onEdit) { Text("Edit network") }
            network.rejectedCertificate?.let { certificate ->
                TextButton(onClick = { inspected = certificate }) { Text("Inspect certificate") }
            }
            if (network.hasCertificatePin) {
                TextButton(onClick = { removeConfirmation = true }) { Text("Remove certificate trust") }
            }
        }
    }
    inspected?.let { certificate ->
        CertificateInspectionDialog(
            inspection = certificate,
            current = network.rejectedCertificate == certificate,
            onDismiss = { inspected = null },
            onTrust = {
                if (network.rejectedCertificate == certificate && onTrustCertificate(certificate)) {
                    inspected = null
                } else {
                    actionError = if (network.rejectedCertificate != certificate) {
                        "This rejection is no longer current. Inspect the current certificate."
                    } else {
                        "Certificate trust could not be applied. Check the current rejection details and certificate validity."
                    }
                    inspected = null
                }
            },
        )
    }
    if (removeConfirmation) {
        AlertDialog(
            onDismissRequest = { removeConfirmation = false },
            title = { Text("Remove certificate trust?") },
            text = { Text("${network.name} will return to normal platform certificate verification. Private or self-signed certificates may be rejected on reconnection.") },
            confirmButton = {
                TextButton(onClick = {
                    if (!onRemoveCertificateTrust()) actionError = "Certificate trust could not be removed."
                    removeConfirmation = false
                }) { Text("Remove trust") }
            },
            dismissButton = { TextButton(onClick = { removeConfirmation = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CertificateInspectionDialog(
    inspection: CertificateInspection,
    current: Boolean,
    onDismiss: () -> Unit,
    onTrust: () -> Unit,
) {
    var confirmed by remember(inspection) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rejected server certificate") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Endpoint: ${inspection.host}:${inspection.port}", style = MaterialTheme.typography.titleSmall)
                Text("Verify the leaf SHA-256 fingerprint with the server operator through a separate trusted channel. Trust applies only to this endpoint and certificate; hostname and validity checks remain enabled.")
                inspection.certificates.forEachIndexed { index, certificate ->
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(if (index == 0) "Leaf certificate" else "Chain certificate ${index + 1}", style = MaterialTheme.typography.titleSmall)
                        Text("Subject: ${certificate.subject}")
                        Text("Issuer: ${certificate.issuer}")
                        Text("Valid from: ${Instant.ofEpochMilli(certificate.notBeforeMs)}")
                        Text("Valid until: ${Instant.ofEpochMilli(certificate.notAfterMs)}")
                        Text("Subject alternative names: ${certificate.subjectAltNames.joinToString().ifEmpty { "None" }}")
                        Text("SHA-256: ${certificate.sha256}")
                    }
                }
                if (!current) Text("This rejection is no longer current. Close and inspect the current certificate.", color = MaterialTheme.colorScheme.error)
                val consentLabel = "I independently verified this fingerprint and trust this certificate for ${inspection.host}:${inspection.port}."
                Checkbox(
                    checked = confirmed,
                    onCheckedChange = { confirmed = it },
                    enabled = current,
                    modifier = Modifier.semantics { contentDescription = consentLabel },
                )
                Text(consentLabel)
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust, enabled = confirmed && current && inspection.certificates.isNotEmpty()) {
                Text("Trust and reconnect")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
