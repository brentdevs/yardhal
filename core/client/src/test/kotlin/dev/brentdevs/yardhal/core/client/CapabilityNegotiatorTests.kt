package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityNegotiatorTests {

    private class Harness {
        val sent = mutableListOf<String>()
        var saslStarted = false
        var finished = false

        val negotiator = CapabilityNegotiator(
            wanted = setOf("server-time", "message-tags", "sasl"),
            sendRaw = sent::add,
            onSaslAcknowledged = { saslStarted = true },
            onFinished = { finished = true },
        )

        fun cap(sub: String, vararg params: String): IrcMessage =
            IrcMessage(prefix = null, command = "CAP", parameters = listOf("*", sub) + params)
    }

    private fun linesStillPending(harness: Harness): Boolean = harness.sent.size == 1

    @Test
    fun beginSendsLs302() {
        val harness = Harness()
        harness.negotiator.begin()
        assertEquals(listOf("CAP LS 302"), harness.sent)
    }

    @Test
    fun fullListingThenRequestThenAckWithSasl() {
        val harness = Harness()
        harness.negotiator.begin()

        assertTrue(harness.negotiator.handle(harness.cap("LS", "*", "multi-prefix userhost-in-names")))
        assertTrue(linesStillPending(harness))
        assertTrue(harness.negotiator.handle(harness.cap("LS", "sasl server-time message-tags")))
        assertFalse(harness.saslStarted)

        assertEquals(2, harness.sent.size)
        assertEquals("CAP REQ :message-tags sasl server-time", harness.sent.last())
        assertTrue(
            harness.negotiator.handle(harness.cap("ACK", "*", "message-tags server-time")),
            "multiline ack consumed",
        )
        assertFalse(harness.finished)

        harness.negotiator.handle(harness.cap("ACK", "sasl"))
        assertTrue(harness.saslStarted)

        harness.negotiator.saslCompleted()
        assertTrue(harness.finished)
        assertTrue(harness.sent.contains("CAP END"))
    }

    @Test
    fun nakFallsBackToFinishWithoutSasl() {
        val harness = Harness()
        harness.negotiator.begin()
        harness.negotiator.handle(harness.cap("LS", "sasl server-time"))
        harness.negotiator.handle(harness.cap("NAK", "message-tags sasl server-time"))
        assertFalse(harness.saslStarted)
        assertTrue(harness.finished)
        assertTrue(harness.sent.contains("CAP END"))
    }

    @Test
    fun nothingWantedAvailableFinishesImmediately() {
        val harness = Harness()
        val negotiator = CapabilityNegotiator(
            wanted = setOf("never-offered-cap"),
            sendRaw = harness.sent::add,
            onSaslAcknowledged = {},
            onFinished = { harness.finished = true },
        )
        negotiator.begin()
        negotiator.handle(harness.cap("LS", "sasl server-time"))
        assertTrue(harness.finished)
        assertTrue(harness.sent.contains("CAP END"))
    }

    @Test
    fun runtimeNewRequestsMissingCapsWithoutCapEnd() {
        val harness = Harness()
        harness.negotiator.begin()
        harness.negotiator.handle(harness.cap("LS", "server-time"))
        harness.negotiator.handle(harness.cap("ACK", "server-time"))
        harness.negotiator.saslCompleted()
        assertTrue(harness.finished)

        val beforeEndCount = harness.sent.count { it == "CAP END" }
        harness.negotiator.handle(harness.cap("NEW", "message-tags"))
        assertTrue(harness.sent.last().startsWith("CAP REQ :"))

        harness.negotiator.handle(harness.cap("ACK", "message-tags"))
        assertEquals(beforeEndCount, harness.sent.count { it == "CAP END" })
    }

    @Test
    fun delRemovesAcknowledgedCapability() {
        val harness = Harness()
        harness.negotiator.begin()
        harness.negotiator.handle(harness.cap("LS", "server-time chghost"))
        harness.negotiator.handle(harness.cap("ACK", "chghost server-time"))
        harness.negotiator.handle(harness.cap("DEL", "chghost"))
        assertFalse(harness.negotiator.acknowledged.contains("chghost"))
        assertFalse(harness.negotiator.available.contains("chghost"))
    }

    @Test
    fun nonCapMessagesIgnored() {
        val harness = Harness()
        assertFalse(harness.negotiator.handle(IrcMessage.parse("PRIVMSG #a :hi")!!))
    }
}
