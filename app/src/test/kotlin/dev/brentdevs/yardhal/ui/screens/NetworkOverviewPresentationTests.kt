package dev.brentdevs.yardhal.ui.screens

import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.coordinator.UiNetwork
import dev.brentdevs.yardhal.core.data.ChannelOrderState
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ChannelMember
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.NicknameSuggestionOrder
import dev.brentdevs.yardhal.core.data.OrderKind
import dev.brentdevs.yardhal.core.data.OrderMove
import dev.brentdevs.yardhal.core.data.ConversationRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class NetworkOverviewPresentationTests {
    @get:Rule
    val tmp = TemporaryFolder()

    private val network = UiNetwork("network", "Saved network", "irc.example.test", ConnectionStatus.DISCONNECTED, "alice")

    @Test
    fun collapsedNetworkUsesFullHistoryCountsWithoutMutedServerOrOtherNetworkBuffers() {
        val channel = buffer("#saved", 740, 12)
        val direct = ConversationBuffer(ConversationRef.directMessage(network.id, "bob"), "bob", hasUnread = true, unreadCount = 35, mentionCount = 2)
        val muted = buffer("#muted", 900, 80).copy(mentionCountKnown = false)
        val server = ConversationBuffer(ConversationRef.server(network.id), "Server", hasUnread = true, unreadCount = 40, mentionCount = 8)
        val otherNetwork = channel.copy(ref = ConversationRef.channel("other", "#saved"))
        val entries = buildOverviewEntries(
            listOf(network), listOf(channel, direct, muted, server, otherNetwork), setOf(muted.key), ChannelOrderState(), setOf(network.id),
        )
        val header = entries.filterIsInstance<OverviewEntry.NetworkHeader>().single()

        assertTrue(header.isCollapsed)
        assertTrue(header.hasUnread)
        assertEquals(775, header.unreadCount)
        assertEquals(14, header.mentionCount)
        assertTrue(header.mentionCountKnown)
        assertTrue(entries.none { it is OverviewEntry.BufferRow })
    }

    @Test
    fun mutedOnlyUnreadDoesNotLightNetworkBadgeButBufferCountsRemainOnExpandedRows() {
        val muted = buffer("#muted", 740, 12).copy(mentionCountKnown = false)
        val quiet = buffer("#quiet", 0, 0)
        val entries = buildOverviewEntries(listOf(network), listOf(muted, quiet), setOf(muted.key), ChannelOrderState(), emptySet())
        val header = entries.filterIsInstance<OverviewEntry.NetworkHeader>().single()
        val mutedRow = entries.filterIsInstance<OverviewEntry.BufferRow>().single { it.buffer.key == muted.key }

        assertFalse(header.hasUnread)
        assertEquals(0, header.unreadCount)
        assertEquals(0, header.mentionCount)
        assertTrue(header.mentionCountKnown)
        assertTrue(mutedRow.muted)
        assertEquals(740, conversationUnreadCount(mutedRow.buffer))
        assertEquals(12, conversationMentionCount(mutedRow.buffer))
        assertFalse(mutedRow.buffer.mentionCountKnown)
    }

    @Test
    fun unmutingRestoresFullCountsAndUnknownMentionStateWithoutLoadingMessagePages() {
        val saved = buffer("#saved", 740, 12).copy(mentionCountKnown = false)
        val mutedHeader = buildOverviewEntries(listOf(network), listOf(saved), setOf(saved.key), ChannelOrderState(), setOf(network.id))
            .filterIsInstance<OverviewEntry.NetworkHeader>().single()
        assertEquals(0, mutedHeader.unreadCount)
        assertTrue(mutedHeader.mentionCountKnown)
        val header = buildOverviewEntries(listOf(network), listOf(saved), emptySet(), ChannelOrderState(), setOf(network.id))
            .filterIsInstance<OverviewEntry.NetworkHeader>().single()

        assertTrue(header.hasUnread)
        assertEquals(740, header.unreadCount)
        assertEquals(12, header.mentionCount)
        assertFalse(header.mentionCountKnown)
        assertTrue(saved.messages.isEmpty())
    }

    @Test
    fun restartedManualOrderingRendersAcrossNetworkNamesUnreadPinsAndGroups() {
        val store = ChannelOrderStore(tmp.root)
        val other = network.copy(id = "other", name = "Alphabetically first")
        val a = buffer("#a", 80, 2)
        val z = buffer("#z", 0, 0)
        val pin = buffer("#pin", 0, 0)
        val groupA = buffer("#group-a", 0, 0)
        val groupB = buffer("#group-b", 0, 0)
        store.createGroup("work", "Work")
        store.createGroup("social", "Social")
        store.addToGroup("work", groupA.key)
        store.addToGroup("social", groupB.key)
        store.togglePin(pin.key)
        store.addToGroup("work", pin.key)
        store.move(OrderMove(OrderKind.NETWORK, network.id, listOf(other.id, network.id), 0))
        store.move(OrderMove(OrderKind.GROUP, "social", listOf("work", "social"), 0))
        store.move(OrderMove(OrderKind.CHANNEL, z.key, listOf(a.key, z.key), 0))
        val order = ChannelOrderStore(tmp.root).snapshot()
        val entries = buildOverviewEntries(listOf(other, network), listOf(a, z, pin, groupA, groupB), emptySet(), order, emptySet())
        assertEquals(listOf(network.id, other.id), entries.filterIsInstance<OverviewEntry.NetworkHeader>().map { it.network.id })
        assertEquals(listOf(pin.key, groupB.key, groupA.key, z.key, a.key), entries.filterIsInstance<OverviewEntry.BufferRow>().map { it.buffer.key })
        assertEquals(listOf("Pinned", "Social", "Work", "Channels"), entries.filterIsInstance<OverviewEntry.SectionHeader>().map { it.label })
        assertTrue(entries.filterIsInstance<OverviewEntry.BufferRow>().first().inGroup)
        store.setSortUnreadFirst(true)
        val alternate = buildOverviewEntries(listOf(network), listOf(a, z), emptySet(), store.snapshot(), emptySet())
        assertEquals(listOf(a.key, z.key), alternate.filterIsInstance<OverviewEntry.BufferRow>().map { it.buffer.key })
        store.setSortUnreadFirst(false)
        val manual = buildOverviewEntries(listOf(network), listOf(a, z), emptySet(), ChannelOrderStore(tmp.root).snapshot(), emptySet())
        assertEquals(listOf(z.key, a.key), manual.filterIsInstance<OverviewEntry.BufferRow>().map { it.buffer.key })
    }

    @Test
    fun orderingSectionsKeepPinnedAndSameNamedGroupsSeparateAndRetainEmptyGroups() {
        val store = ChannelOrderStore(tmp.root)
        store.createGroup("first", "Same")
        store.createGroup("second", "Same")
        store.createGroup("empty", "Empty")
        val pinned = buffer("#pin", 0, 0)
        val first = buffer("#first", 0, 0)
        val second = buffer("#second", 0, 0)
        store.togglePin(pinned.key)
        store.addToGroup("first", pinned.key)
        store.addToGroup("first", first.key)
        store.addToGroup("second", second.key)
        val entries = buildOverviewEntries(listOf(network), listOf(pinned, first, second), emptySet(), store.snapshot(), emptySet())
        val sections = orderingSections(entries, store.snapshot())
        assertEquals(listOf("first", "second", "empty"), sections.single { it.kind == OrderKind.GROUP }.items.map { it.key })
        assertEquals(listOf(listOf(pinned.key), listOf(first.key), listOf(second.key)), sections.filter { it.kind == OrderKind.CHANNEL }.map { it.items.map { item -> item.key } })
        assertEquals(3, sections.filter { it.kind == OrderKind.CHANNEL }.map { it.id }.distinct().size)
    }

    @Test
    fun nicknameSuggestionModesSortRolesAlphabeticallyAndLatestObservedActivityWithoutChangingMembers() {
        val members = listOf(ChannelMember("zed", '@'), ChannelMember("amy"), ChannelMember("voice", '+'), ChannelMember("Owner", '~'), ChannelMember("Half", '%'))
        val buffer = buffer("#room", 0, 0).copy(
            members = members,
            messages = listOf(
                ChatMessage(1, "AMY", MessageKind.PRIVMSG, "new", 300, false, false, "one"),
                ChatMessage(2, "zed", MessageKind.PRIVMSG, "middle", 200, false, false, "two"),
                ChatMessage(3, "amy", MessageKind.PRIVMSG, "old playback", 100, false, false, "three", playback = true),
            ),
        )
        assertEquals(listOf("Owner", "zed", "Half", "voice", "amy"), nicknameSuggestions(buffer, NicknameSuggestionOrder.ROLE))
        assertEquals(listOf("amy", "Half", "Owner", "voice", "zed"), nicknameSuggestions(buffer, NicknameSuggestionOrder.ALPHABETICAL))
        assertEquals(listOf("amy", "zed", "Half", "Owner", "voice"), nicknameSuggestions(buffer, NicknameSuggestionOrder.RECENT))
        assertEquals(members, buffer.members)
    }

    @Test
    fun hiddenUnreadCountsPreserveMentionAndUnknownCoverageIndicatorsWithoutNumbers() {
        assertEquals("•", overviewBadgeLabel(740, 0, true, false))
        assertEquals("@", overviewBadgeLabel(740, 12, true, false))
        assertEquals("@", overviewBadgeLabel(740, 0, false, false))
        assertEquals("99+ · @12", overviewBadgeLabel(740, 12, true, true))
        assertEquals("35 · @?", overviewBadgeLabel(35, 0, false, true))
    }

    private fun buffer(target: String, unread: Int, mentions: Int): ConversationBuffer = ConversationBuffer(
        ref = ConversationRef.channel(network.id, target),
        displayName = target,
        hasUnread = unread > 0,
        unreadCount = unread,
        mentionCount = mentions,
    )
}
