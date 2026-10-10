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
import dev.brentdevs.yardhal.core.data.UploadProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.UUID
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    private data class UploadHttpRequest(val method: String, val headers: Map<String, String>, val body: ByteArray)

    private class UploadHttpPeer(
        private val server: ServerSocket,
        private val tolerateDisconnectedResponse: Boolean = false,
        private val respond: (UploadHttpRequest) -> String,
    ) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        private val worker = executor.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (failure: IOException) {
                    if (server.isClosed) break else throw failure
                }
                socket.use {
                    socket.soTimeout = 5000
                    val input = DataInputStream(socket.getInputStream().buffered())
                    val requestLine = readLine(input).split(' ')
                    check(requestLine.size == 3 && requestLine[1] == "/upload")
                    val headers = LinkedHashMap<String, String>()
                    while (true) {
                        val line = readLine(input)
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        check(separator > 0)
                        headers[line.substring(0, separator).lowercase(Locale.ROOT)] = line.substring(separator + 1).trim()
                    }
                    val length = checkNotNull(headers["content-length"]).toInt()
                    check(length in 0..1024 * 1024)
                    val body = ByteArray(length).also(input::readFully)
                    val location = respond(UploadHttpRequest(requestLine.first(), headers, body))
                    try {
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 201 Created\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                    } catch (failure: IOException) {
                        if (!tolerateDisconnectedResponse) throw failure
                    }
                }
            }
        }

        private fun readLine(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                check(next >= 0)
                if (next == 10) return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
                check(bytes.size() < 8192)
                bytes.write(next)
            }
        }

        override fun close() {
            server.close()
            try { worker.get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
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
    fun captionSaveFailureNeverPublishesUndurableTextAndRapidEditsKeepTheLatestRevision() = runBlocking {
        val directory = File(temporary.root, "stages")
        val stages = manager(access(byteArrayOf(1)), directory)
        val batch = stages.stageIncoming(listOf(Uri.parse("content://owned/caption")), caption = "durable", destination = destination)
        withTimeout(5000) { stages.attachments.first { it.single().status == AttachmentStageStatus.READY } }
        scope.coroutineContext.job.children.toList().joinAll()
        val blocker = File(directory, "manifest.json.tmp")
        assertTrue(blocker.mkdir())
        stages.updateCaption(batch, "not durable")
        withTimeout(5000) { stages.attachments.first { it.single().error.orEmpty().contains("Unable to save") } }
        assertEquals("durable", stages.attachments.value.single().caption)
        assertEquals("durable", AttachmentStageStore(directory).load().single().caption)
        scope.coroutineContext.job.children.toList().joinAll()
        assertTrue(blocker.delete())
        repeat(100) { stages.updateCaption(batch, "revision $it") }
        scope.coroutineContext.job.children.toList().joinAll()
        assertEquals("revision 99", stages.attachments.value.single().caption)
        assertEquals("revision 99", AttachmentStageStore(directory).load().single().caption)
        assertEquals("revision 99", recreateManager(access(byteArrayOf()), directory).attachments.value.single().caption)
    }

    @Test
    fun keepRequestedDuringCopySurvivesReleaseAndRetryWithoutFlatteningAnimation() = runBlocking {
        val bytes = Base64.getDecoder().decode("R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBCgABACwAAAAAAgACAIEAAP8AAAAAAAAAAAAIBgABCAQQEAA7")
        val reading = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val opens = AtomicInteger()
        val preferences = settings()
        val stages = manager(object : AttachmentUriAccess {
            override fun describe(uri: Uri): AttachmentUriDescription = AttachmentUriDescription("animated.gif", "image/gif", null)
            override fun open(uri: Uri): InputStream {
                opens.incrementAndGet()
                return object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        reading.countDown()
                        check(proceed.await(5, TimeUnit.SECONDS))
                        return super.read(buffer, offset, length)
                    }
                }
            }
        }, settings = preferences)
        stages.stageIncoming(listOf(Uri.parse("content://owned/animation")), destination = destination)
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        val id = stages.attachments.value.single().id
        val copying = scope.coroutineContext.job.children.toSet()
        stages.keepMetadataAndRetry(id)
        stages.retry(id)
        scope.coroutineContext.job.children.filter { it !in copying }.toList().joinAll()
        proceed.countDown()
        val ready = withTimeout(5000) { stages.attachments.first { it.single().status == AttachmentStageStatus.READY }.single() }
        scope.coroutineContext.job.children.toList().joinAll()
        assertEquals(PhotoMetadataPolicy.KEEP, ready.metadataPolicy)
        assertEquals(PhotoMetadataPolicy.STRIP, preferences.snapshot().photoMetadataPolicy)
        assertEquals(destination, ready.destination)
        assertEquals(1, opens.get())
        assertNull(ready.uploaded)
        assertContentEquals(bytes, File(temporary.root, "stages/$id.source").readBytes())
        assertContentEquals(bytes, File(temporary.root, "stages/$id.ready").readBytes())
        val restored = recreateManager(access(byteArrayOf()))
        assertEquals(PhotoMetadataPolicy.KEEP, restored.attachments.value.single().metadataPolicy)
        assertContentEquals(bytes, File(temporary.root, "stages/$id.ready").readBytes())
    }

    @Test
    fun removeDuringCopyCancelsQueuedKeepAndRetryAndDeletesEveryPrivateArtifact() = runBlocking {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val directory = File(temporary.root, "stages")
        val stages = manager(object : AttachmentUriAccess {
            override fun describe(uri: Uri): AttachmentUriDescription = AttachmentUriDescription("blocked", "application/octet-stream", null)
            override fun open(uri: Uri): InputStream = object : InputStream() {
                override fun read(): Int = throw IOException("Use bulk reads")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reading.countDown()
                    check(closed.await(5, TimeUnit.SECONDS))
                    throw IOException("closed")
                }
                override fun close() { closed.countDown() }
            }
        }, directory)
        stages.stageIncoming(listOf(Uri.parse("content://owned/remove")), destination = destination)
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        val id = stages.attachments.value.single().id
        val copying = scope.coroutineContext.job.children.toSet()
        stages.keepMetadataAndRetry(id)
        stages.retry(id)
        scope.coroutineContext.job.children.filter { it !in copying }.toList().joinAll()
        stages.remove(id)
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        scope.coroutineContext.job.children.toList().joinAll()
        assertTrue(stages.attachments.value.isEmpty())
        assertTrue(AttachmentStageStore(directory).load().isEmpty())
        listOf("source", "ready", "source.part", "ready.part").forEach { assertTrue(!File(directory, "$id.$it").exists()) }
        assertTrue(recreateManager(access(byteArrayOf()), directory).attachments.value.isEmpty())
    }

    @Test
    fun startupSweepsPartialAndOrphanFilesButPreservesDurablePrivateSources() {
        val directory = File(temporary.root, "stages").apply { mkdirs() }
        val stage = StagedAttachment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), destination, "content://owned/restore", status = AttachmentStageStatus.COPYING)
        AttachmentStageStore(directory).save(listOf(stage))
        val source = File(directory, "${stage.id}.source").apply { writeBytes(byteArrayOf(1, 2)) }
        val ready = File(directory, "${stage.id}.ready").apply { writeBytes(byteArrayOf(3)) }
        val garbage = listOf("${stage.id}.source.part", "${stage.id}.ready.part", "${UUID.randomUUID()}.source", "${UUID.randomUUID()}.ready", "${UUID.randomUUID()}.ready.part")
            .map { File(directory, it).apply { writeBytes(byteArrayOf(9)) } }
        val stages = manager(access(byteArrayOf()), directory)
        assertEquals(AttachmentStageStatus.INTERRUPTED, stages.attachments.value.single().status)
        assertTrue(garbage.none { it.exists() })
        assertContentEquals(byteArrayOf(1, 2), source.readBytes())
        assertContentEquals(byteArrayOf(3), ready.readBytes())
    }

    @Test
    fun realHttpConsentUploadInsertionAcknowledgementAndRemovalRetainBytesUntilExplicitRemoval() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val body = AtomicReference<ByteArray>()
        val method = AtomicReference<String>()
        val contentType = AtomicReference<String>()
        val peer = UploadHttpPeer(server) { request ->
            requests.incrementAndGet()
            method.set(request.method)
            contentType.set(request.headers["content-type"])
            body.set(request.body)
            "/files/private.bin"
        }
        try {
            val endpoint = "http://127.0.0.1:${server.localPort}/upload"
            val preferences = settings()
            val provider = UploadProvider(label = "Local filehost", endpointUrl = endpoint)
            preferences.saveProvider(provider)
            preferences.selectProvider(null, provider.id)
            val directory = File(temporary.root, "stages")
            val bytes = ByteArray(65537) { (it % 251).toByte() }
            val stages = AttachmentStageManager(directory, preferences, scope, { UploadEnvironment(null, false) }, access(bytes))
            stages.stageIncoming(listOf(Uri.parse("content://owned/http")), caption = "caption", destination = destination)
            val ready = withTimeout(5000) { stages.attachments.first { it.single().status == AttachmentStageStatus.READY }.single() }
            assertEquals(0, requests.get())
            stages.upload(ready.id)
            val consent = withTimeout(5000) { stages.attachments.first { it.single().status == AttachmentStageStatus.AWAITING_CONSENT }.single() }
            assertEquals(0, requests.get())
            stages.upload(ready.id, consent.consentKey)
            val uploaded = withTimeout(5000) { stages.attachments.first { it.single().status == AttachmentStageStatus.UPLOADED }.single() }
            assertEquals(1, requests.get())
            assertEquals("POST", method.get())
            assertEquals("application/octet-stream", contentType.get())
            assertContentEquals(bytes, body.get())
            assertEquals("http://127.0.0.1:${server.localPort}/files/private.bin", uploaded.uploaded?.url)
            val insertion = assertNotNull(stages.prepareInsertion(listOf(ready.id)))
            assertEquals(destination, insertion.destination)
            assertEquals("caption", insertion.caption)
            stages.ackInserted(insertion.token)
            withTimeout(5000) { stages.attachments.first { it.single().status == AttachmentStageStatus.INSERTED } }
            stages.retry(ready.id)
            scope.coroutineContext.job.children.toList().joinAll()
            assertEquals(1, requests.get())
            assertContentEquals(bytes, File(directory, "${ready.id}.ready").readBytes())
            assertEquals(uploaded.uploaded, AttachmentStageStore(directory).load().single().uploaded)
            stages.remove(ready.id)
            scope.coroutineContext.job.children.toList().joinAll()
            assertTrue(stages.attachments.value.isEmpty())
            assertTrue(AttachmentStageStore(directory).load().isEmpty())
            assertTrue(!File(directory, "${ready.id}.source").exists())
            assertTrue(!File(directory, "${ready.id}.ready").exists())
        } finally { peer.close() }
    }

    @Test
    fun removingAnInFlightHttpUploadNeverResurrectsACompletedRowOrPrivateFiles() = runBlocking {
        val received = CountDownLatch(1)
        val respond = CountDownLatch(1)
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val peer = UploadHttpPeer(server, tolerateDisconnectedResponse = true) {
            received.countDown()
            check(respond.await(5, TimeUnit.SECONDS))
            "/files/removed"
        }
        try {
            val preferences = settings()
            val provider = UploadProvider(label = "Local", endpointUrl = "http://127.0.0.1:${server.localPort}/upload")
            preferences.saveProvider(provider)
            preferences.selectProvider(null, provider.id)
            preferences.grantConsent(preferences.resolve(destination.networkId, null, false))
            val directory = File(temporary.root, "stages")
            val stages = AttachmentStageManager(directory, preferences, scope, { UploadEnvironment(null, false) }, access(byteArrayOf(1, 2, 3)))
            stages.stageIncoming(listOf(Uri.parse("content://owned/upload-remove")), destination = destination)
            val ready = withTimeout(5000) { stages.attachments.first { it.single().status == AttachmentStageStatus.READY }.single() }
            scope.coroutineContext.job.children.toList().joinAll()
            stages.upload(ready.id)
            assertTrue(received.await(5, TimeUnit.SECONDS))
            stages.remove(ready.id)
            stages.retry(ready.id)
            stages.keepMetadataAndRetry(ready.id)
            respond.countDown()
            withTimeout(5000) { scope.coroutineContext.job.children.toList().joinAll() }
            assertTrue(stages.attachments.value.isEmpty())
            assertTrue(AttachmentStageStore(directory).load().isEmpty())
            listOf("source", "ready", "source.part", "ready.part").forEach { assertTrue(!File(directory, "${ready.id}.$it").exists()) }
        } finally {
            respond.countDown()
            peer.close()
        }
    }

    @Test
    fun startupRetainsOnlyNeededUriGrantsAndDurableSourceCopyReleasesThem() = runBlocking {
        val directory = File(temporary.root, "stages").apply { mkdirs() }
        val missing = StagedAttachment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), destination, "content://owned/needed", status = AttachmentStageStatus.COPYING)
        val copied = missing.copy(id = UUID.randomUUID().toString(), sourceUri = "content://owned/copied", status = AttachmentStageStatus.READY, sizeBytes = 1)
        AttachmentStageStore(directory).save(listOf(missing, copied))
        File(directory, "${copied.id}.source").writeBytes(byteArrayOf(1))
        File(directory, "${copied.id}.ready").writeBytes(byteArrayOf(1))
        val retained = AtomicReference<Set<String>>()
        val uriAccess = object : AttachmentUriAccess {
            override fun describe(uri: Uri): AttachmentUriDescription = AttachmentUriDescription("file", "application/octet-stream", null)
            override fun open(uri: Uri): InputStream = ByteArrayInputStream(byteArrayOf(2, 3))
            override fun releaseUnused(retainedUriStrings: Set<String>) {
                if (missing.sourceUri !in retainedUriStrings) assertTrue(File(directory, "${missing.id}.source").isFile)
                retained.set(retainedUriStrings)
            }
        }
        val stages = manager(uriAccess, directory)
        assertEquals(setOf(missing.sourceUri), retained.get())
        stages.retry(missing.id)
        withTimeout(5000) { stages.attachments.first { it.first().status == AttachmentStageStatus.READY } }
        scope.coroutineContext.job.children.toList().joinAll()
        assertEquals(emptySet(), retained.get())
        assertContentEquals(byteArrayOf(2, 3), File(directory, "${missing.id}.source").readBytes())
    }

    @Test
    fun uriGrantCleanupFailureIsActionableDurableAndNeverDeletesThePrivateSource() {
        val directory = File(temporary.root, "stages").apply { mkdirs() }
        val stage = StagedAttachment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), destination, "content://owned/copied",
            status = AttachmentStageStatus.READY, sizeBytes = 2)
        AttachmentStageStore(directory).save(listOf(stage))
        val source = File(directory, "${stage.id}.source").apply { writeBytes(byteArrayOf(1, 2)) }
        val ready = File(directory, "${stage.id}.ready").apply { writeBytes(byteArrayOf(1, 2)) }
        val stages = manager(object : AttachmentUriAccess {
            override fun describe(uri: Uri): AttachmentUriDescription = error("Private source must be retained")
            override fun open(uri: Uri): InputStream = error("Private source must be retained")
            override fun releaseUnused(retainedUriStrings: Set<String>) { throw SecurityException("Provider refused release") }
        }, directory)
        val failed = stages.attachments.value.single()
        assertEquals(AttachmentStageStatus.ERROR, failed.status)
        assertTrue(failed.error.orEmpty().contains("release unused file permissions"))
        assertEquals(failed, AttachmentStageStore(directory).load().single())
        assertContentEquals(byteArrayOf(1, 2), source.readBytes())
        assertContentEquals(byteArrayOf(1, 2), ready.readBytes())
    }

    @Test
    fun permissionAndManifestFailuresReleaseNewGrantsWithoutPublishingRowsAndSameRequestCanRetry() = runBlocking {
        val directory = File(temporary.root, "stages")
        val uri = Uri.parse("content://owned/persist")
        val request = UUID.randomUUID().toString()
        val grants = AtomicReference<Set<String>>(emptySet())
        val opens = AtomicInteger()
        var denyPermission = true
        val stages = manager(object : AttachmentUriAccess {
            override fun describe(uri: Uri): AttachmentUriDescription = AttachmentUriDescription("file", "application/octet-stream", null)
            override fun open(uri: Uri): InputStream {
                opens.incrementAndGet()
                return ByteArrayInputStream(byteArrayOf(1, 2))
            }
            override fun persist(uris: List<Uri>) {
                grants.set(uris.map { it.toString() }.toSet())
                if (denyPermission) throw AttachmentUriAccessException("Unable to retain file permission. Remove unused stages to release permissions, then select the file again.")
            }
            override fun releaseUnused(retainedUriStrings: Set<String>) {
                grants.set(grants.get().intersect(retainedUriStrings))
            }
        }, directory)
        val denied = assertFailsWith<AttachmentUriAccessException> {
            stages.stageIncoming(listOf(uri), requestId = request, persistPermissions = true)
        }
        assertEquals("Unable to retain file permission. Remove unused stages to release permissions, then select the file again.", denied.message)
        assertTrue(stages.attachments.value.isEmpty())
        assertTrue(AttachmentStageStore(directory).load().isEmpty())
        assertEquals(emptySet(), grants.get())
        assertEquals(0, opens.get())
        denyPermission = false
        val blocker = File(directory, "manifest.json.tmp")
        assertTrue(blocker.mkdir())
        assertFailsWith<IOException> { stages.stageIncoming(listOf(uri), requestId = request, persistPermissions = true) }
        assertTrue(stages.attachments.value.isEmpty())
        assertTrue(AttachmentStageStore(directory).load().isEmpty())
        assertEquals(emptySet(), grants.get())
        assertEquals(0, opens.get())
        assertTrue(blocker.delete())
        assertEquals(request, stages.stageIncoming(listOf(uri), requestId = request, persistPermissions = true))
        withTimeout(5000) { stages.attachments.first { it.single().status == AttachmentStageStatus.READY } }
        scope.coroutineContext.job.children.toList().joinAll()
        assertEquals(1, opens.get())
        assertEquals(emptySet(), grants.get())
        assertContentEquals(byteArrayOf(1, 2), File(directory, "${stages.attachments.value.single().id}.source").readBytes())
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
