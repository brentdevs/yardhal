package dev.brentdevs.yardhal.core.client

import java.util.concurrent.atomic.AtomicInteger
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
        scope.launch(start = CoroutineStart.UNDISPATCHED) { events.collect { channel.trySend(it) } }
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

    suspend fun drainUntilDisconnected(): List<IrcEvent> {
        val seen = ArrayList<IrcEvent>()
        while (true) {
            val event = await()
            seen += event
            if (event is IrcEvent.Disconnected) return seen
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

    @org.junit.jupiter.api.Test
    fun inboundBudgetsPreserveMaximumFrameAndNeverDispatchPartialMessagesOrPings() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.lineListener = { line ->
                when {
                    line.startsWith("USER ") -> server.sendLine(":srv 001 yardhal-test :Welcome")
                    line == "PONG :framing-recovery" -> server.sendLine(":srv NOTICE yardhal-test :framing-barrier")
                }
            }
            server.start()
            val scope = newScope()
            val tapped = java.util.concurrent.CopyOnWriteArrayList<String>()
            val connection = IrcConnection(
                plainConfig("127.0.0.1", server.port),
                rawTap = { outbound, line -> if (!outbound) tapped.add(line) },
            )
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                collector.awaitRegistered()
                assertEquals(1, collector.awaitInstance<IrcEvent.MessageReceived>().message.numeric)
                tapped.clear()
                val tags = "@server=" + "s".repeat(4087) + ";+client=" + "c".repeat(4086) + " "
                val prefix = ":sender!u@h PRIVMSG #a :"
                val body = "b".repeat(510 - prefix.toByteArray().size)
                val legal = tags + prefix + body
                assertEquals(8703, "$legal\r\n".toByteArray().size)
                server.sendLine(legal)
                server.sendLine(prefix + body + "x")
                server.sendLine("@t=x " + prefix + body + "x")
                server.sendLine(tags.dropLast(1) + "x TAGMSG #a")
                server.sendLine("@t=missing-separator")
                server.sendLine("PING :" + "p".repeat(511 - "PING :".length))
                server.sendLine("PING :framing-recovery")
                val received = mutableListOf<dev.brentdevs.yardhal.core.protocol.IrcMessage>()
                val rejected = mutableListOf<LineRejection>()
                while (true) {
                    when (val event = collector.await()) {
                        is IrcEvent.FrameRejected -> rejected += event.reason
                        is IrcEvent.MessageReceived -> {
                            received += event.message
                            if (event.message.command == "NOTICE" && event.message.parameters.lastOrNull() == "framing-barrier") break
                        }
                        is IrcEvent.Disconnected -> error("Disconnected before recovery: ${event.cause}")
                        else -> Unit
                    }
                }
                assertEquals(listOf("PRIVMSG", "PING", "NOTICE"), received.map { it.command })
                val message = received.first()
                assertEquals("sender", message.prefix?.nick)
                assertEquals(listOf("#a", body), message.parameters)
                assertEquals(mapOf("server" to "s".repeat(4087), "+client" to "c".repeat(4086)), message.tags)
                assertEquals(listOf(
                    LineRejection.BASE_TOO_LONG,
                    LineRejection.BASE_TOO_LONG,
                    LineRejection.TAGS_TOO_LONG,
                    LineRejection.MISSING_TAG_SEPARATOR,
                    LineRejection.BASE_TOO_LONG,
                ), rejected)
                assertEquals(listOf(legal, "PING :framing-recovery", ":srv NOTICE yardhal-test :framing-barrier"), tapped.toList())
                assertEquals(listOf("PONG :framing-recovery"), server.receivedLines.filter { it.startsWith("PONG ") })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
        Unit
    }

    @org.junit.jupiter.api.Test
    fun fragmentedHugeTaggedFramesRecoverAndOversizedEofRemainderNeverDispatches() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.lineListener = { line ->
                if (line.startsWith("USER ")) server.sendLine(":srv 001 yardhal-test :Welcome")
            }
            server.start()
            val scope = newScope()
            val tapped = java.util.concurrent.CopyOnWriteArrayList<String>()
            val connection = IrcConnection(
                plainConfig("127.0.0.1", server.port),
                rawTap = { outbound, line -> if (!outbound) tapped.add(line) },
            )
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                collector.awaitRegistered()
                assertEquals(1, collector.awaitInstance<IrcEvent.MessageReceived>().message.numeric)
                tapped.clear()
                val fragment = ByteArray(4096) { 'x'.code.toByte() }
                server.sendBytes("@t=x :sender!u@h PRIVMSG #a :".toByteArray())
                repeat(512) { server.sendBytes(fragment) }
                server.sendBytes("\r".toByteArray())
                server.sendBytes("\n".toByteArray())
                server.sendLine(":srv NOTICE yardhal-test :framing-recovered")
                val received = mutableListOf<dev.brentdevs.yardhal.core.protocol.IrcMessage>()
                val rejected = mutableListOf<LineRejection>()
                while (true) {
                    when (val event = collector.await()) {
                        is IrcEvent.FrameRejected -> rejected += event.reason
                        is IrcEvent.MessageReceived -> {
                            received += event.message
                            if (event.message.parameters.lastOrNull() == "framing-recovered") break
                        }
                        is IrcEvent.Disconnected -> error("Disconnected before recovery: ${event.cause}")
                        else -> Unit
                    }
                }
                assertEquals(listOf("NOTICE"), received.map { it.command })
                assertEquals(listOf(LineRejection.BASE_TOO_LONG), rejected)
                server.sendBytes("@t=".toByteArray())
                repeat(512) { server.sendBytes(fragment) }
                server.finishSending()
                val terminal = collector.drainUntilDisconnected()
                assertTrue(terminal.none { it is IrcEvent.MessageReceived })
                assertEquals(listOf(LineRejection.TAGS_TOO_LONG), terminal.filterIsInstance<IrcEvent.FrameRejected>().map { it.reason })
                assertEquals(listOf(":srv NOTICE yardhal-test :framing-recovered"), tapped.toList())
                assertTrue(server.receivedLines.none { it.startsWith("PONG ") })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
        Unit
    }

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
                            if (event.event is IrcEvent.Registered) {
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
            val inbound = events.filterIsInstance<IrcEvent.MessageReceived>().map { it.message }
            assertTrue(inbound.any { it.command == "AUTHENTICATE" })
            assertTrue(inbound.any { it.numeric == 903 })
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
    fun unicodeScramCredentialsAuthenticateAgainstPreparedLoopbackKeys() {
        val fixture = ScramServerFixture(password = "p\u00E9ncil")
        withLoopback(
            config = {
                saslConfig(it).copy(
                    saslAuthcid = "\u2168\u00AD\uFF1D\uFF0C\uD835\uDC00",
                    saslPassword = "pe\u0301ncil\u00AD",
                )
            },
            respond = scramResponder(fixture, tamperSignature = false),
        ) { _, collector, server ->
            val events = collector.drainUntilRegistered()
            assertEquals(SaslOutcome.Success, events.filterIsInstance<IrcEvent.SaslResult>().single().outcome)
            assertEquals("IX=,A", fixture.clientUsername)
            assertTrue(fixture.clientProofValid)
            assertFalse("AUTHENTICATE PLAIN" in server.receivedLines)
            val inbound = events.filterIsInstance<IrcEvent.MessageReceived>().map { it.message }
            assertTrue(inbound.any { it.command == "AUTHENTICATE" })
            assertTrue(inbound.any { it.numeric == 903 })
        }
    }

    @org.junit.jupiter.api.Test
    fun saslPreparationFailurePreventsRegistration() {
        withLoopback(
            config = { saslConfig(it).copy(saslPassword = "password\uD83D\uDE00") },
            respond = { line -> handleCapBasics(line, "sasl=PLAIN,SCRAM-SHA-256 cap-notify") },
        ) { _, collector, server ->
            val events = collector.drainUntilDisconnected()
            val failure = events.filterIsInstance<IrcEvent.SaslResult>().single().outcome
            assertTrue(failure is SaslOutcome.Failure && failure.description.contains("unassigned"))
            assertFalse(events.any { it is IrcEvent.Registered })
            assertTrue((events.last() as IrcEvent.Disconnected).cause is AuthenticationRejectedException)
            assertFalse(server.receivedLines.any { it == "CAP END" || it.startsWith("USER ") || it.startsWith("AUTHENTICATE ") })
        }
    }

    @org.junit.jupiter.api.Test
    fun scramBadServerSignaturePreventsUnauthenticatedRegistration() {
        val fixture = ScramServerFixture(password = "sesame")
        withLoopback(
            config = { saslConfig(it) },
            respond = scramResponder(fixture, tamperSignature = true),
        ) { _, collector, server ->
            val events = collector.drainUntilDisconnected()
            val failure = events.filterIsInstance<IrcEvent.SaslResult>().single().outcome
            assertTrue(failure is SaslOutcome.Failure && failure.description.contains("signature"))
            assertFalse(events.any { it is IrcEvent.Registered })
            assertTrue((events.last() as IrcEvent.Disconnected).cause is AuthenticationRejectedException)
            assertFalse(server.receivedLines.any { it == "CAP END" || it.startsWith("USER ") || it == "AUTHENTICATE PLAIN" })
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

    private suspend fun assertRegisteredSessionResponds(connection: IrcConnection, collector: EventCollector) {
        connection.sendLine("PING :still-registered")
        while (true) {
            when (val event = collector.await()) {
                is IrcEvent.MessageReceived -> if (event.message.command == "PONG") {
                    assertEquals("still-registered", event.message.parameters.last())
                    assertTrue(connection.isRegistered)
                    return
                }
                is IrcEvent.Disconnected -> error("Established session disconnected: ${event.cause}")
                else -> Unit
            }
        }
    }

    private suspend fun awaitSessionMessage(collector: EventCollector, predicate: (dev.brentdevs.yardhal.core.protocol.IrcMessage) -> Boolean): dev.brentdevs.yardhal.core.protocol.IrcMessage {
        while (true) {
            when (val event = collector.await()) {
                is IrcEvent.MessageReceived -> if (predicate(event.message)) return event.message
                is IrcEvent.Disconnected -> error("Established session disconnected: ${event.cause}")
                else -> Unit
            }
        }
    }

    @org.junit.jupiter.api.Test
    fun operAndPassFailuresAfterRegistrationRemainOrdinaryEvents() {
        for (password in listOf(null, "server-secret")) {
            withLoopback(
                config = { plainConfig("127.0.0.1", it).copy(serverPassword = password) },
                respond = { line ->
                    when {
                        line.startsWith("USER ") -> sendLine(":srv 001 yardhal-test :Welcome")
                        line.startsWith("OPER ") -> sendLine(":srv 464 yardhal-test :Password incorrect")
                        line.startsWith("PING ") -> sendLine(":srv PONG srv :${line.substringAfter(':')}")
                    }
                },
            ) { connection, collector, server ->
                collector.drainUntilRegistered()
                connection.sendLine("OPER admin incorrect")
                assertEquals(464, awaitSessionMessage(collector) { it.numeric == 464 }.numeric)
                for (line in listOf(":srv 461 yardhal-test PASS :Not enough parameters", ":srv FAIL PASS INVALID_PASSWORD :Password rejected")) {
                    server.sendLine(line)
                    assertEquals(line, collector.awaitInstance<IrcEvent.MessageReceived>().message.toWire())
                }
                assertRegisteredSessionResponds(connection, collector)
            }
        }
    }

    @org.junit.jupiter.api.Test
    fun initialPassFailuresCannotBeOverriddenByWelcome() {
        for (failure in listOf(":srv 464 yardhal-test :Password incorrect", ":srv 461 yardhal-test PASS :Not enough parameters", ":srv FAIL PASS INVALID_PASSWORD :Password rejected")) {
            withLoopback(
                config = { plainConfig("127.0.0.1", it).copy(serverPassword = "server-secret") },
                respond = { line ->
                    if (line.startsWith("PASS ")) {
                        sendLine(failure)
                        sendLine(":srv 001 yardhal-test :Welcome")
                    }
                },
            ) { _, collector, _ ->
                val events = collector.drainUntilDisconnected()
                assertIs<AuthenticationRejectedException>((events.last() as IrcEvent.Disconnected).cause)
                assertFalse(events.any { it is IrcEvent.Registered })
            }
        }
    }

    private fun exerciseRegisteredSaslFailures(reoffer: Boolean) {
        for ((numeric, description) in listOf(902 to "Account locked", 904 to "Bad credentials", 905 to "Response too long", 906 to "Aborted", 907 to "Already authenticated", 904 to "Rate limit exceeded")) {
            val attempts = AtomicInteger()
            val tapped = java.util.concurrent.CopyOnWriteArrayList<String>()
            withLoopback(
                config = { saslConfig(it).copy(saslMode = "PLAIN") },
                respond = { line ->
                    when {
                        handleCapBasics(line, "sasl=PLAIN cap-notify") -> Unit
                        line == "AUTHENTICATE PLAIN" -> {
                            if (attempts.incrementAndGet() == 1) sendLine("AUTHENTICATE +")
                            else sendLine(":srv $numeric yardhal-test :$description sesame")
                        }
                        line.startsWith("AUTHENTICATE ") -> sendLine(":srv 903 * :SASL authentication successful")
                        line.startsWith("PING ") -> sendLine(":srv PONG srv :${line.substringAfter(':')}")
                    }
                },
                rawTap = { _, line -> tapped += line },
            ) { connection, collector, server ->
                collector.drainUntilRegistered()
                if (reoffer) {
                    server.sendLine(":srv CAP yardhal-test DEL :sasl")
                    server.sendLine(":srv CAP yardhal-test NEW :sasl=PLAIN")
                } else {
                    assertTrue(connection.reauthenticate())
                }
                val failure = assertIs<SaslOutcome.Failure>(collector.awaitInstance<IrcEvent.SaslResult>().outcome)
                assertEquals(numeric, failure.numeric)
                assertFalse("sesame" in failure.description)
                assertEquals(numeric, awaitSessionMessage(collector) { it.numeric == numeric }.numeric)
                assertRegisteredSessionResponds(connection, collector)
                assertEquals(1, server.receivedLines.count { it == "CAP END" })
                assertFalse(tapped.any { "sesame" in it })
                assertTrue(connection.reauthenticate())
                assertIs<SaslOutcome.Failure>(collector.awaitInstance<IrcEvent.SaslResult>().outcome)
                assertRegisteredSessionResponds(connection, collector)
            }
        }
    }

    @org.junit.jupiter.api.Test
    fun manualReauthenticationFailuresRetainRegisteredSession() {
        exerciseRegisteredSaslFailures(reoffer = false)
    }

    @org.junit.jupiter.api.Test
    fun capNewAuthenticationFailuresRetainRegisteredSession() {
        exerciseRegisteredSaslFailures(reoffer = true)
    }

    @org.junit.jupiter.api.Test
    fun initialSaslRejectionsCannotRegisterAsGuest() {
        for (numeric in listOf(902, 904, 905, 906, 907)) {
            withLoopback(
                config = { saslConfig(it).copy(saslMode = "PLAIN") },
                respond = { line ->
                    when {
                        handleCapBasics(line, "sasl=PLAIN cap-notify") -> Unit
                        line == "AUTHENTICATE PLAIN" -> sendLine("AUTHENTICATE +")
                        line.startsWith("AUTHENTICATE ") -> {
                            sendLine(":srv $numeric * :Authentication rejected")
                            sendLine(":srv 001 yardhal-test :Guest welcome")
                        }
                    }
                },
            ) { _, collector, server ->
                val events = collector.drainUntilDisconnected()
                assertEquals(numeric, assertIs<SaslOutcome.Failure>(events.filterIsInstance<IrcEvent.SaslResult>().single().outcome).numeric)
                assertIs<AuthenticationRejectedException>((events.last() as IrcEvent.Disconnected).cause)
                assertFalse(events.any { it is IrcEvent.Registered })
                val credentials = server.receivedLines.single { it.startsWith("AUTHENTICATE ") && it != "AUTHENTICATE PLAIN" }.substringAfter(' ')
                assertEquals("\u0000jilles\u0000sesame", java.util.Base64.getDecoder().decode(credentials).toString(Charsets.UTF_8))
                assertFalse(server.receivedLines.any { it == "CAP END" || it.startsWith("NICK ") || it.startsWith("USER ") })
            }
        }
    }

    @org.junit.jupiter.api.Test
    fun initialMissingOrRejectedSaslCapabilityPreventsRegistration() {
        for (offered in listOf(false, true)) {
            withLoopback(
                config = { saslConfig(it) },
                respond = { line ->
                    when {
                        line.startsWith("CAP LS") -> sendLine(":srv CAP * LS :${if (offered) "sasl=PLAIN" else "server-time"}")
                        line.startsWith("CAP REQ") -> sendLine(":srv CAP * NAK :sasl")
                    }
                },
            ) { _, collector, server ->
                val events = collector.drainUntilDisconnected()
                assertIs<SaslOutcome.Failure>(events.filterIsInstance<IrcEvent.SaslResult>().single().outcome)
                assertIs<AuthenticationRejectedException>((events.last() as IrcEvent.Disconnected).cause)
                assertFalse(events.any { it is IrcEvent.Registered })
                assertFalse(server.receivedLines.any { it == "CAP END" || it.startsWith("NICK ") || it.startsWith("USER ") })
            }
        }
    }

    @org.junit.jupiter.api.Test
    fun saslDeletionDoesNotRejectUnrelatedRuntimeCapabilityAck() {
        withLoopback(
            config = { saslConfig(it, capabilities = setOf("sasl", "cap-notify", "server-time")) },
            respond = { line ->
                when {
                    handleCapBasics(line, "sasl=PLAIN cap-notify") -> Unit
                    line == "AUTHENTICATE PLAIN" -> sendLine("AUTHENTICATE +")
                    line.startsWith("AUTHENTICATE ") -> sendLine(":srv 903 * :SASL authentication successful")
                    line.startsWith("PING ") -> sendLine(":srv PONG srv :${line.substringAfter(':')}")
                }
            },
        ) { connection, collector, server ->
            collector.drainUntilRegistered()
            server.sendLine(":srv CAP yardhal-test DEL :sasl")
            server.sendLine(":srv CAP yardhal-test NEW :server-time")
            while (true) {
                val event = collector.await()
                assertFalse(event is IrcEvent.Disconnected || event is IrcEvent.SaslResult)
                if (event is IrcEvent.CapabilitiesNegotiated && "server-time" in event.capabilities) {
                    assertFalse("sasl" in event.capabilities)
                    break
                }
            }
            assertRegisteredSessionResponds(connection, collector)
            assertEquals(1, server.receivedLines.count { it == "CAP END" })
        }
    }

    @org.junit.jupiter.api.Test
    fun withdrawingSaslDuringManualReauthenticationKeepsSessionHealthy() {
        val attempts = AtomicInteger()
        withLoopback(
            config = { saslConfig(it).copy(saslMode = "PLAIN") },
            respond = { line ->
                when {
                    handleCapBasics(line, "sasl=PLAIN cap-notify") -> Unit
                    line == "AUTHENTICATE PLAIN" -> if (attempts.incrementAndGet() == 1) sendLine("AUTHENTICATE +")
                    line.startsWith("AUTHENTICATE ") -> sendLine(":srv 903 * :SASL authentication successful")
                    line.startsWith("PING ") -> sendLine(":srv PONG srv :${line.substringAfter(':')}")
                }
            },
        ) { connection, collector, server ->
            collector.drainUntilRegistered()
            assertTrue(connection.reauthenticate())
            server.sendLine(":srv CAP yardhal-test DEL :sasl")
            val failure = assertIs<SaslOutcome.Failure>(collector.awaitInstance<IrcEvent.SaslResult>().outcome)
            assertEquals(0, failure.numeric)
            assertFalse(connection.reauthenticate())
            assertRegisteredSessionResponds(connection, collector)
        }
    }

    @org.junit.jupiter.api.Test
    fun incompatibleReofferedSaslMechanismDoesNotCloseRegisteredSession() {
        withLoopback(
            config = { saslConfig(it).copy(saslMode = "PLAIN") },
            respond = { line ->
                when {
                    handleCapBasics(line, "sasl=PLAIN cap-notify") -> Unit
                    line == "AUTHENTICATE PLAIN" -> sendLine("AUTHENTICATE +")
                    line.startsWith("AUTHENTICATE ") -> sendLine(":srv 903 * :SASL authentication successful")
                    line.startsWith("PING ") -> sendLine(":srv PONG srv :${line.substringAfter(':')}")
                }
            },
        ) { connection, collector, server ->
            collector.drainUntilRegistered()
            server.sendLine(":srv CAP yardhal-test DEL :sasl")
            server.sendLine(":srv CAP yardhal-test NEW :sasl=EXTERNAL")
            val failure = assertIs<SaslOutcome.Failure>(collector.awaitInstance<IrcEvent.SaslResult>().outcome)
            assertEquals(0, failure.numeric)
            assertRegisteredSessionResponds(connection, collector)
            assertEquals(1, server.receivedLines.count { it == "CAP END" })
            assertEquals(1, server.receivedLines.count { it.startsWith("AUTHENTICATE PLAIN") })
        }
    }

    @org.junit.jupiter.api.Test
    fun missingRequiredSaslCredentialsNeverStartTransport() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            for ((account, password) in listOf(null to null, "" to "sesame", "jilles" to null)) {
                val scope = newScope()
                val connection = IrcConnection(plainConfig("127.0.0.1", server.port, saslAuthcid = account, saslPassword = password).copy(saslMode = "PLAIN"))
                try {
                    val collector = EventCollector(scope, connection.events)
                    connection.start()
                    val events = collector.drainUntilDisconnected()
                    assertIs<AuthenticationRejectedException>((events.last() as IrcEvent.Disconnected).cause)
                    assertFalse(events.any { it == IrcEvent.ConnectionOpened || it is IrcEvent.Registered })
                    assertFalse(server.awaitClient(timeoutSeconds = 0))
                    assertTrue(server.receivedLines.isEmpty())
                } finally {
                    connection.disconnect()
                    scope.cancel()
                }
            }
        }
    }

    @org.junit.jupiter.api.Test
    fun invalidRealNameCannotInjectUserCommandsOrStartTransport() {
        LoopbackIrcServer().use { server ->
            server.start()
            for (realName in listOf("Name\rJOIN #guest", "Name\nJOIN #guest", "Name\r\nJOIN #guest", "Name\u0000")) {
                assertFailsWith<IllegalArgumentException> {
                    IrcConnection(plainConfig("127.0.0.1", server.port).copy(realName = realName)).start()
                }
            }
            assertFalse(server.awaitClient(timeoutSeconds = 0))
            assertTrue(server.receivedLines.isEmpty())
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
