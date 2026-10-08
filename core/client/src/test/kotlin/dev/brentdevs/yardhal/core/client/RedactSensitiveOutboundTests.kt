package dev.brentdevs.yardhal.core.client

import kotlin.test.Test
import kotlin.test.assertEquals

class RedactSensitiveOutboundTests {

    @Test
    fun mechanismNamesAndMarkersStayVisible() {
        for (line in listOf("AUTHENTICATE PLAIN", "AUTHENTICATE SCRAM-SHA-256", "AUTHENTICATE EXTERNAL", "AUTHENTICATE +", "AUTHENTICATE *")) {
            assertEquals(line, redactSensitiveOutbound(line))
        }
    }

    @Test
    fun authenticatePayloadsAreRedacted() {
        assertEquals("AUTHENTICATE <redacted>", redactSensitiveOutbound("AUTHENTICATE biwsbj11c2VyLHI9ck9wck5HZndFYmVSV2diTkVrcU8="))
        assertEquals("PASS <redacted>", redactSensitiveOutbound("PASS hunter2"))
    }

    @Test
    fun registerPasswordIsRedacted() {
        assertEquals("REGISTER * me@example.org <redacted>", redactSensitiveOutbound("REGISTER * me@example.org hunter2"))
        assertEquals("REGISTER acct * <redacted>", redactSensitiveOutbound("REGISTER acct * :pass with spaces"))
        assertEquals("REGISTER <redacted>", redactSensitiveOutbound("REGISTER hunter2"))
    }

    @Test
    fun nickServIdentifyRedactsPasswordsAndAccountWithPrefixesAndTags() {
        assertEquals("PRIVMSG NickServ :IDENTIFY <redacted>", redactSensitiveOutbound("PRIVMSG NickServ :IDENTIFY hunter2"))
        assertEquals("@label=identify :me!u@h PRIVMSG AccountService :IDENTIFY <redacted>", redactSensitiveOutbound(
            "@label=identify :me!u@h PRIVMSG AccountService :IDENTIFY account hunter2", "AccountService",
        ))
        assertEquals("NS IDENTIFY <redacted>", redactSensitiveOutbound("NS IDENTIFY account hunter2"))
        assertEquals("PRIVMSG NickServ :IDENTIFY <redacted>", redactSensitiveOutbound("PRIVMSG NickServ : \tidentify\taccount hunter2"))
        assertEquals("PASS <redacted>", redactSensitiveOutbound("  PASS hunter2"))
        assertEquals(":srv AUTHENTICATE <redacted>", redactSensitiveOutbound(":srv AUTHENTICATE c2VjcmV0"))
        assertEquals("PRIVMSG #a :IDENTIFY hunter2", redactSensitiveOutbound("PRIVMSG #a :IDENTIFY hunter2"))
    }

    @Test
    fun literalPresentationMaskingIsIdempotentAndPrefersLongestOverlappingSecret() {
        val redactor = TrafficRedactor(listOf("red", "secret", "secret-password"), "NickServ")
        val masked = redactor.redactPresentation("Echo secret-password red <redacted>")
        assertEquals("Echo <redacted> <redacted> <redacted>", masked)
        assertEquals(masked, redactor.redactPresentation(masked))
        assertEquals("payload <redacted>", redactor.redact("payload <redacted>"))
    }

    @Test
    fun otherCommandsPassThrough() {
        assertEquals("VERIFY acct 1234", redactSensitiveOutbound("VERIFY acct 1234"))
        assertEquals("PRIVMSG #a :hi", redactSensitiveOutbound("PRIVMSG #a :hi"))
    }
}
