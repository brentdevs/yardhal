package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class IrcDriverRecoveryTests {
    private fun config(port: Int) = IrcConnectionConfig(
        host = "127.0.0.1", port = port, tls = false, nick = "primary", capabilities = emptySet(), connectTimeoutMillis = 1_000,
    )

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun awaitLine(server: LoopbackIrcServer, predicate: (String) -> Boolean): String = withTimeout(5_000) {
        var found = server.receivedLines.firstOrNull(predicate)
        while (found == null) {
            delay(5)
            found = server.receivedLines.firstOrNull(predicate)
        }
        found
    }

    private suspend fun awaitMarker(collector: EventCollector, marker: String) {
        while (true) {
            val event = collector.awaitInstance<IrcEvent.MessageReceived>()
            if (event.message.parameters.lastOrNull() == marker) return
        }
    }

    private fun cancelProbeDeadlineTask(connection: IrcConnection) {
        val probe = requireNotNull(connection.javaClass.getDeclaredField("pendingProbe").apply {
            isAccessible = true
        }.get(connection))
        val deadlineTask = probe.javaClass.getDeclaredField("deadlineJob").apply {
            isAccessible = true
        }.get(probe) as Job
        deadlineTask.cancel()
    }

    @Test
    fun probeCoalescesAndOnlyMatchingLastPongTokenSucceeds() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { if (it.startsWith("USER ")) server.sendLine(":srv 001 primary :Welcome") }
            val scope = scope()
            val connection = IrcConnection(config(server.port))
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                collector.awaitRegistered()
                val first = async(start = CoroutineStart.UNDISPATCHED) { connection.probe(2_000) }
                val second = async(start = CoroutineStart.UNDISPATCHED) { connection.probe(2_000) }
                val ping = awaitLine(server) { it.startsWith("PING :yardhal-probe-") }
                val token = ping.substringAfter("PING :")
                server.sendLine(":srv PONG $token :unrelated")
                server.sendLine(":srv PONG srv :another-token")
                server.sendLine(":srv NOTICE primary :probe-marker")
                awaitMarker(collector, "probe-marker")
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted)
                assertEquals(1, server.receivedLines.count { it.startsWith("PING :yardhal-probe-") })
                server.sendLine(":srv PONG srv :$token")
                assertTrue(withTimeout(1_000) { first.await() })
                assertTrue(withTimeout(1_000) { second.await() })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test
    fun probeDeadlineIgnoresTrafficAndExpiredRepliesCannotPassNextProbe() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { if (it.startsWith("USER ")) server.sendLine(":srv 001 primary :Welcome") }
            val scope = scope()
            val connection = IrcConnection(config(server.port))
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                collector.awaitRegistered()
                val timedOut = async(start = CoroutineStart.UNDISPATCHED) { connection.probe(150) }
                val expiredToken = awaitLine(server) { it.startsWith("PING :yardhal-probe-") }.substringAfter("PING :")
                server.sendLine(":srv PONG srv :unrelated")
                assertFalse(withTimeout(1_000) { timedOut.await() })
                val fresh = async(start = CoroutineStart.UNDISPATCHED) { connection.probe(2_000) }
                val freshToken = awaitLine(server) {
                    it.startsWith("PING :yardhal-probe-") && it.substringAfter("PING :") != expiredToken
                }.substringAfter("PING :")
                assertNotEquals(expiredToken, freshToken)
                server.sendLine(":srv PONG srv :$expiredToken")
                server.sendLine(":srv NOTICE primary :expired-marker")
                awaitMarker(collector, "expired-marker")
                assertFalse(fresh.isCompleted)
                server.sendLine(":srv PONG srv :$freshToken")
                assertTrue(withTimeout(1_000) { fresh.await() })
                val interrupted = async(start = CoroutineStart.UNDISPATCHED) { connection.probe(5_000) }
                connection.disconnect()
                assertFalse(withTimeout(500) { interrupted.await() })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test
    fun lateMatchingPongFailsCoalescedCallerWhenDeadlineTaskIsDelayed() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { if (it.startsWith("USER ")) server.sendLine(":srv 001 primary :Welcome") }
            val scope = scope()
            val connection = IrcConnection(config(server.port))
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                collector.awaitRegistered()
                val first = async(start = CoroutineStart.UNDISPATCHED) { connection.probe(150) }
                cancelProbeDeadlineTask(connection)
                val coalesced = async(start = CoroutineStart.UNDISPATCHED) { connection.probe(2_000) }
                val expiredToken = awaitLine(server) { it.startsWith("PING :yardhal-probe-") }.substringAfter("PING :")
                assertFalse(withTimeout(1_000) { first.await() })
                assertFalse(coalesced.isCompleted)
                assertEquals(1, server.receivedLines.count { it.startsWith("PING :yardhal-probe-") })
                server.sendLine(":srv PONG srv :$expiredToken")
                server.sendLine(":srv NOTICE primary :late-pong-marker")
                awaitMarker(collector, "late-pong-marker")
                assertFalse(withTimeout(1_000) { coalesced.await() })
                val fresh = async(start = CoroutineStart.UNDISPATCHED) { connection.probe(2_000) }
                val freshToken = awaitLine(server) {
                    it.startsWith("PING :yardhal-probe-") && it.substringAfter("PING :") != expiredToken
                }.substringAfter("PING :")
                server.sendLine(":srv PONG srv :$freshToken")
                assertTrue(withTimeout(1_000) { fresh.await() })
                assertEquals(2, server.receivedLines.count { it.startsWith("PING :yardhal-probe-") })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test
    fun rejectedSaslCredentialsCannotReleaseRegistrationAndAreNotDisplayed() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                when {
                    line.startsWith("CAP LS") -> server.sendLine(":srv CAP * LS :sasl=PLAIN,SCRAM-SHA-256")
                    line.startsWith("CAP REQ") -> server.sendLine(":srv CAP * ACK :sasl")
                    line == "AUTHENTICATE PLAIN" -> server.sendLine("AUTHENTICATE +")
                    line.startsWith("AUTHENTICATE ") -> {
                        server.sendLine(":srv 904 * :Rejected secret-password ${line.substringAfter(' ')}")
                        server.sendLine(":srv 001 primary :Unauthenticated welcome")
                    }
                }
            }
            val tapped = CopyOnWriteArrayList<String>()
            val scope = scope()
            val connection = IrcConnection(
                config(server.port).copy(saslAuthcid = "account", saslPassword = "secret-password", saslMode = "PLAIN"),
                rawTap = { _, line -> tapped += line },
            )
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                val events = collector.drainUntilDisconnected()
                val failure = assertIs<SaslOutcome.Failure>(events.filterIsInstance<IrcEvent.SaslResult>().single().outcome)
                assertEquals(904, failure.numeric)
                assertFalse("secret-password" in failure.description)
                assertIs<AuthenticationRejectedException>((events.last() as IrcEvent.Disconnected).cause)
                assertFalse(events.any { it is IrcEvent.Registered })
                assertFalse(server.receivedLines.any { it == "CAP END" || it.startsWith("NICK ") || it.startsWith("USER ") })
                val payload = server.receivedLines.single { it.startsWith("AUTHENTICATE ") && it != "AUTHENTICATE PLAIN" }.substringAfter(' ')
                assertFalse(tapped.any { "secret-password" in it || payload in it })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test
    fun absentDeclinedAndIncompatibleRequiredSaslAllFailClosed() = runBlocking {
        for (offer in listOf("server-time", "sasl=PLAIN", "sasl=SCRAM-SHA-256")) {
            LoopbackIrcServer().use { server ->
                server.start()
                server.lineListener = { line ->
                    when {
                        line.startsWith("CAP LS") -> server.sendLine(":srv CAP * LS :$offer")
                        line.startsWith("CAP REQ") -> server.sendLine(":srv CAP * ${if (offer == "sasl=PLAIN") "NAK" else "ACK"} :sasl")
                    }
                }
                val scope = scope()
                val connection = IrcConnection(config(server.port).copy(saslAuthcid = "account", saslPassword = "secret", saslMode = "PLAIN"))
                try {
                    val collector = EventCollector(scope, connection.events)
                    connection.start()
                    val events = collector.drainUntilDisconnected()
                    assertIs<AuthenticationRejectedException>((events.last() as IrcEvent.Disconnected).cause)
                    assertFalse(events.any { it is IrcEvent.Registered })
                    assertFalse(server.receivedLines.any { it == "CAP END" || it.startsWith("USER ") || it.startsWith("AUTHENTICATE ") })
                } finally {
                    connection.disconnect()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun serverPasswordRejectionCannotBeOverriddenByFollowingWelcome() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                if (line.startsWith("PASS ")) {
                    server.sendLine(":srv 464 primary :Wrong password server-secret")
                    server.sendLine(":srv 001 primary :Welcome without authentication")
                }
            }
            val scope = scope()
            val tapped = CopyOnWriteArrayList<String>()
            val connection = IrcConnection(config(server.port).copy(serverPassword = "server-secret"), rawTap = { _, line -> tapped += line })
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                val events = collector.drainUntilDisconnected()
                val failure = assertIs<AuthenticationRejectedException>((events.last() as IrcEvent.Disconnected).cause)
                assertFalse("server-secret" in failure.message.orEmpty())
                assertFalse(events.any { it is IrcEvent.Registered })
                assertFalse(tapped.any { "server-secret" in it })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test
    fun serverPasswordPreservesSpacesAndLeadingColonAsOneParameter() = runBlocking {
        for (password in listOf("server secret with spaces", ":server-secret")) {
            LoopbackIrcServer().use { server ->
                server.start()
                val connection = IrcConnection(config(server.port).copy(serverPassword = password))
                try {
                    connection.start()
                    val line = awaitLine(server) { it.startsWith("PASS ") }
                    assertEquals(listOf(password), requireNotNull(IrcMessage.parse(line)).parameters)
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    @Test
    fun invalidServerPasswordCannotInjectCommandsOrOpenTransport() = runBlocking {
        for (password in listOf("secret\r\nJOIN #guest", "secret\u0000")) {
            LoopbackIrcServer().use { server ->
                server.start()
                server.lineListener = { line ->
                    if (line.startsWith("PASS ")) server.sendLine(":srv 464 primary :Invalid password")
                }
                val scope = scope()
                val connection = IrcConnection(config(server.port).copy(serverPassword = password))
                try {
                    val collector = EventCollector(scope, connection.events)
                    connection.start()
                    val events = collector.drainUntilDisconnected()
                    assertIs<AuthenticationRejectedException>((events.last() as IrcEvent.Disconnected).cause)
                    assertFalse(events.any { it == IrcEvent.ConnectionOpened || it is IrcEvent.Registered })
                    assertEquals(emptyList(), server.receivedLines)
                } finally {
                    connection.disconnect()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun nicknameCollisionsUseConfiguredAlternatesBeforeFallback() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                if (line.startsWith("NICK ")) {
                    val nickname = line.substringAfter(' ')
                    if (nickname == "primary_") server.sendLine(":srv 001 $nickname :Welcome")
                    else server.sendLine(":srv 433 * $nickname :Already in use")
                }
            }
            val scope = scope()
            val connection = IrcConnection(config(server.port).copy(alternateNicks = listOf("first-alt", "second-alt")))
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                assertEquals("primary_", collector.awaitRegistered().nickname)
                assertEquals(listOf("NICK primary", "NICK first-alt", "NICK second-alt", "NICK primary_"), server.receivedLines.filter { it.startsWith("NICK ") })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test
    fun nickServCommandsAndConfiguredAndEncodedEchoesAreRedactedWithoutChangingProtocol() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                when {
                    line.startsWith("USER ") -> server.sendLine(":srv 001 primary :Welcome")
                    line.startsWith("PRIVMSG AccountService ") -> {
                        server.sendLine(":primary!u@h $line")
                        server.sendLine(":srv NOTICE primary :Echo nickserv-secret server-secret proxy-secret sasl-secret")
                    }
                    line.startsWith("AUTHENTICATE ") -> server.sendLine(":srv NOTICE primary :Echo ${line.substringAfter(' ')}")
                }
            }
            val scope = scope()
            val tapped = CopyOnWriteArrayList<String>()
            val secrets = setOf("nickserv-secret", "server-secret", "proxy-secret", "sasl-secret")
            val connection = IrcConnection(
                config(server.port).copy(knownSecrets = secrets, nickServService = "AccountService"),
                rawTap = { _, line -> tapped += line },
            )
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                collector.awaitRegistered()
                connection.sendLine("PRIVMSG AccountService :IDENTIFY account nickserv-secret")
                awaitMarker(collector, "Echo nickserv-secret server-secret proxy-secret sasl-secret")
                val token = "YWNjb3VudC1jcmVkZW50aWFscw=="
                connection.sendLine("AUTHENTICATE $token")
                awaitMarker(collector, "Echo $token")
                assertEquals("Echo <redacted>", connection.redactPresentation("Echo $token"))
                assertTrue(tapped.any { "IDENTIFY <redacted>" in it })
                assertFalse(tapped.any { line -> secrets.any { it in line } || token in line })
                assertFalse(secrets.any { it in config(server.port).copy(serverPassword = it, knownSecrets = secrets).toString() })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test
    fun offlineLaunchPausesAndRepeatedNudgesNeverCreateParallelConnections() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { if (it.startsWith("USER ")) server.sendLine(":srv 001 primary :Welcome") }
            val scope = scope()
            val calls = AtomicInteger()
            val reconnector = IrcReconnector(scope, connectionFactory = { calls.incrementAndGet(); IrcConnection(config(server.port)) })
            try {
                val collector = EventCollector(scope, reconnector.events.map { it.event })
                reconnector.setNetworkAvailable(false)
                reconnector.start()
                repeat(10) { reconnector.nudge() }
                withTimeout(1_000) { reconnector.state.first { it == ReconnectState.Paused } }
                delay(75)
                assertEquals(0, calls.get())
                reconnector.setNetworkAvailable(true)
                repeat(10) { reconnector.nudge(); reconnector.start() }
                collector.awaitRegistered()
                assertEquals(1, calls.get())
                val original = requireNotNull(reconnector.currentConnection)
                reconnector.setNetworkAvailable(false)
                assertFalse(withTimeout(500) { original.probe() })
                delay(75)
                assertEquals(1, calls.get())
                reconnector.setNetworkAvailable(true)
                collector.awaitRegistered()
                assertEquals(2, calls.get())
            } finally {
                reconnector.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun nudgeInterruptsLongBackoffAndRestartsOrdinaryExhaustion() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { if (it.startsWith("USER ")) server.sendLine(":srv 001 primary :Recovered") }
            val scope = scope()
            val calls = AtomicInteger()
            val ready = AtomicInteger()
            val reconnector = IrcReconnector(
                scope, ReconnectPolicy(initialDelayMillis = 30_000, maxDelayMillis = 30_000, jitterRatio = 0.0, maxAttempts = 2),
                connectionFactory = {
                    calls.incrementAndGet()
                    if (ready.get() == 0) throw IOException("temporary factory failure")
                    IrcConnection(config(server.port))
                },
            )
            try {
                val collector = EventCollector(scope, reconnector.events.map { it.event })
                reconnector.start()
                val failure = collector.awaitInstance<IrcEvent.Disconnected>()
                assertIs<IOException>(failure.cause)
                withTimeout(1_000) { reconnector.state.first { it is ReconnectState.BackingOff } }
                reconnector.nudge()
                collector.awaitInstance<IrcEvent.Disconnected>()
                withTimeout(1_000) { reconnector.state.first { it is ReconnectState.BackingOff } }
                ready.set(1)
                repeat(8) { reconnector.nudge() }
                withTimeout(2_000) { collector.awaitRegistered() }
                assertEquals(3, calls.get())
            } finally {
                reconnector.stop()
                scope.cancel()
            }
            val exhaustedScope = scope()
            val exhaustedCalls = AtomicInteger()
            val exhausted = IrcReconnector(
                exhaustedScope, ReconnectPolicy(initialDelayMillis = 10, maxDelayMillis = 10, jitterRatio = 0.0, maxAttempts = 1),
                connectionFactory = { exhaustedCalls.incrementAndGet(); throw IOException("unreachable") },
            )
            try {
                val collector = EventCollector(exhaustedScope, exhausted.events.map { it.event })
                exhausted.start()
                collector.awaitInstance<IrcEvent.Disconnected>()
                withTimeout(1_000) { exhausted.state.first { it == ReconnectState.Stopped } }
                exhausted.nudge()
                collector.awaitInstance<IrcEvent.Disconnected>()
                assertEquals(2, exhaustedCalls.get())
            } finally {
                exhausted.stop()
                exhaustedScope.cancel()
            }
        }
    }

    @Test
    fun authenticationFailureStopsRetriesAndNudgesUntilExplicitRestart() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { if (it.startsWith("PASS ")) server.sendLine(":srv 464 primary :Password rejected") }
            val scope = scope()
            val calls = AtomicInteger()
            val reconnector = IrcReconnector(
                scope, ReconnectPolicy(initialDelayMillis = 10, maxDelayMillis = 20, jitterRatio = 0.0),
                connectionFactory = { calls.incrementAndGet(); IrcConnection(config(server.port).copy(serverPassword = "secret")) },
            )
            try {
                val collector = EventCollector(scope, reconnector.events.map { it.event })
                reconnector.start()
                assertIs<AuthenticationRejectedException>(collector.awaitInstance<IrcEvent.Disconnected>().cause)
                withTimeout(1_000) { reconnector.state.first { it == ReconnectState.Stopped } }
                repeat(8) { reconnector.nudge() }
                reconnector.setNetworkAvailable(false)
                reconnector.setNetworkAvailable(true)
                delay(75)
                assertEquals(1, calls.get())
                reconnector.stop()
                reconnector.start()
                assertIs<AuthenticationRejectedException>(collector.awaitInstance<IrcEvent.Disconnected>().cause)
                assertEquals(2, calls.get())
            } finally {
                reconnector.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun retiredReaderCannotUpgradeOrPublishIntoReplacementConnection() = runBlocking {
        LoopbackIrcServer().use { retiredServer ->
            LoopbackIrcServer().use { replacementServer ->
                retiredServer.start()
                replacementServer.start()
                for (server in listOf(retiredServer, replacementServer)) {
                    server.lineListener = { line ->
                        when {
                            line.startsWith("CAP LS") -> server.sendLine(":srv CAP * LS :")
                            line.startsWith("USER ") -> server.sendLine(":srv 001 primary :Welcome")
                        }
                    }
                }
                val scope = scope()
                val target = AtomicInteger(retiredServer.port)
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val finished = CompletableDeferred<Unit>()
                val upgrades = AtomicInteger()
                val seen = CopyOnWriteArrayList<IrcEvent>()
                val reconnector = IrcReconnector(scope, connectionFactory = {
                    val old = target.get() == retiredServer.port
                    IrcConnection(
                        config(target.get()).copy(capabilities = setOf("server-time")),
                        rawTap = { outbound, line ->
                            if (old && !outbound && "sts=port=" in line) {
                                entered.complete(Unit)
                                release.await(5, TimeUnit.SECONDS)
                                finished.complete(Unit)
                            }
                        },
                        onStsUpgrade = { upgrades.incrementAndGet() },
                    )
                })
                try {
                    val collector = EventCollector(scope, reconnector.events.map { it.event })
                    val watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) { reconnector.events.collect { seen += it.event } }
                    reconnector.start()
                    collector.awaitRegistered()
                    val retired = requireNotNull(reconnector.currentConnection)
                    retiredServer.sendLine(":srv CAP primary NEW :sts=port=6697")
                    withTimeout(1_000) { entered.await() }
                    reconnector.setNetworkAvailable(false)
                    target.set(replacementServer.port)
                    reconnector.setNetworkAvailable(true)
                    collector.awaitRegistered()
                    val replacement = requireNotNull(reconnector.currentConnection)
                    assertTrue(retired !== replacement)
                    release.countDown()
                    withTimeout(1_000) { finished.await() }
                    retired.disconnect()
                    replacementServer.sendLine(":srv NOTICE primary :replacement-marker")
                    awaitMarker(collector, "replacement-marker")
                    assertTrue(replacement.isRegistered)
                    assertEquals(0, upgrades.get())
                    assertFalse(seen.filterIsInstance<IrcEvent.MessageReceived>().any { "sts=" in it.message.toWire() })
                    watcher.cancel()
                } finally {
                    release.countDown()
                    reconnector.stop()
                    scope.cancel()
                }
            }
        }
    }
}
