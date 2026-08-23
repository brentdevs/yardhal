package dev.brentdevs.yardhal.core.client

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
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
                    line.startsWith("CAP LS") -> server.sendLine(":srv CAP * LS :sasl server-time echo-message")
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
                val connection = IrcConnection(config)
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
}
