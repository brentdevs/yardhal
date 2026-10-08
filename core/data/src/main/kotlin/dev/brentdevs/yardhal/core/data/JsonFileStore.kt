package dev.brentdevs.yardhal.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public open class JsonFileStore<T>(
    private val file: File,
    private val serializer: kotlinx.serialization.KSerializer<T>,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    @Volatile
    private var cachedValue: T? = null

    public fun loadOrDefault(defaultValue: T): T {
        cachedValue?.let { return it }
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

    public fun save(value: T) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { output ->
            output.write(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("Unable to atomically replace persisted data")
        cachedValue = value
    }
}
