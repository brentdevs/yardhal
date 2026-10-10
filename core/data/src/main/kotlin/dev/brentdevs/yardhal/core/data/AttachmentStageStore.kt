package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
public data class AttachmentDestination(public val networkId: String, public val storageKey: String, public val label: String) {
    override fun equals(other: Any?): Boolean = other is AttachmentDestination && networkId == other.networkId && storageKey == other.storageKey
    override fun hashCode(): Int = 31 * networkId.hashCode() + storageKey.hashCode()
}

@Serializable
public enum class AttachmentStageStatus { COPYING, READY, AWAITING_CONSENT, UPLOADING, UPLOADED, INSERTED, CANCELLED, ERROR, INTERRUPTED }

@Serializable
public data class StagedAttachment(
    public val id: String,
    public val batchId: String,
    public val destination: AttachmentDestination? = null,
    public val sourceUri: String,
    public val caption: String = "",
    public val captionCaptured: Boolean = false,
    public val name: String = "attachment",
    public val mimeType: String = "application/octet-stream",
    public val sizeBytes: Long = 0,
    public val metadataPolicy: PhotoMetadataPolicy = PhotoMetadataPolicy.STRIP,
    public val status: AttachmentStageStatus = AttachmentStageStatus.COPYING,
    public val progressBytes: Long = 0,
    public val error: String? = null,
    public val sanitizationFailed: Boolean = false,
    public val uploaded: UploadedAttachment? = null,
    public val providerOrigin: String? = null,
    public val providerLabel: String? = null,
    public val authenticationDisclosure: String? = null,
    public val consentKey: String? = null,
    public val insertionToken: String? = null,
    public val insertionAcknowledged: Boolean = false,
    public val insertionIncludesCaption: Boolean = false,
)

public data class AttachmentInsertion(
    public val token: String,
    public val destination: AttachmentDestination,
    public val attachments: List<UploadedAttachment>,
    public val caption: String,
)

public class AttachmentStageStore(directory: File) {
    private val store = JsonFileStore(File(directory, "manifest.json"), ListSerializer(StagedAttachment.serializer()))
    public fun load(): List<StagedAttachment> = store.loadOrDefault(emptyList())
    public fun save(attachments: List<StagedAttachment>) { store.save(attachments) }
}
