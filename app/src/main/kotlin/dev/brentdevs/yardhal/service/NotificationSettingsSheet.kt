package dev.brentdevs.yardhal.service

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.brentdevs.yardhal.core.data.NotificationKind
import dev.brentdevs.yardhal.core.data.NotificationPreferences
import dev.brentdevs.yardhal.core.data.NotificationPreferencesStore
import dev.brentdevs.yardhal.core.data.NotificationPriority
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun NotificationSettingsSheet(store: NotificationPreferencesStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preferences by store.preferences.collectAsState()
    val scope = rememberCoroutineScope()
    val updates = remember(store) { Mutex() }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(context, revision) { Notifications.permissionGranted(context) }
    fun update(transform: (NotificationPreferences) -> NotificationPreferences) {
        scope.launch {
            updates.withLock {
                try {
                    withContext(Dispatchers.IO) { store.update(transform) }
                    error = null
                } catch (_: IOException) {
                    error = "Notification preferences could not be saved. Free local storage and try again."
                } catch (_: SecurityException) {
                    error = "Notification preference storage is unavailable. Your previous settings are unchanged."
                }
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notifications", style = MaterialTheme.typography.titleLarge)
            Text(if (granted) "Android notifications are allowed." else "Android notifications are blocked. Yardhal cannot alert you until permission and app notifications are enabled.")
            if (!granted) TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notification permission") }
            TextButton(onClick = { context.startActivity(Notifications.settingsIntent(context)) }) { Text("Android app notification settings") }
            Text("Muted, already-read, currently viewed, ignored and replayed messages do not alert. Direct messages use their own settings even when they mention you. Invitation taps ask before joining.", style = MaterialTheme.typography.bodySmall)
            for (kind in NotificationKind.entries) {
                HorizontalDivider()
                val settings = preferences.forKind(kind)
                val channel = remember(context, kind, settings.sound, settings.priority, revision) { Notifications.ensureEventChannel(context, kind, settings) }
                Text(Notifications.kindLabel(kind), style = MaterialTheme.typography.titleMedium)
                NotificationSwitch("Notify for ${Notifications.kindLabel(kind).lowercase(java.util.Locale.ROOT)}", settings.enabled) { value -> update { it.updateKind(kind) { option -> option.copy(enabled = value) } } }
                NotificationSwitch("Sound for ${Notifications.kindLabel(kind).lowercase(java.util.Locale.ROOT)}", settings.sound) { value -> update { it.updateKind(kind) { option -> option.copy(sound = value) } } }
                var expanded by remember(kind) { mutableStateOf(false) }
                Column {
                    TextButton(onClick = { expanded = true }) { Text("Priority: ${settings.priority.name.lowercase(java.util.Locale.ROOT)}") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        for (priority in NotificationPriority.entries) DropdownMenuItem(
                            text = { Text(when (priority) {
                                NotificationPriority.QUIET -> "Quiet — silent, no heads-up"
                                NotificationPriority.DEFAULT -> "Default — notification tray"
                                NotificationPriority.HIGH -> "High — allow heads-up"
                            }) },
                            onClick = {
                                expanded = false
                                update { it.updateKind(kind) { option -> option.copy(priority = priority) } }
                            },
                        )
                    }
                }
                Text("Active Android channel: importance ${channel.importance}, ${if (channel.sound == null) "silent" else "sound enabled"}. Android, Do Not Disturb and your channel overrides decide actual delivery.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { context.startActivity(Notifications.settingsIntent(context, kind, store.snapshot())) }) { Text("Android ${Notifications.kindLabel(kind).lowercase(java.util.Locale.ROOT)} channel settings") }
            }
            HorizontalDivider()
            Text("Android channels retain user overrides. Changing Yardhal sound or priority selects a distinct channel, not an update to an immutable channel. Quiet is always silent. Returning to a previous configuration restores its Android overrides.", style = MaterialTheme.typography.bodySmall)
            NotificationSwitch("Show message previews", preferences.showPreview) { value -> update { it.copy(showPreview = value) } }
            NotificationSwitch("Show sender avatars", preferences.showAvatars) { value -> update { it.copy(showAvatars = value) } }
            Text("Avatars use locally cached images or initials; notifications never fetch remote avatars. Message content is hidden on the lock screen unless Android settings override privacy.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
}

@Composable
private fun NotificationSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = value, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = null)
    }
}
