package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrcCtcpTests {

    @Test
    fun encodesActionWithArguments() {
        assertEquals("\u0001ACTION waves hello\u0001", IrcCtcp.encode("ACTION", "waves hello"))
    }

    @Test
    fun encodesCommandWithoutArguments() {
        assertEquals("\u0001VERSION\u0001", IrcCtcp.encode("VERSION", ""))
    }

    @Test
    fun decodesPureQuery() {
        val contents = IrcCtcp.decode("\u0001VERSION\u0001")
        assertEquals(1, contents.size)
        val ctcp = contents.first() as PrivmsgContent.Ctcp
        assertEquals("VERSION", ctcp.message.command)
        assertEquals("", ctcp.message.arguments)
    }

    @Test
    fun decodesActionAndTrailingPlainText() {
        val contents = IrcCtcp.decode("\u0001ACTION waves\u0001 and smiles")
        assertEquals(2, contents.size)
        assertEquals("ACTION waves", (contents[0] as PrivmsgContent.Ctcp).message.toEncodedPayload().trim('\u0001'))
        assertEquals(" and smiles", (contents[1] as PrivmsgContent.Plain).text)
    }

    @Test
    fun plainTextOnlyHasNoCtcp() {
        val contents = IrcCtcp.decode("just chatting")
        assertEquals(listOf<PrivmsgContent>(PrivmsgContent.Plain("just chatting")), contents)
    }

    @Test
    fun multipleCtcpsInOneMessage() {
        val contents = IrcCtcp.decode("\u0001PING 12345\u0001 middle \u0001VERSION\u0001")
        assertEquals(3, contents.size)
        assertEquals("PING", (contents[0] as PrivmsgContent.Ctcp).message.command)
        assertEquals("12345", (contents[0] as PrivmsgContent.Ctcp).message.arguments)
        assertEquals(" middle ", (contents[1] as PrivmsgContent.Plain).text)
        assertEquals("VERSION", (contents[2] as PrivmsgContent.Ctcp).message.command)
    }

    @Test
    fun unterminatedDelimiterStillParses() {
        val contents = IrcCtcp.decode("\u0001VERSION")
        val ctcp = contents.filterIsInstance<PrivmsgContent.Ctcp>().single()
        assertEquals("VERSION", ctcp.message.command)
    }

    @Test
    fun commandCaseNormalizedToUppercase() {
        val parsed = IrcCtcp.parseBody("action jumps")
        assertEquals("ACTION", parsed!!.command)
    }

    @Test
    fun emptyBodyRejected() {
        assertNull(IrcCtcp.parseBody(""))
    }

    @Test
    fun lowLevelQuoteRoundTrip() {
        val original = "line1\nline2\rreturn\u0000null quote\u0010end"
        val decoded = IrcCtcp.unquote(IrcCtcp.quote(original))
        assertEquals(original, decoded)
    }

    @Test
    fun quotedDataCannotSmuggleDelimiters() {
        val payload = IrcCtcp.encode("PING", "arg with \u0001 inside")
        val contents = IrcCtcp.decode(payload)
        val ctcp = contents.filterIsInstance<PrivmsgContent.Ctcp>().single()
        assertTrue(ctcp.message.arguments.endsWith("inside"))
    }

    @Test
    fun trailingQuoteCharDropped() {
        assertEquals("safe", IrcCtcp.unquote("safe\u0010"))
    }
}
