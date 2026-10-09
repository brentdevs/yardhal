package dev.brentdevs.yardhal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.coordinator.ConnectionFactory
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.core.client.InMemoryStsPolicyStore
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.CredentialVault
import dev.brentdevs.yardhal.core.data.InMemoryCredentialVault
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.SaslMode
import dev.brentdevs.yardhal.core.data.SocksProxyConfig
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.YardhalDatabase
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.ui.screens.NetworkDraft
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NetworkSaverTests {
    private class Server : AutoCloseable {
        private val listener = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        private val executor = Executors.newCachedThreadPool()
        val clients = CopyOnWriteArrayList<Client>()
        val port: Int get() = listener.localPort

        inner class Client(private val socket: Socket) : AutoCloseable {
            val received = CopyOnWriteArrayList<String>()
            private var nick = "*"

            fun read() {
                try {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    while (!socket.isClosed) {
                        val line = reader.readLine() ?: break
                        received.add(line)
                        when {
                            line.startsWith("CAP LS") -> send(":srv CAP * LS :sasl=PLAIN")
                            line.startsWith("CAP REQ :") -> send(":srv CAP * ACK :${line.substringAfter("CAP REQ :")}")
                            line == "AUTHENTICATE PLAIN" -> send("AUTHENTICATE +")
                            line.startsWith("AUTHENTICATE ") -> send(":srv 903 * :SASL authentication successful")
                            line.startsWith("NICK ") -> nick = line.substringAfter("NICK ")
                            line.startsWith("USER ") -> send(":srv 001 $nick :Welcome")
                        }
                    }
                } finally {
                    close()
                }
            }

            private fun send(line: String) {
                socket.getOutputStream().write((line + "\r\n").toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }

            fun saslPlain(): String? = received.firstOrNull {
                it.startsWith("AUTHENTICATE ") && it != "AUTHENTICATE PLAIN" && it != "AUTHENTICATE +"
            }?.substringAfter("AUTHENTICATE ")?.let {
                String(Base64.getDecoder().decode(it), Charsets.UTF_8)
            }

            fun parameters(command: String): List<String>? = received.firstNotNullOfOrNull {
                IrcMessage.parse(it)?.takeIf { message -> message.command == command }?.parameters
            }

            override fun close() {
                socket.close()
            }
        }

        init {
            executor.submit {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    val client = Client(socket)
                    clients.add(client)
                    executor.submit { runCatching { client.read() } }
                }
            }
        }

        override fun close() {
            listener.close()
            clients.forEach { it.close() }
            executor.shutdownNow()
        }
    }

    private class Stage {
        private val staged = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private var key: String? = null

        fun block(key: String) {
            this.key = key
            staged.countDown()
            assertTrue(released.await(5, TimeUnit.SECONDS))
        }

        fun awaitKey(): String {
            assertTrue(staged.await(5, TimeUnit.SECONDS))
            return assertNotNull(key)
        }

        fun release() {
            released.countDown()
        }
    }

    private class ObservedVault(
        private val real: CredentialVault = InMemoryCredentialVault(),
    ) : CredentialVault by real {
        val storedKeys = CopyOnWriteArrayList<String>()
        @Volatile var stage: Stage? = null
        @Volatile var failOnStoreNumber: Int? = null

        override fun storePassword(key: String, password: String) {
            real.storePassword(key, password)
            storedKeys.add(key)
            if (storedKeys.size == failOnStoreNumber) throw IOException("Credential storage unavailable")
            stage?.block(key)
        }
    }

    private class Harness(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : AutoCloseable {
        val directory = Files.createTempDirectory("yardhal-network-save").toFile()
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + dispatcher)
        private val database = YardhalDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val vault = ObservedVault()
        val networks = NetworkStore(directory)
        private val policies = InMemoryStsPolicyStore()
        val attemptedConnections = CopyOnWriteArrayList<NetworkConfig>()
        val coordinator = LiveCoordinator(
            scope = scope,
            networkStore = networks,
            messageStore = MessageStore(database.messageDao()),
            readMarkers = ReadMarkerStore(directory),
            historyCoverage = dev.brentdevs.yardhal.core.data.HistoryCoverageStore(directory),
            mutes = MuteStore(directory),
            vault = vault,
            channelOrder = ChannelOrderStore(directory),
            connectionFactory = ConnectionFactory { config, onStsUpgrade ->
                attemptedConnections.add(config)
                IrcConnection(
                    IrcConnectionConfig(
                        host = config.host,
                        port = config.port,
                        tls = config.tls,
                        nick = config.nick,
                        username = config.username,
                        realName = config.realName,
                        saslAuthcid = config.saslAuthcid,
                        saslPassword = config.saslPassword,
                        serverPassword = config.serverPassword,
                        capabilities = setOf("sasl"),
                        saslMode = config.saslMode.name,
                        connectTimeoutMillis = 1_000,
                    ),
                    stsPolicyStore = policies,
                    onStsUpgrade = onStsUpgrade,
                )
            },
            stsPolicies = policies,
        )
        val saver = NetworkSaver(coordinator, vault)

        fun seed(config: NetworkConfig, password: String = "old-password") {
            assertTrue(networks.add(config))
            config.saslPasswordRef?.let { vault.storePassword(it, password) }
        }

        fun saved(): NetworkConfig = NetworkStore(directory).all().single()

        suspend fun registered(id: String) {
            withTimeout(5_000) {
                while (coordinator.networks.value.none { it.id == id && it.status == ConnectionStatus.REGISTERED }) {
                    (dispatcher as? TestDispatcher)?.scheduler?.runCurrent()
                    delay(10)
                }
            }
        }

        fun rejectSave(draft: NetworkDraft, rejection: (String) -> Unit): Boolean {
            val stage = Stage()
            val executor = Executors.newSingleThreadExecutor()
            vault.stage = stage
            try {
                val result = executor.submit<Boolean> { saver.save(draft) }
                try {
                    rejection(stage.awaitKey())
                } finally {
                    stage.release()
                }
                return result.get(5, TimeUnit.SECONDS)
            } finally {
                stage.release()
                vault.stage = null
                executor.shutdownNow()
            }
        }

        override fun close() {
            coordinator.networks.value.forEach { coordinator.disconnect(it.id) }
            if (dispatcher is TestDispatcher) {
                job.cancel()
                runBlocking {
                    withTimeout(5_000) {
                        while (!job.isCompleted) {
                            dispatcher.scheduler.runCurrent()
                            delay(10)
                        }
                    }
                }
            } else {
                runBlocking { job.cancelAndJoin() }
            }
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun config(server: Server, sharedPassword: Boolean = false): NetworkConfig = NetworkConfig(
        id = "network",
        name = "Original network",
        host = "127.0.0.1",
        port = server.port,
        tls = false,
        nick = "tester",
        username = "hidden-user",
        realName = "Hidden real name",
        saslAuthcid = "account",
        saslPasswordRef = "old-secret",
        serverPasswordRef = "old-secret".takeIf { sharedPassword },
    )

    private fun draft(config: NetworkConfig): NetworkDraft = NetworkDraft(
        host = config.host,
        port = config.port,
        tls = config.tls,
        nick = config.nick,
        saslPassword = null,
        autojoin = config.autojoin,
        displayName = config.name,
        networkId = config.id,
        saslAuthcid = config.saslAuthcid,
    )

    private fun assertFreshKey(config: NetworkConfig, previous: String? = null): String {
        val key = assertNotNull(config.saslPasswordRef)
        assertNotEquals(previous, key)
        return key
    }

    @Test
    fun addWithoutSaslPersistsDefaultsAndConnectsWithoutAuthentication() = runBlocking {
        Server().use { server ->
            Harness().use { harness ->
                val input = draft(config(server)).copy(networkId = null, saslAuthcid = null)
                assertTrue(harness.saver.save(input))
                val saved = harness.saved()
                harness.registered(saved.id)
                assertNull(saved.saslPasswordRef)
                assertNull(saved.saslAuthcid)
                assertTrue(harness.vault.storedKeys.isEmpty())
                assertNull(server.clients.single().saslPlain())
            }
        }
    }

    @Test
    fun addCommitsFreshSecretAndSessionAuthenticatesWithOpaquePassword() = runBlocking {
        Server().use { server ->
            Harness().use { harness ->
                val password = " \t päss word  "
                val input = draft(config(server)).copy(networkId = null, saslPassword = password, saslAuthcid = null)
                assertTrue(harness.saver.save(input))
                val saved = harness.saved()
                val key = assertFreshKey(saved)
                assertEquals(password, harness.vault.readPassword(key))
                assertEquals(input.nick, saved.saslAuthcid)
                assertNull(saved.saslPassword)
                harness.registered(saved.id)
                assertEquals("\u0000${input.nick}\u0000$password", server.clients.single().saslPlain())
            }
        }
    }

    @Test
    fun keepPasswordEditsPersistHiddenFieldsAndLeaveRegisteredAuthenticationUnchanged() = runBlocking {
        Server().use { server ->
            Harness().use { harness ->
                val original = config(server, sharedPassword = true)
                harness.seed(original)
                harness.coordinator.startAll()
                harness.registered(original.id)
                val client = server.clients.single()
                val writes = harness.vault.storedKeys.toList()
                assertTrue(harness.saver.save(draft(original).copy(displayName = "Renamed network")))
                assertEquals(original.copy(name = "Renamed network"), harness.saved())
                assertEquals(writes, harness.vault.storedKeys.toList())
                assertEquals("old-password", harness.vault.readPassword("old-secret"))
                assertEquals("Renamed network", harness.coordinator.networks.value.single().name)
                assertEquals(1, server.clients.size)
                assertEquals("\u0000account\u0000old-password", client.saslPlain())
                assertEquals("hidden-user", client.parameters("USER")?.firstOrNull())
                assertEquals("Hidden real name", client.parameters("USER")?.lastOrNull())
                assertEquals(listOf("old-password"), client.parameters("PASS"))
            }
        }
    }

    @Test
    fun replaceAlwaysRotatesKeyEvenForIdenticalPasswordAndCleansOldOnlySecretAfterCommit() = runBlocking {
        Server().use { server ->
            Harness().use { harness ->
                val original = config(server)
                harness.seed(original)
                harness.coordinator.startAll()
                harness.registered(original.id)
                assertTrue(harness.saver.save(draft(original).copy(saslPassword = "old-password")))
                val saved = harness.saved()
                val key = assertFreshKey(saved, original.saslPasswordRef)
                assertNull(harness.vault.readPassword("old-secret"))
                assertEquals("old-password", harness.vault.readPassword(key))
                assertEquals(original.copy(saslPasswordRef = key), saved)
                harness.registered(saved.id)
                assertEquals(2, server.clients.size)
                assertEquals("\u0000account\u0000old-password", server.clients.last().saslPlain())
            }
        }
    }

    @Test
    fun replaceSharedSecretPreservesServerPasswordAndCommitsWhitespaceOnlySaslPassword() = runBlocking {
        Server().use { server ->
            Harness().use { harness ->
                val original = config(server, sharedPassword = true)
                harness.seed(original)
                harness.coordinator.startAll()
                harness.registered(original.id)
                assertTrue(harness.saver.save(draft(original).copy(saslPassword = "   ")))
                val saved = harness.saved()
                val key = assertFreshKey(saved, "old-secret")
                assertEquals(original.copy(saslPasswordRef = key), saved)
                assertEquals("old-password", harness.vault.readPassword("old-secret"))
                assertEquals("   ", harness.vault.readPassword(key))
                harness.registered(saved.id)
                val client = server.clients.last()
                assertEquals(2, server.clients.size)
                assertEquals(listOf("old-password"), client.parameters("PASS"))
                assertEquals("\u0000account\u0000   ", client.saslPlain())
            }
        }
    }

    @Test
    fun clearOldOnlySecretCommitsNoSaslAndDeletesUnreferencedPassword() = runBlocking {
        Server().use { server ->
            Harness().use { harness ->
                val original = config(server)
                harness.seed(original)
                assertTrue(harness.saver.save(draft(original).copy(clearSaslPassword = true, saslPassword = "ignored")))
                assertEquals(original.copy(saslPasswordRef = null), harness.saved())
                assertNull(harness.vault.readPassword("old-secret"))
                assertEquals(listOf("old-secret"), harness.vault.storedKeys.toList())
                assertTrue(server.clients.isEmpty())
                harness.coordinator.connectNetwork(original.id)
                harness.registered(original.id)
                assertNull(server.clients.single().saslPlain())
            }
        }
    }

    @Test
    fun clearSharedSecretPreservesServerPasswordAndHiddenRegistrationFields() = runBlocking {
        Server().use { server ->
            Harness().use { harness ->
                val original = config(server, sharedPassword = true)
                harness.seed(original)
                assertTrue(harness.saver.save(draft(original).copy(clearSaslPassword = true)))
                assertEquals(original.copy(saslPasswordRef = null), harness.saved())
                assertEquals("old-password", harness.vault.readPassword("old-secret"))
                assertTrue(server.clients.isEmpty())
                harness.coordinator.connectNetwork(original.id)
                harness.registered(original.id)
                val client = server.clients.single()
                assertNull(client.saslPlain())
                assertEquals(listOf("old-password"), client.parameters("PASS"))
                assertEquals("hidden-user", client.parameters("USER")?.firstOrNull())
                assertEquals("Hidden real name", client.parameters("USER")?.lastOrNull())
            }
        }
    }

    @Test
    fun rejectedAddRollsBackStagedSecretAndDoesNotConnectOrReplaceConcurrentRecord() {
        Server().use { server ->
            Harness().use { harness ->
                val original = config(server)
                var stagedKey: String? = null
                var concurrent: NetworkConfig? = null
                val saved = harness.rejectSave(draft(original).copy(networkId = null, saslPassword = "new-password")) { key ->
                    stagedKey = key
                    assertEquals("new-password", harness.vault.readPassword(key))
                    val id = key.removePrefix("sasl-").dropLast(37)
                    concurrent = original.copy(id = id, name = "Concurrent add", saslPasswordRef = null)
                    assertTrue(harness.networks.add(assertNotNull(concurrent)))
                }
                assertFalse(saved)
                assertNull(harness.vault.readPassword(assertNotNull(stagedKey)))
                assertEquals(assertNotNull(concurrent), harness.saved())
                assertTrue(harness.coordinator.networks.value.isEmpty())
                assertTrue(server.clients.isEmpty())
            }
        }
    }

    @Test
    fun rejectedUpdateRollsBackFreshSecretWithoutOverwritingOrDeletingOldPassword() = runBlocking {
        Server().use { server ->
            Harness().use { harness ->
                val original = config(server)
                harness.seed(original)
                harness.coordinator.startAll()
                harness.registered(original.id)
                var stagedKey: String? = null
                val saved = harness.rejectSave(draft(original).copy(saslPassword = "replacement")) { key ->
                    stagedKey = key
                    assertNotEquals("old-secret", key)
                    assertEquals("replacement", harness.vault.readPassword(key))
                    assertEquals("old-password", harness.vault.readPassword("old-secret"))
                    assertEquals(original, harness.saved())
                    assertTrue(harness.networks.remove(original.id))
                }
                assertFalse(saved)
                assertNull(harness.vault.readPassword(assertNotNull(stagedKey)))
                assertEquals("old-password", harness.vault.readPassword("old-secret"))
                assertTrue(NetworkStore(harness.directory).all().isEmpty())
                assertEquals(ConnectionStatus.REGISTERED, harness.coordinator.networks.value.single().status)
                assertEquals(1, server.clients.size)
                assertEquals("\u0000account\u0000old-password", server.clients.single().saslPlain())
            }
        }
    }

    @Test
    fun missingTargetReturnsFalseBeforeVaultPersistenceOrSessionChanges() {
        Server().use { server ->
            Harness().use { harness ->
                val original = config(server)
                harness.seed(original)
                val keys = harness.vault.storedKeys.toList()
                assertFalse(harness.saver.save(draft(original).copy(networkId = "missing", saslPassword = "replacement")))
                assertFalse(harness.saver.save(draft(original).copy(networkId = "missing", clearSaslPassword = true)))
                assertEquals(original, harness.saved())
                assertEquals(keys, harness.vault.storedKeys.toList())
                assertEquals("old-password", harness.vault.readPassword("old-secret"))
                assertTrue(harness.coordinator.networks.value.isEmpty())
                assertTrue(server.clients.isEmpty())
            }
        }
    }

    @Test
    fun savesAllAuthenticationProxyIdentityAndIntentSettingsAsOneDurableConfiguration() {
        Harness().use { harness ->
            val original = offlineConfig()
            seedAllCredentials(harness, original)
            val input = draft(original).copy(
                realName = "Edited realname",
                alternateNicks = listOf("alternate", "another"),
                autoConnect = false,
                saslMode = SaslMode.SCRAM_SHA_256,
                saslPassword = "new-sasl-password",
                serverPassword = "new-server-password",
                nickServAccount = "edited-nickserv-account",
                nickServService = "AccountService",
                nickServPassword = "new-nickserv-password",
                waitForNickServ = false,
                proxyEnabled = true,
                proxyHost = "new-proxy.example",
                proxyPort = 1082,
                proxyUsername = "new-proxy-user",
                proxyPassword = "new-proxy-password",
                tlsClientAlias = "new-keychain-alias",
            )
            assertTrue(harness.saver.save(input))
            val saved = harness.saved()
            val references = passwordReferences(saved)
            assertEquals(4, references.size)
            assertEquals(4, references.distinct().size)
            assertTrue(references.none { it in passwordReferences(original) })
            assertEquals("new-sasl-password", harness.vault.readPassword(assertNotNull(saved.saslPasswordRef)))
            assertEquals("new-server-password", harness.vault.readPassword(assertNotNull(saved.serverPasswordRef)))
            assertEquals("new-nickserv-password", harness.vault.readPassword(assertNotNull(saved.nickServPasswordRef)))
            assertEquals("new-proxy-password", harness.vault.readPassword(assertNotNull(saved.proxy?.passwordRef)))
            passwordReferences(original).forEach { assertNull(harness.vault.readPassword(it)) }
            assertEquals(
                original.copy(
                    realName = "Edited realname",
                    alternateNicks = listOf("alternate", "another"),
                    autoConnect = false,
                    saslMode = SaslMode.SCRAM_SHA_256,
                    saslPasswordRef = saved.saslPasswordRef,
                    serverPasswordRef = saved.serverPasswordRef,
                    nickServAccount = "edited-nickserv-account",
                    nickServService = "AccountService",
                    nickServPasswordRef = saved.nickServPasswordRef,
                    waitForNickServ = false,
                    proxy = SocksProxyConfig("new-proxy.example", 1082, "new-proxy-user", saved.proxy?.passwordRef),
                    tlsClientAlias = "new-keychain-alias",
                ),
                saved,
            )
            assertEquals("hidden-user", saved.username)
            assertTrue(saved.userDisconnected)
            assertNull(saved.saslPassword)
            assertNull(saved.serverPassword)
            assertNull(saved.nickServPassword)
            assertNull(saved.proxyPassword)
        }
    }

    @Test
    fun unspecifiedNewFieldsPreserveHiddenIdentityUnusedSettingsAndDisconnectIntent() {
        Harness().use { harness ->
            val original = offlineConfig().copy(saslMode = SaslMode.EXTERNAL)
            seedAllCredentials(harness, original)
            val keys = harness.vault.storedKeys.toList()
            assertTrue(harness.saver.save(draft(original).copy(displayName = "Renamed")))
            assertEquals(original.copy(name = "Renamed"), harness.saved())
            assertEquals(keys, harness.vault.storedKeys.toList())
            passwordReferences(original).forEach { assertNotNull(harness.vault.readPassword(it)) }
        }
    }

    @Test
    fun explicitClearActionsRemoveEveryCredentialAndIdentityWithoutDiscardingOtherSettings() {
        Harness().use { harness ->
            val original = offlineConfig()
            seedAllCredentials(harness, original)
            assertTrue(
                harness.saver.save(
                    draft(original).copy(
                        clearSaslPassword = true,
                        clearServerPassword = true,
                        clearNickServPassword = true,
                        clearProxyPassword = true,
                        proxyEnabled = false,
                        clearTlsClientAlias = true,
                        saslPassword = "ignored",
                        serverPassword = "ignored",
                        nickServPassword = "ignored",
                        proxyPassword = "ignored",
                    ),
                ),
            )
            assertEquals(
                original.copy(
                    saslPasswordRef = null,
                    serverPasswordRef = null,
                    nickServPasswordRef = null,
                    proxy = null,
                    tlsClientAlias = null,
                ),
                harness.saved(),
            )
            passwordReferences(original).forEach { assertNull(harness.vault.readPassword(it)) }
            assertEquals(4, harness.vault.storedKeys.size)
        }
    }

    @Test
    fun replacementRetainsEveryOldReferenceSharedByAnotherNetworksDifferentCredentialRole() {
        Harness().use { harness ->
            val original = offlineConfig()
            seedAllCredentials(harness, original)
            val other = original.copy(
                id = "other",
                saslPasswordRef = original.proxy?.passwordRef,
                serverPasswordRef = original.nickServPasswordRef,
                nickServPasswordRef = original.serverPasswordRef,
                proxy = SocksProxyConfig("other-proxy.example", passwordRef = original.saslPasswordRef),
            )
            assertTrue(harness.networks.add(other))
            assertTrue(harness.saver.save(replacingAllCredentials(draft(original))))
            passwordReferences(original).forEach { assertNotNull(harness.vault.readPassword(it)) }
            assertEquals(other, NetworkStore(harness.directory).byId(other.id))
            val saved = assertNotNull(NetworkStore(harness.directory).byId(original.id))
            assertTrue(passwordReferences(saved).none { it in passwordReferences(original) })
        }
    }

    @Test
    fun failedDurableUpdateRollsBackAllFourStagedSecretsAndLeavesConfigAndOldSecretsIntact() {
        Harness().use { harness ->
            val original = offlineConfig()
            seedAllCredentials(harness, original)
            val previousKeys = harness.vault.storedKeys.toList()
            val blocker = File(harness.directory, "networks.json.tmp")
            assertTrue(blocker.mkdir())
            assertFalse(harness.saver.save(replacingAllCredentials(draft(original))))
            assertEquals(original, harness.saved())
            assertEquals(original, harness.networks.byId(original.id))
            val stagedKeys = harness.vault.storedKeys.filterNot { it in previousKeys }
            assertEquals(4, stagedKeys.size)
            stagedKeys.forEach { assertNull(harness.vault.readPassword(it)) }
            passwordReferences(original).forEach { assertNotNull(harness.vault.readPassword(it)) }
            assertTrue(blocker.delete())
            assertTrue(harness.saver.save(replacingAllCredentials(draft(original))))
            assertEquals(4, passwordReferences(harness.saved()).size)
        }
    }

    @Test
    fun partialVaultFailureRollsBackEveryAttemptedWriteIncludingTheFailingWrite() {
        Harness().use { harness ->
            val original = offlineConfig()
            seedAllCredentials(harness, original)
            val previousKeys = harness.vault.storedKeys.toList()
            harness.vault.failOnStoreNumber = previousKeys.size + 3
            assertFalse(harness.saver.save(replacingAllCredentials(draft(original))))
            val stagedKeys = harness.vault.storedKeys.filterNot { it in previousKeys }
            assertEquals(3, stagedKeys.size)
            stagedKeys.forEach { assertNull(harness.vault.readPassword(it)) }
            assertEquals(original, harness.saved())
            passwordReferences(original).forEach { assertNotNull(harness.vault.readPassword(it)) }
            harness.vault.failOnStoreNumber = null
            assertTrue(harness.saver.save(replacingAllCredentials(draft(original))))
        }
    }

    @Test
    fun disabledStartupAddPersistsWithoutAttemptingAConnection() = runBlocking {
        Server().use { server ->
            val dispatcher = StandardTestDispatcher()
            Harness(dispatcher).use { harness ->
                val input = draft(config(server)).copy(
                    networkId = null,
                    saslAuthcid = null,
                    autoConnect = false,
                    realName = "Saved realname",
                    alternateNicks = listOf("alternate"),
                    saslMode = SaslMode.AUTO,
                )
                harness.coordinator.startAll()
                assertTrue(harness.saver.save(input))
                harness.coordinator.startAll()
                harness.coordinator.onForegroundResume()
                dispatcher.scheduler.advanceTimeBy(60_000)
                dispatcher.scheduler.runCurrent()
                val saved = harness.saved()
                assertFalse(saved.autoConnect)
                assertFalse(saved.userDisconnected)
                assertEquals("Saved realname", saved.realName)
                assertEquals(listOf("alternate"), saved.alternateNicks)
                assertEquals(SaslMode.AUTO, saved.saslMode)
                assertEquals(ConnectionStatus.DISCONNECTED, harness.coordinator.networks.value.single().status)
                assertTrue(harness.attemptedConnections.isEmpty())
                assertTrue(server.clients.isEmpty())
                harness.coordinator.connectNetwork(saved.id)
                harness.registered(saved.id)
                assertEquals(1, harness.attemptedConnections.size)
                assertEquals(1, server.clients.size)
                assertEquals("Saved realname", server.clients.single().parameters("USER")?.last())
            }
        }
    }

    @Test
    fun clearingProxyPasswordKeepsProxyEndpointAndOtherCredentialRoles() {
        Harness().use { harness ->
            val original = offlineConfig()
            seedAllCredentials(harness, original)
            assertTrue(harness.saver.save(draft(original).copy(clearProxyPassword = true)))
            assertEquals(
                original.copy(proxy = assertNotNull(original.proxy).copy(passwordRef = null)),
                harness.saved(),
            )
            assertNull(harness.vault.readPassword(assertNotNull(original.proxy?.passwordRef)))
            listOfNotNull(original.saslPasswordRef, original.serverPasswordRef, original.nickServPasswordRef).forEach {
                assertNotNull(harness.vault.readPassword(it))
            }
        }
    }

    @Test
    fun explicitEmptyOptionalAccountsClearWithoutRemovingCredentialsOrHiddenUserIdentity() {
        Harness().use { harness ->
            val original = offlineConfig()
            seedAllCredentials(harness, original)
            assertTrue(harness.saver.save(draft(original).copy(nickServAccount = "", proxyUsername = "")))
            assertEquals(
                original.copy(nickServAccount = null, proxy = assertNotNull(original.proxy).copy(username = null)),
                harness.saved(),
            )
            passwordReferences(original).forEach { assertNotNull(harness.vault.readPassword(it)) }
        }
    }

    @Test
    fun rejectedAddRollsBackAllSecretRolesBeforePublishingAnyNetwork() {
        Harness().use { harness ->
            val blocker = File(harness.directory, "networks.json.tmp")
            assertTrue(blocker.mkdir())
            val input = replacingAllCredentials(draft(offlineConfig())).copy(
                networkId = null,
                autoConnect = false,
                proxyEnabled = true,
                proxyHost = "proxy.example",
                proxyPort = 1080,
            )
            assertFalse(harness.saver.save(input))
            assertEquals(4, harness.vault.storedKeys.size)
            harness.vault.storedKeys.forEach { assertNull(harness.vault.readPassword(it)) }
            assertTrue(harness.networks.all().isEmpty())
            assertTrue(NetworkStore(harness.directory).all().isEmpty())
            assertTrue(harness.coordinator.networks.value.isEmpty())
        }
    }

    @Test
    fun sojuSaveDropsHiddenServerPasswordWithoutStagingItOrDeletingSharedSaslSecret() {
        Harness().use { harness ->
            val account = offlineConfig().copy(
                mode = dev.brentdevs.yardhal.core.data.NetworkMode.SOJU,
                serverPasswordRef = "old-sasl",
            )
            seedAllCredentials(harness, account)
            val storedBefore = harness.vault.storedKeys.toList()
            assertTrue(harness.saver.save(draft(account).copy(serverPassword = "inapplicable-hidden-secret")))
            val saved = harness.saved()
            assertNull(saved.serverPasswordRef)
            assertEquals("old-sasl", saved.saslPasswordRef)
            assertNotNull(harness.vault.readPassword("old-sasl"))
            assertEquals(storedBefore, harness.vault.storedKeys.toList())
        }
    }

    @Test
    fun sojuSaveRetiresOnlyUnreferencedInapplicableServerCredentials() {
        Harness().use { harness ->
            val account = offlineConfig().copy(mode = dev.brentdevs.yardhal.core.data.NetworkMode.SOJU)
            seedAllCredentials(harness, account)
            val other = NetworkConfig(
                id = "other", name = "Other", host = "other.example", nick = "tester",
                autoConnect = false, serverPasswordRef = account.serverPasswordRef,
            )
            assertTrue(harness.networks.add(other))
            assertTrue(harness.saver.save(draft(account)))
            assertNull(harness.networks.byId(account.id)?.serverPasswordRef)
            assertEquals("old-server", harness.networks.byId(other.id)?.serverPasswordRef)
            assertNotNull(harness.vault.readPassword("old-server"))
            assertNotNull(harness.vault.readPassword("old-sasl"))
        }
    }

    private fun offlineConfig(): NetworkConfig = NetworkConfig(
        id = "network",
        name = "Saved network",
        host = "irc.example",
        nick = "tester",
        username = "hidden-user",
        realName = "Hidden realname",
        alternateNicks = listOf("saved-alternate"),
        autoConnect = false,
        userDisconnected = true,
        saslAuthcid = "account",
        saslPasswordRef = "old-sasl",
        serverPasswordRef = "old-server",
        nickServAccount = "nickserv-account",
        nickServService = "NickServ",
        nickServPasswordRef = "old-nickserv",
        waitForNickServ = true,
        proxy = SocksProxyConfig("proxy.example", 1081, "proxy-user", "old-proxy"),
        tlsClientAlias = "saved-keychain-alias",
    )

    private fun passwordReferences(config: NetworkConfig): List<String> =
        listOfNotNull(config.saslPasswordRef, config.serverPasswordRef, config.nickServPasswordRef, config.proxy?.passwordRef)

    private fun seedAllCredentials(harness: Harness, config: NetworkConfig) {
        harness.seed(config)
        listOfNotNull(config.serverPasswordRef, config.nickServPasswordRef, config.proxy?.passwordRef).forEach {
            harness.vault.storePassword(it, "old-password-for-$it")
        }
    }

    private fun replacingAllCredentials(draft: NetworkDraft): NetworkDraft = draft.copy(
        saslPassword = "replacement-sasl",
        serverPassword = "replacement-server",
        nickServPassword = "replacement-nickserv",
        proxyPassword = "replacement-proxy",
    )
}
