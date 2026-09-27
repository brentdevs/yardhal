package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhoisAccumulatorTests {

    private val accumulator = WhoisAccumulator()

    @Test
    fun assemblesCompleteWhoisOnEndNumeric() {
        assertNull(accumulator.handle(311, listOf("me", "alice", "a", "h.tld", "*", "Alice A")))
        assertNull(accumulator.handle(312, listOf("me", "alice", "irc.example.org", "Example server")))
        assertNull(accumulator.handle(330, listOf("me", "alice", "alice-account")))
        assertNull(accumulator.handle(317, listOf("me", "alice", "42", "1700000000")))
        assertNull(accumulator.handle(319, listOf("me", "alice", "#a @#b")))
        val complete = accumulator.handle(318, listOf("me", "alice", ":End of /WHOIS list"))!!

        assertEquals("alice", complete.nick)
        assertEquals("a", complete.user)
        assertEquals("h.tld", complete.host)
        assertEquals("Alice A", complete.realName)
        assertEquals("irc.example.org", complete.server)
        assertEquals("alice-account", complete.account)
        assertEquals(42L, complete.idleSeconds)
        assertEquals(listOf("#a", "@#b"), complete.channels)
    }

    @Test
    fun operFlagAndAwayTracked() {
        accumulator.handle(311, listOf("me", "op", "o", "oh", "*", "Op"))
        assertNull(accumulator.handle(313, listOf("me", "op", "is an operator")))
        assertNull(accumulator.handle(301, listOf("me", "op", "brb")))
        val complete = accumulator.handle(318, listOf("me", "op", "end"))!!
        assertTrue(complete.isOper)
        assertEquals("brb", complete.awayMessage)
    }

    @Test
    fun resetClearsState() {
        accumulator.handle(311, listOf("me", "x", "u", "h", "*", "X"))
        accumulator.reset()
        assertNull(accumulator.handle(312, listOf("me", "y", "srv", "info")))
    }
}
