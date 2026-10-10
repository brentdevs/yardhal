package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
internal data class CatchUpDismissal(val msgid: String?, val throughActivityMs: Long)

public class CatchUpStore(directory: File) {
    private val file = JsonFileStore(
        File(directory, "catch-up.json"), MapSerializer(String.serializer(), CatchUpDismissal.serializer()),
    )
    private var dismissed = file.loadOrDefault(emptyMap())

    @Synchronized
    public fun isDismissed(anchor: CatchUpAnchor, activityTimestampMs: Long): Boolean {
        val entry = dismissed[anchor.key] ?: return false
        return (entry.msgid == null || entry.msgid == anchor.msgid) && activityTimestampMs <= entry.throughActivityMs
    }

    @Synchronized
    public fun dismiss(anchor: CatchUpAnchor, activityTimestampMs: Long) {
        val current = dismissed[anchor.key]
        val through = if (current != null && current.msgid == anchor.msgid) maxOf(current.throughActivityMs, activityTimestampMs) else activityTimestampMs
        val next = dismissed + (anchor.key to CatchUpDismissal(anchor.msgid, through))
        file.save(next)
        dismissed = next
    }

    @Synchronized
    public fun restoreAll() {
        file.save(emptyMap())
        dismissed = emptyMap()
    }

    @Synchronized
    public fun deleteNetwork(networkId: String) {
        val next = dismissed.filterKeys { !it.startsWith("$networkId|") }
        if (next != dismissed) {
            file.save(next)
            dismissed = next
        }
    }

    @Synchronized
    public fun mergeRows(networkId: String, removedRowId: Long, survivingRowId: Long) {
        val removedKey = "$networkId|$removedRowId"
        val removed = dismissed[removedKey] ?: return
        val survivingKey = "$networkId|$survivingRowId"
        val surviving = dismissed[survivingKey]
        val moved = if (surviving == null || removed.throughActivityMs > surviving.throughActivityMs) removed else surviving
        val next = (dismissed - removedKey) + (survivingKey to moved)
        file.save(next)
        dismissed = next
    }
}
