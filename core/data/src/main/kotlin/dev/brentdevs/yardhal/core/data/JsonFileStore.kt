package dev.brentdevs.yardhal.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.SerializationException
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
    private var writesBlocked = false
    private var evidenceChecked = false

    @Synchronized
    public fun loadOrDefault(defaultValue: T): T {
        cachedValue?.let { return it }
        if (uncommittedReplacement) {
            cachedValue = defaultValue
            return defaultValue
        }
        val loaded = readStoredValue() ?: defaultValue
        cachedValue = loaded
        return loaded
    }

    @Synchronized
    public fun save(value: T) {
        if (cachedValue == null && !uncommittedReplacement) cachedValue = readStoredValue()
        if (writesBlocked) throw IOException("Storage evidence must be preserved before persisted data can be replaced")
        val encoded = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
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
                output.write(encoded)
                output.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("Unable to atomically replace persisted data")
            renamed = true
            syncDirectory(parent)
            cachedValue = value
            uncommittedReplacement = false
        } catch (failure: IOException) {
            failedSave(failure, renamed, temporaryOwned, tmp)
            throw failure
        } catch (failure: SecurityException) {
            failedSave(failure, renamed, temporaryOwned, tmp)
            throw failure
        }
    }

    private fun readStoredValue(): T? {
        try {
            if (!evidenceChecked) {
                evidenceChecked = true
                if (StorageRecovery.announceRetained(file)) {
                    writesBlocked = true
                    StorageRecovery.report(
                        StorageRecoveryNotice(
                            "Earlier ${file.name} recovery was interrupted. Remaining originals and any evidence " +
                                "copies were left untouched. This store uses temporary empty state, not restored data.",
                            StorageRecovery.retainedEvidence(file).lastOrNull()?.absolutePath,
                            temporary = true,
                        ),
                    )
                    return null
                }
            }
            if (!file.exists()) return null
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(file.readBytes()))
                .toString()
            return json.decodeFromString(serializer, text)
        } catch (failure: SerializationException) {
            recoverCorruptFile()
        } catch (failure: NetworkConfigValidationException) {
            recoverCorruptFile()
        } catch (failure: CharacterCodingException) {
            recoverCorruptFile()
        } catch (failure: IOException) {
            unavailableFile()
        } catch (failure: SecurityException) {
            unavailableFile()
        }
        return null
    }

    private fun recoverCorruptFile() {
        try {
            val evidence = preserveStorageEvidence(file, listOf(file), syncDirectory = syncDirectory)
            StorageRecovery.report(
                StorageRecoveryNotice(
                    "${file.name} could not be decoded. Exact original bytes were retained, not restored. " +
                        "This store starts with empty state; new changes can be saved persistently.",
                    evidence.absolutePath,
                    temporary = false,
                ),
            )
        } catch (failure: EvidencePreservationException) {
            writesBlocked = true
            StorageRecovery.report(
                StorageRecoveryNotice(
                    "${file.name} could not be decoded and preservation could not finish. Remaining originals " +
                        "and any evidence copies were not overwritten. This store uses temporary empty state; " +
                        "changes cannot be saved.",
                    failure.directory?.absolutePath ?: file.absolutePath,
                    temporary = true,
                ),
            )
        }
    }

    private fun unavailableFile() {
        writesBlocked = true
        StorageRecovery.report(
            StorageRecoveryNotice(
                "${file.name} is unavailable. Existing files were left in place, not quarantined or restored. " +
                    "This store uses temporary empty state; changes cannot be saved.",
                quarantinePath = null,
                temporary = true,
            ),
        )
    }

    private fun failedSave(failure: Exception, renamed: Boolean, temporaryOwned: Boolean, tmp: File) {
        if (renamed) {
            uncommittedReplacement = true
        } else if (temporaryOwned) {
            try {
                if (!tmp.delete() && tmp.exists()) {
                    failure.addSuppressed(IOException("Unable to remove persisted data temporary file"))
                }
            } catch (cleanup: SecurityException) {
                failure.addSuppressed(cleanup)
            }
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
