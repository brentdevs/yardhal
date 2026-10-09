package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.MessageKind

internal fun redactInBuffer(buffer: ConversationBuffer, msgid: String): ConversationBuffer {
    val parent = buffer.messages.firstOrNull { it.msgid == msgid }
    val messages = buffer.messages.map { message ->
        when {
            message.msgid == msgid -> message.asRedacted()
            message.replyToMsgid == msgid ||
                parent?.storedRowId != null && message.localReplyParentRowId == parent.storedRowId ->
                message.withRedactedReply(parent?.sender)
            else -> message
        }
    }
    val draft = buffer.replyDraft?.let { message ->
        when {
            message.msgid == msgid -> null
            message.replyToMsgid == msgid ||
                parent?.storedRowId != null && message.localReplyParentRowId == parent.storedRowId ->
                message.withRedactedReply(parent?.sender)
            else -> message
        }
    }
    return buffer.copy(messages = messages, reactions = buffer.reactions - msgid, replyDraft = draft)
}

internal fun reconcileEcho(
    buffer: ConversationBuffer,
    kind: MessageKind,
    text: String,
    echoLabel: String?,
    msgid: String?,
    timestampMs: Long,
    attachmentUrl: String?,
    senderAccount: String? = null,
): ConversationBuffer? {
    val index = if (echoLabel != null) {
        buffer.messages.indexOfFirst { it.pendingEcho && it.echoLabel == echoLabel }
    } else {
        buffer.messages.indexOfFirst { it.pendingEcho && it.kind == kind && it.text == text }
    }
    if (index < 0) return null
    val messages = buffer.messages.toMutableList()
    val previous = messages[index]
    val canonical = previous.copy(
        text = if (previous.redacted) previous.text else text,
        msgid = msgid ?: previous.msgid,
        timestampMs = timestampMs,
        attachmentUrl = if (previous.redacted) null else attachmentUrl ?: previous.attachmentUrl,
        pendingEcho = false,
        echoLabel = null,
        senderAccount = senderAccount ?: previous.senderAccount,
    )
    val destination = if (previous.timestampMs == timestampMs) index else {
        val firstEqual = chronologicalInsertionIndex(buffer.messages, timestampMs, afterEqual = false)
        val afterEqual = chronologicalInsertionIndex(buffer.messages, timestampMs, afterEqual = true)
        index.coerceIn(
            firstEqual - if (index < firstEqual) 1 else 0,
            afterEqual - if (index < afterEqual) 1 else 0,
        )
    }
    if (destination < index) {
        for (position in index downTo destination + 1) messages[position] = messages[position - 1]
    } else {
        for (position in index until destination) messages[position] = messages[position + 1]
    }
    messages[destination] = canonical
    val duplicateIndex = if (msgid == null) -1 else messages.indexOfFirst {
        it.localId != previous.localId && it.msgid == msgid
    }
    if (duplicateIndex >= 0) {
        messages[destination] = mergeChatMessageMetadata(messages[destination], messages[duplicateIndex])
            .copy(pendingEcho = false, echoLabel = null)
        messages.removeAt(duplicateIndex)
    }
    val redactedMsgid = messages.firstOrNull { it.localId == previous.localId && it.redacted }?.msgid
    return buffer.copy(
        messages = sanitizeRedactedMessages(messages),
        reactions = if (redactedMsgid == null) buffer.reactions else buffer.reactions - redactedMsgid,
    )
}

internal fun applyReaction(
    reactions: Map<String, Map<String, Set<String>>>,
    effect: InboundEffect.ApplyReaction,
): Map<String, Map<String, Set<String>>> {
    var updated = reactions
    for (msgid in effect.msgids) {
        val perMessage = updated[msgid].orEmpty().toMutableMap()
        val nicks = perMessage[effect.emoji].orEmpty().toMutableSet()
        if (effect.added) nicks.add(effect.sender) else nicks.remove(effect.sender)
        if (nicks.isEmpty()) perMessage.remove(effect.emoji) else perMessage[effect.emoji] = nicks
        updated = updated + (msgid to perMessage.toMap())
    }
    return updated
}

internal fun appendChronologically(
    messages: List<ChatMessage>,
    entry: ChatMessage,
    optimistic: Boolean = false,
): List<ChatMessage> {
    if (optimistic) {
        val timestampMs = maxOf(entry.timestampMs, messages.lastOrNull()?.timestampMs ?: entry.timestampMs)
        return messages + if (timestampMs == entry.timestampMs) entry else entry.copy(timestampMs = timestampMs)
    }
    if (messages.isEmpty() || messages.last().timestampMs <= entry.timestampMs) return messages + entry
    val index = chronologicalInsertionIndex(messages, entry.timestampMs, afterEqual = true)
    return ArrayList<ChatMessage>(messages.size + 1).also {
        for (position in 0 until index) it.add(messages[position])
        it.add(entry)
        for (position in index until messages.size) it.add(messages[position])
    }
}

private fun chronologicalInsertionIndex(
    messages: List<ChatMessage>,
    timestampMs: Long,
    afterEqual: Boolean,
): Int {
    var start = 0
    var end = messages.size
    while (start < end) {
        val middle = (start + end).ushr(1)
        val time = messages[middle].timestampMs
        if (time < timestampMs || afterEqual && time == timestampMs) start = middle + 1 else end = middle
    }
    return start
}

internal fun orderBeforeReplay(messages: List<ChatMessage>, firstNewId: Long): List<ChatMessage> {
    for (index in 1 until messages.size) {
        val previous = messages[index - 1]
        val current = messages[index]
        if (previous.timestampMs > current.timestampMs ||
            previous.timestampMs == current.timestampMs && previous.localId < firstNewId && current.localId >= firstNewId
        ) {
            return messages.sortedWith(compareBy<ChatMessage> { it.timestampMs }
                .thenBy { if (it.localId >= firstNewId) 0 else 1 })
        }
    }
    return messages
}

internal fun mergeConversationMessages(
    preferred: List<ChatMessage>,
    incoming: List<ChatMessage>,
    incomingCanonical: Boolean = false,
): List<ChatMessage> {
    if (incoming.isEmpty()) return sanitizeRedactedMessages(preferred)
    val merged = ArrayList<ChatMessage>(preferred.size + incoming.size).also { it.addAll(preferred) }
    val byMsgid = mutableMapOf<String, Int>()
    val byStoredRowId = mutableMapOf<Long, Int>()
    for (index in merged.indices) {
        merged[index].msgid?.let { byMsgid[it] = index }
        merged[index].storedRowId?.let { byStoredRowId[it] = index }
    }
    for (message in incoming) {
        val identified = message.msgid?.let(byMsgid::get)
            ?: message.storedRowId?.let(byStoredRowId::get)
            ?: -1
        val anonymous = if (identified >= 0) -1 else merged.uniqueHistoryIndex {
            !it.pendingEcho && !message.pendingEcho && (it.storedRowId == null || message.storedRowId == null) &&
                (it.msgid == null || message.msgid == null) && it.sender == message.sender &&
                it.kind == message.kind && it.text == message.text && it.timestampMs == message.timestampMs
        }
        val index = if (identified >= 0) identified else anonymous
        if (index < 0) {
            message.msgid?.let { byMsgid[it] = merged.size }
            message.storedRowId?.let { byStoredRowId[it] = merged.size }
            merged.add(message)
        } else {
            merged[index] = mergeChatMessageMetadata(merged[index], message, incomingCanonical)
            merged[index].msgid?.let { byMsgid[it] = index }
            merged[index].storedRowId?.let { byStoredRowId[it] = index }
        }
    }
    merged.sortWith(conversationMessageOrder)
    return sanitizeRedactedMessages(merged)
}

internal fun mergeChatMessageMetadata(
    preferred: ChatMessage,
    incoming: ChatMessage,
    incomingCanonical: Boolean = false,
): ChatMessage {
    val base = if (incomingCanonical) incoming else preferred
    val other = if (incomingCanonical) preferred else incoming
    val preview = when {
        base.replyPreview?.redacted == true -> base.replyPreview
        other.replyPreview?.redacted == true -> other.replyPreview
        else -> base.replyPreview ?: other.replyPreview
    }
    val pendingEcho = preferred.pendingEcho && incoming.pendingEcho && preferred.msgid == null && incoming.msgid == null
    val merged = base.copy(
        localId = preferred.localId,
        msgid = base.msgid ?: other.msgid,
        storedRowId = base.storedRowId ?: other.storedRowId,
        replyToMsgid = base.replyToMsgid ?: other.replyToMsgid,
        localReplyParentRowId = base.localReplyParentRowId ?: other.localReplyParentRowId,
        replyPreview = preview,
        attachmentUrl = base.attachmentUrl ?: other.attachmentUrl,
        attachmentName = base.attachmentName ?: other.attachmentName,
        attachmentMimeType = base.attachmentMimeType ?: other.attachmentMimeType,
        attachmentSizeBytes = base.attachmentSizeBytes ?: other.attachmentSizeBytes,
        attachmentWidth = base.attachmentWidth ?: other.attachmentWidth,
        attachmentHeight = base.attachmentHeight ?: other.attachmentHeight,
        senderAccount = base.senderAccount ?: other.senderAccount,
        channelContext = base.channelContext ?: other.channelContext,
        sentByUs = preferred.sentByUs || incoming.sentByUs,
        highlightsMe = when {
            preferred.highlightsKnown && incoming.highlightsKnown -> preferred.highlightsMe || incoming.highlightsMe
            preferred.highlightsKnown -> preferred.highlightsMe
            incoming.highlightsKnown -> incoming.highlightsMe
            else -> false
        },
        highlightsKnown = preferred.highlightsKnown || incoming.highlightsKnown,
        pendingEcho = pendingEcho,
        echoLabel = if (pendingEcho) base.echoLabel ?: other.echoLabel else null,
        playback = preferred.playback && incoming.playback,
        historyContext = preferred.historyContext && incoming.historyContext,
        redacted = preferred.redacted || incoming.redacted,
        reactionsTruncated = preferred.reactionsTruncated || incoming.reactionsTruncated,
    )
    return if (merged.redacted) merged.asRedacted() else merged
}

private val conversationMessageOrder = compareBy<ChatMessage> { it.timestampMs }
