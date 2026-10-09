package dev.brentdevs.yardhal.media

import android.content.ContentResolver
import android.net.Uri
import dev.brentdevs.yardhal.core.client.FilehostException
import dev.brentdevs.yardhal.core.client.FilehostUploader
import dev.brentdevs.yardhal.core.client.OutgoingFile
import dev.brentdevs.yardhal.core.client.UploadCancellation
import dev.brentdevs.yardhal.core.data.AttachmentDestination
import dev.brentdevs.yardhal.core.data.AttachmentInsertion
import dev.brentdevs.yardhal.core.data.AttachmentStageStatus
import dev.brentdevs.yardhal.core.data.AttachmentStageStore
import dev.brentdevs.yardhal.core.data.PhotoMetadataPolicy
import dev.brentdevs.yardhal.core.data.StagedAttachment
import dev.brentdevs.yardhal.core.data.UploadEnvironment
import dev.brentdevs.yardhal.core.data.UploadSettingsStore
import dev.brentdevs.yardhal.core.data.UploadedAttachment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public class AttachmentStageManager(
    private val directory: File,
    private val settings: UploadSettingsStore,
    private val scope: CoroutineScope,
    private val environment: (String) -> UploadEnvironment,
    private val access: AttachmentUriAccess,
) {
    public constructor(directory: File, resolver: ContentResolver, settings: UploadSettingsStore, scope: CoroutineScope, environment: (String) -> UploadEnvironment) :
        this(directory, settings, scope, environment, AndroidAttachmentUriAccess(resolver))

    private val store = AttachmentStageStore(directory)
    private val lock = Any()
    private val operations = LinkedHashMap<String, StageCancellation>()
    private val cancelledIds = HashSet<String>()
    private val retryAfterRelease = HashSet<String>()
    private val mutableAttachments = MutableStateFlow(restore(store.load()))
    public val attachments: StateFlow<List<StagedAttachment>> = mutableAttachments.asStateFlow()

    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to create private attachment staging storage")
        store.save(mutableAttachments.value)
        val retained = mutableAttachments.value.flatMap { listOf("${it.id}.source", "${it.id}.ready") }.toSet()
        directory.listFiles()?.filter { it.name != "manifest.json" && (it.name.endsWith(".source") || it.name.endsWith(".ready") || it.name.endsWith(".part")) && it.name !in retained }
            ?.forEach { it.delete() }
    }

    public suspend fun stageIncoming(uris: List<Uri>, caption: String = "", destination: AttachmentDestination? = null, requestId: String? = null): String = withContext(Dispatchers.IO) {
        val batch = requestId ?: UUID.randomUUID().toString()
        require(validId(batch)) { "Share request identity must be a UUID" }
        synchronized(lock) { if (mutableAttachments.value.any { it.batchId == batch }) return@withContext batch }
        val policy = settings.snapshot().photoMetadataPolicy
        val added = if (uris.size > MAXIMUM_SHARE_COUNT) listOf(StagedAttachment(UUID.randomUUID().toString(), batch, destination, "", caption = caption,
            metadataPolicy = policy, status = AttachmentStageStatus.ERROR, error = "This share contains too many files. Share at most $MAXIMUM_SHARE_COUNT files at once; none of this batch were copied or uploaded."))
        else uris.distinct().map { StagedAttachment(UUID.randomUUID().toString(), batch, destination, it.toString(), caption = caption, metadataPolicy = policy) }
        synchronized(lock) {
            if (mutableAttachments.value.any { it.batchId == batch }) return@withContext batch
            val next = mutableAttachments.value + added
            store.save(next)
            mutableAttachments.value = next
        }
        scope.launch(Dispatchers.IO) {
            added.filter { it.status == AttachmentStageStatus.COPYING }.forEach { beginCopy(it.id) }
        }
        batch
    }

    public fun assignDestination(batchId: String, destination: AttachmentDestination) {
        scope.launch(Dispatchers.IO) {
            persistUserChange { mutate { entries -> entries.map { entry ->
                if (entry.batchId == batchId && entry.destination == null) entry.copy(destination = destination) else entry
            } } }
        }
    }

    public fun updateCaption(batchId: String, caption: String) {
        synchronized(lock) { mutableAttachments.value = mutableAttachments.value.map { if (it.batchId == batchId) it.copy(caption = caption) else it } }
        scope.launch(Dispatchers.IO) { persistUserChange { synchronized(lock) { store.save(mutableAttachments.value) } } }
    }

    public fun retry(id: String) {
        scope.launch(Dispatchers.IO) {
            val entry = entry(id) ?: return@launch
            if (entry.uploaded != null) return@launch
            synchronized(lock) {
                if (id in operations) { retryAfterRelease.add(id); return@launch }
                cancelledIds.remove(id)
            }
            val ready = readyFile(id)
            if (ready.isFile && ready.length() == entry.sizeBytes && !entry.sanitizationFailed) upload(id) else beginCopy(id)
        }
    }

    public fun keepMetadataAndRetry(id: String) {
        scope.launch(Dispatchers.IO) {
            if (synchronized(lock) { id in operations }) return@launch
            synchronized(lock) { cancelledIds.remove(id) }
            if (persistUserChange { update(id) { it.copy(metadataPolicy = PhotoMetadataPolicy.KEEP) } }) beginCopy(id)
        }
    }

    public fun upload(id: String, confirmedConsentKey: String? = null) {
        scope.launch(Dispatchers.IO) { performUpload(id, confirmedConsentKey) }
    }

    public fun cancel(id: String) {
        val operation = synchronized(lock) { cancelledIds.add(id); operations[id]?.also { it.markCancelled() } }
        scope.launch(Dispatchers.IO) {
            operation?.cancel()
            persistUserChange { update(id) { if (it.uploaded == null) it.copy(status = AttachmentStageStatus.CANCELLED, error = "Cancelled. Retry keeps this attachment in its original conversation.") else it } }
        }
    }

    public fun remove(id: String) {
        val operation = synchronized(lock) { cancelledIds.add(id); operations[id]?.also { it.markCancelled() } }
        scope.launch(Dispatchers.IO) {
            operation?.cancel()
            if (persistUserChange { mutate { entries -> entries.filterNot { it.id == id } } } && synchronized(lock) {
                    retryAfterRelease.remove(id)
                    if (id in operations) false else { cancelledIds.remove(id); true }
                }) deleteFiles(id)
        }
    }

    public suspend fun prepareInsertion(ids: List<String>): AttachmentInsertion? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val selected = mutableAttachments.value.filter { it.id in ids && it.uploaded != null }
            if (selected.isEmpty() || selected.size != ids.distinct().size) return@synchronized null
            val destination = selected.first().destination ?: return@synchronized null
            if (selected.any { it.destination != destination }) return@synchronized null
            environment(destination.networkId)
            val existingToken = selected.first().insertionToken
            if (existingToken != null && selected.all { it.insertionToken == existingToken && !it.insertionAcknowledged }) {
                return@synchronized AttachmentInsertion(existingToken, destination, selected.mapNotNull { it.uploaded },
                    selected.filter { it.insertionIncludesCaption }.joinToString("\n") { it.caption })
            }
            val token = UUID.randomUUID().toString()
            val captions = selected.filter { entry -> !entry.captionCaptured && mutableAttachments.value.none {
                it.id !in ids && it.batchId == entry.batchId && it.insertionToken != null && !it.insertionAcknowledged && it.insertionIncludesCaption
            } }.distinctBy { it.batchId }
            val captionIds = captions.map { it.id }.toSet()
            val caption = captions.map { it.caption }.filter { it.isNotBlank() }.joinToString("\n")
            val next = mutableAttachments.value.map { if (it.id in ids) it.copy(insertionToken = token, insertionAcknowledged = false, insertionIncludesCaption = it.id in captionIds) else it }
            store.save(next); mutableAttachments.value = next
            AttachmentInsertion(token, destination, selected.mapNotNull { it.uploaded }, caption)
        }
    }

    public fun pendingInsertions(): List<AttachmentInsertion> = synchronized(lock) {
        mutableAttachments.value.filter { it.insertionToken != null && !it.insertionAcknowledged && it.uploaded != null }
            .groupBy { it.insertionToken }.mapNotNull { (token, entries) ->
                val destination = entries.first().destination
                if (token == null || destination == null || entries.any { it.destination != destination }) null
                else AttachmentInsertion(token, destination, entries.mapNotNull { it.uploaded }, entries.filter { it.insertionIncludesCaption }.joinToString("\n") { it.caption })
            }
    }

    public fun ackInserted(token: String) {
        scope.launch(Dispatchers.IO) {
            persistUserChange { mutate { entries ->
                val batches = entries.filter { it.insertionToken == token && it.insertionIncludesCaption }.map { it.batchId }.toSet()
                entries.map { entry -> when {
                    entry.insertionToken == token -> entry.copy(status = AttachmentStageStatus.INSERTED, insertionAcknowledged = true, captionCaptured = entry.captionCaptured || entry.batchId in batches)
                    entry.batchId in batches -> entry.copy(captionCaptured = true)
                    else -> entry
                } }
            } }
        }
    }

    private fun beginCopy(id: String) {
        val cancellation = acquire(id) ?: return
        try {
            var entry = entry(id) ?: return
            update(id) { it.copy(status = AttachmentStageStatus.COPYING, error = null, progressBytes = 0, sanitizationFailed = false) }
            val maximum = settings.snapshot().maximumBytes
            val source = sourceFile(id)
            if (!source.isFile) {
                val uri = Uri.parse(entry.sourceUri)
                val description = access.describe(uri)
                if (description.advertisedBytes?.let { it > maximum } == true) throw AttachmentSizeException()
                update(id) { it.copy(name = description.name, mimeType = description.mimeType) }
                val partial = File(directory, "$id.source.part")
                try {
                    access.open(uri).use { input ->
                        cancellation.attach(input)
                        FileOutputStream(partial).use { output ->
                            copyBoundedAttachment(input, output, maximum, cancellation::check)
                            output.fd.sync()
                        }
                    }
                    cancellation.check()
                    if (!partial.renameTo(source)) throw IOException("Unable to preserve staged file")
                } finally { partial.delete() }
            }
            entry = entry(id) ?: return
            cancellation.check()
            if (source.length() > maximum) throw AttachmentSizeException()
            readyFile(id).delete()
            val sanitized = File(directory, "$id.ready.part")
            val type = AttachmentSanitizer.sanitize(source, sanitized, entry.mimeType, entry.metadataPolicy, maximum, cancellation::check)
            cancellation.check()
            FileOutputStream(sanitized, true).use { it.fd.sync() }
            if (!sanitized.renameTo(readyFile(id))) throw IOException("Unable to preserve sanitized file")
            synchronized(lock) {
                cancellation.check()
                update(id) { it.copy(mimeType = type, sizeBytes = readyFile(id).length(), status = AttachmentStageStatus.READY, error = null) }
            }
        } catch (failure: Exception) {
            fail(id, failure, cancellation)
        } finally { File(directory, "$id.ready.part").delete(); release(id) }
    }

    private fun performUpload(id: String, confirmedConsentKey: String?) {
        val cancellation = acquire(id) ?: return
        try {
            val entry = entry(id) ?: return
            if (entry.uploaded != null || entry.status == AttachmentStageStatus.COPYING) return
            val destination = entry.destination ?: throw IllegalStateException("Choose a destination conversation before uploading.")
            val context = environment(destination.networkId)
            val provider = settings.resolve(destination.networkId, context.negotiatedFilehost, context.requireSecureTransport)
            val file = readyFile(id)
            if (!file.isFile) throw IllegalStateException("The private staged file is missing. Remove this attachment and select the file again.")
            val size = file.length()
            if (size != entry.sizeBytes) throw FilehostException.SizeMismatch()
            if (size > settings.snapshot().maximumBytes) throw AttachmentSizeException()
            update(id) { it.copy(providerOrigin = provider.origin, providerLabel = provider.label, authenticationDisclosure = provider.authenticationDisclosure, consentKey = provider.consentKey) }
            if (!settings.hasConsent(provider)) {
                if (confirmedConsentKey != provider.consentKey) {
                    update(id) { it.copy(status = AttachmentStageStatus.AWAITING_CONSENT, error = null) }
                    return
                }
                settings.grantConsent(provider)
            }
            cancellation.check()
            update(id) { it.copy(status = AttachmentStageStatus.UPLOADING, error = null, progressBytes = 0) }
            synchronized(lock) { mutableAttachments.value.filter { it.id != id && it.status == AttachmentStageStatus.AWAITING_CONSENT && it.consentKey == provider.consentKey }.map { it.id } }
                .forEach { upload(it) }
            var lastProgress = 0L
            val uploaded = FilehostUploader.upload(provider.endpointUrl, OutgoingFile(entry.name, entry.mimeType, size, file::inputStream),
                provider.requireSecureTransport, provider.authentication?.username, provider.authentication?.password, cancellation = cancellation.upload,
                onProgress = { sent, _ ->
                    val now = System.nanoTime()
                    if (now - lastProgress >= 100_000_000 || sent == size) {
                        lastProgress = now
                        synchronized(lock) { mutableAttachments.value = mutableAttachments.value.map { if (it.id == id && it.status == AttachmentStageStatus.UPLOADING) it.copy(progressBytes = sent) else it } }
                    }
                })
            val result = UploadedAttachment(uploaded.url, entry.name, entry.mimeType, size)
            synchronized(lock) {
                cancellation.check()
                update(id) { it.copy(status = AttachmentStageStatus.UPLOADED, uploaded = result, progressBytes = size, error = null) }
            }
        } catch (failure: Exception) { fail(id, failure, cancellation) } finally { release(id) }
    }

    private fun restore(entries: List<StagedAttachment>): List<StagedAttachment> = entries.filter { validId(it.id) && validId(it.batchId) }.map { entry ->
        when {
            entry.uploaded != null -> entry.copy(status = if (entry.insertionAcknowledged) AttachmentStageStatus.INSERTED else AttachmentStageStatus.UPLOADED)
            entry.status in setOf(AttachmentStageStatus.COPYING, AttachmentStageStatus.UPLOADING) -> entry.copy(status = AttachmentStageStatus.INTERRUPTED,
                error = "Interrupted by app restart. Retry the private staged file, or select/share it again if access was revoked.", progressBytes = 0)
            entry.status == AttachmentStageStatus.READY && !readyFile(entry.id).isFile -> entry.copy(status = AttachmentStageStatus.ERROR, error = "The staged file is missing. Remove it and select the file again.")
            else -> entry
        }
    }

    private fun fail(id: String, failure: Exception, cancellation: StageCancellation) {
        val cancelled = cancellation.isCancelled || failure is FilehostException.Cancelled
        val message = when {
            cancelled -> "Cancelled. Retry this attachment in its original conversation."
            failure is SecurityException -> "File permission was revoked or not granted. Select or share the file again with access enabled."
            failure is AttachmentSizeException || failure is AttachmentSanitizationException || failure is AttachmentUriAccessException || failure is FilehostException -> failure.message
            failure is IllegalStateException -> failure.message
            else -> "Unable to read, sanitize or upload this file. Check storage, file access and connectivity, then retry or select/share it again."
        }
        persistUserChange { update(id) { it.copy(status = if (cancelled) AttachmentStageStatus.CANCELLED else AttachmentStageStatus.ERROR, error = message, sanitizationFailed = failure is AttachmentSanitizationException) } }
    }

    private fun acquire(id: String): StageCancellation? = synchronized(lock) {
        if (id in operations || id in cancelledIds || mutableAttachments.value.none { it.id == id }) null else StageCancellation().also { operations[id] = it }
    }
    private fun release(id: String) {
        val shouldRetry = synchronized(lock) {
            operations.remove(id)
            if (mutableAttachments.value.none { it.id == id }) { cancelledIds.remove(id); deleteFiles(id) }
            retryAfterRelease.remove(id)
        }
        if (shouldRetry) retry(id)
    }
    private fun entry(id: String): StagedAttachment? = synchronized(lock) { mutableAttachments.value.firstOrNull { it.id == id } }
    private fun update(id: String, transform: (StagedAttachment) -> StagedAttachment) { mutate { entries -> entries.map { if (it.id == id) transform(it) else it } } }
    private fun mutate(transform: (List<StagedAttachment>) -> List<StagedAttachment>) { synchronized(lock) { val next = transform(mutableAttachments.value); store.save(next); mutableAttachments.value = next } }
    private fun sourceFile(id: String): File = File(directory, "$id.source")
    private fun readyFile(id: String): File = File(directory, "$id.ready")
    private fun deleteFiles(id: String) { if (validId(id)) listOf("source", "ready", "source.part", "ready.part").forEach { File(directory, "$id.$it").delete() } }
    private fun validId(id: String): Boolean = try { UUID.fromString(id).toString() == id } catch (_: IllegalArgumentException) { false }

    private fun persistUserChange(change: () -> Unit): Boolean = try { change(); true } catch (failure: Exception) {
        if (failure !is IOException && failure !is SecurityException) throw failure
        synchronized(lock) {
            operations.values.forEach { it.cancel() }
            mutableAttachments.value = mutableAttachments.value.map { it.copy(
                status = if (it.uploaded == null) AttachmentStageStatus.ERROR else it.status,
                error = "Unable to save attachment staging. Free local storage and retry; the last durable state and private files were retained.",
            ) }
        }
        false
    }

    public companion object {
        public const val MAXIMUM_SHARE_COUNT: Int = 128
    }

    private class StageCancellation {
        val upload = UploadCancellation()
        @Volatile var isCancelled: Boolean = false
            private set
        private var input: InputStream? = null
        @Synchronized fun attach(stream: InputStream) { check(); input = stream }
        fun check() { if (isCancelled) throw FilehostException.Cancelled() }
        fun markCancelled() { isCancelled = true }
        fun cancel() {
            isCancelled = true
            upload.cancel()
            try { synchronized(this) { input }?.close() } catch (_: IOException) { }
        }
    }
}
