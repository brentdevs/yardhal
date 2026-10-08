package dev.brentdevs.yardhal.core.client

import java.net.InetAddress
import java.net.Socket
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TlsSupportTests {
    private val properties = listOf("javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword", "javax.net.ssl.trustStoreType")
    private var originalProperties: Map<String, String?> = emptyMap()

    @BeforeEach
    fun installFixturePlatformTrust() {
        originalProperties = properties.associateWith(System::getProperty)
        val resource = requireNotNull(javaClass.getResource("/tls/trusted.p12"))
        System.setProperty("javax.net.ssl.trustStore", Path.of(resource.toURI()).toString())
        System.setProperty("javax.net.ssl.trustStorePassword", "fixture-password")
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")
    }

    @AfterEach
    fun restorePlatformTrust() {
        originalProperties.forEach { (property, value) ->
            if (value == null) System.clearProperty(property) else System.setProperty(property, value)
        }
    }

    @Test
    fun ordinaryPlatformTrustAcceptsItsCertificate() {
        TlsLoopback("server").use { server ->
            connect(server)
            assertTrue(server.result().isSuccess)
        }
    }

    @Test
    fun absoluteDnsEndpointNormalizesSniAndStillPassesPlatformVerification() {
        TlsLoopback("server").use { server ->
            connect(server, host = "localhost.")
            assertTrue(server.result().isSuccess)
        }
    }

    @Test
    fun absoluteDnsEndpointStillEnforcesItsPinnedCertificateHostname() {
        TlsLoopback("private").use { server ->
            connect(server, host = "localhost.", pin = sha256(certificate("private")))
            assertTrue(server.result().isSuccess)
        }
        TlsLoopback("private").use { server ->
            val failure = nonInspectableFailure {
                connect(server, host = "wrong-host.invalid.", pin = sha256(certificate("private")))
            }
            assertTrue(assertNotNull(failure.message).contains("wrong-host.invalid"))
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun invalidSniDoesNotAbortSocketConfigurationOrBypassPinnedHostnameChecks() {
        TlsLoopback("private").use { server ->
            nonInspectableFailure {
                connect(server, host = "irc_bad.invalid", pin = sha256(certificate("private")))
            }
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun privateCertificateRejectionRetainsEndpointAndChainInspectionThroughSslWrapping() {
        TlsLoopback("private").use { server ->
            val rejected = rejection { connect(server) }
            assertEquals("localhost", rejected.inspection.host)
            assertEquals(server.port, rejected.inspection.port)
            val leaf = rejected.inspection.certificates.single()
            val certificate = certificate("private")
            assertEquals(sha256(certificate), leaf.sha256)
            assertEquals(certificate.subjectX500Principal.name, leaf.subject)
            assertEquals(certificate.issuerX500Principal.name, leaf.issuer)
            assertEquals(certificate.notBefore.time, leaf.notBeforeMs)
            assertEquals(certificate.notAfter.time, leaf.notAfterMs)
            assertTrue("DNS:localhost" in leaf.subjectAltNames)
            assertTrue("IP:127.0.0.1" in leaf.subjectAltNames)
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun explicitlyPinnedPrivateCertificatePassesActualHandshake() {
        TlsLoopback("private").use { server ->
            connect(server, pin = sha256(certificate("private")))
            assertTrue(server.result().isSuccess)
        }
    }

    @Test
    fun aChangedLeafIsRejectedEvenWhenOrdinaryPlatformTrustAcceptsIt() {
        TlsLoopback("changed").use { server ->
            connect(server)
            assertTrue(server.result().isSuccess)
        }
        TlsLoopback("changed").use { server ->
            val rejected = rejection { connect(server, pin = sha256(certificate("private"))) }
            assertEquals(sha256(certificate("changed")), rejected.inspection.certificates.first().sha256)
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun removingExplicitTrustRestoresNormalPrivateCertificateRejection() {
        TlsLoopback("private").use { server ->
            connect(server, pin = sha256(certificate("private")))
            assertTrue(server.result().isSuccess)
        }
        TlsLoopback("private").use { server ->
            rejection { connect(server, pin = null) }
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun aMatchingPrivateLeafPinDoesNotBypassDestinationHostnameVerification() {
        TlsLoopback("private").use { server ->
            val failure = nonInspectableFailure {
                connect(server, host = "wrong-host.invalid", pin = sha256(certificate("private")))
            }
            assertTrue(assertNotNull(failure.message).contains("wrong-host.invalid"))
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun ordinaryPlatformTrustStillRejectsWrongDestinationHostname() {
        TlsLoopback("server").use { server ->
            rejection { connect(server, host = "wrong-host.invalid") }
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun aMatchingPinDoesNotAcceptAnExpiredLeaf() {
        TlsLoopback("expired").use { server ->
            val failure = nonInspectableFailure { connect(server, pin = sha256(certificate("expired"))) }
            assertTrue(failure is CertificateExpiredException)
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun anExpiredChangedOrUnpinnedLeafReportsValidityInsteadOfOfferingTrust() {
        for (pin in listOf(null, sha256(certificate("private")))) {
            TlsLoopback("expired").use { server ->
                val failure = nonInspectableFailure { connect(server, host = "localhost.", pin = pin) }
                assertTrue(failure is CertificateExpiredException)
                assertTrue(server.result().isFailure)
            }
        }
    }

    @Test
    fun configuredClientIdentityPresentsItsActualCertificateChainToTheServer() {
        val identity = identity()
        TlsLoopback("server", requireClientCertificate = true).use { server ->
            connect(server, identity = identity)
            val received = server.result().getOrThrow()
            assertEquals(identity.certificates.map(::sha256), received.map(::sha256))
            assertEquals(2, received.size)
        }
        assertFalse(identity.toString().contains("PRIVATE"))
        assertEquals("TlsClientIdentity(certificates=2)", identity.toString())
    }

    @Test
    fun distinctClientIdentitiesPresentTheirOwnKeysAndCertificatesToTheServer() {
        val first = identity()
        val second = identity("client-other")
        assertFalse(sha256(first.certificates.first()) == sha256(second.certificates.first()))
        assertFalse(first.privateKey.encoded.contentEquals(second.privateKey.encoded))
        for (selected in listOf(first, second)) {
            TlsLoopback("server", requireClientCertificate = true).use { server ->
                connect(server, identity = selected)
                assertEquals(selected.certificates.map(::sha256), server.result().getOrThrow().map(::sha256))
            }
        }
    }

    @Test
    fun missingIdentityCertificateChainProducesStructuredSafeError() {
        val key = identity().privateKey
        val failure = assertFailsWith<TlsIdentityUnavailableException> { TlsClientIdentity(key, emptyArray()) }
        assertTrue(assertNotNull(failure.message).contains("no certificate chain"))
        assertEquals(null, failure.cause)
    }

    @Test
    fun identityCertificateAndPrivateKeyMustActuallyMatch() {
        val key = store("server").getKey("server", password) as PrivateKey
        val failure = assertFailsWith<TlsIdentityUnavailableException> {
            TlsClientIdentity(key, identity().certificates)
        }
        assertTrue(assertNotNull(failure.message).contains("does not match"))
        assertEquals(null, failure.cause)
    }

    @Test
    fun explicitTrustCannotBeReusedForAnotherPortOrHost() {
        val factory = createTlsSocketFactory("localhost", 6697, pinnedFingerprintSha256 = sha256(certificate("private")))
        Socket().use { raw ->
            assertFailsWith<java.io.IOException> { factory.createSocket(raw, "localhost", 6698, false) }
            assertFailsWith<java.io.IOException> { factory.createSocket(raw, "other.invalid", 6697, false) }
        }
    }

    @Test
    fun anUnconnectedFactorySocketCannotReuseItsPinForAnotherPort() {
        TlsLoopback("private").use { server ->
            val factory = createTlsSocketFactory("localhost", server.port, pinnedFingerprintSha256 = sha256(certificate("private")))
            (factory.createSocket() as SSLSocket).use { socket ->
                socket.soTimeout = 5_000
                socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), server.port), 5_000)
                socket.startHandshake()
                socket.getOutputStream().write(7)
                socket.getOutputStream().flush()
            }
            assertTrue(server.result().isSuccess)
        }
        TlsLoopback("private").use { server ->
            val scopedPort = if (server.port == 6697) 6698 else 6697
            val factory = createTlsSocketFactory("localhost", scopedPort, pinnedFingerprintSha256 = sha256(certificate("private")))
            val failure = nonInspectableFailure {
                (factory.createSocket() as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), server.port), 5_000)
                    socket.startHandshake()
                }
            }
            assertTrue(assertNotNull(failure.message).contains("endpoint does not match"))
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun numericIpv6EndpointsAcceptEquivalentSpellingsWithBothInetAddressOverloads() {
        val loopback = InetAddress.getByName("::1")
        for (bindLocal in listOf(false, true)) {
            TlsLoopback("private-v6", bindAddress = loopback).use { server ->
                val factory = createTlsSocketFactory("::1", server.port, pinnedFingerprintSha256 = sha256(certificate("private-v6")))
                val created = if (bindLocal) {
                    factory.createSocket(loopback, server.port, loopback, 0)
                } else {
                    factory.createSocket(loopback, server.port)
                }
                (created as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    socket.startHandshake()
                    socket.getOutputStream().write(7)
                    socket.getOutputStream().flush()
                }
                assertTrue(server.result().isSuccess)
            }
        }
    }

    @Test
    fun anUnconnectedNumericSocketCannotReuseItsPinForAnotherIpv6Host() {
        val loopback = InetAddress.getByName("::1")
        TlsLoopback("private-v6", bindAddress = loopback).use { server ->
            val factory = createTlsSocketFactory("::2", server.port, pinnedFingerprintSha256 = sha256(certificate("private-v6")))
            val failure = nonInspectableFailure {
                (factory.createSocket() as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    socket.connect(java.net.InetSocketAddress(loopback, server.port), 5_000)
                    socket.startHandshake()
                }
            }
            assertTrue(assertNotNull(failure.message).contains("endpoint does not match"))
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun numericEndpointNormalizationDoesNotBroadenHostOrPortPinScope() {
        val factory = createTlsSocketFactory("::1", 6697, pinnedFingerprintSha256 = sha256(certificate("private")))
        val loopback = InetAddress.getByName("::1")
        assertFailsWith<java.io.IOException> { factory.createSocket(InetAddress.getByName("::2"), 6697) }
        assertFailsWith<java.io.IOException> { factory.createSocket(loopback, 6698, loopback, 0) }
        assertFailsWith<java.io.IOException> { factory.createSocket(InetAddress.getByName("::2"), 6697, loopback, 0) }
        assertFailsWith<java.io.IOException> { factory.createSocket(loopback, 6698) }
        Socket().use { raw ->
            assertFailsWith<java.io.IOException> { factory.createSocket(raw, "localhost", 6697, false) }
            assertFailsWith<java.io.IOException> { factory.createSocket(raw, "127.0.0.1", 6697, false) }
            val dnsFactory = createTlsSocketFactory("localhost", 6697, pinnedFingerprintSha256 = sha256(certificate("private")))
            assertFailsWith<java.io.IOException> { dnsFactory.createSocket(raw, "127.0.0.1", 6697, false) }
        }
    }

    private fun connect(server: TlsLoopback, host: String = "localhost", pin: String? = null, identity: TlsClientIdentity? = null) {
        Socket(InetAddress.getLoopbackAddress(), server.port).use { raw ->
            raw.soTimeout = 5_000
            val factory = createTlsSocketFactory(host, server.port, identity, pin)
            (factory.createSocket(raw, host, server.port, true) as SSLSocket).use { socket ->
                socket.soTimeout = 5_000
                assertEquals("HTTPS", socket.sslParameters.endpointIdentificationAlgorithm)
                socket.startHandshake()
                socket.getOutputStream().write(7)
                socket.getOutputStream().flush()
            }
        }
    }

    private fun rejection(block: () -> Unit): CertificateRejectedException {
        val failure = assertFailsWith<javax.net.ssl.SSLException>(block = block)
        return assertNotNull(generateSequence<Throwable>(failure) { it.cause }
            .filterIsInstance<CertificateRejectedException>().firstOrNull())
    }

    private fun nonInspectableFailure(block: () -> Unit): CertificateException {
        val failure = assertFailsWith<javax.net.ssl.SSLException>(block = block)
        val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
        assertNull(causes.filterIsInstance<CertificateRejectedException>().firstOrNull())
        return assertNotNull(causes.filterIsInstance<CertificateException>().firstOrNull())
    }

    private fun identity(name: String = "client"): TlsClientIdentity {
        val store = store(name)
        val key = store.getKey("client", password) as PrivateKey
        val chain = requireNotNull(store.getCertificateChain("client")).map { it as X509Certificate }.toTypedArray()
        return TlsClientIdentity(key, chain)
    }

    private fun certificate(name: String): X509Certificate = store(name).getCertificate("server") as X509Certificate

    private fun store(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
        requireNotNull(this@TlsSupportTests.javaClass.getResourceAsStream("/tls/$name.p12")).use { load(it, password) }
    }

    private fun sha256(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }

    private inner class TlsLoopback(
        name: String,
        requireClientCertificate: Boolean = false,
        bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    ) : AutoCloseable {
        private val listener: SSLServerSocket
        private val completed = CompletableFuture<Result<List<X509Certificate>>>()
        private val worker: Thread
        val port: Int get() = listener.localPort

        init {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store(name), password)
            }
            val serverTrust = store("trusted").apply {
                setCertificateEntry("other-client", store("client-other").getCertificate("client"))
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(serverTrust)
            }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
            listener = context.serverSocketFactory.createServerSocket(0, 1, bindAddress) as SSLServerSocket
            listener.soTimeout = 5_000
            listener.needClientAuth = requireClientCertificate
            worker = thread(name = "tls-loopback", isDaemon = true) {
                completed.complete(runCatching {
                    (listener.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5_000
                        socket.startHandshake()
                        assertEquals(7, socket.getInputStream().read())
                        if (requireClientCertificate) socket.session.peerCertificates.map { it as X509Certificate } else emptyList()
                    }
                })
            }
        }

        fun result(): Result<List<X509Certificate>> = completed.get(7, TimeUnit.SECONDS)

        override fun close() {
            listener.close()
            worker.join(7_000)
            assertFalse(worker.isAlive)
        }
    }

    private companion object {
        val password = "fixture-password".toCharArray()
    }
}
