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
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun MediaSettingsSheet(environment: MediaEnvironment, onDismiss: () -> Unit) {
    val preferences by environment.preferences.preferences.collectAsState()
    val revision by environment.cacheRevision.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var usage by remember { mutableStateOf(ImageCacheUsage(0, 0)) }
    LaunchedEffect(environment, preferences.mediaCacheBytes, preferences.avatarCacheBytes, revision) {
        usage = withContext(Dispatchers.IO) {
            environment.loader.configureBudgets(preferences.mediaCacheBytes, preferences.avatarCacheBytes)
            environment.loader.usage()
        }
    }
    fun update(value: MediaPreferences) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { environment.preferences.update(value) }
                error = null
            } catch (_: IOException) {
                error = "Media preferences could not be saved. Try again when storage is available."
            } catch (_: SecurityException) {
                error = "Media preferences storage is unavailable."
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
            MediaSwitch("Automatically load images and discover image links", preferences.autoLoadImages) { update(preferences.copy(autoLoadImages = it)) }
            MediaSwitch("Load avatars and network icons", preferences.loadAvatars) { update(preferences.copy(loadAvatars = it)) }
            MediaSwitch("Allow video playback after pressing Play", preferences.enableVideos) { update(preferences.copy(enableVideos = it)) }
            MediaSwitch("Animate GIFs and animated images", preferences.animateImages) { update(preferences.copy(animateImages = it)) }
            MediaSwitch("Reduce motion (show still images)", preferences.reducedMotion) { update(preferences.copy(reducedMotion = it)) }
            Text("System reduced-motion settings also disable animation. Returning to a video requires pressing Play again.", style = MaterialTheme.typography.bodySmall)
            CacheBudget("Images and GIFs", preferences.mediaCacheBytes, usage.mediaBytes) { update(preferences.copy(mediaCacheBytes = it)) }
            TextButton(onClick = { clear(ImageCacheCategory.MEDIA) }) { Text("Clear image and GIF cache") }
            CacheBudget("Avatars", preferences.avatarCacheBytes, usage.avatarBytes) { update(preferences.copy(avatarCacheBytes = it)) }
            TextButton(onClick = { clear(ImageCacheCategory.AVATAR) }) { Text("Clear avatar cache") }
            Text("Videos use bounded temporary files removed when playback closes. Media bytes and temporary playback files are not synced. Reveal and hide choices are saved per message.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
