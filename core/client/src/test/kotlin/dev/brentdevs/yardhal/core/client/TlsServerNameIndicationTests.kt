package dev.brentdevs.yardhal.core.client

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class TlsServerNameIndicationTests {

    private class LoopbackRedirectingSocketFactory(private val targetPort: Int) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), timeout)
            }
        }

        override fun createSocket(host: String, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(host: InetAddress, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }

    @org.junit.jupiter.api.Test
    fun clientHelloNamesTheConfiguredHost() = runBlocking {
        val hello = captureClientHello("irc.sni.invalid")
        assertEquals(0x16, hello.first().toInt())
        assertTrue(String(hello, Charsets.ISO_8859_1).contains("irc.sni.invalid"))
    }

    @org.junit.jupiter.api.Test
    fun absoluteDnsEndpointUsesNormalizedSniInTheActualClientHello() = runBlocking {
        val hello = captureClientHello("irc.sni.invalid.")
        val text = String(hello, Charsets.ISO_8859_1)
        assertEquals(0x16, hello.first().toInt())
        assertTrue(text.contains("irc.sni.invalid"))
        assertFalse(text.contains("irc.sni.invalid."))
    }

    @org.junit.jupiter.api.Test
    fun invalidSniEndpointStillSendsAClientHelloWithoutAnInvalidServerName() = runBlocking {
        val hello = captureClientHello("irc_sni.invalid")
        assertEquals(0x16, hello.first().toInt())
        assertFalse(String(hello, Charsets.ISO_8859_1).contains("irc_sni.invalid"))
    }

    private suspend fun captureClientHello(host: String): ByteArray {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val captured = CompletableDeferred<ByteArray>()
            thread(isDaemon = true) {
                runCatching {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val buffer = ByteArray(8_192)
                        val read = socket.getInputStream().read(buffer)
                        captured.complete(buffer.copyOf(maxOf(read, 0)))
                    }
                }.onFailure { captured.completeExceptionally(it) }
            }
            val connection = IrcConnection(
                config = IrcConnectionConfig(
                    host = host,
                    port = 6697,
                    tls = true,
                    nick = "sni-test",
                    capabilities = emptySet(),
                    connectTimeoutMillis = 3_000,
                ),
                socketFactory = LoopbackRedirectingSocketFactory(listener.localPort),
            )
            connection.start()
            val hello = withTimeout(5_000) { captured.await() }
            connection.disconnect()
            return hello
        }
    }
}
