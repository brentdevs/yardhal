package dev.brentdevs.yardhal.core.client

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class IrcProxyIntegrationTests {
    @Test
    fun proxyTunnelPreservesDestinationTlsHostnameSniAndPin() = runBlocking {
        val password = "fixture-password".toCharArray()
        val store = KeyStore.getInstance("PKCS12")
        requireNotNull(javaClass.getResourceAsStream("/tls/server.p12")).use { store.load(it, password) }
        val certificate = store.getCertificate("server") as X509Certificate
        val pin = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, password)
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val accepted = AtomicReference<Socket?>()
        val destination = CompletableDeferred<Pair<String, Int>>()
        val serverName = CompletableDeferred<String>()
        val lines = CopyOnWriteArrayList<String>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { proxy ->
            val connection = IrcConnection(IrcConnectionConfig(
                host = "localhost", port = 6697, nick = "proxy-user", trustedCertificateSha256 = pin,
                proxy = Socks5Config("127.0.0.1", proxy.localPort), capabilities = emptySet(), connectTimeoutMillis = 2_000,
            ))
            try {
                scope.launch {
                    try {
                        val raw = proxy.accept()
                        accepted.set(raw)
                        raw.soTimeout = 5_000
                        val input = raw.getInputStream()
                        val output = raw.getOutputStream()
                        assertEquals(5, input.read())
                        val methods = input.readNBytes(input.read())
                        assertTrue(0.toByte() in methods)
                        output.write(byteArrayOf(5, 0))
                        output.flush()
                        assertEquals(5, input.read())
                        assertEquals(1, input.read())
                        assertEquals(0, input.read())
                        assertEquals(3, input.read())
                        val host = String(input.readNBytes(input.read()), Charsets.US_ASCII)
                        val port = (input.read() shl 8) or input.read()
                        destination.complete(host to port)
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                        output.flush()
                        val secured = tls.socketFactory.createSocket(raw, "localhost", port, true) as SSLSocket
                        accepted.set(secured)
                        secured.useClientMode = false
                        secured.startHandshake()
                        val session = secured.session as ExtendedSSLSession
                        val requested = session.requestedServerNames.filterIsInstance<SNIHostName>().single().asciiName
                        serverName.complete(requested)
                        val reader = secured.getInputStream().bufferedReader(Charsets.UTF_8)
                        val encryptedOutput = secured.getOutputStream()
                        while (true) {
                            val line = reader.readLine() ?: break
                            lines += line
                            if (line.startsWith("USER ")) {
                                encryptedOutput.write(":srv 001 proxy-user :Welcome through destination TLS\r\n".toByteArray(Charsets.UTF_8))
                                encryptedOutput.flush()
                            }
                        }
                    } catch (failure: Exception) {
                        destination.completeExceptionally(failure)
                        serverName.completeExceptionally(failure)
                    }
                }
                val collector = EventCollector(scope, connection.events)
                connection.start()
                assertEquals("proxy-user", withTimeout(5_000) { collector.awaitRegistered() }.nickname)
                assertEquals("localhost" to 6697, withTimeout(1_000) { destination.await() })
                assertEquals("localhost", withTimeout(1_000) { serverName.await() })
                assertTrue(lines.any { it == "NICK proxy-user" })
            } finally {
                connection.disconnect()
                accepted.get()?.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun proxiedHostnameIsSentUnresolvedToTheTunnel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val accepted = AtomicReference<Socket?>()
        val destination = CompletableDeferred<String>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { proxy ->
            val connection = IrcConnection(IrcConnectionConfig(
                host = "does-not-resolve.invalid", port = 6667, tls = false, nick = "proxy-user",
                proxy = Socks5Config("127.0.0.1", proxy.localPort), capabilities = emptySet(), connectTimeoutMillis = 2_000,
            ))
            try {
                scope.launch {
                    try {
                        val raw = proxy.accept()
                        accepted.set(raw)
                        raw.soTimeout = 5_000
                        val input = raw.getInputStream()
                        val output = raw.getOutputStream()
                        assertEquals(5, input.read())
                        input.readNBytes(input.read())
                        output.write(byteArrayOf(5, 0))
                        output.flush()
                        assertEquals(5, input.read())
                        assertEquals(1, input.read())
                        assertEquals(0, input.read())
                        assertEquals(3, input.read())
                        destination.complete(String(input.readNBytes(input.read()), Charsets.US_ASCII))
                        assertEquals(6667, (input.read() shl 8) or input.read())
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                        output.flush()
                        val reader = input.bufferedReader(Charsets.UTF_8)
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.startsWith("USER ")) {
                                output.write(":srv 001 proxy-user :Welcome without local DNS\r\n".toByteArray(Charsets.UTF_8))
                                output.flush()
                            }
                        }
                    } catch (failure: Exception) {
                        destination.completeExceptionally(failure)
                    }
                }
                val collector = EventCollector(scope, connection.events)
                connection.start()
                assertEquals("proxy-user", withTimeout(5_000) { collector.awaitRegistered() }.nickname)
                assertEquals("does-not-resolve.invalid", withTimeout(1_000) { destination.await() })
            } finally {
                connection.disconnect()
                accepted.get()?.close()
                scope.cancel()
            }
        }
    }
}
