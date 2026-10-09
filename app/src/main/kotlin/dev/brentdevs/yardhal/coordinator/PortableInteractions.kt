package dev.brentdevs.yardhal.coordinator

internal fun portableQuote(parent: ChatMessage, text: String): String {
    val sender = sanitizeOutboundText(parent.relayedSender ?: parent.sender).take(64)
    val quote = if (parent.redacted) "Deleted message" else sanitizeOutboundText(parent.relayedBody ?: parent.text)
        .take(160).ifBlank { if (parent.attachmentUrl != null) "Attachment" else "Message" }
    return "> $sender: $quote — $text"
}

internal fun ownReaction(buffer: ConversationBuffer, msgid: String, emoji: String, ownNick: String): Boolean =
    ownNick in buffer.reactions[msgid]?.get(emoji).orEmpty()

internal fun messageMediaIdentity(buffer: ConversationBuffer, message: ChatMessage): String =
    "${buffer.ref.storageKey}|" + when {
        message.storedRowId != null -> "row:${message.storedRowId}"
        message.msgid != null -> "msgid:${message.msgid}"
        else -> "local:${message.localId}"
    }
