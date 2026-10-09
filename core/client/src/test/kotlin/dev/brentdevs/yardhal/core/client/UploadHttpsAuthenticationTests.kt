package dev.brentdevs.yardhal.core.client

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
