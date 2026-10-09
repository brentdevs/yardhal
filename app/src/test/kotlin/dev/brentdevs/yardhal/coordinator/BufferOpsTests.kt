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

    private fun decorated(message: ChatMessage): ChatMessage = message.copy(
        highlightsMe = true,
        replyToMsgid = "parent",
        localReplyParentRowId = 41,
        replyPreview = ReplyPreview("bob", "parent text", "https://example.test/parent.png"),
        attachmentUrl = "https://example.test/image.png",
        attachmentName = "image.png",
        attachmentMimeType = "image/png",
        attachmentSizeBytes = 123,
        attachmentWidth = 320,
        attachmentHeight = 240,
        senderAccount = "account",
        channelContext = "#context",
        playback = true,
        historyContext = true,
        storedRowId = 42,
    )

    @Test
    fun echoKeepsReplyIdentityPreviewHighlightAndAttachmentMetadataWhenEchoTagsAreAbsent() {
        val pending = decorated(message(1, 1_000, pending = true))
        val reconciled = assertNotNull(reconcileEcho(
            ConversationBuffer(ref, "#room", messages = listOf(pending)),
            pending.kind, "canonical", pending.echoLabel, "echo", 1_000, null,
        ))

        assertEquals(pending.copy(
            text = "canonical",
            msgid = "echo",
            pendingEcho = false,
            echoLabel = null,
        ), reconciled.messages.single())
    }

    @Test
    fun canonicalMergeRetainsAllDurableMetadataAndPreferredLocalIdentity() {
        val preferred = decorated(message(1, 1_000))
        val canonical = message(2, 1_000).copy(msgid = preferred.msgid)
        val merged = mergeConversationMessages(listOf(preferred), listOf(canonical), incomingCanonical = true)

        assertEquals(preferred.copy(
            text = canonical.text,
            playback = false,
            historyContext = false,
        ), merged.single())
    }

    @Test
    fun redactionRemovesReactionsAttachmentsAndParentPreviewsWithoutRemovingReplyIdentity() {
        val parent = decorated(message(1, 1_000))
        val reply = decorated(message(2, 2_000)).copy(
            replyToMsgid = parent.msgid,
            localReplyParentRowId = parent.storedRowId,
            storedRowId = 43,
        )
        val other = message(3, 3_000)
        val reactions = mapOf(
            "message-1" to mapOf("star" to setOf("alice", "bob")),
            "message-3" to mapOf("star" to setOf("bob")),
        )
        val buffer = ConversationBuffer(ref, "#room", messages = listOf(parent, reply, other), reactions = reactions, replyDraft = parent)
        val redacted = redactInBuffer(buffer, "message-1")

        assertEquals(parent.asRedacted(), redacted.messages[0])
        assertEquals(reply.copy(replyPreview = ReplyPreview(parent.sender, "message deleted", redacted = true)), redacted.messages[1])
        assertEquals(other, redacted.messages[2])
        assertEquals(reactions - "message-1", redacted.reactions)
        assertEquals(null, redacted.replyDraft)
        assertFalse(redacted.messages[0].highlightsMe)
        assertEquals(redacted, redactInBuffer(redacted, "message-1"))
    }

    @Test
    fun redactionOfOffPageParentStillRemovesReactionMembershipAndSensitiveReplyPreview() {
        val reply = decorated(message(2, 2_000))
        val buffer = ConversationBuffer(
            ref, "#room", messages = listOf(reply),
            reactions = mapOf("parent" to mapOf("star" to setOf("bob"))),
        )
        val redacted = redactInBuffer(buffer, "parent")

        assertEquals(emptyMap(), redacted.reactions)
        assertEquals(ReplyPreview("bob", "message deleted", redacted = true), redacted.messages.single().replyPreview)
        assertEquals(reply.localReplyParentRowId, redacted.messages.single().localReplyParentRowId)
        assertEquals(reply.attachmentUrl, redacted.messages.single().attachmentUrl)
    }

    @Test
    fun tombstoneWinsOverHealthyDuplicateInEveryMergeDirection() {
        val healthy = decorated(message(1, 1_000))
        val deleted = healthy.asRedacted()
        for (canonical in listOf(false, true)) {
            assertEquals(listOf(deleted), mergeConversationMessages(listOf(deleted), listOf(healthy), canonical))
            assertEquals(listOf(deleted), mergeConversationMessages(listOf(healthy), listOf(deleted), canonical))
        }
    }

    @Test
    fun redactedReplyPreviewWinsOverHealthyDuplicatePreview() {
        val healthy = decorated(message(1, 1_000))
        val deletedPreview = healthy.withRedactedReply()

        assertEquals(deletedPreview, mergeChatMessageMetadata(healthy, deletedPreview))
        assertEquals(deletedPreview, mergeChatMessageMetadata(deletedPreview, healthy, incomingCanonical = true))
    }

    @Test
    fun echoReconciliationWithDurableTombstoneDuplicateNeverRestoresDeletedAttachment() {
        val pending = decorated(message(1, 1_000, pending = true))
        val deleted = decorated(message(2, 1_000)).copy(msgid = "echo").asRedacted()
        val buffer = ConversationBuffer(
            ref, "#room", messages = listOf(pending, deleted),
            reactions = mapOf("echo" to mapOf("star" to setOf("bob"))),
        )
        val reconciled = assertNotNull(reconcileEcho(
            buffer, pending.kind, "canonical", pending.echoLabel, "echo", 1_000, pending.attachmentUrl,
        ))

        assertEquals(pending.copy(
            msgid = "echo",
            pendingEcho = false,
            echoLabel = null,
        ).asRedacted(), reconciled.messages.single())
        assertEquals(emptyMap(), reconciled.reactions)
    }
}
