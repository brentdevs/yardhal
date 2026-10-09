package dev.brentdevs.yardhal.ui

import dev.brentdevs.yardhal.core.data.AttachmentDestination
import dev.brentdevs.yardhal.core.data.AttachmentInsertion
import dev.brentdevs.yardhal.core.data.StagedAttachment
import dev.brentdevs.yardhal.core.data.UploadedAttachment
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class AttachmentDraftRoutingTests {
    private val upload = UploadedAttachment("https://files.example/photo", "photo.jpg", "image/jpeg", 1200)
    private val original = AttachmentDestination("first", "first|#original", "First · #original")

    @Test fun completingUploadCannotInsertIntoNewlySelectedConversationOrNetwork() {
        val insertion = AttachmentInsertion("pending", original, listOf(upload), "caption")
        assertNull(pendingAttachmentInsertion("first|#next", listOf(insertion)))
        assertNull(pendingAttachmentInsertion("second|#original", listOf(insertion)))
        assertEquals(insertion, pendingAttachmentInsertion(original.storageKey, listOf(insertion)))
    }

    @Test fun inconsistentStoredDestinationCannotCrossNetworkBoundary() {
        val inconsistent = original.copy(networkId = "second")
        val insertion = AttachmentInsertion("pending", inconsistent, listOf(upload), "")
        assertNull(pendingAttachmentInsertion(original.storageKey, listOf(insertion)))
        assertEquals(emptyList(), messageUploads(original.storageKey, upload.url, listOf(staged(inconsistent))))
    }

    @Test fun sendingUrlOnlyConsumesExactUploadedUrlFromItsOwnConversation() {
        val another = original.copy(storageKey = "first|#another")
        val staged = listOf(staged(original), staged(another))
        assertEquals(listOf(upload), messageUploads(original.storageKey, "caption\n${upload.url}", staged))
        assertEquals(emptyList(), messageUploads(original.storageKey, "${upload.url}/different", staged))
        assertEquals(emptyList(), messageUploads(original.storageKey, "caption without the URL", staged))
    }

    @Test fun localOrNonMessageCommandDoesNotConsumeStagedUpload() {
        val staged = listOf(staged(original))
        assertEquals(emptyList(), messageUploads(original.storageKey, "/whois ${upload.url}", staged))
        assertEquals(emptyList(), messageUploads(original.storageKey, "/quit ${upload.url}", staged))
        assertEquals(listOf(upload), messageUploads(original.storageKey, "/me shares ${upload.url}", staged))
    }

    private fun staged(destination: AttachmentDestination): StagedAttachment = StagedAttachment(
        id = destination.storageKey,
        batchId = "batch",
        destination = destination,
        sourceUri = "content://owned/photo",
        uploaded = upload,
    )
}
