package dev.brentdevs.yardhal.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public data class StorageRecoveryNotice(
    val message: String,
    val quarantinePath: String?,
    val temporary: Boolean,
)

public object StorageRecovery {
    private val mutableNotices = MutableStateFlow<List<StorageRecoveryNotice>>(emptyList())
    public val notices: StateFlow<List<StorageRecoveryNotice>> = mutableNotices.asStateFlow()

    private val mutableDatabaseTemporary = MutableStateFlow(false)
    public val databaseTemporary: StateFlow<Boolean> = mutableDatabaseTemporary.asStateFlow()

    @Synchronized
    internal fun report(notice: StorageRecoveryNotice) {
        if (notice !in mutableNotices.value) mutableNotices.value += notice
    }

    @Synchronized
    internal fun clearSessionNotices() {
        mutableNotices.value = emptyList()
    }

    internal fun databaseSession(temporary: Boolean) {
        mutableDatabaseTemporary.value = temporary
    }

    internal fun retainedEvidence(source: File): List<File> {
        val root = File(source.absoluteFile.parentFile, "storage-quarantine")
        if (!root.exists()) return emptyList()
        val directories = root.listFiles() ?: throw IOException("Unable to inspect retained storage evidence")
        return directories.filter { directory ->
            directory.isDirectory && File(directory, ".source").let { it.isFile && it.readText() == source.name }
        }
    }

    internal fun announceRetained(source: File): Boolean {
        val evidence = retainedEvidence(source)
        for (directory in evidence) {
            report(
                StorageRecoveryNotice(
                    message = if (File(directory, ".retired").isFile) {
                        "Earlier ${source.name} storage could not be opened. Original evidence remains retained; " +
                            "this does not mean its data was restored."
                    } else {
                        "Earlier ${source.name} preservation was interrupted. Remaining originals and available " +
                            "evidence copies were left untouched; no data was restored."
                    },
                    quarantinePath = directory.absolutePath,
                    temporary = false,
                ),
            )
        }
        return evidence.any { !File(it, ".retired").isFile }
    }
}

internal class EvidencePreservationException(
    val directory: File?,
    cause: Exception,
) : IOException("Unable to finish preserving and retiring failed storage", cause)

internal fun preserveStorageEvidence(
    source: File,
    files: List<File>,
    retireFiles: List<File> = files,
    syncDirectory: (File) -> Unit = ::syncStoreDirectory,
): File {
    var directory: File? = null
    try {
        val parent = source.absoluteFile.parentFile ?: throw IOException("Storage has no parent directory")
        val root = File(parent, "storage-quarantine")
        if (!root.isDirectory) {
            if (!root.mkdir() && !root.isDirectory) throw IOException("Unable to create storage quarantine")
            syncDirectory(parent)
        }
        val owned = File(root, UUID.randomUUID().toString())
        if (!owned.mkdir()) throw IOException("Unable to create unique storage quarantine")
        directory = owned
        syncDirectory(root)
        writeEvidenceMarker(File(owned, ".source"), source.name)
        for (file in files) {
            if (!file.exists()) continue
            if (!file.isFile) throw IOException("Storage evidence is not a regular file")
            file.inputStream().use { input ->
                FileOutputStream(File(owned, file.name)).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        }
        writeEvidenceMarker(File(owned, ".complete"), "preserved")
        syncDirectory(owned)
        for (file in retireFiles) {
            if (file.exists() && !file.delete()) throw IOException("Unable to retire preserved storage")
        }
        syncDirectory(parent)
        writeEvidenceMarker(File(owned, ".retired"), "retired")
        syncDirectory(owned)
        return owned
    } catch (failure: IOException) {
        throw EvidencePreservationException(directory, failure)
    } catch (failure: SecurityException) {
        throw EvidencePreservationException(directory, failure)
    }
}

private fun writeEvidenceMarker(file: File, value: String) {
    FileOutputStream(file).use { output ->
        output.write(value.toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
}

internal class DatabaseOpeningGuard private constructor(
    val directory: File,
    private val source: File,
) {
    val evidenceFiles: List<File>
        get() = databaseFiles(source).map { File(directory, it.name) }.filter { it.isFile }

    fun accept() {
        writeEvidenceMarker(File(directory, ".accepted"), "accepted")
        syncStoreDirectory(directory)
        try {
            discard()
        } catch (failure: IOException) {
            retainedOpeningCopy()
        } catch (failure: SecurityException) {
            retainedOpeningCopy()
        }
    }

    fun markFailed() {
        writeEvidenceMarker(File(directory, ".failed"), "failed integrity validation")
        syncStoreDirectory(directory)
    }

    private fun retainedOpeningCopy() {
        StorageRecovery.report(
            StorageRecoveryNotice(
                "An opening safeguard could not be removed. This is retained evidence, not restored messages.",
                directory.absolutePath,
                temporary = false,
            ),
        )
    }

    fun restore() {
        val present = File(directory, ".present").readText().split(',').filter { it.isNotEmpty() }.map { it.toInt() }.toSet()
        for ((index, original) in databaseFiles(source).withIndex()) {
            val backup = File(directory, original.name)
            if (index in present) {
                if (backup.exists()) {
                    if (!backup.renameTo(original)) throw IOException("Unable to restore original storage bytes")
                } else if (!original.isFile) {
                    throw IOException("Original storage evidence is unavailable")
                }
            } else if (original.exists() && !original.delete()) {
                throw IOException("Unable to remove an uncommitted storage sidecar")
            }
        }
        syncStoreDirectory(source.absoluteFile.parentFile ?: throw IOException("Storage has no parent directory"))
        accept()
    }

    private fun discard() {
        val files = directory.listFiles() ?: throw IOException("Unable to inspect opening safeguard")
        for (file in files.filter { it.name != ".accepted" }) {
            if (!file.delete()) throw IOException("Unable to remove opening safeguard")
        }
        if (!File(directory, ".accepted").delete() || !directory.delete()) {
            throw IOException("Unable to remove opening safeguard")
        }
        syncStoreDirectory(directory.parentFile ?: throw IOException("Opening safeguard has no parent"))
    }

    companion object {
        fun existing(source: File): DatabaseOpeningGuard? {
            val root = File(source.absoluteFile.parentFile, "storage-opening")
            if (!root.exists()) return null
            val directories = root.listFiles() ?: throw IOException("Unable to inspect opening safeguards")
            return directories.firstOrNull {
                it.isDirectory && File(it, ".source").isFile && File(it, ".source").readText() == source.name &&
                    File(it, ".complete").isFile && !File(it, ".accepted").isFile
            }?.let { DatabaseOpeningGuard(it, source) }
        }
        fun prepare(source: File, protectOriginal: Boolean): DatabaseOpeningGuard? {
            val parent = source.absoluteFile.parentFile ?: throw IOException("Storage has no parent directory")
            val root = File(parent, "storage-opening")
            var restored = false
            if (root.exists()) {
                val directories = root.listFiles() ?: throw IOException("Unable to inspect opening safeguards")
                for (directory in directories) {
                    if (!directory.isDirectory || !File(directory, ".source").isFile ||
                        File(directory, ".source").readText() != source.name
                    ) continue
                    val guard = DatabaseOpeningGuard(directory, source)
                    if (File(directory, ".failed").isFile && !File(directory, ".accepted").isFile) {
                        throw EvidencePreservationException(directory, IOException("Failed originals remain safeguarded"))
                    }
                    if (File(directory, ".complete").isFile && !File(directory, ".accepted").isFile) {
                        guard.restore()
                        restored = true
                    } else {
                        guard.accept()
                    }
                }
            }
            if (!protectOriginal && !restored) return null
            val existing = databaseFiles(source).filter { it.exists() }
            if (existing.isEmpty()) return null
            if (!root.isDirectory) {
                if (!root.mkdir() && !root.isDirectory) throw IOException("Unable to create opening safeguard root")
                syncStoreDirectory(parent)
            }
            val directory = File(root, UUID.randomUUID().toString())
            if (!directory.mkdir()) throw IOException("Unable to create opening safeguard")
            syncStoreDirectory(root)
            writeEvidenceMarker(File(directory, ".source"), source.name)
            for (file in existing) {
                if (!file.isFile) throw IOException("Original storage is not a regular file")
                file.inputStream().use { input ->
                    FileOutputStream(File(directory, file.name)).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            writeEvidenceMarker(
                File(directory, ".present"),
                databaseFiles(source).mapIndexedNotNull { index, file -> index.takeIf { file in existing } }.joinToString(","),
            )
            writeEvidenceMarker(File(directory, ".complete"), "guarded")
            syncStoreDirectory(directory)
            return DatabaseOpeningGuard(directory, source)
        }
    }
}

internal fun databaseFiles(source: File): List<File> = listOf(
    source,
    File(source.path + "-wal"),
    File(source.path + "-shm"),
    File(source.path + "-journal"),
)
