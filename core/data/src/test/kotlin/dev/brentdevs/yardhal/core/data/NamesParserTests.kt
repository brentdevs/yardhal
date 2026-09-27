package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NamesParserTests {

    private val prefixes = ChannelPrefixModes.DEFAULT

    @Test
    fun stripsHighestPrefixOnly() {
        assertEquals("alice", NamesParser.stripPrefixes("@alice", prefixes))
        assertEquals("bob", NamesParser.stripPrefixes("+bob", prefixes))
        assertEquals("carol", NamesParser.stripPrefixes("carol", prefixes))
    }

    @Test
    fun parsesChannelAndMembers() {
        val (channel, members) = NamesParser.parseNamesLine(
            listOf("me", "=", "#room", "@alice +bob carol dave"),
            prefixes,
        )
        assertEquals("#room", channel)
        assertEquals(listOf("alice", "bob", "carol", "dave"), members)
    }

    @Test
    fun emptyPayloadYieldsEmpty() {
        val (_, members) = NamesParser.parseNamesLine(listOf("me", "#room", ""), prefixes)
        assertTrue(members.isEmpty())
    }

    @Test
    fun malformedShortParamsSafe() {
        val (channel, members) = NamesParser.parseNamesLine(listOf("only"), prefixes)
        assertNull(channel)
        assertEquals(emptyList(), members)
    }
}
