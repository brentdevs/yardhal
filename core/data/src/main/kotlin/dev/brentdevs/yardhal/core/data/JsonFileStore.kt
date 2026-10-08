package dev.brentdevs.yardhal.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public open class JsonFileStore<T> internal constructor(
    private val file: File,
    private val serializer: kotlinx.serialization.KSerializer<T>,
    private val json: Json,
    private val syncDirectory: (File) -> Unit,
) {
    public constructor(
        file: File,
        serializer: kotlinx.serialization.KSerializer<T>,
        json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    ) : this(file, serializer, json, ::syncStoreDirectory)

    private var cachedValue: T? = null
    private var uncommittedReplacement = false
    private var pendingDirectorySync: File? = null

    @Synchronized
    public fun loadOrDefault(defaultValue: T): T {
        cachedValue?.let { return it }
        if (uncommittedReplacement) {
            cachedValue = defaultValue
            return defaultValue
        }
        val loaded: T = if (!file.exists()) {
            defaultValue
        } else {
            runCatching {
                json.decodeFromString(serializer, file.readText())
            }.getOrDefault(defaultValue)
        }
        cachedValue = loaded
        return loaded
    }

    @Synchronized
    public fun save(value: T) {
        if (cachedValue == null && !uncommittedReplacement && file.isFile) {
            cachedValue = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
        }
        val target = file.absoluteFile
        val parent = target.parentFile ?: throw IOException("Persisted data has no parent directory")
        pendingDirectorySync?.let {
            syncDirectory(it)
            pendingDirectorySync = null
        }
        ensureDirectory(parent)
        val tmp = File(parent, target.name + ".tmp")
        var temporaryOwned = false
        var renamed = false
        try {
            FileOutputStream(tmp).use { output ->
                temporaryOwned = true
                output.write(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("Unable to atomically replace persisted data")
            renamed = true
            syncDirectory(parent)
            cachedValue = value
            uncommittedReplacement = false
        } catch (failure: Throwable) {
            if (renamed) {
                uncommittedReplacement = true
            } else if (temporaryOwned && !tmp.delete() && tmp.exists()) {
                failure.addSuppressed(IOException("Unable to remove persisted data temporary file"))
            }
            throw failure
        }
    }

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        val parent = directory.parentFile ?: throw IOException("Unable to create persisted data directory")
        ensureDirectory(parent)
        if (!directory.mkdir() && !directory.isDirectory) {
            throw IOException("Unable to create persisted data directory")
        }
        pendingDirectorySync = parent
        syncDirectory(parent)
        pendingDirectorySync = null
    }
}

internal fun syncStoreDirectory(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
