package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.MessageKind

internal fun redactInBuffer(buffer: ConversationBuffer, msgid: String): ConversationBuffer {
    val index = buffer.messages.indexOfFirst { it.msgid == msgid }
    if (index < 0) return buffer
    val messages = buffer.messages.toMutableList()
    messages[index] = messages[index].copy(text = "message deleted", kind = MessageKind.SYSTEM)
    return buffer.copy(messages = messages)
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
        text = text,
        msgid = msgid,
        timestampMs = timestampMs,
        attachmentUrl = attachmentUrl,
        pendingEcho = false,
        echoLabel = null,
        senderAccount = senderAccount,
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
    return buffer.copy(messages = messages)
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
    if (preferred.isEmpty()) return incoming
    if (incoming.isEmpty()) return preferred
    val merged = ArrayList<ChatMessage>(preferred.size + incoming.size).also { it.addAll(preferred) }
    val byMsgid = mutableMapOf<String, Int>()
    for (index in merged.indices) merged[index].msgid?.let { byMsgid[it] = index }
    for (message in incoming) {
        val identified = message.msgid?.let(byMsgid::get) ?: -1
        val anonymous = if (identified >= 0) -1 else merged.uniqueHistoryIndex {
            (it.msgid == null || message.msgid == null) && it.sender == message.sender &&
                it.kind == message.kind && it.text == message.text && it.timestampMs == message.timestampMs
        }
        val index = if (identified >= 0) identified else anonymous
        if (index < 0) {
            message.msgid?.let { byMsgid[it] = merged.size }
            merged.add(message)
        } else {
            val previous = merged[index]
            val base = if (incomingCanonical) message else previous
            val other = if (incomingCanonical) previous else message
            merged[index] = base.copy(
                localId = previous.localId,
                msgid = base.msgid ?: other.msgid,
                storedRowId = base.storedRowId ?: other.storedRowId,
                replyToMsgid = base.replyToMsgid ?: other.replyToMsgid,
                attachmentUrl = base.attachmentUrl ?: other.attachmentUrl,
                senderAccount = base.senderAccount ?: other.senderAccount,
                channelContext = base.channelContext ?: other.channelContext,
                sentByUs = previous.sentByUs || message.sentByUs,
                historyContext = previous.historyContext && message.historyContext,
            )
            merged[index].msgid?.let { byMsgid[it] = index }
        }
    }
    merged.sortWith(conversationMessageOrder)
    return merged
}

private val conversationMessageOrder = compareBy<ChatMessage> { it.timestampMs }
