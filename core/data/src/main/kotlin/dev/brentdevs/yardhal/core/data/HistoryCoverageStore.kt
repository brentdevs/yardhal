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
            val next = coverage.toMutableMap()
            if (unique.isEmpty()) next.remove(storageKey) else next[storageKey] = unique
            store.save(next)
            coverage.clear()
            coverage.putAll(next)
        }
    }

    public fun rename(fromKey: String, toKey: String): Boolean = synchronized(coverage) {
        if (fromKey == toKey) return false
        val moved = coverage[fromKey] ?: return false
        val next = coverage.toMutableMap()
        next.remove(fromKey)
        next[toKey] = (next[toKey].orEmpty() + moved).distinct()
        store.save(next)
        coverage.clear()
        coverage.putAll(next)
        true
    }

    public fun removeNetwork(networkId: String): Unit = synchronized(coverage) {
        val prefix = "$networkId|"
        val next = coverage.filterKeys { !it.startsWith(prefix) }
        if (next.size != coverage.size) {
            store.save(next)
            coverage.clear()
            coverage.putAll(next)
        }
    }
}
