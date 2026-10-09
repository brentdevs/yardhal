package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SojuUpstreamConfigTests {
    @get:Rule val tmp = TemporaryFolder()

    private fun parent(): NetworkConfig = NetworkConfig(
        id = "6d519ba4-21af-4135-b021-504265f3cd26", name = "Account", host = "bouncer.example", nick = "account-nick",
        mode = NetworkMode.SOJU, saslAuthcid = "account", saslPasswordRef = "shared", serverPasswordRef = "server",
        nickServPasswordRef = "nickserv", tlsClientAlias = "identity", certificatePin = CertificatePin("bouncer.example", 6697, "ab".repeat(32)),
        proxy = SocksProxyConfig("proxy.example", username = "proxy-user", passwordRef = "proxy"), autojoin = listOf("#account-only"),
    )

    @Test fun identitiesMatchHalyardUuidVersionFiveVectorsAndLegacyIdsAreDeterministic() {
        assertEquals("56f6cfdc-d834-5ff6-94fc-daf2ab4b2a71", DerivedNetworkIdentity.sojuNetworkId("00000000-0000-0000-0000-000000000000", "1"))
        assertEquals("1ed81162-769a-5ace-8a85-a1362f110173", DerivedNetworkIdentity.sojuNetworkId(parent().id, "42"))
        assertEquals("1ed81162-769a-5ace-8a85-a1362f110173", DerivedNetworkIdentity.sojuNetworkId(parent().id.uppercase(), "42"))
        assertEquals("d2d3658c-4ee9-5255-b112-f07507895da4", DerivedNetworkIdentity.sojuNetworkId("ffffffff-ffff-ffff-ffff-ffffffffffff", "network/é"))
        val legacy = DerivedNetworkIdentity.sojuNetworkId("legacy-account", "42")
        assertEquals("bafa22ae-7346-582e-95f5-6117918f90f6", legacy)
        assertEquals(5, UUID.fromString(legacy).version())
        assertEquals(2, UUID.fromString(legacy).variant())
        assertNotEquals(legacy, DerivedNetworkIdentity.sojuNetworkId("legacy-account", "43"))
        assertNotEquals(legacy, DerivedNetworkIdentity.sojuNetworkId("Legacy-account", "42"))
    }

    @Test fun boundConfigInheritsAccountTransportAndAuthenticationButNeverUpstreamServerAddressOrNickserv() {
        val parent = parent()
        val attrs = IrcBouncerNetworks.Attributes(name = "Libera", host = "irc.libera.chat", port = 6667, tls = false,
            nickname = "upstream-nick", realname = "Upstream Name", pass = "remote-server-secret")
        val child = SojuUpstreamConfig.reconcile(parent, "42", attrs)
        assertEquals(parent.host, child.host)
        assertEquals(parent.port, child.port)
        assertEquals(parent.tls, child.tls)
        assertEquals(parent.proxy, child.proxy)
        assertEquals(parent.certificatePin, child.certificatePin)
        assertEquals(parent.tlsClientAlias, child.tlsClientAlias)
        assertEquals(parent.saslPasswordRef, child.saslPasswordRef)
        assertEquals(parent.serverPasswordRef, child.serverPasswordRef)
        assertEquals("upstream-nick", child.nick)
        assertEquals("Upstream Name", child.realName)
        assertTrue(child.autojoin.isEmpty())
        assertNull(child.nickServPasswordRef)
        assertFalse(child.waitForNickServ)
        assertEquals(BouncerBinding(parent.id, "42", nickname = "upstream-nick", realName = "Upstream Name"), child.bouncerBinding)
        assertFalse(child.toString().contains("remote-server-secret"))
    }

    @Test fun duplicateAndDeltaUpdatesPreserveIdentityIndependentIntentAndInheritedEdits() {
        val parent = parent()
        val original = SojuUpstreamConfig.reconcile(parent, "42", IrcBouncerNetworks.Attributes(name = "Old", nickname = "own-nick"))
            .copy(autojoin = listOf("#saved"), autoConnect = false, userDisconnected = true)
        val delta = SojuUpstreamConfig.reconcile(parent, "42", IrcBouncerNetworks.Attributes(name = "New"), original)
        assertEquals(original.id, delta.id)
        assertEquals("own-nick", delta.nick)
        assertEquals("New", delta.name)
        assertEquals(original.autojoin, delta.autojoin)
        assertFalse(delta.autoConnect)
        assertTrue(delta.userDisconnected)
        assertEquals(delta, SojuUpstreamConfig.reconcile(parent, "42", IrcBouncerNetworks.Attributes(name = "New"), delta))
        val changedParent = parent.copy(host = "new-bouncer.example", port = 7000, saslPasswordRef = "replacement", proxy = null)
        val inherited = SojuUpstreamConfig.inherit(changedParent, delta)
        assertEquals(changedParent.host, inherited.host)
        assertEquals(changedParent.port, inherited.port)
        assertEquals("replacement", inherited.saslPasswordRef)
        assertNull(inherited.proxy)
        assertEquals(original.id, inherited.id)
        assertEquals("own-nick", inherited.nick)
        assertTrue(inherited.userDisconnected)
    }

    @Test fun explicitEmptyUpstreamOverridesReturnToInheritedNicknameAndRealName() {
        val parent = parent()
        val original = SojuUpstreamConfig.reconcile(parent, "42", IrcBouncerNetworks.Attributes(nickname = "old", realname = "Old"))
        val cleared = SojuUpstreamConfig.reconcile(parent, "42", IrcBouncerNetworks.Attributes(nickname = "", realname = ""), original)
        assertEquals(parent.nick, cleared.nick)
        assertEquals(parent.realName, cleared.realName)
        assertNull(cleared.bouncerBinding?.nickname)
        assertNull(cleared.bouncerBinding?.realName)
    }

    @Test fun persistedOfflineShellsRetainBindingsDisabledRejectionsAndAllSharedSecretReferences() {
        val store = NetworkStore(tmp.root)
        val parent = parent()
        val child = SojuUpstreamConfig.reconcile(parent, "42", IrcBouncerNetworks.Attributes(name = "Saved", unknown = mapOf("enabled" to "0")))
        val rejected = child.copy(bouncerBinding = assertNotNull(child.bouncerBinding).copy(rejectionReason = "Binding rejected"))
        assertTrue(store.upsertAll(listOf(parent, rejected)))
        val restored = NetworkStore(tmp.root)
        assertEquals(listOf(rejected), restored.dependents(parent.id))
        assertEquals(store.all(), restored.all())
        assertFalse(assertNotNull(restored.byId(child.id)?.bouncerBinding).enabled)
        assertEquals("Binding rejected", restored.byId(child.id)?.bouncerBinding?.rejectionReason)
        assertTrue(store.upsertAll(listOf(rejected)))
        assertEquals(2, store.all().size)
    }

    @Test fun failedParentEditsAndCascadeDeletionNeverCommitPartialRowsOrRetireSharedSecrets() {
        val store = NetworkStore(tmp.root)
        val parent = parent()
        val child = SojuUpstreamConfig.reconcile(parent, "42", IrcBouncerNetworks.Attributes())
        val unrelated = parent.copy(id = "other", name = "Other", mode = NetworkMode.DIRECT)
        val vault = InMemoryCredentialVault().also { it.storePassword("shared", "safe") }
        assertTrue(store.upsertAll(listOf(parent, child, unrelated)))
        val blocker = File(tmp.root, "networks.json.tmp")
        assertTrue(blocker.mkdir())
        assertFalse(store.upsertAll(listOf(parent.copy(host = "changed"), child.copy(host = "changed"))))
        assertFalse(store.removeAll(setOf(parent.id, child.id)))
        assertEquals(listOf(parent, child, unrelated), store.all())
        assertEquals(store.all(), NetworkStore(tmp.root).all())
        assertEquals("safe", vault.readPassword("shared"))
        assertTrue(blocker.delete())
        assertTrue(store.removeAll(setOf(parent.id, child.id)))
        store.deleteUnreferencedPasswords(listOf("shared"), vault)
        assertEquals("safe", vault.readPassword("shared"))
        assertTrue(store.remove(unrelated.id))
        store.deleteUnreferencedPasswords(listOf("shared"), vault)
        assertNull(vault.readPassword("shared"))
    }
}
