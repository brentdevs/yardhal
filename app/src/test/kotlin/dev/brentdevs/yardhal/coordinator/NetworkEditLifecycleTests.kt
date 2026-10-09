package dev.brentdevs.yardhal.coordinator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.core.client.InMemoryStsPolicyStore
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.client.StsPolicy
import dev.brentdevs.yardhal.core.client.Socks5Config
import dev.brentdevs.yardhal.core.client.TlsClientIdentity
import dev.brentdevs.yardhal.core.client.TlsIdentityUnavailableException
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.CertificatePin
import dev.brentdevs.yardhal.core.data.CredentialVault
import dev.brentdevs.yardhal.core.data.InMemoryCredentialVault
import dev.brentdevs.yardhal.core.data.MessageDao
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.SaslMode
import dev.brentdevs.yardhal.core.data.NetworkMode
import dev.brentdevs.yardhal.core.data.SojuUpstreamConfig
import dev.brentdevs.yardhal.core.data.OfflineStore
import dev.brentdevs.yardhal.core.data.OfflineConversationShell
import java.io.DataInputStream
import dev.brentdevs.yardhal.core.data.SocksProxyConfig
import dev.brentdevs.yardhal.core.data.StoredMessage
import dev.brentdevs.yardhal.core.data.YardhalDatabase
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NetworkEditLifecycleTests {
    private var scheduler: TestCoroutineScheduler? = null
    private class Server(
        private val welcomeAutomatically: Boolean = true,
        private val publishProfiles: Boolean = false,
        private val dropOnAccept: Boolean = false,
        private val stsPort: Int? = null,
        private val casemapping: String? = null,
        private val acknowledgeParts: Boolean = true,
        private val rejectSasl: Boolean = false,
        private val rejectServerPassword: Boolean = false,
        private val acknowledgeProbes: Boolean = true,
        private val historyAvailable: Boolean = false,
        private val acknowledgeHistory: Boolean = false,
        private val preNicknameAuthentication: List<String> = emptyList(),
        private val authenticatedAccount: String? = null,
        tlsFixture: String? = null,
        private val advertisedSasl: String = "PLAIN",
        requireClientIdentity: Boolean = false,
        private val bouncer: Boolean = false,
        private val rejectedBindings: Set<String> = emptySet(),
        private val additionalCapabilities: Set<String> = emptySet(),
    ) : AutoCloseable {
        private val listener = if (tlsFixture == null) {
            ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        } else {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(tlsStore(tlsFixture), tlsPassword)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(tlsStore("trusted").apply {
                    if (requireClientIdentity) setCertificateEntry("client-other", tlsStore("client-other").getCertificate("client"))
                })
            }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
            (context.serverSocketFactory.createServerSocket(0, 10, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply {
                needClientAuth = requireClientIdentity
            }
        }
        private val executor = Executors.newCachedThreadPool()
        val clients: MutableList<Client> = CopyOnWriteArrayList()
        val port: Int get() = listener.localPort
        val bouncerNetworkLines: MutableList<String> = CopyOnWriteArrayList()

        inner class Client(private val socket: Socket) : AutoCloseable {
            var peerCertificates: List<X509Certificate> = emptyList()
                private set
            @Volatile var failure: Throwable? = null
                private set
            val received: MutableList<String> = CopyOnWriteArrayList()
            @Volatile var nextSaslFailure: Int? = null
            @Volatile var nick: String = "*"
                private set
            @Volatile var closed: Boolean = false
                private set
            private var historyResponses = 0
            @Volatile var boundNetId: String? = null
                private set
            private var bindingRejected = false
            private var bouncerListings = 0

            fun read() {
                try {
                    if (dropOnAccept) return
                    if (socket is SSLSocket) {
                        val tlsSocket: SSLSocket = socket
                        socket.soTimeout = 5_000
                        socket.startHandshake()
                        peerCertificates = runCatching {
                            tlsSocket.session.peerCertificates.map { it as X509Certificate }
                        }.getOrDefault(emptyList())
                        socket.soTimeout = 0
                    }
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    while (!socket.isClosed) {
                        val line = reader.readLine() ?: break
                        received.add(line)
                        when {
                            line.startsWith("CAP LS") -> send(
                                ":srv CAP * LS :server-time sasl=$advertisedSasl draft/channel-rename away-notify" +
                                    (if (historyAvailable) " batch draft/chathistory" else "") +
                                    (if (bouncer) " batch soju.im/bouncer-networks soju.im/bouncer-networks-notify" else "") +
                                    (if (additionalCapabilities.isEmpty()) "" else " ${additionalCapabilities.joinToString(" ")}") +
                                    (if (stsPort != null) " sts=port=$stsPort,duration=3600" else
                                        if (publishProfiles) " draft/metadata-2=max-subs=10" else ""),
                            )
                            line.startsWith("CAP REQ :") -> send(":srv CAP * ACK :${line.substringAfter("CAP REQ :")}")
                            line == "AUTHENTICATE PLAIN" || line == "AUTHENTICATE EXTERNAL" -> send("AUTHENTICATE +")
                            line.startsWith("AUTHENTICATE ") -> {
                                val failure = nextSaslFailure ?: 904.takeIf { rejectSasl }
                                nextSaslFailure = null
                                if (failure == null && nick == "*") {
                                    for (response in preNicknameAuthentication) send(response)
                                }
                                send(
                                    if (failure == null) ":srv 903 * :SASL authentication successful"
                                    else ":srv $failure $nick :SASL authentication failed",
                                )
                            }
                            line.startsWith("BOUNCER BIND ") -> {
                                boundNetId = line.substringAfter("BOUNCER BIND ")
                                if (boundNetId in rejectedBindings) {
                                    bindingRejected = true
                                    send(":srv FAIL BOUNCER INVALID_NETID $boundNetId :Unknown network ID")
                                }
                            }
                            line == "BOUNCER LISTNETWORKS" -> {
                                val batch = "bouncer-${bouncerListings++}"
                                send(":srv BATCH +$batch soju.im/bouncer-networks")
                                for (network in bouncerNetworkLines) send("@batch=$batch :srv BOUNCER NETWORK $network")
                                send(":srv BATCH -$batch")
                            }
                            line.startsWith("PRIVMSG BouncerServ ") -> send(":srv 401 $nick BouncerServ :No such nick")
                            line.startsWith("PASS ") && rejectServerPassword -> send(":srv 464 * :Password incorrect")
                            line.startsWith("PING ") && acknowledgeProbes -> send(":srv PONG srv :${line.substringAfter(':')}")
                            line.startsWith("CHATHISTORY ") && acknowledgeHistory -> {
                                val message = requireNotNull(IrcMessage.parse(line))
                                val reference = "history-${historyResponses++}"
                                val type = if (message.parameters[0] == "TARGETS") "draft/chathistory-targets"
                                else "chathistory ${message.parameters[1]}"
                                send(":srv BATCH +$reference $type")
                                send(":srv BATCH -$reference")
                            }
                            line.startsWith("NICK ") -> nick = line.substringAfter("NICK ")
                            line.startsWith("USER ") && welcomeAutomatically && !bindingRejected -> welcome()
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
                            line.startsWith("PART ") && acknowledgeParts ->
                                send(":$nick!u@h PART ${line.substringAfter("PART ").substringBefore(' ')}")
                        }
                    }
                } catch (failure: Throwable) {
                    this.failure = failure
                    throw failure
                } finally {
                    close()
                }
            }

            fun parameters(command: String): List<String>? = received.firstNotNullOfOrNull {
                IrcMessage.parse(it)?.takeIf { message -> message.command == command }?.parameters
            }

            fun renameOwnNick(newNick: String) {
                val previous = nick
                nick = newNick
                send(":$previous!u@h NICK :$newNick")
            }

            fun welcome() {
                casemapping?.let { send(":srv 005 $nick CASEMAPPING=$it :are supported") }
                authenticatedAccount?.let { send(":srv 900 $nick $nick!u@h $it :You are now logged in") }
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
                    executor.submit { client.read() }
                }
            }
        }

        override fun close() {
            listener.close()
            clients.forEach { it.close() }
            executor.shutdownNow()
        }
    }

    private class Proxy(private val rejectAuthentication: Boolean = false, private val resolvedHost: String? = null) : AutoCloseable {
        private val listener = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        private val executor = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        val passwords = CopyOnWriteArrayList<String>()
        val usernames = CopyOnWriteArrayList<String>()
        val destinations = CopyOnWriteArrayList<Pair<String, Int>>()
        val port: Int get() = listener.localPort

        init {
            executor.submit {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    sockets.add(socket)
                    executor.submit { runCatching { tunnel(socket) } }
                }
            }
        }

        private fun tunnel(client: Socket) {
            client.use {
                client.soTimeout = 5_000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                check(input.readUnsignedByte() == 5)
                val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
                check(2.toByte() in methods)
                output.write(byteArrayOf(5, 2))
                output.flush()
                check(input.readUnsignedByte() == 1)
                usernames.add(String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8))
                passwords.add(String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8))
                output.write(byteArrayOf(1, (if (rejectAuthentication) 1 else 0).toByte()))
                output.flush()
                if (rejectAuthentication) return
                check(input.readUnsignedByte() == 5)
                check(input.readUnsignedByte() == 1)
                check(input.readUnsignedByte() == 0)
                check(input.readUnsignedByte() == 3)
                val host = String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8)
                val port = input.readUnsignedShort()
                destinations.add(host to port)
                Socket(resolvedHost ?: host, port).use { destination ->
                    sockets.add(destination)
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                    output.flush()
                    client.soTimeout = 0
                    val inbound = executor.submit {
                        runCatching { destination.getInputStream().copyTo(output) }
                        client.close()
                    }
                    input.copyTo(destination.getOutputStream())
                    destination.close()
                    inbound.get()
                }
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { it.close() }
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

    private class HoldingProbeDispatcher : CoroutineDispatcher() {
        private val lock = Any()
        private val queued = ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>>()
        private var captureThread: Thread? = null
        var heldJob: Job? = null
            private set
        val pending: Int get() = queued.size

        fun captureNextLaunch() {
            synchronized(lock) { captureThread = Thread.currentThread() }
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(lock) {
                if (captureThread === Thread.currentThread()) {
                    heldJob = context[Job]
                    captureThread = null
                }
                val held = heldJob
                val job = context[Job]
                if (held != null && job != null && contains(held, job)) queued.add(context to block)
                else Dispatchers.Default.dispatch(context, block)
            }
        }

        private fun contains(root: Job, expected: Job): Boolean {
            if (root === expected) return true
            for (child in root.children) if (contains(child, expected)) return true
            return false
        }

        fun startHeldLaunch() {
            synchronized(lock) {
                val initial = queued.poll() ?: error("No captured probe launch")
                Dispatchers.Default.dispatch(initial.first, initial.second)
            }
        }

        fun release() {
            synchronized(lock) {
                heldJob = null
                captureThread = null
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
        afterDiscovery: (suspend () -> Unit)? = null,
        private val identityProvider: (String) -> TlsClientIdentity = {
            throw TlsIdentityUnavailableException("Selected TLS identity is unavailable.")
        },
        val vault: CredentialVault = InMemoryCredentialVault(),
        private val beforeCreate: ((NetworkConfig) -> Unit)? = null,
        private val clock: () -> Long = System::currentTimeMillis,
        private val restoreOffline: Boolean = false,
    ) : AutoCloseable {
        private val scopeJob = SupervisorJob()
        private val scope = CoroutineScope(scopeJob + dispatcher)
        val database = YardhalDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        private val dao = database.messageDao()
        val messages = MessageStore(
            if (afterDiscovery == null) dao else object : MessageDao by dao {
                override suspend fun conversations(networkId: String): List<String> {
                    val targets = dao.conversations(networkId)
                    afterDiscovery()
                    return targets
                }
            },
        )
        val policies = InMemoryStsPolicyStore()
        val networks = NetworkStore(directory).also { it.add(config) }
        val readMarkers = ReadMarkerStore(directory)
        val mutes = MuteStore(directory)
        val channelOrder = ChannelOrderStore(directory)
        val offline = OfflineStore(database.offlineDao())
        val createdConfigs: MutableList<NetworkConfig> = CopyOnWriteArrayList()
        val stsCallbacks = CopyOnWriteArrayList<(Int) -> Unit>()
        private val connectionFactory: ConnectionFactory = ConnectionFactory { updated, onStsUpgrade ->
            createdConfigs.add(updated)
            stsCallbacks.add(onStsUpgrade)
            beforeCreate?.invoke(updated)
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
                    serverPassword = updated.serverPassword,
                    alternateNicks = updated.alternateNicks,
                    saslMode = updated.saslMode.name,
                    proxy = updated.proxy?.let { Socks5Config(it.host, it.port, it.username, updated.proxyPassword) },
                    tlsClientIdentity = updated.tlsClientIdentity,
                    trustedCertificateSha256 = updated.certificatePin?.sha256,
                    knownSecrets = updated.knownSecrets,
                    nickServService = updated.nickServService,
                    bouncerNetId = updated.bouncerBinding?.netId,
                    capabilities = setOf("server-time", "sasl", "draft/metadata-2", "draft/channel-rename", "away-notify", "batch",
                        "draft/chathistory", "soju.im/bouncer-networks", "soju.im/bouncer-networks-notify", "znc.in/playback"),
                    connectTimeoutMillis = 1_000,
                ),
                stsPolicyStore = policies,
                rawTap = { outbound, line -> coordinator.ingestRaw(updated.id, outbound, line) },
                onStsUpgrade = onStsUpgrade,
            )
        }
        var coordinator: LiveCoordinator = LiveCoordinator(
            scope = scope,
            networkStore = networks,
            messageStore = messages,
            readMarkers = readMarkers,
            historyCoverage = dev.brentdevs.yardhal.core.data.HistoryCoverageStore(directory),
            mutes = mutes,
            vault = vault,
            channelOrder = channelOrder,
            connectionFactory = connectionFactory,
            stsPolicies = policies,
            clientIdentityProvider = identityProvider,
            clock = clock,
            historyElapsedClock = { (dispatcher as? TestDispatcher)?.scheduler?.currentTime ?: System.nanoTime() / 1_000_000 },
            offlineStore = offline.takeIf { restoreOffline },
        )
            private set

        fun reloadCoordinator(): LiveCoordinator {
            val disconnected = coordinator.networkStore.byId(config.id)?.userDisconnected ?: false
            coordinator.disconnect(config.id)
            coordinator.networkStore.setUserDisconnected(config.id, disconnected)
            coordinator = LiveCoordinator(
                scope = scope,
                networkStore = NetworkStore(directory),
                messageStore = MessageStore(database.messageDao()),
                readMarkers = ReadMarkerStore(directory),
                historyCoverage = dev.brentdevs.yardhal.core.data.HistoryCoverageStore(directory),
                mutes = MuteStore(directory),
                vault = vault,
                channelOrder = ChannelOrderStore(directory),
                connectionFactory = connectionFactory,
                stsPolicies = policies,
                clientIdentityProvider = identityProvider,
                clock = clock,
                historyElapsedClock = { (scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as? TestDispatcher)?.scheduler?.currentTime ?: System.nanoTime() / 1_000_000 },
                offlineStore = offline.takeIf { restoreOffline },
            )
            return coordinator
        }

        override fun close() {
            coordinator.disconnect(config.id)
            val testDispatcher = scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as? TestDispatcher
            if (testDispatcher == null) {
                runBlocking { scopeJob.cancelAndJoin() }
            } else {
                scopeJob.cancel()
                runBlocking {
                    withTimeout(5_000) {
                        while (!scopeJob.isCompleted) {
                            testDispatcher.scheduler.runCurrent()
                            delay(10)
                        }
                    }
                }
            }
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

    private fun harness(
        config: NetworkConfig,
        dispatcher: CoroutineDispatcher? = null,
        afterDiscovery: (suspend () -> Unit)? = null,
        identityProvider: (String) -> TlsClientIdentity = {
            throw TlsIdentityUnavailableException("Selected TLS identity is unavailable.")
        },
        vault: CredentialVault = InMemoryCredentialVault(),
        beforeCreate: ((NetworkConfig) -> Unit)? = null,
        clock: () -> Long = System::currentTimeMillis,
        restoreOffline: Boolean = false,
    ): Harness {
        val effectiveDispatcher = dispatcher ?: StandardTestDispatcher()
        scheduler = (effectiveDispatcher as? TestDispatcher)?.scheduler
        return Harness(
            Files.createTempDirectory("yardhal-network-edit").toFile(), config, effectiveDispatcher,
            afterDiscovery, identityProvider, vault, beforeCreate, clock, restoreOffline,
        )
    }

    @Test
    fun sojuDiscoveryCreatesStableBoundSessionsDeduplicatesDeltasAndPrunesOnlyCompleteSnapshots() = runBlocking {
        Server(bouncer = true).use { server ->
            server.bouncerNetworkLines.addAll(listOf("1 :name=Alpha;nickname=alpha", "2 :name=Beta;nickname=beta", "1 :name=Alpha;nickname=alpha"))
            val account = config(server).copy(mode = NetworkMode.SOJU, autojoin = emptyList())
            harness(account).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.dependentNetworks(account.id).size == 2 && coordinator.networks.value.all { it.status == ConnectionStatus.REGISTERED } }
                val children = coordinator.dependentNetworks(account.id).associateBy { it.bouncerBinding?.netId }
                val alpha = requireNotNull(children["1"])
                val beta = requireNotNull(children["2"])
                assertEquals(1, harness.createdConfigs.count { it.id == alpha.id })
                assertEquals(1, harness.createdConfigs.count { it.id == beta.id })
                val discovery = server.clients.first { it.boundNetId == null }
                assertTrue(discovery.received.none { it.startsWith("JOIN ") })
                assertEquals(setOf("1", "2"), server.clients.mapNotNull { it.boundNetId }.toSet())
                discovery.send(":srv BOUNCER NETWORK 1 :name=Renamed")
                await { harness.networks.byId(alpha.id)?.name == "Renamed" }
                assertEquals("alpha", harness.networks.byId(alpha.id)?.nick)
                assertEquals(1, harness.createdConfigs.count { it.id == alpha.id })
                discovery.send(":srv BATCH +partial soju.im/bouncer-networks")
                discovery.send("@batch=partial :srv BOUNCER NETWORK 1 :name=Renamed")
                discovery.send(":srv BOUNCER NETWORK 3 :name=Delta")
                await { coordinator.dependentNetworks(account.id).size == 3 }
                assertNotNull(harness.networks.byId(beta.id))
                server.bouncerNetworkLines.clear()
                server.bouncerNetworkLines.add("1 :name=Renamed;nickname=alpha")
                discovery.send(":srv BATCH -partial")
                await { coordinator.dependentNetworks(account.id).map { it.bouncerBinding?.netId }.toSet() == setOf("1", "3") }
                assertEquals(null, harness.networks.byId(beta.id))
                discovery.send(":srv BATCH +complete soju.im/bouncer-networks")
                discovery.send("@batch=complete :srv BOUNCER NETWORK 1 :name=Renamed")
                discovery.send(":srv BATCH -complete")
                await { coordinator.dependentNetworks(account.id).size == 1 }
                assertEquals(alpha.id, coordinator.dependentNetworks(account.id).single().id)
                assertEquals(1, harness.createdConfigs.count { it.id == alpha.id })
            }
        }
    }

    @Test
    fun manuallyConnectedSojuAccountStartsNewChildrenWithoutChangingSavedAutoConnectOrExistingIntent() = runBlocking {
        Server(bouncer = true).use { server ->
            server.bouncerNetworkLines.addAll(listOf("1 :name=New Alpha", "2 :name=New Beta", "3 :name=Saved disconnected", "4 :name=Saved not wanted"))
            val account = config(server).copy(mode = NetworkMode.SOJU, autojoin = emptyList(), autoConnect = false)
            harness(account).use { harness ->
                val disconnected = SojuUpstreamConfig.reconcile(account, "3",
                    dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.Attributes(name = "Saved disconnected")).copy(userDisconnected = true)
                val notWanted = SojuUpstreamConfig.reconcile(account, "4",
                    dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.Attributes(name = "Saved not wanted"))
                assertTrue(harness.networks.upsertAll(listOf(disconnected, notWanted)))
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.restorationReady.value }
                assertTrue(harness.createdConfigs.isEmpty())
                coordinator.connectNetwork(account.id)
                await {
                    coordinator.dependentNetworks(account.id).size == 4 &&
                        coordinator.networks.value.count { it.name.startsWith("New ") && it.status == ConnectionStatus.REGISTERED } == 2
                }
                val newChildren = coordinator.dependentNetworks(account.id).filter { it.bouncerBinding?.netId in setOf("1", "2") }
                assertTrue(newChildren.all { !it.autoConnect && !it.userDisconnected })
                assertFalse(requireNotNull(NetworkStore(harness.directory).byId(account.id)).autoConnect)
                assertEquals(ConnectionStatus.DISCONNECTED, coordinator.networks.value.first { it.id == disconnected.id }.status)
                assertEquals(ConnectionStatus.DISCONNECTED, coordinator.networks.value.first { it.id == notWanted.id }.status)
                assertTrue(harness.createdConfigs.none { it.id == disconnected.id || it.id == notWanted.id })
                coordinator.disconnect(account.id)
                coordinator.connectNetwork(account.id)
                await { newChildren.all { child -> coordinator.networks.value.first { it.id == child.id }.status == ConnectionStatus.REGISTERED } }
                assertTrue(NetworkStore(harness.directory).dependents(account.id).all { !it.autoConnect })
                assertTrue(requireNotNull(harness.networks.byId(disconnected.id)).userDisconnected)
                assertTrue(harness.createdConfigs.none { it.id == disconnected.id || it.id == notWanted.id })
            }
        }
    }

    @Test
    fun incompleteNetworkFramesRetainDurableChildrenHistoryOfflineShellsPinsGroupsReadsAndMutes() = runBlocking {
        Server(bouncer = true).use { server ->
            server.bouncerNetworkLines.addAll(listOf("1 :name=Retained", "2 :name=Other"))
            val account = config(server).copy(mode = NetworkMode.SOJU, autojoin = emptyList())
            harness(account, restoreOffline = true, clock = { 1_000_000 }).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.dependentNetworks(account.id).size == 2 && coordinator.networks.value.all { it.status == ConnectionStatus.REGISTERED } }
                val child = coordinator.dependentNetworks(account.id).first { it.bouncerBinding?.netId == "1" }
                val room = ConversationRef.channel(child.id, "#retained")
                record(harness, room, "retained-anchor", "Retained history", 999_000)
                harness.offline.saveConversation(OfflineConversationShell(room, "#retained", 999_000, CaseMapping.RFC1459,
                    topic = "Retained topic", topicObservedAtMs = 999_000))
                harness.channelOrder.togglePin(room.storageKey)
                harness.channelOrder.createGroup("retained-group", "Retained group")
                harness.channelOrder.addToGroup("retained-group", room.storageKey)
                coordinator.ensureConversation(child.id, room.rawTarget)
                assertTrue(coordinator.loadPersistedHistory(room.storageKey))
                await { coordinator.buffers.value[room.storageKey]?.messages?.any { it.msgid == "retained-anchor" } == true }
                coordinator.markRead(room.storageKey)
                coordinator.toggleMute(room.storageKey)
                val marker = harness.readMarkers.marker(room.storageKey)
                val client = server.clients.first { it.boundNetId == null }
                client.send(":srv BOUNCER NETWORK 1")
                client.send(":srv BOUNCER NETWORK 1 * unexpected")
                client.send(":srv BATCH +malformed soju.im/bouncer-networks")
                client.send("@batch=malformed :srv BOUNCER NETWORK 1")
                client.send("@batch=malformed :srv BOUNCER NETWORK 2 :name=Other")
                client.send(":srv BATCH -malformed")
                client.send(":srv PONG srv :malformed-finished")
                await { coordinator.rawLog(account.id).any { "malformed-finished" in it.line } }
                assertNotNull(scheduler).runCurrent()
                assertEquals(child.id, coordinator.dependentNetworks(account.id).first { it.bouncerBinding?.netId == "1" }.id)
                assertEquals(child, NetworkStore(harness.directory).byId(child.id))
                assertEquals("Retained history", harness.messages.recent(room, 10).single().text)
                assertEquals("Retained topic", harness.offline.shell(room)?.topic)
                assertTrue(room.storageKey in ChannelOrderStore(harness.directory).snapshot().pinnedKeys)
                assertEquals("retained-group", ChannelOrderStore(harness.directory).snapshot().groupOf(room.storageKey)?.id)
                assertEquals(marker, ReadMarkerStore(harness.directory).marker(room.storageKey))
                assertTrue(MuteStore(harness.directory).isMuted(room.storageKey))
                assertEquals(ConnectionStatus.REGISTERED, coordinator.networks.value.first { it.id == child.id }.status)
            }
        }
    }

    @Test
    fun serverManagedBouncerPartAndReattachRetainConversationAndDurableIntentUntilExplicitUserPart() = runBlocking {
        for (mode in listOf(NetworkMode.SOJU, NetworkMode.ZNC, NetworkMode.DIRECT)) {
            Server(bouncer = mode == NetworkMode.SOJU,
                additionalCapabilities = if (mode == NetworkMode.DIRECT) setOf("znc.in/playback") else emptySet()).use { server ->
                server.bouncerNetworkLines.add("1 :name=Attached upstream")
                val account = config(server).copy(
                    mode = mode, autojoin = emptyList(),
                    saslAuthcid = "account".takeIf { mode == NetworkMode.ZNC },
                    zncNetwork = "network".takeIf { mode == NetworkMode.ZNC },
                    serverPasswordRef = "znc-password".takeIf { mode == NetworkMode.ZNC },
                )
                val vault = InMemoryCredentialVault().also { it.storePassword("znc-password", "znc-secret") }
                harness(account, restoreOffline = true, clock = { 1_000_000 }, vault = vault).use { harness ->
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await {
                        coordinator.networks.value.any { it.id == account.id && it.status == ConnectionStatus.REGISTERED } &&
                            coordinator.networks.value.all { it.status == ConnectionStatus.REGISTERED } &&
                            (mode != NetworkMode.SOJU || coordinator.dependentNetworks(account.id).size == 1)
                    }
                    val network = if (mode == NetworkMode.SOJU) coordinator.dependentNetworks(account.id).single() else account
                    val client = server.clients.first { it.boundNetId == network.bouncerBinding?.netId }
                    val room = ConversationRef.channel(network.id, "#attached")
                    record(harness, room, "attached-anchor", "Retained through detach", 999_000)
                    assertEquals(room.storageKey, coordinator.openChannel(network.id, room.rawTarget))
                    coordinator.loadPersistedHistory(room.storageKey)
                    await {
                        val buffer = coordinator.buffers.value[room.storageKey]
                        buffer?.joinState == JoinState.JOINED && buffer.members.size == 2 &&
                            buffer.messages.any { it.msgid == "attached-anchor" }
                    }
                    coordinator.togglePin(room.storageKey)
                    val group = coordinator.createGroup("Attached conversations")
                    coordinator.addToGroup(group, room.storageKey)
                    coordinator.toggleMute(room.storageKey)
                    coordinator.markRead(room.storageKey)
                    val marker = harness.readMarkers.marker(room.storageKey)
                    client.send("@+typing=active :alice!u@h TAGMSG ${room.rawTarget}")
                    await { coordinator.buffers.value[room.storageKey]?.typingUsers?.containsKey("alice") == true }
                    client.send(":${client.nick}!u@h PART ${room.rawTarget} :Detached by bouncer")
                    await { coordinator.buffers.value[room.storageKey]?.joinState == JoinState.IDLE }
                    val detached = requireNotNull(coordinator.buffers.value[room.storageKey])
                    assertTrue(detached.members.isEmpty())
                    assertTrue(detached.memberPresence.isEmpty())
                    assertTrue(detached.typingUsers.isEmpty())
                    assertTrue(detached.messages.any { it.msgid == "attached-anchor" })
                    assertFalse(detached.hasUnread)
                    assertFalse(ChannelOrderStore(harness.directory).isParted(room.storageKey))
                    assertTrue(room.storageKey in coordinator.orderState.value.pinnedKeys)
                    assertEquals(group, coordinator.orderState.value.groupOf(room.storageKey)?.id)
                    assertTrue(room.storageKey in coordinator.mutedState.value)
                    assertEquals(marker, ReadMarkerStore(harness.directory).marker(room.storageKey))
                    assertTrue(harness.messages.recent(room, 10).any { it.msgid == "attached-anchor" })
                    assertNotNull(harness.offline.shell(room))
                    client.send(":${client.nick}!u@h JOIN ${room.rawTarget}")
                    client.send(":srv 353 ${client.nick} = ${room.rawTarget} :${client.nick} bob")
                    client.send(":srv 366 ${client.nick} ${room.rawTarget} :End of NAMES")
                    await {
                        val buffer = coordinator.buffers.value[room.storageKey]
                        buffer?.joinState == JoinState.JOINED && buffer.members.any { it.nick == "bob" }
                    }
                    assertTrue(requireNotNull(coordinator.buffers.value[room.storageKey]).messages.any { it.msgid == "attached-anchor" })
                    assertTrue(room.storageKey in ChannelOrderStore(harness.directory).snapshot().pinnedKeys)
                    assertEquals(group, ChannelOrderStore(harness.directory).snapshot().groupOf(room.storageKey)?.id)
                    assertTrue(MuteStore(harness.directory).isMuted(room.storageKey))
                    assertEquals(marker, ReadMarkerStore(harness.directory).marker(room.storageKey))
                    assertTrue(coordinator.sendText(network.id, room.storageKey, "/part ${room.rawTarget}"))
                    await { room.storageKey !in coordinator.buffers.value }
                    assertTrue(ChannelOrderStore(harness.directory).isParted(room.storageKey))
                    client.send(":${client.nick}!u@h JOIN ${room.rawTarget}")
                    await { coordinator.buffers.value[room.storageKey]?.joinState == JoinState.JOINED }
                    assertFalse(ChannelOrderStore(harness.directory).isParted(room.storageKey))
                    client.send(":${client.nick}!u@h PART ${room.rawTarget} :Detached again")
                    await { coordinator.buffers.value[room.storageKey]?.joinState == JoinState.IDLE }
                    assertFalse(ChannelOrderStore(harness.directory).isParted(room.storageKey))
                }
            }
        }
    }

    @Test
    fun disabledUpstreamsAndAccountDisconnectKeepIndependentDesiredIntentAndSiblingTransports() = runBlocking {
        Server(bouncer = true).use { server ->
            server.bouncerNetworkLines.addAll(listOf("1 :name=Alpha", "2 :name=Beta"))
            val account = config(server).copy(mode = NetworkMode.SOJU, autojoin = emptyList())
            harness(account).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.dependentNetworks(account.id).size == 2 && coordinator.networks.value.all { it.status == ConnectionStatus.REGISTERED } }
                val alpha = coordinator.dependentNetworks(account.id).first { it.bouncerBinding?.netId == "1" }
                val beta = coordinator.dependentNetworks(account.id).first { it.bouncerBinding?.netId == "2" }
                val alphaClient = server.clients.first { it.boundNetId == "1" }
                assertTrue(coordinator.setDiscoveredUpstreamEnabled(account.id, "2", false))
                assertEquals(ConnectionStatus.DISCONNECTED, coordinator.networks.value.first { it.id == beta.id }.status)
                assertFalse(requireNotNull(NetworkStore(harness.directory).byId(beta.id)?.bouncerBinding).enabled)
                assertFalse(requireNotNull(harness.networks.byId(beta.id)).userDisconnected)
                assertEquals(ConnectionStatus.REGISTERED, coordinator.networks.value.first { it.id == alpha.id }.status)
                assertFalse(alphaClient.closed)
                assertTrue(coordinator.setDiscoveredUpstreamEnabled(account.id, "2", true))
                await { coordinator.networks.value.first { it.id == beta.id }.status == ConnectionStatus.REGISTERED }
                assertEquals(2, harness.createdConfigs.count { it.id == beta.id })
                assertEquals(1, harness.createdConfigs.count { it.id == alpha.id })
                coordinator.disconnect(beta.id)
                assertTrue(coordinator.setDiscoveredUpstreamEnabled(account.id, "2", false))
                assertTrue(coordinator.setDiscoveredUpstreamEnabled(account.id, "2", true))
                assertEquals(2, harness.createdConfigs.count { it.id == beta.id })
                coordinator.disconnect(account.id)
                assertTrue(coordinator.networks.value.all { it.status == ConnectionStatus.DISCONNECTED })
                assertFalse(requireNotNull(harness.networks.byId(alpha.id)).userDisconnected)
                assertTrue(requireNotNull(harness.networks.byId(beta.id)).userDisconnected)
                coordinator.connectNetwork(account.id)
                await { coordinator.networks.value.first { it.id == alpha.id }.status == ConnectionStatus.REGISTERED }
                assertEquals(ConnectionStatus.DISCONNECTED, coordinator.networks.value.first { it.id == beta.id }.status)
                assertEquals(2, harness.createdConfigs.count { it.id == beta.id })
            }
        }
    }

    @Test
    fun rejectedBindPersistsAnIsolatedOfflineShellAndNeverRetriesOnConnectivityOrResume() = runBlocking {
        Server(bouncer = true, rejectedBindings = setOf("2")).use { server ->
            server.bouncerNetworkLines.addAll(listOf("1 :name=Alpha", "2 :name=Rejected"))
            val account = config(server).copy(mode = NetworkMode.SOJU, autojoin = emptyList())
            harness(account).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await {
                    coordinator.dependentNetworks(account.id).size == 2 &&
                        coordinator.networks.value.any { it.name == "Rejected" && it.connectionPhase == RecoveryPhase.AUTHENTICATION_REJECTED } &&
                        coordinator.networks.value.any { it.name == "Alpha" && it.status == ConnectionStatus.REGISTERED }
                }
                val rejected = coordinator.dependentNetworks(account.id).first { it.bouncerBinding?.netId == "2" }
                assertNotNull(rejected.bouncerBinding?.rejectionReason)
                assertNotNull(coordinator.buffers.value[ConversationRef.server(rejected.id).storageKey])
                coordinator.updateConnectivity(false)
                coordinator.updateConnectivity(true, 41)
                repeat(3) { coordinator.onForegroundResume() }
                assertNotNull(scheduler).advanceTimeBy(60_000)
                assertNotNull(scheduler).runCurrent()
                await { coordinator.networks.value.any { it.name == "Alpha" && it.status == ConnectionStatus.REGISTERED } }
                assertEquals(1, harness.createdConfigs.count { it.id == rejected.id })
                assertEquals(rejected.bouncerBinding, NetworkStore(harness.directory).byId(rejected.id)?.bouncerBinding)
            }
        }
    }

    @Test
    fun offlineRestartRestoresBoundIdentityHistoryPinsGroupsReadsMutesAndCachedShellBeforeTransport() = runBlocking {
        Server(bouncer = true).use { server ->
            val account = config(server).copy(mode = NetworkMode.SOJU, autojoin = emptyList())
            harness(account, restoreOffline = true, clock = { 1_000_000 }).use { harness ->
                val child = SojuUpstreamConfig.reconcile(account, "1",
                    dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.Attributes(name = "Saved upstream"))
                assertTrue(harness.networks.add(child))
                val room = ConversationRef.channel(child.id, "#saved")
                record(harness, room, "saved-anchor", "Saved transcript", 999_000)
                harness.offline.saveConversation(OfflineConversationShell(room, "#saved", 999_000, CaseMapping.RFC1459,
                    topic = "Cached topic", topicObservedAtMs = 999_000))
                harness.channelOrder.togglePin(room.storageKey)
                harness.channelOrder.createGroup("group", "Saved group")
                harness.channelOrder.addToGroup("group", room.storageKey)
                harness.mutes.mute(room.storageKey)
                val coordinator = harness.coordinator
                coordinator.updateConnectivity(false)
                coordinator.startAll()
                await { coordinator.restorationReady.value && room.storageKey in coordinator.buffers.value }
                assertTrue(coordinator.loadPersistedHistory(room.storageKey))
                await { coordinator.buffers.value[room.storageKey]?.messages?.any { it.msgid == "saved-anchor" } == true }
                coordinator.markRead(room.storageKey)
                coordinator.trackSelection(room.storageKey)
                await { harness.offline.selection() == room }
                val marker = harness.readMarkers.marker(room.storageKey)
                val reloaded = harness.reloadCoordinator()
                reloaded.updateConnectivity(false)
                reloaded.startAll()
                await { reloaded.restorationReady.value && room.storageKey in reloaded.buffers.value }
                assertTrue(harness.createdConfigs.isEmpty())
                assertEquals(child.id, reloaded.dependentNetworks(account.id).single().id)
                assertEquals("Cached topic", reloaded.buffers.value[room.storageKey]?.topic)
                assertTrue(room.storageKey in reloaded.orderState.value.pinnedKeys)
                assertEquals("group", reloaded.orderState.value.groupOf(room.storageKey)?.id)
                assertTrue(room.storageKey in reloaded.mutedState.value)
                assertEquals(marker, ReadMarkerStore(harness.directory).marker(room.storageKey))
                await {
                    reloaded.buffers.value[room.storageKey]?.messages?.any { it.msgid == "saved-anchor" } == true &&
                        reloaded.buffers.value[room.storageKey]?.history?.initial?.status != HistoryLoadStatus.LOADING
                }
                assertFalse(reloaded.buffers.value[room.storageKey]?.hasUnread == true)
            }
        }
    }

    @Test
    fun parentSettingsAndCascadeRemovalAreAtomicAndSharedCredentialsSurviveUntilTheirLastReference() = runBlocking {
        Server(bouncer = true).use { server ->
            server.bouncerNetworkLines.addAll(listOf("1 :name=Alpha", "2 :name=Beta"))
            val vault = InMemoryCredentialVault().also { it.storePassword("shared", "account-secret") }
            val account = config(server).copy(mode = NetworkMode.SOJU, autojoin = emptyList(), saslAuthcid = "account", saslPasswordRef = "shared")
            harness(account, vault = vault).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.dependentNetworks(account.id).size == 2 && coordinator.networks.value.all { it.status == ConnectionStatus.REGISTERED } }
                val originals = harness.networks.all()
                val attempts = harness.createdConfigs.size
                withBlockedNetworkFile(harness) {
                    assertFalse(coordinator.updateNetwork(account.copy(host = "changed.example")))
                    assertFalse(coordinator.removeNetwork(account.id))
                    assertEquals(originals, harness.networks.all())
                    assertEquals(attempts, harness.createdConfigs.size)
                    assertEquals("account-secret", vault.readPassword("shared"))
                }
                assertTrue(coordinator.updateNetwork(account.copy(realName = "Updated inherited name")))
                await { coordinator.networks.value.all { it.status == ConnectionStatus.REGISTERED } }
                assertTrue(coordinator.dependentNetworks(account.id).all { it.realName == "Updated inherited name" })
                assertEquals(originals.map { it.id }.toSet(), harness.networks.all().map { it.id }.toSet())
                val unrelated = account.copy(id = "unrelated", name = "Shared credential owner", mode = NetworkMode.DIRECT, autoConnect = false)
                assertTrue(harness.networks.add(unrelated))
                assertTrue(coordinator.removeNetwork(account.id))
                await { coordinator.networks.value.map { it.id } == listOf(unrelated.id) }
                assertEquals(listOf(unrelated), NetworkStore(harness.directory).all())
                assertEquals("account-secret", vault.readPassword("shared"))
                assertTrue(coordinator.removeNetwork(unrelated.id))
                await { vault.readPassword("shared") == null }
                assertTrue(coordinator.networks.value.isEmpty())
            }
        }
    }

    @Test
    fun remotelyDisabledAndDeletedUpstreamsStopEvenWhenTheirObservedStateCannotBeSaved() = runBlocking {
        Server(bouncer = true).use { server ->
            server.bouncerNetworkLines.addAll(listOf("1 :name=Alpha", "2 :name=Beta"))
            val account = config(server).copy(mode = NetworkMode.SOJU, autojoin = emptyList())
            harness(account).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.dependentNetworks(account.id).size == 2 && coordinator.networks.value.all { it.status == ConnectionStatus.REGISTERED } }
                val alpha = coordinator.dependentNetworks(account.id).first { it.bouncerBinding?.netId == "1" }
                val beta = coordinator.dependentNetworks(account.id).first { it.bouncerBinding?.netId == "2" }
                val discovery = server.clients.first { it.boundNetId == null }
                withBlockedNetworkFile(harness) {
                    assertFalse(coordinator.setDiscoveredUpstreamEnabled(account.id, "2", false))
                    assertEquals(ConnectionStatus.DISCONNECTED, coordinator.networks.value.first { it.id == beta.id }.status)
                    assertTrue(requireNotNull(harness.networks.byId(beta.id)?.bouncerBinding).enabled)
                    discovery.send(":srv BOUNCER NETWORK 2 :state=connected")
                    discovery.send(":srv PONG srv :disabled-fenced")
                    await { coordinator.rawLog(account.id).any { "disabled-fenced" in it.line } }
                    assertNotNull(scheduler).runCurrent()
                    assertEquals(ConnectionStatus.DISCONNECTED, coordinator.networks.value.first { it.id == beta.id }.status)
                    assertEquals(1, harness.createdConfigs.count { it.id == beta.id })
                    discovery.send(":srv BOUNCER NETWORK 1 *")
                    await { coordinator.networks.value.first { it.id == alpha.id }.connectionPhase == RecoveryPhase.AUTHENTICATION_REJECTED }
                    assertNotNull(harness.networks.byId(alpha.id))
                    assertNotNull(coordinator.buffers.value[ConversationRef.server(alpha.id).storageKey])
                    assertEquals(ConnectionStatus.REGISTERED, coordinator.networks.value.first { it.id == account.id }.status)
                }
                discovery.send(":srv BOUNCER NETWORK 1 *")
                await { harness.networks.byId(alpha.id) == null }
                assertTrue(coordinator.setDiscoveredUpstreamEnabled(account.id, "2", true))
                await { coordinator.networks.value.first { it.id == beta.id }.status == ConnectionStatus.REGISTERED }
                assertEquals(2, harness.createdConfigs.count { it.id == beta.id })
            }
        }
    }

    @Test
    fun bouncerManagementAndHistoricalDiscoveryTrafficNeverCreateServiceDmsUnreadOrExtraSessions() = runBlocking {
        Server(bouncer = true).use { server ->
            server.bouncerNetworkLines.add("1 :name=Alpha")
            val account = config(server).copy(mode = NetworkMode.SOJU, autojoin = emptyList())
            harness(account).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.dependentNetworks(account.id).size == 1 && coordinator.networks.value.all { it.status == ConnectionStatus.REGISTERED } }
                val client = server.clients.first { it.boundNetId == null }
                client.send(":BouncerServ!service@bouncer PRIVMSG tester :tester management response")
                client.send(":srv BATCH +history chathistory #room")
                client.send("@batch=history :srv BATCH +old soju.im/bouncer-networks")
                client.send("@batch=old :srv BOUNCER NETWORK 99 :name=Historical")
                client.send("@batch=old :srv BOUNCER NETWORK 1 *")
                client.send(":srv BATCH -old")
                client.send(":srv BATCH -history")
                client.send("@draft/chathistory-context :srv BOUNCER NETWORK 1 *")
                client.send("@draft/chathistory-context :srv BOUNCER NETWORK 1 :name=Historical context")
                client.send("@batch=undeclared :srv BOUNCER NETWORK 1 *")
                client.send("@batch=undeclared :srv BOUNCER NETWORK 1 :name=Undeclared context")
                client.send(":srv PONG srv :history-finished")
                await { coordinator.rawLog(account.id).any { "history-finished" in it.line } }
                assertNotNull(scheduler).runCurrent()
                val managed = requireNotNull(coordinator.bouncerManagement.accounts.value[account.id])
                assertEquals(setOf("1"), managed.sojuNetworks.keys)
                assertEquals("Alpha", managed.sojuNetworks["1"]?.name)
                assertEquals(setOf("1"), coordinator.dependentNetworks(account.id).map { it.bouncerBinding?.netId }.toSet())
                assertFalse(coordinator.buffers.value.values.any { it.ref.rawTarget.equals("BouncerServ", true) })
                assertTrue(coordinator.buffers.value.values.all { !it.hasUnread })
            }
        }
        Server().use { server ->
            val account = config(server).copy(mode = NetworkMode.ZNC, autojoin = emptyList(), saslAuthcid = "account",
                zncNetwork = "network", serverPasswordRef = "znc-password")
            val vault = InMemoryCredentialVault().also { it.storePassword("znc-password", "znc-secret") }
            harness(account, vault = vault).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                val client = server.clients.single()
                assertTrue(client.received.contains("PASS :account/network:znc-secret"))
                assertTrue(client.received.none { it.startsWith("AUTHENTICATE ") })
                client.send(":*controlpanel!service@znc PRIVMSG tester :tester control response")
                client.send(":*status!service@znc NOTICE tester :tester status response")
                client.send(":srv PONG srv :znc-finished")
                await { coordinator.rawLog(account.id).any { "znc-finished" in it.line } }
                assertFalse(coordinator.buffers.value.values.any { it.ref.rawTarget in setOf("*controlpanel", "*status") })
                assertTrue(coordinator.buffers.value.values.all { !it.hasUnread })
                client.send(":srv NOTICE tester :Authentication diagnostic znc-secret")
                client.send(":srv PONG srv :diagnostic-finished")
                await { coordinator.rawLog(account.id).any { "diagnostic-finished" in it.line } }
                await { coordinator.buffers.value.values.any { buffer ->
                    buffer.messages.any { it.text == "Authentication diagnostic <redacted>" }
                } }
                assertTrue(coordinator.rawLog(account.id).none { "znc-secret" in it.line })
            }
        }
    }

    @Test
    fun zncWithoutPlaybackKeepsStoredReadingButRejectsForwardedUpstreamHistoryAvailability() = runBlocking {
        Server().use { server ->
            val account = config(server).copy(mode = NetworkMode.ZNC, autojoin = emptyList(), saslAuthcid = "account",
                zncNetwork = "network", serverPasswordRef = "znc-password")
            val vault = InMemoryCredentialVault().also { it.storePassword("znc-password", "znc-secret") }
            harness(account, vault = vault).use { harness ->
                val coordinator = harness.coordinator
                val room = ConversationRef.channel(account.id, "#cached")
                record(harness, room, "cached-anchor", "Stored before bouncer reconnect", 999_000)
                coordinator.startAll()
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                val client = server.clients.single()
                client.send(":srv 005 tester CHATHISTORY=1000 MSGREFTYPES=msgid,timestamp :Forwarded upstream support")
                client.send(":srv PONG srv :forwarded-support")
                await { coordinator.rawLog(account.id).any { "forwarded-support" in it.line } }
                assertEquals(room.storageKey, coordinator.openChannel(account.id, room.rawTarget))
                coordinator.loadPersistedHistory(room.storageKey)
                await { coordinator.buffers.value[room.storageKey]?.messages?.any { it.msgid == "cached-anchor" } == true }
                coordinator.loadOlderHistory(room.storageKey)
                await { coordinator.buffers.value[room.storageKey]?.history?.older?.status == HistoryLoadStatus.UNSUPPORTED }
                assertEquals("Stored before bouncer reconnect", harness.messages.recent(room, 10).single().text)
                assertTrue(client.received.none { it.startsWith("CHATHISTORY ") })
            }
        }
    }

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
                    oldServer.clients.single().send(":alice!u@h TOPIC #room :Topic before editing")
                    await { coordinator.buffers.value[room.storageKey]?.topic == "Topic before editing" }
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
                    assertEquals(listOf(room.storageKey), order.pinnedKeys)
                    assertEquals(listOf(room.storageKey), order.groups.single().memberKeys)
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
                    assertEquals(previous.topic, preserved.topic)
                    assertTrue(preserved.cachedTopic)
                    assertEquals(previous.members, preserved.members)
                    assertEquals(previous.memberPresence, preserved.memberPresence)
                    assertTrue(preserved.cachedRoster)
                    assertTrue(preserved.typingUsers.isEmpty())
                    assertEquals(JoinState.JOINING, preserved.joinState)
                    assertTrue(coordinator.buffers.value.containsKey(direct.storageKey))
                    assertFalse(coordinator.loadPersistedHistory(room.storageKey))
                    val cachedProfile = assertNotNull(coordinator.profiles.value[updated.id]?.forNick("alice"))
                    assertEquals("Old Alice", cachedProfile.displayName)
                    assertTrue(cachedProfile.cached)
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
                    await { coordinator.buffers.value[room.storageKey]?.cachedRoster == false }
                    assertEquals(setOf("editedNick", "alice"), coordinator.buffers.value.getValue(room.storageKey).members.map { it.nick }.toSet())
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
                assertNotNull(scheduler).advanceTimeBy(60_000)
                assertNotNull(scheduler).runCurrent()
                assertEquals(3, harness.createdConfigs.size)
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
    fun changingConnectionSettingsAfterDisconnectPreservesOptOutUntilExplicitConnect() = runBlocking {
        Server().use { server ->
            harness(config(server)).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.networks.value.singleOrNull()?.status == ConnectionStatus.REGISTERED }
                coordinator.disconnect(harness.config.id)
                await { server.clients.single().closed }
                val renamed = harness.config.copy(name = "Offline rename", userDisconnected = true)
                assertTrue(coordinator.updateNetwork(renamed))
                assertEquals(1, server.clients.size)

                val updated = renamed.copy(nick = "onlineAgain", username = "newUser", realName = "New Realname")
                assertTrue(coordinator.updateNetwork(updated))
                assertEquals(ConnectionStatus.DISCONNECTED, coordinator.networks.value.single().status)
                assertEquals(RecoveryPhase.USER_DISCONNECTED, coordinator.networks.value.single().connectionPhase)
                assertEquals(1, server.clients.size)
                assertEquals(updated, NetworkStore(harness.directory).all().single())
                coordinator.connectNetwork(updated.id)
                await { coordinator.networks.value.singleOrNull()?.ownNick == "onlineAgain" &&
                    coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                assertEquals(2, server.clients.size)
                assertTrue("USER newUser 0 * :New Realname" in server.clients.last().received)
                assertEquals(updated.copy(userDisconnected = false), NetworkStore(harness.directory).all().single())
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
                    await {
                        assertNotNull(scheduler).advanceTimeBy(50)
                        harness.createdConfigs.size >= 2
                    }
                    await { oldServer.clients.size == 2 && oldServer.clients.all { it.closed } }
                    assertEquals(2, harness.createdConfigs.size)
                    assertTrue(coordinator.updateNetwork(harness.config.copy(port = replacement.port, nick = "recovered")))
                    await { coordinator.networks.value.singleOrNull()?.status == ConnectionStatus.REGISTERED }
                    assertNotNull(scheduler).advanceTimeBy(60_000)
                    assertNotNull(scheduler).runCurrent()
                    assertEquals(3, harness.createdConfigs.size)
                    assertEquals(2, oldServer.clients.size)
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
                                await {
                                    assertNotNull(scheduler).advanceTimeBy(50)
                                    oldTls.clients.isNotEmpty()
                                }
                                val changedHost = harness.config.copy(host = "localhost", port = replacement.port)
                                assertTrue(coordinator.updateNetwork(changedHost))
                                await { coordinator.networks.value.singleOrNull()?.status == ConnectionStatus.REGISTERED }
                                assertEquals(1, replacement.clients.size)
                                await { oldServer.clients.firstOrNull()?.closed == true && oldTls.clients.all { it.closed } }
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

    @Test
    fun advertisedCasemappingMergesChannelAndDirectMessageHistoryAndMetadataAcrossEditAndReload() = runBlocking {
        Server(casemapping = "ascii").use { oldServer ->
            Server(casemapping = "rfc1459").use { replacement ->
                val config = config(oldServer).copy(autojoin = listOf("#[room]"))
                harness(config).use { harness ->
                    val coordinator = harness.coordinator
                    val room = ConversationRef.channel(config.id, "#[room]", CaseMapping.ASCII)
                    val alias = ConversationRef.channel(config.id, "#{room}", CaseMapping.ASCII)
                    val direct = ConversationRef.directMessage(config.id, "[alice]", CaseMapping.ASCII)
                    val directAlias = ConversationRef.directMessage(config.id, "{alice}", CaseMapping.ASCII)
                    record(harness, room, "room-before", "First channel", 1_000)
                    record(harness, alias, "alias-before", "Second channel", 2_000)
                    record(harness, room, null, "Same playback", 3_000)
                    record(harness, alias, null, "Same playback", 3_000)
                    record(harness, direct, "direct-before", "First direct message", 1_000)
                    record(harness, directAlias, "direct-alias-before", "Second direct message", 2_000)
                    coordinator.startAll()
                    await { coordinator.buffers.value[room.storageKey]?.joinState == JoinState.JOINED }
                    for (ref in listOf(room, alias, direct, directAlias)) {
                        coordinator.ensureConversation(config.id, ref.rawTarget)
                        assertTrue(coordinator.loadPersistedHistory(ref.storageKey))
                    }
                    await {
                        listOf(room, alias, direct, directAlias).all { ref ->
                            coordinator.buffers.value[ref.storageKey]?.messages?.isNotEmpty() == true
                        }
                    }
                    coordinator.markRead(alias.storageKey)
                    coordinator.markRead(directAlias.storageKey)
                    coordinator.toggleMute(room.storageKey)
                    coordinator.togglePin(room.storageKey)
                    coordinator.togglePin(alias.storageKey)
                    val group = coordinator.createGroup("Mapping-sensitive channels")
                    coordinator.addToGroup(group, room.storageKey)
                    val alternateGroup = coordinator.createGroup("Colliding channel group")
                    coordinator.addToGroup(alternateGroup, alias.storageKey)
                    coordinator.trackSelection(room.storageKey)
                    val reply = assertNotNull(coordinator.buffers.value[room.storageKey]).messages.first()
                    assertTrue(coordinator.sendText(config.id, room.storageKey, "Queued before CASEMAPPING"))
                    coordinator.setReplyDraft(config.id, room.storageKey, reply)
                    val edited = config.copy(port = replacement.port)
                    assertTrue(coordinator.updateNetwork(edited))
                    val mergedRoom = ConversationRef.channel(config.id, "#[room]")
                    val mergedDirect = ConversationRef.directMessage(config.id, "[alice]")
                    await { coordinator.buffers.value[mergedRoom.storageKey]?.joinState == JoinState.JOINED }
                    await {
                        val persisted = harness.messages.recent(mergedRoom, 20)
                        val ids = persisted.map { it.rowId }.toSet()
                        persisted.size == 4 && coordinator.buffers.value[mergedRoom.storageKey]?.messages
                            ?.all { it.storedRowId in ids } == true && harness.messages.recent(mergedDirect, 20).size == 2
                    }
                    val channelBuffer = assertNotNull(coordinator.buffers.value[mergedRoom.storageKey])
                    assertFalse(room.storageKey in coordinator.buffers.value)
                    assertFalse(direct.storageKey in coordinator.buffers.value)
                    assertEquals(mergedRoom.storageKey, coordinator.followRenamedSelection(room.storageKey, coordinator.buffers.value.keys))
                    val persistedIds = harness.messages.recent(mergedRoom, 20).map { it.rowId }.toSet()
                    assertTrue(channelBuffer.messages.all { it.storedRowId in persistedIds })
                    assertEquals(1, channelBuffer.messages.count { it.text == "Same playback" })
                    assertEquals(4, channelBuffer.messages.size)
                    assertEquals(3_000L, channelBuffer.readAtMs)
                    assertFalse(channelBuffer.hasUnread)
                    assertEquals(reply, channelBuffer.replyDraft)
                    assertFalse(coordinator.loadPersistedHistory(mergedRoom.storageKey))
                    assertEquals(2, coordinator.buffers.value[mergedDirect.storageKey]?.messages?.size)
                    assertEquals(2_000L, coordinator.buffers.value[mergedDirect.storageKey]?.readAtMs)
                    assertEquals(setOf(mergedRoom.storageKey), coordinator.mutedState.value)
                    assertEquals(listOf(mergedRoom.storageKey), coordinator.orderState.value.pinnedKeys)
                    assertEquals(listOf(mergedRoom.storageKey), coordinator.orderState.value.groups.first { it.id == group }.memberKeys)
                    assertTrue(coordinator.orderState.value.groups.first { it.id == alternateGroup }.memberKeys.isEmpty())
                    assertEquals(listOf(group, alternateGroup), coordinator.orderState.value.groupOrder)
                    assertEquals(listOf("Mapping-sensitive channels", "Colliding channel group"), coordinator.orderState.value.groups.map { it.name })
                    assertEquals(coordinator.orderState.value, ChannelOrderStore(harness.directory).snapshot())
                    assertEquals(3_000L, ReadMarkerStore(harness.directory).marker(mergedRoom.storageKey))
                    assertTrue(harness.messages.recent(room, 20).isEmpty())
                    assertTrue(harness.messages.recent(direct, 20).isEmpty())
                    await { oldServer.clients.single().closed }

                    val reloaded = harness.reloadCoordinator()
                    reloaded.startAll()
                    await { reloaded.buffers.value[mergedRoom.storageKey]?.joinState == JoinState.JOINED }
                    await { mergedDirect.storageKey in reloaded.buffers.value }
                    assertTrue(reloaded.loadPersistedHistory(mergedRoom.storageKey))
                    assertTrue(reloaded.loadPersistedHistory(mergedDirect.storageKey))
                    await { reloaded.buffers.value[mergedRoom.storageKey]?.messages?.size == 4 }
                    await { reloaded.buffers.value[mergedDirect.storageKey]?.messages?.size == 2 }
                    val client = replacement.clients.last()
                    client.send("@msgid=room-after :alice!u@h PRIVMSG #[room] :Channel after reload")
                    client.send("@msgid=direct-after :[alice]!u@h PRIVMSG tester :Direct after reload")
                    await { reloaded.buffers.value[mergedRoom.storageKey]?.messages?.size == 5 }
                    await { reloaded.buffers.value[mergedDirect.storageKey]?.messages?.size == 3 }
                    await { harness.messages.recent(mergedRoom, 20).size == 5 }
                    await { harness.messages.recent(mergedDirect, 20).size == 3 }
                    assertEquals(setOf(mergedRoom.storageKey), reloaded.mutedState.value)
                    assertEquals(listOf(mergedRoom.storageKey), reloaded.orderState.value.pinnedKeys)
                    assertEquals(listOf(mergedRoom.storageKey), reloaded.orderState.value.groups.first { it.id == group }.memberKeys)
                    assertTrue(reloaded.orderState.value.groups.first { it.id == alternateGroup }.memberKeys.isEmpty())
                    assertEquals(coordinator.orderState.value, reloaded.orderState.value)
                    assertEquals(3_000L, reloaded.buffers.value[mergedRoom.storageKey]?.readAtMs)
                    assertEquals(2_000L, reloaded.buffers.value[mergedDirect.storageKey]?.readAtMs)
                    assertEquals(1, reloaded.buffers.value.values.count { it.ref.rawTarget.contains("room") })
                    assertEquals(1, reloaded.buffers.value.values.count { it.ref.rawTarget.contains("alice") })
                }
            }
        }
    }

    @Test
    fun lessPermissiveAdvertisedCasemappingRekeysKnownRawTargetsWithoutStrandingHistory() = runBlocking {
        Server(casemapping = "rfc1459").use { oldServer ->
            Server(casemapping = "ascii").use { replacement ->
                val config = config(oldServer).copy(autojoin = listOf("#[room]"))
                harness(config).use { harness ->
                    val coordinator = harness.coordinator
                    val room = ConversationRef.channel(config.id, "#[room]")
                    val direct = ConversationRef.directMessage(config.id, "[alice]")
                    coordinator.startAll()
                    await { coordinator.buffers.value[room.storageKey]?.joinState == JoinState.JOINED }
                    oldServer.clients.single().send("@msgid=room-before :alice!u@h PRIVMSG #[room] :Old channel history")
                    oldServer.clients.single().send("@msgid=direct-before :[alice]!u@h PRIVMSG tester :Old direct history")
                    await { harness.messages.recent(room, 20).size == 1 && harness.messages.recent(direct, 20).size == 1 }
                    coordinator.markRead(direct.storageKey)
                    val marker = harness.readMarkers.marker(direct.storageKey)
                    coordinator.toggleMute(direct.storageKey)
                    coordinator.trackSelection(direct.storageKey)
                    assertTrue(coordinator.loadPersistedHistory(room.storageKey))
                    assertTrue(coordinator.loadPersistedHistory(direct.storageKey))
                    assertTrue(coordinator.updateNetwork(config.copy(port = replacement.port)))
                    val changedRoom = ConversationRef.channel(config.id, "#[room]", CaseMapping.ASCII)
                    val changedDirect = ConversationRef.directMessage(config.id, "[alice]", CaseMapping.ASCII)
                    await { coordinator.buffers.value[changedRoom.storageKey]?.joinState == JoinState.JOINED }
                    await { harness.messages.recent(changedDirect, 20).size == 1 }
                    assertTrue(harness.messages.recent(room, 20).isEmpty())
                    assertTrue(harness.messages.recent(direct, 20).isEmpty())
                    assertFalse(coordinator.loadPersistedHistory(changedRoom.storageKey))
                    assertFalse(coordinator.loadPersistedHistory(changedDirect.storageKey))
                    assertEquals(changedDirect.storageKey, coordinator.followRenamedSelection(direct.storageKey, coordinator.buffers.value.keys))
                    assertEquals(setOf(changedDirect.storageKey), coordinator.mutedState.value)
                    val reloaded = harness.reloadCoordinator()
                    reloaded.startAll()
                    await { reloaded.buffers.value[changedRoom.storageKey]?.joinState == JoinState.JOINED }
                    await { changedDirect.storageKey in reloaded.buffers.value }
                    assertTrue(reloaded.loadPersistedHistory(changedRoom.storageKey))
                    assertTrue(reloaded.loadPersistedHistory(changedDirect.storageKey))
                    await { reloaded.buffers.value[changedRoom.storageKey]?.messages?.singleOrNull()?.msgid == "room-before" }
                    await { reloaded.buffers.value[changedDirect.storageKey]?.messages?.singleOrNull()?.msgid == "direct-before" }
                    assertEquals(marker, reloaded.buffers.value[changedDirect.storageKey]?.readAtMs)
                    assertEquals(setOf(changedDirect.storageKey), reloaded.mutedState.value)
                    replacement.clients.last().send("@msgid=direct-after :[alice]!u@h PRIVMSG tester :New direct history")
                    await { harness.messages.recent(changedDirect, 20).size == 2 }
                    assertEquals(2, reloaded.buffers.value[changedDirect.storageKey]?.messages?.size)
                    assertFalse(reloaded.buffers.value[changedDirect.storageKey]?.hasUnread == true)
                    assertFalse(direct.storageKey in reloaded.buffers.value)
                }
            }
        }
    }

    @Test
    fun historyDiscoveredBeforeMappingChangeCannotReopenTheOldNormalizedTarget() = runBlocking {
        Server(casemapping = "rfc1459").use { oldServer ->
            Server(casemapping = "ascii").use { replacement ->
                val config = config(oldServer).copy(autojoin = listOf("#[room]"))
                var holdDiscovery = false
                val discovered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                harness(config, afterDiscovery = {
                    if (holdDiscovery) {
                        discovered.complete(Unit)
                        release.await()
                    }
                }).use { harness ->
                    val coordinator = harness.coordinator
                    val original = ConversationRef.channel(config.id, "#[room]")
                    val updated = ConversationRef.channel(config.id, "#[room]", CaseMapping.ASCII)
                    coordinator.startAll()
                    await { coordinator.buffers.value[original.storageKey]?.joinState == JoinState.JOINED }
                    record(harness, original, "held-history", "Retained history", 1_000)
                    holdDiscovery = true
                    try {
                        assertTrue(coordinator.updateNetwork(config.copy(port = replacement.port)))
                        await { discovered.isCompleted &&
                            coordinator.buffers.value[updated.storageKey]?.joinState == JoinState.JOINED }
                        holdDiscovery = false
                        release.complete(Unit)
                        assertNotNull(scheduler).runCurrent()
                        await { harness.messages.recent(updated, 20).any { it.msgid == "held-history" } }
                        assertTrue(harness.messages.recent(original, 20).isEmpty())
                        assertEquals(
                            setOf(updated.storageKey),
                            coordinator.buffers.value.values.filter { it.ref.kind ==
                                dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL }.map { it.key }.toSet(),
                        )
                        assertTrue(replacement.clients.single().received.none { it == "JOIN #{room}" })
                    } finally {
                        holdDiscovery = false
                        release.complete(Unit)
                    }
                }
            }
        }
    }

    @Test
    fun pendingAndAcknowledgedPartsStayPartedAcrossEditUntilDeliberatelyReaddedToAutojoin() = runBlocking {
        for (acknowledgeParts in listOf(false, true)) {
            Server(acknowledgeParts = acknowledgeParts).use { oldServer ->
                Server().use { replacement ->
                    harness(config(oldServer)).use { harness ->
                        val coordinator = harness.coordinator
                        val room = ConversationRef.channel(harness.config.id, "#room")
                        coordinator.startAll()
                        await { coordinator.buffers.value[room.storageKey]?.joinState == JoinState.JOINED }
                        assertTrue(coordinator.sendText(room.networkId, room.storageKey, "/part"))
                        await { "PART #room" in oldServer.clients.single().received }
                        if (acknowledgeParts) await { room.storageKey !in coordinator.buffers.value }
                        coordinator.ensureConversation(room.networkId, "#room")
                        val edited = harness.config.copy(port = replacement.port)
                        assertTrue(coordinator.updateNetwork(edited))
                        await { coordinator.networks.value.singleOrNull()?.status == ConnectionStatus.REGISTERED }
                        assertTrue(coordinator.sendText(room.networkId, room.storageKey, "/raw PING :part-barrier"))
                        await { "PING :part-barrier" in replacement.clients.single().received }
                        assertTrue(replacement.clients.single().received.none { it == "JOIN #room" })
                        assertEquals(JoinState.IDLE, coordinator.buffers.value[room.storageKey]?.joinState)
                        assertTrue(coordinator.orderState.value.isParted(room.storageKey))
                        assertTrue(ChannelOrderStore(harness.directory).isParted(room.storageKey))
                        assertTrue(coordinator.updateNetwork(edited.copy(autojoin = emptyList())))
                        await { replacement.clients.size == 2 && coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                        assertTrue(coordinator.updateNetwork(edited))
                        await { replacement.clients.size == 3 && coordinator.buffers.value[room.storageKey]?.joinState == JoinState.JOINED }
                        assertTrue("JOIN #room" in replacement.clients.last().received)
                        assertFalse(coordinator.orderState.value.isParted(room.storageKey))
                    }
                }
            }
        }
    }

    @Test
    fun disabledColdStartAndDisconnectedEditsNeverOpenASocketUntilManualConnect() = runBlocking {
        Server().use { server ->
            harness(config(server).copy(autoConnect = false)).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.restorationReady.value }
                assertEquals(RecoveryPhase.DISCONNECTED, coordinator.networks.value.single().connectionPhase)
                val room = ConversationRef.channel(harness.config.id, "#room")
                assertEquals(JoinState.IDLE, coordinator.buffers.value[room.storageKey]?.joinState)
                val edited = harness.config.copy(nick = "manualNick", serverPasswordRef = "pass")
                harness.vault.storePassword("pass", "saved-pass")
                assertTrue(coordinator.updateNetwork(edited, credentialsChanged = true))
                assertNotNull(scheduler).advanceTimeBy(60_000)
                assertNotNull(scheduler).runCurrent()
                assertTrue(harness.createdConfigs.isEmpty())
                assertTrue(server.clients.isEmpty())
                coordinator.connectNetwork(edited.id)
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                await { coordinator.buffers.value[room.storageKey]?.joinState == JoinState.JOINED }
                assertFalse(NetworkStore(harness.directory).all().single().autoConnect)
                assertFalse(NetworkStore(harness.directory).all().single().userDisconnected)
                assertEquals(listOf("saved-pass"), server.clients.single().parameters("PASS"))
                val reloaded = harness.reloadCoordinator()
                reloaded.startAll()
                await { reloaded.restorationReady.value }
                assertNotNull(scheduler).advanceTimeBy(60_000)
                assertNotNull(scheduler).runCurrent()
                assertEquals(RecoveryPhase.DISCONNECTED, reloaded.networks.value.single().connectionPhase)
                assertEquals(JoinState.IDLE, reloaded.buffers.value[room.storageKey]?.joinState)
                assertEquals(1, server.clients.size)
            }
        }
    }

    @Test
    fun disconnectWithoutASessionSurvivesColdStartAndStaleEditorSettings() = runBlocking {
        Server().use { server ->
            harness(config(server)).use { harness ->
                harness.coordinator.disconnect(harness.config.id)
                assertTrue(NetworkStore(harness.directory).all().single().userDisconnected)
                assertTrue(harness.coordinator.updateNetwork(harness.config.copy(nick = "savedOffline")))
                assertTrue(NetworkStore(harness.directory).all().single().userDisconnected)
                val reloaded = harness.reloadCoordinator()
                reloaded.startAll()
                await { reloaded.restorationReady.value }
                reloaded.updateConnectivity(true, 2)
                reloaded.onForegroundResume()
                assertNotNull(scheduler).advanceTimeBy(60_000)
                assertNotNull(scheduler).runCurrent()
                assertEquals(RecoveryPhase.USER_DISCONNECTED, reloaded.networks.value.single().connectionPhase)
                assertTrue(server.clients.isEmpty())
                val room = ConversationRef.channel(harness.config.id, "#room")
                assertEquals(JoinState.IDLE, reloaded.buffers.value[room.storageKey]?.joinState)
                reloaded.connectNetwork(harness.config.id)
                await { reloaded.networks.value.single().status == ConnectionStatus.REGISTERED }
                await { reloaded.buffers.value[room.storageKey]?.joinState == JoinState.JOINED }
                assertTrue("NICK savedOffline" in server.clients.single().received)
                assertFalse(NetworkStore(harness.directory).all().single().userDisconnected)
            }
        }
    }

    @Test
    fun offlineStartupAndLostRoutesPauseSocketsAndOnlineWakeIsImmediate() = runBlocking {
        Server().use { server ->
            harness(config(server)).use { harness ->
                val coordinator = harness.coordinator
                coordinator.updateConnectivity(false)
                coordinator.startAll()
                await { coordinator.restorationReady.value }
                assertNotNull(scheduler).advanceTimeBy(60_000)
                assertNotNull(scheduler).runCurrent()
                assertEquals(RecoveryPhase.OFFLINE, coordinator.networks.value.single().connectionPhase)
                assertTrue(harness.createdConfigs.isEmpty())
                assertTrue(server.clients.isEmpty())
                coordinator.updateConnectivity(true, 1)
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                val first = server.clients.single()
                coordinator.updateConnectivity(false)
                await { first.closed }
                val count = harness.createdConfigs.size
                coordinator.onForegroundResume()
                assertNotNull(scheduler).advanceTimeBy(60_000)
                assertNotNull(scheduler).runCurrent()
                assertEquals(count, harness.createdConfigs.size)
                assertEquals(RecoveryPhase.OFFLINE, coordinator.networks.value.single().connectionPhase)
                coordinator.updateConnectivity(true, 2)
                await { server.clients.size == 2 && coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                assertEquals(2, harness.createdConfigs.size)
            }
        }
    }

    @Test
    fun foregroundAndRouteProbesCoalesceIgnoreUnrelatedPongsAndRearmAfterSuccess() = runBlocking {
        Server(acknowledgeProbes = false).use { server ->
            harness(config(server)).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                val client = server.clients.single()
                coordinator.onForegroundResume()
                coordinator.onForegroundResume()
                coordinator.updateConnectivity(true, 3)
                coordinator.updateConnectivity(true, 3)
                await { client.received.count { it.startsWith("PING ") } == 1 }
                client.send(":srv PONG srv :unrelated")
                client.send(":srv 372 tester :probe-still-pending")
                await { coordinator.buffers.value[ConversationRef.server(harness.config.id).storageKey]?.messages?.any { it.text == "probe-still-pending" } == true }
                coordinator.onForegroundResume()
                assertNotNull(scheduler).runCurrent()
                assertTrue(coordinator.sendText(harness.config.id, "network|#room", "probe remains pending"))
                await { "PRIVMSG #room :probe remains pending" in client.received }
                assertEquals(1, client.received.count { it.startsWith("PING ") })
                assertEquals(1, server.clients.size)
                val token = client.received.single { it.startsWith("PING ") }.substringAfter(':')
                client.send(":srv PONG srv :$token")
                client.send(":srv 372 tester :probe-complete")
                await { coordinator.buffers.value[ConversationRef.server(harness.config.id).storageKey]?.messages?.any { it.text == "probe-complete" } == true }
                coordinator.onForegroundResume()
                await { client.received.count { it.startsWith("PING ") } == 2 }
                assertEquals(1, server.clients.size)
            }
        }
    }

    @Test
    fun staleFailedProbeCannotCloseTheCredentialReplacementSocket() = runBlocking {
        Server(acknowledgeProbes = false).use { oldServer ->
            Server().use { replacement ->
                harness(config(oldServer)).use { harness ->
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                    coordinator.onForegroundResume()
                    await { oldServer.clients.single().received.any { it.startsWith("PING ") } }
                    harness.vault.storePassword("replacement-pass", "new-password")
                    assertTrue(coordinator.updateNetwork(harness.config.copy(
                        port = replacement.port, serverPasswordRef = "replacement-pass",
                    ), credentialsChanged = true))
                    await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                    assertNotNull(scheduler).advanceTimeBy(5_001)
                    assertNotNull(scheduler).runCurrent()
                    val active = replacement.clients.single()
                    assertFalse(active.closed)
                    assertEquals(2, harness.createdConfigs.size)
                    assertEquals(listOf("new-password"), active.parameters("PASS"))
                    assertTrue(coordinator.sendText(harness.config.id, "network|#room", "new socket survived"))
                    await { "PRIVMSG #room :new socket survived" in active.received }
                }
            }
        }
    }

    @Test
    fun failedCurrentProbeRestartsOnceAndPreservesTheStoredTranscriptAnchor() = runBlocking {
        Server(acknowledgeProbes = false).use { server ->
            harness(config(server)).use { harness ->
                val coordinator = harness.coordinator
                val room = ConversationRef.channel(harness.config.id, "#room")
                record(harness, room, "phase1-anchor", "Saved before the route changed", 1_000)
                coordinator.ensureConversation(room.networkId, room.rawTarget)
                assertTrue(coordinator.loadPersistedHistory(room.storageKey))
                await { coordinator.buffers.value[room.storageKey]?.messages?.singleOrNull()?.msgid == "phase1-anchor" }
                val identity = assertNotNull(coordinator.buffers.value[room.storageKey]).messages.single().localId
                coordinator.startAll()
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                coordinator.onForegroundResume()
                await { server.clients.single().received.any { it.startsWith("PING ") } }
                assertNotNull(scheduler).advanceTimeBy(5_001)
                await { server.clients.size == 2 && coordinator.networks.value.single().status == ConnectionStatus.REGISTERED &&
                    "JOIN #room" in server.clients.last().received }
                assertEquals(identity, coordinator.buffers.value[room.storageKey]?.messages?.single()?.localId)
                assertEquals("phase1-anchor", harness.messages.recent(room, 10).single().msgid)
                assertEquals(2, harness.createdConfigs.size)
                assertEquals(1, server.clients.last().received.count { it == "JOIN #room" })
            }
        }
    }

    @Test
    fun saslAndServerPasswordRejectionsBlockAutjoinsHistoryAndRecoveryStorms() = runBlocking {
        for (sasl in listOf(true, false)) {
            Server(welcomeAutomatically = sasl, rejectSasl = sasl, rejectServerPassword = !sasl, historyAvailable = true).use { rejected ->
                Server().use { corrected ->
                    val config = if (sasl) config(rejected).copy(saslAuthcid = "account", saslPasswordRef = "auth")
                    else config(rejected).copy(serverPasswordRef = "auth")
                    harness(config).use { harness ->
                        val coordinator = harness.coordinator
                        harness.vault.storePassword("auth", "not-accepted")
                        coordinator.startAll()
                        await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.AUTHENTICATION_REJECTED }
                        assertNotNull(coordinator.networks.value.single().connectionError)
                        assertEquals(ConnectionStatus.DISCONNECTED, coordinator.networks.value.single().status)
                        assertTrue(rejected.clients.single().received.none { it.startsWith("JOIN ") || "CHATHISTORY" in it })
                        coordinator.onForegroundResume()
                        coordinator.updateConnectivity(false)
                        coordinator.updateConnectivity(true, 5)
                        assertNotNull(scheduler).advanceTimeBy(60_000)
                        assertNotNull(scheduler).runCurrent()
                        assertEquals(1, harness.createdConfigs.size)
                        assertTrue(coordinator.updateNetwork(config.copy(port = corrected.port)))
                        await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                        assertEquals(1, corrected.clients.size)
                        assertEquals(2, harness.createdConfigs.size)
                        assertEquals(null, coordinator.networks.value.single().connectionError)
                    }
                }
            }
        }
    }

    @Test
    fun everyReferencedSecretFailsClosedBeforeAnySocketIsCreated() = runBlocking {
        Server().use { server ->
            val base = config(server)
            val missing = listOf(
                base.copy(saslAuthcid = "account", saslPasswordRef = "missing"),
                base.copy(serverPasswordRef = "missing"),
                base.copy(nickServAccount = "account", nickServPasswordRef = "missing"),
                base.copy(proxy = SocksProxyConfig("127.0.0.1", server.port, "proxyUser", "missing")),
            )
            for (config in missing) {
                harness(config).use { harness ->
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.AUTHENTICATION_REJECTED }
                    assertTrue(assertNotNull(coordinator.networks.value.single().connectionError).contains("password"))
                    coordinator.onForegroundResume()
                    coordinator.updateConnectivity(true, 7)
                    assertNotNull(scheduler).advanceTimeBy(60_000)
                    assertNotNull(scheduler).runCurrent()
                    assertTrue(harness.createdConfigs.isEmpty())
                    assertTrue(server.clients.isEmpty())
                }
            }
        }
    }

    @Test
    fun knownSaslAccountSkipsNickServOnlyWhenItMatchesTheIntendedIdentity() = runBlocking {
        for ((intended, authenticated) in listOf("account" to "ACCOUNT", null to "tester")) {
            Server(authenticatedAccount = authenticated).use { server ->
                val config = config(server).copy(
                    saslAuthcid = authenticated, saslPasswordRef = "sasl",
                    nickServAccount = intended, nickServPasswordRef = "nickserv",
                )
                harness(config).use { harness ->
                    harness.vault.storePassword("sasl", "sasl-secret")
                    harness.vault.storePassword("nickserv", "nickserv-secret")
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED &&
                        coordinator.buffers.value["network|#room"]?.joinState == JoinState.JOINED }
                    val client = server.clients.single()
                    assertEquals("\u0000$authenticated\u0000sasl-secret", credentials(client))
                    assertTrue(client.received.none { it.startsWith("PRIVMSG NickServ :IDENTIFY") })
                    assertNotNull(scheduler).advanceTimeBy(7_001)
                    assertNotNull(scheduler).runCurrent()
                    assertEquals(RecoveryPhase.REGISTERED, coordinator.networks.value.single().connectionPhase)
                    assertEquals(null, coordinator.networks.value.single().connectionError)
                    assertFalse(client.closed)
                    assertEquals(1, client.received.count { it == "JOIN #room" })
                }
            }
        }
    }

    @Test
    fun preNicknameSaslAccountMatchesIntendedIdentityWithoutSendingIncorrectNickServPassword() = runBlocking {
        for ((intended, authenticated) in listOf("account" to "ACCOUNT", null to "tester")) {
            Server(preNicknameAuthentication = listOf(
                ":srv 900 * * $authenticated :You are now logged in as $authenticated",
            )).use { server ->
                val config = config(server).copy(
                    saslAuthcid = authenticated, saslPasswordRef = "sasl",
                    nickServAccount = intended, nickServPasswordRef = "nickserv",
                )
                harness(config).use { harness ->
                    harness.vault.storePassword("sasl", "sasl-secret")
                    harness.vault.storePassword("nickserv", "deliberately-incorrect-nickserv-password")
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { coordinator.buffers.value["network|#room"]?.joinState == JoinState.JOINED }
                    val client = server.clients.single()
                    assertEquals("\u0000$authenticated\u0000sasl-secret", credentials(client))
                    assertTrue(client.received.none { it.startsWith("PRIVMSG NickServ :IDENTIFY") })
                    assertNotNull(scheduler).advanceTimeBy(7_001)
                    assertNotNull(scheduler).runCurrent()
                    assertEquals(RecoveryPhase.REGISTERED, coordinator.networks.value.single().connectionPhase)
                    assertEquals(null, coordinator.networks.value.single().connectionError)
                    assertFalse(client.closed)
                    assertEquals(1, client.received.count { it == "JOIN #room" })
                    assertTrue(coordinator.sendText(config.id, "network|#room", "authenticated before nickname"))
                    await { "PRIVMSG #room :authenticated before nickname" in client.received }
                }
            }
        }
    }

    @Test
    fun preNicknameForeignHistoricalAndLoggedOutAccountsCannotSkipIdentification() = runBlocking {
        for (responses in listOf(
            listOf(":srv 900 * * account :Logged in", ":srv 901 * * account :Logged out"),
            listOf(":srv 900 * * account :Logged in", ":*!u@h ACCOUNT *"),
            listOf(":srv 900 * * someone-else :Logged in"),
            listOf(":attacker!u@h 900 * * account :Logged in"),
            listOf("@batch=history :srv 900 * * account :Logged in"),
            listOf(":srv 900 * someone-else!u@h account :Logged in"),
            listOf(":srv 900 someone-else * account :Logged in"),
        )) {
            Server(preNicknameAuthentication = responses).use { server ->
                val config = config(server).copy(
                    saslAuthcid = "account", saslPasswordRef = "sasl",
                    nickServAccount = "account", nickServPasswordRef = "nickserv",
                )
                harness(config).use { harness ->
                    harness.vault.storePassword("sasl", "sasl-secret")
                    harness.vault.storePassword("nickserv", "nickserv-secret")
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { server.clients.singleOrNull()?.received?.any { it.startsWith("PRIVMSG NickServ :IDENTIFY account ") } == true }
                    val client = server.clients.single()
                    client.send(":srv 900 * * account :Late unnamed identity")
                    client.send(":srv 372 tester :identity remains pending")
                    await { coordinator.buffers.value[ConversationRef.server(config.id).storageKey]?.messages?.any {
                        it.text == "identity remains pending"
                    } == true }
                    assertEquals(RecoveryPhase.IDENTIFYING, coordinator.networks.value.single().connectionPhase)
                    assertTrue(client.received.none { it.startsWith("JOIN ") })
                    client.send(":NickServ!s@h NOTICE tester :You're now logged in as account.")
                    await { coordinator.buffers.value["network|#room"]?.joinState == JoinState.JOINED }
                    assertEquals(1, client.received.count { it == "JOIN #room" })
                }
            }
        }
    }

    @Test
    fun unknownOrDifferentSaslAccountStillUsesTheBoundedNickServFallback() = runBlocking {
        for (authenticated in listOf(null, "someone-else")) {
            Server(authenticatedAccount = authenticated).use { server ->
                val config = config(server).copy(
                    saslAuthcid = "sasl-account", saslPasswordRef = "sasl",
                    nickServAccount = "account", nickServPasswordRef = "nickserv",
                )
                harness(config).use { harness ->
                    harness.vault.storePassword("sasl", "sasl-secret")
                    harness.vault.storePassword("nickserv", "nickserv-secret")
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { server.clients.singleOrNull()?.received?.any { it.startsWith("PRIVMSG NickServ :IDENTIFY account ") } == true }
                    val client = server.clients.single()
                    assertEquals(RecoveryPhase.IDENTIFYING, coordinator.networks.value.single().connectionPhase)
                    assertTrue(client.received.none { it.startsWith("JOIN ") })
                    assertNotNull(scheduler).advanceTimeBy(7_001)
                    await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.AUTHENTICATION_REJECTED }
                    assertTrue(assertNotNull(coordinator.networks.value.single().connectionError).contains("seven seconds"))
                    assertTrue(client.received.none { it.startsWith("JOIN ") })
                }
            }
        }
    }

    @Test
    fun loggedOutForeignAndHistoricalAccountsCannotBypassTheNickServGate() = runBlocking {
        for (lines in listOf(
            listOf(":srv 900 tester tester!u@h account :Logged in", ":srv 901 tester tester!u@h account :Logged out"),
            listOf(":srv 900 tester tester!u@h account :Logged in", ":tester!u@h ACCOUNT *"),
            listOf(":srv 900 someone-else someone-else!u@h account :Logged in"),
            listOf(":attacker!u@h 900 tester tester!u@h account :Logged in"),
            listOf("@batch=history :srv 900 tester tester!u@h account :Logged in"),
        )) {
            Server(welcomeAutomatically = false).use { server ->
                val config = config(server).copy(
                    saslAuthcid = "account", saslPasswordRef = "sasl",
                    nickServAccount = "account", nickServPasswordRef = "nickserv",
                )
                harness(config).use { harness ->
                    harness.vault.storePassword("sasl", "sasl-secret")
                    harness.vault.storePassword("nickserv", "nickserv-secret")
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { server.clients.singleOrNull()?.received?.any { it.startsWith("USER ") } == true }
                    val client = server.clients.single()
                    for (line in lines) client.send(line)
                    client.welcome()
                    await { client.received.any { it.startsWith("PRIVMSG NickServ :IDENTIFY account ") } }
                    assertEquals(RecoveryPhase.IDENTIFYING, coordinator.networks.value.single().connectionPhase)
                    assertTrue(client.received.none { it.startsWith("JOIN ") })
                    client.send(":NickServ!s@h NOTICE tester :You're now logged in as account.")
                    await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                }
            }
        }
    }

    @Test
    fun validWillAndNotMeAccountNoticesReleaseJoinsWithoutAcceptingSpoofedOrNegativeConfirmations() = runBlocking {
        for (account in listOf("will", "not-me")) {
            Server().use { server ->
                val config = config(server).copy(nickServAccount = account, nickServPasswordRef = "nickserv")
                harness(config).use { harness ->
                    harness.vault.storePassword("nickserv", "nickserv-secret")
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { server.clients.singleOrNull()?.received?.any { it.startsWith("PRIVMSG NickServ :IDENTIFY") } == true }
                    val client = server.clients.single()
                    client.send(":EvilNickServ!s@h NOTICE tester :You're now logged in as $account.")
                    client.send(":NickServ!s@h NOTICE tester :You're now logged in as $account, but authentication failed.")
                    client.send(":srv 372 tester :still waiting for account")
                    await { coordinator.buffers.value[ConversationRef.server(config.id).storageKey]?.messages?.any { it.text == "still waiting for account" } == true }
                    assertEquals(RecoveryPhase.IDENTIFYING, coordinator.networks.value.single().connectionPhase)
                    assertTrue(client.received.none { it.startsWith("JOIN ") })
                    client.send(":NickServ!s@h NOTICE tester :You're now logged in as $account.")
                    await { coordinator.buffers.value["network|#room"]?.joinState == JoinState.JOINED }
                    assertEquals(ConnectionStatus.REGISTERED, coordinator.networks.value.single().status)
                    assertEquals(1, client.received.count { it == "JOIN #room" })
                }
            }
        }
    }

    @Test
    fun failedPostregistrationSaslReauthenticationPreservesTheHealthySocketAndConversation() = runBlocking {
        Server().use { server ->
            val config = config(server).copy(saslAuthcid = "account", saslPasswordRef = "sasl")
            harness(config).use { harness ->
                harness.vault.storePassword("sasl", "sasl-secret")
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.buffers.value["network|#room"]?.joinState == JoinState.JOINED }
                val client = server.clients.single()
                client.nextSaslFailure = 904
                client.send(":srv CAP tester DEL :sasl")
                client.send(":srv CAP tester NEW :sasl=PLAIN")
                await { client.received.count { it == "AUTHENTICATE PLAIN" } == 2 &&
                    coordinator.buffers.value[ConversationRef.server(config.id).storageKey]?.messages?.any {
                        it.text.startsWith("SASL authentication failed:")
                    } == true }
                assertEquals(ConnectionStatus.REGISTERED, coordinator.networks.value.single().status)
                assertEquals(RecoveryPhase.REGISTERED, coordinator.networks.value.single().connectionPhase)
                assertFalse(client.closed)
                assertTrue(coordinator.sendText(config.id, "network|#room", "still healthy after reauth"))
                await { "PRIVMSG #room :still healthy after reauth" in client.received }
                client.send("@msgid=after-reauth :alice!u@h PRIVMSG #room :live after reauth")
                await { coordinator.buffers.value["network|#room"]?.messages?.any { it.msgid == "after-reauth" } == true }
                assertEquals(1, harness.createdConfigs.size)
                assertEquals(1, client.received.count { it == "JOIN #room" })
            }
        }
    }

    @Test
    fun nickServConfirmationReleasesRegistrationJoinsAndHistoryExactlyOnce() = runBlocking {
        Server(historyAvailable = true, acknowledgeHistory = true).use { server ->
            val config = config(server).copy(nickServAccount = "account", nickServPasswordRef = "nickserv")
            harness(config).use { harness ->
                val coordinator = harness.coordinator
                harness.vault.storePassword("nickserv", "saved-nickserv-password")
                val room = ConversationRef.channel(config.id, "#room")
                record(harness, room, "anchor-before-identify", "Preserved phase one anchor", 1_000)
                coordinator.startAll()
                await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.IDENTIFYING }
                val client = server.clients.single()
                await { "PRIVMSG NickServ :IDENTIFY account saved-nickserv-password" in client.received }
                assertTrue(client.received.none { it.startsWith("JOIN ") || "CHATHISTORY" in it })
                assertEquals(ConnectionStatus.CONNECTING, coordinator.networks.value.single().status)
                client.send(":EvilNickServ!u@h NOTICE tester :You are now identified")
                client.send("@batch=old :NickServ!u@h NOTICE tester :You are now identified")
                client.send(":srv 372 tester :identity-still-pending")
                await { coordinator.buffers.value[ConversationRef.server(config.id).storageKey]?.messages?.any { it.text == "identity-still-pending" } == true }
                assertEquals(RecoveryPhase.IDENTIFYING, coordinator.networks.value.single().connectionPhase)
                client.send(":srv 900 tester tester!u@h account :You are now logged in")
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED &&
                    client.received.any { it.startsWith("CHATHISTORY LATEST #room ") } &&
                    coordinator.buffers.value[room.storageKey]?.history?.catchUp?.status == HistoryLoadStatus.IDLE }
                client.send(":srv 900 tester tester!u@h account :Duplicate confirmation")
                client.welcome()
                client.send(":srv 372 tester :identity-complete")
                await { coordinator.buffers.value[ConversationRef.server(config.id).storageKey]?.messages?.any { it.text == "identity-complete" } == true }
                assertEquals(1, client.received.count { it == "JOIN #room" })
                assertEquals(1, client.received.count { it.startsWith("PRIVMSG NickServ :IDENTIFY") })
                assertEquals(1, client.received.count { it.startsWith("CHATHISTORY TARGETS ") })
                assertEquals(1, client.received.count { it.startsWith("CHATHISTORY LATEST #room ") })
                assertEquals("anchor-before-identify", harness.messages.recent(room, 10).single().msgid)
            }
        }
    }

    @Test
    fun nickServRejectionAndTimeoutBlockJoinsThenManualRetryUsesAFreshGate() = runBlocking {
        for (reject in listOf(true, false)) {
            Server(historyAvailable = true).use { server ->
                val config = config(server).copy(nickServAccount = "account", nickServPasswordRef = "nickserv")
                harness(config).use { harness ->
                    val coordinator = harness.coordinator
                    harness.vault.storePassword("nickserv", "secret")
                    coordinator.startAll()
                    await { server.clients.singleOrNull()?.received?.any { it.startsWith("PRIVMSG NickServ :IDENTIFY") } == true }
                    val first = server.clients.single()
                    if (reject) first.send(":NickServ!u@h NOTICE tester :Password incorrect")
                    else assertNotNull(scheduler).advanceTimeBy(7_001)
                    await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.AUTHENTICATION_REJECTED }
                    assertTrue(first.received.none { it.startsWith("JOIN ") || "CHATHISTORY" in it })
                    assertTrue(assertNotNull(coordinator.networks.value.single().connectionError).contains(if (reject) "rejected" else "seven seconds"))
                    coordinator.onForegroundResume()
                    coordinator.updateConnectivity(true, 9)
                    assertNotNull(scheduler).advanceTimeBy(60_000)
                    assertNotNull(scheduler).runCurrent()
                    assertEquals(1, server.clients.size)
                    coordinator.connectNetwork(config.id)
                    await { server.clients.size == 2 && server.clients.last().received.any { it.startsWith("PRIVMSG NickServ :IDENTIFY") } }
                    val second = server.clients.last()
                    second.send(":NickServ!u@h NOTICE tester :You are now identified")
                    await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED && "JOIN #room" in second.received }
                    assertEquals(1, second.received.count { it == "JOIN #room" })
                    assertEquals(2, harness.createdConfigs.size)
                }
            }
        }
    }

    @Test
    fun nonwaitingNickServJoinsImmediatelyButStillSurfacesARejection() = runBlocking {
        Server().use { server ->
            val config = config(server).copy(nickServPasswordRef = "nickserv", waitForNickServ = false)
            harness(config).use { harness ->
                harness.vault.storePassword("nickserv", "secret")
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED && "JOIN #room" in server.clients.single().received }
                server.clients.single().send(":NickServ!u@h NOTICE tester :Password incorrect")
                await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.AUTHENTICATION_REJECTED }
                assertEquals(ConnectionStatus.DISCONNECTED, coordinator.networks.value.single().status)
                assertFalse(coordinator.sendText(config.id, "network|#room", "not authenticated"))
            }
        }
    }

    @Test
    fun rejectedCertificateRequiresMatchingConsentAndRemovalRestoresPlatformVerification() = runBlocking {
        Server(tlsFixture = "private").use { server ->
            val config = config(server).copy(tls = true)
            harness(config).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.CERTIFICATE_REJECTED }
                val inspection = assertNotNull(coordinator.networks.value.single().rejectedCertificate)
                assertEquals("127.0.0.1", inspection.host)
                assertEquals(server.port, inspection.port)
                assertEquals(tlsFingerprint("private"), inspection.certificates.first().sha256)
                assertFalse(coordinator.trustCertificate(config.id, inspection.copy(port = server.port + 1)))
                assertEquals(null, NetworkStore(harness.directory).all().single().certificatePin)
                assertTrue(coordinator.trustCertificate(config.id, inspection))
                awaitRegistered(harness, server)
                val pin = assertNotNull(NetworkStore(harness.directory).all().single().certificatePin)
                assertEquals(CertificatePin(inspection.host, inspection.port, inspection.certificates.first().sha256), pin)
                assertTrue(coordinator.networks.value.single().hasCertificatePin)
                assertEquals(2, harness.createdConfigs.size)
                assertTrue(coordinator.removeCertificateTrust(config.id))
                await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.CERTIFICATE_REJECTED }
                assertFalse(coordinator.networks.value.single().hasCertificatePin)
                assertEquals(null, NetworkStore(harness.directory).all().single().certificatePin)
                assertEquals(3, harness.createdConfigs.size)
                coordinator.onForegroundResume()
                coordinator.updateConnectivity(true, 10)
                assertNotNull(scheduler).advanceTimeBy(60_000)
                assertNotNull(scheduler).runCurrent()
                assertEquals(3, harness.createdConfigs.size)
            }
        }
    }

    @Test
    fun aSavedPinIsNotReusedForAnEditedOrStsUpgradedEndpoint() = runBlocking {
        Server(tlsFixture = "private").use { first ->
            Server(tlsFixture = "private").use { second ->
                val config = config(first).copy(tls = true, certificatePin = CertificatePin("127.0.0.1", first.port, tlsFingerprint("private")))
                harness(config).use { harness ->
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    awaitRegistered(harness, first)
                    assertTrue(coordinator.updateNetwork(config.copy(port = second.port)))
                    await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.CERTIFICATE_REJECTED }
                    assertEquals(null, harness.createdConfigs.last().certificatePin)
                    assertEquals(config.certificatePin, NetworkStore(harness.directory).all().single().certificatePin)
                }
                val upgraded = config.copy(tls = false)
                harness(upgraded).use { harness ->
                    harness.policies.save("127.0.0.1", StsPolicy(second.port, System.currentTimeMillis() / 1000 + 3_600))
                    harness.coordinator.startAll()
                    await { harness.coordinator.networks.value.single().connectionPhase == RecoveryPhase.CERTIFICATE_REJECTED }
                    val effective = harness.createdConfigs.single()
                    assertTrue(effective.tls)
                    assertEquals(second.port, effective.port)
                    assertEquals(null, effective.certificatePin)
                }
            }
        }
    }

    @Test
    fun missingIdentityAndExternalWithoutTlsFailBeforeCreatingASocket() = runBlocking {
        Server().use { server ->
            for (config in listOf(
                    config(server).copy(tlsClientAlias = "removed-alias"),
                    config(server).copy(saslMode = SaslMode.EXTERNAL),
                )
            ) {
                harness(config).use { harness ->
                    harness.coordinator.startAll()
                    await { harness.coordinator.networks.value.single().connectionPhase == RecoveryPhase.AUTHENTICATION_REJECTED }
                    assertTrue(assertNotNull(harness.coordinator.networks.value.single().connectionError).contains("TLS"))
                    assertTrue(harness.createdConfigs.isEmpty())
                    assertTrue(server.clients.isEmpty())
                }
            }
        }
    }

    @Test
    fun clientAliasesResolvePerNetworkAndTheirActualChainsReachTheirOwnTlsEndpoints() = runBlocking {
        Server(tlsFixture = "private", requireClientIdentity = true).use { first ->
            Server(tlsFixture = "private", requireClientIdentity = true).use { second ->
                val aliases = CopyOnWriteArrayList<String>()
                val firstIdentity = tlsIdentity()
                val secondIdentity = tlsIdentity("client-other")
                assertFalse(firstIdentity.privateKey.encoded.contentEquals(secondIdentity.privateKey.encoded))
                assertFalse(firstIdentity.certificates.first().encoded.contentEquals(secondIdentity.certificates.first().encoded))
                val config = config(first).copy(
                    tls = true, tlsClientAlias = "first-alias",
                    certificatePin = CertificatePin("127.0.0.1", first.port, tlsFingerprint("private")),
                )
                harness(config, identityProvider = { alias ->
                    aliases.add(alias)
                    when (alias) {
                        "first-alias" -> firstIdentity
                        "second-alias" -> secondIdentity
                        else -> throw TlsIdentityUnavailableException("Unknown TLS identity.")
                    }
                }).use { harness ->
                    val secondConfig = config.copy(
                        id = "second-network", port = second.port, tlsClientAlias = "second-alias",
                        certificatePin = CertificatePin("127.0.0.1", second.port, tlsFingerprint("private")),
                    )
                    assertTrue(harness.networks.add(secondConfig))
                    harness.coordinator.startAll()
                    awaitRegistered(harness, first, second)
                    assertEquals(setOf("first-alias", "second-alias"), aliases.toSet())
                    assertEquals(
                        firstIdentity.certificates.map { Base64.getEncoder().encodeToString(it.encoded) },
                        first.clients.single().peerCertificates.map { Base64.getEncoder().encodeToString(it.encoded) },
                    )
                    assertEquals(
                        secondIdentity.certificates.map { Base64.getEncoder().encodeToString(it.encoded) },
                        second.clients.single().peerCertificates.map { Base64.getEncoder().encodeToString(it.encoded) },
                    )
                    assertEquals(config.username, harness.createdConfigs.first().username)
                    harness.coordinator.disconnect(secondConfig.id)
                }
            }
        }
    }

    @Test
    fun proxyAuthenticationFailureIsStructuredAndDoesNotRetryOrRegister() = runBlocking {
        Server().use { destination ->
            Proxy(rejectAuthentication = true).use { proxy ->
                val config = config(destination).copy(proxy = SocksProxyConfig("127.0.0.1", proxy.port, "proxy-user", "proxy"))
                harness(config).use { harness ->
                    harness.vault.storePassword("proxy", "proxy-secret")
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.AUTHENTICATION_REJECTED }
                    assertTrue(assertNotNull(coordinator.networks.value.single().connectionError).contains("proxy"))
                    assertEquals(listOf("proxy-user"), proxy.usernames)
                    assertEquals(listOf("proxy-secret"), proxy.passwords)
                    assertTrue(destination.clients.isEmpty())
                    coordinator.onForegroundResume()
                    coordinator.updateConnectivity(true, 11)
                    assertNotNull(scheduler).advanceTimeBy(60_000)
                    assertNotNull(scheduler).runCurrent()
                    assertEquals(1, harness.createdConfigs.size)
                    assertEquals(1, proxy.passwords.size)
                }
            }
        }
    }

    @Test
    fun allResolvedCredentialsReachTheirIntendedLoginAndStayOutOfTranscriptsRoomAndRawLogs() = runBlocking {
        Server().use { destination ->
            Proxy().use { proxy ->
                val secrets = listOf("sasl-secret", "server-secret", "nickserv-secret", "proxy-secret")
                val config = config(destination).copy(
                    host = "localhost", alternateNicks = listOf("alternate"), saslAuthcid = "account", saslPasswordRef = "sasl",
                    serverPasswordRef = "server", nickServAccount = "account", nickServPasswordRef = "nickserv",
                    proxy = SocksProxyConfig("127.0.0.1", proxy.port, "proxy-user", "proxy"),
                )
                harness(config).use { harness ->
                    listOf("sasl", "server", "nickserv", "proxy").zip(secrets).forEach { (reference, password) ->
                        harness.vault.storePassword(reference, password)
                    }
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.IDENTIFYING }
                    val client = destination.clients.single()
                    await { "PRIVMSG NickServ :IDENTIFY account nickserv-secret" in client.received }
                    assertEquals(listOf("localhost" to destination.port), proxy.destinations)
                    assertEquals(listOf("proxy-secret"), proxy.passwords)
                    assertEquals("\u0000account\u0000sasl-secret", credentials(client))
                    assertEquals(listOf("server-secret"), client.parameters("PASS"))
                    client.send(":NickServ!u@h NOTICE tester :You are now identified")
                    await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                    val room = ConversationRef.channel(config.id, "#room")
                    val text = secrets.joinToString(" ")
                    assertTrue(coordinator.sendText(config.id, room.storageKey, text))
                    await { "PRIVMSG #room :$text" in client.received }
                    client.send("@msgid=credential-echo :alice!u@h PRIVMSG #room :$text")
                    await { harness.messages.recent(room, 10).size == 2 }
                    assertTrue(assertNotNull(coordinator.buffers.value[room.storageKey]).messages.all { message ->
                        secrets.none(message.text::contains)
                    })
                    assertTrue(harness.messages.recent(room, 10).all { message -> secrets.none(message.text::contains) })
                    assertTrue(coordinator.rawLog(config.id).all { frame -> secrets.none(frame.line::contains) })
                    val encoded = client.received.first { it.startsWith("AUTHENTICATE ") && it != "AUTHENTICATE PLAIN" }
                        .substringAfter(' ')
                    client.send("@msgid=encoded-credential-echo :alice!u@h PRIVMSG #room :$encoded")
                    await { harness.messages.recent(room, 10).size == 3 }
                    assertTrue(harness.messages.recent(room, 10).none { encoded in it.text })
                    assertTrue(assertNotNull(coordinator.buffers.value[room.storageKey]).messages.none { encoded in it.text })
                    assertTrue(coordinator.rawLog(config.id).none { encoded in it.line })
                    assertTrue(harness.createdConfigs.single().alternateNicks == listOf("alternate"))
                    val privateMessage = "IDENTIFY account nickserv-secret"
                    val serviceLine = "PRIVMSG NickServ :$privateMessage"
                    val sentServiceMessages = client.received.count { it == serviceLine }
                    assertTrue(coordinator.sendText(config.id, room.storageKey, "/msg NickServ $privateMessage"))
                    val service = ConversationRef.directMessage(config.id, "NickServ")
                    await {
                        client.received.count { it == serviceLine } == sentServiceMessages + 1 &&
                            harness.messages.recent(service, 10).any { it.sentByUs }
                    }
                    val serviceMessages = harness.messages.recent(service, 10)
                    assertTrue(serviceMessages.all { message -> secrets.none(message.text::contains) })
                    val serviceBuffer = assertNotNull(coordinator.buffers.value[service.storageKey])
                    assertTrue(serviceBuffer.messages.any { it.sentByUs })
                    assertTrue(serviceBuffer.messages.all { message -> secrets.none(message.text::contains) })
                    assertTrue(coordinator.rawLog(config.id).all { frame -> secrets.none(frame.line::contains) })
                }
            }
        }
    }

    @Test
    fun restoredMissingSecretCanBeManuallyRetriedOfflineWithoutOpeningASocketUntilOnline() = runBlocking {
        Server().use { server ->
            val config = config(server).copy(saslAuthcid = "account", saslPasswordRef = "missing")
            harness(config).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.AUTHENTICATION_REJECTED }
                assertTrue(harness.createdConfigs.isEmpty())
                harness.vault.storePassword("missing", "restored-secret")
                coordinator.updateConnectivity(false)
                coordinator.connectNetwork(config.id)
                assertNotNull(scheduler).advanceTimeBy(60_000)
                assertNotNull(scheduler).runCurrent()
                assertEquals(RecoveryPhase.OFFLINE, coordinator.networks.value.single().connectionPhase)
                assertTrue(harness.createdConfigs.isEmpty())
                assertTrue(server.clients.isEmpty())
                coordinator.updateConnectivity(true, 12)
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                assertEquals("\u0000account\u0000restored-secret", credentials(server.clients.single()))
                assertEquals(1, harness.createdConfigs.size)
                assertEquals(null, coordinator.networks.value.single().connectionError)
            }
        }
    }

    @Test
    fun nickServOwnNickChangeConfirmsTheFinalIdentityWithoutRevertingToTheWelcomeNickname() = runBlocking {
        Server().use { server ->
            val config = config(server).copy(nickServAccount = "accountName", nickServPasswordRef = "nickserv")
            harness(config).use { harness ->
                val coordinator = harness.coordinator
                harness.vault.storePassword("nickserv", "secret")
                coordinator.startAll()
                await { server.clients.singleOrNull()?.received?.any { it.startsWith("PRIVMSG NickServ :IDENTIFY") } == true }
                val client = server.clients.single()
                client.renameOwnNick("accountName")
                client.send(":NickServ!services@h NOTICE accountName :You're now logged in as accountName.")
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED &&
                    coordinator.buffers.value["network|#room"]?.joinState == JoinState.JOINED }
                assertEquals("accountName", coordinator.networks.value.single().ownNick)
                assertEquals(1, client.received.count { it == "JOIN #room" })
                assertEquals(1, client.received.count { it.startsWith("PRIVMSG NickServ :IDENTIFY") })
            }
        }
    }

    @Test
    fun nonwaitingNickServTimeoutSurfacesAnErrorWithoutDisconnectingOrJoiningAgain() = runBlocking {
        Server().use { server ->
            val config = config(server).copy(nickServPasswordRef = "nickserv", waitForNickServ = false)
            harness(config).use { harness ->
                val coordinator = harness.coordinator
                harness.vault.storePassword("nickserv", "secret")
                coordinator.startAll()
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED && "JOIN #room" in server.clients.single().received }
                assertNotNull(scheduler).advanceTimeBy(7_001)
                await { coordinator.networks.value.single().connectionError?.contains("seven seconds") == true }
                assertEquals(ConnectionStatus.REGISTERED, coordinator.networks.value.single().status)
                assertEquals(RecoveryPhase.REGISTERED, coordinator.networks.value.single().connectionPhase)
                assertFalse(server.clients.single().closed)
                assertEquals(1, server.clients.single().received.count { it == "JOIN #room" })
            }
        }
    }

    @Test
    fun externalUsesTheSelectedRealClientCertificateWithoutRequiringAPasswordReference() = runBlocking {
        Server(tlsFixture = "private", requireClientIdentity = true, advertisedSasl = "EXTERNAL").use { server ->
            val identity = tlsIdentity()
            val config = config(server).copy(
                tls = true, saslMode = SaslMode.EXTERNAL, saslAuthcid = "authorization", tlsClientAlias = "client",
                certificatePin = CertificatePin("127.0.0.1", server.port, tlsFingerprint("private")),
            )
            harness(config, identityProvider = { alias ->
                check(alias == "client")
                identity
            }).use { harness ->
                harness.coordinator.startAll()
                awaitRegistered(harness, server)
                val client = server.clients.single()
                assertTrue("AUTHENTICATE EXTERNAL" in client.received)
                assertFalse("AUTHENTICATE PLAIN" in client.received)
                assertEquals(identity.certificates.map { it.serialNumber }, client.peerCertificates.map { it.serialNumber })
                assertEquals(null, harness.createdConfigs.single().saslPassword)
                assertEquals(SaslMode.EXTERNAL, harness.createdConfigs.single().saslMode)
                assertTrue(harness.createdConfigs.single().tlsClientIdentity === identity)
            }
        }
    }

    @Test
    fun networkRemovalRetiresOnlyUnsharedSecretsAcrossEveryCredentialRole() = runBlocking {
        Server().use { server ->
            val config = config(server).copy(
                autoConnect = false, saslAuthcid = "account", saslPasswordRef = "shared", serverPasswordRef = "owned-pass",
                nickServPasswordRef = "shared-service", proxy = SocksProxyConfig("localhost", 1080, "proxy-user", "owned-proxy"),
            )
            harness(config).use { harness ->
                for (reference in listOf("shared", "owned-pass", "shared-service", "owned-proxy")) {
                    harness.vault.storePassword(reference, "secret-for-$reference")
                }
                val retained = config.copy(
                    id = "retained", saslPasswordRef = null, serverPasswordRef = "shared",
                    nickServPasswordRef = "shared-service", proxy = null,
                )
                assertTrue(harness.networks.add(retained))
                harness.coordinator.startAll()
                harness.coordinator.removeNetwork(config.id)
                await { harness.vault.readPassword("owned-pass") == null && harness.vault.readPassword("owned-proxy") == null }
                assertEquals(listOf(retained), NetworkStore(harness.directory).all())
                assertEquals("secret-for-shared", harness.vault.readPassword("shared"))
                assertEquals("secret-for-shared-service", harness.vault.readPassword("shared-service"))
                assertTrue(harness.coordinator.buffers.value.values.none { it.ref.networkId == config.id })
                assertTrue(harness.createdConfigs.isEmpty())
            }
        }
    }

    @Test
    fun committedNetworkRemovalReportsVaultCleanupFailureWithoutUndoingTheRemoval() = runBlocking {
        Server().use { server ->
            val backing = InMemoryCredentialVault()
            val vault = object : CredentialVault by backing {
                override fun deletePassword(key: String) {
                    throw java.io.IOException("must-not-be-logged-secret")
                }
            }
            val config = config(server).copy(autoConnect = false, serverPasswordRef = "password")
            harness(config, vault = vault).use { harness ->
                harness.vault.storePassword("password", "secret")
                harness.coordinator.startAll()
                harness.coordinator.ingestRaw(config.id, false, "old network log")
                harness.coordinator.removeNetwork(config.id)
                await { harness.coordinator.operationError.value?.contains("could not be deleted") == true }
                assertTrue(NetworkStore(harness.directory).all().isEmpty())
                assertTrue(harness.coordinator.networks.value.isEmpty())
                assertEquals("secret", backing.readPassword("password"))
                val error = assertNotNull(harness.coordinator.operationError.value)
                assertFalse("must-not-be-logged-secret" in error)
                assertTrue(harness.coordinator.rawLog(config.id).isEmpty())
                harness.coordinator.ingestRaw(config.id, false, "late retired traffic")
                assertTrue(harness.coordinator.rawLog(config.id).isEmpty())
                harness.coordinator.dismissOperationError("older error")
                assertEquals(error, harness.coordinator.operationError.value)
                harness.coordinator.dismissOperationError(error)
                assertEquals(null, harness.coordinator.operationError.value)
                assertTrue(harness.createdConfigs.isEmpty())
            }
        }
    }

    @Test
    fun explicitPasswordSaslModesWithMissingOrEmptySecretsNeverDowngradeOrCreateASocket() = runBlocking {
        Server(historyAvailable = true).use { server ->
            for (mode in listOf(SaslMode.PLAIN, SaslMode.SCRAM_SHA_256)) {
                for (reference in listOf(null, "empty")) {
                    val config = config(server).copy(saslMode = mode, saslAuthcid = "account", saslPasswordRef = reference)
                    harness(config).use { harness ->
                        if (reference != null) harness.vault.storePassword(reference, "")
                        val coordinator = harness.coordinator
                        coordinator.startAll()
                        await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.AUTHENTICATION_REJECTED }
                        assertTrue(assertNotNull(coordinator.networks.value.single().connectionError).contains("requires a saved password"))
                        assertTrue(harness.createdConfigs.isEmpty())
                        assertTrue(server.clients.isEmpty())
                        coordinator.onForegroundResume()
                        coordinator.updateConnectivity(true, 13)
                        assertNotNull(scheduler).advanceTimeBy(60_000)
                        assertNotNull(scheduler).runCurrent()
                        assertTrue(harness.createdConfigs.isEmpty())
                        assertEquals(mode, NetworkStore(harness.directory).all().single().saslMode)
                    }
                }
            }
        }
    }

    @Test
    fun lateFalseFromARetiredProbeCannotCloseANewConnectionWithinTheSameSession() = runBlocking {
        Server(acknowledgeProbes = false).use { server ->
            val dispatcher = HoldingProbeDispatcher()
            harness(config(server), dispatcher).use { harness ->
                val coordinator = harness.coordinator
                try {
                    coordinator.startAll()
                    await { coordinator.restorationReady.value }
                    await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED &&
                        coordinator.buffers.value["network|#room"]?.members?.isNotEmpty() == true }
                    dispatcher.captureNextLaunch()
                    coordinator.onForegroundResume()
                    val probeJob = assertNotNull(dispatcher.heldJob)
                    dispatcher.startHeldLaunch()
                    await { server.clients.single().received.any { it.startsWith("PING ") } }
                    server.clients.single().close()
                    await { dispatcher.pending > 0 && server.clients.size == 2 &&
                        coordinator.networks.value.single().status == ConnectionStatus.REGISTERED &&
                        "JOIN #room" in server.clients.last().received }
                    assertTrue(probeJob.isActive)
                    val replacement = server.clients.last()
                    dispatcher.release()
                    await { probeJob.isCompleted }
                    assertEquals(ConnectionStatus.REGISTERED, coordinator.networks.value.single().status)
                    assertFalse(replacement.closed)
                    assertEquals(2, harness.createdConfigs.size)
                    assertTrue(coordinator.sendText(harness.config.id, "network|#room", "late false was rejected"))
                    await { "PRIVMSG #room :late false was rejected" in replacement.received }
                } finally {
                    dispatcher.release()
                }
            }
        }
    }

    @Test
    fun anOfflineRetiredSameEpochStsCallbackCannotRetargetTheNextConnection() = runBlocking {
        Server().use { original ->
            Server().use { staleUpgrade ->
                harness(config(original)).use { harness ->
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                    val callback = harness.stsCallbacks.single()
                    coordinator.updateConnectivity(false)
                    await { original.clients.single().closed }
                    callback(staleUpgrade.port)
                    coordinator.updateConnectivity(true, 14)
                    await { original.clients.size == 2 && coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                    assertEquals(original.port, harness.createdConfigs.last().port)
                    assertFalse(harness.createdConfigs.last().tls)
                    assertTrue(staleUpgrade.clients.isEmpty())
                    assertEquals(2, harness.createdConfigs.size)
                }
            }
        }
    }

    @Test
    fun manualConnectDoesNotOpenTransportWhenClearingTheDurableOptOutCannotBeSaved() = runBlocking {
        Server().use { server ->
            val config = config(server).copy(userDisconnected = true)
            harness(config).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                withBlockedNetworkFile(harness) {
                    coordinator.connectNetwork(config.id)
                    assertTrue(assertNotNull(coordinator.networks.value.single().connectionError).contains("Could not save Connect intent"))
                    assertEquals(RecoveryPhase.USER_DISCONNECTED, coordinator.networks.value.single().connectionPhase)
                    assertTrue(harness.networks.all().single().userDisconnected)
                    assertNotNull(scheduler).runCurrent()
                    assertTrue(harness.createdConfigs.isEmpty())
                    assertTrue(server.clients.isEmpty())
                }
                assertTrue(NetworkStore(harness.directory).all().single().userDisconnected)
                coordinator.connectNetwork(config.id)
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                assertFalse(NetworkStore(harness.directory).all().single().userDisconnected)
                assertEquals(1, harness.createdConfigs.size)
            }
        }
    }

    @Test
    fun disconnectStopsTheCurrentSocketWhenRestartOptOutCannotBeSavedAndExplainsTheFailure() = runBlocking {
        Server().use { server ->
            harness(config(server)).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                val client = server.clients.single()
                withBlockedNetworkFile(harness) {
                    coordinator.disconnect(harness.config.id)
                    await { client.closed }
                    assertEquals(ConnectionStatus.DISCONNECTED, coordinator.networks.value.single().status)
                    assertEquals(RecoveryPhase.USER_DISCONNECTED, coordinator.networks.value.single().connectionPhase)
                    assertTrue(assertNotNull(coordinator.networks.value.single().connectionError).contains("restart disconnect preference was not saved"))
                    assertTrue(coordinator.networks.value.single().disconnectSavePending)
                    assertFalse(harness.networks.all().single().userDisconnected)
                    coordinator.onForegroundResume()
                    coordinator.updateConnectivity(true, 15)
                    assertNotNull(scheduler).advanceTimeBy(60_000)
                    assertNotNull(scheduler).runCurrent()
                    assertEquals(1, harness.createdConfigs.size)
                }
                assertFalse(NetworkStore(harness.directory).all().single().userDisconnected)
                val reloaded = harness.reloadCoordinator()
                reloaded.startAll()
                await { server.clients.size == 2 && reloaded.networks.value.single().status == ConnectionStatus.REGISTERED }
            }
        }
    }

    @Test
    fun retryingAnUnsavedDisconnectPersistsOptOutWithoutOpeningAnotherSocket() = runBlocking {
        Server().use { server ->
            harness(config(server)).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                withBlockedNetworkFile(harness) {
                    coordinator.disconnect(harness.config.id)
                    await { server.clients.single().closed }
                    assertTrue(coordinator.networks.value.single().disconnectSavePending)
                }
                coordinator.disconnect(harness.config.id)
                assertFalse(coordinator.networks.value.single().disconnectSavePending)
                assertEquals(null, coordinator.networks.value.single().connectionError)
                assertTrue(NetworkStore(harness.directory).all().single().userDisconnected)
                val reloaded = harness.reloadCoordinator()
                reloaded.startAll()
                assertNotNull(scheduler).advanceTimeBy(60_000)
                assertNotNull(scheduler).runCurrent()
                assertEquals(1, harness.createdConfigs.size)
                assertEquals(1, server.clients.size)
            }
        }
    }

    @Test
    fun certificateTrustAndRemovalReturnFalseWithoutReplacingTransportWhenDurableSaveFails() = runBlocking {
        Server(tlsFixture = "private").use { server ->
            val config = config(server).copy(tls = true)
            harness(config).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.CERTIFICATE_REJECTED }
                val inspection = assertNotNull(coordinator.networks.value.single().rejectedCertificate)
                withBlockedNetworkFile(harness) {
                    assertFalse(coordinator.trustCertificate(config.id, inspection))
                    assertTrue(assertNotNull(coordinator.networks.value.single().connectionError).contains("not saved"))
                    assertEquals(null, harness.networks.all().single().certificatePin)
                    assertEquals(1, harness.createdConfigs.size)
                    assertEquals(inspection, coordinator.networks.value.single().rejectedCertificate)
                }
                assertEquals(null, NetworkStore(harness.directory).all().single().certificatePin)
                assertTrue(coordinator.trustCertificate(config.id, inspection))
                awaitRegistered(harness, server)
                val pin = assertNotNull(NetworkStore(harness.directory).all().single().certificatePin)
                val client = server.clients.last()
                withBlockedNetworkFile(harness) {
                    assertFalse(coordinator.removeCertificateTrust(config.id))
                    assertEquals(pin, harness.networks.all().single().certificatePin)
                    assertEquals(ConnectionStatus.REGISTERED, coordinator.networks.value.single().status)
                    assertFalse(client.closed)
                    assertEquals(2, harness.createdConfigs.size)
                }
                assertEquals(pin, NetworkStore(harness.directory).all().single().certificatePin)
                assertTrue(coordinator.removeCertificateTrust(config.id))
                await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.CERTIFICATE_REJECTED }
                assertEquals(3, harness.createdConfigs.size)
            }
        }
    }

    @Test
    fun fallbackRedactionMatchesMarkerPrefixedSecretsBeforePreservingExistingMarkers() = runBlocking {
        Server().use { server ->
            val secret = "<redacted>marker-prefixed-secret"
            val config = config(server).copy(saslAuthcid = "account", saslPasswordRef = "sasl")
            lateinit var coordinator: LiveCoordinator
            harness(config, beforeCreate = { updated ->
                coordinator.ingestRaw(updated.id, false, "factory diagnostic: $secret and <redacted>")
            }).use { harness ->
                coordinator = harness.coordinator
                harness.vault.storePassword("sasl", secret)
                coordinator.startAll()
                await { coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                assertEquals("\u0000account\u0000$secret", credentials(server.clients.single()))
                assertTrue(coordinator.rawLog(config.id).any { it.line == "factory diagnostic: <redacted> and <redacted>" })
                assertTrue(coordinator.rawLog(config.id).none { secret in it.line })
            }
        }
    }

    @Test
    fun anInspectedCertificateThatExpiresBeforeConsentCannotBeTrustedAndExplainsWhy() = runBlocking {
        Server(tlsFixture = "private").use { server ->
            var now = System.currentTimeMillis()
            val config = config(server).copy(tls = true)
            harness(config, clock = { now }).use { harness ->
                val coordinator = harness.coordinator
                coordinator.startAll()
                await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.CERTIFICATE_REJECTED }
                val inspection = assertNotNull(coordinator.networks.value.single().rejectedCertificate)
                now = inspection.certificates.first().notAfterMs + 1
                assertFalse(coordinator.trustCertificate(config.id, inspection))
                assertTrue(assertNotNull(coordinator.networks.value.single().connectionError).contains("expired or not yet valid"))
                assertEquals(inspection, coordinator.networks.value.single().rejectedCertificate)
                assertEquals(null, NetworkStore(harness.directory).all().single().certificatePin)
                assertEquals(1, harness.createdConfigs.size)
            }
        }
    }

    @Test
    fun expiredServerCertificatesAreTerminalWithoutOfferingAnUnusableTrustLoop() = runBlocking {
        Server(tlsFixture = "expired").use { server ->
            for (pinned in listOf(false, true)) {
                val config = config(server).copy(
                    tls = true,
                    certificatePin = if (pinned) CertificatePin("127.0.0.1", server.port, tlsFingerprint("expired")) else null,
                )
                harness(config).use { harness ->
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.CERTIFICATE_REJECTED }
                    val network = coordinator.networks.value.single()
                    assertEquals(null, network.rejectedCertificate)
                    assertTrue(assertNotNull(network.connectionError).contains("expired"))
                    assertFalse(coordinator.trustCertificate(config.id))
                    assertEquals(config.certificatePin, NetworkStore(harness.directory).all().single().certificatePin)
                    coordinator.onForegroundResume()
                    coordinator.updateConnectivity(false)
                    coordinator.updateConnectivity(true, 16)
                    assertNotNull(scheduler).advanceTimeBy(60_000)
                    assertNotNull(scheduler).runCurrent()
                    assertEquals(1, harness.createdConfigs.size)
                }
            }
        }
    }

    @Test
    fun matchingPinCannotBypassTheLogicalHostnameOrOfferRepeatedTrust() = runBlocking {
        Server(tlsFixture = "private").use { server ->
            Proxy(resolvedHost = "127.0.0.1").use { proxy ->
                val config = config(server).copy(
                    host = "wrong-host.invalid", tls = true,
                    certificatePin = CertificatePin("wrong-host.invalid", server.port, tlsFingerprint("private")),
                    proxy = SocksProxyConfig("127.0.0.1", proxy.port, "proxy-user", "proxy"),
                )
                harness(config).use { harness ->
                    harness.vault.storePassword("proxy", "proxy-secret")
                    val coordinator = harness.coordinator
                    coordinator.startAll()
                    await { coordinator.networks.value.single().connectionPhase == RecoveryPhase.CERTIFICATE_REJECTED }
                    assertEquals(listOf("wrong-host.invalid" to server.port), proxy.destinations.toList())
                    assertEquals(null, coordinator.networks.value.single().rejectedCertificate)
                    assertTrue(assertNotNull(coordinator.networks.value.single().connectionError).contains("hostname"))
                    assertFalse(coordinator.trustCertificate(config.id))
                    coordinator.onForegroundResume()
                    coordinator.updateConnectivity(true, 17)
                    assertNotNull(scheduler).advanceTimeBy(60_000)
                    assertNotNull(scheduler).runCurrent()
                    assertEquals(1, harness.createdConfigs.size)
                    assertEquals(config.certificatePin, NetworkStore(harness.directory).all().single().certificatePin)
                }
            }
        }
    }

    private suspend fun withBlockedNetworkFile(harness: Harness, action: suspend () -> Unit) {
        val file = File(harness.directory, "networks.json")
        val original = file.readText()
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        try {
            action()
        } finally {
            assertTrue(file.deleteRecursively())
            file.writeText(original)
        }
    }

    private suspend fun record(harness: Harness, ref: ConversationRef, msgid: String?, text: String, timestampMs: Long) {
        harness.messages.record(
            StoredMessage(
                networkId = ref.networkId,
                conversation = ref,
                msgid = msgid,
                senderNick = "alice",
                senderUser = "u",
                senderHost = "h",
                kind = MessageKind.PRIVMSG,
                text = text,
                sentByUs = false,
                timestampMs = timestampMs,
            ),
        )
    }

    private fun credentials(client: Server.Client): String {
        val encoded = client.received.first { it.startsWith("AUTHENTICATE ") && it != "AUTHENTICATE PLAIN" }
            .substringAfter("AUTHENTICATE ")
        return String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }

    private suspend fun awaitRegistered(harness: Harness, vararg servers: Server) {
        await {
            val networks = harness.coordinator.networks.value
            val failed = networks.filter {
                it.connectionPhase == RecoveryPhase.AUTHENTICATION_REJECTED ||
                    it.connectionPhase == RecoveryPhase.CERTIFICATE_REJECTED ||
                    it.connectionPhase == RecoveryPhase.SERVER_UNREACHABLE
            }
            if (failed.isNotEmpty()) {
                val states = failed.joinToString { "${it.id}: ${it.connectionPhase}: ${it.connectionError}" }
                val transports = servers.joinToString { server ->
                    val clients = server.clients.joinToString { client ->
                        "closed=${client.closed}, peerCertificates=${client.peerCertificates.map { it.serialNumber }}, " +
                            "failure=${client.failure?.stackTraceToString()}"
                    }
                    "${server.port}: [$clients]"
                }
                throw AssertionError("Registration failed: $states; TLS endpoints: $transports")
            }
            networks.size == harness.networks.all().size && networks.all { it.status == ConnectionStatus.REGISTERED }
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(5_000) {
            while (true) {
                scheduler?.runCurrent()
                if (condition()) break
                delay(10)
            }
        }
    }

    private companion object {
        val tlsPassword = "fixture-password".toCharArray()

        fun tlsStore(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
            val relative = "core/client/src/test/resources/tls/$name.p12"
            val file = listOf(File(relative), File("../$relative")).first { it.isFile }
            file.inputStream().use { load(it, tlsPassword) }
        }

        fun tlsFingerprint(name: String): String = MessageDigest.getInstance("SHA-256")
            .digest(tlsStore(name).getCertificate("server").encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        fun tlsIdentity(name: String = "client"): TlsClientIdentity {
            val store = tlsStore(name)
            return TlsClientIdentity(
                store.getKey("client", tlsPassword) as PrivateKey,
                requireNotNull(store.getCertificateChain("client")).map { it as X509Certificate }.toTypedArray(),
            )
        }
    }
}
