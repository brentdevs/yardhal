package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PortableInteractionsTests {
    private fun parent(): ChatMessage = ChatMessage(1, "alice", MessageKind.PRIVMSG, "hello", 100, false, false, null, storedRowId = 2)

    @Test
    fun msgidlessPortableQuoteContainsVisibleParentAndReply() {
        assertEquals("> alice: hello — answer", portableQuote(parent(), "answer"))
        assertEquals("> alice: Attachment — answer", portableQuote(parent().copy(text = "", attachmentUrl = "https://example/file"), "answer"))
    }

    @Test
    fun redactedParentCannotLeakRetainedContentOrUrl() {
        val quoted = portableQuote(parent().copy(redacted = true, text = "private", attachmentUrl = "https://private"), "answer")
        assertEquals("> alice: Deleted message — answer", quoted)
        assertFalse(quoted.contains("private"))
    }

    @Test
    fun quoteBoundaryStripsParentControlAndBoundsPreview() {
        val quoted = portableQuote(parent().copy(sender = "alice\nspoof", text = "x".repeat(200) + "\r\ntrailer"), "answer")
        assertFalse(quoted.contains('\r'))
        assertFalse(quoted.contains('\n'))
        assertTrue(quoted.endsWith(" — answer"))
        assertFalse(quoted.contains("trailer"))
    }

    @Test
    fun quoteBoundsNeverSplitSupplementarySenderOrBodyCodePoints() {
        val senderPrefix = "s".repeat(63)
        val bodyPrefix = "b".repeat(159)
        assertEquals(
            "> $senderPrefix: $bodyPrefix — answer",
            portableQuote(parent().copy(sender = senderPrefix + "😀tail", text = bodyPrefix + "😀tail"), "answer"),
        )
        val completeSender = "s".repeat(62) + "😀"
        val completeBody = "b".repeat(158) + "😀"
        assertEquals(
            "> $completeSender: $completeBody — answer",
            portableQuote(parent().copy(sender = completeSender + "tail", text = completeBody + "tail"), "answer"),
        )
    }

    @Test
    fun mediaIdentityUsesScopedDurableRows() {
        val one = ConversationBuffer(ConversationRef.channel("one", "#room"), "#room")
        val two = ConversationBuffer(ConversationRef.channel("two", "#room"), "#room")
        assertEquals(messageMediaIdentity(one, parent()), messageMediaIdentity(one, parent().copy(localId = 99, msgid = "remote")))
        assertNotEquals(messageMediaIdentity(one, parent()), messageMediaIdentity(two, parent()))
    }
}
