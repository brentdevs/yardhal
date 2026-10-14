package dev.brentdevs.yardhal.core.client

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
    fun optingInAcceptsSelfSignedTlsAndASeparateSecureConnectionStillRejectsIt() {
        TlsLoopback("private").use { server ->
            connect(server, allowInsecureCertificates = true)
            assertTrue(server.result().isSuccess)
        }
        TlsLoopback("private").use { server ->
            rejection { connect(server) }
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun optingInAcceptsExpiredTlsAndTurningItOffRestoresValidityChecks() {
        val pin = sha256(certificate("expired"))
        TlsLoopback("expired").use { server ->
            connect(server, pin = pin, allowInsecureCertificates = true)
            assertTrue(server.result().isSuccess)
        }
        TlsLoopback("expired").use { server ->
            val failure = nonInspectableFailure { connect(server, pin = pin, allowInsecureCertificates = false) }
            assertTrue(failure is CertificateExpiredException)
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun optingInDisablesHostnameChecksForBothPlatformTrustedAndPinnedTls() {
        for (name in listOf("server", "private")) {
            val pin = if (name == "private") sha256(certificate(name)) else null
            TlsLoopback(name).use { server ->
                connect(server, host = "wrong-host.invalid", pin = pin, allowInsecureCertificates = true)
                assertTrue(server.result().isSuccess)
            }
            TlsLoopback(name).use { server ->
                if (pin == null) {
                    rejection { connect(server, host = "wrong-host.invalid") }
                } else {
                    nonInspectableFailure { connect(server, host = "wrong-host.invalid", pin = pin) }
                }
                assertTrue(server.result().isFailure)
            }
        }
    }

    @Test
    fun optingInIgnoresAChangedPinAndTurningItOffRestoresPinEnforcement() {
        val pin = sha256(certificate("private"))
        TlsLoopback("changed").use { server ->
            connect(server, pin = pin, allowInsecureCertificates = true)
            assertTrue(server.result().isSuccess)
        }
        TlsLoopback("changed").use { server ->
            rejection { connect(server, pin = pin, allowInsecureCertificates = false) }
            assertTrue(server.result().isFailure)
        }
    }

    @Test
    fun insecureTlsStillSendsSniAndAuthenticatesWithTheConfiguredClientIdentity() {
        val identity = identity()
        TlsLoopback("private", requireClientCertificate = true, expectedServerName = "wrong-host.invalid").use { server ->
            connect(server, host = "wrong-host.invalid", identity = identity, allowInsecureCertificates = true)
            assertEquals(identity.certificates.map(::sha256), server.result().getOrThrow().map(::sha256))
        }
    }

    @Test
    fun ircConnectionOptInRegistersOverActualSelfSignedAndExpiredTls() {
        for (name in listOf("private", "expired")) {
            TlsLoopback(name, expectIrcRegistration = true).use { server ->
                val connection = IrcConnection(
                    IrcConnectionConfig(
                        host = "localhost",
                        port = server.port,
                        nick = "insecure-test",
                        capabilities = emptySet(),
                        allowInsecureCertificates = true,
                    ),
                )
                try {
                    connection.start()
                    assertTrue(server.result().isSuccess)
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    @Test
    fun activeStsMatchingTlsPortRejectsInvalidCertificatesDespiteOptIn() = runBlocking {
        assertStsRejectsInvalidCertificates(tlsRequested = true, matchingPort = true)
    }

    @Test
    fun activeStsUpgradesPlainAndRejectsInvalidCertificatesDespiteOptIn() = runBlocking {
        assertStsRejectsInvalidCertificates(tlsRequested = false, matchingPort = false)
    }

    @Test
    fun activeStsUpgradesWrongTlsPortAndRejectsInvalidCertificatesDespiteOptIn() = runBlocking {
        assertStsRejectsInvalidCertificates(tlsRequested = true, matchingPort = false)
    }

    @Test
    fun expiredStsPolicyAllowsExplicitTlsOptInToRegisterWithAnExpiredCertificate() {
        TlsLoopback("expired", expectIrcRegistration = true).use { server ->
            val policies = InMemoryStsPolicyStore().apply {
                save("localhost", StsPolicy(server.port, 1000, 3600))
            }
            val connection = IrcConnection(
                IrcConnectionConfig(
                    host = "localhost",
                    port = server.port,
                    nick = "insecure-test",
                    capabilities = emptySet(),
                    allowInsecureCertificates = true,
                ),
                nowMillis = { 1_000_000 },
                stsPolicyStore = policies,
            )
            try {
                connection.start()
                assertTrue(server.result().isSuccess)
                assertNull(policies.load("localhost"))
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun verifiedTlsWithoutOptInPersistsNewStsPolicyAtCurrentPort() = runBlocking {
        TlsLoopback("server", stsAdvertisementDuration = 3600).use { server ->
            val policies = InMemoryStsPolicyStore()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val connection = IrcConnection(
                IrcConnectionConfig(
                    host = "localhost",
                    port = server.port,
                    nick = "insecure-test",
                    capabilities = setOf("server-time"),
                ),
                nowMillis = { 1_000_000 },
                stsPolicyStore = policies,
            )
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                collector.awaitRegistered()
                assertEquals(StsPolicy(server.port, 4600, 3600), policies.load("localhost"))
                connection.disconnect()
                collector.awaitInstance<IrcEvent.Disconnected>()
                assertTrue(server.result().isSuccess)
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test
    fun activeStsVerifiedTlsWithSavedOptInSavesDeletesAndRefreshesPolicies() = runBlocking {
        for ((tlsRequested, matchingPort) in listOf(true to true, false to false, true to false)) {
            for (duration in listOf(0L, 3600L)) {
                TlsLoopback("server", stsAdvertisementDuration = duration).use { server ->
                    val policies = InMemoryStsPolicyStore().apply {
                        save("localhost", StsPolicy(server.port, 1100, 600))
                    }
                    val clock = AtomicLong(1_000_000)
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val connection = IrcConnection(
                        IrcConnectionConfig(
                            host = "localhost",
                            port = if (matchingPort) server.port else if (server.port == 6697) 6698 else 6697,
                            tls = tlsRequested,
                            nick = "insecure-test",
                            capabilities = setOf("server-time"),
                            allowInsecureCertificates = true,
                        ),
                        nowMillis = clock::get,
                        stsPolicyStore = policies,
                    )
                    try {
                        val collector = EventCollector(scope, connection.events)
                        connection.start()
                        collector.awaitRegistered()
                        val advertised = if (duration == 0L) null else StsPolicy(server.port, 4600, duration)
                        assertEquals(advertised, policies.load("localhost"))
                        clock.set(2_000_000)
                        connection.disconnect()
                        collector.awaitInstance<IrcEvent.Disconnected>()
                        assertEquals(advertised?.copy(expiresAtEpochSeconds = 5600), policies.load("localhost"))
                        assertTrue(server.result().isSuccess)
                    } finally {
                        connection.disconnect()
                        scope.cancel()
                    }
                }
            }
        }
    }

    @Test
    fun unverifiedTlsWithoutActiveStsCannotCreateDeleteOrRefreshPolicies() = runBlocking {
        for (duration in listOf(0L, 3600L)) {
            for (existingPolicy in listOf<StsPolicy?>(null, StsPolicy(6697, 4600, 3600))) {
                val policies = InMemoryStsPolicyStore()
                TlsLoopback(
                    "private",
                    stsAdvertisementDuration = duration,
                    beforeStsAdvertisement = {
                        if (existingPolicy != null) policies.save("localhost", existingPolicy)
                    },
                ).use { server ->
                    val clock = AtomicLong(1_000_000)
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val connection = IrcConnection(
                        IrcConnectionConfig(
                            host = "localhost",
                            port = server.port,
                            nick = "insecure-test",
                            capabilities = setOf("server-time"),
                            allowInsecureCertificates = true,
                        ),
                        nowMillis = clock::get,
                        stsPolicyStore = policies,
                    )
                    try {
                        val collector = EventCollector(scope, connection.events)
                        connection.start()
                        collector.awaitRegistered()
                        assertEquals(existingPolicy, policies.load("localhost"))
                        clock.set(2_000_000)
                        connection.disconnect()
                        collector.awaitInstance<IrcEvent.Disconnected>()
                        assertEquals(existingPolicy, policies.load("localhost"))
                        assertTrue(server.result().isSuccess)
                    } finally {
                        connection.disconnect()
                        scope.cancel()
                    }
                }
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

    @Test
    fun insecureSocketFactoryStillRejectsOtherEndpointsBeforeCreatingSockets() {
        val factory = createTlsSocketFactory("127.0.0.1", 6697, allowInsecureCertificates = true)
        val loopback = InetAddress.getByName("127.0.0.1")
        val other = InetAddress.getByName("127.0.0.2")
        assertFailsWith<java.io.IOException> { factory.createSocket("127.0.0.2", 6697) }
        assertFailsWith<java.io.IOException> { factory.createSocket("127.0.0.1", 6698) }
        assertFailsWith<java.io.IOException> { factory.createSocket("127.0.0.2", 6697, loopback, 0) }
        assertFailsWith<java.io.IOException> { factory.createSocket("127.0.0.1", 6698, loopback, 0) }
        assertFailsWith<java.io.IOException> { factory.createSocket(other, 6697) }
        assertFailsWith<java.io.IOException> { factory.createSocket(loopback, 6698) }
        assertFailsWith<java.io.IOException> { factory.createSocket(other, 6697, loopback, 0) }
        assertFailsWith<java.io.IOException> { factory.createSocket(loopback, 6698, loopback, 0) }
        Socket().use { raw ->
            assertFailsWith<java.io.IOException> { factory.createSocket(raw, "127.0.0.2", 6697, false) }
            assertFailsWith<java.io.IOException> { factory.createSocket(raw, "127.0.0.1", 6698, false) }
        }
    }

    @Test
    fun anUnconnectedInsecureSocketStillRejectsAnotherHostOrPortDuringHandshake() {
        TlsLoopback("private").use { server ->
            val factory = createTlsSocketFactory("127.0.0.1", server.port, allowInsecureCertificates = true)
            (factory.createSocket() as SSLSocket).use { socket ->
                socket.soTimeout = 5_000
                socket.connect(java.net.InetSocketAddress("127.0.0.1", server.port), 5_000)
                socket.startHandshake()
                socket.getOutputStream().write(7)
                socket.getOutputStream().flush()
            }
            assertTrue(server.result().isSuccess)
        }
        for (wrongHost in listOf(false, true)) {
            TlsLoopback("private").use { server ->
                val host = if (wrongHost) "127.0.0.2" else "127.0.0.1"
                val port = if (wrongHost) server.port else if (server.port == 6697) 6698 else 6697
                val factory = createTlsSocketFactory(host, port, allowInsecureCertificates = true)
                val failure = nonInspectableFailure {
                    (factory.createSocket() as SSLSocket).use { socket ->
                        socket.soTimeout = 5_000
                        socket.connect(java.net.InetSocketAddress("127.0.0.1", server.port), 5_000)
                        socket.startHandshake()
                    }
                }
                assertTrue(assertNotNull(failure.message).contains("endpoint does not match"))
                assertTrue(server.result().isFailure)
            }
        }
    }

    private suspend fun assertStsRejectsInvalidCertificates(tlsRequested: Boolean, matchingPort: Boolean) {
        val endpoints = listOf(
            "expired" to "localhost",
            "private" to "localhost",
            "server" to "wrong-host.invalid",
        ) + if (matchingPort) listOf("changed" to "localhost") else emptyList()
        for ((name, host) in endpoints) {
            TlsLoopback(name, expectIrcRegistration = true).use { server ->
                val policy = StsPolicy(server.port, 4600, 3600)
                val policies = InMemoryStsPolicyStore().apply { save(host, policy) }
                val outbound = CopyOnWriteArrayList<String>()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val pin = when (name) {
                    "expired" -> sha256(certificate("expired"))
                    "changed" -> sha256(certificate("private"))
                    else -> null
                }
                val connection = IrcConnection(
                    IrcConnectionConfig(
                        host = host,
                        port = if (matchingPort) server.port else if (server.port == 6697) 6698 else 6697,
                        tls = tlsRequested,
                        nick = "insecure-test",
                        capabilities = emptySet(),
                        trustedCertificateSha256 = pin,
                        allowInsecureCertificates = true,
                    ),
                    socketFactory = if (host == "localhost") null else LoopbackRedirectingSocketFactory(server.port),
                    nowMillis = { 1_000_000 },
                    rawTap = { sent, line -> if (sent) outbound += line },
                    stsPolicyStore = policies,
                )
                try {
                    val collector = EventCollector(scope, connection.events)
                    connection.start()
                    val events = collector.drainUntilDisconnected()
                    val failure = assertNotNull((events.last() as IrcEvent.Disconnected).cause)
                    assertTrue(generateSequence(failure) { it.cause }.any { it is CertificateException })
                    assertFalse(events.any { it is IrcEvent.ConnectionOpened })
                    assertTrue(outbound.isEmpty())
                    assertEquals(policy, policies.load(host))
                    assertTrue(server.result().isFailure)
                } finally {
                    connection.disconnect()
                    scope.cancel()
                }
            }
        }
    }

    private class LoopbackRedirectingSocketFactory(private val targetPort: Int) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                assertEquals(targetPort, (endpoint as InetSocketAddress).port)
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

    private fun connect(
        server: TlsLoopback,
        host: String = "localhost",
        pin: String? = null,
        identity: TlsClientIdentity? = null,
        allowInsecureCertificates: Boolean = false,
    ) {
        Socket(InetAddress.getLoopbackAddress(), server.port).use { raw ->
            raw.soTimeout = 5_000
            val factory = createTlsSocketFactory(host, server.port, identity, pin, allowInsecureCertificates)
            (factory.createSocket(raw, host, server.port, true) as SSLSocket).use { socket ->
                socket.soTimeout = 5_000
                if (!allowInsecureCertificates) assertEquals("HTTPS", socket.sslParameters.endpointIdentificationAlgorithm)
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
        expectedServerName: String? = null,
        expectIrcRegistration: Boolean = false,
        stsAdvertisementDuration: Long? = null,
        beforeStsAdvertisement: (() -> Unit)? = null,
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
                        if (expectedServerName != null) {
                            val session = socket.session as ExtendedSSLSession
                            assertEquals(listOf(expectedServerName), session.requestedServerNames.map { (it as SNIHostName).asciiName })
                        }
                        if (stsAdvertisementDuration != null) {
                            val reader = socket.getInputStream().bufferedReader()
                            val writer = socket.getOutputStream().bufferedWriter()
                            assertEquals("CAP LS 302", reader.readLine())
                            beforeStsAdvertisement?.invoke()
                            writer.write(":srv CAP * LS :sts=duration=$stsAdvertisementDuration\r\n")
                            writer.flush()
                            assertEquals("CAP END", reader.readLine())
                            assertEquals("NICK insecure-test", reader.readLine())
                            assertEquals("USER yardhal 0 * :Yardhal", reader.readLine())
                            writer.write(":srv 001 insecure-test :Welcome\r\n")
                            writer.flush()
                            assertNull(reader.readLine())
                        } else if (expectIrcRegistration) {
                            val reader = socket.getInputStream().bufferedReader()
                            assertEquals("NICK insecure-test", reader.readLine())
                            assertEquals("USER yardhal 0 * :Yardhal", reader.readLine())
                        } else {
                            assertEquals(7, socket.getInputStream().read())
                        }
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
