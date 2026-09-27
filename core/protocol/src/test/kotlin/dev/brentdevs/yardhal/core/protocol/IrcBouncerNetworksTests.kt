package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrcBouncerNetworksTests {

    @Test
    fun attributeStringIsSortedAndEscaped() {
        val attrs = IrcBouncerNetworks.Attributes(
            name = "libera",
            host = "irc.libera.chat",
            port = 6697,
            tls = true,
            nickname = "nick;with;semis\\and",
        )
        val wire = attrs.attributeString()
        assertEquals(
            "host=irc.libera.chat;name=libera;nickname=nick\\;with\\;semis\\\\and;port=6697;tls=1",
            wire,
        )
    }

    @Test
    fun parseAttributesRoundTripsThroughSerialization() {
        val original = IrcBouncerNetworks.Attributes(
            name = "net name",
            host = "h",
            port = 6667,
            tls = false,
            nickname = "n",
            username = "u",
            realname = "r",
            pass = "p;a\\ss",
            error = null,
            unknown = mapOf("custom" to "v"),
        )
        assertEquals(original, IrcBouncerNetworks.parseAttributes(original.attributeString()))
    }

    @Test
    fun parseNetworkUpsert() {
        val update = IrcBouncerNetworks.parseNetwork(listOf("NETWORK", "id1", "name=n;state=connected"))!!
        assertEquals("id1", update.netId)
        val attrs = (update.change as IrcBouncerNetworks.Change.Upsert).attributes
        assertEquals("n", attrs.name)
        assertEquals(IrcBouncerNetworks.State.CONNECTED, attrs.state)
    }

    @Test
    fun parseNetworkDeleteUsesAsterisk() {
        val update = IrcBouncerNetworks.parseNetwork(listOf("NETWORK", "id2", "*"))!!
        assertEquals("id2", update.netId)
        assertTrue(update.change is IrcBouncerNetworks.Change.Deleted)
    }

    @Test
    fun parseNetworkRejectsWrongSubcommandAndMissingId() {
        assertNull(IrcBouncerNetworks.parseNetwork(listOf("ADDNETWORK", "x")))
        assertNull(IrcBouncerNetworks.parseNetwork(listOf("NETWORK")))
        assertNull(IrcBouncerNetworks.parseNetwork(listOf("NETWORK", "")))
    }

    @Test
    fun addNetworkReplyExtraction() {
        assertEquals("new-id", IrcBouncerNetworks.parseAddNetworkReply(listOf("ADDNETWORK", "new-id")))
        assertNull(IrcBouncerNetworks.parseAddNetworkReply(listOf("DELNETWORK", "x")))
        assertNull(IrcBouncerNetworks.parseAddNetworkReply(listOf("ADDNETWORK", "")))
    }

    @Test
    fun commandBuilders() {
        assertEquals(
            "BOUNCER ADDNETWORK host=h;name=n;tls=1",
            IrcBouncerNetworks.addNetworkCommand(IrcBouncerNetworks.Attributes(name = "n", host = "h", tls = true)),
        )
        assertEquals("BOUNCER DELNETWORK id9", IrcBouncerNetworks.delNetworkCommand("id9"))
    }

    @Test
    fun mergeOnlyOverwritesPresentFields() {
        val base = IrcBouncerNetworks.Attributes(name = "a", host = "h1", port = 6697)
        val merged = base.merged(IrcBouncerNetworks.Attributes(port = 7000, error = "boom"))
        assertEquals("a", merged.name)
        assertEquals("h1", merged.host)
        assertEquals(7000, merged.port)
        assertEquals("boom", merged.error)
    }
}
