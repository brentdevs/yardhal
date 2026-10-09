package dev.brentdevs.yardhal.ui.screens

import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.coordinator.ReplyPreview
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineConversationPresentationTests {
    private val ref = ConversationRef.channel("network", "#saved")



    @Test
    fun offPageReplyUsesHydratedDurablePreview() {
        val reply = message().copy(replyToMsgid = "parent", replyPreview = ReplyPreview("bob", "Stored parent outside this page"))
        val buffer = ConversationBuffer(ref, "#saved", messages = listOf(reply))
        val preview = assertNotNull(replyPreviewText(reply, buffer))
        assertTrue(preview.contains("bob"))
        assertTrue(preview.contains("Stored parent outside this page"))
    }

    @Test
    fun durableLocalQuoteDoesNotRequireServerMessageIdentity() {
        val reply = message().copy(localReplyParentRowId = 47L, replyPreview = ReplyPreview("bob", "Local quoted parent"))
        assertNull(reply.replyToMsgid)
        val preview = assertNotNull(replyPreviewText(reply, ConversationBuffer(ref, "#saved")))
        assertTrue(preview.contains("bob"))
        assertTrue(preview.contains("Local quoted parent"))
    }

    @Test
    fun deletedParentNeverDisplaysItsPreviousText() {
        val reply = message().copy(replyPreview = ReplyPreview("bob", "Private deleted text", redacted = true))
        val preview = assertNotNull(replyPreviewText(reply, ConversationBuffer(ref, "#saved")))
        assertFalse(preview.contains("Private deleted text"))
    }


    @Test
    fun visibleParentRemainsAvailableWithoutHydratedPreview() {
        val parent = message().copy(localId = 2L, sender = "bob", msgid = "parent", text = "Visible parent")
        val reply = message().copy(replyToMsgid = "parent")
        val preview = assertNotNull(replyPreviewText(reply, ConversationBuffer(ref, "#saved", messages = listOf(parent, reply))))
        assertTrue(preview.contains("bob"))
        assertTrue(preview.contains("Visible parent"))
    }


    private fun message(): ChatMessage = ChatMessage(
        localId = 1L,
        sender = "alice",
        kind = MessageKind.PRIVMSG,
        text = "Reply",
        timestampMs = 1_790_000_000_000L,
        sentByUs = false,
        highlightsMe = false,
        msgid = "child",
    )
}
