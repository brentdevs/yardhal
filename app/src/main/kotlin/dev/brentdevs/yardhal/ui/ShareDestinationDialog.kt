package dev.brentdevs.yardhal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.coordinator.UiNetwork
import dev.brentdevs.yardhal.core.data.AttachmentDestination
import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.StagedAttachment

@Composable
internal fun ShareDestinationDialog(
    batchId: String,
    staged: List<StagedAttachment>,
    buffers: Collection<ConversationBuffer>,
    networks: List<UiNetwork>,
    onChoose: (AttachmentDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(batchId) { mutableStateOf("") }
    val networkNames = networks.associate { it.id to it.name }
    val choices = buffers.filter {
        it.ref.kind != ConversationKind.SERVER && it.ref.networkId in networkNames &&
            (query.isBlank() || it.displayName.contains(query, true) || networkNames[it.ref.networkId].orEmpty().contains(query, true))
    }.sortedWith(compareBy({ networkNames[it.ref.networkId] }, { it.displayName }))
    val incoming = staged.filter { it.batchId == batchId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose attachment destination") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${incoming.size} attachment(s). Nothing is uploaded or sent until you confirm in the composer.")
                incoming.firstOrNull()?.caption?.takeIf { it.isNotBlank() }?.let {
                    Text("Caption: $it", style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
                OutlinedTextField(query, { query = it }, label = { Text("Find conversation or network") }, singleLine = true)
                if (choices.isEmpty()) {
                    Text("No matching conversations. Open or join the destination, then choose it from staged attachments.")
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(choices, key = { it.key }) { buffer ->
                        val label = "${networkNames[buffer.ref.networkId]} · ${buffer.displayName}"
                        TextButton(onClick = { onChoose(AttachmentDestination(buffer.ref.networkId, buffer.key, label)) }) {
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Choose later · keep staged files") } },
    )
}
