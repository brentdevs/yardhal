package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ConversationRenameStoreTests {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun readMarkerMovesToNewKeyKeepingTheLaterMarkerAndPersists() {
        val store = ReadMarkerStore(tmp.root)
        store.advance("n|#old", 500)
        store.advance("n|#new", 200)
        assertTrue(store.rename("n|#old", "n|#new"))
        assertEquals(0, store.marker("n|#old"))
        assertEquals(500, store.marker("n|#new"))
        assertEquals(500, ReadMarkerStore(tmp.root).marker("n|#new"))
        assertFalse(store.rename("n|#missing", "n|#x"))
        assertFalse(store.rename("n|#new", "n|#new"))
    }

    @Test
    fun muteFollowsRenameAndPersists() {
        val store = MuteStore(tmp.root)
        store.mute("n|#old")
        assertTrue(store.rename("n|#old", "n|#new"))
        assertFalse(store.isMuted("n|#old"))
        assertTrue(MuteStore(tmp.root).isMuted("n|#new"))
        assertFalse(store.rename("n|#unmuted", "n|#other"))
        assertFalse(store.isMuted("n|#other"))
    }

    @Test
    fun channelOrderRenamesPinsGroupMembershipAndPartedKeysInPlace() {
        val store = ChannelOrderStore(tmp.root)
        store.togglePin("n|#a")
        store.togglePin("n|#old")
        store.togglePin("n|#b")
        store.createGroup("g", "Group")
        store.addToGroup("g", "n|#x")
        store.addToGroup("g", "n|#old")
        store.markParted("n|#old")
        store.rename("n|#old", "n|#new")
        val state = ChannelOrderStore(tmp.root).snapshot()
        assertEquals(listOf("n|#a", "n|#new", "n|#b"), state.pinnedKeys)
        assertEquals(listOf("n|#x", "n|#new"), state.groups.single().memberKeys)
        assertEquals(listOf("n|#new"), state.partedKeys)
    }

    @Test
    fun channelOrderRenameOntoExistingKeyDoesNotDuplicate() {
        val store = ChannelOrderStore(tmp.root)
        store.togglePin("n|#old")
        store.togglePin("n|#new")
        store.rename("n|#old", "n|#new")
        assertEquals(listOf("n|#new"), store.snapshot().pinnedKeys)
    }
}
