package dev.brentdevs.yardhal.core.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DatabaseRecoveryTests {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun context(root: File = tmp.root): Context = object : ContextWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
    ) {
        override fun getDatabasePath(name: String): File {
            require(name == File(name).name)
            return File(root, name)
        }

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    }

    @Test
    fun actualCorruptionRetainsOriginalBytesAndCreatesAnEagerPersistentStoreWithoutRepeatingFailure() {
        val context = context()
        val source = context.getDatabasePath("corrupt-fixture.db")
        val original = ByteArray(1024) { ((it * 37 + 11) % 256).toByte() }
        source.writeBytes(original)
        val wal = File(source.path + "-wal")
        val shm = File(source.path + "-shm")
        val walBytes = ByteArray(513) { (it * 7).toByte() }
        val shmBytes = ByteArray(257) { (it * 11).toByte() }
        wal.writeBytes(walBytes)
        shm.writeBytes(shmBytes)
        DatabaseRecovery.open(context, source.name).use { database ->
            assertTrue(database.isOpen)
            assertFalse(StorageRecovery.databaseTemporary.value)
            assertNotNull(database.messageDao())
        }
        val evidence = StorageRecovery.retainedEvidence(source).single()
        assertContentEquals(original, File(evidence, source.name).readBytes())
        assertContentEquals(walBytes, File(evidence, wal.name).readBytes())
        assertContentEquals(shmBytes, File(evidence, shm.name).readBytes())
        assertTrue(File(evidence, ".retired").isFile)
        assertTrue(StorageRecovery.notices.value.any { it.quarantinePath == evidence.path && !it.temporary })
        DatabaseRecovery.open(context, source.name).use { assertTrue(it.isOpen) }
        assertEquals(listOf(evidence), StorageRecovery.retainedEvidence(source))
    }

    @Test
    fun incompleteSchemaIsRetainedInsteadOfLoopingItsFailedMigration() {
        val context = context()
        val source = context.getDatabasePath("incomplete-fixture.db")
        context.openOrCreateDatabase(source.name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("CREATE TABLE retained_payload(value TEXT NOT NULL)")
            database.execSQL("INSERT INTO retained_payload VALUES ('upgrade evidence')")
            database.version = 1
        }
        DatabaseRecovery.open(context, source.name).use { assertTrue(it.isOpen) }
        val evidence = StorageRecovery.retainedEvidence(source).single()
        SQLiteDatabase.openDatabase(File(evidence, source.name).path, null, SQLiteDatabase.OPEN_READONLY).use { original ->
            assertEquals(1, original.version)
            original.rawQuery("SELECT value FROM retained_payload", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("upgrade evidence", row.getString(0))
            }
        }
        DatabaseRecovery.open(context, source.name).use { assertTrue(it.isOpen) }
        assertEquals(listOf(evidence), StorageRecovery.retainedEvidence(source))
    }

    @Test
    fun futureSchemaRetainsItsPayloadAndStartsOnlyOneFreshPersistentStore() {
        val context = context()
        val source = context.getDatabasePath("future-fixture.db")
        context.openOrCreateDatabase(source.name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("CREATE TABLE future_payload(value TEXT NOT NULL)")
            database.execSQL("INSERT INTO future_payload VALUES ('future history')")
            database.version = 999
        }
        DatabaseRecovery.open(context, source.name).use { assertTrue(it.isOpen) }
        val evidence = StorageRecovery.retainedEvidence(source).single()
        SQLiteDatabase.openDatabase(File(evidence, source.name).path, null, SQLiteDatabase.OPEN_READONLY).use { original ->
            assertEquals(999, original.version)
            original.rawQuery("SELECT value FROM future_payload", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("future history", row.getString(0))
            }
        }
        DatabaseRecovery.open(context, source.name).use { assertTrue(it.isOpen) }
        assertEquals(listOf(evidence), StorageRecovery.retainedEvidence(source))
    }

    @Test
    fun supportedVersionOneUpgradePreservesHistoryIdentifiersHashesAndSearch() = runBlocking {
        val context = context()
        val source = context.getDatabasePath("upgrade-fixture.db")
        val message = StoredMessage(
            rowId = 41,
            networkId = "fixture-network",
            conversation = ConversationRef.channel("fixture-network", "#fixture"),
            msgid = "legacy-msgid",
            senderNick = "fixture-sender",
            senderUser = null,
            senderHost = null,
            kind = MessageKind.PRIVMSG,
            text = "retained legacy history",
            sentByUs = false,
            timestampMs = 1234,
        )
        createVersionOne(context, source.name, message)
        DatabaseRecovery.open(context, source.name).use { database ->
            val store = MessageStore(database.messageDao())
            val restored = store.recent(message.conversation, 10).single()
            assertEquals(message.rowId, restored.rowId)
            assertEquals(message.msgid, restored.msgid)
            assertEquals(message.text, restored.text)
            assertEquals(MessageStore.contentHash(message), database.messageDao().allRows().single().contentHash)
            assertEquals(listOf(message.rowId), store.search("retained").map { it.rowId })
            assertFalse(StorageRecovery.databaseTemporary.value)
        }
        assertTrue(StorageRecovery.retainedEvidence(source).isEmpty())
    }

    @Test
    fun diskFullLocksReadOnlyAndPermissionFailuresDoNotQuarantineValidOriginals() {
        val failures = listOf(
            SQLiteFullException("database or disk is full"),
            SQLiteDatabaseLockedException("database is locked"),
            SQLiteReadOnlyDatabaseException("attempt to write a readonly database"),
            SQLiteCantOpenDatabaseException("unable to open database file"),
            IOException("disk i/o error"),
            SecurityException("permission denied"),
        )
        for ((index, failure) in failures.withIndex()) {
            val context = context(tmp.newFolder("transient-$index"))
            val source = context.getDatabasePath("valid-fixture.db")
            DatabaseRecovery.open(context, source.name).close()
            val original = source.readBytes()
            var attempts = 0
            DatabaseRecovery.open(
                source,
                persistent = { attempts++; throw failure },
                temporary = { YardhalDatabase.inMemory(context) },
            ).use { database ->
                assertTrue(database.isOpen)
                assertTrue(StorageRecovery.databaseTemporary.value)
                database.openHelper.writableDatabase.execSQL("CREATE TABLE temporary_fixture(value TEXT)")
                database.openHelper.writableDatabase.execSQL("INSERT INTO temporary_fixture VALUES ('temporary')")
            }
            assertEquals(1, attempts)
            assertContentEquals(original, source.readBytes())
            assertTrue(StorageRecovery.retainedEvidence(source).isEmpty())
            DatabaseRecovery.open(context, source.name).use { database ->
                database.openHelper.writableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE name = 'temporary_fixture'",
                ).use { assertFalse(it.moveToFirst()) }
                assertFalse(StorageRecovery.databaseTemporary.value)
            }
        }
    }

    @Test
    fun healthyCurrentSchemaLaunchDoesNotCopyTheDatabaseToAnOpeningGuard() {
        val context = context()
        val source = context.getDatabasePath("healthy-fixture.db")
        DatabaseRecovery.open(context, source.name).close()
        DatabaseRecovery.open(context, source.name).use { assertTrue(it.isOpen) }
        assertFalse(File(tmp.root, "storage-opening").exists())
        assertTrue(StorageRecovery.retainedEvidence(source).isEmpty())
    }

    @Test
    fun pendingRollbackJournalRestartsRetainMessagesAndSearchInPersistentStorage() {
        val context = context()
        val source = context.getDatabasePath("rollback-fixture.db")
        DatabaseRecovery.open(context, source.name).use { database ->
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(
                "INSERT INTO messages(rowId,networkId,conversation,msgid,contentHash,senderNick,kind,text,sentByUs,timestampMs) " +
                    "VALUES(41,'network','#room','retained-id','retained-hash','alice','PRIVMSG','retained needle',0,1000)",
            )
            sqlite.execSQL("INSERT INTO message_fts(rowid,sender,body) VALUES(41,'alice','retained needle')")
        }
        File(source.path + "-journal").writeText("interrupted rollback journal")
        DatabaseRecovery.open(context, source.name).use { database ->
            database.openHelper.writableDatabase.query(
                "SELECT rowId,msgid,contentHash,text FROM messages",
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(41L, rows.getLong(0))
                assertEquals("retained-id", rows.getString(1))
                assertEquals("retained-hash", rows.getString(2))
                assertEquals("retained needle", rows.getString(3))
                assertFalse(rows.moveToNext())
            }
            database.openHelper.writableDatabase.query(
                "SELECT rowid FROM message_fts WHERE message_fts MATCH 'needle'",
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(41L, rows.getLong(0))
                assertFalse(rows.moveToNext())
            }
            assertFalse(StorageRecovery.databaseTemporary.value)
        }
        assertTrue(StorageRecovery.retainedEvidence(source).isEmpty())
    }

    @Test
    fun upgradeTransientFailureRestoresOriginalDatabaseAndSidecarBytesWithoutQuarantine() {
        val context = context()
        val source = context.getDatabasePath("transient-upgrade-fixture.db")
        context.openOrCreateDatabase(source.name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("CREATE TABLE upgrade_payload(value TEXT)")
            database.execSQL("INSERT INTO upgrade_payload VALUES ('unchanged valid original')")
            database.version = 1
        }
        val wal = File(source.path + "-wal")
        val shm = File(source.path + "-shm")
        val original = source.readBytes()
        val walBytes = ByteArray(513) { (it * 7).toByte() }
        val shmBytes = ByteArray(257) { (it * 11).toByte() }
        wal.writeBytes(walBytes)
        shm.writeBytes(shmBytes)
        DatabaseRecovery.open(
            source,
            persistent = {
                source.writeText("simulated failed migration checkpoint")
                assertTrue(wal.delete())
                shm.writeText("simulated modified shared memory")
                throw SQLiteFullException("database or disk is full")
            },
            temporary = { YardhalDatabase.inMemory(context) },
        ).use { assertTrue(it.isOpen) }
        assertContentEquals(original, source.readBytes())
        assertContentEquals(walBytes, wal.readBytes())
        assertContentEquals(shmBytes, shm.readBytes())
        assertTrue(StorageRecovery.retainedEvidence(source).isEmpty())
        assertTrue(StorageRecovery.databaseTemporary.value)
    }

    @Test
    fun preservationFailureLeavesSourceBytesAndUsesARealTemporaryDatabase() {
        val context = context()
        val source = context.getDatabasePath("preservation-fixture.db")
        val original = "not a sqlite database".toByteArray()
        source.writeBytes(original)
        File(tmp.root, "storage-quarantine").writeText("unrelated blocker")
        DatabaseRecovery.open(context, source.name).use { database ->
            assertTrue(database.isOpen)
            assertTrue(StorageRecovery.databaseTemporary.value)
        }
        assertContentEquals(original, source.readBytes())
        assertEquals("unrelated blocker", File(tmp.root, "storage-quarantine").readText())
    }

    @Test
    fun completedEvidenceWithInterruptedRetirementIsNeverOpenedOrMigratedAgain() {
        val context = context()
        val source = context.getDatabasePath("interrupted-fixture.db")
        val original = "known failed original".toByteArray()
        source.writeBytes(original)
        var attempts = 0
        DatabaseRecovery.open(
            source,
            persistent = { attempts++; throw SQLiteDatabaseCorruptException("fixture corruption") },
            temporary = { YardhalDatabase.inMemory(context) },
            preserve = { file, files, originals ->
                preserveStorageEvidence(file, files, retireFiles = originals) { directory ->
                    if (directory == tmp.root && !source.exists()) throw IOException("interrupted retirement sync")
                    syncStoreDirectory(directory)
                }
            },
        ).close()
        assertEquals(1, attempts)
        val evidence = StorageRecovery.retainedEvidence(source).single()
        assertContentEquals(original, File(evidence, source.name).readBytes())
        assertTrue(File(evidence, ".complete").isFile)
        assertFalse(File(evidence, ".retired").exists())
        DatabaseRecovery.open(
            source,
            persistent = { attempts++; YardhalDatabase.build(context, source.name, PreservingOpenHelperFactory) },
            temporary = { YardhalDatabase.inMemory(context) },
        ).use { assertTrue(it.isOpen) }
        assertEquals(1, attempts)
        assertTrue(StorageRecovery.databaseTemporary.value)
    }

    @Test
    fun freshPersistentFailureLeavesEvidenceIntactAndFallsBackOnlyOnce() {
        val context = context()
        val source = context.getDatabasePath("replacement-fixture.db")
        val original = "known failed original".toByteArray()
        source.writeBytes(original)
        var attempts = 0
        DatabaseRecovery.open(
            source,
            persistent = {
                attempts++
                if (attempts == 1) throw SQLiteDatabaseCorruptException("fixture corruption")
                throw SQLiteFullException("disk full")
            },
            temporary = { YardhalDatabase.inMemory(context) },
        ).use { assertTrue(it.isOpen) }
        assertEquals(2, attempts)
        assertTrue(StorageRecovery.databaseTemporary.value)
        val evidence = StorageRecovery.retainedEvidence(source).single()
        assertContentEquals(original, File(evidence, source.name).readBytes())
        assertTrue(StorageRecovery.notices.value.any { it.quarantinePath == evidence.path && it.temporary })
    }

    @Test
    fun cancellationAndProgrammingErrorsAreNotConvertedToEmptySessions() {
        val context = context()
        val source = context.getDatabasePath("control-fixture.db")
        for (failure in listOf(CancellationException("cancelled"), IllegalStateException("programming error"))) {
            assertFailsWith<IllegalStateException> {
                DatabaseRecovery.open(source, persistent = { throw failure }, temporary = { error("must not recover") })
            }
        }
        assertFailsWith<OutOfMemoryError> {
            DatabaseRecovery.open(source, persistent = { throw OutOfMemoryError("fixture fatal") }, temporary = { error("must not recover") })
        }
        assertTrue(StorageRecovery.retainedEvidence(source).isEmpty())
    }

    @Test
    fun transientCauseTakesPrecedenceOverAnOuterMigrationIntegrityMessage() {
        assertFalse(
            DatabaseRecovery.isIntegrityFailure(
                IllegalStateException("Migration didn't properly handle: messages", SQLiteFullException("full")),
            ),
        )
    }

    private fun createVersionOne(context: Context, name: String, message: StoredMessage) {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL(
                "CREATE TABLE messages (rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, networkId TEXT NOT NULL, " +
                    "conversation TEXT NOT NULL, msgid TEXT, contentHash TEXT NOT NULL, senderNick TEXT NOT NULL, " +
                    "senderUser TEXT, senderHost TEXT, kind TEXT NOT NULL, text TEXT NOT NULL, " +
                    "sentByUs INTEGER NOT NULL, timestampMs INTEGER NOT NULL)",
            )
            database.execSQL("CREATE UNIQUE INDEX index_messages_msgid ON messages(msgid)")
            database.execSQL("CREATE INDEX index_messages_networkId_conversation_timestampMs ON messages(networkId, conversation, timestampMs)")
            database.execSQL("CREATE INDEX index_messages_contentHash ON messages(contentHash)")
            database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            database.execSQL("INSERT INTO room_master_table VALUES (42, '6b750802119638f32c9bf2fbe6e7526b')")
            database.execSQL("CREATE VIRTUAL TABLE message_fts USING fts4(sender, body, tokenize=unicode61)")
            database.execSQL(
                "INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, 0, ?)",
                arrayOf<Any>(
                    message.rowId, message.networkId, message.conversation.normalizedTarget, message.msgid.orEmpty(),
                    MessageStore.contentHash(message), message.senderNick, message.kind.name, message.text, message.timestampMs,
                ),
            )
            database.execSQL("INSERT INTO message_fts(rowid, sender, body) VALUES (?, ?, ?)", arrayOf<Any>(message.rowId, message.senderNick, message.text))
            database.version = 1
        }
    }
}

private inline fun <T> YardhalDatabase.use(block: (YardhalDatabase) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
