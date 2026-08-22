package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
public fun ComposerBar(
    enabled: Boolean,
    members: List<String>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }

    fun suggestions(): List<String> {
        if (draft.isEmpty() || draft.contains(' ')) return emptyList()
        val token = draft.removePrefix("@")
        if (token.isEmpty()) return emptyList()
        return members
            .filter { it.startsWith(token, ignoreCase = true) && !it.equals(draft, ignoreCase = true) }
            .take(3)
    }

    fun complete(candidate: String) {
        draft = if (draft.startsWith("@")) "$candidate: " else "$candidate "
    }

    fun submit() {
        val text = draft.trimEnd()
        if (text.isEmpty()) return
        onSend(text)
        draft = ""
    }

    Column(modifier = modifier.fillMaxWidth()) {
        val candidates = suggestions()
        if (candidates.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                candidates.forEach { candidate ->
                    androidx.compose.material3.TextButton(onClick = { complete(candidate) }) {
                        Text(candidate, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (enabled) "Message…" else "Offline") },
                enabled = enabled,
                maxLines = 4,
            )
            IconButton(onClick = { submit() }, enabled = enabled && draft.isNotBlank()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
