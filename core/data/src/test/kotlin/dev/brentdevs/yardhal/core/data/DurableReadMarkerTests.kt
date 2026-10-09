package dev.brentdevs.yardhal.core.data

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DurableReadMarkerTests {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun legacyNumericMarkersLoadWithoutLosingTimestampPrecedence() {
        File(tmp.root, "read-markers.json").writeText("{\"n1|#room\":123,\"n1|bob\":456}")
        val store = ReadMarkerStore(tmp.root)
        assertEquals(ReadCursor(123, Long.MAX_VALUE), store.cursor("n1|#room"))
        assertFalse(store.advance("n1|#room", 123, 10))
        assertTrue(store.pending("n1").isEmpty())
        assertTrue(store.advance("n1|#room", 124, 11))
        val reopened = ReadMarkerStore(tmp.root)
        assertEquals(ReadCursor(124, 11), reopened.cursor("n1|#room"))
        assertEquals(mapOf("n1|#room" to ReadCursor(124, 11)), reopened.pending("n1"))
        assertEquals(456, reopened.marker("n1|bob"))
    }

    @Test
    fun equalTimestampLocalAdvancesSurviveReopenAndOldReplayAcknowledgment() {
        val store = ReadMarkerStore(tmp.root)
        assertTrue(store.advance("n1|#room", 1000, 10))
        assertTrue(store.advance("n1|#room", 1000, 11))
        assertFalse(store.advance("n1|#room", 1000, 9))
        assertFalse(store.acknowledge("n1|#room", ReadCursor(1000, 10)))
        val reopened = ReadMarkerStore(tmp.root)
        assertEquals(ReadCursor(1000, 11), reopened.cursor("n1|#room"))
        assertEquals(ReadCursor(1000, 11), reopened.pending("n1")["n1|#room"])
        assertTrue(reopened.acknowledge("n1|#room", ReadCursor(1000, 11)))
        assertTrue(ReadMarkerStore(tmp.root).pending("n1").isEmpty())
    }

    @Test
    fun olderRemoteMarkersNeverRegressOrClearNewerOfflinePending() {
        val store = ReadMarkerStore(tmp.root)
        store.advance("n1|#room", 2000, 20)
        assertFalse(store.reconcileRemote("n1|#room", 1000))
        assertEquals(ReadCursor(2000, 20), store.cursor("n1|#room"))
        assertEquals(ReadCursor(2000, 20), store.pending("n1")["n1|#room"])
        assertTrue(store.reconcileRemote("n1|#room", 2000))
        assertEquals(ReadCursor(2000, Long.MAX_VALUE), store.cursor("n1|#room"))
        assertTrue(store.pending("n1").isEmpty())
        assertFalse(store.advance("n1|#room", 2000, 30))
        assertTrue(store.reconcileRemote("n1|#room", 3000))
        assertFalse(store.reconcileRemote("n1|#room", 2500))
        assertEquals(3000, ReadMarkerStore(tmp.root).marker("n1|#room"))
    }

    @Test
    fun renameCollisionUsesNewestCursorAndPendingThenDeletesOnlyRequestedNetwork() {
        val store = ReadMarkerStore(tmp.root)
        store.advance("n1|old", 2000, 20)
        store.advance("n1|new", 2000, 21)
        store.advance("n2|new", 9000, 90)
        assertTrue(store.rename("n1|old", "n1|new"))
        assertEquals(ReadCursor(2000, 21), store.cursor("n1|new"))
        assertEquals(ReadCursor(), store.cursor("n1|old"))
        assertEquals(mapOf("n1|new" to ReadCursor(2000, 21)), store.pending("n1"))
        store.deleteNetwork("n1")
        val reopened = ReadMarkerStore(tmp.root)
        assertTrue(reopened.pending("n1").isEmpty())
        assertEquals(ReadCursor(9000, 90), reopened.cursor("n2|new"))
    }

    @Test
    fun networkRetentionPrunesOnlyDroppedCursorAndPendingKeysAtomically() {
        val store = ReadMarkerStore(tmp.root)
        store.advance("n1|kept", 1000, 10)
        store.advance("n1|dropped", 2000, 20)
        store.advance("n2|dropped", 3000, 30)
        assertTrue(store.retainNetwork("n1", setOf("n1|kept")))
        assertFalse(store.retainNetwork("n1", setOf("n1|kept")))
        val reopened = ReadMarkerStore(tmp.root)
        assertEquals(ReadCursor(1000, 10), reopened.cursor("n1|kept"))
        assertEquals(ReadCursor(), reopened.cursor("n1|dropped"))
        assertEquals(mapOf("n1|kept" to ReadCursor(1000, 10)), reopened.pending("n1"))
        assertEquals(ReadCursor(3000, 30), reopened.cursor("n2|dropped"))
    }

    @Test
    fun failedAtomicSaveDoesNotPublishCursorPendingOrRename() {
        var failing = false
        val fileStore = JsonFileStore(
            File(tmp.root, "read-markers.json"), ReadMarkerSerializer,
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
        ) { if (failing) throw IOException("injected directory sync failure") }
        val store = ReadMarkerStore(fileStore)
        assertTrue(store.advance("n1|old", 1000, 10))
        failing = true
        assertFailsWith<IOException> { store.advance("n1|old", 2000, 20) }
        assertEquals(ReadCursor(1000, 10), store.cursor("n1|old"))
        assertEquals(ReadCursor(1000, 10), store.pending("n1")["n1|old"])
        assertFailsWith<IOException> { store.rename("n1|old", "n1|new") }
        assertEquals(ReadCursor(1000, 10), store.cursor("n1|old"))
        assertEquals(ReadCursor(), store.cursor("n1|new"))
        assertFailsWith<IOException> { store.retainNetwork("n1", emptySet()) }
        assertEquals(ReadCursor(1000, 10), store.cursor("n1|old"))
        assertEquals(ReadCursor(1000, 10), store.pending("n1")["n1|old"])
    }

    @Test
    fun blockedRecoveryUsesExplicitVolatileCursorsWithoutReplacingOriginalBytes() {
        StorageRecovery.clearSessionNotices()
        val file = File(tmp.root, "read-markers.json")
        val original = "malformed read marker original".toByteArray()
        file.writeBytes(original)
        File(tmp.root, "storage-quarantine").writeText("unrelated preservation blocker")
        val store = ReadMarkerStore(tmp.root)
        assertEquals(ReadMarkerPersistence.VOLATILE, store.persistence.value)
        assertTrue(store.advance("n1|old", 1000, 10))
        assertEquals(ReadCursor(1000, 10), store.cursor("n1|old"))
        assertEquals(ReadCursor(1000, 10), store.pending("n1")["n1|old"])
        assertTrue(store.rename("n1|old", "n1|new"))
        assertTrue(store.acknowledge("n1|new", ReadCursor(1000, 10)))
        assertTrue(store.reconcileRemote("n1|new", 2000))
        assertEquals(ReadCursor(2000, Long.MAX_VALUE), store.cursor("n1|new"))
        assertTrue(store.advance("n2|kept", 3000, 30))
        assertTrue(store.retainNetwork("n1", emptySet()))
        assertEquals(ReadCursor(3000, 30), store.cursor("n2|kept"))
        store.deleteNetwork("n2")
        assertTrue(store.all().isEmpty())
        kotlin.test.assertContentEquals(original, file.readBytes())
        assertTrue(StorageRecovery.notices.value.any { it.temporary })
        assertTrue(StorageRecovery.notices.value.filter { it.temporary }.all { !StorageRecovery.acknowledge(it) })
        assertTrue(ReadMarkerStore(tmp.root).all().isEmpty())
        assertEquals(ReadMarkerPersistence.VOLATILE, store.persistence.value)
    }

    @Test
    fun volatilePendingMarkersDisappearAcrossRestartInsteadOfClaimingDurability() {
        val blocked = tmp.newFolder("read-markers.json")
        File(blocked, "unrelated").writeText("keep")
        val store = ReadMarkerStore(tmp.root)
        assertTrue(store.advance("n1|#room", 1000, 10))
        assertEquals(mapOf("n1|#room" to ReadCursor(1000, 10)), store.pending("n1"))
        assertEquals(ReadMarkerPersistence.VOLATILE, store.persistence.value)
        val restarted = ReadMarkerStore(tmp.root)
        assertEquals(ReadCursor(), restarted.cursor("n1|#room"))
        assertTrue(restarted.pending("n1").isEmpty())
        assertEquals("keep", File(blocked, "unrelated").readText())
    }

    @Test
    fun blockedReadMarkersBecomeDurableOnNextLaunchAfterRecoveryCanFinish() {
        val file = File(tmp.root, "read-markers.json")
        val original = "failed original".toByteArray()
        file.writeBytes(original)
        val fileStore = JsonFileStore(file, ReadMarkerSerializer, Json) { directory ->
            if (File(directory, ".planned").isFile) throw IOException("fixture preservation interrupted")
            syncStoreDirectory(directory)
        }
        val store = ReadMarkerStore(fileStore)
        assertTrue(store.advance("n1|#room", 1000, 10))
        assertEquals(ReadMarkerPersistence.VOLATILE, store.persistence.value)
        val restarted = ReadMarkerStore(tmp.root)
        assertEquals(ReadMarkerPersistence.DURABLE, restarted.persistence.value)
        assertEquals(ReadCursor(), restarted.cursor("n1|#room"))
        assertTrue(restarted.advance("n1|#room", 2000, 20))
        assertEquals(ReadCursor(2000, 20), ReadMarkerStore(tmp.root).cursor("n1|#room"))
        val evidence = StorageRecovery.retainedEvidence(file).single()
        kotlin.test.assertContentEquals(original, File(evidence, file.name).readBytes())
    }

    @Test
    fun readMarkerProgrammerAndCancellationFailuresNeverPublishVolatileSuccess() {
        val file = File(tmp.root, "read-markers.json")
        var failure: RuntimeException? = null
        val fileStore = JsonFileStore(file, ReadMarkerSerializer, Json) { directory ->
            failure?.let { throw it }
            syncStoreDirectory(directory)
        }
        val store = ReadMarkerStore(fileStore)
        assertTrue(store.advance("n1|#room", 1000, 10))
        failure = IllegalArgumentException("fixture programmer error")
        assertFailsWith<IllegalArgumentException> { store.advance("n1|#room", 2000, 20) }
        failure = java.util.concurrent.CancellationException("fixture cancellation")
        assertFailsWith<java.util.concurrent.CancellationException> { store.advance("n1|#room", 3000, 30) }
        assertEquals(ReadCursor(1000, 10), store.cursor("n1|#room"))
        assertEquals(ReadCursor(1000, 10), store.pending("n1")["n1|#room"])
        assertEquals(ReadMarkerPersistence.DURABLE, store.persistence.value)
    }
}
