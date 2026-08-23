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
        if (!mutes.add(storageKey)) return false
        store.save(mutes.toSet())
        true
    }

    public fun unmute(storageKey: String): Boolean = synchronized(mutes) {
        if (!mutes.remove(storageKey)) return false
        store.save(mutes.toSet())
        true
    }
}
