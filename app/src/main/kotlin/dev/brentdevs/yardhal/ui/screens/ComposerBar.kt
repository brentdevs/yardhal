package dev.brentdevs.yardhal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable

private val COMMANDS = listOf(
    "/join", "/part", "/msg", "/query", "/me", "/nick", "/topic", "/whois", "/who",
    "/away", "/back", "/monitor", "/ignore", "/unignore", "/mode", "/help",
)

@Composable
public fun ComposerBar(
    enabled: Boolean,
    members: List<String>,
    channels: List<String> = emptyList(),
    onAttach: () -> Unit = {},
    initialDraft: String? = null,
    onSend: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(initialDraft, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialDraft.orEmpty()))
    }
    var sendError by rememberSaveable { mutableStateOf(false) }

    fun tokenStart(): Int {
        val cursor = draft.selection.start.coerceIn(0, draft.text.length)
        return draft.text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), cursor - 1) + 1
    }

    fun suggestions(): List<String> {
        val cursor = draft.selection.start.coerceIn(0, draft.text.length)
        val start = tokenStart()
        val raw = draft.text.substring(start, cursor)
        if (raw.isEmpty()) return emptyList()
        val nickCompletion = !(start == 0 && raw.startsWith("/")) && !raw.startsWith("#") && !raw.startsWith("&")
        val candidates = when {
            start == 0 && raw.startsWith("/") -> COMMANDS
            raw.startsWith("#") || raw.startsWith("&") -> channels
            else -> members
        }
        val token = if (nickCompletion) raw.removePrefix("@") else raw
        if (token.isEmpty()) return emptyList()
        return candidates.filter { it.startsWith(token, ignoreCase = true) && !it.equals(token, ignoreCase = true) }
            .take(3)
    }

    fun complete(candidate: String) {
        val start = tokenStart()
        val cursor = draft.selection.start.coerceIn(start, draft.text.length)
        val end = draft.text.indexOfAny(charArrayOf(' ', '\n', '\t'), cursor).let {
            if (it < 0) draft.text.length else it
        }
        val suffix = if (start == 0 && !candidate.startsWith("/") && !candidate.startsWith("#") && !candidate.startsWith("&")) ": " else " "
        val replacement = candidate + suffix
        val updated = draft.text.replaceRange(start, end, replacement)
        draft = TextFieldValue(updated, TextRange(start + replacement.length))
    }

    fun submit() {
        val text = draft.text.trimEnd()
        if (text.isEmpty()) return
        if (onSend(text)) {
            draft = TextFieldValue("")
            sendError = false
        } else {
            sendError = true
        }
    }

    Column(modifier = modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        val candidates = suggestions()
        if (candidates.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
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
                onValueChange = {
                    draft = it
                    sendError = false
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message…") },
                supportingText = if (enabled && !sendError) null else {
                    { Text(if (sendError) "Could not send · draft retained" else "Offline · draft saved until reconnection") }
                },
                maxLines = 4,
            )
            IconButton(onClick = onAttach, enabled = enabled) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = "Attach file",
                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = { submit() }, enabled = enabled && draft.text.isNotBlank()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
