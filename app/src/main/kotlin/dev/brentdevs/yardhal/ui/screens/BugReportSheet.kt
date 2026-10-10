package dev.brentdevs.yardhal.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.JoinState
import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.core.data.BugReport
import dev.brentdevs.yardhal.core.data.BugReportBuild
import dev.brentdevs.yardhal.core.data.BugReportConnection
import dev.brentdevs.yardhal.core.data.BugReportRedaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun BugReportSheet(coordinator: LiveCoordinator, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var description by rememberSaveable { mutableStateOf("") }
    var captured by remember { mutableStateOf<BugReport?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(coordinator, refresh) {
        loading = true
        captureError = null
        try {
            captured = withContext(Dispatchers.Default) { captureBugReport(context, coordinator) }
        } catch (_: PackageManager.NameNotFoundException) {
            captureError = "App build metadata could not be read. Retry after reopening Yardhal."
        } catch (_: SecurityException) {
            captureError = "Device metadata is unavailable. Check this device's app restrictions and retry."
        }
        loading = false
    }
    val report = remember(captured, description) {
        captured?.copy(description = BugReportRedaction.description(description, coordinator::redactReportText))
    }
    val preview = remember(report) { report?.readable().orEmpty() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().safeDrawingPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Bug report", style = MaterialTheme.typography.titleLarge)
            Text("Describe the problem, expected behavior and reproduction steps. Credentials and raw chat are not required.")
            OutlinedTextField(
                value = description,
                onValueChange = {
                    if (it.length <= BugReportRedaction.MAX_DESCRIPTION_CHARS) {
                        description = it
                        inputError = null
                    } else inputError = "Description is limited to ${BugReportRedaction.MAX_DESCRIPTION_CHARS} characters. Shorten it before pasting."
                    copied = false
                },
                label = { Text("What happened?") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Network names, hosts, nicknames, chat bodies, tags, URLs and authentication arguments are omitted. Active known credentials and common secret fields are redacted from the description. Review the preview; arbitrary prose can still contain personal information.", style = MaterialTheme.typography.bodySmall)
            if (loading) CircularProgressIndicator()
            captureError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(enabled = !loading, onClick = { refresh += 1; copied = false }) { Text("Refresh context") }
            Text("Export preview", style = MaterialTheme.typography.titleMedium)
            if (report != null) SelectionContainer { Text(preview, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = report != null && !loading, onClick = {
                    try {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Yardhal bug report", preview))
                        copied = true
                        exportError = null
                    } catch (_: SecurityException) {
                        exportError = "Clipboard access was denied. Use Share or select the preview text."
                    }
                }) { Text(if (copied) "Report copied" else "Copy report") }
                TextButton(enabled = report != null && !loading, onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Yardhal bug report")
                            .putExtra(Intent.EXTRA_TEXT, preview)
                        context.startActivity(Intent.createChooser(intent, "Share bug report"))
                        exportError = null
                    } catch (_: android.content.ActivityNotFoundException) {
                        exportError = "No sharing app is available. Copy the report instead."
                    } catch (_: SecurityException) {
                        exportError = "Sharing is restricted on this device. Copy the report instead."
                    }
                }) { Text("Share report") }
            }
            exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onDismiss) { Text("Close bug report") }
        }
    }
}

private fun captureBugReport(context: Context, coordinator: LiveCoordinator): BugReport {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    val networks = coordinator.networks.value.sortedBy { it.id }.take(32)
    val buffers = coordinator.buffers.value.values.toList()
    val connections = networks.mapIndexed { index, network ->
        val networkBuffers = buffers.filter { it.ref.networkId == network.id }
        BugReportConnection(
            alias = "network-${index + 1}",
            status = "${network.status}/${network.connectionPhase}",
            tlsRequested = coordinator.lastAttemptRequestsTls(network.id),
            proxyConfigured = coordinator.lastAttemptUsesProxy(network.id),
            openConversations = networkBuffers.size,
            joinedChannels = if (network.status == ConnectionStatus.REGISTERED) {
                networkBuffers.count {
                    it.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL && it.joinState == JoinState.JOINED
                }
            } else 0,
            loadedMessages = networkBuffers.sumOf { it.messages.size },
        )
    }
    val activity = networks.flatMapIndexed { index, network ->
        coordinator.rawLog(network.id).takeLast(20).map { frame ->
            BugReportRedaction.activity("network-${index + 1}", frame.outbound, frame.line)
        }
    }.takeLast(BugReportRedaction.MAX_ACTIVITY)
    return BugReport(
        generatedAtMs = System.currentTimeMillis(),
        description = "",
        build = BugReportBuild(
            version = BugReportRedaction.deviceField(packageInfo.versionName ?: "Unavailable in package metadata"),
            versionCode = packageInfo.longVersionCode,
            androidApi = Build.VERSION.SDK_INT,
            manufacturer = BugReportRedaction.deviceField(Build.MANUFACTURER),
            model = BugReportRedaction.deviceField(Build.MODEL),
        ),
        connections = connections,
        activity = activity,
    )
}
