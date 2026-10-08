package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BufferOpsTests {
    private val ref = ConversationRef.channel("network", "#room")

    private fun message(localId: Long, timestampMs: Long, pending: Boolean = false): ChatMessage = ChatMessage(
        localId = localId,
        sender = if (pending) "me" else "alice",
        kind = MessageKind.PRIVMSG,
        text = "message $localId",
        timestampMs = timestampMs,
        sentByUs = pending,
        highlightsMe = false,
        msgid = if (pending) null else "message-$localId",
        pendingEcho = pending,
        echoLabel = if (pending) "label-$localId" else null,
    )

    @Test
    fun aLaterCanonicalEchoMovesForwardWithoutCrossingPreviouslyLaterEqualTimeRows() {
        val pending = message(2, 1_000, pending = true)
        val firstAtEchoTime = message(3, 2_000)
        val secondAtEchoTime = message(4, 2_000)
        val messages = listOf(message(1, 500), pending, message(5, 1_500), firstAtEchoTime, secondAtEchoTime)
        val buffer = ConversationBuffer(ref, "#room", messages = messages)

        val reconciled = assertNotNull(
            reconcileEcho(buffer, MessageKind.PRIVMSG, pending.text, pending.echoLabel, "echo", 2_000, null),
        )

        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), reconciled.messages.map { it.localId })
        assertEquals(firstAtEchoTime, reconciled.messages[3])
        assertEquals(secondAtEchoTime, reconciled.messages[4])
        assertEquals("echo", reconciled.messages[2].msgid)
        assertFalse(reconciled.messages[2].pendingEcho)
        assertEquals(null, reconciled.messages[2].echoLabel)
        assertTrue(reconciled.messages.zipWithNext().all { (left, right) -> left.timestampMs <= right.timestampMs })
    }

    @Test
    fun anUnchangedCanonicalTimestampKeepsEqualTimePendingAndCanonicalIdentitiesInPlace() {
        val first = message(1, 1_000)
        val pending = message(2, 1_000, pending = true)
        val stillPending = message(3, 1_000, pending = true)
        val buffer = ConversationBuffer(ref, "#room", messages = listOf(first, pending, stillPending))

        val reconciled = assertNotNull(
            reconcileEcho(buffer, MessageKind.PRIVMSG, "canonical text", pending.echoLabel, "echo", 1_000, null),
        )

        assertEquals(listOf(1L, 2L, 3L), reconciled.messages.map { it.localId })
        assertEquals(first, reconciled.messages.first())
        assertEquals(stillPending, reconciled.messages.last())
        assertEquals("canonical text", reconciled.messages[1].text)
        assertFalse(reconciled.messages[1].pendingEcho)
    }

    @Test
    fun chronologicalAppendsKeepEstablishedEqualTimeOrderAfterOptimisticTimestampFlooring() {
        val first = message(1, 2_000)
        val optimistic = message(2, 1_000, pending = true)
        val sent = appendChronologically(listOf(first), optimistic, optimistic = true)
        val appended = appendChronologically(sent, message(3, 2_000))

        assertEquals(listOf(1L, 2L, 3L), appended.map { it.localId })
        assertEquals(listOf(2_000L, 2_000L, 2_000L), appended.map { it.timestampMs })
        assertEquals(optimistic.copy(timestampMs = 2_000), appended[1])
        assertTrue(appended[1].pendingEcho)
    }
}
