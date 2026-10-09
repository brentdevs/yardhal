package dev.brentdevs.yardhal.ui

import dev.brentdevs.yardhal.core.data.StorageRecoveryNotice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun restoredLaunchSelection(
    selectedKey: String?,
    selectionSettled: Boolean,
    restorationReady: Boolean,
    restoredKey: String?,
    availableKeys: Set<String>,
): String? = when {
    selectedKey != null || selectionSettled || !restorationReady -> selectedKey
    restoredKey != null && restoredKey in availableKeys -> restoredKey
    else -> null
}

internal fun metadataFreshnessLabel(cached: Boolean, connected: Boolean, observedAtMs: Long?): String {
    if (!cached && connected) return "Live"
    val status = if (connected) "Cached · stale" else "Cached · stale · offline"
    val observed = observedAtMs?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
    }
    return if (observed == null) status else "$status · observed $observed"
}

internal fun storageRecoveryExplanation(notice: StorageRecoveryNotice): String = buildString {
    append(notice.message)
    if (notice.quarantinePath != null) {
        append("\nRetained evidence location: ")
        append(notice.quarantinePath)
        append(". Preserved evidence, including any quarantine copies, is not restored messages.")
    } else {
        append("\nThis warning does not mean lost messages were restored.")
    }
    if (notice.temporary) append("\nSession storage is temporary; new data may not survive closing the app.")
}
