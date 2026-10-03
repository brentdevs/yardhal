package dev.brentdevs.yardhal.core.client

import java.util.concurrent.atomic.AtomicInteger
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal class EventCollector(
    scope: CoroutineScope,
    events: kotlinx.coroutines.flow.Flow<IrcEvent>,
) {
    private val channel = Channel<IrcEvent>(Channel.UNLIMITED)

    init {
        scope.launch { events.collect { channel.trySend(it) } }
    }

    suspend fun await(timeoutMillis: Long = 5_000): IrcEvent = withTimeout(timeoutMillis) { channel.receive() }

    suspend fun awaitRegistered(): IrcEvent.Registered {
        while (true) {
            when (val event = await()) {
                is IrcEvent.Registered -> return event
                else -> continue
            }
        }
    }

    suspend fun drainUntilRegistered(): List<IrcEvent> {
        val seen = ArrayList<IrcEvent>()
        while (true) {
            when (val event = await()) {
                is IrcEvent.Registered -> {
                    seen.add(event)
                    return seen
                }
                else -> seen.add(event)
            }
        }
    }

    suspend inline fun <reified T : IrcEvent> awaitInstance(): T {
        while (true) {
            val event = await()
            if (event is T) return event
        }
    }
}

class IrcConnectionIntegrationTests {

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun plainConfig(
        host: String,
        port: Int,
        capabilities: Set<String> = emptySet(),
        saslAuthcid: String? = null,
        saslPassword: String? = null,
    ): IrcConnectionConfig = IrcConnectionConfig(
        host = host,
        port = port,
        tls = false,
        nick = "yardhal-test",
        username = "tester",
        realName = "Yardhal Tester",
        saslAuthcid = saslAuthcid,
        saslPassword = saslPassword,
        capabilities = capabilities,
        connectTimeoutMillis = 3_000,
    )

    @kotlinx.coroutines.DelicateCoroutinesApi
    @org.junit.jupiter.api.Test
    fun registersWithoutCapabilities() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                if (line.startsWith("USER")) {
                    server.sendLine(":irc.loopback.test 001 yardhal-test :Welcome to the loopback network")
                }
            }
            val scope = newScope()
            try {
                val connection = IrcConnection(plainConfig("127.0.0.1", server.port))
                val collector = EventCollector(scope, connection.events)
                connection.start()
                server.awaitClient()

                val registered = collector.awaitRegistered()
                assertEquals("yardhal-test", registered.nickname)

                assertTrue(server.receivedLines.any { it.startsWith("NICK ") })
                assertTrue(server.receivedLines.any { it.startsWith("USER tester") })
                connection.disconnect()
            } finally {
                scope.cancel()
            }
        }
        Unit
    }

    @kotlinx.coroutines.DelicateCoroutinesApi
    @org.junit.jupiter.api.Test
    fun negotiatesCapabilitiesWithSaslPlainThenRegisters() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                when {
                    line.startsWith("CAP LS") -> server.sendLine(":srv CAP * LS :sasl=PLAIN,EXTERNAL server-time echo-message")
                    line.startsWith("CAP REQ :") -> {
                        val requested = line.removePrefix("CAP REQ :")
                        server.sendLine(":srv CAP * ACK :$requested")
                    }
                    line == "AUTHENTICATE PLAIN" -> server.sendLine("AUTHENTICATE +")
                    line.startsWith("AUTHENTICATE ") -> {
                        server.sendLine(":srv 900 * yardhal-test!u@h yardhal-test")
                        server.sendLine(":srv 903 * :SASL authentication successful")
                    }
                    line.startsWith("USER") -> server.sendLine(":srv 001 yardhal-test :Welcome")
                }
            }
            val scope = newScope()
            try {
                val config = plainConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    capabilities = IrcConnectionConfig.DEFAULT_CAPABILITIES,
                    saslAuthcid = "jilles",
                    saslPassword = "sesame",
                )
                val tapped = java.util.concurrent.CopyOnWriteArrayList<String>()
                val connection = IrcConnection(
                    config.copy(serverPassword = "server-secret"),
                    rawTap = { outbound, line -> if (outbound) tapped.add(line) },
                )
                val collector = EventCollector(scope, connection.events)
                connection.start()
                server.awaitClient()

                val events = collector.drainUntilRegistered()
                val negotiated = events.filterIsInstance<IrcEvent.CapabilitiesNegotiated>().single()
                assertTrue(negotiated.capabilities.containsAll(setOf("sasl", "server-time", "echo-message")))
                val saslResult = events.filterIsInstance<IrcEvent.SaslResult>().single()
                assertEquals(SaslOutcome.Success, saslResult.outcome)
                assertEquals("yardhal-test", events.last().let { (it as IrcEvent.Registered).nickname })

                assertTrue(server.receivedLines.contains("AUTHENTICATE PLAIN"))
                assertTrue(server.receivedLines.any { it.startsWith("AUTHENTICATE A") })
                assertTrue(server.receivedLines.contains("CAP END"))
                assertTrue(tapped.contains("PASS <redacted>"))
                assertTrue(tapped.contains("AUTHENTICATE <redacted>"))
                assertFalse(tapped.any { "server-secret" in it || "sesame" in it })
                connection.disconnect()
            } finally {
                scope.cancel()
            }
        }
        Unit
    }

    @kotlinx.coroutines.DelicateCoroutinesApi
    @org.junit.jupiter.api.Test
    fun pingGetsPongBackThroughSocket() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                when {
                    line.startsWith("USER") -> server.sendLine(":srv 001 yardhal-test :Welcome")
                    line.startsWith("PING") -> server.sendLine(":srv PONG srv ${line.substringAfter("PING ")}")
                }
            }
            val scope = newScope()
            try {
                val connection = IrcConnection(plainConfig("127.0.0.1", server.port))
                val collector = EventCollector(scope, connection.events)
                connection.start()
                server.awaitClient()
                collector.awaitRegistered()

                connection.sendLine("PING :probe-token")
                var sawPongMessage = false
                val deadline = System.currentTimeMillis() + 5_000
                while (!sawPongMessage && System.currentTimeMillis() < deadline) {
                    val event = collector.await(1_000)
                    if (event is IrcEvent.MessageReceived && event.message.command == "PONG") {
                        sawPongMessage = true
                        assertEquals("probe-token", event.message.parameters.last())
                    }
                }
                assertTrue(sawPongMessage)
                connection.disconnect()
            } finally {
                scope.cancel()
            }
        }
        Unit
    }

    @kotlinx.coroutines.DelicateCoroutinesApi
    @org.junit.jupiter.api.Test
    fun insecureStsAdvertisementStopsBeforeRegistration() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            val upgradePort = AtomicInteger(0)
            server.lineListener = { line ->
                if (line.startsWith("CAP LS")) server.sendLine(":srv CAP * LS :sts=port=6697 server-time")
            }
            val scope = newScope()
            try {
                val connection = IrcConnection(
                    plainConfig("127.0.0.1", server.port, capabilities = setOf("server-time"))
                        .copy(serverPassword = "secret"),
                    onStsUpgrade = upgradePort::set,
                )
                val collector = EventCollector(scope, connection.events)
                connection.start()
                server.awaitClient()
                while (collector.await() !is IrcEvent.Disconnected) Unit
                assertEquals(6697, upgradePort.get())
                assertFalse(server.receivedLines.any { it.startsWith("PASS ") || it.startsWith("NICK ") })
            } finally {
                scope.cancel()
            }
        }
        Unit
    }

    @kotlinx.coroutines.DelicateCoroutinesApi
    @org.junit.jupiter.api.Test
    fun secureStsAdvertisementPersistsCurrentPort() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                when {
                    line.startsWith("CAP LS") -> server.sendLine(":srv CAP * LS :sts=duration=3600")
                    line.startsWith("USER") -> server.sendLine(":srv 001 yardhal-test :Welcome")
                }
            }
            val scope = newScope()
            try {
                val store = InMemoryStsPolicyStore()
                val config = plainConfig("127.0.0.1", server.port, capabilities = setOf("server-time"))
                    .copy(tls = true)
                val connection = IrcConnection(config, stsPolicyStore = store)
                val collector = EventCollector(scope, connection.events)
                connection.start(Socket("127.0.0.1", server.port))
                server.awaitClient()
                collector.awaitRegistered()
                assertEquals(server.port, store.load("127.0.0.1")?.port)
                assertTrue((store.load("127.0.0.1")?.expiresAtEpochSeconds ?: 0) > System.currentTimeMillis() / 1000)
                connection.disconnect()
            } finally {
                scope.cancel()
            }
        }
        Unit
    }

    @kotlinx.coroutines.DelicateCoroutinesApi
    @org.junit.jupiter.api.Test
    fun registrationTimeoutDisconnects() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            val scope = newScope()
            try {
                val connection = IrcConnection(
                    plainConfig("127.0.0.1", server.port),
                    keepAlive = KeepAliveConfig(registrationTimeoutMillis = 250),
                )
                val collector = EventCollector(scope, connection.events)
                connection.start()
                server.awaitClient()
                var sawDisconnected = false
                val deadline = System.currentTimeMillis() + 5_000
                while (!sawDisconnected && System.currentTimeMillis() < deadline) {
                    if (collector.await(500) is IrcEvent.Disconnected) sawDisconnected = true
                }
                assertTrue(sawDisconnected)
            } finally {
                scope.cancel()
            }
        }
        Unit
    }

    @kotlinx.coroutines.DelicateCoroutinesApi
    @org.junit.jupiter.api.Test
    fun reconnectorRecoversAfterDroppedConnection() = runBlocking {
        LoopbackIrcServer().use { flaky ->
            LoopbackIrcServer().use { stable ->
                flaky.start()
                stable.start()
                flaky.lineListener = { _ -> flaky.dropClient() }
                stable.lineListener = { line ->
                    if (line.startsWith("USER")) stable.sendLine(":srv 001 yardhal-test :Welcome back")
                }
                val target = AtomicInteger(flaky.port)
                val scope = newScope()
                try {
                    val reconnector = IrcReconnector(
                        scope = scope,
                        policy = ReconnectPolicy(initialDelayMillis = 25, maxDelayMillis = 100, multiplier = 2.0, maxAttempts = 6),
                        connectionFactory = { IrcConnection(plainConfig("127.0.0.1", target.get())) },
                    )
                    var registrations = 0
                    val registered = CompletableDeferred<Unit>()
                    val watcher = scope.launch {
                        reconnector.events.collect { event ->
                            if (event is IrcEvent.Registered) {
                                registrations += 1
                                registered.complete(Unit)
                            }
                        }
                    }
                    reconnector.start()
                    withTimeout(15_000) {
                        kotlinx.coroutines.delay(150)
                        target.set(stable.port)
                        registered.await()
                    }
                    assertTrue(registrations >= 1)
                    reconnector.stop()
                    watcher.cancel()
                } finally {
                    scope.cancel()
                }
            }
        }
        Unit
    }

    private fun withLoopback(
        config: (port: Int) -> IrcConnectionConfig,
        respond: LoopbackIrcServer.(String) -> Unit,
        rawTap: ((Boolean, String) -> Unit)? = null,
        body: suspend (IrcConnection, EventCollector, LoopbackIrcServer) -> Unit,
    ) = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line -> server.respond(line) }
            val scope = newScope()
            try {
                val connection = IrcConnection(config(server.port), rawTap = rawTap)
                val collector = EventCollector(scope, connection.events)
                connection.start()
                server.awaitClient()
                body(connection, collector, server)
                connection.disconnect()
            } finally {
                scope.cancel()
            }
        }
    }

    private fun LoopbackIrcServer.handleCapBasics(line: String, ls: String): Boolean {
        when {
            line.startsWith("CAP LS") -> sendLine(":srv CAP * LS :$ls")
            line.startsWith("CAP REQ :") -> sendLine(":srv CAP * ACK :${line.removePrefix("CAP REQ :")}")
            line.startsWith("USER") -> sendLine(":srv 001 yardhal-test :Welcome")
            else -> return false
        }
        return true
    }

    private fun scramResponder(fixture: ScramServerFixture, tamperSignature: Boolean): LoopbackIrcServer.(String) -> Unit {
        var step = 0
        return { line ->
            when {
                handleCapBasics(line, "sasl=PLAIN,SCRAM-SHA-256 cap-notify") -> Unit
                line == "AUTHENTICATE SCRAM-SHA-256" -> sendLine("AUTHENTICATE +")
                line == "AUTHENTICATE *" -> sendLine(":srv 906 * :SASL authentication aborted")
                line == "AUTHENTICATE +" && step == 2 -> {
                    sendLine(":srv 900 * yardhal-test!u@h jilles :You are now logged in as jilles")
                    sendLine(":srv 903 * :SASL authentication successful")
                }
                line.startsWith("AUTHENTICATE ") -> {
                    val payload = ScramServerFixture.decode(line.removePrefix("AUTHENTICATE "))
                    step += 1
                    val reply = if (step == 1) fixture.serverFirst(payload) else fixture.serverFinal(payload, tamperSignature)
                    sendLine("AUTHENTICATE ${ScramServerFixture.encode(reply)}")
                }
            }
        }
    }

    private fun saslConfig(port: Int, capabilities: Set<String> = setOf("sasl", "cap-notify")): IrcConnectionConfig =
        plainConfig("127.0.0.1", port, capabilities = capabilities, saslAuthcid = "jilles", saslPassword = "sesame")

    private suspend fun awaitReceived(server: LoopbackIrcServer, predicate: (String) -> Boolean): String =
        withTimeout(5_000) {
            var found = server.receivedLines.firstOrNull(predicate)
            while (found == null) {
                kotlinx.coroutines.delay(10)
                found = server.receivedLines.firstOrNull(predicate)
            }
            found
        }

    @org.junit.jupiter.api.Test
    fun scramSha256AuthenticatesAndVerifiesServer() {
        val fixture = ScramServerFixture(password = "sesame")
        val tapped = java.util.concurrent.CopyOnWriteArrayList<String>()
        withLoopback(
            config = { saslConfig(it) },
            respond = scramResponder(fixture, tamperSignature = false),
            rawTap = { outbound, line -> if (outbound) tapped.add(line) },
        ) { _, collector, server ->
            val events = collector.drainUntilRegistered()
            assertEquals(SaslOutcome.Success, events.filterIsInstance<IrcEvent.SaslResult>().single().outcome)
            assertTrue(fixture.clientProofValid)
            val lines = server.receivedLines.toList()
            assertTrue(lines.indexOf("AUTHENTICATE SCRAM-SHA-256") < lines.indexOf("CAP END"))
            assertFalse(lines.contains("AUTHENTICATE PLAIN"))
            val negotiated = events.filterIsInstance<IrcEvent.CapabilitiesNegotiated>().single()
            assertEquals("PLAIN,SCRAM-SHA-256", negotiated.values["sasl"])
            assertTrue(tapped.contains("AUTHENTICATE SCRAM-SHA-256"))
            assertTrue(tapped.contains("AUTHENTICATE <redacted>"))
            assertFalse(tapped.any { it.startsWith("AUTHENTICATE ") && it.length > 40 })
        }
    }

    @org.junit.jupiter.api.Test
    fun scramBadServerSignatureFailsClosedAndContinuesWithoutAccount() {
        val fixture = ScramServerFixture(password = "sesame")
        withLoopback(
            config = { saslConfig(it) },
            respond = scramResponder(fixture, tamperSignature = true),
        ) { _, collector, server ->
            val events = collector.drainUntilRegistered()
            val failure = events.filterIsInstance<IrcEvent.SaslResult>().single().outcome
            assertTrue(failure is SaslOutcome.Failure && failure.description.contains("signature"))
            val lines = server.receivedLines.toList()
            assertTrue(lines.indexOf("AUTHENTICATE *") in 0 until lines.indexOf("CAP END"))
            assertFalse(lines.contains("AUTHENTICATE +"))
        }
    }

    @org.junit.jupiter.api.Test
    fun saslMechsReplyFallsBackToPlain() {
        withLoopback(
            config = { saslConfig(it) },
            respond = { line ->
                when {
                    handleCapBasics(line, "sasl=SCRAM-SHA-256 cap-notify") -> Unit
                    line == "AUTHENTICATE SCRAM-SHA-256" -> {
                        sendLine(":srv 908 * PLAIN :are available SASL mechanisms")
                        sendLine(":srv 904 * :SASL authentication failed")
                    }
                    line == "AUTHENTICATE PLAIN" -> sendLine("AUTHENTICATE +")
                    line.startsWith("AUTHENTICATE ") -> sendLine(":srv 903 * :SASL authentication successful")
                }
            },
        ) { _, collector, server ->
            val events = collector.drainUntilRegistered()
            assertEquals(SaslOutcome.Success, events.filterIsInstance<IrcEvent.SaslResult>().single().outcome)
            val lines = server.receivedLines.toList()
            assertTrue(lines.indexOf("AUTHENTICATE SCRAM-SHA-256") < lines.indexOf("AUTHENTICATE PLAIN"))
            assertTrue(lines.indexOf("AUTHENTICATE PLAIN") < lines.indexOf("CAP END"))
        }
    }

    @org.junit.jupiter.api.Test
    fun reauthenticatesAfterRegistrationAndOnCapNew() {
        val plainResponder: LoopbackIrcServer.(String) -> Unit = { line ->
            when {
                handleCapBasics(line, "sasl=PLAIN cap-notify") -> Unit
                line == "AUTHENTICATE PLAIN" -> sendLine("AUTHENTICATE +")
                line.startsWith("AUTHENTICATE ") -> sendLine(":srv 903 * :SASL authentication successful")
            }
        }
        withLoopback(config = { saslConfig(it) }, respond = plainResponder) { connection, collector, server ->
            collector.drainUntilRegistered()
            assertTrue(connection.reauthenticate())
            assertEquals(SaslOutcome.Success, collector.awaitInstance<IrcEvent.SaslResult>().outcome)
            assertEquals(2, server.receivedLines.count { it == "AUTHENTICATE PLAIN" })

            server.sendLine(":srv CAP yardhal-test DEL :sasl")
            server.sendLine(":srv NOTICE yardhal-test :marker")
            while (collector.awaitInstance<IrcEvent.MessageReceived>().message.command != "NOTICE") continue
            assertFalse(connection.reauthenticate())

            server.sendLine(":srv CAP yardhal-test NEW :sasl=PLAIN")
            assertEquals(SaslOutcome.Success, collector.awaitInstance<IrcEvent.SaslResult>().outcome)
            assertEquals(3, server.receivedLines.count { it == "AUTHENTICATE PLAIN" })
            assertEquals(1, server.receivedLines.count { it == "CAP END" })
            assertEquals(1, server.receivedLines.count { it == "CAP REQ :sasl" })
        }
    }

    @org.junit.jupiter.api.Test
    fun preAwaySendsAwayBeforeCapEnd() {
        withLoopback(
            config = { plainConfig("127.0.0.1", it, capabilities = setOf("draft/pre-away")).copy(initialAway = "*") },
            respond = { line -> handleCapBasics(line, "draft/pre-away") },
        ) { _, collector, server ->
            collector.drainUntilRegistered()
            val lines = server.receivedLines.toList()
            assertTrue(lines.indexOf("AWAY :*") in 0 until lines.indexOf("CAP END"))
            assertTrue(lines.indexOf("AWAY :*") < lines.indexOfFirst { it.startsWith("USER ") })
            assertEquals(1, lines.count { it.startsWith("AWAY") })
        }
    }

    @org.junit.jupiter.api.Test
    fun initialAwayWithoutPreAwayIsSentAfterWelcome() {
        withLoopback(
            config = { plainConfig("127.0.0.1", it, capabilities = setOf("draft/pre-away")).copy(initialAway = "Gone fishing") },
            respond = { line -> handleCapBasics(line, "server-time") },
        ) { _, collector, server ->
            collector.drainUntilRegistered()
            awaitReceived(server) { it.startsWith("AWAY") }
            val lines = server.receivedLines.toList()
            assertTrue(lines.indexOf("AWAY :Gone fishing") > lines.indexOfFirst { it.startsWith("USER ") })
            assertEquals(1, lines.count { it.startsWith("AWAY") })
        }
    }

    @org.junit.jupiter.api.Test
    fun extendedIsupportRequestsIsupportBeforeCapEnd() {
        withLoopback(
            config = { plainConfig("127.0.0.1", it, capabilities = setOf("batch", "draft/extended-isupport")) },
            respond = { line ->
                when {
                    handleCapBasics(line, "batch draft/extended-isupport") -> Unit
                    line == "ISUPPORT" -> {
                        sendLine(":srv BATCH +isu draft/isupport")
                        sendLine("@batch=isu :srv 005 * NETWORK=Loopback UTF8ONLY :are supported by this server")
                        sendLine(":srv BATCH -isu")
                    }
                }
            },
        ) { _, collector, server ->
            val events = collector.drainUntilRegistered()
            val lines = server.receivedLines.toList()
            assertTrue(lines.indexOf("ISUPPORT") in 0 until lines.indexOf("CAP END"))
            val isupport = events.filterIsInstance<IrcEvent.MessageReceived>().single { it.message.command == "005" }
            assertEquals("isu", isupport.message.tags["batch"])
            assertTrue(isupport.message.parameters.contains("UTF8ONLY"))
        }
    }

    @org.junit.jupiter.api.Test
    fun extendedIsupportNotAcknowledgedSendsNoIsupport() {
        withLoopback(
            config = { plainConfig("127.0.0.1", it, capabilities = setOf("batch", "draft/extended-isupport")) },
            respond = { line -> handleCapBasics(line, "batch") },
        ) { _, collector, server ->
            collector.drainUntilRegistered()
            assertFalse(server.receivedLines.contains("ISUPPORT"))
        }
    }
}
