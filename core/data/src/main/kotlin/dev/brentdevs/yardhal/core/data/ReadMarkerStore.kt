package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

public class ReadMarkerStore(directory: File) {

    private val file = File(directory, "read-markers.json")
    private val store = JsonFileStore(
        file = file,
        serializer = MapSerializer(String.serializer(), Long.serializer()),
    )

    @Volatile
    private var markers: MutableMap<String, Long> = store.loadOrDefault(emptyMap()).toMutableMap()

    public fun marker(storageKey: String): Long = synchronized(markers) { markers[storageKey] ?: 0L }

    public fun advance(storageKey: String, timestampMs: Long): Boolean = synchronized(markers) {
        val current = markers[storageKey] ?: 0L
        if (timestampMs <= current) return false
        markers[storageKey] = timestampMs
        store.save(markers.toMap())
        true
    }

    public fun hasUnread(storageKey: String, latestTimestampMs: Long): Boolean =
        latestTimestampMs > marker(storageKey)

    public fun all(): Map<String, Long> = synchronized(markers) { markers.toMap() }
}
