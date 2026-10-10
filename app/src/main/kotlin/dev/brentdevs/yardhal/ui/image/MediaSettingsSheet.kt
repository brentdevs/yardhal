package dev.brentdevs.yardhal.ui.image

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.brentdevs.yardhal.core.data.MediaPreferences
import dev.brentdevs.yardhal.core.data.MediaPreferencesStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun MediaSettingsSheet(environment: MediaEnvironment, onDismiss: () -> Unit) {
    val preferences by environment.preferences.preferences.collectAsState()
    val revision by environment.cacheRevision.collectAsState()
    val retentionError by environment.retentionError.collectAsState()
    val scope = rememberCoroutineScope()
    val updates = remember(environment) { Mutex() }
    var error by remember { mutableStateOf<String?>(null) }
    var usage by remember { mutableStateOf(ImageCacheUsage(0, 0)) }
    LaunchedEffect(environment, preferences.mediaCacheBytes, preferences.avatarCacheBytes, revision) {
        usage = withContext(Dispatchers.IO) {
            environment.loader.configureBudgets(preferences.mediaCacheBytes, preferences.avatarCacheBytes)
            environment.loader.usage()
        }
    }
    fun update(transform: (MediaPreferences) -> MediaPreferences) {
        scope.launch {
            updates.withLock {
                try {
                    withContext(Dispatchers.IO) { environment.preferences.update(transform) }
                    error = null
                } catch (_: IOException) {
                    error = "Media preferences could not be saved. Try again when storage is available."
                } catch (_: SecurityException) {
                    error = "Media preferences storage is unavailable."
                }
            }
        }
    }
    fun clear(category: ImageCacheCategory) {
        scope.launch {
            try {
                environment.clearCache(category)
                error = null
            } catch (_: IOException) {
                error = "Some cached files could not be removed. Try clearing the cache again."
            } catch (_: SecurityException) {
                error = "Cache storage is unavailable."
            }
            usage = withContext(Dispatchers.IO) { environment.loader.usage() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Media and privacy", style = MaterialTheme.typography.titleLarge)
            Text("Previews contact the linked host and reveal your IP address. Hidden images are not fetched or probed. Videos never autoplay.", style = MaterialTheme.typography.bodySmall)
            MediaSwitch("Automatically load images and discover image links", preferences.autoLoadImages) { value -> update { it.copy(autoLoadImages = value) } }
            MediaSwitch("Load avatars and network icons", preferences.loadAvatars) { value -> update { it.copy(loadAvatars = value) } }
            MediaSwitch("Allow video playback after pressing Play", preferences.enableVideos) { value -> update { it.copy(enableVideos = value) } }
            MediaSwitch("Animate GIFs and animated images", preferences.animateImages) { value -> update { it.copy(animateImages = value) } }
            MediaSwitch("Reduce motion (show still images)", preferences.reducedMotion) { value -> update { it.copy(reducedMotion = value) } }
            Text("System reduced-motion settings also disable animation. Returning to a video requires pressing Play again.", style = MaterialTheme.typography.bodySmall)
            CacheBudget("Images and GIFs", preferences.mediaCacheBytes, usage.mediaBytes) { value -> update { it.copy(mediaCacheBytes = value) } }
            TextButton(onClick = { clear(ImageCacheCategory.MEDIA) }) { Text("Clear image and GIF cache") }
            CacheBudget("Avatars", preferences.avatarCacheBytes, usage.avatarBytes) { value -> update { it.copy(avatarCacheBytes = value) } }
            TextButton(onClick = { clear(ImageCacheCategory.AVATAR) }) { Text("Clear avatar cache") }
            Text("Videos use bounded temporary files removed when playback closes. Media bytes and temporary playback files are not synced.", style = MaterialTheme.typography.bodySmall)
            Text("Reveal and hide retention normally keeps at most ${MediaPreferencesStore.MAX_REVEAL_ENTRIES} choices, protecting visible media and the ${MediaPreferencesStore.RECENT_REVEAL_ENTRIES} latest changes. Older revealed choices expire before older hidden choices. Expired choices use the global setting and may load automatically.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            retentionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
}

@Composable
private fun MediaSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun CacheBudget(label: String, budget: Long, used: Long, onChange: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("$label cache: ${formatMediaSize(used)} used", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { expanded = true }) { Text("$label budget: ${formatMediaSize(budget)}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (mib in listOf(0, 8, 16, 32, 64, 128, 256, 512)) {
                DropdownMenuItem(text = { Text(if (mib == 0) "No disk cache" else "$mib MiB") }, onClick = {
                    expanded = false
                    onChange(mib.toLong() * 1024 * 1024)
                })
            }
        }
    }
}

internal fun formatMediaSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MiB", bytes.toDouble() / (1024 * 1024))
    bytes >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f KiB", bytes.toDouble() / 1024)
    else -> "$bytes B"
}
