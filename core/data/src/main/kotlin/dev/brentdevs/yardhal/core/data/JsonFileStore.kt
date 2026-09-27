package dev.brentdevs.yardhal.core.data

import java.io.File
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
        cachedValue = value
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
