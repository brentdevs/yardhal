package dev.brentdevs.yardhal.ui.screens

import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.coordinator.ConversationHistory
import dev.brentdevs.yardhal.coordinator.HistoryGap
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.protocol.HistoryAnchor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationTranscriptTests {
    private val ref = ConversationRef.channel("network", "#room")

    @Test
    fun differentRelayedSendersKeepDistinctAttributionWithinOneWireBot() {
        val first = message(1, "2026-10-05T12:00:00Z").copy(sender = "bridge", relayedSender = "alice", relaySource = "bridge")
        val second = message(2, "2026-10-05T12:00:01Z").copy(sender = "bridge", relayedSender = "bob", relaySource = "bridge")
        val entries = transcript(first, second).filterIsInstance<TranscriptEntry.Message>()
        assertTrue(entries.none { it.groupedWithPrevious })
        val same = transcript(first, second.copy(relayedSender = "alice")).filterIsInstance<TranscriptEntry.Message>()
        assertTrue(same.last().groupedWithPrevious)
        val ordinary = transcript(first.copy(relayedSender = null, relaySource = null), second).filterIsInstance<TranscriptEntry.Message>()
        assertTrue(ordinary.none { it.groupedWithPrevious })
    }

    @Test
    fun addingLiveEventsPreservesTheExpandedRunIdentityAndChronologicalContents() {
        val first = message(41, "2026-10-05T12:00:00Z", MessageKind.JOIN)
        val second = message(42, "2026-10-05T12:01:00Z", MessageKind.PART)
        val original = transcript(first, second).filterIsInstance<TranscriptEntry.CollapsedEvents>().single()
        val live = message(43, "2026-10-05T12:02:00Z", MessageKind.JOIN)
        val extended = transcript(first, second, live).filterIsInstance<TranscriptEntry.CollapsedEvents>().single()

        assertEquals("collapsed-41", original.key)
        assertEquals(original.key, extended.key)
        assertEquals(listOf(first, second, live), extended.events)
    }

    @Test
    fun prependingRestoredEventsPreservesTheExpandedRunIdentityAndChronologicalContents() {
        val first = message(41, "2026-10-05T12:00:00Z", MessageKind.JOIN)
        val second = message(42, "2026-10-05T12:01:00Z", MessageKind.PART)
        val original = transcript(first, second).filterIsInstance<TranscriptEntry.CollapsedEvents>().single()
        val restored = message(43, "2026-10-05T11:59:00Z", MessageKind.PART).copy(playback = true)
        val prepended = transcript(restored, first, second).filterIsInstance<TranscriptEntry.CollapsedEvents>().single()
        val live = message(44, "2026-10-05T12:02:00Z", MessageKind.JOIN)
        val extended = transcript(restored, first, second, live).filterIsInstance<TranscriptEntry.CollapsedEvents>().single()

        assertEquals(original.key, prepended.key)
        assertEquals(original.key, extended.key)
        assertEquals(listOf(restored, first, second, live), extended.events)
    }

    @Test
    fun separateEventRunsRetainDistinctIdentitiesAcrossHistoryPrepends() {
        val first = message(10, "2026-10-05T12:00:00Z", MessageKind.JOIN)
        val second = message(11, "2026-10-05T12:01:00Z", MessageKind.PART)
        val chat = message(12, "2026-10-05T12:02:00Z")
        val third = message(13, "2026-10-05T12:03:00Z", MessageKind.JOIN)
        val fourth = message(14, "2026-10-05T12:04:00Z", MessageKind.PART)
        val restored = message(15, "2026-10-05T11:59:00Z", MessageKind.PART).copy(playback = true)
        val entries = transcript(restored, first, second, chat, third, fourth)

        assertEquals(listOf("collapsed-13", "msg-12", "collapsed-10", "day-2026-10-05"), entries.map { it.key })
        assertEquals(listOf(restored, first, second), entries.filterIsInstance<TranscriptEntry.CollapsedEvents>().last().events)
    }

    @Test
    fun oneUnreadDividerSeparatesReadMessagesAcrossSeveralEarlierDays() {
        val messages = listOf(
            message(1, "2026-10-02T12:00:00Z"),
            message(2, "2026-10-03T12:00:00Z"),
            message(3, "2026-10-04T12:00:00Z"),
            message(4, "2026-10-05T12:00:00Z"),
            message(5, "2026-10-05T12:01:00Z"),
        )
        val unreadFrom = timestamp("2026-10-05T12:00:30Z")
        val entries = buildTranscript(buffer(messages).copy(unreadFromTimestampMs = unreadFrom), ZoneOffset.UTC)

        assertEquals(
            listOf(
                "msg-5", "unread-$unreadFrom", "msg-4", "day-2026-10-05",
                "msg-3", "day-2026-10-04", "msg-2", "day-2026-10-03", "msg-1", "day-2026-10-02",
            ),
            entries.map { it.key },
        )
        assertEquals(1, entries.filterIsInstance<TranscriptEntry.UnreadDivider>().size)
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test
    fun unreadCrossingAtMidnightKeepsEveryDaySeparator() {
        val unreadFrom = timestamp("2026-10-04T00:00:00Z")
        val entries = buildTranscript(
            buffer(
                listOf(
                    message(1, "2026-10-03T23:59:00Z"),
                    message(2, "2026-10-04T00:00:00Z"),
                    message(3, "2026-10-05T12:00:00Z"),
                ),
            ).copy(unreadFromTimestampMs = unreadFrom),
            ZoneOffset.UTC,
        )

        assertEquals(
            listOf("msg-3", "day-2026-10-05", "msg-2", "day-2026-10-04", "unread-$unreadFrom", "msg-1", "day-2026-10-03"),
            entries.map { it.key },
        )
        assertEquals(
            listOf(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-04"), LocalDate.parse("2026-10-03")),
            entries.filterIsInstance<TranscriptEntry.DayHeader>().map { it.date },
        )
    }

    @Test
    fun removingDuplicateUnreadDividersPreservesMessageGroupingWithinEachDay() {
        val unreadFrom = timestamp("2026-10-05T12:00:30Z")
        val entries = buildTranscript(
            buffer(
                listOf(
                    message(1, "2026-10-03T12:00:00Z"),
                    message(2, "2026-10-03T12:01:00Z"),
                    message(3, "2026-10-04T12:00:00Z"),
                    message(4, "2026-10-04T12:01:00Z"),
                    message(5, "2026-10-05T12:00:00Z"),
                    message(6, "2026-10-05T12:01:00Z"),
                ),
            ).copy(unreadFromTimestampMs = unreadFrom),
            ZoneOffset.UTC,
        )

        val grouped = entries.filterIsInstance<TranscriptEntry.Message>().associate { it.value.localId to it.groupedWithPrevious }
        assertTrue(grouped.getValue(1))
        assertTrue(grouped.getValue(3))
        assertTrue(grouped.getValue(5))
        assertFalse(grouped.getValue(2))
        assertFalse(grouped.getValue(4))
        assertFalse(grouped.getValue(6))
        assertEquals(1, entries.filterIsInstance<TranscriptEntry.UnreadDivider>().size)
    }

    @Test
    fun repeatedTimestampCrossingsOnlyPlaceOneUnreadDivider() {
        val unreadFrom = timestamp("2026-10-05T12:02:00Z")
        val entries = buildTranscript(
            buffer(
                listOf(
                    message(1, "2026-10-05T12:00:00Z"),
                    message(2, "2026-10-05T12:03:00Z"),
                    message(3, "2026-10-05T12:01:00Z"),
                    message(4, "2026-10-05T12:04:00Z"),
                ),
            ).copy(unreadFromTimestampMs = unreadFrom),
            ZoneOffset.UTC,
        )

        assertEquals(listOf("msg-4", "unread-$unreadFrom", "msg-3", "msg-2", "msg-1", "day-2026-10-05"), entries.map { it.key })
    }

    @Test
    fun allUnreadMessagesPlaceOneDividerBeforeTheOldestDay() {
        val unreadFrom = timestamp("2026-10-01T00:00:00Z")
        val entries = buildTranscript(
            buffer(listOf(message(1, "2026-10-03T12:00:00Z"), message(2, "2026-10-04T12:00:00Z")))
                .copy(unreadFromTimestampMs = unreadFrom),
            ZoneOffset.UTC,
        )

        assertEquals(listOf("msg-2", "day-2026-10-04", "msg-1", "day-2026-10-03", "unread-$unreadFrom"), entries.map { it.key })
    }

    @Test
    fun allReadMessagesPlaceOneDividerAfterTheNewestMessageAcrossMultipleDays() {
        val unreadFrom = timestamp("2026-10-05T00:00:00Z")
        val entries = buildTranscript(
            buffer(listOf(message(1, "2026-10-03T12:00:00Z"), message(2, "2026-10-04T12:00:00Z")))
                .copy(unreadFromTimestampMs = unreadFrom),
            ZoneOffset.UTC,
        )

        assertEquals(listOf("unread-$unreadFrom", "msg-2", "day-2026-10-04", "msg-1", "day-2026-10-03"), entries.map { it.key })
    }

    @Test
    fun gapAndUnreadDividerKeepEventRunsSeparateWithoutLosingEvents() {
        val first = message(1, "2026-10-05T12:00:00Z", MessageKind.JOIN)
        val second = message(2, "2026-10-05T12:01:00Z", MessageKind.PART)
        val third = message(3, "2026-10-05T12:02:00Z", MessageKind.JOIN)
        val fourth = message(4, "2026-10-05T12:03:00Z", MessageKind.PART)
        val unreadFrom = third.timestampMs
        val gap = HistoryGap("missing", HistoryAnchor(second.timestampMs, second.msgid), HistoryAnchor(third.timestampMs, third.msgid))
        val entries = buildTranscript(
            buffer(listOf(first, second, third, fourth)).copy(
                unreadFromTimestampMs = unreadFrom,
                history = ConversationHistory(gaps = listOf(gap)),
            ),
            ZoneOffset.UTC,
        )

        assertEquals(listOf("collapsed-3", "gap-missing", "unread-$unreadFrom", "collapsed-1", "day-2026-10-05"), entries.map { it.key })
        assertEquals(listOf(third, fourth, first, second), entries.filterIsInstance<TranscriptEntry.CollapsedEvents>().flatMap { it.events })
        assertEquals(gap, entries.filterIsInstance<TranscriptEntry.Gap>().single().value)
    }

    private fun transcript(vararg messages: ChatMessage): List<TranscriptEntry> = buildTranscript(buffer(messages.toList()), ZoneOffset.UTC)

    private fun buffer(messages: List<ChatMessage>): ConversationBuffer = ConversationBuffer(ref, "#room", messages = messages)

    private fun message(localId: Long, time: String, kind: MessageKind = MessageKind.PRIVMSG): ChatMessage = ChatMessage(
        localId = localId,
        sender = "alice",
        kind = kind,
        text = "message $localId",
        timestampMs = timestamp(time),
        sentByUs = false,
        highlightsMe = false,
        msgid = "message-$localId",
    )

    private fun timestamp(time: String): Long = Instant.parse(time).toEpochMilli()
}
