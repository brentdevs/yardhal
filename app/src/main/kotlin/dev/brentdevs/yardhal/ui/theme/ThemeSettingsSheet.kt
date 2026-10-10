package dev.brentdevs.yardhal.ui.theme

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.ThemeColors
import dev.brentdevs.yardhal.core.data.ThemeDefinition
import dev.brentdevs.yardhal.core.data.ThemeEditorDraft
import dev.brentdevs.yardhal.core.data.ThemeFileParser
import dev.brentdevs.yardhal.core.data.ThemeLibraryStore
import dev.brentdevs.yardhal.core.data.ThemeShareLink
import dev.brentdevs.yardhal.core.data.ThemeVariants
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ThemeSettingsSheet(
    store: ThemeLibraryStore,
    onDismiss: () -> Unit,
    conversation: ConversationRef? = null,
    incomingLink: String? = null,
    onIncomingLinkConsumed: (String) -> Unit,
) {
    val state by store.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorText by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var link by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(incomingLink) {
        if (incomingLink != null) {
            link = incomingLink.take(ThemeShareLink.MAX_LENGTH + 1)
            error = if (incomingLink.length > ThemeShareLink.MAX_LENGTH) "Theme link exceeds 24 KiB" else null
            onIncomingLinkConsumed(incomingLink)
        }
    }
    var accentText by rememberSaveable(conversation?.networkId, conversation?.normalizedTarget) {
        mutableStateOf(conversation?.let { state.accent(it) }?.let {
            "#${(it and 0xFFFFFF).toString(16).padStart(6, '0')}"
        }.orEmpty())
    }
    fun perform(operation: () -> Unit, success: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
                error = null
                success()
            } catch (failure: IllegalArgumentException) {
                error = failure.message ?: "Theme is invalid"
            } catch (_: IOException) {
                error = "Theme could not be read or its durable save confirmed. Your draft is retained; check storage before saving again."
            } catch (_: SecurityException) {
                error = "Theme storage access failed. Your draft is retained; restore access before saving again."
            } finally {
                busy = false
            }
        }
    }
    fun addVariant(dark: Boolean) {
        try {
            val variants = ThemeFileParser.importTheme(editorText)
            require((if (dark) variants.dark else variants.light) == null) {
                "This draft already has a ${if (dark) "dark" else "light"} variant; edit its color section below"
            }
            val colors = if (dark) {
                requireNotNull(ThemeFileParser.parse(ThemeFileParser.SAMPLE_TOML)).colors
            } else {
                ThemeColors(0xFFFAFAFAL, 0xFF00639AL, 0xFF526069L, 0xFF63577EL, 0xFFF0F4F7L)
            }
            val definition = ThemeDefinition(variants.name, dark, colors)
            editorText = ThemeFileParser.export(
                if (dark) variants.copy(dark = definition) else variants.copy(light = definition),
            )
            error = null
        } catch (failure: IllegalArgumentException) {
            error = failure.message ?: "Fix the theme draft before adding a variant"
        }
    }
    fun editImported(variants: ThemeVariants) {
        editorId = null
        editorText = ThemeFileParser.export(variants)
        editorVisible = true
        error = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    val variants = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use {
                            it.readNBytes(ThemeFileParser.MAX_BYTES + 1)
                        } ?: throw IOException("Document could not be opened")
                        require(bytes.size <= ThemeFileParser.MAX_BYTES) { "Theme file exceeds 16 KiB; choose a smaller file" }
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                        ThemeFileParser.importTheme(text)
                    }
                    editImported(variants)
                } catch (failure: IllegalArgumentException) {
                    error = failure.message ?: "Theme is invalid"
                } catch (_: java.nio.charset.CharacterCodingException) {
                    error = "Theme file is not valid UTF-8. Export it as UTF-8 and retry."
                } catch (_: IOException) {
                    error = "Theme file could not be read. Choose an available document and retry."
                } catch (_: SecurityException) {
                    error = "Document access was denied. Choose the file again to grant access."
                } finally {
                    busy = false
                }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val text = editorText
            perform({
                val normalized = ThemeFileParser.export(ThemeFileParser.importTheme(text))
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(normalized.toByteArray(Charsets.UTF_8)) }
                    ?: throw IOException("Document could not be written")
            })
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Themes", style = MaterialTheme.typography.titleLarge)
            Text("Theme variants follow the device light/dark setting. A single variant is used in both modes. Share links carry the theme locally, without an account or server.", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = { perform({ store.select(null) }) }) {
                Text(if (state.selectedId == null) "System colors · selected" else "Use system colors")
            }
            for (theme in state.themes) {
                Text(theme.variants.name + if (state.selectedId == theme.id) " · selected" else "", style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(enabled = !busy, onClick = { perform({ store.select(theme.id) }) }) { Text("Apply") }
                    TextButton(enabled = !busy, onClick = {
                        editorId = theme.id
                        editorText = ThemeFileParser.export(theme.variants)
                        editorVisible = true
                        error = null
                    }) { Text("Edit") }
                    TextButton(enabled = !busy, onClick = { perform({ store.duplicate(theme.id) }) }) { Text("Duplicate") }
                }
                Row {
                    TextButton(enabled = !busy, onClick = {
                        val shareLink = ThemeShareLink.encode(theme.variants)
                        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareLink)
                        context.startActivity(Intent.createChooser(intent, "Share ${theme.variants.name}"))
                    }) { Text("Share link") }
                    TextButton(enabled = !busy, onClick = { deletingId = theme.id }) { Text("Delete") }
                }
            }
            TextButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("text/*", "application/toml", "application/octet-stream")) }) {
                Text("Import theme file")
            }
            OutlinedTextField(value = link, onValueChange = { link = it.take(ThemeShareLink.MAX_LENGTH + 1) },
                label = { Text("Portable theme link") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
            TextButton(enabled = !busy && link.isNotBlank(), onClick = {
                try { editImported(ThemeShareLink.decode(link.trim())) }
                catch (failure: IllegalArgumentException) { error = failure.message ?: "Invalid theme link" }
            }) { Text("Import link into editor") }
            if (conversation != null) {
                Text("Accent for ${conversation.rawTarget}", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(accentText, onValueChange = { accentText = it }, label = { Text("Opaque accent (#RRGGBB)") },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
                Row {
                    TextButton(enabled = !busy, onClick = {
                        val draft = accentText
                        perform({ store.setAccent(conversation, ThemeFileParser.parseColor(draft.trim())) })
                    }) { Text("Save accent") }
                    TextButton(enabled = !busy, onClick = { perform({ store.setAccent(conversation, null) }, { accentText = "" }) }) { Text("Reset accent") }
                }
            }
            if (editorVisible) {
                Text(if (editorId == null) "Imported theme draft" else "Edit theme draft", style = MaterialTheme.typography.titleMedium)
                Text("Edit the name and #RRGGBB colors below. Use [light.colors] and [dark.colors] for separate variants. Saving applies edits to this library entry; Apply selects it.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(editorText, onValueChange = { editorText = it.take(ThemeFileParser.MAX_BYTES + 1) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Theme TOML") }, minLines = 8, enabled = !busy)
                Row {
                    TextButton(enabled = !busy, onClick = { addVariant(false) }) { Text("Add light variant") }
                    TextButton(enabled = !busy, onClick = { addVariant(true) }) { Text("Add dark variant") }
                }
                Row {
                    Button(enabled = !busy, onClick = {
                        val draft = ThemeEditorDraft(editorId, editorText)
                        busy = true
                        scope.launch {
                            try {
                                val savedId = withContext(Dispatchers.IO) { draft.save(store) }
                                error = draft.error
                                if (savedId != null) editorVisible = false
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Save theme") }
                    TextButton(enabled = !busy, onClick = { exportLauncher.launch("yardhal-theme.toml") }) { Text("Export file") }
                    TextButton(enabled = !busy, onClick = { editorVisible = false }) { Text("Cancel") }
                }
            }
            if (busy) Text("Saving or reading…")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
    deletingId?.let { id ->
        val name = state.themes.firstOrNull { it.id == id }?.variants?.name.orEmpty()
        AlertDialog(onDismissRequest = { deletingId = null }, title = { Text("Delete $name?") },
            text = { Text("This removes the library entry. Deleting the selected theme returns to system colors; conversation accents remain.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                perform({ store.delete(id) }, { deletingId = null; if (editorId == id) editorVisible = false })
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } })
    }
}
