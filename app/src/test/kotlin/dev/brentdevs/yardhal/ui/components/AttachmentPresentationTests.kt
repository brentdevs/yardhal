package dev.brentdevs.yardhal.ui.components

import dev.brentdevs.yardhal.ui.image.MediaDescriptor
import dev.brentdevs.yardhal.ui.image.MediaKind
import dev.brentdevs.yardhal.ui.image.MediaPolicy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentPresentationTests {
    @Test
    fun restoredMetadataNamesSignedAttachmentsWithoutChangingTheirIdentity() {
        val label = MediaPolicy.displayName(MediaDescriptor("https://files.test/asset?signature=secret", "photo.png", "image/png", 2048L))
        assertTrue(label.contains("photo.png"))
        assertFalse(label.contains("signature=secret"))
        assertTrue(MediaPolicy.kind("https://files.test/asset?signature=secret", "image/png") == MediaKind.IMAGE)
    }

    @Test
    fun existingUrlOnlyAttachmentsRetainTheirPresentation() {
        assertTrue(MediaPolicy.displayName(MediaDescriptor("https://files.test/photo.png?signature=secret#part")).contains("photo.png"))
        assertTrue(MediaPolicy.kind("https://files.test/photo.PNG?signature=secret", null) == MediaKind.IMAGE)
        assertFalse(MediaPolicy.kind("https://files.test/notes.txt", null) == MediaKind.IMAGE)
    }

    @Test
    fun explicitMimeMetadataTakesPrecedenceOverAnImageLookingPath() {
        assertFalse(MediaPolicy.kind("https://files.test/file.png", "application/pdf") == MediaKind.IMAGE)
        assertTrue(MediaPolicy.displayName(MediaDescriptor("https://files.test/asset", "document.pdf")).contains("document.pdf"))
    }
}
