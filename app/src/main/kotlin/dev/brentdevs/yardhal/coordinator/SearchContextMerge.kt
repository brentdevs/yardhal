package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.StoredMessage

internal fun mergeSearchContext(
    current: List<ChatMessage>,
    context: List<StoredMessage>,
    nextLocalId: () -> Long,
): List<ChatMessage> {
    val merged = current.toMutableList()
    for (row in context) {
        val index = merged.indexOfFirst { message ->
            message.storedRowId == row.rowId ||
                (row.msgid != null && message.msgid == row.msgid)
        }
        if (index >= 0) {
            merged[index] = merged[index].copy(storedRowId = row.rowId)
        } else {
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
                ),
            )
        }
    }
    return merged.sortedBy { it.timestampMs }
}
