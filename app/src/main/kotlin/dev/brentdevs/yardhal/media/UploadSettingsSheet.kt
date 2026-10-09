package dev.brentdevs.yardhal.media

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.PhotoMetadataPolicy
import dev.brentdevs.yardhal.core.data.UploadAuthentication
import dev.brentdevs.yardhal.core.data.UploadProvider
import dev.brentdevs.yardhal.core.data.UploadSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun UploadSettingsSheet(settings: UploadSettingsStore, networks: List<NetworkConfig>, initialNetworkId: String? = null, onDismiss: () -> Unit) {
    var preferences by remember(settings) { mutableStateOf(settings.snapshot()) }
    var networkId by remember { mutableStateOf(initialNetworkId) }
    var maximumMiB by remember { mutableStateOf((preferences.maximumBytes / (1024 * 1024)).toString()) }
    var policy by remember { mutableStateOf(preferences.photoMetadataPolicy) }
    var editing by remember { mutableStateOf<UploadProvider?>(null) }
    var label by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("https://") }
    var replaceAuthentication by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun applyChange(change: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { change() }
                preferences = settings.snapshot()
                error = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Unable to save upload settings. Check the endpoint URL, HTTPS authentication, provider scope and local storage." }
            finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Upload providers and privacy", style = MaterialTheme.typography.titleLarge)
            Text("Selection is deterministic: this network's explicit selection, otherwise global selection, otherwise advertised FILEHOST. A missing selected provider fails; it never silently falls back. Configured providers never receive IRC credentials.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = networkId == null, onClick = { networkId = null }, label = { Text("Global") })
                networks.forEach { network -> FilterChip(selected = networkId == network.id, onClick = { networkId = network.id }, label = { Text(network.name) }) }
            }
            val selected = if (networkId == null) preferences.globalProviderId else preferences.networkProviderIds[networkId]
            FilterChip(selected = selected == null, enabled = !busy, onClick = { val target = networkId; applyChange { settings.selectProvider(target, null) } },
                label = { Text(if (networkId == null) "Default: advertised FILEHOST" else "Inherit global selection") })
            FilterChip(selected = selected == UploadSettingsStore.NEGOTIATED_PROVIDER, enabled = !busy, onClick = { val target = networkId; applyChange { settings.selectProvider(target, UploadSettingsStore.NEGOTIATED_PROVIDER) } }, label = { Text("Explicit advertised FILEHOST") })
            preferences.providers.filter { it.networkId == null || it.networkId == networkId }.forEach { provider ->
                FilterChip(selected = selected == provider.id, enabled = !busy, onClick = { val target = networkId; applyChange { settings.selectProvider(target, provider.id) } }, label = { Text(provider.label) })
                Text(provider.endpointUrl, style = MaterialTheme.typography.bodySmall)
                Text(if (provider.credentialRef == null) "No credentials" else "Provider-specific credentials protected in Android vault", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { editing = provider; label = provider.label; endpoint = provider.endpointUrl; replaceAuthentication = false; username = ""; password = "" }) { Text("Edit ${provider.label}") }
                    TextButton(enabled = !busy, onClick = { applyChange { settings.removeProvider(provider.id) } }) { Text("Remove ${provider.label}") }
                }
            }
            HorizontalDivider()
            Text(if (editing == null) "Add provider for ${networkId?.let { id -> networks.firstOrNull { it.id == id }?.name } ?: "all networks"}" else "Edit provider", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Provider name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("HTTP(S) upload endpoint; no embedded credentials") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = replaceAuthentication, onCheckedChange = { replaceAuthentication = it; username = ""; password = "" })
                Text("Set/replace protected Basic credentials (empty clears)")
            }
            if (replaceAuthentication) {
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Provider username") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Provider password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text("Authentication requires HTTPS. Secrets are not stored in draft manifests or provider JSON.")
            }
            Button(enabled = !busy && label.isNotBlank(), onClick = {
                val provider = editing?.copy(label = label.trim(), endpointUrl = endpoint.trim()) ?: UploadProvider(label = label.trim(), endpointUrl = endpoint.trim(), networkId = networkId)
                val replace = replaceAuthentication
                val auth = if (username.isEmpty() && password.isEmpty()) null else UploadAuthentication(username, password)
                applyChange { settings.saveProvider(provider, auth, replace) }
                password = ""; username = ""
            }) { Text("Save provider") }
            if (editing != null) TextButton(onClick = { editing = null; label = ""; endpoint = "https://"; replaceAuthentication = false; username = ""; password = "" }) { Text("Add another provider") }
            HorizontalDivider()
            Text("Attachment size and metadata", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = maximumMiB, onValueChange = { maximumMiB = it }, label = { Text("Maximum file size in MiB (1–2048)") }, modifier = Modifier.fillMaxWidth())
            FilterChip(selected = policy == PhotoMetadataPolicy.STRIP, onClick = { policy = PhotoMetadataPolicy.STRIP }, label = { Text("Strip photo metadata/location (default)") })
            FilterChip(selected = policy == PhotoMetadataPolicy.KEEP, onClick = { policy = PhotoMetadataPolicy.KEEP }, label = { Text("Keep metadata explicitly") })
            Text("JPEG preserves orientation and supported color interpretation while stripping photo metadata. PNG and static WebP strip metadata where appearance can be retained. GIF, animated PNG/WebP, unknown images and unsafe orientation fail honestly under Strip; choose Keep explicitly instead. No format is silently flattened.")
            if (policy == PhotoMetadataPolicy.KEEP) Text("Warning: uploads may disclose location, device and personal information.", color = MaterialTheme.colorScheme.error)
            val maximum = maximumMiB.toLongOrNull()?.takeIf { it in 1..2048 }?.times(1024 * 1024)
            Button(enabled = !busy && maximum != null, onClick = { if (maximum != null) { val chosenPolicy = policy; applyChange { settings.updateLimits(maximum, chosenPolicy) } } }) { Text("Save size and privacy policy") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Private staging bytes and temporary files are not synced or backed up. An upload only produces a URL for explicit insertion/send.")
        }
    }
}
