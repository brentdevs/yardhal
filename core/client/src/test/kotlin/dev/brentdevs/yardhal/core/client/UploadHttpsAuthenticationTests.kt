package dev.brentdevs.yardhal.core.client

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class UploadHttpsAuthenticationTests {
    @Test
    fun realHttpsUploadsSendBasicCredentialsOnlyToTheChosenEndpoint() {
        val context = context()
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        val authorization = AtomicReference<String?>()
        server.createContext("/upload") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Location", "/files/owned")
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        val original = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
        server.start()
        try {
            val file = OutgoingFile("owned.bin", "application/octet-stream", 3) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
            val endpoint = "https://localhost:${server.address.port}/upload"
            val uploaded = FilehostUploader.upload(endpoint, file, true, "provider-user", "provider-password")
            assertEquals("Basic cHJvdmlkZXItdXNlcjpwcm92aWRlci1wYXNzd29yZA==", authorization.get())
            assertEquals("https://localhost:${server.address.port}/files/owned", uploaded.url)
        } finally {
            server.stop(0)
            HttpsURLConnection.setDefaultSSLSocketFactory(original)
        }
    }

    @Test
    fun successfulHttpsResponseCannotReturnAnHttpOrCredentialedUrl() {
        val context = context()
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        val location = AtomicReference("http://example.com/file")
        server.createContext("/upload") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Location", location.get())
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        val original = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
        server.start()
        try {
            val file = OutgoingFile("owned.bin", "application/octet-stream", 1) { ByteArrayInputStream(byteArrayOf(1)) }
            val endpoint = "https://localhost:${server.address.port}/upload"
            assertFailsWith<FilehostException.InsecureTransport> { FilehostUploader.upload(endpoint, file, true) }
            location.set("https://user:password@example.com/file")
            assertFailsWith<FilehostException.UnsafeUrl> { FilehostUploader.upload(endpoint, file, true) }
        } finally {
            server.stop(0)
            HttpsURLConnection.setDefaultSSLSocketFactory(original)
        }
    }

    @Test
    fun authenticatedRedirectsNeverContactHttpsOrDowngradeTargetsOrForwardCredentials() {
        val context = context()
        val source = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val secureTarget = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val insecureTarget = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        source.httpsConfigurator = HttpsConfigurator(context)
        secureTarget.httpsConfigurator = HttpsConfigurator(context)
        val targetRequests = AtomicInteger()
        val targetAuthorization = AtomicReference<String?>()
        for (target in listOf(secureTarget, insecureTarget)) {
            target.createContext("/target") { exchange ->
                targetRequests.incrementAndGet()
                targetAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
                exchange.requestBody.use { it.readBytes() }
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            }
        }
        val location = AtomicReference("")
        val status = AtomicInteger(307)
        val sourceAuthorization = AtomicReference<String?>()
        val sourceRequests = AtomicInteger()
        source.createContext("/upload") { exchange ->
            sourceRequests.incrementAndGet()
            sourceAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Location", location.get())
            exchange.sendResponseHeaders(status.get(), -1)
            exchange.close()
        }
        val original = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
        source.start()
        secureTarget.start()
        insecureTarget.start()
        try {
            val file = OutgoingFile("owned.bin", "application/octet-stream", 1) { ByteArrayInputStream(byteArrayOf(1)) }
            for (target in listOf("https://localhost:${secureTarget.address.port}/target", "http://localhost:${insecureTarget.address.port}/target")) {
                location.set(target)
                for (code in listOf(301, 302, 303, 307, 308)) {
                    status.set(code)
                    val failure = assertFailsWith<FilehostException.HttpStatus> {
                        FilehostUploader.upload("https://localhost:${source.address.port}/upload", file, true, "provider-user", "provider-password")
                    }
                    assertEquals(code, failure.code)
                    assertEquals("Basic cHJvdmlkZXItdXNlcjpwcm92aWRlci1wYXNzd29yZA==", sourceAuthorization.get())
                    assertEquals(0, targetRequests.get())
                    assertNull(targetAuthorization.get())
                }
            }
            assertEquals(10, sourceRequests.get())
        } finally {
            source.stop(0)
            secureTarget.stop(0)
            insecureTarget.stop(0)
            HttpsURLConnection.setDefaultSSLSocketFactory(original)
        }
    }

    private fun context(): SSLContext {
        val password = "fixture-password".toCharArray()
        val resources = UploadHttpsAuthenticationTests::class.java
        val keys = KeyStore.getInstance("PKCS12").apply { requireNotNull(resources.getResourceAsStream("/tls/server.p12")).use { load(it, password) } }
        val trust = KeyStore.getInstance("PKCS12").apply { requireNotNull(resources.getResourceAsStream("/tls/trusted.p12")).use { load(it, password) } }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, trustManagers.trustManagers, null) }
    }
}
