package dev.brentdevs.yardhal.core.client

import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class IrcExternalIntegrationTests {
    private val password = "fixture-password".toCharArray()

    private fun store(resource: String): KeyStore {
        val store = KeyStore.getInstance("PKCS12")
        val input = requireNotNull(javaClass.getResourceAsStream(resource))
        input.use { store.load(it, password) }
        return store
    }

    private fun identity(): TlsClientIdentity {
        val store = store("/tls/client.p12")
        return TlsClientIdentity(
            store.getKey("client", password) as PrivateKey,
            store.getCertificateChain("client").map { it as X509Certificate }.toTypedArray(),
        )
    }

    private fun serverContext(serverStore: KeyStore): SSLContext {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(serverStore, password)
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(store("/tls/trusted.p12"))
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
    }

    @Test
    fun externalUsesPresentedClientCertificateAndEmptyAuthorizationIdentityBeforeRegistration() = runBlocking {
        val serverStore = store("/tls/server.p12")
        val serverCertificate = serverStore.getCertificate("server") as X509Certificate
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(serverCertificate.encoded).joinToString("") { "%02x".format(it) }
        val clientIdentity = identity()
        val received = CopyOnWriteArrayList<String>()
        val clientCertificate = CompletableDeferred<ByteArray>()
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = serverContext(serverStore).serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true
        val connection = IrcConnection(IrcConnectionConfig(
            host = "localhost", port = listener.localPort, nick = "external-user", saslMode = "EXTERNAL",
            tlsClientIdentity = clientIdentity, trustedCertificateSha256 = fingerprint, capabilities = setOf("sasl"), connectTimeoutMillis = 2_000,
        ))
        var accepted: SSLSocket? = null
        try {
            serverScope.launch {
                try {
                    val socket = listener.accept() as SSLSocket
                    accepted = socket
                    socket.soTimeout = 5_000
                    socket.startHandshake()
                    clientCertificate.complete(socket.session.peerCertificates.first().encoded)
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val output = socket.getOutputStream()
                    fun reply(line: String) {
                        output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                    while (true) {
                        val line = reader.readLine() ?: break
                        received += line
                        when {
                            line.startsWith("CAP LS") -> reply(":srv CAP * LS :sasl=PLAIN,SCRAM-SHA-256,EXTERNAL")
                            line.startsWith("CAP REQ") -> reply(":srv CAP * ACK :sasl")
                            line == "AUTHENTICATE EXTERNAL" -> reply("AUTHENTICATE +")
                            line == "AUTHENTICATE +" -> reply(":srv 903 * :Certificate authentication successful")
                            line.startsWith("USER ") -> reply(":srv 001 external-user :Authenticated welcome")
                        }
                    }
                } catch (error: Exception) {
                    clientCertificate.completeExceptionally(error)
                }
            }
            val collector = EventCollector(clientScope, connection.events)
            connection.start()
            val events = withTimeout(5_000) { collector.drainUntilRegistered() }
            assertEquals(SaslOutcome.Success, events.filterIsInstance<IrcEvent.SaslResult>().single().outcome)
            assertContentEquals(clientIdentity.certificates.first().encoded, withTimeout(1_000) { clientCertificate.await() })
            assertTrue("AUTHENTICATE EXTERNAL" in received)
            assertTrue("AUTHENTICATE +" in received)
            assertFalse("AUTHENTICATE PLAIN" in received || "AUTHENTICATE SCRAM-SHA-256" in received)
            assertTrue(received.indexOf("AUTHENTICATE +") < received.indexOf("CAP END"))
            assertTrue(received.indexOf("CAP END") < received.indexOfFirst { it.startsWith("USER ") })
        } finally {
            connection.disconnect()
            accepted?.close()
            listener.close()
            serverScope.cancel()
            clientScope.cancel()
        }
    }

    @Test
    fun externalWithoutIdentityOrTlsFailsBeforeOpeningTransport() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            for (clientIdentity in listOf(null, identity())) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val connection = IrcConnection(IrcConnectionConfig(
                    host = "127.0.0.1", port = server.port, tls = false, nick = "external-user", saslMode = "EXTERNAL", tlsClientIdentity = clientIdentity,
                ))
                try {
                    val collector = EventCollector(scope, connection.events)
                    connection.start()
                    val events = collector.drainUntilDisconnected()
                    assertIs<AuthenticationRejectedException>((events.last() as IrcEvent.Disconnected).cause)
                    assertFalse(events.any { it is IrcEvent.ConnectionOpened || it is IrcEvent.Registered })
                    assertTrue(server.receivedLines.isEmpty())
                } finally {
                    connection.disconnect()
                    scope.cancel()
                }
            }
        }
    }
}
