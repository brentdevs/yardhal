package dev.brentdevs.yardhal.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brentdevs.yardhal.core.data.AttachmentDestination
import dev.brentdevs.yardhal.core.data.AttachmentInsertion
import dev.brentdevs.yardhal.core.data.AttachmentStageStatus
import dev.brentdevs.yardhal.core.data.PhotoMetadataPolicy
import dev.brentdevs.yardhal.core.data.StagedAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
public fun AttachmentStagingPanel(
    manager: AttachmentStageManager,
    destination: AttachmentDestination?,
    onInsert: (AttachmentInsertion) -> Unit,
    onChooseDestination: (batchId: String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by manager.attachments.collectAsStateWithLifecycle()
    val visible = entries.filter { it.destination == null || it.destination == destination }
    val scope = rememberCoroutineScope()
    var insertionError by remember { mutableStateOf<String?>(null) }
    var keepId by remember { mutableStateOf<String?>(null) }
    if (visible.isNotEmpty()) {
        Column(modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Staged attachments · never sent automatically", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onOpenSettings) { Text("Upload provider, size and privacy settings") }
            visible.groupBy { it.batchId }.forEach { (batch, attachments) ->
                val first = attachments.first()
                Text("Destination: ${first.destination?.label ?: "Choose a conversation"}")
                if (first.destination == null) TextButton(onClick = { onChooseDestination(batch) }) { Text("Choose destination") }
                var caption by remember(batch) { mutableStateOf(first.caption) }
                OutlinedTextField(value = caption, onValueChange = { caption = it; manager.updateCaption(batch, it) },
                    label = { Text("Caption (inserted into draft, not sent)") }, modifier = Modifier.fillMaxWidth())
                attachments.forEach { attachment ->
                    AttachmentStageCard(attachment, manager, onKeepMetadata = { keepId = attachment.id }) {
                        scope.launch {
                            try {
                                val insertion = manager.prepareInsertion(listOf(attachment.id))
                                if (insertion != null) { insertionError = null; onInsert(insertion) }
                                else insertionError = "This uploaded attachment has no valid destination. Remove it and select/share it again."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { insertionError = "The destination or local staging storage is unavailable. Select/share this attachment into an available conversation." }
                        }
                    }
                }
                val completed = attachments.filter { it.uploaded != null }
                if (completed.size > 1) TextButton(onClick = {
                    scope.launch {
                        try {
                            manager.prepareInsertion(completed.map { it.id })?.let(onInsert)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { insertionError = "Unable to capture this destination's upload draft. Check local storage and destination availability." }
                    }
                }) { Text("Insert ${completed.size} uploaded URLs into draft") }
                HorizontalDivider()
            }
            insertionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    val consent = visible.firstOrNull { it.status == AttachmentStageStatus.AWAITING_CONSENT }
    if (consent != null) AlertDialog(
        onDismissRequest = { manager.cancel(consent.id) },
        title = { Text("Allow uploads to this provider?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${consent.providerLabel} · ${consent.providerOrigin}")
            Text(consent.authenticationDisclosure.orEmpty())
            Text("Destination: ${consent.destination?.label}")
            Text(if (consent.metadataPolicy == PhotoMetadataPolicy.STRIP) "Photo metadata/location stripped where safely supported; JPEG orientation retained." else "Keep metadata selected: this file may disclose location, device and personal information.")
            Text("Files leave your device. This consent is remembered for this provider origin and authentication policy. Completed uploads automatically place their URLs in the original destination's draft; sending is a separate action.")
        } },
        confirmButton = { TextButton(onClick = { manager.upload(consent.id, consent.consentKey) }) { Text("Allow and upload") } },
        dismissButton = { TextButton(onClick = { manager.cancel(consent.id) }) { Text("Cancel") } },
    )
    keepId?.let { id -> AlertDialog(
        onDismissRequest = { keepId = null },
        title = { Text("Keep original metadata?") },
        text = { Text("The original file may include location, device information and personal metadata. Animation will not be flattened. Only this attachment uses Keep metadata; the global default remains unchanged.") },
        confirmButton = { TextButton(onClick = { keepId = null; manager.keepMetadataAndRetry(id) }) { Text("Keep metadata and restage") } },
        dismissButton = { TextButton(onClick = { keepId = null }) { Text("Keep private") } },
    ) }
}

@Composable
private fun AttachmentStageCard(attachment: StagedAttachment, manager: AttachmentStageManager, onKeepMetadata: () -> Unit, onInsert: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${attachment.name} · ${attachment.mimeType} · ${attachment.sizeBytes} bytes", style = MaterialTheme.typography.bodyMedium)
        Text(if (attachment.metadataPolicy == PhotoMetadataPolicy.STRIP) "Privacy: strip photo metadata/location; preserve supported orientation" else "Privacy: KEEP metadata/location (explicit)", style = MaterialTheme.typography.bodySmall)
        Text(attachment.status.name.lowercase().replace('_', ' '))
        attachment.providerOrigin?.let { Text("Upload: ${attachment.providerLabel} · $it", style = MaterialTheme.typography.bodySmall) }
        attachment.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when (attachment.status) {
            AttachmentStageStatus.COPYING -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { manager.cancel(attachment.id) }) { Text("Cancel staging") }
            }
            AttachmentStageStatus.UPLOADING -> {
                LinearProgressIndicator(progress = { if (attachment.sizeBytes == 0L) 0f else (attachment.progressBytes.toFloat() / attachment.sizeBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${attachment.progressBytes}/${attachment.sizeBytes} bytes uploaded")
                TextButton(onClick = { manager.cancel(attachment.id) }) { Text("Cancel upload") }
            }
            AttachmentStageStatus.READY -> Button(enabled = attachment.destination != null, onClick = { manager.upload(attachment.id) }) { Text("Upload to selected provider") }
            AttachmentStageStatus.UPLOADED, AttachmentStageStatus.INSERTED -> {
                Text(attachment.uploaded?.url.orEmpty())
                Button(onClick = onInsert) { Text(if (attachment.status == AttachmentStageStatus.INSERTED) "Insert URL again" else "Insert URL into draft") }
                Text("Retained until explicit send or removal; no reupload needed.", style = MaterialTheme.typography.bodySmall)
            }
            AttachmentStageStatus.ERROR, AttachmentStageStatus.CANCELLED, AttachmentStageStatus.INTERRUPTED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { manager.retry(attachment.id) }) { Text("Retry") }
                if (attachment.metadataPolicy == PhotoMetadataPolicy.STRIP && attachment.sanitizationFailed) TextButton(onClick = onKeepMetadata) { Text("Keep metadata explicitly") }
            }
            AttachmentStageStatus.AWAITING_CONSENT -> Text("Awaiting provider disclosure confirmation")
        }
        TextButton(onClick = { manager.remove(attachment.id) }) { Text("Remove staged attachment") }
    }
}
