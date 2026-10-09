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
}
