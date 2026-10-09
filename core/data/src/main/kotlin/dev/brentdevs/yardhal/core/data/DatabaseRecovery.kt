package dev.brentdevs.yardhal.core.data

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

public object DatabaseRecovery {
    @Synchronized
    public fun open(context: Context, name: String = "yardhal.db"): YardhalDatabase = open(
        source = context.getDatabasePath(name),
        persistent = { YardhalDatabase.build(context, name, PreservingOpenHelperFactory) },
        temporary = { YardhalDatabase.inMemory(context) },
    )

    internal fun open(
        source: File,
        persistent: () -> YardhalDatabase,
        temporary: () -> YardhalDatabase,
        preserve: (File, List<File>, List<File>) -> File = { file, files, originals ->
            preserveStorageEvidence(file, files, retireFiles = originals)
        },
    ): YardhalDatabase {
        StorageRecovery.configure(source)
        try {
            if (StorageRecovery.announceRetained(source, databaseFiles(source))) {
                return temporarySession(
                    temporary,
                    "Storage retirement could not finish. Preserved evidence and any remaining originals remain " +
                        "protected; the failed database was not opened again. This session is temporary and its " +
                        "messages will not survive restart.",
                    StorageRecovery.retainedEvidence(source).lastOrNull()?.absolutePath,
                )
            }
        } catch (failure: IOException) {
            return inaccessibleSession(source, temporary)
        } catch (failure: SecurityException) {
            return inaccessibleSession(source, temporary)
        }
        val guard = try {
            DatabaseOpeningGuard.prepare(source, protectOriginal = requiresOpeningGuard(source))
        } catch (failure: EvidencePreservationException) {
            return guardedUnavailableSession(temporary, failure.directory ?: source)
        } catch (failure: IOException) {
            return inaccessibleSession(source, temporary)
        } catch (failure: SecurityException) {
            return inaccessibleSession(source, temporary)
        }
        try {
            val preflightChecked = guard == null && source.isFile
            if (preflightChecked) validateReadOnly(source)
            val database = openValidated(persistent, checkIntegrity = !preflightChecked)
            try {
                guard?.accept()
            } catch (failure: IOException) {
                database.close()
                return inaccessibleSession(source, temporary, guard)
            } catch (failure: SecurityException) {
                database.close()
                return inaccessibleSession(source, temporary, guard)
            }
            StorageRecovery.databaseSession(temporary = false)
            return database
        } catch (failure: SQLiteException) {
            return failedOpen(source, failure, persistent, temporary, preserve, guard)
        } catch (failure: IllegalStateException) {
            propagateControlFailure(failure)
            if (!isIntegrityFailure(failure) && !isStorageFailure(failure)) throw failure
            return failedOpen(source, failure, persistent, temporary, preserve, guard)
        } catch (failure: IOException) {
            return inaccessibleSession(source, temporary, guard)
        } catch (failure: SecurityException) {
            return inaccessibleSession(source, temporary, guard)
        }
    }

    private fun failedOpen(
        source: File,
        failure: Exception,
        persistent: () -> YardhalDatabase,
        temporary: () -> YardhalDatabase,
        preserve: (File, List<File>, List<File>) -> File,
        guard: DatabaseOpeningGuard?,
    ): YardhalDatabase {
        propagateControlFailure(failure)
        if (!isIntegrityFailure(failure)) return inaccessibleSession(source, temporary, guard)
        val evidence = try {
            val originals = databaseFiles(source)
            val originalGuard = guard ?: DatabaseOpeningGuard.existing(source) ?:
                DatabaseOpeningGuard.prepare(source, protectOriginal = true)
            originalGuard?.markFailed()
            val retained = preserve(source, originalGuard?.evidenceFiles ?: originals, originals)
            try {
                originalGuard?.accept()
            } catch (acceptance: IOException) {
                return replacementFailed(temporary, retained)
            } catch (acceptance: SecurityException) {
                return replacementFailed(temporary, retained)
            }
            retained
        } catch (preservation: EvidencePreservationException) {
            return temporarySession(
                temporary,
                "The database has an integrity or schema failure, but preservation could not finish. Remaining " +
                    "originals and any available evidence copies were not overwritten. No messages were restored; " +
                    "this session is temporary and its messages will not survive restart.",
                preservation.directory?.absolutePath ?: source.absolutePath,
            )
        } catch (preservation: IOException) {
            return inaccessibleSession(source, temporary)
        } catch (preservation: SecurityException) {
            return inaccessibleSession(source, temporary)
        }
        try {
            val database = openValidated(persistent)
            StorageRecovery.databaseSession(temporary = false)
            StorageRecovery.report(
                StorageRecoveryNotice(
                    message = "The previous database has an integrity or unsupported schema failure. Original " +
                        "database evidence was retained, not restored. A new empty persistent message store is in use.",
                    quarantinePath = evidence.absolutePath,
                    temporary = false,
                ),
            )
            return database
        } catch (replacement: SQLiteException) {
            propagateControlFailure(replacement)
            return replacementFailed(temporary, evidence)
        } catch (replacement: IllegalStateException) {
            propagateControlFailure(replacement)
            if (!isIntegrityFailure(replacement) && !isStorageFailure(replacement)) throw replacement
            return replacementFailed(temporary, evidence)
        } catch (replacement: IOException) {
            return replacementFailed(temporary, evidence)
        } catch (replacement: SecurityException) {
            return replacementFailed(temporary, evidence)
        }
    }

    private fun replacementFailed(temporary: () -> YardhalDatabase, evidence: File): YardhalDatabase = temporarySession(
        temporary,
        "Original database evidence was retained, not restored. A new persistent store could not be opened. " +
            "This session uses an empty temporary message store; its messages will not survive restart.",
        evidence.absolutePath,
    )

    private fun inaccessibleSession(
        source: File,
        temporary: () -> YardhalDatabase,
        guard: DatabaseOpeningGuard? = null,
    ): YardhalDatabase {
        try {
            guard?.restore()
        } catch (failure: IOException) {
            return guardedUnavailableSession(temporary, guard?.directory ?: source)
        } catch (failure: SecurityException) {
            return guardedUnavailableSession(temporary, guard?.directory ?: source)
        }
        return temporarySession(
            temporary,
            "Persistent storage ${source.name} is unavailable. Existing database files were not quarantined or " +
                "restored as messages. This session uses an empty temporary message store; " +
                "its messages will not survive restart.",
            null,
        )
    }

    private fun guardedUnavailableSession(temporary: () -> YardhalDatabase, evidence: File): YardhalDatabase =
        temporarySession(
            temporary,
            "Persistent storage is unavailable. Original database evidence remains safeguarded and was not " +
                "restored as messages. This session is temporary; its messages will not survive restart.",
            evidence.absolutePath,
        )

    private fun temporarySession(
        temporary: () -> YardhalDatabase,
        message: String,
        path: String?,
    ): YardhalDatabase {
        val database = openValidated(temporary)
        StorageRecovery.databaseSession(temporary = true)
        StorageRecovery.report(StorageRecoveryNotice(message, path, temporary = true))
        return database
    }

    private fun requiresOpeningGuard(source: File): Boolean {
        if (!source.exists()) return false
        if (File(source.path + "-journal").length() > 0) return true
        val header = source.inputStream().use { it.readNBytes(100) }
        val signature = "SQLite format 3\u0000"
        if (header.size < 100 || signature.indices.any { header[it].toInt() != signature[it].code }) return true
        val version = ((header[60].toInt() and 255) shl 24) or ((header[61].toInt() and 255) shl 16) or
            ((header[62].toInt() and 255) shl 8) or (header[63].toInt() and 255)
        return version != YardhalDatabase.SCHEMA_VERSION
    }

    private fun validateReadOnly(source: File) {
        SQLiteDatabase.openDatabase(
            source.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
            DatabaseErrorHandler {
                val guard = DatabaseOpeningGuard.existing(source) ?:
                    DatabaseOpeningGuard.prepare(source, protectOriginal = true)
                guard?.markFailed()
                throw SQLiteDatabaseCorruptException("Corrupt database retained for storage recovery")
            },
        ).use { sqlite ->
            validateReadOnly(sqlite)
        }
    }

    internal fun validateReadOnly(
        sqlite: SQLiteDatabase,
        version: String = sqlite.rawQuery("SELECT sqlite_version()", null).use { result ->
            if (!result.moveToFirst()) throw SQLiteDatabaseCorruptException("SQLite version returned no result")
            result.getString(0)
        },
        checked: () -> Unit = {},
    ) {
        val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
        val tableChecksSupported = parts.firstOrNull().let { it != null && it >= 3 } &&
            (parts[0] > 3 || (parts.getOrNull(1) ?: 0) >= 33)
        if (!tableChecksSupported) {
            sqlite.rawQuery("PRAGMA quick_check", null).use(::validateIntegrity)
            checked()
            return
        }
        sqlite.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND rootpage>0", null).use { tables ->
            while (tables.moveToNext()) {
                val name = tables.getString(0).replace("\"", "\"\"")
                sqlite.rawQuery("PRAGMA quick_check(\"$name\")", null).use(::validateIntegrity)
                checked()
            }
        }
    }

    private fun validateIntegrity(result: Cursor) {
        if (!result.moveToFirst()) throw SQLiteDatabaseCorruptException("SQLite integrity check returned no result")
        do {
            if (result.getString(0) != "ok") throw SQLiteDatabaseCorruptException("SQLite integrity check failed")
        } while (result.moveToNext())
    }

    private fun isStorageFailure(failure: Exception): Boolean =
        generateSequence<Throwable>(failure) { it.cause }.take(16).any {
            it is SQLiteException || it is IOException || it is SecurityException
        }

    private fun openValidated(build: () -> YardhalDatabase, checkIntegrity: Boolean = true): YardhalDatabase {
        val database = build()
        var validated = false
        try {
            val sqlite = database.openHelper.writableDatabase
            if (checkIntegrity) sqlite.query("PRAGMA quick_check").use(::validateIntegrity)
            sqlite.execSQL("INSERT INTO message_fts(message_fts) VALUES('integrity-check')")
            sqlite.query("PRAGMA foreign_key_check").use { result ->
                if (result.moveToFirst()) throw SQLiteConstraintException("Database foreign key integrity check failed")
            }
            validated = true
            return database
        } finally {
            if (!validated) database.close()
        }
    }

    internal fun isIntegrityFailure(failure: Exception): Boolean {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
        if (causes.any {
            it is CancellationException || it is SQLiteFullException || it is SQLiteDatabaseLockedException ||
                it is SQLiteReadOnlyDatabaseException || it is SQLiteCantOpenDatabaseException ||
                it is IOException || it is SecurityException ||
                it.message.orEmpty().lowercase().let { message ->
                    "database is locked" in message || "database table is locked" in message ||
                        "disk is full" in message || "disk i/o error" in message || "permission denied" in message ||
                        "readonly database" in message || "read-only database" in message
                }
        }) return false
        return causes.any { cause ->
            cause is SQLiteDatabaseCorruptException || cause is SQLiteConstraintException ||
                cause.message.orEmpty().let { message ->
                    when (cause) {
                        is IllegalStateException -> "Migration didn't properly handle:" in message ||
                            "Room cannot verify the data integrity" in message ||
                            ("A migration from " in message && "was required but not found" in message) ||
                            "Pre-packaged database has an invalid schema" in message
                        is SQLiteException -> "no such table:" in message || "no such column:" in message ||
                            "duplicate column name:" in message || "file is not a database" in message ||
                            "database disk image is malformed" in message || "Can't downgrade database" in message
                        else -> false
                    }
                }
        }
    }

    private fun propagateControlFailure(failure: Exception) {
        for (cause in generateSequence<Throwable>(failure) { it.cause }.take(16)) {
            if (cause is CancellationException) throw cause
            if (cause is Error) throw cause
        }
    }
}

internal object PreservingOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val delegate = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(delegate.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                delegate.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                delegate.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                val source = configuration.name?.let { configuration.context.getDatabasePath(it) }
                if (source != null) {
                    val guard = DatabaseOpeningGuard.existing(source) ?:
                        DatabaseOpeningGuard.prepare(source, protectOriginal = true)
                    guard?.markFailed()
                }
                throw SQLiteDatabaseCorruptException("Corrupt database retained for storage recovery")
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(callback)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(false)
                .build(),
        )
    }
}
