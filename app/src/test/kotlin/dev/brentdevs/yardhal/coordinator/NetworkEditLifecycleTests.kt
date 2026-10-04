package dev.brentdevs.yardhal.coordinator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.core.client.InMemoryStsPolicyStore
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.client.StsPolicy
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.InMemoryCredentialVault
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.StoredMessage
import dev.brentdevs.yardhal.core.data.YardhalDatabase
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
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
class NetworkEditLifecycleTests {
    private class Server(
        private val welcomeAutomatically: Boolean = true,
        private val publishProfiles: Boolean = false,
        private val dropOnAccept: Boolean = false,
        private val stsPort: Int? = null,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        private val executor = Executors.newCachedThreadPool()
        val clients: MutableList<Client> = CopyOnWriteArrayList()
        val port: Int get() = listener.localPort

        inner class Client(private val socket: Socket) : AutoCloseable {
            val received: MutableList<String> = CopyOnWriteArrayList()
            @Volatile var nick: String = "*"
                private set
            @Volatile var closed: Boolean = false
                private set

            fun read() {
                try {
                    if (dropOnAccept) return
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    while (!socket.isClosed) {
                        val line = reader.readLine() ?: break
                        received.add(line)
                        when {
                            line.startsWith("CAP LS") -> send(
                                ":srv CAP * LS :server-time sasl=PLAIN draft/channel-rename away-notify" +
                                    if (stsPort != null) " sts=port=$stsPort,duration=3600" else
                                        if (publishProfiles) " draft/metadata-2=max-subs=10" else "",
                            )
                            line.startsWith("CAP REQ :") -> send(":srv CAP * ACK :${line.substringAfter("CAP REQ :")}")
                            line == "AUTHENTICATE PLAIN" -> send("AUTHENTICATE +")
                            line.startsWith("AUTHENTICATE ") -> send(":srv 903 * :SASL authentication successful")
                            line.startsWith("NICK ") -> nick = line.substringAfter("NICK ")
                            line.startsWith("USER ") && welcomeAutomatically -> welcome()
                            line.startsWith("JOIN ") -> {
                                val channel = line.substringAfter("JOIN ")
                                send(":$nick!u@h JOIN $channel")
                                send(":srv 353 $nick = $channel :$nick alice")
                                send(":srv 366 $nick $channel :End of NAMES")
                                if (publishProfiles) {
                                    send(":alice!alice@old.host AWAY :Away on the old connection")
                                    send(":srv METADATA alice display-name * :Old Alice")
                                }
                            }
                        }
                    }
                } finally {
                    close()
                }
            }

            fun welcome() {
                send(":srv 001 $nick :Welcome")
                if (publishProfiles) {
                    send(":srv 005 $nick BOT=B EXTBAN=~,a draft/ICON=https://example.org/old.png :are supported")
                }
            }

            fun send(line: String) {
                synchronized(socket) {
                    socket.getOutputStream().write((line + "\r\n").toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                }
            }

            override fun close() {
                closed = true
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

    private class PausingDispatcher : CoroutineDispatcher() {
        private val lock = Any()
        private val queued = ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>>()
        private var paused = false
        val pending: Int get() = queued.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(lock) {
                if (paused) queued.add(context to block) else Dispatchers.Default.dispatch(context, block)
            }
        }

        fun pause() {
            synchronized(lock) { paused = true }
        }

        fun resume() {
            synchronized(lock) {
                paused = false
                while (true) {
                    val next = queued.poll() ?: break
                    Dispatchers.Default.dispatch(next.first, next.second)
                }
            }
        }
    }

    private class Harness(
        val directory: File,
        val config: NetworkConfig,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : AutoCloseable {
        private val scopeJob = SupervisorJob()
        private val scope = CoroutineScope(scopeJob + dispatcher)
        val database = YardhalDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val messages = MessageStore(database.messageDao())
        val vault = InMemoryCredentialVault()
        val policies = InMemoryStsPolicyStore()
        val networks = NetworkStore(directory).also { it.add(config) }
        val readMarkers = ReadMarkerStore(directory)
        val mutes = MuteStore(directory)
        val channelOrder = ChannelOrderStore(directory)
        private val connectionFactory = ConnectionFactory { updated, onStsUpgrade ->
            IrcConnection(
                IrcConnectionConfig(
                    host = updated.host,
                    port = updated.port,
                    tls = updated.tls,
                    nick = updated.nick,
                    username = updated.username,
                    realName = updated.realName,
                    saslAuthcid = updated.saslAuthcid,
                    saslPassword = updated.saslPassword,
                    serverPassword = updated.serverPasswordRef?.let(vault::readPassword),
                    capabilities = setOf("server-time", "sasl", "draft/metadata-2", "draft/channel-rename", "away-notify"),
                    connectTimeoutMillis = 1_000,
                ),
                stsPolicyStore = policies,
                onStsUpgrade = onStsUpgrade,
            )
        }
        var coordinator = LiveCoordinator(
            scope = scope,
            networkStore = networks,
            messageStore = messages,
            readMarkers = readMarkers,
            mutes = mutes,
            vault = vault,
            channelOrder = channelOrder,
            connectionFactory = connectionFactory,
            stsPolicies = policies,
        )
            private set

        fun reloadCoordinator(): LiveCoordinator {
            coordinator.disconnect(config.id)
            coordinator = LiveCoordinator(
                scope = scope,
                networkStore = NetworkStore(directory),
                messageStore = MessageStore(database.messageDao()),
                readMarkers = ReadMarkerStore(directory),
                mutes = MuteStore(directory),
                vault = vault,
                channelOrder = ChannelOrderStore(directory),
                connectionFactory = connectionFactory,
                stsPolicies = policies,
            )
            return coordinator
        }

        override fun close() {
            coordinator.disconnect(config.id)
            runBlocking { scopeJob.cancelAndJoin() }
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun config(server: Server): NetworkConfig = NetworkConfig(
        id = "network",
        name = "Original network",
        host = "127.0.0.1",
        port = server.port,
        tls = false,
        nick = "tester",
        autojoin = listOf("#room"),
    )

    private fun harness(config: NetworkConfig, dispatcher: CoroutineDispatcher = Dispatchers.Default): Harness =
        Harness(Files.createTempDirectory("yardhal-network-edit").toFile(), config, dispatcher)

    @Test
    fun nameOnlyAndNoOpEditsKeepTheRegisteredSocketAndPersistTheNewName() = runBlocking {
        Server().use { server ->
            harness(config(server)).use { harness ->
                val coordinator = harness.coordinator
                val room = ConversationRef.channel(harness.config.id, "#room")
                coordinator.startAll()
                await { coordinator.buffers.value[room.storageKey]?.joinState == JoinState.JOINED }
                val client = server.clients.single()
                val renamed = harness.config.copy(name = "Renamed network")

                assertTrue(coordinator.updateNetwork(renamed))
                assertTrue(coordinator.updateNetwork(renamed))
                assertEquals("Renamed network", coordinator.networks.value.single().name)
                assertEquals(ConnectionStatus.REGISTERED, coordinator.networks.value.single().status)
                assertEquals(renamed, NetworkStore(harness.directory).all().single())
                assertTrue(coordinator.sendText(renamed.id, room.storageKey, "still connected"))
                await { "PRIVMSG #room :still connected" in client.received }
                assertEquals(1, server.clients.size)
                assertEquals(1, client.received.count { it.startsWith("USER ") })
                assertFalse(client.closed)
                assertTrue(client.received.none { it.startsWith("QUIT ") })
            }
        }
    }

    @Test
    fun missingNetworkIdDoesNotPersistOrConnectAnotherNetwork() = runBlocking {
        Server().use { server ->
            harness(config(server)).use { harness ->
                assertFalse(harness.coordinator.updateNetwork(harness.config.copy(id = "missing"), credentialsChanged = true))
                assertEquals(listOf(harness.config), NetworkStore(harness.directory).all())
                assertTrue(harness.coordinator.networks.value.isEmpty())
                assertTrue(server.clients.isEmpty())
            }
        }
    }

    @Test
    fun endpointAndRegistrationEditsPreserveConversationStateAndRejoinOldAndNewChannels() = runBlocking {
        Server(publishProfiles = true).use { oldServer ->
            Server(welcomeAutomatically = false).use { replacement ->
                harness(config(oldServer)).use { harness ->
                    val coordinator = harness.coordinator
                    val room = ConversationRef.channel(harness.config.id, "#room")
                    val direct = ConversationRef.directMessage(harness.config.id, "alice")
                    harness.messages.record(
                        StoredMessage(
                            networkId = room.networkId,
                            conversation = room,
                            msgid = "saved-message",
                            senderNick = "alice",
                            senderUser = "u",
                            senderHost = "h",
                            kind = MessageKind.PRIVMSG,
                            text = "Persisted before editing",
                            sentByUs = false,
                            timestampMs = 1_000,
                        ),
                    )
                    coordinator.startAll()
                    await { coordinator.profiles.value[room.networkId]?.forNick("alice")?.displayName == "Old Alice" }
                    await { coordinator.networks.value.single().iconUrl != null }
                    await { coordinator.buffers.value[room.storageKey]?.memberPresence?.get("alice")?.away == true }
                    assertTrue(coordinator.loadPersistedHistory(room.storageKey))
                    await { coordinator.buffers.value[room.storageKey]?.messages?.any { it.msgid == "saved-message" } == true }
                    oldServer.clients.single().send(
                        "@msgid=live-message;time=2024-01-01T00:00:00.000Z :alice!u@h PRIVMSG #room :Live before editing",
                    )
                    await { harness.messages.recent(room, 10).size == 2 }
                    await { coordinator.buffers.value[room.storageKey]?.messages?.lastOrNull()?.storedRowId != null }
                    coordinator.ensureConversation(room.networkId, "alice")
                    coordinator.trackSelection(room.storageKey)
                    coordinator.markRead(room.storageKey)
                    coordinator.togglePin(room.storageKey)
                    coordinator.toggleMute(room.storageKey)
                    val group = coordinator.createGroup("Kept group")
                    coordinator.addToGroup(group, room.storageKey)
                    val previous = assertNotNull(coordinator.buffers.value[room.storageKey])
                    val order = coordinator.orderState.value
                    val updated = harness.config.copy(
                        name = "Edited network",
                        host = "localhost",
                        port = replacement.port,
                        nick = "editedNick",
                        username = "editedUser",
                        realName = "Edited real name",
                        autojoin = listOf("#edited"),
                    )

                    assertTrue(coordinator.updateNetwork(updated))
                    val preserved = assertNotNull(coordinator.buffers.value[room.storageKey])
                    assertEquals(previous.ref, preserved.ref)
                    assertEquals(previous.messages, preserved.messages)
                    assertEquals(previous.readAtMs, preserved.readAtMs)
                    assertEquals(previous.hasUnread, preserved.hasUnread)
                    assertTrue(preserved.members.isEmpty())
                    assertTrue(preserved.memberPresence.isEmpty())
                    assertTrue(preserved.typingUsers.isEmpty())
                    assertEquals(JoinState.JOINING, preserved.joinState)
                    assertTrue(coordinator.buffers.value.containsKey(direct.storageKey))
                    assertFalse(coordinator.loadPersistedHistory(room.storageKey))
                    assertFalse(coordinator.profiles.value.containsKey(updated.id))
                    assertEquals(null, coordinator.networks.value.single().iconUrl)
                    assertFalse(coordinator.networks.value.single().hasBotMode)
                    assertEquals("Edited network", coordinator.networks.value.single().name)
                    assertEquals("editedNick", coordinator.networks.value.single().ownNick)
                    assertEquals(order, coordinator.orderState.value)
                    assertEquals(order, ChannelOrderStore(harness.directory).snapshot())
                    assertEquals(setOf(room.storageKey), MuteStore(harness.directory).all())
                    assertEquals(previous.readAtMs, ReadMarkerStore(harness.directory).marker(room.storageKey))
                    assertEquals(updated, NetworkStore(harness.directory).all().single())
                    assertEquals(listOf("saved-message", "live-message"), harness.messages.recent(room, 10).map { it.msgid })
                    await { replacement.clients.singleOrNull()?.received?.contains("USER editedUser 0 * :Edited real name") == true }
                    val client = replacement.clients.single()
                    assertTrue("NICK editedNick" in client.received)
                    client.welcome()
                    await { "JOIN #room" in client.received && "JOIN #edited" in client.received }
                    await { coordinator.buffers.value[room.storageKey]?.joinState == JoinState.JOINED }
                    assertEquals(1, replacement.clients.size)
                    assertEquals(1, client.received.count { it == "JOIN #room" })
                    assertEquals(1, client.received.count { it == "JOIN #edited" })
                    assertTrue(oldServer.clients.single().received.none { it.startsWith("PART ") })
                    client.send(":srv RENAME #room #renamed :Rename after editing")
                    val renamedKey = ConversationRef.channel(updated.id, "#renamed").storageKey
                    await { renamedKey in coordinator.buffers.value }
                    assertEquals(renamedKey, coordinator.followRenamedSelection(room.storageKey, coordinator.buffers.value.keys))
                }
            }
        }
    }

    @Test
    fun editedNetworkAndPersistedConversationStateRestoreIntoAFreshCoordinator() = runBlocking {
        Server().use { oldServer ->
            Server().use { replacement ->
                harness(config(oldServer)).use { harness ->
                    val room = ConversationRef.channel(harness.config.id, "#room")
                    harness.messages.record(
                        StoredMessage(
                            networkId = room.networkId,
                            conversation = room,
                            msgid = "persisted-across-edit",
                            senderNick = "alice",
                            senderUser = "u",
                            senderHost = "h",
                            kind = MessageKind.PRIVMSG,
                            text = "A preserved transcript",
                            sentByUs = false,
                            timestampMs = 1_000,
                        ),
                    )
                    val original = harness.coordinator
                    original.startAll()
                    await { original.buffers.value[room.storageKey]?.members?.isNotEmpty() == true }
                    assertTrue(original.loadPersistedHistory(room.storageKey))
                    await { original.buffers.value[room.storageKey]?.messages?.singleOrNull()?.msgid == "persisted-across-edit" }
                    original.markRead(room.storageKey)
                    original.togglePin(room.storageKey)
                    original.toggleMute(room.storageKey)
                    val group = original.createGroup("Saved channels")
                    original.addToGroup(group, room.storageKey)
                    val order = original.orderState.value
                    val updated = harness.config.copy(port = replacement.port, nick = "savedNick", autojoin = listOf("#next"))
                    assertTrue(original.updateNetwork(updated))
                    await { original.networks.value.singleOrNull()?.status == ConnectionStatus.REGISTERED }

                    val reloaded = harness.reloadCoordinator()
                    assertEquals(listOf(updated), reloaded.networkStore.all())
                    reloaded.startAll()
                    await { reloaded.buffers.value[room.storageKey]?.members?.isNotEmpty() == true }
                    assertTrue(reloaded.loadPersistedHistory(room.storageKey))
                    await { reloaded.buffers.value[room.storageKey]?.messages?.singleOrNull()?.msgid == "persisted-across-edit" }
                    assertEquals(1_000L, reloaded.buffers.value[room.storageKey]?.readAtMs)
                    assertFalse(reloaded.buffers.value[room.storageKey]?.hasUnread == true)
                    assertEquals(setOf(room.storageKey), reloaded.mutedState.value)
                    assertEquals(order, reloaded.orderState.value)
                    assertEquals("savedNick", reloaded.networks.value.single().ownNick)
                    assertEquals(2, replacement.clients.size)
                    assertTrue("JOIN #room" in replacement.clients.last().received)
                    assertTrue("JOIN #next" in replacement.clients.last().received)
                }
            }
        }
    }

    @Test
    fun credentialReplacementUnderTheSameVaultReferenceReauthenticatesExactlyOnce() = runBlocking {
        Server().use { server ->
            val config = config(server).copy(saslAuthcid = "savedAccount", saslPasswordRef = "saved-password")
            harness(config).use { harness ->
                val coordinator = harness.coordinator
                harness.vault.storePassword("saved-password", "first-secret")
                coordinator.startAll()
                await { coordinator.networks.value.singleOrNull()?.status == ConnectionStatus.REGISTERED }
                val first = server.clients.single()
                assertEquals("\u0000savedAccount\u0000first-secret", credentials(first))

                harness.vault.storePassword("saved-password", "replacement-secret")
                assertTrue(coordinator.updateNetwork(config, credentialsChanged = true))
                await { server.clients.size == 2 && coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                val second = server.clients.last()
                assertEquals("\u0000savedAccount\u0000replacement-secret", credentials(second))
                assertEquals(config, NetworkStore(harness.directory).all().single())
                val newIdentity = config.copy(nick = "anotherNick", saslAuthcid = "anotherAccount")
                assertTrue(coordinator.updateNetwork(newIdentity))
                await {
                    server.clients.size == 3 && coordinator.networks.value.single().ownNick == "anotherNick" &&
                        coordinator.networks.value.single().status == ConnectionStatus.REGISTERED
                }
                assertEquals("\u0000anotherAccount\u0000replacement-secret", credentials(server.clients.last()))
                await { first.closed && second.closed }
                delay(1_300)
                assertEquals(3, server.clients.size)
                assertEquals(1, server.clients.last().received.count { it.startsWith("USER ") })
            }
        }
    }

    @Test
    fun clearingSavedPasswordRetainsAccountAndRegistersWithoutSaslAfterEditAndReload() = runBlocking {
        Server().use { server ->
            val config = config(server).copy(saslAuthcid = "savedAccount", saslPasswordRef = "saved-password")
            harness(config).use { harness ->
                val coordinator = harness.coordinator
                harness.vault.storePassword("saved-password", "first-secret")
                coordinator.startAll()
                await { coordinator.networks.value.singleOrNull()?.status == ConnectionStatus.REGISTERED }
                assertEquals("\u0000savedAccount\u0000first-secret", credentials(server.clients.single()))

                val cleared = config.copy(saslPasswordRef = null)
                assertTrue(coordinator.updateNetwork(cleared))
                harness.vault.deletePassword("saved-password")
                await { server.clients.size == 2 && coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                assertTrue(server.clients.last().received.none { it.startsWith("AUTHENTICATE ") })
                assertEquals(cleared, NetworkStore(harness.directory).all().single())

                val reloaded = harness.reloadCoordinator()
                reloaded.startAll()
                await { server.clients.size == 3 && reloaded.networks.value.singleOrNull()?.status == ConnectionStatus.REGISTERED }
                assertEquals("savedAccount", reloaded.networkStore.all().single().saslAuthcid)
                assertEquals(null, reloaded.networkStore.all().single().saslPasswordRef)
                assertTrue(server.clients.last().received.none { it.startsWith("AUTHENTICATE ") })
            }
        }
    }

    @Test
    fun changingConnectionSettingsAfterDisconnectStartsTheSavedUpdatedConnection() = runBlocking {
        Server().use { server ->
            harness(config(server)).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.networks.value.singleOrNull()?.status == ConnectionStatus.REGISTERED }
                coordinator.disconnect(harness.config.id)
                await { server.clients.single().closed }
                val renamed = harness.config.copy(name = "Offline rename")
                assertTrue(coordinator.updateNetwork(renamed))
                assertTrue(coordinator.networks.value.isEmpty())
                assertEquals(1, server.clients.size)

                val updated = renamed.copy(nick = "onlineAgain", username = "newUser", realName = "New Realname")
                assertTrue(coordinator.updateNetwork(updated))
                await { coordinator.networks.value.singleOrNull()?.ownNick == "onlineAgain" &&
                    coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                assertEquals(2, server.clients.size)
                assertTrue("USER newUser 0 * :New Realname" in server.clients.last().received)
                assertEquals(updated, NetworkStore(harness.directory).all().single())
            }
        }
    }

    @Test
    fun editingDuringBackoffCancelsRetriesAgainstTheOldEndpoint() = runBlocking {
        Server(dropOnAccept = true).use { oldServer ->
            Server().use { replacement ->
                harness(config(oldServer)).use { harness ->
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { oldServer.clients.singleOrNull()?.closed == true }
                    assertTrue(coordinator.updateNetwork(harness.config.copy(port = replacement.port, nick = "recovered")))
                    await { coordinator.networks.value.singleOrNull()?.status == ConnectionStatus.REGISTERED }
                    delay(1_300)
                    assertEquals(1, oldServer.clients.size)
                    assertEquals(1, replacement.clients.size)
                    assertTrue("NICK recovered" in replacement.clients.single().received)
                }
            }
        }
    }

    @Test
    fun queuedEventsFromTheReplacedSessionCannotRenameOrAppendToCurrentConversations() = runBlocking {
        Server().use { oldServer ->
            Server().use { replacement ->
                val dispatcher = PausingDispatcher()
                harness(config(oldServer), dispatcher).use { harness ->
                    val coordinator = harness.coordinator
                    val room = ConversationRef.channel(harness.config.id, "#room")
                    coordinator.startAll()
                    await { coordinator.buffers.value[room.storageKey]?.members?.isNotEmpty() == true }
                    coordinator.trackSelection(room.storageKey)
                    dispatcher.pause()
                    try {
                        oldServer.clients.single().send(":srv RENAME #room #stale :Queued old rename")
                        oldServer.clients.single().send("@msgid=stale :alice!u@h PRIVMSG #room :Queued old message")
                        await { dispatcher.pending > 0 }
                        val updated = harness.config.copy(port = replacement.port, autojoin = listOf("#fresh"))
                        assertTrue(coordinator.updateNetwork(updated))
                        dispatcher.resume()
                        await { coordinator.networks.value.singleOrNull()?.status == ConnectionStatus.REGISTERED }
                        await { "JOIN #fresh" in replacement.clients.single().received }
                        assertTrue(room.storageKey in coordinator.buffers.value)
                        assertFalse(ConversationRef.channel(updated.id, "#stale").storageKey in coordinator.buffers.value)
                        assertTrue(coordinator.buffers.value.values.flatMap { it.messages }.none { it.msgid == "stale" })
                        assertEquals(updated, NetworkStore(harness.directory).all().single())
                        assertTrue(harness.messages.recent(room, 10).none { it.msgid == "stale" })
                    } finally {
                        dispatcher.resume()
                    }
                }
            }
        }
    }

    @Test
    fun hostChangesDiscardOldStsUpgradePortsAndStillEnforceTheNewHostsCachedPolicy() = runBlocking {
        Server(dropOnAccept = true).use { oldTls ->
            Server(stsPort = oldTls.port).use { oldServer ->
                Server().use { replacement ->
                    Server(dropOnAccept = true).use { policyEndpoint ->
                        Server().use { unprotectedEndpoint ->
                            harness(config(oldServer)).use { harness ->
                                val coordinator = harness.coordinator
                                coordinator.startAll()
                                await { oldTls.clients.isNotEmpty() }
                                val changedHost = harness.config.copy(host = "localhost", port = replacement.port)
                                assertTrue(coordinator.updateNetwork(changedHost))
                                await { coordinator.networks.value.singleOrNull()?.status == ConnectionStatus.REGISTERED }
                                assertEquals(1, replacement.clients.size)
                                assertTrue("NICK tester" in replacement.clients.single().received)

                                harness.policies.save(
                                    "127.0.0.1",
                                    StsPolicy(policyEndpoint.port, System.currentTimeMillis() / 1000 + 3_600),
                                )
                                val protected = changedHost.copy(host = "127.0.0.1", port = unprotectedEndpoint.port)
                                assertTrue(coordinator.updateNetwork(protected))
                                await { policyEndpoint.clients.isNotEmpty() }
                                assertTrue(unprotectedEndpoint.clients.isEmpty())
                                assertEquals(protected, NetworkStore(harness.directory).all().single())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun credentials(client: Server.Client): String {
        val encoded = client.received.first { it.startsWith("AUTHENTICATE ") && it != "AUTHENTICATE PLAIN" }
            .substringAfter("AUTHENTICATE ")
        return String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }
}
