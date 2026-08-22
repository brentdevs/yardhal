package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrcMessageTests {

    @Test
    fun parsesSimplePrivmsg() {
        val message = IrcMessage.parse("PRIVMSG #chan :hello world")
        assertEquals("PRIVMSG", message!!.command)
        assertEquals(listOf("#chan", "hello world"), message.parameters)
        assertNull(message.prefix)
    }

    @Test
    fun parsesPrefixAndParams() {
        val message = IrcMessage.parse(":nick!user@host.com PRIVMSG #chan hello")
        assertEquals(IrcPrefix("nick", "user", "host.com"), message!!.prefix)
        assertEquals(listOf("#chan", "hello"), message.parameters)
    }

    @Test
    fun parsesServerPrefix() {
        val message = IrcMessage.parse(":irc.example.org 001 nick :Welcome")
        assertTrue(message!!.prefix!!.isServer)
        assertEquals(listOf("nick", "Welcome"), message.parameters)
        assertEquals(1, message.numeric)
    }

    @Test
    fun parsesTagsWithPrefixAndTrailing() {
        val line = "@time=2024-01-01T00:00:00.000Z;account=alice :alice!a@h PRIVMSG #c :hi there"
        val message = IrcMessage.parse(line)
        assertEquals("2024-01-01T00:00:00.000Z", message!!.tag("time"))
        assertEquals("alice", message.tag("account"))
        assertEquals(IrcPrefix("alice", "a", "h"), message.prefix)
        assertEquals(listOf("#c", "hi there"), message.parameters)
    }

    @Test
    fun parsesFlagTagsWithoutValue() {
        val message = IrcMessage.parse("@+only;batch=1 PRIVMSG #c :x")
        assertNull(message!!.tag("+only"))
        assertEquals(mapOf("+only" to null, "batch" to "1"), message.tags)
    }

    @Test
    fun unescapesTagValues() {
        val message = IrcMessage.parse("""@msg=a\sb\\c\:d;empty= PRIVMSG #c :x""")
        assertEquals("a b\\c;d", message!!.tag("msg"))
        assertEquals("", message.tag("empty"))
    }

    @Test
    fun rejectsLinesWithoutCommand() {
        assertNull(IrcMessage.parse(""))
        assertNull(IrcMessage.parse("@time=1"))
        assertNull(IrcMessage.parse(":prefix.only"))
        assertNull(IrcMessage.parse("@a=b :pfx"))
    }

    @Test
    fun rejectsInvalidCommands() {
        assertNull(IrcMessage.parse("NOT_A_CMD x"))
        assertNull(IrcMessage.parse("12 x"))
        assertNull(IrcMessage.parse("1234 x"))
    }

    @Test
    fun colonParamBecomesTrailing() {
        val message = IrcMessage.parse("PRIVMSG #c :starts with colon")
        assertEquals("starts with colon", message!!.parameters.last())
    }

    @Test
    fun emptyTrailingPreserved() {
        val message = IrcMessage.parse("PRIVMSG #c :")
        assertEquals("", message!!.parameters.last())
        assertEquals(2, message.parameters.size)
    }

    @Test
    fun multipleSpacesBetweenParamsTolerated() {
        val message = IrcMessage.parse("JOIN   #a  #b")
        assertEquals(listOf("#a", "#b"), message!!.parameters)
    }

    @Test
    fun crlfTrimmed() {
        val message = IrcMessage.parse("PING :server\r\n")
        assertEquals("server", message!!.parameters.last())
    }

    @Test
    fun serializesRoundTrip() {
        val original = IrcMessage(
            tags = mapOf("time" to "2024-01-01", "+client" to null),
            prefix = IrcPrefix("bob"),
            command = "PRIVMSG",
            parameters = listOf("#room", "multi word text"),
        )
        val wire = original.toWire()
        assertEquals(original.copy(prefix = IrcPrefix("bob")), IrcMessage.parse(wire))
        assertEquals("@time=2024-01-01;+client :bob PRIVMSG #room :multi word text", wire)
    }

    @Test
    fun serializationEscapesTagValues() {
        val message = IrcMessage(tags = mapOf("k" to "v with;specials\r"), command = "PING")
        val parsed = IrcMessage.parse(message.toWire())
        assertEquals("v with;specials\r", parsed!!.tag("k"))
    }

    @Test
    fun serializationAddsColonForColonLeadingParam() {
        val message = IrcMessage(command = "TOPIC", parameters = listOf("#c", ":weird"))
        assertTrue(message.toWire().endsWith(" ::weird"))
    }

    @Test
    fun middleParamWithSpaceRejectedOnSerialize() {
        val bad = IrcMessage(command = "X", parameters = listOf("a b", "c"))
        assertFalse(runCatching { bad.toWire() }.isSuccess)
    }

    @Test
    fun byteLengthCountsUtf8() {
        val ascii = IrcMessage(command = "PRIVMSG", parameters = listOf("#c", "abc"))
        assertEquals(ascii.toWire().length, ascii.wireByteLength())
        val unicode = IrcMessage(command = "PRIVMSG", parameters = listOf("#c", "héllo"))
        assertTrue(unicode.wireByteLength() > unicode.toWire().length)
    }

    @Test
    fun numericOnlyForThreeDigits() {
        assertEquals(433, IrcMessage.parse("433 * nick")!!.numeric)
        assertNull(IrcMessage.parse("PRIVMSG #c :x")!!.numeric)
        assertNull(IrcMessage.parse("42 x y")?.numeric)
        assertNull(IrcMessage.parse("42 x y"))
    }
}
