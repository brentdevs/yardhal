package dev.brentdevs.yardhal.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public data class StorageRecoveryNotice(
    val message: String,
    val quarantinePath: String?,
    val temporary: Boolean,
    val scopeKey: String? = null,
    val evidenceKey: String? = null,
)

public object StorageRecovery {
    private val mutableNotices = MutableStateFlow<List<StorageRecoveryNotice>>(emptyList())
    public val notices: StateFlow<List<StorageRecoveryNotice>> = mutableNotices.asStateFlow()

    private val mutableDatabaseTemporary = MutableStateFlow(false)
    public val databaseTemporary: StateFlow<Boolean> = mutableDatabaseTemporary.asStateFlow()
    @Volatile
    private var acknowledgementRoot: File? = null
    private val acknowledgementFiles = mutableMapOf<StorageRecoveryNotice, File>()

    @Synchronized
    internal fun configure(source: File) {
        acknowledgementRoot = File(source.absoluteFile.parentFile, "storage-acknowledged")
    }

    internal fun report(notice: StorageRecoveryNotice) {
        val acknowledgement = try {
            acknowledgementFile(notice)
        } catch (failure: SecurityException) {
            null
        }
        val acknowledged = try {
            acknowledgement?.let { it.isFile && it.readText() == "acknowledged" } == true
        } catch (failure: IOException) {
            false
        } catch (failure: SecurityException) {
            false
        }
        synchronized(this) {
            if (!notice.temporary && acknowledged) return
            if (acknowledgement != null) acknowledgementFiles[notice] = acknowledgement
            if (notice !in mutableNotices.value) mutableNotices.value += notice
        }
    }

    @Synchronized
    public fun canAcknowledge(notice: StorageRecoveryNotice): Boolean =
        !notice.temporary && notice in mutableNotices.value && acknowledgementFiles.containsKey(notice)

    public fun acknowledge(notice: StorageRecoveryNotice): Boolean {
        val marker = synchronized(this) {
            if (notice.temporary || notice !in mutableNotices.value) return false
            acknowledgementFiles[notice] ?: return false
        }
        try {
            val directory = marker.parentFile ?: return false
            if (!directory.isDirectory) {
                if (!directory.mkdir() && !directory.isDirectory) return false
                syncStoreDirectory(directory.parentFile ?: return false)
            }
            writeEvidenceMarker(marker, "pending")
            syncStoreDirectory(directory)
            writeEvidenceMarker(marker, "acknowledged")
        } catch (failure: IOException) {
            return false
        } catch (failure: SecurityException) {
            return false
        }
        synchronized(this) {
            val acknowledged = acknowledgementFiles.filterValues { it == marker }.keys.toSet()
            mutableNotices.value = mutableNotices.value.filterNot { !it.temporary && it in acknowledged }
            acknowledgementFiles.keys.removeAll(acknowledged)
        }
        return true
    }

    private fun acknowledgementFile(notice: StorageRecoveryNotice): File? {
        if (notice.temporary) return null
        notice.quarantinePath?.let { path ->
            val directory = File(path)
            if (!File(directory, ".retired").isFile && !File(directory, ".accepted").isFile) return null
            return File(directory, ".acknowledged")
        }
        val root = acknowledgementRoot ?: return null
        val identity = notice.scopeKey.orEmpty() + "\n" + (notice.evidenceKey ?: notice.message)
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return File(root, digest.joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    @Synchronized
    internal fun clearScope(scopeKey: String) {
        mutableNotices.value = mutableNotices.value.filterNot { it.scopeKey == scopeKey && !it.temporary }
        acknowledgementFiles.keys.removeAll { it.scopeKey == scopeKey && !it.temporary }
    }

    @Synchronized
    internal fun clearSessionNotices() {
        mutableNotices.value = emptyList()
        acknowledgementFiles.clear()
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

    internal fun announceRetained(
        source: File,
        originals: List<File> = listOf(source),
        syncDirectory: (File) -> Unit = ::syncStoreDirectory,
    ): Boolean {
        var unresolved = false
        for (directory in retainedEvidence(source)) {
            var retained = directory
            if (!File(directory, ".retired").isFile) {
                try {
                    val guard = DatabaseOpeningGuard.existing(source)?.takeIf { it.failed }
                    retained = resumeStorageEvidence(source, directory, guard?.evidenceFiles ?: originals, originals, syncDirectory)
                    guard?.accept()
                } catch (failure: EvidencePreservationException) {
                    unresolved = true
                }
            }
            val retired = File(retained, ".retired").isFile
            if (retired) {
                mutableNotices.value = mutableNotices.value.filterNot { it.quarantinePath == directory.absolutePath && it.temporary }
            }
            report(
                StorageRecoveryNotice(
                    message = if (retired) {
                        "Earlier ${source.name} storage could not be opened. Original evidence remains retained; " +
                            "this does not mean its data was restored."
                    } else {
                        "Earlier ${source.name} preservation could not finish. Remaining originals and available " +
                            "evidence copies remain protected; this store is temporary and its changes are not durable."
                    },
                    quarantinePath = retained.absolutePath,
                    temporary = !retired,
                ),
            )
        }
        return unresolved
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
        val present = files.filter { it.exists() }
        writeEvidenceMarker(File(owned, ".files"), present.joinToString("\n") { it.absolutePath })
        writeEvidenceMarker(File(owned, ".retire"), retireFiles.joinToString("\n") { it.name })
        present.firstOrNull()?.parentFile?.takeIf { File(it, ".failed").isFile }?.let { opening ->
            writeEvidenceMarker(File(owned, ".opening"), opening.absolutePath)
        }
        writeEvidenceMarker(File(owned, ".planned"), "planned")
        syncDirectory(owned)
        finishStorageEvidence(source, owned, syncDirectory)
        return owned
    } catch (failure: IOException) {
        throw EvidencePreservationException(directory, failure)
    } catch (failure: SecurityException) {
        throw EvidencePreservationException(directory, failure)
    }
}

private fun resumeStorageEvidence(
    source: File,
    directory: File,
    files: List<File>,
    originals: List<File>,
    syncDirectory: (File) -> Unit,
): File {
    try {
        if (File(directory, ".planned").isFile) {
            finishStorageEvidence(source, directory, syncDirectory)
            return directory
        }
        val guard = files.firstOrNull()?.parentFile?.takeIf { File(it, ".failed").isFile }
        if (guard != null) writeEvidenceMarker(File(directory, ".opening"), guard.absolutePath)
        if (File(directory, ".complete").isFile) {
            syncDirectory(directory)
            retireStorageEvidence(source, directory, originals, syncDirectory)
            return directory
        }
        val replacement = preserveStorageEvidence(source, files, originals, syncDirectory)
        writeEvidenceMarker(File(directory, ".continued"), replacement.absolutePath)
        writeEvidenceMarker(File(directory, ".retired"), "retired with additional evidence")
        syncDirectory(directory)
        return replacement
    } catch (failure: EvidencePreservationException) {
        throw failure
    } catch (failure: IOException) {
        throw EvidencePreservationException(directory, failure)
    } catch (failure: SecurityException) {
        throw EvidencePreservationException(directory, failure)
    }
}

private fun finishStorageEvidence(source: File, directory: File, syncDirectory: (File) -> Unit) {
    if (File(directory, ".retired").isFile) return
    val names = databaseFiles(source).map { it.name }.toSet()
    if (!File(directory, ".complete").isFile) {
        val files = File(directory, ".files").readLines().filter { it.isNotEmpty() }.map(::File)
        for (file in files) {
            if (file.name !in names) throw IOException("Invalid storage evidence plan")
            val target = File(directory, file.name)
            if (target.isFile) continue
            if (target.exists() || !file.isFile) throw IOException("Original storage evidence is unavailable")
            val copying = File(directory, ".copy-" + UUID.randomUUID())
            file.inputStream().use { input ->
                FileOutputStream(copying).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (target.exists() || !copying.renameTo(target)) throw IOException("Unable to publish original storage evidence")
        }
        syncDirectory(directory)
        writeEvidenceMarker(File(directory, ".complete"), "preserved")
    }
    syncDirectory(directory)
    val originals = File(directory, ".retire").readLines().filter { it.isNotEmpty() }.map { name ->
        if (name !in names) throw IOException("Invalid storage retirement plan")
        File(source.absoluteFile.parentFile, name)
    }
    retireStorageEvidence(source, directory, originals, syncDirectory)
}

private fun retireStorageEvidence(source: File, directory: File, originals: List<File>, syncDirectory: (File) -> Unit) {
    for (file in originals) {
        if (!file.exists()) continue
        val retired = File(directory, ".retired-${UUID.randomUUID()}-${file.name}")
        if (retired.exists() || !file.renameTo(retired)) throw IOException("Unable to retain current storage during retirement")
    }
    syncDirectory(directory)
    syncDirectory(source.absoluteFile.parentFile ?: throw IOException("Storage has no parent directory"))
    writeEvidenceMarker(File(directory, ".retired"), "retired")
    syncDirectory(directory)
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
        get() {
            val present = File(directory, ".present").readText().split(',').filter { it.isNotEmpty() }.map { it.toInt() }.toSet()
            return databaseFiles(source).mapIndexedNotNull { index, original ->
                if (index !in present) return@mapIndexedNotNull null
                File(directory, original.name).also {
                    if (!it.isFile) throw IOException("Original storage evidence is unavailable")
                }
            }
        }
    val failed: Boolean
        get() = File(directory, ".failed").isFile

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
        for (file in files.filter { it.name != ".accepted" && it.name != ".acknowledged" }) {
            if (!file.delete()) throw IOException("Unable to remove opening safeguard")
        }
        File(directory, ".acknowledged").let { if (it.exists() && !it.delete()) throw IOException("Unable to remove opening acknowledgement") }
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
                    if (guard.failed && !File(directory, ".accepted").isFile) {
                        val retained = StorageRecovery.retainedEvidence(source).firstOrNull {
                            File(it, ".opening").let { marker -> marker.isFile && marker.readText() == directory.absolutePath }
                        }
                        if (retained == null) {
                            preserveStorageEvidence(source, guard.evidenceFiles, databaseFiles(source))
                        } else if (!File(retained, ".retired").isFile) {
                            resumeStorageEvidence(source, retained, guard.evidenceFiles, databaseFiles(source), ::syncStoreDirectory)
                        }
                        guard.accept()
                        StorageRecovery.announceRetained(source, databaseFiles(source))
                    } else if (File(directory, ".complete").isFile && !File(directory, ".accepted").isFile) {
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
