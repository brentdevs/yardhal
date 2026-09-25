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
            val inserted = dao.insert(row)
            if (inserted != -1L) indexForFts(inserted, row.senderNick, row.text)
            return inserted != -1L
        }
        if (dao.existsByHash(message.networkId, row.conversation, hash)) return false
        val inserted = dao.insert(row)
        if (inserted != -1L) indexForFts(inserted, row.senderNick, row.text)
        return inserted != -1L
    }

    private suspend fun indexForFts(rowId: Long, sender: String, body: String) {
        runCatching {
            dao.indexMessageRaw(
                androidx.sqlite.db.SimpleSQLiteQuery(
                    "INSERT INTO message_fts(rowid, sender, body) VALUES(?, ?, ?)",
                    arrayOf<Any>(rowId, sender, body),
                ),
            )
        }
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

    public suspend fun knownConversations(
        networkId: String,
        casemapping: dev.brentdevs.yardhal.core.protocol.CaseMapping = dev.brentdevs.yardhal.core.protocol.CaseMapping.RFC1459,
    ): List<ConversationRef> =
        dao.conversations(networkId).mapNotNull { target ->
            val leader = target.firstOrNull()
            when {
                target == ConversationRef.SERVER_TARGET -> ConversationRef.server(networkId)
                leader != null && leader in "#&" -> ConversationRef.channel(networkId, target, casemapping)
                else -> ConversationRef.directMessage(networkId, target, casemapping)
            }
        }

    public suspend fun trimTo(conversation: ConversationRef, keep: Int) {
        dao.trim(conversation.networkId, conversation.normalizedTarget, keep)
    }

    public suspend fun reindexAll() {
        runCatching {
            dao.deleteFtsForNetworkRaw(
                androidx.sqlite.db.SimpleSQLiteQuery("DELETE FROM message_fts"),
            )
            for (row in dao.allRows()) {
                indexForFts(row.rowId, row.senderNick, row.text)
            }
        }
    }

    public suspend fun deleteNetwork(networkId: String) {
        runCatching {
            dao.deleteFtsForNetworkRaw(
                androidx.sqlite.db.SimpleSQLiteQuery(
                    "DELETE FROM message_fts WHERE rowid IN (SELECT rowId FROM messages WHERE networkId = ?)",
                    arrayOf<Any>(networkId),
                ),
            )
        }
        dao.deleteNetwork(networkId)
    }

    public suspend fun search(raw: String, limit: Int = 50): List<FtsHit> {
        val query = FtsQuery.build(raw) ?: return emptyList()
        return runCatching {
            dao.searchFtsRaw(
                androidx.sqlite.db.SimpleSQLiteQuery(
                    "SELECT m.rowId AS rowId, m.networkId AS networkId, m.conversation AS conversation, " +
                        "m.senderNick AS sender, m.timestampMs AS timestampMs, " +
                        "snippet(message_fts, 1, '[', ']', '…', 12) AS snippet " +
                        "FROM message_fts JOIN messages m ON m.rowId = message_fts.rowid " +
                        "WHERE message_fts MATCH ? ORDER BY m.timestampMs DESC LIMIT ?",
                    arrayOf<Any>(query, limit),
                ),
            )
        }.getOrDefault(emptyList())
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
