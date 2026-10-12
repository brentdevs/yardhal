package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

public class MuteStore(directory: File) {

    private val file = File(directory, "mutes.json")
    private val store = JsonFileStore(
        file = file,
        serializer = SetSerializer(String.serializer()),
    )

    @Volatile
    private var mutes: MutableSet<String> = store.loadOrDefault(emptySet()).toMutableSet()

    public fun all(): Set<String> = synchronized(mutes) { mutes.toSet() }

    public fun isMuted(storageKey: String): Boolean = synchronized(mutes) { storageKey in mutes }

    public fun mute(storageKey: String): Boolean = synchronized(mutes) {
        if (storageKey in mutes) return false
        store.save(mutes + storageKey)
        mutes.add(storageKey)
        true
    }

    public fun unmute(storageKey: String): Boolean = synchronized(mutes) {
        if (storageKey !in mutes) return false
        store.save(mutes - storageKey)
        mutes.remove(storageKey)
        true
    }

    public fun deleteNetwork(networkId: String): Boolean = synchronized(mutes) {
        val prefix = "$networkId|"
        val next = mutes.filterNot { it.startsWith(prefix) }.toSet()
        if (next.size == mutes.size) return@synchronized false
        store.save(next)
        mutes.clear()
        mutes.addAll(next)
        true
    }

    public fun rename(fromKey: String, toKey: String): Boolean = synchronized(mutes) {
        if (fromKey == toKey || fromKey !in mutes) return false
        val next = (mutes - fromKey) + toKey
        store.save(next)
        mutes.clear()
        mutes.addAll(next)
        true
    }
}
