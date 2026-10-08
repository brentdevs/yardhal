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
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.YardhalDatabase
import dev.brentdevs.yardhal.ui.screens.NetworkDraft
import java.io.BufferedReader
import java.io.InputStreamReader
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

        override fun storePassword(key: String, password: String) {
            real.storePassword(key, password)
            storedKeys.add(key)
            stage?.block(key)
        }
    }

    private class Harness : AutoCloseable {
        val directory = Files.createTempDirectory("yardhal-network-save").toFile()
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.Default)
        private val database = YardhalDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val vault = ObservedVault()
        val networks = NetworkStore(directory)
        private val policies = InMemoryStsPolicyStore()
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
                        serverPassword = config.serverPasswordRef?.let(vault::readPassword),
                        capabilities = setOf("sasl"),
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
            runBlocking { job.cancelAndJoin() }
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
                assertTrue("USER hidden-user 0 * :Hidden real name" in client.received)
                assertTrue("PASS old-password" in client.received)
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
                assertTrue("PASS old-password" in client.received)
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
                harness.registered(original.id)
                val client = server.clients.single()
                assertNull(client.saslPlain())
                assertTrue("PASS old-password" in client.received)
                assertTrue("USER hidden-user 0 * :Hidden real name" in client.received)
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
}
