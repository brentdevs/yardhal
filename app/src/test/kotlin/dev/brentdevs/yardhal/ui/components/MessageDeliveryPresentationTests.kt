package dev.brentdevs.yardhal.ui.components

import dev.brentdevs.yardhal.coordinator.toChatMessage
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.StoredMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageDeliveryPresentationTests {
    @Test
    fun coldRestoredOwnSendWithoutServerEchoRemainsExplicitlyUnconfirmed() {
        val restored = stored(MessageKind.PRIVMSG, pendingEcho = true).toChatMessage(1)

        assertEquals(MessageDeliveryStatus.UNCONFIRMED, messageDeliveryStatus(restored))
        assertEquals(MessageDeliveryStatus.UNCONFIRMED, messageDeliveryStatus(restored.copy(playback = true)))
    }

    @Test
    fun canonicalEchoRemovesUnconfirmedPresentationForTheSameDurableMessage() {
        val pending = stored(MessageKind.PRIVMSG, pendingEcho = true)
        assertEquals(MessageDeliveryStatus.UNCONFIRMED, messageDeliveryStatus(pending.toChatMessage(1)))

        val confirmed = pending.copy(pendingEcho = false, msgid = "server-confirmed")
        assertNull(messageDeliveryStatus(confirmed.toChatMessage(1)))
    }

    @Test
    fun restoredActionAndAttachmentCarryTheSameDeliveryWarningAsPlainMessages() {
        val action = stored(MessageKind.ACTION, pendingEcho = true).toChatMessage(1)
        val attachment = stored(MessageKind.PRIVMSG, pendingEcho = true)
            .copy(attachmentUrl = "https://example.test/file.png", attachmentMimeType = "image/png").toChatMessage(2)

        assertEquals(MessageDeliveryStatus.UNCONFIRMED, messageDeliveryStatus(action))
        assertEquals(MessageDeliveryStatus.UNCONFIRMED, messageDeliveryStatus(attachment))
    }

    @Test
    fun receivedAndConfirmedOwnMessagesHaveNoUnconfirmedIndicator() {
        assertNull(messageDeliveryStatus(stored(MessageKind.PRIVMSG, pendingEcho = false).toChatMessage(1)))
        assertNull(messageDeliveryStatus(stored(MessageKind.NOTICE, pendingEcho = false).copy(sentByUs = false).toChatMessage(2)))
    }

    private fun stored(kind: MessageKind, pendingEcho: Boolean): StoredMessage = StoredMessage(
        rowId = 41L,
        networkId = "network",
        conversation = ConversationRef.channel("network", "#saved"),
        msgid = null,
        senderNick = "alice",
        senderUser = null,
        senderHost = null,
        kind = kind,
        text = "Saved send",
        sentByUs = true,
        timestampMs = 1_790_000_000_000L,
        pendingEcho = pendingEcho,
    )
}
