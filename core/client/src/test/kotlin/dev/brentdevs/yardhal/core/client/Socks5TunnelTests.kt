package dev.brentdevs.yardhal.core.client

import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class Socks5TunnelTests {
    @Test
    fun anonymousTunnelForwardsDestinationHostnameAndPayloadForEveryReplyAddress() {
        for (addressType in listOf(1, 3, 4)) {
            assertDestinationTunnel(addressType = addressType)
        }
    }

    @Test
    fun authenticatedTunnelNegotiatesRfc1929AndForwardsDestinationPayload() {
        assertDestinationTunnel(username = "prøxy-user", password = "pässword secret", addressType = 4)
    }

    @Test
    fun credentialLengthsUseUnsignedUtf8Octets() {
        assertDestinationTunnel(username = "é".repeat(127) + "a", password = "p".repeat(255), addressType = 3)
    }

    @Test
    fun internationalDestinationNamesAreSentToTheProxyAsIdnaWithoutLocalDns() {
        assertDestinationTunnel(
            destinationHost = "irc.bücher.invalid",
            wireHost = "irc.xn--bcher-kva.invalid",
        )
    }

    @Test
    fun anonymousConnectionsRejectUnsupportedOrUnrequestedMethods() {
        for (method in listOf(1, 2, 0xff)) {
            assertNegotiationFailure(authenticationRejected = false) { socket ->
                write(socket, byteArrayOf(5, method.toByte()))
                assertClientClosed(socket)
            }
        }
    }

    @Test
    fun configuredAuthenticationCannotDowngradeToAnonymousOrAnotherMethod() {
        for (method in listOf(0, 1, 0xff)) {
            assertNegotiationFailure(authenticationRejected = true, authenticated = true) { socket ->
                write(socket, byteArrayOf(5, method.toByte()))
                assertClientClosed(socket)
            }
        }
    }

    @Test
    fun rejectedCredentialsAndInvalidAuthenticationVersionsAreSafeStructuredFailures() {
        for (reply in listOf(byteArrayOf(1, 1), byteArrayOf(1, -1), byteArrayOf(5, 0), byteArrayOf(0, 0))) {
            assertNegotiationFailure(authenticationRejected = true, authenticated = true) { socket ->
                write(socket, byteArrayOf(5, 2))
                expectCredentials(socket, PROXY_USERNAME, PROXY_PASSWORD)
                write(socket, reply)
                assertClientClosed(socket)
            }
        }
    }

    @Test
    fun invalidMethodSelectionVersionClosesTheFailedSocket() {
        assertNegotiationFailure(authenticationRejected = false) { socket ->
            write(socket, byteArrayOf(4, 0))
            assertClientClosed(socket)
        }
    }

    @Test
    fun everyProxyConnectionErrorIsReportedAndClosesTheFailedSocket() {
        val reasons = mapOf(
            1 to "general failure",
            2 to "rules",
            3 to "destination network",
            4 to "destination host",
            5 to "refused",
            6 to "time to live",
            7 to "CONNECT command",
            8 to "address type",
            0xff to "unknown connection error",
        )
        for ((result, reason) in reasons) {
            val failure = assertNegotiationFailure(authenticationRejected = false) { socket ->
                acceptAnonymousConnect(socket)
                val reply = successReply(1)
                reply[1] = result.toByte()
                write(socket, reply)
                assertClientClosed(socket)
            }
            assertTrue(failure.message.orEmpty().contains(reason))
        }
    }

    @Test
    fun malformedConnectionRepliesAreRejectedBeforeAnyTunnelIsReturned() {
        val replies = listOf(
            byteArrayOf(4, 0, 0, 1),
            byteArrayOf(5, 0, 1, 1),
            byteArrayOf(5, 0, 0, 2),
            byteArrayOf(5, 0, 0, 0xff.toByte()),
            byteArrayOf(5, 0, 0, 3, 0),
        )
        for (reply in replies) {
            assertNegotiationFailure(authenticationRejected = false) { socket ->
                acceptAnonymousConnect(socket)
                write(socket, reply)
                assertClientClosed(socket)
            }
        }
    }

    @Test
    fun eofDuringReplyHeaderAddressOrPortRejectsTheTunnelAndClosesTheSocket() {
        for (addressType in listOf(1, 3, 4)) {
            val reply = successReply(addressType)
            val truncatedLengths = setOf(0, 1, 2, 3, 4, reply.size - 3, reply.size - 2, reply.size - 1)
            for (length in truncatedLengths) {
                assertNegotiationFailure(authenticationRejected = false) { socket ->
                    acceptAnonymousConnect(socket)
                    write(socket, reply.copyOf(length))
                    socket.shutdownOutput()
                    assertClientClosed(socket)
                }
            }
        }
    }

    @Test
    fun eofDuringMethodSelectionAndAuthenticationRejectsIncompleteNegotiation() {
        assertNegotiationFailure(authenticationRejected = false) { socket ->
            write(socket, byteArrayOf(5))
            socket.shutdownOutput()
            assertClientClosed(socket)
        }
        assertNegotiationFailure(authenticationRejected = false, authenticated = true) { socket ->
            write(socket, byteArrayOf(5, 2))
            expectCredentials(socket, PROXY_USERNAME, PROXY_PASSWORD)
            write(socket, byteArrayOf(1))
            socket.shutdownOutput()
            assertClientClosed(socket)
        }
    }

    @Test
    fun stalledPartialMethodSelectionIsBoundedAndClosesTheFailedSocket() {
        assertNegotiationTimeout { socket ->
            write(socket, byteArrayOf(5))
            assertClientClosed(socket)
        }
    }

    @Test
    fun stalledPartialAuthenticationReplyIsBoundedAndClosesTheFailedSocket() {
        assertNegotiationTimeout(authenticated = true) { socket ->
            write(socket, byteArrayOf(5, 2))
            expectCredentials(socket, PROXY_USERNAME, PROXY_PASSWORD)
            write(socket, byteArrayOf(1))
            assertClientClosed(socket)
        }
    }

    @Test
    fun stalledPartialBoundAddressIsBoundedAndClosesTheFailedSocket() {
        assertNegotiationTimeout { socket ->
            acceptAnonymousConnect(socket)
            write(socket, successReply(4).copyOf(19))
            assertClientClosed(socket)
        }
    }

    @Test
    fun fragmentedReadsShareOneDeadlineAcrossHandshakeStages() {
        val clock = AtomicLong()
        TcpFixture { socket ->
            expectGreeting(socket, 2)
            write(socket, byteArrayOf(5))
            clock.set(TimeUnit.SECONDS.toNanos(8))
            write(socket, byteArrayOf(2))
            expectCredentials(socket, PROXY_USERNAME, PROXY_PASSWORD)
            write(socket, byteArrayOf(1))
            clock.set(TimeUnit.SECONDS.toNanos(16))
            write(socket, byteArrayOf(0))
            expectDestination(socket, DESTINATION_HOST)
            val reply = successReply(1)
            write(socket, reply.copyOf(1))
            clock.set(TimeUnit.SECONDS.toNanos(22))
            runCatching { write(socket, reply.copyOfRange(1, reply.size)) }
            assertClientClosed(socket)
        }.use { proxy ->
            assertFailsWith<SocketTimeoutException> {
                Socks5Tunnel.connect(
                    DESTINATION_HOST,
                    6667,
                    proxy.config(authenticated = true),
                    20_000,
                    lookupHost = { host ->
                        InetAddress.getByName(host).also { clock.set(TimeUnit.SECONDS.toNanos(4)) }
                    },
                    nanoTime = clock::get,
                ).close()
            }
            proxy.await()
        }
    }

    @Test
    fun stalledProxyResolutionTimesOutWithoutGrowingWorkersOrRetainingCancelledLookups() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val lookups = AtomicInteger()
        val interrupted = AtomicInteger()
        val resolver: (String) -> InetAddress = { host ->
            assertEquals("stalled.proxy.invalid", host)
            lookups.incrementAndGet()
            started.countDown()
            try {
                var released = false
                while (!released) {
                    try {
                        release.await()
                        released = true
                    } catch (_: InterruptedException) {
                        interrupted.incrementAndGet()
                    }
                }
                InetAddress.getLoopbackAddress()
            } finally {
                finished.countDown()
            }
        }
        val clients = List(2) {
            FutureTask {
                val startNanos = System.nanoTime()
                val failure = runCatching {
                    Socks5Tunnel.connect(
                        DESTINATION_HOST,
                        6667,
                        Socks5Config("stalled.proxy.invalid", 1080),
                        1_000,
                        resolver,
                        System::nanoTime,
                    ).close()
                }.exceptionOrNull()
                failure to (System.nanoTime() - startNanos)
            }.also { task ->
                thread(isDaemon = true, name = "socks-test-dns-client") { task.run() }
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(6) {
                assertFailsWith<SocketTimeoutException> {
                    Socks5Tunnel.connect(
                        DESTINATION_HOST,
                        6667,
                        Socks5Config("stalled.proxy.invalid", 1080),
                        100,
                        resolver,
                        System::nanoTime,
                    )
                }
            }
            for (client in clients) {
                val (failure, elapsedNanos) = client.get(5, TimeUnit.SECONDS)
                assertIs<SocketTimeoutException>(failure)
                assertTrue(elapsedNanos < TimeUnit.SECONDS.toNanos(5))
            }
            assertEquals(2, lookups.get())
        } finally {
            release.countDown()
            clients.forEach { it.cancel(true) }
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(interrupted.get() >= 2)
        TcpFixture { socket ->
            expectGreeting(socket, 0)
            acceptAnonymousConnect(socket)
            write(socket, successReply(1))
        }.use { proxy ->
            Socks5Tunnel.connect(
                DESTINATION_HOST,
                6667,
                proxy.config(authenticated = false),
                3_000,
                lookupHost = { InetAddress.getLoopbackAddress() },
                nanoTime = System::nanoTime,
            ).use { tunnel -> assertTrue(tunnel.isConnected) }
            proxy.await()
        }
        assertEquals(2, lookups.get())
    }

    @Test
    fun interruptingProxyResolutionCancelsTheLookupAndPreservesCallerInterruption() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = FutureTask {
            val failure = runCatching {
                Socks5Tunnel.connect(
                    DESTINATION_HOST,
                    6667,
                    Socks5Config("cancelled.proxy.invalid", 1080),
                    20_000,
                    lookupHost = {
                        started.countDown()
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            cancelled.countDown()
                        }
                        InetAddress.getLoopbackAddress()
                    },
                    nanoTime = System::nanoTime,
                ).close()
            }.exceptionOrNull()
            failure to Thread.currentThread().isInterrupted
        }
        val caller = thread(isDaemon = true, name = "socks-test-interrupted-dns-client") { client.run() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            caller.interrupt()
            val (failure, wasInterrupted) = client.get(5, TimeUnit.SECONDS)
            assertIs<InterruptedIOException>(failure)
            assertTrue(wasInterrupted)
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            client.cancel(true)
            caller.join(1_000)
        }
    }

    @Test
    fun incompleteEmptyOrOversizedCredentialsFailClosedWithoutExposingSecrets() {
        val configs = listOf(
            Socks5Config("127.0.0.1", 1080, username = PROXY_USERNAME),
            Socks5Config("127.0.0.1", 1080, password = PROXY_PASSWORD),
            Socks5Config("127.0.0.1", 1080, username = "", password = PROXY_PASSWORD),
            Socks5Config("127.0.0.1", 1080, username = PROXY_USERNAME, password = ""),
            Socks5Config("127.0.0.1", 1080, username = "é".repeat(128), password = PROXY_PASSWORD),
            Socks5Config("127.0.0.1", 1080, username = PROXY_USERNAME, password = "é".repeat(128)),
        )
        for (config in configs) {
            val failure = assertFailsWith<Socks5Exception> {
                Socks5Tunnel.connect(DESTINATION_HOST, 6667, config, 500)
            }
            assertTrue(failure.authenticationRejected)
            assertFalse(failure.toString().contains(PROXY_USERNAME))
            assertFalse(failure.toString().contains(PROXY_PASSWORD))
            assertFalse(config.toString().contains(PROXY_USERNAME))
            assertFalse(config.toString().contains(PROXY_PASSWORD))
            assertFalse(config.toString().contains("é"))
        }
    }

    @Test
    fun invalidDestinationNamesFailWithoutLocalResolution() {
        for (host in listOf("", " ", "irc\n.invalid", "a".repeat(256))) {
            assertFailsWith<Socks5Exception> {
                Socks5Tunnel.connect(host, 6667, Socks5Config("127.0.0.1", 1080), 500)
            }
        }
    }

    private fun assertDestinationTunnel(
        username: String? = null,
        password: String? = null,
        addressType: Int = 1,
        destinationHost: String = DESTINATION_HOST,
        wireHost: String = destinationHost,
    ) {
        val payload = "NICK actual-destination-client\r\n".toByteArray() + byteArrayOf(0, -1, 42)
        val response = "001 actual-destination-client :Destination accepted payload\r\n".toByteArray() + byteArrayOf(-2, 0)
        TcpFixture { socket ->
            assertContentEquals(payload, readBytes(socket.getInputStream(), payload.size))
            write(socket, response)
        }.use { destination ->
            TcpFixture { socket ->
                expectGreeting(socket, if (username != null) 2 else 0)
                writeFragmented(socket, byteArrayOf(5, if (username != null) 2 else 0))
                if (username != null && password != null) {
                    expectCredentials(socket, username, password)
                    writeFragmented(socket, byteArrayOf(1, 0))
                }
                val requestedPort = expectDestination(socket, wireHost)
                assertEquals(destination.port, requestedPort)
                Socket().use { upstream ->
                    upstream.connect(InetSocketAddress(destination.host, requestedPort), 3_000)
                    upstream.soTimeout = 3_000
                    writeFragmented(socket, successReply(addressType))
                    val upload = FutureTask {
                        socket.getInputStream().copyTo(upstream.getOutputStream())
                    }
                    thread(isDaemon = true, name = "socks-test-upload") { upload.run() }
                    upstream.getInputStream().copyTo(socket.getOutputStream())
                    socket.shutdownOutput()
                    upload.get(5, TimeUnit.SECONDS)
                }
            }.use { proxy ->
                Socks5Tunnel.connect(
                    destinationHost,
                    destination.port,
                    Socks5Config(proxy.host, proxy.port, username, password),
                    3_000,
                ).use { tunnel ->
                    assertTrue(tunnel.isConnected)
                    assertEquals(0, tunnel.soTimeout)
                    tunnel.soTimeout = 3_000
                    write(tunnel, payload)
                    assertContentEquals(response, readBytes(tunnel.getInputStream(), response.size))
                    assertEquals(-1, tunnel.getInputStream().read())
                }
                proxy.await()
            }
            destination.await()
        }
    }

    private fun assertNegotiationFailure(
        authenticationRejected: Boolean,
        authenticated: Boolean = false,
        action: (Socket) -> Unit,
    ): Socks5Exception {
        TcpFixture { socket ->
            expectGreeting(socket, if (authenticated) 2 else 0)
            action(socket)
        }.use { proxy ->
            val failure = assertFailsWith<Socks5Exception> {
                Socks5Tunnel.connect(DESTINATION_HOST, 6667, proxy.config(authenticated), 1_000)
            }
            assertEquals(authenticationRejected, failure.authenticationRejected)
            assertFalse(failure.toString().contains(PROXY_USERNAME))
            assertFalse(failure.toString().contains(PROXY_PASSWORD))
            proxy.await()
            return failure
        }
    }

    private fun assertNegotiationTimeout(
        authenticated: Boolean = false,
        timeoutMillis: Int = 150,
        action: (Socket) -> Unit,
    ) {
        TcpFixture { socket ->
            expectGreeting(socket, if (authenticated) 2 else 0)
            action(socket)
        }.use { proxy ->
            val startNanos = System.nanoTime()
            assertFailsWith<SocketTimeoutException> {
                Socks5Tunnel.connect(DESTINATION_HOST, 6667, proxy.config(authenticated), timeoutMillis)
            }
            assertTrue(System.nanoTime() - startNanos < TimeUnit.SECONDS.toNanos(2))
            proxy.await()
        }
    }

    private fun expectGreeting(socket: Socket, method: Int) {
        assertContentEquals(byteArrayOf(5, 1, method.toByte()), readBytes(socket.getInputStream(), 3))
    }

    private fun expectCredentials(socket: Socket, username: String, password: String) {
        val input = socket.getInputStream()
        val header = readBytes(input, 2)
        assertEquals(1, header[0].toInt())
        val usernameBytes = username.toByteArray(Charsets.UTF_8)
        assertEquals(usernameBytes.size, header[1].toInt() and 0xff)
        assertContentEquals(usernameBytes, readBytes(input, usernameBytes.size))
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        assertEquals(passwordBytes.size, readBytes(input, 1)[0].toInt() and 0xff)
        assertContentEquals(passwordBytes, readBytes(input, passwordBytes.size))
    }

    private fun acceptAnonymousConnect(socket: Socket) {
        write(socket, byteArrayOf(5, 0))
        assertEquals(6667, expectDestination(socket, DESTINATION_HOST))
    }

    private fun expectDestination(socket: Socket, host: String): Int {
        val input = socket.getInputStream()
        val header = readBytes(input, 5)
        assertContentEquals(byteArrayOf(5, 1, 0, 3), header.copyOf(4))
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        assertEquals(hostBytes.size, header[4].toInt() and 0xff)
        assertContentEquals(hostBytes, readBytes(input, hostBytes.size))
        val port = readBytes(input, 2)
        return ((port[0].toInt() and 0xff) shl 8) or (port[1].toInt() and 0xff)
    }

    private fun successReply(addressType: Int): ByteArray {
        val address = when (addressType) {
            1 -> byteArrayOf(127, 0, 0, 1)
            3 -> {
                val host = List(4) { "a".repeat(63) }.joinToString(".").toByteArray(Charsets.US_ASCII)
                byteArrayOf(host.size.toByte()) + host
            }
            4 -> ByteArray(16).also { it[15] = 1 }
            else -> error("unsupported fixture address type")
        }
        return byteArrayOf(5, 0, 0, addressType.toByte()) + address + byteArrayOf(0x1a, 0x0b)
    }

    private fun readBytes(input: InputStream, count: Int): ByteArray =
        input.readNBytes(count).also { assertEquals(count, it.size, "incomplete fixture request") }

    private fun write(socket: Socket, bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private fun writeFragmented(socket: Socket, bytes: ByteArray) {
        for (byte in bytes) {
            socket.getOutputStream().write(byte.toInt() and 0xff)
            socket.getOutputStream().flush()
            Thread.sleep(2)
        }
    }

    private fun assertClientClosed(socket: Socket) {
        val closed = try {
            socket.getInputStream().read() == -1
        } catch (_: SocketException) {
            true
        }
        assertTrue(closed, "the failed SOCKS5 client must close its TCP socket")
    }

    private class TcpFixture(action: (Socket) -> Unit) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val host: String = listener.inetAddress.hostAddress
        val port: Int = listener.localPort

        @Volatile
        private var accepted: Socket? = null

        private val task = FutureTask {
            listener.accept().use { socket ->
                accepted = socket
                socket.soTimeout = 3_000
                action(socket)
            }
        }
        private val worker = thread(isDaemon = true, name = "socks-test-listener") { task.run() }

        fun config(authenticated: Boolean): Socks5Config = Socks5Config(
            host,
            port,
            username = if (authenticated) PROXY_USERNAME else null,
            password = if (authenticated) PROXY_PASSWORD else null,
        )

        fun await() {
            task.get(5, TimeUnit.SECONDS)
        }

        override fun close() {
            runCatching { accepted?.close() }
            runCatching { listener.close() }
            worker.join(1_000)
            worker.interrupt()
        }
    }

    private companion object {
        const val DESTINATION_HOST = "irc.socks.invalid"
        const val PROXY_USERNAME = "private-proxy-user"
        const val PROXY_PASSWORD = "private-proxy-password"
    }
}
