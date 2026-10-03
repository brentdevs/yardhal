package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NamesParserTests {

    private val prefixes = ChannelPrefixModes.DEFAULT

    @Test
    fun parsesHighestPrefixWithRole() {
        assertEquals(ChannelMember("alice", '@'), NamesParser.parseMember("@alice", prefixes))
        assertEquals(ChannelMember("bob", '+'), NamesParser.parseMember("+bob", prefixes))
        assertEquals(ChannelMember("carol", null), NamesParser.parseMember("carol", prefixes))
    }

    @Test
    fun keepsHighestRoleWhenMultiPrefixStacksPrefixes() {
        val stacked = ChannelPrefixModes(listOf('o', 'v', 'h'), listOf('@', '+', '!'))
        assertEquals(ChannelMember("alice", '@'), NamesParser.parseMember("@+alice", stacked))
        assertEquals(ChannelMember("bob", '+'), NamesParser.parseMember("+!bob", stacked))
    }

    @Test
    fun stripsUserhostWhenUserhostInNamesIsNegotiated() {
        assertEquals(ChannelMember("alice", '@'), NamesParser.parseMember("@alice!u@host.tld", prefixes))
        assertEquals(ChannelMember("bob", null), NamesParser.parseMember("bob!u@host.tld", prefixes))
    }

    @Test
    fun retainsUserAndHostFromUserhostInNames() {
        assertEquals(
            NamesEntry(ChannelMember("alice", '@'), user = "~al", host = "host.tld"),
            NamesParser.parseEntry("@+alice!~al@host.tld", ChannelPrefixModes(listOf('o', 'v'), listOf('@', '+'))),
        )
        assertEquals(NamesEntry(ChannelMember("carol", null)), NamesParser.parseEntry("carol", prefixes))
        val (_, entries) = NamesParser.parseNamesLine(listOf("me", "=", "#room", "@alice!a@h1 bob!b@h2"), prefixes)
        assertEquals(listOf("a" to "h1", "b" to "h2"), entries.map { it.user to it.host })
    }

    @Test
    fun parsesChannelAndMembers() {
        val (channel, members) = NamesParser.parseNamesLine(
            listOf("me", "=", "#room", "@alice +bob carol dave"),
            prefixes,
        )
        assertEquals("#room", channel)
        assertEquals(
            listOf(
                ChannelMember("alice", '@'),
                ChannelMember("bob", '+'),
                ChannelMember("carol", null),
                ChannelMember("dave", null),
            ),
            members.map { it.member },
        )
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
