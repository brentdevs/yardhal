package dev.brentdevs.yardhal.core.client

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilehostUploaderTests {

    private lateinit var server: HttpServer
    private var port: Int = 0

    private val lastRequest = AtomicReference<com.sun.net.httpserver.HttpExchange?>(null)
    private val lastBody = AtomicReference<ByteArray>(ByteArray(0))
    private var responder: (com.sun.net.httpserver.HttpExchange) -> Unit = { exchange ->
        exchange.responseHeaders.add("Location", "/f/uploaded.bin")
        exchange.sendResponseHeaders(201, -1)
        exchange.close()
    }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        port = server.address.port
        server.createContext("/upload") { exchange ->
            lastRequest.set(exchange)
            lastBody.set(exchange.requestBody.readBytes())
            responder(exchange)
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun endpoint(tls: Boolean = false): String =
        if (tls) "https://127.0.0.1:$port/upload" else "http://127.0.0.1:$port/upload"

    private fun file(): OutgoingFile = OutgoingFile(
        name = "picture.jpeg",
        mimeType = "image/jpeg",
        bytes = ByteArray(128) { it.toByte() },
    )

    @Test
    fun rawPostSendsHeadersAndResolvesRelativeLocation() {
        responder = { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("image/jpeg", exchange.requestHeaders.getFirst("Content-Type"))
            assertTrue(exchange.requestHeaders.getFirst("Content-Disposition").contains("picture.jpeg"))
            exchange.responseHeaders.add("Location", "/f/abc.jpeg")
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        val uploaded = FilehostUploader.upload(endpoint(), file(), ircConnectionIsTls = false)
        assertEquals("http://127.0.0.1:$port/f/abc.jpeg", uploaded.url)
    }

    @Test
    fun basicAuthAppliedWhenSaslCredentialsPresent() {
        responder = { exchange ->
            assertEquals("Basic amlsbGVzOnNlc2FtZQ==", exchange.requestHeaders.getFirst("Authorization"))
            exchange.responseHeaders.add("Location", "/f/x")
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        FilehostUploader.upload(endpoint(), file(), ircConnectionIsTls = false, saslUser = "jilles", saslPassword = "sesame")
    }

    @Test
    fun noAuthHeaderWithoutCredentials() {
        var seenAuthorization: String? = null
        responder = { exchange ->
            seenAuthorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.responseHeaders.add("Location", "/f/x")
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        FilehostUploader.upload(endpoint(), file(), ircConnectionIsTls = false)
        assertNull(seenAuthorization)
    }

    @Test
    fun bodyFormatRejectionRetriesMultipartThenSucceeds() {
        var attempts = 0
        responder = { exchange ->
            attempts += 1
            if (attempts == 1) {
                assertEquals("image/jpeg", exchange.requestHeaders.getFirst("Content-Type"))
                exchange.sendResponseHeaders(415, -1)
                exchange.close()
            } else {
                assertEquals("multipart/form-data", exchange.requestHeaders.getFirst("Content-Type")!!.substringBefore(';'))
                val body = lastBody.get().toString(Charsets.UTF_8)
                assertTrue(body.contains("name=\"file\"; filename=\"picture.jpeg\""))
                exchange.responseHeaders.add("Location", "/f/multi.bin")
                exchange.sendResponseHeaders(201, -1)
                exchange.close()
            }
        }
        val uploaded = FilehostUploader.upload(endpoint(), file(), ircConnectionIsTls = false)
        assertEquals(2, attempts)
        assertTrue(uploaded.url.endsWith("/f/multi.bin"))
    }

    @Test
    fun nonRejectionStatusDoesNotRetry() {
        var attempts = 0
        responder = { exchange ->
            attempts += 1
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        assertFailsWith<FilehostException.HttpStatus> {
            FilehostUploader.upload(endpoint(), file(), ircConnectionIsTls = false)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun unauthorizedSurfacesAsSuch() {
        responder = { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        assertFailsWith<FilehostException.Unauthorized> {
            FilehostUploader.upload(endpoint(), file(), ircConnectionIsTls = false)
        }
    }

    @Test
    fun missingLocationHeaderRejected() {
        responder = { exchange ->
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        assertFailsWith<FilehostException.MissingLocation> {
            FilehostUploader.upload(endpoint(), file(), ircConnectionIsTls = false)
        }
    }

    @Test
    fun plainHttpBlockedWhenIrcConnectionUsesTls() {
        assertFailsWith<FilehostException.InsecureTransport> {
            FilehostUploader.upload(endpoint(tls = false), file(), ircConnectionIsTls = true)
        }
    }
}
