package dev.brentdevs.yardhal.core.data

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class StorageRecoveryTests {
    @get:Rule
    val tmp = TemporaryFolder()

    @BeforeTest
    fun clearSessionNotices() {
        StorageRecovery.clearSessionNotices()
    }

    @Test
    fun corruptionPreservesExactDatabaseWalShmAndJournalBytesInUniqueDirectories() {
        val database = File(tmp.root, "fixture.db")
        val files = listOf(database, File(tmp.root, "fixture.db-wal"), File(tmp.root, "fixture.db-shm"), File(tmp.root, "fixture.db-journal"))
        val bytes = files.mapIndexed { index, _ -> ByteArray(4097) { (it * 29 + index).toByte() } }
        val evidence = (0 until 2).map {
            files.zip(bytes).forEach { (file, original) -> file.writeBytes(original) }
            preserveStorageEvidence(database, files)
        }
        assertEquals(2, evidence.toSet().size)
        for (directory in evidence) {
            for ((file, original) in files.zip(bytes)) {
                assertFalse(file.exists())
                assertContentEquals(original, File(directory, file.name).readBytes())
            }
            assertTrue(File(directory, ".complete").isFile)
            assertTrue(File(directory, ".retired").isFile)
        }
    }

    @Test
    fun preservationSyncFailureNeverDeletesAnySourceBeforeAllEvidenceIsDurable() {
        val database = File(tmp.root, "fixture.db")
        val wal = File(tmp.root, "fixture.db-wal")
        val original = byteArrayOf(0, 1, -1, 42)
        val walOriginal = byteArrayOf(9, 8, 7, 6)
        database.writeBytes(original)
        wal.writeBytes(walOriginal)
        val failure = assertFailsWith<EvidencePreservationException> {
            preserveStorageEvidence(database, listOf(database, wal)) { directory ->
                if (File(directory, ".complete").exists()) throw IOException("fixture directory fsync failure")
                syncStoreDirectory(directory)
            }
        }
        assertContentEquals(original, database.readBytes())
        assertContentEquals(walOriginal, wal.readBytes())
        val directory = requireNotNull(failure.directory)
        assertContentEquals(original, File(directory, database.name).readBytes())
        assertContentEquals(walOriginal, File(directory, wal.name).readBytes())
        assertFalse(File(directory, ".retired").exists())
    }

    @Test
    fun invalidJsonRetainsExactBytesBeforeLoadingEmptyAndNewChangesRemainDurable() {
        val file = File(tmp.root, "fixture.json")
        val original = "  [ unfinished\n\t".toByteArray()
        file.writeBytes(original)
        val store = JsonFileStore(file, String.serializer())
        assertEquals("empty", store.loadOrDefault("empty"))
        assertFalse(file.exists())
        val directory = StorageRecovery.retainedEvidence(file).single()
        assertContentEquals(original, File(directory, file.name).readBytes())
        assertTrue(StorageRecovery.notices.value.any { it.quarantinePath == directory.path && !it.temporary })
        store.save("new persistent state")
        assertEquals("new persistent state", JsonFileStore(file, String.serializer()).loadOrDefault("empty"))
        assertContentEquals(original, File(directory, file.name).readBytes())
    }

    @Test
    fun malformedUtf8IsNotSilentlyDecodedUsingReplacementCharacters() {
        val file = File(tmp.root, "fixture.json")
        val original = byteArrayOf('"'.code.toByte(), 0xc3.toByte(), 0x28, '"'.code.toByte())
        file.writeBytes(original)
        assertEquals("empty", JsonFileStore(file, String.serializer()).loadOrDefault("empty"))
        val directory = StorageRecovery.retainedEvidence(file).single()
        assertContentEquals(original, File(directory, file.name).readBytes())
    }

    @Test
    fun saveBeforeLoadAlsoPreservesCorruptOriginal() {
        val file = File(tmp.root, "fixture.json")
        val original = "{ malformed original".toByteArray()
        file.writeBytes(original)
        JsonFileStore(file, String.serializer()).save("new persistent state")
        val directory = StorageRecovery.retainedEvidence(file).single()
        assertContentEquals(original, File(directory, file.name).readBytes())
        assertEquals("new persistent state", JsonFileStore(file, String.serializer()).loadOrDefault("empty"))
    }

    @Test
    fun failedJsonPreservationBlocksReplacementAndKeepsOriginalBytes() {
        val file = File(tmp.root, "fixture.json")
        val original = "corrupt original".toByteArray()
        file.writeBytes(original)
        File(tmp.root, "storage-quarantine").writeText("unrelated blocker")
        val store = JsonFileStore(file, String.serializer())
        assertEquals("empty", store.loadOrDefault("empty"))
        assertFailsWith<IOException> { store.save("must not replace evidence") }
        assertContentEquals(original, file.readBytes())
        assertEquals("unrelated blocker", File(tmp.root, "storage-quarantine").readText())
        assertTrue(StorageRecovery.notices.value.last().temporary)
        assertEquals(null, StorageRecovery.notices.value.last().quarantinePath)
    }

    @Test
    fun interruptedJsonEvidenceSyncKeepsOriginalAndLaterInstancesDoNotDecodeOrOverwriteIt() {
        val file = File(tmp.root, "fixture.json")
        val original = "corrupt original".toByteArray()
        file.writeBytes(original)
        val store = JsonFileStore(file, String.serializer(), Json) { directory ->
            if (File(directory, ".complete").exists()) throw IOException("fixture evidence sync failure")
            syncStoreDirectory(directory)
        }
        assertEquals("empty", store.loadOrDefault("empty"))
        assertContentEquals(original, file.readBytes())
        assertFailsWith<IOException> { store.save("must not overwrite") }
        val later = JsonFileStore(file, String.serializer())
        assertEquals("empty", later.loadOrDefault("empty"))
        assertFailsWith<IOException> { later.save("must not overwrite") }
        assertContentEquals(original, file.readBytes())
        assertEquals(1, StorageRecovery.retainedEvidence(file).size)
    }

    @Test
    fun unreadableJsonLocationIsNotMisclassifiedAsCorruptionOrOverwritten() {
        val file = tmp.newFolder("fixture.json")
        val sentinel = File(file, "unrelated")
        sentinel.writeText("keep")
        val store = JsonFileStore(file, String.serializer())
        assertEquals("empty", store.loadOrDefault("empty"))
        assertFailsWith<IOException> { store.save("must not overwrite") }
        assertEquals("keep", sentinel.readText())
        assertTrue(StorageRecovery.retainedEvidence(file).isEmpty())
    }

    @Test
    fun decoderCancellationAndFatalErrorsArePropagatedWithoutQuarantine() {
        val file = File(tmp.root, "fixture.json")
        val original = "\"valid data\"".toByteArray()
        file.writeBytes(original)
        for (failure in listOf(CancellationException("fixture cancellation"), OutOfMemoryError("fixture fatal"))) {
            val serializer = object : KSerializer<String> by String.serializer() {
                override fun deserialize(decoder: Decoder): String = throw failure
            }
            if (failure is CancellationException) {
                assertFailsWith<CancellationException> { JsonFileStore(file, serializer).loadOrDefault("empty") }
            } else {
                assertFailsWith<OutOfMemoryError> { JsonFileStore(file, serializer).loadOrDefault("empty") }
            }
        }
        assertContentEquals(original, file.readBytes())
        assertTrue(StorageRecovery.retainedEvidence(file).isEmpty())
    }

    @Test
    fun invalidPersistedNetworkValuesRetainExactBytesBeforeEmptyRecoveredConfiguration() {
        val invalid = listOf(
            """[{"id":"fixture","name":"Fixture","host":"irc.fixture","nick":"tester","port":0}]""",
            """[{"id":"","name":"Fixture","host":"irc.fixture","nick":"tester"}]""",
            """[{"id":"fixture","name":"Fixture","host":"","nick":"tester"}]""",
            """[{"id":"fixture","name":"Fixture","host":"irc.fixture","nick":""}]""",
            """[{"id":"fixture","name":"Fixture","host":"irc.fixture","nick":"tester","proxy":{"host":"proxy.fixture","port":0}}]""",
            """[{"id":"fixture","name":"Fixture","host":"irc.fixture","nick":"tester","certificatePin":{"host":"irc.fixture","port":6697,"sha256":"invalid"}}]""",
        )
        for ((index, text) in invalid.withIndex()) {
            val directory = tmp.newFolder("invalid-network-$index")
            val file = File(directory, "networks.json")
            val original = " \n$text\t".toByteArray()
            file.writeBytes(original)
            val store = NetworkStore(directory)
            assertTrue(store.all().isEmpty())
            val evidence = StorageRecovery.retainedEvidence(file).single()
            assertContentEquals(original, File(evidence, file.name).readBytes())
            assertTrue(StorageRecovery.notices.value.any { it.quarantinePath == evidence.path && !it.temporary })
            val valid = NetworkConfig(id = "new", name = "New", host = "irc.fixture", nick = "tester")
            assertTrue(store.add(valid))
            assertEquals(listOf(valid), NetworkStore(directory).all())
            assertContentEquals(original, File(evidence, file.name).readBytes())
            StorageRecovery.clearSessionNotices()
            NetworkStore(directory).all()
            assertTrue(StorageRecovery.notices.value.any { it.quarantinePath == evidence.path })
        }
    }

    @Test
    fun invalidPersistedReadCursorValuesAndRootShapeRetainExactBytesBeforeEmptyMarkers() {
        val invalid = listOf(
            """{"fixture|CHANNEL|#room":"not-a-timestamp"}""",
            """{"fixture|CHANNEL|#room":9223372036854775808}""",
            """{"fixture|CHANNEL|#room":true}""",
            """{"fixture|CHANNEL|#room":null}""",
            """{"fixture|CHANNEL|#room":{"cursor":{"timestampMs":"not-a-timestamp","rowId":1}}}""",
            """{"fixture|CHANNEL|#room":{"cursor":{"timestampMs":1,"rowId":"invalid"}}}""",
            """[]""",
        )
        for ((index, text) in invalid.withIndex()) {
            val directory = tmp.newFolder("invalid-cursor-$index")
            val file = File(directory, "read-markers.json")
            val original = "\t$text \n".toByteArray()
            file.writeBytes(original)
            val store = ReadMarkerStore(directory)
            assertTrue(store.all().isEmpty())
            val evidence = StorageRecovery.retainedEvidence(file).single()
            assertContentEquals(original, File(evidence, file.name).readBytes())
            assertTrue(StorageRecovery.notices.value.any { it.quarantinePath == evidence.path && !it.temporary })
            assertTrue(store.advance("fixture|CHANNEL|#room", 1234, 41))
            assertEquals(ReadCursor(1234, 41), ReadMarkerStore(directory).cursor("fixture|CHANNEL|#room"))
            assertContentEquals(original, File(evidence, file.name).readBytes())
        }
    }

    @Test
    fun arbitraryDeserializerProgrammingArgumentsAreNotTreatedAsCorruptPersistedInput() {
        val file = File(tmp.root, "fixture.json")
        val original = "\"valid persisted data\"".toByteArray()
        file.writeBytes(original)
        val serializer = object : KSerializer<String> by String.serializer() {
            override fun deserialize(decoder: Decoder): String = throw IllegalArgumentException("programming error")
        }
        assertFailsWith<IllegalArgumentException> { JsonFileStore(file, serializer).loadOrDefault("empty") }
        assertContentEquals(original, file.readBytes())
        assertTrue(StorageRecovery.retainedEvidence(file).isEmpty())
    }
}
