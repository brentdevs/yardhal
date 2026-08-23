package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BouncerServCommandTests {

    @Test
    fun networkUpdateToggle() {
        assertEquals("network update libera -enabled", BouncerServCommand.networkUpdate("libera", true))
        assertEquals("network update libera -disabled", BouncerServCommand.networkUpdate("libera", false))
    }

    @Test
    fun posixQuotingOnlyWhenNeeded() {
        assertEquals("libera", BouncerServCommand.posixQuote("libera"))
        assertEquals("'two words'", BouncerServCommand.posixQuote("two words"))
        assertEquals("'it'\\''s'", BouncerServCommand.posixQuote("it's"))
        assertEquals("''", BouncerServCommand.posixQuote(""))
    }

    @Test
    fun durationFormattingPicksLargestUnit() {
        assertEquals("1d", BouncerServCommand.formatDuration(86400))
        assertEquals("90m", BouncerServCommand.formatDuration(5400))
        assertEquals("59s", BouncerServCommand.formatDuration(59))
        assertEquals("0", BouncerServCommand.formatDuration(0))
        assertEquals("-1h", BouncerServCommand.formatDuration(-3600))
    }

    @Test
    fun channelUpdateAssemblesFlagsInOrder() {
        assertEquals(
            "channel update '#room' -detach-after 1d -relay-detached highlight -reattach-on message",
            BouncerServCommand.channelUpdate(
                "#room",
                detachAfterSeconds = 86400,
                relayDetached = BouncerServCommand.RelayMode.HIGHLIGHT,
                reattachOn = BouncerServCommand.RelayMode.MESSAGE,
            ),
        )
    }

    @Test
    fun channelStatusVariants() {
        assertEquals("channel status", BouncerServCommand.channelStatus())
        assertEquals("channel status '#a'", BouncerServCommand.channelStatus("#a"))
    }
}

class BouncerNetworkDraftTests {

    @Test
    fun addrSchemesParse() {
        assertEquals(
            BouncerNetworkDraft.ParsedAddr(BouncerNetworkDraft.Scheme.TLS, "irc.libera.chat", 6697),
            BouncerNetworkDraft(addr = "ircs://irc.libera.chat:6697").parseAddr(),
        )
        assertEquals(
            BouncerNetworkDraft.ParsedAddr(BouncerNetworkDraft.Scheme.PLAIN, "h", null),
            BouncerNetworkDraft(addr = "irc+insecure://h").parseAddr(),
        )
        assertEquals(
            BouncerNetworkDraft.ParsedAddr(BouncerNetworkDraft.Scheme.UNIX, "/run/soju", null),
            BouncerNetworkDraft(addr = "irc+unix:///run/soju").parseAddr(),
        )
    }

    @Test
    fun validationMessages() {
        assertEquals("Address is required.", BouncerNetworkDraft().addrValidationError())
        assertEquals(
            "Use ircs://host, irc+insecure://host, or irc+unix:///path.",
            BouncerNetworkDraft(addr = "ftp://x").addrValidationError(),
        )
        assertNull(BouncerNetworkDraft(addr = "ircs://host:6697").addrValidationError())
        assertTrue(BouncerNetworkDraft(addr = "ircs://host").isValid())
    }

    @Test
    fun toAttributesMapsSchemeToTlsAndDefaultPorts() {
        val tls = BouncerNetworkDraft(addr = "ircs://h").toAttributes()
        assertEquals(true, tls.tls)
        assertEquals(6697, tls.port)

        val plain = BouncerNetworkDraft(addr = "irc+insecure://h:7000").toAttributes()
        assertEquals(false, plain.tls)
        assertEquals(7000, plain.port)
    }

    @Test
    fun diffContainsOnlyChangedKeys() {
        val baseline = IrcBouncerNetworks.Attributes(name = "n", host = "old", port = 6697, tls = true)
        val diff = BouncerNetworkDraft(addr = "ircs://new", name = "n")
            .attributesChangedAgainst(baseline)
        assertEquals("new", diff.host)
        assertNull(diff.name)
        assertNull(diff.port)
        assertNull(diff.pass)
    }

    @Test
    fun fromAttributesRoundTripsAddress() {
        val draft = BouncerNetworkDraft.fromAttributes(
            IrcBouncerNetworks.Attributes(host = "h", port = 16667, tls = true),
        )
        assertEquals("ircs://h:16667", draft.addr)
        assertTrue(draft.enabled)
    }
}

class BouncerNetworkStoreTests {

    private fun upsert(id: String, name: String): IrcBouncerNetworks.NetworkUpdate =
        IrcBouncerNetworks.NetworkUpdate(
            id,
            IrcBouncerNetworks.Change.Upsert(IrcBouncerNetworks.Attributes(name = name)),
        )

    @Test
    fun upsertMergeAndDelete() {
        val store = BouncerNetworkStore()
        assertTrue(store.apply(upsert("id1", "Libera")))
        store.apply(IrcBouncerNetworks.NetworkUpdate("id1", IrcBouncerNetworks.Change.Upsert(IrcBouncerNetworks.Attributes(port = 7000))))
        assertEquals(7000, store.get("id1")!!.port)
        assertEquals("Libera", store.get("id1")!!.name)

        assertFalse(store.apply(upsert("id1", "Libera")))
        store.apply(IrcBouncerNetworks.parseNetwork(listOf("NETWORK", "id1", "*"))!!)
        assertNull(store.get("id1"))
    }

    @Test
    fun sortedByNameAndClearResetsBinding() {
        store.apply(upsert("b", "zeta"))
        store.apply(upsert("a", "alpha"))
        assertEquals(listOf("a", "b"), store.all().map { it.first })

        store.bind("a")
        assertEquals("a", store.boundNetId)
        store.clear()
        assertNull(store.boundNetId)
        assertTrue(store.all().isEmpty())
    }

    private val store = BouncerNetworkStore()
}
