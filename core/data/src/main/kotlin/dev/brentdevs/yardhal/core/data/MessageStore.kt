package dev.brentdevs.yardhal.core.data

import java.security.MessageDigest

public class MessageStore(private val dao: MessageDao) {

    public suspend fun record(message: StoredMessage): Boolean = recordWithRowId(message) != null

    public suspend fun recordWithRowId(message: StoredMessage): Long? = dao.recordAtomic(message.toRow())

    public suspend fun recordLocalEcho(message: StoredMessage): Long? =
        dao.recordAtomic(message.toRow(), freshLocal = true)

    public suspend fun healEcho(conversation: ConversationRef, rowId: Long, message: StoredMessage): Long? {
        require(message.networkId == conversation.networkId && message.conversation.normalizedTarget == conversation.normalizedTarget)
        val inserted = dao.recordAtomic(message.toRow(), rowId)
        if (inserted != null) return inserted
        val msgid = message.msgid
        if (msgid != null) return dao.byMsgid(conversation.networkId, conversation.normalizedTarget, msgid)?.rowId
        return dao.byRowId(rowId)?.takeIf {
            it.networkId == conversation.networkId && it.conversation == conversation.normalizedTarget
        }?.rowId
    }

    private fun StoredMessage.toRow(): MessageRow = MessageRow(
        networkId = networkId,
        conversation = conversation.normalizedTarget,
        msgid = msgid,
        contentHash = contentHash(this),
        senderNick = senderNick,
        senderUser = senderUser,
        senderHost = senderHost,
        kind = kind.name,
        text = text,
        sentByUs = sentByUs,
        timestampMs = timestampMs,
        channelContext = channelContext,
        historyContext = historyContext,
        highlightsMe = highlightsMe,
        highlightsKnown = highlightsKnown,
        playback = playback,
        replyToMsgid = replyToMsgid,
        replyParentRowId = replyParentRowId,
        attachmentUrl = attachmentUrl,
        attachmentName = attachmentName,
        attachmentMimeType = attachmentMimeType,
        attachmentSizeBytes = attachmentSizeBytes,
        attachmentWidth = attachmentWidth,
        attachmentHeight = attachmentHeight,
        senderAccount = senderAccount,
        redacted = redacted,
        reactionsTruncated = reactionsTruncated,
        pendingEcho = pendingEcho,
    )

    public suspend fun findStoredMessage(message: StoredMessage): StoredMessage? {
        val conversation = message.conversation.normalizedTarget
        val byMsgid = message.msgid?.let { dao.byMsgid(message.networkId, conversation, it) }
        return (byMsgid ?: dao.byHash(message.networkId, conversation, contentHash(message)))?.toStored(message.conversation)
    }

    public suspend fun byRowId(conversation: ConversationRef, rowId: Long): StoredMessage? =
        dao.byRowId(rowId)?.takeIf {
            it.networkId == conversation.networkId && it.conversation == conversation.normalizedTarget
        }?.toStored(conversation)

    public suspend fun byRowIds(conversation: ConversationRef, rowIds: Collection<Long>): List<StoredMessage> = buildList {
        val seen = hashSetOf<Long>()
        val batch = ArrayList<Long>(minOf(rowIds.size, 500))
        suspend fun appendBatch() {
            for (row in dao.byRowIds(conversation.networkId, conversation.normalizedTarget, batch)) add(row.toStored(conversation))
            batch.clear()
        }
        for (rowId in rowIds) {
            if (!seen.add(rowId)) continue
            batch.add(rowId)
            if (batch.size == 500) appendBatch()
        }
        if (batch.isNotEmpty()) appendBatch()
    }

    public suspend fun replyParents(conversation: ConversationRef, messages: List<StoredMessage>): Map<Long, StoredMessage> =
        buildMap {
            for (message in messages) {
                val parent = message.replyParentRowId?.let { byRowId(conversation, it) }
                    ?: message.replyToMsgid?.let {
                        dao.byMsgid(conversation.networkId, conversation.normalizedTarget, it)?.toStored(conversation)
                    }
                if (!message.redacted && parent != null) put(message.rowId, parent)
            }
        }

    public suspend fun reactions(conversation: ConversationRef): Map<String, Map<String, Set<String>>> {
        val result = linkedMapOf<String, MutableMap<String, MutableSet<String>>>()
        for (reaction in dao.visibleReactions(conversation.networkId, conversation.normalizedTarget)) {
            result.getOrPut(reaction.msgid) { linkedMapOf() }.getOrPut(reaction.emoji) { linkedSetOf() }.add(reaction.sender)
        }
        return result
    }

    public suspend fun reactions(conversation: ConversationRef, msgid: String): Map<String, Set<String>> {
        val result = linkedMapOf<String, MutableSet<String>>()
        for (reaction in dao.visibleReactionsForMessage(conversation.networkId, conversation.normalizedTarget, msgid)) {
            result.getOrPut(reaction.emoji) { linkedSetOf() }.add(reaction.sender)
        }
        return result
    }

    public suspend fun reactions(conversation: ConversationRef, msgids: Collection<String>): Map<String, Map<String, Set<String>>> {
        val result = linkedMapOf<String, MutableMap<String, MutableSet<String>>>()
        val seen = hashSetOf<String>()
        val batch = ArrayList<String>(minOf(msgids.size, 500))
        suspend fun appendBatch() {
            for (reaction in dao.visibleReactionsForMessages(conversation.networkId, conversation.normalizedTarget, batch)) {
                result.getOrPut(reaction.msgid) { linkedMapOf() }.getOrPut(reaction.emoji) { linkedSetOf() }.add(reaction.sender)
            }
            batch.clear()
        }
        for (msgid in msgids) {
            if (!seen.add(msgid)) continue
            batch.add(msgid)
            if (batch.size == 500) appendBatch()
        }
        if (batch.isNotEmpty()) appendBatch()
        return result
    }

    public suspend fun applyReaction(
        conversation: ConversationRef,
        msgid: String,
        sender: String,
        emoji: String,
        added: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        perParentLimit: Int = MESSAGE_REACTIONS_PER_PARENT_LIMIT,
        networkLimit: Int = MESSAGE_REACTIONS_PER_NETWORK_LIMIT,
        onRetentionChanged: (Set<Long>) -> Unit = {},
    ) {
        val changed = dao.applyReactionAtomic(
            MessageReactionRow(conversation.networkId, conversation.normalizedTarget, msgid, emoji, sender, nowMs),
            added, perParentLimit, networkLimit,
        )
        if (changed.isNotEmpty()) onRetentionChanged(changed)
    }

    public suspend fun redact(conversation: ConversationRef, msgid: String, nowMs: Long = System.currentTimeMillis()) {
        dao.redactAtomic(conversation.networkId, conversation.normalizedTarget, msgid, nowMs)
    }

    public suspend fun unreadCounts(conversation: ConversationRef, cursor: ReadCursor): UnreadCounts =
        dao.unreadCounts(conversation.networkId, conversation.normalizedTarget, cursor.timestampMs, cursor.rowId)

    public suspend fun newestCursor(conversation: ConversationRef): ReadCursor? =
        dao.newestUnreadEligible(conversation.networkId, conversation.normalizedTarget)?.let { ReadCursor(it.timestampMs, it.rowId) }

    public suspend fun maintain(nowMs: Long = System.currentTimeMillis()) {
        dao.pruneOrphanReactions(nowMs)
        dao.pruneTombstones(nowMs)
        for (networkId in dao.reactionNetworks()) dao.pruneReactionMemberships(networkId, MESSAGE_REACTIONS_PER_PARENT_LIMIT, MESSAGE_REACTIONS_PER_NETWORK_LIMIT)
    }

    public suspend fun maintainReactionMemberships(
        networkId: String,
        perParentLimit: Int = MESSAGE_REACTIONS_PER_PARENT_LIMIT,
        networkLimit: Int = MESSAGE_REACTIONS_PER_NETWORK_LIMIT,
    ) {
        dao.pruneReactionMemberships(networkId, perParentLimit, networkLimit)
    }

    public suspend fun maintainNetwork(
        networkId: String,
        keepMessages: Int,
        keepConversations: Int,
        protectedConversation: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        protectedConversations: Set<String> = emptySet(),
    ) {
        val protected = linkedSetOf<String>()
        if (protectedConversation != null) protected.add(protectedConversation)
        protected.addAll(protectedConversations)
        dao.maintainNetworkAtomic(networkId, keepMessages, keepConversations, protected.toList(), nowMs)
    }

    public suspend fun recent(conversation: ConversationRef, limit: Int): List<StoredMessage> =
        dao.recent(conversation.networkId, conversation.normalizedTarget, limit).map { it.toStored(conversation) }
            .reversed()

    public suspend fun after(conversation: ConversationRef, afterMs: Long): List<StoredMessage> =
        dao.after(conversation.networkId, conversation.normalizedTarget, afterMs).map { it.toStored(conversation) }

    public suspend fun before(conversation: ConversationRef, cursor: MessageCursor, limit: Int): List<StoredMessage> =
        dao.before(conversation.networkId, conversation.normalizedTarget, cursor.timestampMs, cursor.rowId, limit)
            .map { it.toStored(conversation) }
            .reversed()

    public suspend fun around(conversation: ConversationRef, rowId: Long, timestampMs: Long): List<StoredMessage> {
        val older = dao.beforeIncluding(conversation.networkId, conversation.normalizedTarget, timestampMs, rowId, 40)
            .asReversed()
        val newer = dao.afterRow(conversation.networkId, conversation.normalizedTarget, timestampMs, rowId, 30)
        return (older + newer).map { it.toStored(conversation) }
    }

    public suspend fun latestTimestamp(conversation: ConversationRef): Long? =
        dao.latestTimestamp(conversation.networkId, conversation.normalizedTarget)

    public suspend fun knownConversations(networkId: String): List<ConversationRef> =
        dao.conversations(networkId).map { target -> conversationRef(networkId, target) }

    public suspend fun historyAnchors(networkId: String): List<StoredMessage> =
        dao.historyAnchors(networkId).map { row -> row.toStored(conversationRef(networkId, row.conversation)) }

    public suspend fun trimTo(conversation: ConversationRef, keep: Int, nowMs: Long = System.currentTimeMillis()) {
        dao.trimAtomic(conversation.networkId, conversation.normalizedTarget, keep, nowMs)
    }

    public suspend fun renameConversation(
        from: ConversationRef,
        to: ConversationRef,
        onRowMerged: (Long, Long) -> Unit = { _, _ -> },
    ): Int {
        if (from.networkId != to.networkId || from.normalizedTarget == to.normalizedTarget) return 0
        val merges = dao.renameAtomic(from.networkId, from.normalizedTarget, to.normalizedTarget)
        for (merge in merges.rows) onRowMerged(merge.removedRowId, merge.survivingRowId)
        return merges.movedCount
    }

    public suspend fun reindexAll() {
        dao.reindexAtomic()
    }

    public suspend fun deleteNetwork(networkId: String) {
        dao.deleteNetworkAtomic(networkId)
    }

    public suspend fun search(
        raw: String,
        limit: Int = 50,
        networkId: String? = null,
        conversation: String? = null,
    ): List<FtsHit> {
        val query = FtsQuery.build(raw) ?: return emptyList()
        val scopeSql = buildString {
            if (networkId != null) append(" AND m.networkId = ?")
            if (conversation != null) append(" AND m.conversation = ?")
        }
        val args = buildList<Any> {
            add(query)
            if (networkId != null) add(networkId)
            if (conversation != null) add(conversation)
            add(limit)
        }
        return dao.searchFtsRaw(
            androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT m.rowId AS rowId, m.networkId AS networkId, m.conversation AS conversation, " +
                    "m.senderNick AS sender, m.timestampMs AS timestampMs, " +
                    "snippet(message_fts) AS snippet " +
                    "FROM message_fts JOIN messages m ON m.rowId = message_fts.rowid " +
                    "WHERE message_fts MATCH ?$scopeSql ORDER BY m.timestampMs DESC LIMIT ?",
                args.toTypedArray(),
            ),
        ).map { hit -> hit.copy(snippet = decorateSnippet(hit.snippet)) }
    }

    private fun decorateSnippet(raw: String): String = raw
        .replace("<b>", "[")
        .replace("</b>", "]")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&#39;", "'")

    public companion object {

        public fun contentHash(message: StoredMessage): String = hashOf(
            message.networkId,
            message.conversation.normalizedTarget,
            message.senderNick,
            message.kind.name,
            message.text,
            message.timestampMs,
        )

        internal fun renamedHash(row: MessageRow, target: String): String =
            hashOf(row.networkId, target, row.senderNick, row.kind, row.text, row.timestampMs)

        internal fun identityHash(row: MessageRow): String =
            row.originalIdentityHash ?: hashOf("", "", row.senderNick, row.kind, row.text, row.timestampMs)

        private fun hashOf(
            networkId: String,
            conversation: String,
            senderNick: String,
            kind: String,
            text: String,
            timestampMs: Long,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val payload = buildString {
                append(networkId).append('\u0000')
                append(conversation).append('\u0000')
                append(senderNick).append('\u0000')
                append(kind).append('\u0000')
                append(text).append('\u0000')
                append(timestampMs)
            }
            return digest.digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        private fun conversationRef(networkId: String, target: String): ConversationRef {
            val kind = when {
                target == ConversationRef.SERVER_TARGET -> ConversationKind.SERVER
                target.firstOrNull()?.let { it in "#&" } == true -> ConversationKind.CHANNEL
                else -> ConversationKind.DIRECT_MESSAGE
            }
            return ConversationRef(networkId, kind, target, target)
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
            channelContext = channelContext,
            historyContext = historyContext,
            highlightsMe = highlightsMe,
            highlightsKnown = highlightsKnown,
            playback = playback,
            replyToMsgid = replyToMsgid,
            replyParentRowId = replyParentRowId,
            attachmentUrl = attachmentUrl,
            attachmentName = attachmentName,
            attachmentMimeType = attachmentMimeType,
            attachmentSizeBytes = attachmentSizeBytes,
            attachmentWidth = attachmentWidth,
            attachmentHeight = attachmentHeight,
            senderAccount = senderAccount,
            redacted = redacted,
            reactionsTruncated = reactionsTruncated,
            pendingEcho = pendingEcho,
        )
    }
}

public data class MessageCursor(public val timestampMs: Long, public val rowId: Long)
