package dev.brentdevs.yardhal.media

import android.content.Intent
import android.net.Uri
import dev.brentdevs.yardhal.core.data.AttachmentDestination
import dev.brentdevs.yardhal.core.data.AttachmentInsertion
import dev.brentdevs.yardhal.core.data.AttachmentStageStatus
import dev.brentdevs.yardhal.core.data.AttachmentStageStore
import dev.brentdevs.yardhal.core.data.InMemoryCredentialVault
import dev.brentdevs.yardhal.core.data.PhotoMetadataPolicy
import dev.brentdevs.yardhal.core.data.StagedAttachment
import dev.brentdevs.yardhal.core.data.UploadEnvironment
import dev.brentdevs.yardhal.core.data.UploadSettingsStore
import dev.brentdevs.yardhal.core.data.UploadedAttachment
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AttachmentStageManagerTests {
    @get:Rule val temporary = TemporaryFolder()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val destination = AttachmentDestination("network-a", "channel-a", "Network A · #a")
    private fun manager(access: AttachmentUriAccess, directory: File = File(temporary.root, "stages"), settings: UploadSettingsStore = settings()): AttachmentStageManager =
        AttachmentStageManager(directory, settings, scope, { if (it != "network-a") error("Destination network was removed") else UploadEnvironment(null, true) }, access)
    private fun settings(): UploadSettingsStore = UploadSettingsStore(File(temporary.root, "settings"), InMemoryCredentialVault())
    private fun access(bytes: ByteArray): AttachmentUriAccess = object : AttachmentUriAccess {
        override fun describe(uri: Uri): AttachmentUriDescription = AttachmentUriDescription("shared.bin", "application/octet-stream", null)
        override fun open(uri: Uri): InputStream = ByteArrayInputStream(bytes)
    }
    private suspend fun recreateManager(access: AttachmentUriAccess, directory: File = File(temporary.root, "stages")): AttachmentStageManager {
        scope.coroutineContext.job.children.toList().joinAll()
        scope.coroutineContext.job.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return manager(access, directory)
    }
    @After fun close() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    @Test
    fun unknownLengthOverLimitAndRevokedPermissionProduceActionableErrors() = runBlocking {
        val settings = settings().apply { updateLimits(4, PhotoMetadataPolicy.STRIP) }
        val oversized = manager(access(ByteArray(5)), settings = settings)
        oversized.stageIncoming(listOf(Uri.parse("content://owned/oversize")), destination = destination)
        val rejected = withTimeout(5000) { oversized.attachments.first { it.firstOrNull()?.status == AttachmentStageStatus.ERROR }.single() }
        assertTrue(rejected.error.orEmpty().contains("size limit"))
        val denied = manager(object : AttachmentUriAccess {
            override fun describe(uri: Uri): AttachmentUriDescription = throw SecurityException("revoked")
            override fun open(uri: Uri): InputStream = error("must not open")
        }, File(temporary.root, "denied"))
        denied.stageIncoming(listOf(Uri.parse("content://owned/revoked")), destination = destination)
        val revoked = withTimeout(5000) { denied.attachments.first { it.firstOrNull()?.status == AttachmentStageStatus.ERROR }.single() }
        assertTrue(revoked.error.orEmpty().contains("permission"))
        assertTrue(revoked.error.orEmpty().contains("share the file again"))
    }

    @Test
    fun unboundShareCopiesPrivatelyBeforeSelectionAndDestinationCannotChangeLater() = runBlocking {
        val stages = manager(access(byteArrayOf(1, 2, 3)))
        val batch = stages.stageIncoming(listOf(Uri.parse("content://owned/a"), Uri.parse("content://owned/b")), caption = "caption")
        withTimeout(5000) { stages.attachments.first { it.size == 2 && it.all { entry -> entry.status == AttachmentStageStatus.READY } } }
        assertTrue(stages.attachments.value.all { it.destination == null && it.caption == "caption" })
        stages.assignDestination(batch, destination)
        withTimeout(5000) { stages.attachments.first { it.all { entry -> entry.destination == destination } } }
        stages.assignDestination(batch, AttachmentDestination("other", "other", "Other"))
        val restored = recreateManager(access(byteArrayOf()), File(temporary.root, "stages"))
        assertTrue(restored.attachments.value.all { it.destination == destination })
        assertEquals(2, restored.attachments.value.size)
        restored.attachments.value.forEach { entry ->
            assertContentEquals(byteArrayOf(1, 2, 3), File(temporary.root, "stages/${entry.id}.source").readBytes())
            assertContentEquals(byteArrayOf(1, 2, 3), File(temporary.root, "stages/${entry.id}.ready").readBytes())
        }
    }

    @Test
    fun staleInFlightStagesRestoreAsInterruptedAndCompletedUploadsRemainInsertable() = runBlocking {
        val directory = File(temporary.root, "stages").apply { mkdirs() }
        val interrupted = StagedAttachment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), destination, "content://owned/a", status = AttachmentStageStatus.UPLOADING)
        val complete = StagedAttachment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), destination, "content://owned/b", caption = "caption", status = AttachmentStageStatus.UPLOADED,
            uploaded = UploadedAttachment("https://owned.example/file", "owned.bin", "application/octet-stream", 3))
        AttachmentStageStore(directory).save(listOf(interrupted, complete))
        val first = manager(access(byteArrayOf()), directory)
        assertEquals(AttachmentStageStatus.INTERRUPTED, first.attachments.value.first().status)
        val insertion = assertNotNull(first.prepareInsertion(listOf(complete.id)))
        assertEquals(destination, insertion.destination)
        assertEquals("caption", insertion.caption)
        val beforeCapture = recreateManager(access(byteArrayOf()), directory)
        assertEquals(insertion, beforeCapture.pendingInsertions().single())
        beforeCapture.ackInserted(insertion.token)
        withTimeout(5000) { beforeCapture.attachments.first { it.last().status == AttachmentStageStatus.INSERTED } }
        val cold = recreateManager(access(byteArrayOf()), directory)
        assertEquals("https://owned.example/file", cold.attachments.value.last().uploaded?.url)
        assertNotNull(cold.prepareInsertion(listOf(complete.id)))
        assertEquals(AttachmentStageStatus.INSERTED, cold.attachments.value.last().status)
    }

    @Test
    fun separatelyCompletedUploadsInOneBatchReserveCaptionOnlyOnceBeforeCapture() = runBlocking {
        val directory = File(temporary.root, "stages").apply { mkdirs() }
        val batch = UUID.randomUUID().toString()
        val a = StagedAttachment(UUID.randomUUID().toString(), batch, destination, "content://owned/a", caption = "one caption",
            status = AttachmentStageStatus.UPLOADED, uploaded = UploadedAttachment("https://owned.example/a", "a", "application/octet-stream", 1))
        val b = a.copy(id = UUID.randomUUID().toString(), sourceUri = "content://owned/b", uploaded = UploadedAttachment("https://owned.example/b", "b", "application/octet-stream", 1))
        AttachmentStageStore(directory).save(listOf(a, b))
        val stages = manager(access(byteArrayOf()), directory)
        assertEquals("one caption", assertNotNull(stages.prepareInsertion(listOf(a.id))).caption)
        assertEquals("", assertNotNull(stages.prepareInsertion(listOf(b.id))).caption)
        val restored = recreateManager(access(byteArrayOf()), directory)
        assertEquals(listOf("one caption", ""), restored.pendingInsertions().map { it.caption })
    }

    @Test
    fun enormousSharesRejectTheWholeBatchVisiblyWithoutOpeningAnyUri() = runBlocking {
        val stages = manager(object : AttachmentUriAccess {
            override fun describe(uri: Uri): AttachmentUriDescription = error("rejected batch must not query")
            override fun open(uri: Uri): InputStream = error("rejected batch must not open")
        })
        stages.stageIncoming((0..AttachmentStageManager.MAXIMUM_SHARE_COUNT).map { Uri.parse("content://owned/$it") })
        val rejected = stages.attachments.value.single()
        assertEquals(AttachmentStageStatus.ERROR, rejected.status)
        assertTrue(rejected.error.orEmpty().contains("none of this batch"))
    }

    @Test
    fun repeatedIntentRequestIdentityDoesNotDuplicateCopyOrDurableRows() = runBlocking {
        val stages = manager(access(byteArrayOf(1, 2)))
        val request = UUID.randomUUID().toString()
        val uris = listOf(Uri.parse("content://owned/once"))
        assertEquals(request, stages.stageIncoming(uris, requestId = request))
        assertEquals(request, stages.stageIncoming(uris, requestId = request))
        withTimeout(5000) { stages.attachments.first { it.firstOrNull()?.status == AttachmentStageStatus.READY } }
        val restored = recreateManager(access(byteArrayOf()), File(temporary.root, "stages"))
        assertEquals(request, restored.stageIncoming(uris, requestId = request))
        assertEquals(1, restored.attachments.value.size)
    }

    @Test
    fun failedIntakePersistenceDoesNotPublishPhantomRowsAndTheSameRequestCanRetry() = runBlocking {
        val directory = File(temporary.root, "stages")
        val stages = manager(access(byteArrayOf(1, 2)), directory)
        val request = UUID.randomUUID().toString()
        val uri = Uri.parse("content://owned/durable-intake")
        val blocker = File(directory, "manifest.json.tmp")
        assertTrue(blocker.mkdir())
        assertFailsWith<IOException> { stages.stageIncoming(listOf(uri), requestId = request) }
        assertTrue(stages.attachments.value.isEmpty())
        assertTrue(AttachmentStageStore(directory).load().isEmpty())
        assertTrue(blocker.delete())
        assertEquals(request, stages.stageIncoming(listOf(uri), requestId = request))
        val ready = withTimeout(5000) { stages.attachments.first { it.single().status == AttachmentStageStatus.READY }.single() }
        assertEquals(request, ready.batchId)
        assertContentEquals(byteArrayOf(1, 2), File(directory, "${ready.id}.ready").readBytes())
    }

    @Test
    fun cancellingABlockedUriCopyClosesItsStreamAndNeverCreatesAnUpload() = runBlocking {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stages = manager(object : AttachmentUriAccess {
            override fun describe(uri: Uri): AttachmentUriDescription = AttachmentUriDescription("blocked.bin", "application/octet-stream", null)
            override fun open(uri: Uri): InputStream = object : InputStream() {
                override fun read(): Int = throw IOException("Use bulk reads")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    reading.countDown()
                    closed.await(5, TimeUnit.SECONDS)
                    throw IOException("closed")
                }
                override fun close() { closed.countDown() }
            }
        })
        stages.stageIncoming(listOf(Uri.parse("content://owned/blocked")), destination = destination)
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        val id = stages.attachments.value.single().id
        stages.cancel(id)
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        val cancelled = withTimeout(5000) { stages.attachments.first { it.single().status == AttachmentStageStatus.CANCELLED }.single() }
        assertNull(cancelled.uploaded)
        assertEquals(destination, cancelled.destination)
    }

    @Test
    fun destinationAssignmentPersistenceFailureIsVisibleAndKeepsLastDurableFiles() = runBlocking {
        val directory = File(temporary.root, "stages")
        val stages = manager(access(byteArrayOf(1, 2)), directory)
        val batch = stages.stageIncoming(listOf(Uri.parse("content://owned/storage")))
        withTimeout(5000) { stages.attachments.first { it.firstOrNull()?.status == AttachmentStageStatus.READY } }
        val id = stages.attachments.value.single().id
        val blocker = File(directory, "manifest.json.tmp")
        assertTrue(blocker.mkdir())
        stages.assignDestination(batch, destination)
        val failed = withTimeout(5000) { stages.attachments.first { it.single().error.orEmpty().contains("Unable to save") }.single() }
        assertNull(failed.destination)
        assertTrue(File(directory, "$id.ready").isFile)
        assertTrue(blocker.delete())
        val restored = recreateManager(access(byteArrayOf()), directory)
        assertEquals(AttachmentStageStatus.READY, restored.attachments.value.single().status)
    }

    @Test
    fun privateStageCanRestageAfterItsOriginalUriPermissionIsRevoked() = runBlocking {
        val directory = File(temporary.root, "stages")
        val bytes = byteArrayOf(1, 2, 3)
        val stages = manager(access(bytes), directory)
        stages.stageIncoming(listOf(Uri.parse("content://owned/revoked-later")), destination = destination)
        val staged = withTimeout(5000) { stages.attachments.first { it.single().status == AttachmentStageStatus.READY }.single() }
        scope.coroutineContext.job.children.toList().joinAll()
        assertTrue(File(directory, "${staged.id}.ready").delete())
        val restored = recreateManager(object : AttachmentUriAccess {
            override fun describe(uri: Uri): AttachmentUriDescription = throw SecurityException("Original grant revoked")
            override fun open(uri: Uri): InputStream = throw SecurityException("Original grant revoked")
        }, directory)
        assertEquals(AttachmentStageStatus.ERROR, restored.attachments.value.single().status)
        restored.retry(staged.id)
        val ready = withTimeout(5000) { restored.attachments.first { it.single().status == AttachmentStageStatus.READY }.single() }
        assertEquals(destination, ready.destination)
        assertContentEquals(bytes, File(directory, "${ready.id}.source").readBytes())
        assertContentEquals(bytes, File(directory, "${ready.id}.ready").readBytes())
    }

    @Test
    fun insertionPersistenceFailureDoesNotClaimTheCaptionOrDiscardACompletedUpload() = runBlocking {
        val directory = File(temporary.root, "stages").apply { mkdirs() }
        val complete = StagedAttachment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), destination, "content://owned/completed",
            caption = "retained caption", status = AttachmentStageStatus.UPLOADED,
            uploaded = UploadedAttachment("https://owned.example/completed", "completed.bin", "application/octet-stream", 3))
        AttachmentStageStore(directory).save(listOf(complete))
        val privateFile = File(directory, "${complete.id}.ready").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val stages = manager(access(byteArrayOf()), directory)
        val blocker = File(directory, "manifest.json.tmp")
        assertTrue(blocker.mkdir())
        assertFailsWith<IOException> { stages.prepareInsertion(listOf(complete.id)) }
        assertEquals(complete, stages.attachments.value.single())
        assertTrue(stages.pendingInsertions().isEmpty())
        assertContentEquals(byteArrayOf(1, 2, 3), privateFile.readBytes())
        assertTrue(blocker.delete())
        val restored = recreateManager(access(byteArrayOf()), directory)
        val insertion = assertNotNull(restored.prepareInsertion(listOf(complete.id)))
        assertEquals(destination, insertion.destination)
        assertEquals("retained caption", insertion.caption)
        assertEquals(listOf(assertNotNull(complete.uploaded)), insertion.attachments)
    }

    @Test
    fun rejectedDraftCallbackLeavesTheSameInsertionRecoverableAfterProcessRecreation() = runBlocking {
        val directory = File(temporary.root, "stages").apply { mkdirs() }
        val complete = StagedAttachment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), destination, "content://owned/completed",
            caption = "retained caption", status = AttachmentStageStatus.UPLOADED,
            uploaded = UploadedAttachment("https://owned.example/completed", "completed.bin", "application/octet-stream", 3))
        AttachmentStageStore(directory).save(listOf(complete))
        val stages = manager(access(byteArrayOf()), directory)
        val rejectDraft: (AttachmentInsertion) -> Unit = { error("Draft storage unavailable") }
        assertFailsWith<IllegalStateException> {
            rejectDraft(assertNotNull(stages.prepareInsertion(listOf(complete.id))))
        }
        val pending = stages.pendingInsertions().single()
        assertEquals("retained caption", pending.caption)
        assertEquals(AttachmentStageStatus.UPLOADED, stages.attachments.value.single().status)
        val restored = recreateManager(access(byteArrayOf()), directory)
        assertEquals(pending, restored.pendingInsertions().single())
        assertEquals(pending, restored.prepareInsertion(listOf(complete.id)))
        restored.ackInserted(pending.token)
        withTimeout(5000) { restored.attachments.first { it.single().status == AttachmentStageStatus.INSERTED } }
        val acknowledged = recreateManager(access(byteArrayOf()), directory)
        assertTrue(acknowledged.pendingInsertions().isEmpty())
        assertEquals("", assertNotNull(acknowledged.prepareInsertion(listOf(complete.id))).caption)
    }

    @Test
    fun displayLabelChangesDoNotChangeDurableDestinationIdentity() {
        assertEquals(destination, destination.copy(label = "Renamed network · #a"))
        assertEquals(destination.hashCode(), destination.copy(label = "Renamed").hashCode())
    }

    @Test
    fun binarySendMultipleRetainsCaptionAndTextOnlyIntentRemainsForExistingIntake() {
        val a = Uri.parse("content://owned/a")
        val b = Uri.parse("content://owned/b")
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
            putExtra(Intent.EXTRA_TEXT, "shared caption")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val parsed = assertNotNull(IncomingShareIntake.fromIntent(share))
        assertEquals(listOf(a, b), parsed.uris)
        assertEquals("shared caption", parsed.caption)
        assertNull(IncomingShareIntake.fromIntent(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "ordinary text")))
    }
}
