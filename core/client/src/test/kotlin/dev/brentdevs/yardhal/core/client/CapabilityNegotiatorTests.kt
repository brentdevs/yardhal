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
        var saslStarts = 0
        var finished = false
        val deleted = mutableListOf<Set<String>>()

        val negotiator = CapabilityNegotiator(
            wanted = setOf("server-time", "message-tags", "sasl", "draft/pre-away"),
            sendRaw = sent::add,
            onSaslAcknowledged = {
                saslStarted = true
                saslStarts += 1
            },
            onFinished = { finished = true },
            beforeCapEnd = { sent.add("BEFORE-END") },
            onDeleted = { deleted += it },
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

        harness.negotiator.saslFinished()
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
        harness.negotiator.saslFinished()
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

    @Test
    fun advertisedValuesDoNotChangeCapabilityNames() {
        val harness = Harness()
        harness.negotiator.begin()
        harness.negotiator.handle(harness.cap("LS", "sasl=PLAIN,EXTERNAL server-time"))
        assertEquals("CAP REQ :sasl server-time", harness.sent.last())
        harness.negotiator.handle(harness.cap("ACK", "sasl server-time"))
        assertTrue(harness.saslStarted)
    }

    @Test
    fun runtimeNewWithValueRequestsCapability() {
        val harness = Harness()
        harness.negotiator.begin()
        harness.negotiator.handle(harness.cap("LS", "server-time"))
        harness.negotiator.handle(harness.cap("ACK", "server-time"))
        harness.negotiator.handle(harness.cap("NEW", "sasl=PLAIN"))
        assertEquals("CAP REQ :sasl", harness.sent.last())
    }

    @Test
    fun advertisedValuesAreRecordedAndForgotten() {
        val harness = Harness()
        harness.negotiator.begin()
        harness.negotiator.handle(harness.cap("LS", "*", "sasl=PLAIN,SCRAM-SHA-256 server-time"))
        harness.negotiator.handle(harness.cap("LS", "draft/account-registration=before-connect,email-required"))
        assertEquals("PLAIN,SCRAM-SHA-256", harness.negotiator.advertisedValues["sasl"])
        assertEquals("before-connect,email-required", harness.negotiator.advertisedValues["draft/account-registration"])
        assertFalse(harness.negotiator.advertisedValues.containsKey("server-time"))

        harness.negotiator.handle(harness.cap("DEL", "sasl"))
        assertFalse(harness.negotiator.advertisedValues.containsKey("sasl"))
        assertEquals(listOf(setOf("sasl")), harness.deleted)
        harness.negotiator.handle(harness.cap("NEW", "sasl=EXTERNAL"))
        assertEquals("EXTERNAL", harness.negotiator.advertisedValues["sasl"])
    }

    @Test
    fun beforeCapEndRunsImmediatelyBeforeCapEndOnce() {
        val harness = Harness()
        harness.negotiator.begin()
        harness.negotiator.handle(harness.cap("LS", "draft/pre-away server-time"))
        harness.negotiator.handle(harness.cap("ACK", "draft/pre-away server-time"))
        assertEquals(listOf("BEFORE-END", "CAP END"), harness.sent.takeLast(2))
        harness.negotiator.handle(harness.cap("NEW", "message-tags"))
        harness.negotiator.handle(harness.cap("ACK", "message-tags"))
        assertEquals(1, harness.sent.count { it == "BEFORE-END" })
    }

    @Test
    fun unrelatedRuntimeAckDoesNotRestartSasl() {
        val harness = Harness()
        harness.negotiator.begin()
        harness.negotiator.handle(harness.cap("LS", "sasl server-time message-tags"))
        harness.negotiator.handle(harness.cap("ACK", "message-tags sasl server-time"))
        harness.negotiator.saslFinished()
        assertEquals(1, harness.saslStarts)

        harness.negotiator.handle(harness.cap("NEW", "draft/pre-away"))
        harness.negotiator.handle(harness.cap("ACK", "draft/pre-away"))
        assertEquals(1, harness.saslStarts)
        assertEquals(CapabilityNegotiator.Phase.FINISHED, harness.negotiator.phase)
    }

    @Test
    fun saslReofferedAfterDelIsRequestedAndAuthenticatedAgain() {
        val harness = Harness()
        harness.negotiator.begin()
        harness.negotiator.handle(harness.cap("LS", "sasl=PLAIN server-time"))
        harness.negotiator.handle(harness.cap("ACK", "sasl server-time"))
        harness.negotiator.saslFinished()
        val endCount = harness.sent.count { it == "CAP END" }

        harness.negotiator.handle(harness.cap("NEW", "sasl=PLAIN,SCRAM-SHA-256"))
        assertEquals("CAP END", harness.sent.last(), "already-enabled sasl is not re-requested on value change")

        harness.negotiator.handle(harness.cap("DEL", "sasl"))
        harness.negotiator.handle(harness.cap("NEW", "sasl=PLAIN"))
        assertEquals("CAP REQ :sasl", harness.sent.last())
        harness.negotiator.handle(harness.cap("ACK", "sasl"))
        assertEquals(2, harness.saslStarts)
        assertEquals(CapabilityNegotiator.Phase.AUTHENTICATING, harness.negotiator.phase)
        harness.negotiator.saslFinished()
        assertEquals(CapabilityNegotiator.Phase.FINISHED, harness.negotiator.phase)
        assertEquals(endCount, harness.sent.count { it == "CAP END" })
    }

    @Test
    fun failedSaslKeepsCapabilityAcknowledged() {
        val harness = Harness()
        harness.negotiator.begin()
        harness.negotiator.handle(harness.cap("LS", "sasl"))
        harness.negotiator.handle(harness.cap("ACK", "sasl"))
        harness.negotiator.saslFinished()
        assertTrue(harness.negotiator.acknowledged.contains("sasl"))
        assertTrue(harness.sent.contains("CAP END"))
    }
}
