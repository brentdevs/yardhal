package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull

@Serializable
public data class ReadCursor(public val timestampMs: Long = 0, public val rowId: Long = 0) : Comparable<ReadCursor> {
    override fun compareTo(other: ReadCursor): Int {
        val timestampOrder = timestampMs.compareTo(other.timestampMs)
        return if (timestampOrder != 0) timestampOrder else rowId.compareTo(other.rowId)
    }
}

@Serializable
internal data class ReadMarkerState(val cursor: ReadCursor, val pending: ReadCursor? = null)

internal object ReadMarkerSerializer : KSerializer<Map<String, ReadMarkerState>> {
    private val delegate = MapSerializer(String.serializer(), ReadMarkerState.serializer())
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Map<String, ReadMarkerState>) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): Map<String, ReadMarkerState> {
        if (decoder !is JsonDecoder) return delegate.deserialize(decoder)
        val value = decoder.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("Read markers must be an object")
        return value.mapValues { (_, element) ->
            if (element is JsonPrimitive) {
                val timestamp = element.longOrNull?.takeUnless { element.isString }
                    ?: throw SerializationException("Legacy read marker must be an integer")
                ReadMarkerState(ReadCursor(timestamp, Long.MAX_VALUE))
            } else decoder.json.decodeFromJsonElement(ReadMarkerState.serializer(), element)
        }
    }
}

public enum class ReadMarkerPersistence {
    DURABLE,
    VOLATILE,
}

public class ReadMarkerStore internal constructor(private val store: JsonFileStore<Map<String, ReadMarkerState>>) {
    public constructor(directory: File) : this(
        JsonFileStore(File(directory, "read-markers.json"), ReadMarkerSerializer),
    )

    private val lock = Any()
    private var markers: Map<String, ReadMarkerState> = store.loadOrDefault(emptyMap())
    private val mutablePersistence = MutableStateFlow(
        if (store.persistenceBlocked) ReadMarkerPersistence.VOLATILE else ReadMarkerPersistence.DURABLE,
    )
    public val persistence: StateFlow<ReadMarkerPersistence> = mutablePersistence.asStateFlow()

    public fun marker(storageKey: String): Long = cursor(storageKey).timestampMs

    public fun cursor(storageKey: String): ReadCursor = synchronized(lock) {
        markers[storageKey]?.cursor ?: ReadCursor()
    }

    public fun advance(storageKey: String, timestampMs: Long): Boolean =
        advance(storageKey, timestampMs, Long.MAX_VALUE)

    public fun advance(storageKey: String, timestampMs: Long, rowId: Long): Boolean = synchronized(lock) {
        val next = ReadCursor(timestampMs, rowId)
        val current = markers[storageKey] ?: ReadMarkerState(ReadCursor())
        if (next <= current.cursor) return false
        commit(markers + (storageKey to ReadMarkerState(next, next)))
        true
    }

    public fun reconcileRemote(storageKey: String, timestampMs: Long): Boolean = synchronized(lock) {
        val remote = ReadCursor(timestampMs, Long.MAX_VALUE)
        val current = markers[storageKey] ?: ReadMarkerState(ReadCursor())
        val next = current.copy(
            cursor = maxOf(current.cursor, remote),
            pending = current.pending?.takeIf { it > remote },
        )
        if (next == current) return false
        commit(markers + (storageKey to next))
        true
    }

    public fun pending(networkId: String): Map<String, ReadCursor> = synchronized(lock) {
        buildMap {
            for ((key, state) in markers) {
                if (key.startsWith("$networkId|")) state.pending?.let { put(key, it) }
            }
        }
    }

    public fun acknowledge(storageKey: String, acknowledged: ReadCursor): Boolean = synchronized(lock) {
        val current = markers[storageKey] ?: return false
        val pending = current.pending ?: return false
        if (acknowledged < pending) return false
        commit(markers + (storageKey to current.copy(pending = null)))
        true
    }

    public fun acknowledge(storageKey: String, timestampMs: Long): Boolean =
        acknowledge(storageKey, ReadCursor(timestampMs, Long.MAX_VALUE))

    public fun rename(fromKey: String, toKey: String): Boolean = synchronized(lock) {
        if (fromKey == toKey) return false
        val moved = markers[fromKey] ?: return false
        val existing = markers[toKey]
        val cursor = maxOf(moved.cursor, existing?.cursor ?: ReadCursor())
        val pending = listOfNotNull(moved.pending, existing?.pending).maxOrNull()
        commit((markers - fromKey) + (toKey to ReadMarkerState(cursor, pending)))
        true
    }

    public fun deleteNetwork(networkId: String) = synchronized(lock) {
        val next = markers.filterKeys { !it.startsWith("$networkId|") }
        if (next != markers) commit(next)
    }

    public fun retainNetwork(networkId: String, retainedStorageKeys: Set<String>): Boolean = synchronized(lock) {
        val next = markers.filterKeys { !it.startsWith("$networkId|") || it in retainedStorageKeys }
        if (next == markers) return false
        commit(next)
        true
    }

    public fun hasUnread(storageKey: String, latestTimestampMs: Long): Boolean = latestTimestampMs > marker(storageKey)

    public fun all(): Map<String, Long> = synchronized(lock) { markers.mapValues { it.value.cursor.timestampMs } }

    private fun commit(next: Map<String, ReadMarkerState>) {
        try {
            store.save(next)
        } catch (failure: StorageWriteBlockedException) {
            mutablePersistence.value = ReadMarkerPersistence.VOLATILE
        }
        markers = next
    }
}
