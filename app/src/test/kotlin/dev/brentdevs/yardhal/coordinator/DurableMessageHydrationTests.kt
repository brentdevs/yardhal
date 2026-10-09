package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.StoredMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DurableMessageHydrationTests {
    private val ref = ConversationRef.channel("network", "#room")

    private fun stored(rowId: Long, msgid: String): StoredMessage = StoredMessage(
        rowId = rowId,
        networkId = "network",
        conversation = ref,
        msgid = msgid,
        senderNick = "alice",
        senderUser = null,
        senderHost = null,
        kind = MessageKind.PRIVMSG,
        text = "message $rowId",
        sentByUs = false,
        timestampMs = rowId * 1_000,
    )

    @Test
    fun offPageParentHydrationUsesChildRowIdentityAndCompactNonrecursivePreview() {
        val parent = stored(41, "parent").copy(
            senderNick = "bob",
            replyToMsgid = "grandparent",
            replyParentRowId = 40,
            attachmentUrl = "https://example.test/image.png",
        )
        val reply = stored(42, "reply").copy(replyToMsgid = "parent").toChatMessage(7)
        val unrelated = stored(43, "unrelated").toChatMessage(8)
        val hydrated = hydrateReplyPreviews(listOf(reply, unrelated), mapOf(42L to parent))

        assertEquals(reply.copy(
            localReplyParentRowId = 41,
            replyPreview = ReplyPreview("bob", "message 41", "https://example.test/image.png"),
        ), hydrated[0])
        assertEquals(unrelated, hydrated[1])
        assertEquals(7L, hydrated[0].localId)
        assertEquals(42L, hydrated[0].storedRowId)
        assertEquals("parent", hydrated[0].replyToMsgid)
        assertEquals(hydrated, hydrateReplyPreviews(hydrated, mapOf(42L to parent)))
    }

    @Test
    fun tombstonedOffPageParentNeverExposesItsStoredSensitiveContents() {
        val parent = stored(41, "parent").copy(
            redacted = true,
            attachmentUrl = "https://example.test/private.png",
        )
        val reply = stored(42, "reply").copy(replyToMsgid = "parent").toChatMessage(7)
        val hydrated = hydrateReplyPreviews(listOf(reply), mapOf(42L to parent)).single()

        assertEquals(41L, hydrated.localReplyParentRowId)
        assertEquals(ReplyPreview("alice", "message deleted", redacted = true), hydrated.replyPreview)
        assertFalse(hydrated.redacted)
        assertEquals(reply.text, hydrated.text)
    }

    @Test
    fun staleHealthyParentCannotRestoreAlreadyTombstonedPreview() {
        val parent = stored(41, "parent").copy(attachmentUrl = "https://example.test/private.png")
        val reply = stored(42, "reply").copy(replyToMsgid = "parent", replyParentRowId = 41).toChatMessage(7)
            .copy(replyPreview = ReplyPreview("alice", "message deleted", redacted = true))

        assertEquals(listOf(reply), hydrateReplyPreviews(listOf(reply), mapOf(42L to parent)))
    }

    @Test
    fun visibleParentTombstoneSanitizesChildPreviewEvenWhenChildHasOnlyLocalParentIdentity() {
        val parent = stored(41, "parent").copy(redacted = true).toChatMessage(1)
        val reply = stored(42, "reply").copy(replyParentRowId = 41).toChatMessage(2).copy(
            replyPreview = ReplyPreview("alice", "private", "https://example.test/private.png"),
        )
        val hydrated = hydrateReplyPreviews(listOf(parent, reply), emptyMap())

        assertTrue(hydrated[0].redacted)
        assertNull(hydrated[0].replyPreview)
        assertEquals(ReplyPreview("alice", "message deleted", redacted = true), hydrated[1].replyPreview)
    }
}
