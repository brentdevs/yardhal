package dev.brentdevs.yardhal.ui.screens

import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.coordinator.UiNetwork
import dev.brentdevs.yardhal.core.data.ChannelOrderState
import dev.brentdevs.yardhal.core.data.ConversationRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkOverviewPresentationTests {
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

    private fun buffer(target: String, unread: Int, mentions: Int): ConversationBuffer = ConversationBuffer(
        ref = ConversationRef.channel(network.id, target),
        displayName = target,
        hasUnread = unread > 0,
        unreadCount = unread,
        mentionCount = mentions,
    )
}
