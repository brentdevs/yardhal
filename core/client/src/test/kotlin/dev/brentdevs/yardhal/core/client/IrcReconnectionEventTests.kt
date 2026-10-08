package dev.brentdevs.yardhal.core.client

import java.security.cert.CertificateException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class IrcReconnectionEventTests {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun config(port: Int) = IrcConnectionConfig(
        host = "127.0.0.1", port = port, tls = false, nick = "origin-user", capabilities = emptySet(),
    )

    @Test
    fun bufferedTerminalAuthenticationFailureKeepsItsEpochAfterTransportRetirement() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { if (it.startsWith("PASS ")) server.sendLine(":srv 464 origin-user :Rejected") }
            val scope = scope()
            val calls = AtomicInteger()
            val opened = CompletableDeferred<ReconnectionEvent>()
            val release = CompletableDeferred<Unit>()
            val terminal = CompletableDeferred<ReconnectionEvent>()
            val reconnector = IrcReconnector(scope, connectionFactory = {
                calls.incrementAndGet()
                IrcConnection(config(server.port).copy(serverPassword = "secret"))
            })
            try {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    reconnector.events.collect { envelope ->
                        if (envelope.event == IrcEvent.ConnectionOpened) {
                            opened.complete(envelope)
                            release.await()
                        }
                        if (envelope.event is IrcEvent.Disconnected) terminal.complete(envelope)
                    }
                }
                reconnector.start()
                val opening = withTimeout(2_000) { opened.await() }
                withTimeout(2_000) { reconnector.state.first { it == ReconnectState.Stopped } }
                assertNull(reconnector.currentConnection)
                assertEquals(opening.epoch, reconnector.currentEpoch)
                release.complete(Unit)
                val failure = withTimeout(1_000) { terminal.await() }
                assertSame(opening.connection, failure.connection)
                assertEquals(reconnector.currentEpoch, failure.epoch)
                assertIs<AuthenticationRejectedException>((failure.event as IrcEvent.Disconnected).cause)
                assertEquals(1, calls.get())
            } finally {
                release.complete(Unit)
                reconnector.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun bufferedEventsRetainOldOriginsAfterTheReplacementFactoryHasRun() = runBlocking {
        LoopbackIrcServer().use { failedServer ->
            LoopbackIrcServer().use { goodServer ->
                failedServer.start()
                goodServer.start()
                failedServer.lineListener = { if (it.startsWith("USER ")) failedServer.dropClient() }
                goodServer.lineListener = { if (it.startsWith("USER ")) goodServer.sendLine(":srv 001 origin-user :Welcome") }
                val scope = scope()
                val calls = AtomicInteger()
                val firstOpening = CompletableDeferred<ReconnectionEvent>()
                val release = CompletableDeferred<Unit>()
                val registration = CompletableDeferred<ReconnectionEvent>()
                val delivered = CopyOnWriteArrayList<ReconnectionEvent>()
                val reconnector = IrcReconnector(
                    scope, ReconnectPolicy(initialDelayMillis = 10, maxDelayMillis = 10, jitterRatio = 0.0),
                    connectionFactory = {
                        val port = if (calls.incrementAndGet() == 1) failedServer.port else goodServer.port
                        IrcConnection(config(port))
                    },
                )
                try {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        reconnector.events.collect { envelope ->
                            if (firstOpening.complete(envelope)) release.await()
                            delivered += envelope
                            if (envelope.event is IrcEvent.Registered) registration.complete(envelope)
                        }
                    }
                    reconnector.start()
                    val first = withTimeout(2_000) { firstOpening.await() }
                    withTimeout(2_000) {
                        while (calls.get() < 2 || reconnector.currentConnection?.isRegistered != true) delay(5)
                    }
                    assertTrue(reconnector.currentEpoch > first.epoch)
                    release.complete(Unit)
                    val registered = withTimeout(1_000) { registration.await() }
                    val oldDisconnect = delivered.single { it.event is IrcEvent.Disconnected }
                    assertSame(first.connection, oldDisconnect.connection)
                    assertEquals(first.epoch, oldDisconnect.epoch)
                    assertSame(reconnector.currentConnection, registered.connection)
                    assertEquals(reconnector.currentEpoch, registered.epoch)
                    assertTrue(oldDisconnect.epoch < registered.epoch)
                    assertFalse(oldDisconnect.connection === registered.connection)
                } finally {
                    release.complete(Unit)
                    reconnector.stop()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun factoryHardFailuresAreStructuredTerminalEnvelopesAndCannotBeNudged() = runBlocking {
        val inspection = CertificateInspection("irc.example", 6697, emptyList())
        val failures = listOf(
            AuthenticationRejectedException("A required credential is missing"),
            Socks5Exception(true, "Proxy authentication rejected"),
            CertificateRejectedException(inspection),
            CertificateException("Certificate verification failed"),
            TlsIdentityUnavailableException("Client identity unavailable"),
        )
        for (failure in failures) {
            val scope = scope()
            val calls = AtomicInteger()
            val delivered = CompletableDeferred<ReconnectionEvent>()
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val reconnector = IrcReconnector(scope, connectionFactory = { calls.incrementAndGet(); throw failure })
            try {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    reconnector.events.collect { envelope ->
                        entered.complete(Unit)
                        release.await()
                        delivered.complete(envelope)
                    }
                }
                reconnector.start()
                withTimeout(1_000) { entered.await() }
                withTimeout(1_000) { reconnector.state.first { it == ReconnectState.Stopped } }
                val epoch = reconnector.currentEpoch
                repeat(4) { reconnector.nudge() }
                reconnector.setNetworkAvailable(false)
                reconnector.setNetworkAvailable(true)
                delay(50)
                assertEquals(1, calls.get())
                assertEquals(epoch, reconnector.currentEpoch)
                release.complete(Unit)
                val envelope = withTimeout(1_000) { delivered.await() }
                assertNull(envelope.connection)
                assertEquals(epoch, envelope.epoch)
                assertEquals(reconnector.currentEpoch, envelope.epoch)
                val reported = assertIs<IrcEvent.Disconnected>(envelope.event).cause
                assertEquals<Class<*>?>(failure.javaClass, reported?.javaClass)
                assertEquals(failure.message, reported?.message)
                assertTrue(generateSequence(reported) { it.cause }.any { it === failure })
                when (failure) {
                    is AuthenticationRejectedException -> assertIs<AuthenticationRejectedException>(reported)
                    is Socks5Exception -> assertTrue(assertIs<Socks5Exception>(reported).authenticationRejected)
                    is CertificateRejectedException ->
                        assertEquals(inspection, assertIs<CertificateRejectedException>(reported).inspection)
                    is CertificateException -> assertIs<CertificateException>(reported)
                    is TlsIdentityUnavailableException -> assertIs<TlsIdentityUnavailableException>(reported)
                    else -> error("Unexpected terminal failure fixture: ${failure.javaClass.name}")
                }
            } finally {
                release.complete(Unit)
                reconnector.stop()
                scope.cancel()
            }
        }
    }
}
