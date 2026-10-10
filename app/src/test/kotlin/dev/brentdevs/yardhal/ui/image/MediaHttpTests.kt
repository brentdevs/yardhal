package dev.brentdevs.yardhal.ui.image

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaHttpTests {
    @Test
    fun headImageAtExactByteBoundaryNeedsNoBodyAndOversizeIsRejected() {
        val boundary = FakeConnection(type = "image/gif", length = MediaPolicy.MAX_IMAGE_BYTES.toLong())
        val accepted = MediaHttp { boundary }.probe(boundary.url.toString(), MediaRequestCancellation())
        assertEquals(MediaProbeResult.Image("image/gif", MediaPolicy.MAX_IMAGE_BYTES.toLong()), accepted)
        assertEquals("HEAD", boundary.requestMethod)
        assertEquals(0, boundary.bodyReads)
        assertFalse(boundary.bodyOpened)
        assertTrue(boundary.disconnected)
        val oversized = FakeConnection(type = "image/png", length = MediaPolicy.MAX_IMAGE_BYTES + 1L)
        assertEquals(MediaProbeResult.NotImage, MediaHttp { oversized }.probe(oversized.url.toString(), MediaRequestCancellation()))
        assertEquals(0, oversized.bodyReads)
    }

    @Test
    fun unsupportedHeadFallsBackToRangeHeadersWithoutReadingAnUnusedBody() {
        val head = FakeConnection(status = 405)
        val range = FakeConnection(type = "image/png", body = ByteArray(MediaPolicy.PROBE_BYTES * 2))
        val connections = ArrayDeque(listOf(head, range))
        assertEquals(MediaProbeResult.Image("image/png", null), MediaHttp { connections.removeFirst() }.probe(head.url.toString(), MediaRequestCancellation()))
        assertEquals("GET", range.requestMethod)
        assertEquals("bytes=0-1023", range.getRequestProperty("Range"))
        assertEquals(0, range.bodyReads)
        assertFalse(range.bodyOpened)
        assertTrue(range.disconnected)
    }

    @Test
    fun contentRangeUsesWholeResourceLengthInsteadOfSmallRangeLength() {
        val head = FakeConnection(status = 501)
        val range = FakeConnection(status = 206, type = "image/png", length = 1024, headers = mapOf("Content-Range" to "bytes 0-1023/${MediaPolicy.MAX_IMAGE_BYTES + 1L}"))
        val connections = ArrayDeque(listOf(head, range))
        assertEquals(MediaProbeResult.NotImage, MediaHttp { connections.removeFirst() }.probe(head.url.toString(), MediaRequestCancellation()))
        assertEquals(0, range.bodyReads)
    }

    @Test
    fun extensionlessVideoAndHtmlAreNotPromotedToInlineMedia() {
        for (type in listOf("video/mp4", "text/html")) {
            val connection = FakeConnection(type = type)
            assertEquals(MediaProbeResult.NotImage, MediaHttp { connection }.probe(connection.url.toString(), MediaRequestCancellation()))
            assertEquals(0, connection.bodyReads)
        }
    }

    @Test
    fun rangeFailureIsUnavailableAndUnknownContentIsNotAnImage() {
        val head = FakeConnection(status = 405)
        val failed = FakeConnection(status = 403)
        val connections = ArrayDeque(listOf(head, failed))
        assertEquals(MediaProbeResult.Unavailable, MediaHttp { connections.removeFirst() }.probe(head.url.toString(), MediaRequestCancellation()))
        val unknownHead = FakeConnection(type = "application/octet-stream")
        val unknownRange = FakeConnection(type = "application/octet-stream")
        val unknown = ArrayDeque(listOf(unknownHead, unknownRange))
        assertEquals(MediaProbeResult.NotImage, MediaHttp { unknown.removeFirst() }.probe(head.url.toString(), MediaRequestCancellation()))
    }

    @Test
    fun failedHeadDoesNotRepeatRequestAndRejectedRedirectNeverGetsASecondMethod() {
        var count = 0
        val failed = MediaHttp { count++; FakeConnection(status = 403) }
        assertEquals(MediaProbeResult.Unavailable, failed.probe("https://files.test/asset", MediaRequestCancellation()))
        assertEquals(1, count)
        count = 0
        val unsafe = MediaHttp { count++; FakeConnection(status = 302, headers = mapOf("Location" to "http://files.test/asset")) }
        assertEquals(MediaProbeResult.Unavailable, unsafe.probe("https://files.test/asset", MediaRequestCancellation()))
        assertEquals(1, count)
    }

    @Test
    fun redirectsHaveAnExactHopLimitAndNeverContactDowngradedTargets() {
        var count = 0
        val successful = MediaHttp { url ->
            count++
            if (count <= MediaPolicy.MAX_REDIRECTS) FakeConnection(url = url, status = 302, headers = mapOf("Location" to "/hop$count"))
            else FakeConnection(url = url, type = "image/png")
        }
        assertTrue(successful.probe("https://files.test/start", MediaRequestCancellation()) is MediaProbeResult.Image)
        assertEquals(MediaPolicy.MAX_REDIRECTS + 1, count)
        count = 0
        val endless = MediaHttp { url -> count++; FakeConnection(url = url, status = 302, headers = mapOf("Location" to "/loop")) }
        assertNull(endless.download("https://files.test/start", 10, false, MediaRequestCancellation()))
        assertEquals(MediaPolicy.MAX_REDIRECTS + 1, count)
        count = 0
        val downgrade = MediaHttp { url -> count++; FakeConnection(url = url, status = 302, headers = mapOf("Location" to "http://files.test/image.png")) }
        assertNull(downgrade.download("https://files.test/start", 10, false, MediaRequestCancellation()))
        assertEquals(1, count)
    }

    @Test
    fun streamingSizeLimitIncludesExactBoundaryAndRejectsUnknownOversizeBodies() {
        val exact = FakeConnection(type = "image/png", body = ByteArray(10))
        assertEquals(10, MediaHttp { exact }.download(exact.url.toString(), 10, false, MediaRequestCancellation())?.size)
        val oversized = FakeConnection(type = "image/png", body = ByteArray(11))
        assertNull(MediaHttp { oversized }.download(oversized.url.toString(), 10, false, MediaRequestCancellation()))
        assertTrue(oversized.disconnected)
    }

    @Test
    fun cancellationPreventsAnyConnectionAndDisconnectsAnAttachedConnection() {
        val cancellation = MediaRequestCancellation()
        var contacted = false
        cancellation.cancel()
        assertFailsWith<CancellationException> {
            MediaHttp { contacted = true; FakeConnection() }.probe("https://files.test/asset", cancellation)
        }
        assertFalse(contacted)
        val active = MediaRequestCancellation()
        val connection = FakeConnection()
        active.attach(connection)
        active.cancel()
        assertTrue(connection.disconnected)
        assertFailsWith<CancellationException> { active.check() }
    }

    private class FakeConnection(
        url: URL = URL("https://files.test/asset"),
        private val status: Int = 200,
        private val type: String? = null,
        private val length: Long = -1,
        private val headers: Map<String, String> = emptyMap(),
        private val body: ByteArray = byteArrayOf(),
    ) : HttpURLConnection(url) {
        var bodyReads = 0
        var bodyOpened = false
        var disconnected = false
        override fun getResponseCode(): Int = status
        override fun getContentType(): String? = type
        override fun getContentLengthLong(): Long = length
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getInputStream(): InputStream {
            bodyOpened = true
            return object : ByteArrayInputStream(body) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) bodyReads += it }
            }
        }
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
    }
}
