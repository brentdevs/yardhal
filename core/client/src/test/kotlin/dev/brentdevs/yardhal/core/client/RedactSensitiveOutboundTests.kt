package dev.brentdevs.yardhal.core.client

import kotlin.test.Test
import kotlin.test.assertEquals

class RedactSensitiveOutboundTests {

    @Test
    fun mechanismNamesAndMarkersStayVisible() {
        for (line in listOf("AUTHENTICATE PLAIN", "AUTHENTICATE SCRAM-SHA-256", "AUTHENTICATE +", "AUTHENTICATE *")) {
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
    fun otherCommandsPassThrough() {
        assertEquals("VERIFY acct 1234", redactSensitiveOutbound("VERIFY acct 1234"))
        assertEquals("PRIVMSG #a :hi", redactSensitiveOutbound("PRIVMSG #a :hi"))
    }
}
