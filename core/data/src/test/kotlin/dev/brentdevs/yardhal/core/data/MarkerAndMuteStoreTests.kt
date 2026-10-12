package dev.brentdevs.yardhal.core.data

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class MarkerAndMuteStoreTests {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun readMarkerAdvancesMonotonically() {
        val store = ReadMarkerStore(tmp.root)
        assertTrue(store.advance("n|#c", 100))
        assertFalse(store.advance("n|#c", 100))
        assertFalse(store.advance("n|#c", 50))
        assertEquals(100, store.marker("n|#c"))
        assertTrue(store.advance("N|#C", 200))
        assertEquals(0, store.marker("other"))
    }

    @Test
    fun readMarkerPersists() {
        ReadMarkerStore(tmp.root).advance("k", 42)
        assertEquals(42, ReadMarkerStore(tmp.root).marker("k"))
    }

    @Test
    fun unreadFlagReflectsMarker() {
        val store = ReadMarkerStore(tmp.root)
        store.advance("k", 100)
        assertTrue(store.hasUnread("k", 150))
        assertFalse(store.hasUnread("k", 100))
        assertTrue(store.hasUnread("k", 101))
        assertTrue(store.hasUnread("fresh", 5))
    }

    @Test
    fun mutesRoundTrip() {
        val store = MuteStore(tmp.root)
        assertTrue(store.mute("a|b"))
        assertFalse(store.mute("a|b"))
        assertTrue(store.isMuted("a|b"))
        assertFalse(store.isMuted("x|y"))

        val reloaded = MuteStore(tmp.root)
        assertTrue(reloaded.isMuted("a|b"))
        assertTrue(reloaded.unmute("a|b"))
        assertFalse(MuteStore(tmp.root).isMuted("a|b"))
    }
    @Test
    fun failedMuteFileSaveDoesNotAdvanceLiveState() {
        val store = MuteStore(tmp.root)
        assertTrue(store.mute("a|old"))
        assertTrue(File(tmp.root, "mutes.json.tmp").mkdir())
        assertFailsWith<IOException> { store.mute("a|other") }
        assertFailsWith<IOException> { store.unmute("a|old") }
        assertFailsWith<IOException> { store.rename("a|old", "a|new") }
        assertEquals(setOf("a|old"), store.all())
        assertEquals(setOf("a|old"), MuteStore(tmp.root).all())
    }

}
