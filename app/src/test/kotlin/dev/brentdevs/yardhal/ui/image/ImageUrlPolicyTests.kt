package dev.brentdevs.yardhal.ui.image

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageUrlPolicyTests {

    @Test
    fun httpsUrlsAreAccepted() {
        assertEquals("https://example.com/a.png", ImageUrlPolicy.resolve(" https://example.com/a.png ", 64))
        assertTrue(ImageUrlPolicy.isAllowed("HTTPS://example.com/a.png"))
    }

    @Test
    fun browserLikeHttpsPolicyKeepsInternalEndpointsAndRedirectsWithoutPretendingToFilterDns() {
        for (url in listOf(
            "https://127.0.0.1/avatar.png",
            "https://[::1]/avatar.png",
            "https://10.0.2.2:8443/avatar.png",
            "https://192.168.1.2/avatar.png",
            "https://169.254.1.2/avatar.png",
            "https://files.internal/avatar.png",
            "https://files/avatar.png",
        )) {
            assertEquals(url, ImageUrlPolicy.resolve(url, 64))
            assertEquals(url, MediaPolicy.redirect("https://public.example/image", url))
        }
    }

    @Test
    fun nonHttpsSchemesAreRejected() {
        assertNull(ImageUrlPolicy.resolve("http://example.com/a.png", 64))
        assertNull(ImageUrlPolicy.resolve("file:///sdcard/a.png", 64))
        assertNull(ImageUrlPolicy.resolve("content://media/1", 64))
        assertNull(ImageUrlPolicy.resolve("javascript:alert(1)", 64))
        assertNull(ImageUrlPolicy.resolve("//example.com/a.png", 64))
        assertFalse(ImageUrlPolicy.isAllowed("http://example.com/a.png"))
    }

    @Test
    fun malformedOrCredentialedUrlsAreRejected() {
        assertNull(ImageUrlPolicy.resolve(null, 64))
        assertNull(ImageUrlPolicy.resolve("", 64))
        assertNull(ImageUrlPolicy.resolve("https://", 64))
        assertNull(ImageUrlPolicy.resolve("https://user:pw@example.com/a.png", 64))
        assertNull(ImageUrlPolicy.resolve("https://exa mple.com/a.png", 64))
        assertNull(ImageUrlPolicy.resolve("https://example.com/" + "a".repeat(ImageUrlPolicy.MAX_URL_LENGTH), 64))
    }

    @Test
    fun urlLengthAndPortBoundsAreEnforcedAfterTemplateAndAsciiExpansion() {
        val prefix = "https://example.com/"
        val boundary = prefix + "a".repeat(ImageUrlPolicy.MAX_URL_LENGTH - prefix.length)
        assertEquals(boundary, ImageUrlPolicy.resolve(boundary, 64))
        assertNull(ImageUrlPolicy.resolve(boundary + "a", 64))
        assertNull(ImageUrlPolicy.resolve(prefix + "{size}".repeat((ImageUrlPolicy.MAX_URL_LENGTH - prefix.length) / 6), Int.MAX_VALUE))
        assertNull(ImageUrlPolicy.resolve(prefix + "é".repeat(400), 64))
        assertTrue(ImageUrlPolicy.isAllowed("https://example.com:65535/image.png"))
        assertNull(ImageUrlPolicy.resolve("https://example.com:65536/image.png", 64))
        assertNull(ImageUrlPolicy.resolve("https://example.com:0/image.png", 64))
    }

    @Test
    fun sizeTemplateIsExpanded() {
        assertEquals("https://example.net/icon.png?size=96", ImageUrlPolicy.resolve("https://example.net/icon.png?size={size}", 96))
        assertEquals("https://example.com/avatar/64/x.jpg", ImageUrlPolicy.resolve("https://example.com/avatar/{size}/x.jpg", 64))
    }

    @Test
    fun contentLengthCapIsEnforced() {
        assertTrue(ImageUrlPolicy.acceptsContentLength(-1))
        assertTrue(ImageUrlPolicy.acceptsContentLength(ImageUrlPolicy.MAX_BYTES.toLong()))
        assertFalse(ImageUrlPolicy.acceptsContentLength(ImageUrlPolicy.MAX_BYTES + 1L))
    }

    @Test
    fun readCappedStopsAtLimit() {
        val small = ByteArray(10) { it.toByte() }
        assertEquals(small.toList(), ImageUrlPolicy.readCapped(ByteArrayInputStream(small), 10)?.toList())
        assertNull(ImageUrlPolicy.readCapped(ByteArrayInputStream(ByteArray(11)), 10))
        assertNull(ImageUrlPolicy.readCapped(ByteArrayInputStream(ByteArray(ImageUrlPolicy.MAX_BYTES + 1))))
        assertEquals(ImageUrlPolicy.MAX_BYTES, ImageUrlPolicy.readCapped(ByteArrayInputStream(ByteArray(ImageUrlPolicy.MAX_BYTES)))?.size)
    }

    @Test
    fun sampleSizeDownscalesByPowersOfTwo() {
        assertEquals(1, ImageUrlPolicy.sampleSize(100, 100, 96))
        assertEquals(2, ImageUrlPolicy.sampleSize(200, 200, 96))
        assertEquals(8, ImageUrlPolicy.sampleSize(1024, 1024, 96))
        assertEquals(4, ImageUrlPolicy.sampleSize(4000, 400, 96))
        assertEquals(1, ImageUrlPolicy.sampleSize(0, 0, 96))
    }
}
