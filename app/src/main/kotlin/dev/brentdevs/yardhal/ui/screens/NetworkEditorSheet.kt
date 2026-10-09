package dev.brentdevs.yardhal.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.security.KeyChain
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brentdevs.yardhal.NetworkIdentitySelectionModel
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkMode
import dev.brentdevs.yardhal.core.data.SaslMode
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
public fun WelcomeScreen(
    onAddNetwork: () -> Unit,
    presets: List<NetworkPresetUi>,
    onPickPreset: (NetworkPresetUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Sailing,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Yardhal", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Your networks sail with you.",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Add an IRC network to get started. Yardhal keeps your connection " +
                "alive and notifies you when your name comes up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (presets.isNotEmpty()) {
            Text(
                "Quick start",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { candidate ->
                    OutlinedButton(
                        onClick = { onPickPreset(candidate) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(candidate.name)
                            Text(
                                "${candidate.host}:${candidate.port}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        TextButton(onClick = onAddNetwork) { Text("Add network manually") }
    }
}

@Composable
public fun NetworkEditorSheet(
    presets: List<NetworkPresetUi>,
    initialPreset: NetworkPresetUi? = null,
    initialConfig: NetworkConfig? = null,
    onSave: (draft: NetworkDraft) -> Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    key(initialConfig?.id, initialPreset?.id) {
        val editing = initialConfig != null
        var networkMode by rememberSaveable { mutableStateOf(initialConfig?.mode ?: NetworkMode.DIRECT) }
        var zncNetwork by rememberSaveable { mutableStateOf(initialConfig?.zncNetwork ?: "") }
        val editorId = rememberSaveable { UUID.randomUUID().toString() }
        val suggestedIdentityName = suggestedTlsIdentityName(initialConfig?.id, editorId)
        val hasSavedPassword = initialConfig?.saslPasswordRef != null
        var presetId by rememberSaveable { mutableStateOf(initialPreset?.id) }
        var host by rememberSaveable { mutableStateOf(initialConfig?.host ?: initialPreset?.host ?: "") }
        var port by rememberSaveable {
            mutableStateOf((initialConfig?.port ?: initialPreset?.port ?: 6697).toString())
        }
        var tls by rememberSaveable { mutableStateOf(initialConfig?.tls ?: initialPreset?.tls ?: true) }
        var nick by rememberSaveable { mutableStateOf(initialConfig?.nick ?: "") }
        var account by rememberSaveable { mutableStateOf(initialConfig?.saslAuthcid ?: "") }
        var password by remember { mutableStateOf("") }
        var clearPassword by rememberSaveable { mutableStateOf(false) }
        var channels by rememberSaveable { mutableStateOf(initialConfig?.autojoin?.joinToString(",") ?: "") }
        var name by rememberSaveable { mutableStateOf(initialConfig?.name ?: initialPreset?.name ?: "") }
        var saveFailed by rememberSaveable { mutableStateOf(false) }
        var realName by rememberSaveable { mutableStateOf(initialConfig?.realName ?: "Yardhal") }
        var alternateNicks by rememberSaveable { mutableStateOf(initialConfig?.alternateNicks?.joinToString(",") ?: "") }
        var autoConnect by rememberSaveable { mutableStateOf(initialConfig?.autoConnect ?: true) }
        var saslMode by rememberSaveable { mutableStateOf(initialConfig?.saslMode ?: SaslMode.AUTO) }
        var serverPassword by remember { mutableStateOf("") }
        var clearServerPassword by rememberSaveable { mutableStateOf(false) }
        var nickServAccount by rememberSaveable { mutableStateOf(initialConfig?.nickServAccount ?: "") }
        var nickServService by rememberSaveable { mutableStateOf(initialConfig?.nickServService ?: "NickServ") }
        var nickServPassword by remember { mutableStateOf("") }
        var clearNickServPassword by rememberSaveable { mutableStateOf(false) }
        var waitForNickServ by rememberSaveable { mutableStateOf(initialConfig?.waitForNickServ ?: true) }
        var proxyEnabled by rememberSaveable { mutableStateOf(initialConfig?.proxy != null) }
        var proxyHost by rememberSaveable { mutableStateOf(initialConfig?.proxy?.host ?: "") }
        var proxyPort by rememberSaveable { mutableStateOf((initialConfig?.proxy?.port ?: 1080).toString()) }
        var proxyUsername by rememberSaveable { mutableStateOf(initialConfig?.proxy?.username ?: "") }
        var proxyPassword by remember { mutableStateOf("") }
        var clearProxyPassword by rememberSaveable { mutableStateOf(false) }
        var tlsClientAlias by rememberSaveable { mutableStateOf(initialConfig?.tlsClientAlias) }
        var clearTlsClientAlias by rememberSaveable { mutableStateOf(false) }
        var identityError by rememberSaveable { mutableStateOf<String?>(null) }
        var identityImportBusy by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val activity = remember(context) { context.findActivity() as? ComponentActivity }
        val identityModel = remember(activity) {
            activity?.let { ViewModelProvider(it)[NetworkIdentitySelectionModel::class.java] }
        }
        val selection = identityModel?.selection?.collectAsStateWithLifecycle()?.value
        val identityBusy = identityImportBusy || selection?.let { it.editorId == editorId && it.busy } == true
        val scope = rememberCoroutineScope()

        LaunchedEffect(selection, editorId) {
            val result = selection ?: return@LaunchedEffect
            val requestId = result.requestId ?: return@LaunchedEffect
            if (result.editorId != editorId || result.busy) return@LaunchedEffect
            result.alias?.let {
                tlsClientAlias = it
                clearTlsClientAlias = false
            }
            identityError = result.error
            identityModel?.consume(requestId)
        }

        fun chooseIdentity() {
            val owner = activity
            val model = identityModel
            if (owner == null || model == null) {
                identityError = "Certificate selection requires an Android activity. Reopen the editor and try again."
                return
            }
            val requestId = model.begin(editorId)
            identityError = null
            try {
                KeyChain.choosePrivateKeyAlias(
                    owner,
                    { selected -> model.complete(requestId, selected) },
                    null,
                    null,
                    host.trim().takeIf(String::isNotEmpty),
                    port.toIntOrNull() ?: -1,
                    tlsClientAlias,
                )
            } catch (_: Exception) {
                model.fail(requestId)
            }
        }

        val installIdentity = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            identityImportBusy = false
            if (result.resultCode == Activity.RESULT_OK) {
                chooseIdentity()
            } else {
                identityError = "Identity installation wasn't completed. Retry with a PKCS#12 (.p12 or .pfx) file containing a private key and certificate, and its correct password."
            }
        }
        val importIdentity = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    identityImportBusy = true
                    identityError = null
                    try {
                        val bytes = withContext(Dispatchers.IO) { readPkcs12(context, uri) }
                        installIdentity.launch(
                            KeyChain.createInstallIntent()
                                .putExtra(KeyChain.EXTRA_PKCS12, bytes)
                                .putExtra(KeyChain.EXTRA_NAME, suggestedIdentityName),
                        )
                    } catch (error: CancellationException) {
                        identityImportBusy = false
                        throw error
                    } catch (_: Exception) {
                        identityImportBusy = false
                        identityError = "Couldn't import the identity. Choose a readable PKCS#12 (.p12 or .pfx) file smaller than 512 KiB, then enter its password in Android's installer."
                    }
                }
            }
        }

        fun applyPreset(selected: NetworkPresetUi) {
            presetId = selected.id
            host = selected.host
            port = selected.port.toString()
            tls = selected.tls
            if (name.isBlank()) name = selected.name
        }

        val draft = NetworkDraft(
            host = host.trim(),
            port = port.toIntOrNull() ?: 0,
            tls = tls,
            nick = nick.trim(),
            saslPassword = if (clearPassword) null else password.takeIf(String::isNotEmpty),
            autojoin = if (networkMode == NetworkMode.DIRECT) {
                channels.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            } else emptyList(),
            displayName = name.trim().ifBlank { host.trim() },
            networkId = initialConfig?.id,
            saslAuthcid = if (networkMode == NetworkMode.DIRECT) account.trim().takeIf(String::isNotEmpty) else account.trim(),
            clearSaslPassword = clearPassword,
            realName = realName,
            alternateNicks = parseAlternateNicknames(alternateNicks),
            autoConnect = autoConnect,
            saslMode = if (networkMode == NetworkMode.ZNC) SaslMode.AUTO else saslMode,
            serverPassword = if (networkMode == NetworkMode.SOJU || clearServerPassword) null
                else serverPassword.takeIf(String::isNotEmpty),
            clearServerPassword = networkMode == NetworkMode.SOJU || clearServerPassword,
            nickServAccount = nickServAccount.trim(),
            nickServService = nickServService.trim(),
            nickServPassword = if (clearNickServPassword) null else nickServPassword.takeIf(String::isNotEmpty),
            clearNickServPassword = clearNickServPassword,
            waitForNickServ = waitForNickServ,
            proxyEnabled = proxyEnabled,
            proxyHost = proxyHost.trim(),
            proxyPort = proxyPort.toIntOrNull(),
            proxyUsername = proxyUsername,
            proxyPassword = if (!proxyEnabled || clearProxyPassword) null else proxyPassword.takeIf(String::isNotEmpty),
            clearProxyPassword = clearProxyPassword,
            tlsClientAlias = tlsClientAlias,
            clearTlsClientAlias = clearTlsClientAlias,
            mode = networkMode,
            zncNetwork = zncNetwork.trim(),
        )
        val validationErrors = networkEditorErrors(draft, initialConfig)

        Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (editing) "Edit network" else "New network", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkMode.entries.forEach { candidate ->
                    FilterChip(
                        selected = networkMode == candidate,
                        enabled = !editing,
                        onClick = {
                            networkMode = candidate
                            if (candidate == NetworkMode.SOJU) {
                                serverPassword = ""
                                clearServerPassword = false
                            }
                        },
                        label = { Text(networkModeLabel(candidate)) },
                    )
                }
            }
            if (networkMode != NetworkMode.DIRECT) {
                Text(
                    if (networkMode == NetworkMode.SOJU) {
                        "Add your soju account once. Upstream networks are discovered and opened automatically. soju owns channel joins and playback."
                    } else {
                        "Use your ZNC account and optional network name. ZNC owns channel joins and playback; manage them through Bouncer settings."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (editing) {
                Text(
                    "Changing connection settings reconnects this network. Existing conversations and history remain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { candidate ->
                        FilterChip(
                            selected = presetId == candidate.id,
                            onClick = { applyPreset(candidate) },
                            label = { Text(candidate.name) },
                        )
                    }
                }
            }
            Text("Connection", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = tls,
                    onClick = { tls = !tls },
                    label = { Text(if (tls) "TLS" else "Plain") },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            EditorCheckbox("Connect automatically on startup", autoConnect) { autoConnect = it }
            if (networkMode != NetworkMode.SOJU) {
                if (networkMode == NetworkMode.ZNC) {
                    EditorTextField("ZNC account", account) { account = it }
                    EditorTextField("ZNC network (optional)", zncNetwork) { zncNetwork = it }
                }
                EditorPassword(
                    if (networkMode == NetworkMode.ZNC) "ZNC password" else "Server password",
                    serverPassword, { serverPassword = it },
                    initialConfig?.serverPasswordRef != null, clearServerPassword,
                    { clearServerPassword = it; if (it) serverPassword = "" },
                )
            }
            Text("Identity", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = nick,
                onValueChange = { nick = it },
                label = { Text("Nickname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            EditorTextField("Alternate nicknames (commas or whitespace)", alternateNicks) { alternateNicks = it }
            EditorTextField("Real name", realName) { realName = it }
            if (networkMode != NetworkMode.ZNC) {
                Text("SASL authentication", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SaslMode.entries.forEach { mode ->
                        FilterChip(
                            selected = saslMode == mode,
                            onClick = { saslMode = mode },
                            label = { Text(mode.name.replace('_', '-')) },
                        )
                    }
                }
                if (saslMode == SaslMode.EXTERNAL) {
                    Text("EXTERNAL authenticates with the selected Android KeyChain identity and requires TLS. Saved password settings are kept but not used.")
                }
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text(if (networkMode == NetworkMode.SOJU) "soju account" else "SASL account (optional)") },
                    supportingText = {
                        Text(if (networkMode == NetworkMode.SOJU) "Your soju account, not an upstream IRC nickname." else "Leave blank to use your nickname for SASL.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                EditorPassword(
                    "SASL password", password, { password = it }, hasSavedPassword, clearPassword,
                    { clearPassword = it; if (it) password = "" },
                )
            }
            if (networkMode == NetworkMode.DIRECT) {
                Text("NickServ identification", style = MaterialTheme.typography.titleSmall)
                EditorTextField("NickServ account (optional)", nickServAccount) { nickServAccount = it }
                EditorTextField("NickServ service", nickServService) { nickServService = it }
                EditorPassword(
                    "NickServ password", nickServPassword, { nickServPassword = it },
                    initialConfig?.nickServPasswordRef != null, clearNickServPassword,
                    { clearNickServPassword = it; if (it) nickServPassword = "" },
                )
                EditorCheckbox("Wait for NickServ identification before joining", waitForNickServ) { waitForNickServ = it }
            }
            Text("SOCKS5 proxy", style = MaterialTheme.typography.titleSmall)
            EditorCheckbox("Use SOCKS5 proxy", proxyEnabled) { proxyEnabled = it }
            if (proxyEnabled) {
                EditorTextField("Proxy host", proxyHost) { proxyHost = it }
                EditorTextField("Proxy port", proxyPort) { proxyPort = it.filter(Char::isDigit).take(5) }
                EditorTextField("Proxy username (optional)", proxyUsername) { proxyUsername = it }
                EditorPassword(
                    "Proxy password", proxyPassword, { proxyPassword = it },
                    initialConfig?.proxy?.passwordRef != null, clearProxyPassword,
                    { clearProxyPassword = it; if (it) proxyPassword = "" },
                )
                Text("The IRC server hostname is resolved by the proxy.")
            }
            Text("TLS client identity", style = MaterialTheme.typography.titleSmall)
            Text(tlsClientAlias?.let { "Selected identity: $it" } ?: "No client identity selected")
            Text("Android KeyChain protects the private key. Import a PKCS#12 file, then choose its installed identity for this network.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !identityBusy, onClick = { chooseIdentity() }) { Text("Choose identity") }
                OutlinedButton(
                    enabled = !identityBusy,
                    onClick = {
                        try {
                            importIdentity.launch(arrayOf("application/x-pkcs12", "application/pkcs12", "application/octet-stream"))
                        } catch (_: Exception) {
                            identityError = "Android couldn't open the file picker. Install a document provider or use Choose identity for an already installed certificate."
                        }
                    },
                ) { Text("Import PKCS#12") }
            }
            if (tlsClientAlias != null) {
                TextButton(
                    enabled = !identityBusy,
                    onClick = { tlsClientAlias = null; clearTlsClientAlias = true },
                ) { Text("Clear selected identity") }
            }
            if (identityBusy) Text("Waiting for Android identity installation or selection…")
            identityError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (networkMode == NetworkMode.DIRECT) {
                Text("Channels", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = channels,
                    onValueChange = { channels = it },
                    label = { Text("Autojoin channels (#a,#b)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (saveFailed) {
                Text(
                    "Couldn't save this network. Your changes are still here; try again or cancel.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            validationErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    enabled = validationErrors.isEmpty() && !identityBusy,
                    onClick = { saveFailed = !onSave(draft) },
                ) { Text(if (editing) "Save changes" else if (autoConnect) "Connect" else "Add network") }
            }
        }
    }
}

public data class NetworkPresetUi(public val id: String, public val name: String, public val host: String, public val port: Int, public val tls: Boolean)

public data class NetworkDraft(
    public val host: String,
    public val port: Int,
    public val tls: Boolean,
    public val nick: String,
    public val saslPassword: String?,
    public val autojoin: List<String>,
    public val displayName: String,
    public val networkId: String? = null,
    public val saslAuthcid: String? = null,
    public val clearSaslPassword: Boolean = false,
    public val realName: String? = null,
    public val alternateNicks: List<String>? = null,
    public val autoConnect: Boolean? = null,
    public val saslMode: SaslMode? = null,
    public val serverPassword: String? = null,
    public val clearServerPassword: Boolean = false,
    public val nickServAccount: String? = null,
    public val nickServService: String? = null,
    public val nickServPassword: String? = null,
    public val clearNickServPassword: Boolean = false,
    public val waitForNickServ: Boolean? = null,
    public val proxyEnabled: Boolean? = null,
    public val proxyHost: String? = null,
    public val proxyPort: Int? = null,
    public val proxyUsername: String? = null,
    public val proxyPassword: String? = null,
    public val clearProxyPassword: Boolean = false,
    public val tlsClientAlias: String? = null,
    public val clearTlsClientAlias: Boolean = false,
    public val mode: NetworkMode? = null,
    public val zncNetwork: String? = null,
) {
    override fun toString(): String =
        "NetworkDraft(networkId=$networkId, host=$host, port=$port, tls=$tls, nick=$nick, saslMode=$saslMode)"
}

internal fun networkModeLabel(mode: NetworkMode): String = when (mode) {
    NetworkMode.DIRECT -> "Direct IRC"
    NetworkMode.SOJU -> "soju"
    NetworkMode.ZNC -> "ZNC"
}

@Composable
private fun EditorTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EditorCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
}

@Composable
private fun EditorPassword(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hasSavedPassword: Boolean,
    clear: Boolean,
    onClearChange: (Boolean) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = {
            if (hasSavedPassword) {
                Text(
                    if (clear) "The saved password will be removed."
                    else "Leave empty to keep the saved password, or enter a new password to replace it.",
                )
            }
        },
        enabled = !clear,
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (hasSavedPassword) EditorCheckbox("Clear saved $label", clear, onClearChange)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun readPkcs12(context: Context, uri: Uri): ByteArray {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(512 * 1024 + 1) }
        ?: throw IOException("Unable to read the selected identity file")
    if (bytes.isEmpty() || bytes.size > 512 * 1024) throw IOException("Invalid identity file size")
    return bytes
}
