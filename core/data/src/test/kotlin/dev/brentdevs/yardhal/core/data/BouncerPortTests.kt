package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BouncerServCommandTests {

    @Test
    fun networkUpdateToggle() {
        assertEquals("network update libera -enabled true", BouncerServCommand.networkUpdate("libera", true))
        assertEquals("network update libera -enabled false", BouncerServCommand.networkUpdate("libera", false))
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
        assertEquals("24h", BouncerServCommand.formatDuration(86400))
        assertEquals("90m", BouncerServCommand.formatDuration(5400))
        assertEquals("59s", BouncerServCommand.formatDuration(59))
        assertEquals("0", BouncerServCommand.formatDuration(0))
        assertFailsWith<IllegalArgumentException> { BouncerServCommand.formatDuration(-3600) }
    }

    @Test
    fun channelUpdateAssemblesFlagsInOrder() {
        assertEquals(
            "channel update '#room' -detach-after 24h -relay-detached highlight -reattach-on message",
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
        assertEquals("channel status -network '#a'", BouncerServCommand.channelStatus("#a"))
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
        assertFalse(BouncerNetworkDraft(addr = "ircs://host:abc").isValid())
        assertFalse(BouncerNetworkDraft(addr = "ircs://host:999999").isValid())
        assertFalse(BouncerNetworkDraft(addr = "ircs://[2001:db8::1").isValid())
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
    fun unixAddressesRetainTheirSchemeAndClearTcpAddressFields() {
        val draft = BouncerNetworkDraft(addr = "irc+unix:///run/irc.sock")
        val attributes = draft.toAttributes()
        assertEquals("irc+unix:///run/irc.sock", attributes.host)
        assertEquals(mapOf("port" to "", "tls" to ""), attributes.unknown)
        val baseline = IrcBouncerNetworks.Attributes(host = "h", port = 6697, tls = true)
        val diff = draft.attributesChangedAgainst(baseline)
        assertEquals("irc+unix:///run/irc.sock", diff.host)
        assertEquals(mapOf("port" to "", "tls" to ""), diff.unknown)
        assertEquals(draft.addr, BouncerNetworkDraft.fromAttributes(attributes).addr)
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
    fun clearingFieldsAndPasswordIsAnExplicitChange() {
        val baseline = IrcBouncerNetworks.Attributes(name = "n", host = "h", nickname = "nick", realname = "Old Name")
        val draft = BouncerNetworkDraft.fromAttributes(baseline).copy(nick = "", realname = "", passwordChanged = true)
        val diff = draft.attributesChangedAgainst(baseline)
        assertEquals("", diff.nickname)
        assertEquals("", diff.realname)
        assertEquals("", diff.pass)
        assertNull(diff.host)
        assertNull(diff.name)
    }

    @Test
    fun passwordAndExistingIpv6AreNotLost() {
        val baseline = IrcBouncerNetworks.Attributes(host = "2001:db8::1", port = 6697, tls = true)
        val draft = BouncerNetworkDraft.fromAttributes(baseline)
        assertEquals("ircs://[2001:db8::1]:6697", draft.addr)
        assertEquals("", draft.attributesChangedAgainst(baseline).attributeString())
        assertFalse(draft.copy(password = "hidden-password").toString().contains("hidden-password"))
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
