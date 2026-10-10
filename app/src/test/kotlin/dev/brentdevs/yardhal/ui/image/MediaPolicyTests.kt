package dev.brentdevs.yardhal.ui.image

import dev.brentdevs.yardhal.core.data.MediaRevealState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaPolicyTests {
    @Test
    fun disabledAutomaticLoadingRequiresRevealAndHiddenOverridesAutomaticLoading() {
        assertFalse(MediaPolicy.mayLoad(false, MediaRevealState.DEFAULT, true, true))
        assertTrue(MediaPolicy.mayLoad(false, MediaRevealState.REVEALED, true, true))
        assertFalse(MediaPolicy.mayLoad(true, MediaRevealState.HIDDEN, true, true))
        assertFalse(MediaPolicy.mayLoad(true, MediaRevealState.REVEALED, false, true))
        assertFalse(MediaPolicy.mayLoad(true, MediaRevealState.REVEALED, true, false))
    }

    @Test
    fun animationStopsOffscreenInBackgroundAndWithReducedMotion() {
        assertTrue(MediaPolicy.mayAnimate(true, false, true, true))
        assertFalse(MediaPolicy.mayAnimate(true, true, true, true))
        assertFalse(MediaPolicy.mayAnimate(true, false, false, true))
        assertFalse(MediaPolicy.mayAnimate(true, false, true, false))
        assertFalse(MediaPolicy.mayAnimate(false, false, true, true))
    }

    @Test
    fun dimensionAndPixelLimitsHaveExactInclusiveBoundaries() {
        assertTrue(MediaPolicy.dimensionsAllowed(8192, 4096))
        assertFalse(MediaPolicy.dimensionsAllowed(8193, 4096))
        assertFalse(MediaPolicy.dimensionsAllowed(8192, 4097))
        assertFalse(MediaPolicy.dimensionsAllowed(0, 1))
        assertEquals(1024 to 512, MediaPolicy.targetDimensions(8192, 4096, 1024))
        assertEquals(1 to 1024, MediaPolicy.targetDimensions(1, 8192, 1024))
    }

    @Test
    fun extensionlessUrlsNeverBecomeVideosWithoutExplicitMetadata() {
        assertEquals(MediaKind.UNKNOWN, MediaPolicy.kind("https://files.test/asset?format=mp4", null))
        assertEquals(MediaKind.VIDEO, MediaPolicy.kind("https://files.test/asset", "video/mp4"))
        assertEquals(MediaKind.VIDEO, MediaPolicy.kind("https://files.test/movie.MP4?token=a", null))
        assertEquals(MediaKind.FILE, MediaPolicy.kind("https://files.test/photo.png", "application/pdf"))
        assertEquals(MediaKind.IMAGE, MediaPolicy.kind("https://files.test/asset", "Image/Gif; charset=binary"))
    }

    @Test
    fun redirectsRetainHttpsWithoutCredentialsAndStopUnsafeSchemes() {
        assertEquals("https://files.test/image", MediaPolicy.redirect("https://files.test/start", "/image"))
        assertNull(MediaPolicy.redirect("https://files.test/start", "http://files.test/image"))
        assertNull(MediaPolicy.redirect("https://files.test/start", "https://user:secret@files.test/image"))
        assertNull(MediaPolicy.redirect("https://files.test/start", "file:///image"))
    }

    @Test
    fun linkedDiscoveryIsBoundedDistinctAndPreservesSignedUrlIdentity() {
        val signed = "https://files.test/asset?signature=one%2Ftwo&format=png"
        val links = MediaPolicy.linkedMedia("$signed $signed https://files.test/2 https://files.test/3 https://files.test/4 https://files.test/5", null)
        assertEquals(MediaPolicy.MAX_LINKS_PER_MESSAGE, links.size)
        assertEquals(signed, links.first().url)
        assertTrue(MediaPolicy.linkedMedia(signed, signed).isEmpty())
        assertTrue(MediaPolicy.linkedMedia("http://files.test/a.png file:///a.png", null).isEmpty())
    }
}
