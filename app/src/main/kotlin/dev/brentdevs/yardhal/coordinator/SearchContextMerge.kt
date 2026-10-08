package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.StoredMessage

internal fun mergeSearchContext(
    current: List<ChatMessage>,
    context: List<StoredMessage>,
    prependEqualTimestamp: Boolean = false,
    nextLocalId: () -> Long,
): List<ChatMessage> {
    val merged = current.toMutableList()
    var added = false
    for (row in context) {
        val identified = merged.indexOfFirst { message ->
            message.storedRowId == row.rowId || (row.msgid != null && message.msgid == row.msgid)
        }
        val anonymous = if (identified >= 0) -1 else merged.uniqueHistoryIndex { message ->
            message.storedRowId == null && (message.msgid == null || row.msgid == null) &&
                message.sender == row.senderNick && message.kind == row.kind &&
                message.text == row.text && message.timestampMs == row.timestampMs
        }
        val index = if (identified >= 0) identified else anonymous
        if (index >= 0) {
            val existing = merged[index]
            merged[index] = existing.copy(
                storedRowId = row.rowId,
                msgid = existing.msgid ?: row.msgid,
                historyContext = existing.historyContext && row.historyContext,
            )
        } else {
            added = true
            merged.add(
                ChatMessage(
                    localId = nextLocalId(),
                    sender = row.senderNick,
                    kind = row.kind,
                    text = row.text,
                    timestampMs = row.timestampMs,
                    sentByUs = row.sentByUs,
                    highlightsMe = false,
                    msgid = row.msgid,
                    storedRowId = row.rowId,
                    channelContext = row.channelContext,
                    historyContext = row.historyContext,
                ),
            )
        }
    }
    if (!added) return merged
    if (prependEqualTimestamp && current.isNotEmpty()) {
        java.util.Collections.rotate(merged, merged.size - current.size)
    }
    merged.sortBy { it.timestampMs }
    return merged
}

internal inline fun <T> List<T>.uniqueHistoryIndex(matches: (T) -> Boolean): Int {
    var found = -1
    for (index in indices) {
        if (!matches(this[index])) continue
        if (found >= 0) return -1
        found = index
    }
    return found
}
