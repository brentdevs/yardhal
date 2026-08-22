package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrcPrefixTests {

    @Test
    fun parsesFullMask() {
        assertEquals(IrcPrefix("nick", "user", "host"), IrcPrefix.parse("nick!user@host"))
    }

    @Test
    fun parsesNickUser() {
        assertEquals(IrcPrefix("nick", "user", null), IrcPrefix.parse("nick!user"))
    }

    @Test
    fun parsesNickHost() {
        assertEquals(IrcPrefix("nick", null, "gateway.tld"), IrcPrefix.parse("nick@gateway.tld"))
    }

    @Test
    fun parsesBareNick() {
        assertEquals(IrcPrefix("nick"), IrcPrefix.parse("nick"))
        assertTrue(IrcPrefix.parse("irc.example.com")!!.isServer)
    }

    @Test
    fun rejectsEmptyAndSpaced() {
        assertNull(IrcPrefix.parse(""))
        assertNull(IrcPrefix.parse("a b!c@d"))
        assertNull(IrcPrefix.parse("!user@host"))
        assertNull(IrcPrefix.parse("nick!@host"))
        assertNull(IrcPrefix.parse("nick!user@"))
    }

    @Test
    fun toStringRoundTrips() {
        for (raw in listOf("n!u@h", "server.tld", "nick")) {
            assertEquals(raw, IrcPrefix.parse(raw)!!.toString())
        }
        assertEquals("nick!user", IrcPrefix.parse("nick!user")!!.toString())
    }
}
