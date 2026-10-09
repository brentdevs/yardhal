package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.StoredMessage

internal fun StoredMessage.toChatMessage(localId: Long): ChatMessage {
    val message = ChatMessage(
        localId = localId,
        sender = senderNick,
        kind = kind,
        text = text,
        timestampMs = timestampMs,
        sentByUs = sentByUs,
        highlightsMe = highlightsMe,
        highlightsKnown = highlightsKnown,
        msgid = msgid,
        replyToMsgid = replyToMsgid,
        attachmentUrl = attachmentUrl,
        playback = playback,
        pendingEcho = pendingEcho,
        storedRowId = rowId,
        senderAccount = senderAccount,
        channelContext = channelContext,
        historyContext = historyContext,
        localReplyParentRowId = replyParentRowId,
        attachmentName = attachmentName,
        attachmentMimeType = attachmentMimeType,
        attachmentSizeBytes = attachmentSizeBytes,
        attachmentWidth = attachmentWidth,
        attachmentHeight = attachmentHeight,
        redacted = redacted,
        reactionsTruncated = reactionsTruncated,
    )
    return if (message.redacted) message.asRedacted() else message
}

internal fun hydrateReplyPreviews(
    messages: List<ChatMessage>,
    parents: Map<Long, StoredMessage>,
): List<ChatMessage> {
    if (parents.isEmpty()) return sanitizeRedactedMessages(messages)
    val hydrated = messages.map { message ->
        val parent = message.storedRowId?.let(parents::get)
        if (message.redacted) message.asRedacted() else if (parent == null) message else message.copy(
            localReplyParentRowId = parent.rowId,
            replyPreview = if (message.replyPreview?.redacted == true) {
                message.replyPreview.copy(text = "message deleted", attachmentUrl = null)
            } else parent.toReplyPreview(),
        )
    }
    return sanitizeRedactedMessages(hydrated)
}

private fun StoredMessage.toReplyPreview(): ReplyPreview = ReplyPreview(
    sender = senderNick,
    text = if (redacted) "message deleted" else text,
    attachmentUrl = attachmentUrl.takeUnless { redacted },
    redacted = redacted,
)

internal fun ChatMessage.asRedacted(): ChatMessage = copy(
    kind = MessageKind.SYSTEM,
    text = "message deleted",
    highlightsMe = false,
    replyToMsgid = null,
    localReplyParentRowId = null,
    replyPreview = null,
    attachmentUrl = null,
    attachmentName = null,
    attachmentMimeType = null,
    attachmentSizeBytes = null,
    attachmentWidth = null,
    attachmentHeight = null,
    redacted = true,
    reactionsTruncated = false,
)

internal fun ChatMessage.withRedactedReply(parentSender: String? = null): ChatMessage = copy(
    replyPreview = ReplyPreview(
        sender = parentSender ?: replyPreview?.sender.orEmpty(),
        text = "message deleted",
        redacted = true,
    ),
)

internal fun sanitizeRedactedMessages(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.none { it.redacted }) return messages
    val redactedIds = mutableMapOf<String, ChatMessage>()
    val redactedRows = mutableMapOf<Long, ChatMessage>()
    for (message in messages) if (message.redacted) {
        message.msgid?.let { redactedIds[it] = message }
        message.storedRowId?.let { redactedRows[it] = message }
    }
    return messages.map { message ->
        if (message.redacted) message.asRedacted() else {
            val parent = message.replyToMsgid?.let(redactedIds::get)
                ?: message.localReplyParentRowId?.let(redactedRows::get)
            if (parent == null) message else message.withRedactedReply(parent.sender)
        }
    }
}
