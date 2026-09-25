package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ChannelOrderStoreTests {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun pinTogglesAndPersists() {
        val store = ChannelOrderStore(tmp.root)
        store.togglePin("n|#a")
        assertEquals(listOf("n|#a"), store.snapshot().pinnedKeys)
        store.togglePin("n|#a")
        assertTrue(store.snapshot().pinnedKeys.isEmpty())
        store.togglePin("n|#b")
        assertEquals(listOf("n|#b"), ChannelOrderStore(tmp.root).snapshot().pinnedKeys)
    }

    @Test
    fun groupsAssignAndRemove() {
        val store = ChannelOrderStore(tmp.root)
        store.createGroup("g1", "Ops")
        store.createGroup("g2", "Bots")
        store.addToGroup("g1", "n|#a")
        store.addToGroup("g1", "n|#b")
        store.addToGroup("g2", "n|#a")

        assertEquals("g1", store.snapshot().groupOf("n|#a")?.id)
        assertNull(store.snapshot().groupOf("n|#unknown"))

        store.removeFromEveryGroup("n|#a")
        assertEquals(listOf("n|#b"), store.snapshot().groups.first { it.id == "g1" }.memberKeys)
        assertTrue(store.snapshot().groups.first { it.id == "g2" }.memberKeys.isEmpty())
    }

    @Test
    fun renameAndDeleteGroup() {
        val store = ChannelOrderStore(tmp.root)
        store.createGroup("g1", "Old")
        store.renameGroup("g1", "New")
        assertEquals("New", store.snapshot().groups.single().name)
        store.deleteGroup("g1")
        assertTrue(store.snapshot().groups.isEmpty())
        assertTrue(store.snapshot().groupOrder.isEmpty())
    }

    @Test
    fun forgetDropsPinAndGroupMembership() {
        val store = ChannelOrderStore(tmp.root)
        store.togglePin("n|#gone")
        store.createGroup("g1", "G")
        store.addToGroup("g1", "n|#gone")
        store.forget("n|#gone")
        val state = store.snapshot()
        assertTrue(state.pinnedKeys.isEmpty())
        assertTrue(state.groups.single().memberKeys.isEmpty())
    }
}
