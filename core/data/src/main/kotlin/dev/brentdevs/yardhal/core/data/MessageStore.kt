package dev.brentdevs.yardhal.core.data

import java.security.MessageDigest

public class MessageStore(private val dao: MessageDao) {

    public suspend fun record(message: StoredMessage): Boolean {
        val hash = contentHash(message)
        val row = MessageRow(
            networkId = message.networkId,
            conversation = message.conversation.normalizedTarget,
            msgid = message.msgid,
            contentHash = hash,
            senderNick = message.senderNick,
            senderUser = message.senderUser,
            senderHost = message.senderHost,
            kind = message.kind.name,
            text = message.text,
            sentByUs = message.sentByUs,
            timestampMs = message.timestampMs,
        )
        if (row.msgid != null) {
            return dao.insert(row) != -1L
        }
        if (dao.existsByHash(message.networkId, row.conversation, hash)) return false
        return dao.insert(row) != -1L
    }

    public suspend fun recent(conversation: ConversationRef, limit: Int): List<StoredMessage> =
        dao.recent(conversation.networkId, conversation.normalizedTarget, limit).map { it.toStored(conversation) }
            .reversed()

    public suspend fun after(conversation: ConversationRef, afterMs: Long): List<StoredMessage> =
        dao.after(conversation.networkId, conversation.normalizedTarget, afterMs).map { it.toStored(conversation) }

    public suspend fun before(conversation: ConversationRef, beforeMs: Long, limit: Int): List<StoredMessage> =
        dao.before(conversation.networkId, conversation.normalizedTarget, beforeMs, limit)
            .map { it.toStored(conversation) }
            .reversed()

    public suspend fun latestTimestamp(conversation: ConversationRef): Long? =
        dao.latestTimestamp(conversation.networkId, conversation.normalizedTarget)

    public suspend fun trimTo(conversation: ConversationRef, keep: Int) {
        dao.trim(conversation.networkId, conversation.normalizedTarget, keep)
    }

    public suspend fun deleteNetwork(networkId: String) {
        dao.deleteNetwork(networkId)
    }

    public companion object {

        public fun contentHash(message: StoredMessage): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val payload = buildString {
                append(message.networkId).append('\u0000')
                append(message.conversation.normalizedTarget).append('\u0000')
                append(message.senderNick).append('\u0000')
                append(message.kind.name).append('\u0000')
                append(message.text).append('\u0000')
                append(message.timestampMs)
            }
            return digest.digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        private fun MessageRow.toStored(conversation: ConversationRef): StoredMessage = StoredMessage(
            rowId = rowId,
            networkId = networkId,
            conversation = conversation.copy(normalizedTarget = this@toStored.conversation),
            msgid = msgid,
            senderNick = senderNick,
            senderUser = senderUser,
            senderHost = senderHost,
            kind = MessageKind.valueOf(kind),
            text = text,
            sentByUs = sentByUs,
            timestampMs = timestampMs,
        )
    }
}
