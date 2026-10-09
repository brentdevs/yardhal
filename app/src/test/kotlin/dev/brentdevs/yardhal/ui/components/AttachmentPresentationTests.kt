package dev.brentdevs.yardhal.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentPresentationTests {
    @Test
    fun restoredMetadataNamesSignedAttachmentsWithoutChangingTheirIdentity() {
        val label = attachmentDisplayName("https://files.test/asset?signature=secret", "photo.png", 2048L)
        assertTrue(label.contains("photo.png"))
        assertFalse(label.contains("signature=secret"))
        assertTrue(isImageAttachment("https://files.test/asset?signature=secret", "image/png"))
    }

    @Test
    fun existingUrlOnlyAttachmentsRetainTheirPresentation() {
        assertTrue(attachmentDisplayName("https://files.test/photo.png?signature=secret#part", null, null).contains("photo.png"))
        assertTrue(isImageAttachment("https://files.test/photo.PNG?signature=secret", null))
        assertFalse(isImageAttachment("https://files.test/notes.txt", null))
    }

    @Test
    fun explicitMimeMetadataTakesPrecedenceOverAnImageLookingPath() {
        assertFalse(isImageAttachment("https://files.test/file.png", "application/pdf"))
        assertTrue(attachmentDisplayName("https://files.test/asset", "document.pdf", null).contains("document.pdf"))
    }
}
