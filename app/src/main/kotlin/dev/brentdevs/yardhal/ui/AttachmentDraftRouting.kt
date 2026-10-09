package dev.brentdevs.yardhal.ui

import dev.brentdevs.yardhal.core.data.AttachmentInsertion
import dev.brentdevs.yardhal.core.data.StagedAttachment
import dev.brentdevs.yardhal.core.data.UploadedAttachment

internal fun pendingAttachmentInsertion(storageKey: String, insertions: List<AttachmentInsertion>): AttachmentInsertion? =
    insertions.firstOrNull { it.destination.storageKey == storageKey && it.destination.networkId == storageKey.substringBefore('|') }

internal fun attachmentInsertionDraft(insertion: AttachmentInsertion): String =
    (listOf(insertion.caption).filter { it.isNotBlank() } + insertion.attachments.map { it.url }).joinToString("\n")

internal fun messageUploads(storageKey: String, text: String, staged: List<StagedAttachment>): List<UploadedAttachment> {
    val trimmed = text.trimStart()
    if (trimmed.startsWith('/') && !trimmed.startsWith("//") && !trimmed.startsWith("/me ", true)) return emptyList()
    val tokens = text.splitToSequence(WHITESPACE).toSet()
    return staged.asSequence()
        .filter { entry ->
            entry.destination?.let { it.storageKey == storageKey && it.networkId == storageKey.substringBefore('|') } == true
        }
        .mapNotNull { it.uploaded }
        .filter { it.url in tokens }
        .distinctBy { it.url }
        .toList()
}

private val WHITESPACE = Regex("\\s+")
