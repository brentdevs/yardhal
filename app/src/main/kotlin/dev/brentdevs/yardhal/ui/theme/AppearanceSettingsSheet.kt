package dev.brentdevs.yardhal.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.core.data.ChatAppearancePreferences
import dev.brentdevs.yardhal.core.data.ChatAppearanceStore
import dev.brentdevs.yardhal.core.data.MessageFont
import dev.brentdevs.yardhal.core.data.NicknameSuggestionOrder
import dev.brentdevs.yardhal.core.data.TimestampFormat
import dev.brentdevs.yardhal.core.data.TimestampPosition
import dev.brentdevs.yardhal.core.data.TimestampStyle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun AppearanceSettingsSheet(
    store: ChatAppearanceStore,
    onDismiss: () -> Unit,
    onOpenThemes: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenUploads: () -> Unit,
    onOpenRelays: () -> Unit,
    relaysAvailable: Boolean,
) {
    val preferences by store.preferences.collectAsState()
    val scope = rememberCoroutineScope()
    val updates = remember(store) { Mutex() }
    var error by remember { mutableStateOf<String?>(null) }
    var textScale by remember(preferences.textScale) { mutableStateOf(preferences.textScale) }
    fun update(transform: (ChatAppearancePreferences) -> ChatAppearancePreferences) {
        scope.launch {
            updates.withLock {
                try {
                    withContext(Dispatchers.IO) { store.update(transform) }
                    error = null
                } catch (_: IOException) {
                    error = "Appearance durable save could not be confirmed. Last settings remain active; check storage before saving again."
                } catch (_: SecurityException) {
                    error = "Appearance storage is unavailable. Previous settings remain active."
                }
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Appearance", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onOpenThemes) { Text("Themes, import and conversation accent") }
            AppearanceSwitch("Compact message spacing", preferences.compact) { value -> update { it.copy(compact = value) } }
            Text("Message text size: ${(textScale * 100).toInt()}%")
            Slider(value = textScale, onValueChange = { textScale = it }, valueRange = 0.85f..1.25f,
                onValueChangeFinished = { val value = textScale; update { it.copy(textScale = value) } })
            AppearanceChoice("Timestamp format", preferences.timestampFormat, TimestampFormat.entries,
                { when (it) {
                    TimestampFormat.TIME_24 -> "24-hour (14:05)"
                    TimestampFormat.TIME_12 -> "12-hour (2:05 PM)"
                    TimestampFormat.TIME_SECONDS -> "With seconds (14:05:09)"
                    TimestampFormat.ISO_DATE_TIME -> "ISO date and time"
                } }) { value -> update { it.copy(timestampFormat = value) } }
            AppearanceChoice("Timestamp style", preferences.timestampStyle, TimestampStyle.entries,
                { if (it == TimestampStyle.NORMAL) "Normal" else "Muted" }) { value -> update { it.copy(timestampStyle = value) } }
            AppearanceChoice("Timestamp position", preferences.timestampPosition, TimestampPosition.entries,
                { when (it) { TimestampPosition.INLINE -> "Inline"; TimestampPosition.ABOVE -> "Above"; TimestampPosition.BELOW -> "Below" } }) {
                value -> update { it.copy(timestampPosition = value) }
            }
            AppearanceChoice("Message font", preferences.font, MessageFont.entries,
                { when (it) { MessageFont.SYSTEM -> "System"; MessageFont.SANS -> "Sans serif"; MessageFont.SERIF -> "Serif"; MessageFont.MONOSPACE -> "Monospace" } }) {
                value -> update { it.copy(font = value) }
            }
            AppearanceChoice("Nickname suggestions", preferences.nicknameSuggestionOrder, NicknameSuggestionOrder.entries,
                { when (it) { NicknameSuggestionOrder.ROLE -> "Channel role"; NicknameSuggestionOrder.ALPHABETICAL -> "Alphabetical"; NicknameSuggestionOrder.RECENT -> "Recent speakers" } }) {
                value -> update { it.copy(nicknameSuggestionOrder = value) }
            }
            AppearanceSwitch("Show avatars", preferences.showAvatars) { value -> update { it.copy(showAvatars = value) } }
            AppearanceSwitch("Show unread counts", preferences.showUnreadCounts) { value -> update { it.copy(showUnreadCounts = value) } }
            AppearanceSwitch("System dynamic colors (when no theme is selected)", preferences.dynamicColor) { value -> update { it.copy(dynamicColor = value) } }
            AppearanceSwitch("Black background in dark themes", preferences.amoledDark) { value -> update { it.copy(amoledDark = value) } }
            TextButton(onClick = onOpenMedia) { Text("Media and privacy") }
            TextButton(onClick = onOpenUploads) { Text("Upload settings") }
            TextButton(onClick = onOpenRelays, enabled = relaysAvailable) { Text("Relay settings") }
            if (!relaysAvailable) Text("Open a conversation to edit its network's relay settings.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
}

@Composable
private fun AppearanceSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> AppearanceChoice(label: String, selected: T, choices: List<T>, describe: (T) -> String, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = true }) { Text("$label: ${describe(selected)}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            for (choice in choices) DropdownMenuItem(text = { Text(describe(choice)) }, onClick = {
                expanded = false
                onChange(choice)
            })
        }
    }
}
