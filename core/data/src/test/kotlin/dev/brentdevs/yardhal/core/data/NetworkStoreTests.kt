package dev.brentdevs.yardhal.core.data

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class NetworkStoreTests {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): NetworkStore = NetworkStore(tmp.root)

    @Test
    fun addAndRetrieve() {
        val store = store()
        val config = NetworkConfig(id = "n1", name = "Libera", host = "irc.libera.chat", nick = "tester")
        assertTrue(store.add(config))
        assertEquals(config, store.byId("n1"))
        assertFalse(store.add(config.copy(name = "dup")))
        assertEquals(1, store.all().size)
    }

    @Test
    fun persistsAcrossInstances() {
        val first = store()
        first.add(NetworkConfig(id = "n1", name = "Libera", host = "irc.libera.chat", nick = "tester"))
        first.update(NetworkConfig(id = "n1", name = "Renamed", host = "irc.libera.chat", nick = "tester"))

        val second = NetworkStore(tmp.root)
        assertEquals("Renamed", assertNotNull(second.byId("n1")).name)
    }

    @Test
    fun removePersists() {
        val first = store()
        first.add(NetworkConfig(id = "n1", name = "x", host = "h", nick = "n"))
        assertTrue(first.remove("n1"))

        val second = NetworkStore(tmp.root)
        assertNull(second.byId("n1"))
        assertTrue(second.all().isEmpty())
    }

    @Test
    fun saslPasswordStoredByReferenceOnly() {
        val config = NetworkConfig(
            id = "n1",
            name = "x",
            host = "h",
            nick = "n",
            saslAuthcid = "alice",
            saslPasswordRef = "cred-n1",
        )
        assertTrue(store().add(config))
        val text = File(tmp.root, "networks.json").readText()
        assertFalse(text.contains("secret"), "plaintext secrets must never hit disk")
        assertEquals("cred-n1", assertNotNull(NetworkStore(tmp.root).byId("n1")).saslPasswordRef)
    }

    @Test
    fun legacyConfigurationLoadsConservativeDefaults() {
        File(tmp.root, "networks.json").writeText(
            """[{"id":"legacy","name":"Old","host":"irc.example","nick":"tester","unusedFutureField":true}]""",
        )
        val loaded = assertNotNull(store().byId("legacy"))
        assertEquals(NetworkConfig(id = "legacy", name = "Old", host = "irc.example", nick = "tester"), loaded)
        assertTrue(loaded.autoConnect)
        assertFalse(loaded.userDisconnected)
        assertEquals(SaslMode.AUTO, loaded.saslMode)
        assertTrue(loaded.waitForNickServ)
        assertNull(loaded.proxy)
        assertNull(loaded.tlsClientAlias)
    }

    @Test
    fun allResolvedSecretsStayOutOfDurableConfigurationAndSavedSettingsReopen() {
        val store = store()
        for (mode in SaslMode.entries) {
            val config = NetworkConfig(
                id = mode.name,
                name = "Saved authentication",
                host = "irc.example",
                nick = "tester",
                username = "hidden-user",
                realName = "Saved realname",
                alternateNicks = listOf("tester_", "tester__"),
                autoConnect = false,
                userDisconnected = true,
                saslAuthcid = "sasl-account",
                saslMode = mode,
                saslPasswordRef = "sasl-ref",
                serverPasswordRef = "server-ref",
                nickServAccount = "nickserv-account",
                nickServPasswordRef = "nickserv-ref",
                nickServService = "Services",
                waitForNickServ = false,
                proxy = SocksProxyConfig("proxy.example", 1081, "proxy-user", "proxy-ref"),
                tlsClientAlias = "android-keychain-alias",
                certificatePin = CertificatePin("irc.example", 6697, "ab".repeat(32)),
                saslPassword = "resolved-sasl-secret",
                serverPassword = "resolved-server-secret",
                nickServPassword = "resolved-nickserv-secret",
                proxyPassword = "resolved-proxy-secret",
                knownSecrets = setOf("resolved-znc-account-secret"),
            )
            assertTrue(store.add(config))
            val reopened = assertNotNull(NetworkStore(tmp.root).byId(mode.name))
            assertEquals(
                config.copy(saslPassword = null, serverPassword = null, nickServPassword = null, proxyPassword = null, knownSecrets = emptySet()),
                reopened,
            )
            assertNull(reopened.tlsClientIdentity)
        }
        val serialized = File(tmp.root, "networks.json").readText()
        for (secret in listOf("resolved-sasl-secret", "resolved-server-secret", "resolved-nickserv-secret", "resolved-proxy-secret", "resolved-znc-account-secret")) {
            assertFalse(serialized.contains(secret))
        }
        assertFalse(serialized.contains("\"tlsClientIdentity\""))
        assertFalse(serialized.contains("\"saslPassword\""))
        assertFalse(serialized.contains("\"serverPassword\""))
        assertFalse(serialized.contains("\"nickServPassword\""))
        assertFalse(serialized.contains("\"proxyPassword\""))
        assertFalse(serialized.contains("\"knownSecrets\""))
    }

    @Test
    fun failedMutationsKeepBothCommittedCacheAndDiskAndCanBeRetried() {
        val store = store()
        val original = NetworkConfig(id = "n1", name = "Original", host = "irc.example", nick = "tester")
        assertTrue(store.add(original))
        val blocker = File(tmp.root, "networks.json.tmp")
        assertTrue(blocker.mkdir())
        assertFalse(store.add(original.copy(id = "n2")))
        assertFalse(store.update(original.copy(name = "Rejected")))
        assertFalse(store.setUserDisconnected(original.id, true))
        assertFalse(store.remove(original.id))
        assertEquals(listOf(original), store.all())
        assertEquals(listOf(original), NetworkStore(tmp.root).all())
        assertTrue(blocker.delete())
        assertTrue(store.setUserDisconnected(original.id, true))
        assertEquals(original.copy(userDisconnected = true), store.byId(original.id))
        assertEquals(store.all(), NetworkStore(tmp.root).all())
        assertTrue(store.setUserDisconnected(original.id, false))
        assertEquals(original, NetworkStore(tmp.root).byId(original.id))
    }

    @Test
    fun concurrentAddsAndDisconnectIntentMutationsHaveNoLostDurableRecords() {
        val store = store()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 40).map { index ->
                executor.submit<Boolean> {
                    val config = NetworkConfig(
                        id = "n$index", name = "Network $index", host = "irc.example", nick = "tester",
                        autoConnect = index % 2 == 0,
                    )
                    store.add(config) && store.setUserDisconnected(config.id, true)
                }
            }
            results.forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
            assertEquals(40, store.all().size)
            assertTrue(store.all().all { it.userDisconnected })
            assertEquals(store.all(), NetworkStore(tmp.root).all())
            assertFalse(store.setUserDisconnected("missing", true))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun credentialRetirementChecksEveryNetworkAndEveryRole() {
        val store = store()
        val vault = InMemoryCredentialVault()
        vault.storePassword("shared", "protected-password")
        vault.storePassword("unused", "retired-password")
        val first = NetworkConfig(
            id = "first", name = "First", host = "irc.example", nick = "tester",
            saslPasswordRef = "shared", serverPasswordRef = "shared",
        )
        val second = first.copy(
            id = "second", saslPasswordRef = null, serverPasswordRef = null,
            nickServPasswordRef = "shared", proxy = SocksProxyConfig("proxy.example", passwordRef = "shared"),
        )
        assertTrue(store.add(first))
        assertTrue(store.add(second))
        store.deleteUnreferencedPasswords(listOf("shared", "unused"), vault)
        assertEquals("protected-password", vault.readPassword("shared"))
        assertNull(vault.readPassword("unused"))
        assertTrue(store.update(first.copy(saslPasswordRef = null, serverPasswordRef = null)))
        store.deleteUnreferencedPasswords(listOf("shared"), vault)
        assertEquals("protected-password", vault.readPassword("shared"))
        assertTrue(store.update(second.copy(nickServPasswordRef = null)))
        store.deleteUnreferencedPasswords(listOf("shared"), vault)
        assertEquals("protected-password", vault.readPassword("shared"))
        assertTrue(store.remove(second.id))
        store.deleteUnreferencedPasswords(listOf("shared", "shared"), vault)
        assertNull(vault.readPassword("shared"))
    }
}
