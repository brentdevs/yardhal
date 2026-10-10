package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun partedChannelsStayPartedUntilCleared() {
        val store = ChannelOrderStore(tmp.root)
        store.markParted("n|#gone")
        assertTrue(store.isParted("n|#gone"))
        assertTrue(ChannelOrderStore(tmp.root).isParted("n|#gone"))

        store.clearParted("n|#gone")
        assertTrue(!store.isParted("n|#gone"))
    }

    @Test
    fun forgetDropsPinGroupMembershipAndPartTombstone() {
        val store = ChannelOrderStore(tmp.root)
        store.togglePin("n|#gone")
        store.createGroup("g1", "G")
        store.addToGroup("g1", "n|#gone")
        store.markParted("n|#gone")
        store.forget("n|#gone")
        val state = store.snapshot()
        assertTrue(state.pinnedKeys.isEmpty())
        assertTrue(state.groups.single().memberKeys.isEmpty())
        assertTrue(!state.isParted("n|#gone"))
    }

    @Test
    fun manualNetworkChannelAndGroupMovesSurviveRestartWithoutChangingMembership() {
        val store = ChannelOrderStore(tmp.root)
        store.createGroup("work", "Work")
        store.createGroup("social", "Social")
        store.addToGroup("work", "n|#a")
        store.addToGroup("work", "n|#b")
        store.addToGroup("work", "other|#hidden")
        store.togglePin("n|#pin-a")
        store.togglePin("other|#pin-hidden")
        store.togglePin("n|#pin-b")
        store.markParted("n|#a")
        store.move(OrderMove(OrderKind.NETWORK, "n", listOf("other", "n"), 0))
        store.move(OrderMove(OrderKind.GROUP, "social", listOf("work", "social"), 0))
        store.move(OrderMove(OrderKind.CHANNEL, "n|#b", listOf("n|#a", "n|#b"), 0))
        store.move(OrderMove(OrderKind.CHANNEL, "n|#pin-b", listOf("n|#pin-a", "n|#pin-b"), 0))
        store.move(OrderMove(OrderKind.CHANNEL, "n|#loose-c", listOf("n|#loose-a", "n|#loose-b", "n|#loose-c"), 0))
        val restarted = ChannelOrderStore(tmp.root).snapshot()
        assertEquals(store.snapshot(), restarted)
        assertEquals(listOf("n", "other"), restarted.networkOrder)
        assertEquals(listOf("social", "work"), restarted.groupOrder)
        assertEquals(listOf("n|#b", "n|#a", "other|#hidden"), restarted.groups.first { it.id == "work" }.memberKeys)
        assertEquals(listOf("n|#pin-b", "other|#pin-hidden", "n|#pin-a"), restarted.pinnedKeys)
        assertEquals(listOf("n|#loose-c", "n|#loose-a", "n|#loose-b"), restarted.channelOrder)
        assertTrue(restarted.isParted("n|#a"))
    }

    @Test
    fun repeatedMovesUseCurrentStateAndManualChannelMovesDisableAlternateUnreadSorting() {
        val store = ChannelOrderStore(tmp.root)
        val peers = listOf("n|#a", "n|#b", "n|#c")
        store.setSortUnreadFirst(true)
        store.move(OrderMove(OrderKind.CHANNEL, peers[0], peers, 2))
        assertTrue(!store.snapshot().sortUnreadFirst)
        assertEquals(listOf(peers[1], peers[2], peers[0]), store.snapshot().channelOrder)
        store.move(OrderMove(OrderKind.CHANNEL, peers[0], store.snapshot().channelOrder, 1))
        assertEquals(listOf(peers[1], peers[0], peers[2]), ChannelOrderStore(tmp.root).snapshot().channelOrder)
        store.setSortUnreadFirst(true)
        assertTrue(ChannelOrderStore(tmp.root).snapshot().sortUnreadFirst)
    }

    @Test
    fun renameAndNetworkDeletionPreserveOtherNetworksAndEmptyGroupIdentity() {
        val store = ChannelOrderStore(tmp.root)
        store.createGroup("g", "Shared")
        store.addToGroup("g", "n|#old")
        store.addToGroup("g", "n10|#keep")
        store.togglePin("n|#old")
        store.markParted("n|#old")
        store.move(OrderMove(OrderKind.NETWORK, "n", listOf("n10", "n"), 0))
        store.move(OrderMove(OrderKind.CHANNEL, "n|#new", listOf("n|#new", "n|#other"), 1))
        store.rename("n|#old", "n|#new")
        assertEquals(listOf("n|#new"), store.snapshot().pinnedKeys)
        assertEquals(listOf("n|#new", "n10|#keep"), store.snapshot().groups.single().memberKeys)
        assertTrue(store.isParted("n|#new"))
        store.forgetNetwork("n")
        val restarted = ChannelOrderStore(tmp.root).snapshot()
        assertEquals(listOf("n10"), restarted.networkOrder)
        assertTrue(restarted.pinnedKeys.isEmpty())
        assertTrue(restarted.channelOrder.isEmpty())
        assertTrue(restarted.partedKeys.isEmpty())
        assertEquals(listOf("n10|#keep"), restarted.groups.single().memberKeys)
        store.forgetNetwork("n10")
        assertEquals("g", ChannelOrderStore(tmp.root).snapshot().groups.single().id)
        assertEquals(listOf("g"), store.snapshot().groupOrder)
    }

    @Test
    fun renameKeepsManualPositionAndDeduplicatesDestinationKeys() {
        val store = ChannelOrderStore(tmp.root)
        store.move(OrderMove(OrderKind.CHANNEL, "n|#old", listOf("n|#first", "n|#old", "n|#last"), 1))
        store.rename("n|#old", "n|#new")
        assertEquals(listOf("n|#first", "n|#new", "n|#last"), store.snapshot().channelOrder)
        store.rename("n|#new", "n|#last")
        assertEquals(listOf("n|#first", "n|#last"), ChannelOrderStore(tmp.root).snapshot().channelOrder)
    }

    @Test
    fun invalidCrossSectionAndCrossNetworkMovesCannotLoseGroupOrPinMetadata() {
        val store = ChannelOrderStore(tmp.root)
        store.createGroup("g", "Group")
        store.addToGroup("g", "n|#group")
        store.togglePin("n|#pin")
        val before = store.snapshot()
        for (peers in listOf(listOf("n|#group", "n|#loose"), listOf("n|#pin", "n|#group"), listOf("n|#loose", "other|#loose"))) {
            assertFailsWith<IllegalArgumentException> { store.move(OrderMove(OrderKind.CHANNEL, peers[0], peers, 1)) }
        }
        assertEquals(before, store.snapshot())
        assertEquals(before, ChannelOrderStore(tmp.root).snapshot())
    }

    @Test
    fun failedSavesRetainPublishedAndRestartedOrderAndIdenticalRetryCommits() {
        val store = ChannelOrderStore(tmp.root)
        store.createGroup("g", "Original")
        store.togglePin("n|#a")
        store.markParted("n|#a")
        store.move(OrderMove(OrderKind.NETWORK, "n", listOf("other", "n"), 0))
        val operations: List<() -> Unit> = listOf(
            { store.move(OrderMove(OrderKind.NETWORK, "other", listOf("n", "other"), 0)) },
            { store.togglePin("n|#b") },
            { store.renameGroup("g", "Changed") },
            { store.moveToGroup("n|#a", "g") },
            { store.rename("n|#a", "n|#renamed") },
            { store.setSortUnreadFirst(true) },
            { store.forgetNetwork("n") },
        )
        for (operation in operations) {
            val before = store.snapshot()
            val blocked = tmp.newFolder("channel-order.json.tmp")
            assertFailsWith<IOException> { operation() }
            assertEquals(before, store.snapshot())
            assertEquals(before, ChannelOrderStore(tmp.root).snapshot())
            assertTrue(blocked.delete())
            operation()
            assertEquals(store.snapshot(), ChannelOrderStore(tmp.root).snapshot())
        }
    }

    @Test
    fun sojuNetworkOrderingUsesDerivedIdentityAcrossDiscoveryRenameAndRestart() {
        val parent = NetworkConfig(id = "6d519ba4-21af-4135-b021-504265f3cd26", name = "Bouncer", host = "bouncer.test", nick = "me", mode = NetworkMode.SOJU)
        val old = SojuUpstreamConfig.reconcile(parent, "42", IrcBouncerNetworks.Attributes(name = "Old"))
        val other = SojuUpstreamConfig.reconcile(parent, "43", IrcBouncerNetworks.Attributes(name = "Other"))
        val store = ChannelOrderStore(tmp.root)
        store.move(OrderMove(OrderKind.NETWORK, old.id, listOf(other.id, old.id), 0))
        store.togglePin("${old.id}|#saved")
        val renamed = SojuUpstreamConfig.reconcile(parent, "42", IrcBouncerNetworks.Attributes(name = "Renamed"), old)
        assertEquals(old.id, renamed.id)
        val restarted = ChannelOrderStore(tmp.root).snapshot()
        assertEquals(listOf(renamed.id, other.id), restarted.networkOrder)
        assertEquals(listOf("${renamed.id}|#saved"), restarted.pinnedKeys)
    }

    @Test
    fun atomicGroupAssignmentRetainsExistingPositionPinsAndPartedState() {
        val store = ChannelOrderStore(tmp.root)
        store.createGroup("first", "First")
        store.createGroup("second", "Second")
        store.addToGroup("first", "n|#a")
        store.addToGroup("first", "n|#b")
        store.togglePin("n|#a")
        store.markParted("n|#a")
        store.moveToGroup("n|#a", "first")
        assertEquals(listOf("n|#a", "n|#b"), store.snapshot().groups.first().memberKeys)
        store.moveToGroup("n|#a", "second")
        val restarted = ChannelOrderStore(tmp.root).snapshot()
        assertEquals(listOf("n|#b"), restarted.groups.first().memberKeys)
        assertEquals(listOf("n|#a"), restarted.groups.last().memberKeys)
        assertEquals(listOf("n|#a"), restarted.pinnedKeys)
        assertTrue(restarted.isParted("n|#a"))
        assertEquals(listOf("first", "second"), restarted.groupOrder)
    }

    @Test
    fun reorderingVisibleSubsetRetainsUnavailableConversationSlots() {
        val store = ChannelOrderStore(tmp.root)
        val all = listOf("n|#a", "n|#hidden", "n|#b", "n|#c")
        store.move(OrderMove(OrderKind.CHANNEL, all[0], all, 0))
        store.markParted(all[1])
        store.move(OrderMove(OrderKind.CHANNEL, all[3], listOf(all[0], all[2], all[3]), 0))
        val restarted = ChannelOrderStore(tmp.root).snapshot()
        assertEquals(listOf(all[3], all[1], all[0], all[2]), restarted.channelOrder)
        assertTrue(restarted.isParted(all[1]))
    }
}
