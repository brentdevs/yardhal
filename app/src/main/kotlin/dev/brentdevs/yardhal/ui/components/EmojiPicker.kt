package dev.brentdevs.yardhal.ui.components

import android.graphics.Paint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.core.data.RecentEmojiStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

public val LocalRecentEmojiStore = staticCompositionLocalOf<RecentEmojiStore?> { null }

internal data class EmojiEntry(val emoji: String, val name: String, val category: String)

internal fun parseEmojiCatalog(lines: Sequence<String>, supported: (String) -> Boolean): List<EmojiEntry> {
    var category = ""
    return lines.mapNotNull { line ->
        if (line.startsWith("# group: ")) category = line.removePrefix("# group: ")
        if (!line.substringBefore('#').substringAfter(';', "").trim().equals("fully-qualified")) return@mapNotNull null
        val points = line.substringBefore(';').trim().split(' ').filter { it.isNotBlank() }
        val emoji = buildString { points.forEach { appendCodePoint(it.toInt(16)) } }
        val name = line.substringAfter("# ").substringAfter(' ').substringAfter(' ')
        EmojiEntry(emoji, name, category).takeIf { supported(emoji) }
    }.toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun EmojiPicker(onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val store = LocalRecentEmojiStore.current
    val recent = store?.recent?.collectAsState()?.value.orEmpty()
    val scope = rememberCoroutineScope()
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<List<EmojiEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    LaunchedEffect(context) {
        try {
            catalog = withContext(Dispatchers.IO) {
                val paint = Paint()
                context.assets.open("emoji-test-15.1.txt").bufferedReader().use { reader ->
                    parseEmojiCatalog(reader.lineSequence(), paint::hasGlyph)
                }
            }
        } catch (failure: java.io.IOException) {
            error = failure.message ?: "Emoji catalog unavailable"
        }
    }
    val entries = catalog.orEmpty()
    val results = remember(entries, query, category, recent) {
        val selected = if (category == "Recent") recent.mapNotNull { emoji -> entries.firstOrNull { it.emoji == emoji } } else entries
        selected.filter { entry ->
            (category == "All" || category == "Recent" || category == entry.category) &&
                (query.isBlank() || entry.name.contains(query.trim(), true) || entry.emoji == query.trim())
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose emoji", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, { query = it }, label = { Text("Search emoji names") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("All", "Recent") + entries.map { it.category }.distinct()) { name ->
                    FilterChip(selected = name == category, onClick = { category = name }, label = { Text(name) })
                }
            }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                error != null -> Text("Emoji selection unavailable: $error")
                catalog == null -> Text("Loading supported emoji…")
                results.isEmpty() -> Text("No supported emoji match this selection")
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(52.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), contentPadding = PaddingValues(4.dp)) {
                items(results, key = { it.emoji }) { entry ->
                    Text(entry.emoji, style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { contentDescription = entry.name }.clickable(enabled = !saving) {
                            saving = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { store?.record(entry.emoji) }
                                    onSelect(entry.emoji)
                                } catch (failure: java.io.IOException) {
                                    saveError = "Could not save recent emoji: ${failure.message}"
                                } catch (failure: SecurityException) {
                                    saveError = "Recent emoji storage is inaccessible"
                                } finally {
                                    saving = false
                                }
                            }
                        }.padding(10.dp))
                }
            }
            Text("Unicode Emoji 15.1 · only emoji supported by this device", style = MaterialTheme.typography.labelSmall)
        }
    }
}
