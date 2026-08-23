package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaslPlainHandlerTests {

    private class Harness {
        val sent = mutableListOf<String>()
        var outcome: SaslOutcome? = null

        val handler = SaslPlainHandler(
            authcid = "jilles",
            password = "sesame",
            sendRaw = sent::add,
            onOutcome = { outcome = it },
        )
    }

    @Test
    fun startAnnouncesMechanism() {
        val harness = Harness()
        harness.handler.start()
        assertEquals(listOf("AUTHENTICATE PLAIN"), harness.sent)
    }

    @Test
    fun continuationProducesBase64Credentials() {
        val harness = Harness()
        harness.handler.start()
        harness.handler.handleMessage(IrcMessage.parse("AUTHENTICATE +")!!)
        assertEquals("AUTHENTICATE AGppbGxlcwBzZXNhbWU=", harness.sent[1])
        assertEquals(
            "\u0000jilles\u0000sesame",
            String(Base64.getDecoder().decode(harness.sent[1].removePrefix("AUTHENTICATE "))),
        )
    }

    @Test
    fun successNumericEmitsSuccessOnce() {
        val harness = Harness()
        harness.handler.start()
        harness.handler.handleMessage(IrcMessage.parse("AUTHENTICATE +")!!)
        assertTrue(harness.handler.handleNumeric(SaslPlainHandler.RPL_SASLSUCCESS, IrcMessage.parse("903 * :SASL authentication successful")!!))
        assertEquals(SaslOutcome.Success, harness.outcome)
        harness.handler.handleNumeric(904, IrcMessage.parse("904 * :SASL authentication failed")!!)
        assertEquals(SaslOutcome.Success, harness.outcome)
    }

    @Test
    fun failureNumericsEmitFailure() {
        for (numeric in listOf(SaslPlainHandler.ERR_SASLFAIL, SaslPlainHandler.ERR_SASLTOOLONG, SaslPlainHandler.ERR_NICKLOCKED)) {
            val harness = Harness()
            harness.handler.handleNumeric(numeric, IrcMessage.parse("$numeric * :bad")!!)
            assertTrue(harness.outcome is SaslOutcome.Failure)
            assertEquals(numeric, (harness.outcome as SaslOutcome.Failure).numeric)
        }
    }

    @Test
    fun nonSaslMessagesPassThrough() {
        val harness = Harness()
        assertFalse(harness.handler.handleMessage(IrcMessage.parse("PRIVMSG #a :x")!!))
        assertFalse(harness.handler.handleNumeric(1, IrcMessage.parse("001 nick :Welcome")!!))
    }

    @Test
    fun unexpectedPayloadFails() {
        val harness = Harness()
        harness.handler.start()
        harness.handler.handleMessage(IrcMessage.parse("AUTHENTICATE ABCD")!!)
        assertTrue(harness.outcome is SaslOutcome.Failure)
    }
}
