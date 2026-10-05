package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
public data class StoredHistoryGap(
    public val id: String,
    public val fromTimestampMs: Long,
    public val fromMsgid: String? = null,
    public val toTimestampMs: Long,
    public val toMsgid: String? = null,
)

public class HistoryCoverageStore(directory: File) {

    private val store = JsonFileStore(
        file = File(directory, "history-coverage.json"),
        serializer = MapSerializer(String.serializer(), ListSerializer(StoredHistoryGap.serializer())),
    )
    private val coverage: MutableMap<String, List<StoredHistoryGap>> = store.loadOrDefault(emptyMap()).toMutableMap()

    public fun gaps(storageKey: String): List<StoredHistoryGap> = synchronized(coverage) {
        coverage[storageKey].orEmpty().toList()
    }

    public fun put(storageKey: String, gaps: List<StoredHistoryGap>): Unit = synchronized(coverage) {
        val unique = gaps.distinct()
        if (coverage[storageKey].orEmpty() != unique) {
            if (unique.isEmpty()) coverage.remove(storageKey) else coverage[storageKey] = unique
            store.save(coverage.toMap())
        }
    }

    public fun rename(fromKey: String, toKey: String): Boolean = synchronized(coverage) {
        if (fromKey == toKey) return false
        val moved = coverage.remove(fromKey) ?: return false
        coverage[toKey] = (coverage[toKey].orEmpty() + moved).distinct()
        store.save(coverage.toMap())
        true
    }

    public fun removeNetwork(networkId: String): Unit = synchronized(coverage) {
        val prefix = "$networkId|"
        if (coverage.keys.removeAll { it.startsWith(prefix) }) store.save(coverage.toMap())
    }
}
