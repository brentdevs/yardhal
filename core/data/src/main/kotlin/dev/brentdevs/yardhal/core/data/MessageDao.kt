package dev.brentdevs.yardhal.core.data

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery

@Dao
public interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insert(row: MessageRow): Long

    @Query("SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation AND msgid = :msgid LIMIT 1")
    public suspend fun byMsgid(networkId: String, conversation: String, msgid: String): MessageRow?

    @Query(
        "SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation AND contentHash = :hash " +
            "ORDER BY rowId LIMIT 1",
    )
    public suspend fun byHash(networkId: String, conversation: String, hash: String): MessageRow?

    @Query(
        "SELECT rowId FROM messages WHERE contentHash = :hash AND conversation = :conversation AND networkId = :networkId " +
            "AND msgid IS NULL ORDER BY rowId LIMIT 1",
    )
    public suspend fun rowIdWithoutMsgidByHash(networkId: String, conversation: String, hash: String): Long?

    @Query(
        "SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation " +
            "ORDER BY timestampMs DESC, rowId DESC LIMIT :limit",
    )
    public suspend fun recent(networkId: String, conversation: String, limit: Int): List<MessageRow>

    @Query(
        "SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation AND timestampMs > :afterMs " +
            "ORDER BY timestampMs ASC, rowId ASC",
    )
    public suspend fun after(networkId: String, conversation: String, afterMs: Long): List<MessageRow>

    @Query(
        "SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation " +
            "AND (timestampMs < :timestampMs OR (timestampMs = :timestampMs AND rowId < :rowId)) " +
            "ORDER BY timestampMs DESC, rowId DESC LIMIT :limit",
    )
    public suspend fun before(
        networkId: String,
        conversation: String,
        timestampMs: Long,
        rowId: Long,
        limit: Int,
    ): List<MessageRow>

    @Query(
        "SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation " +
            "AND (timestampMs < :timestampMs OR (timestampMs = :timestampMs AND rowId <= :rowId)) " +
            "ORDER BY timestampMs DESC, rowId DESC LIMIT :limit",
    )
    public suspend fun beforeIncluding(
        networkId: String,
        conversation: String,
        timestampMs: Long,
        rowId: Long,
        limit: Int,
    ): List<MessageRow>

    @Query(
        "SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation " +
            "AND (timestampMs > :timestampMs OR (timestampMs = :timestampMs AND rowId > :rowId)) " +
            "ORDER BY timestampMs ASC, rowId ASC LIMIT :limit",
    )
    public suspend fun afterRow(
        networkId: String,
        conversation: String,
        timestampMs: Long,
        rowId: Long,
        limit: Int,
    ): List<MessageRow>

    @Query(
        "SELECT conversation FROM messages WHERE networkId = :networkId GROUP BY conversation " +
            "ORDER BY MAX(timestampMs) DESC, conversation ASC",
    )
    public suspend fun conversations(networkId: String): List<String>

    @Query("SELECT MAX(timestampMs) FROM messages WHERE networkId = :networkId AND conversation = :conversation")
    public suspend fun latestTimestamp(networkId: String, conversation: String): Long?

    @Query(
        "SELECT m.* FROM messages m WHERE m.networkId = :networkId AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION') " +
            "AND m.conversation NOT LIKE '*%' AND m.historyContext = 0 " +
            "AND NOT EXISTS (SELECT 1 FROM messages newer WHERE newer.networkId = m.networkId " +
            "AND newer.conversation = m.conversation AND newer.kind IN ('PRIVMSG', 'NOTICE', 'ACTION') " +
            "AND newer.historyContext = 0 " +
            "AND (newer.timestampMs > m.timestampMs OR (newer.timestampMs = m.timestampMs AND newer.rowId > m.rowId))) " +
            "ORDER BY m.timestampMs ASC, m.rowId ASC",
    )
    public suspend fun historyAnchors(networkId: String): List<MessageRow>

    @Query("DELETE FROM messages WHERE networkId = :networkId")
    public suspend fun deleteNetwork(networkId: String)

    @Query("SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation ORDER BY rowId")
    public suspend fun allIn(networkId: String, conversation: String): List<MessageRow>

    @Query("DELETE FROM messages WHERE rowId = :rowId")
    public suspend fun deleteRow(rowId: Long)

    @Query("SELECT * FROM messages")
    public suspend fun allRows(): List<MessageRow>

    @RawQuery(observedEntities = [MessageRow::class])
    public suspend fun indexMessageRaw(query: SupportSQLiteQuery): Int

    @RawQuery(observedEntities = [MessageRow::class])
    public suspend fun searchFtsRaw(query: SupportSQLiteQuery): List<FtsHit>

    @Update
    public suspend fun update(row: MessageRow)

    @Query("SELECT * FROM messages WHERE rowId = :rowId LIMIT 1")
    public suspend fun byRowId(rowId: Long): MessageRow?

    @Query(
        "SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation AND rowId IN (:rowIds) " +
            "ORDER BY timestampMs ASC, rowId ASC",
    )
    public suspend fun byRowIds(networkId: String, conversation: String, rowIds: List<Long>): List<MessageRow>

    @Query(
        "SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation AND sentByUs = 0 " +
            "AND historyContext = 0 AND playback = 0 AND redacted = 0 AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION') " +
            "ORDER BY timestampMs DESC, rowId DESC LIMIT 1",
    )
    public suspend fun newestUnreadEligible(networkId: String, conversation: String): MessageRow?

    @Query(
        "SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation " +
            "ORDER BY timestampMs DESC, rowId DESC LIMIT :limit OFFSET :keep",
    )
    public suspend fun rowsToTrim(networkId: String, conversation: String, keep: Int, limit: Int): List<MessageRow>

    @Query(
        "SELECT COUNT(*) AS unreadCount, COALESCE(SUM(CASE WHEN highlightsKnown = 1 THEN highlightsMe ELSE 0 END), 0) AS mentionCount, " +
            "CASE WHEN COALESCE(MIN(highlightsKnown), 1) = 1 THEN 1 ELSE 0 END AS mentionCountKnown FROM messages " +
            "WHERE networkId = :networkId AND conversation = :conversation AND sentByUs = 0 " +
            "AND historyContext = 0 AND playback = 0 AND redacted = 0 AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION') " +
            "AND (timestampMs > :timestampMs OR (timestampMs = :timestampMs AND rowId > :rowId))",
    )
    public suspend fun unreadCounts(networkId: String, conversation: String, timestampMs: Long, rowId: Long): UnreadCounts

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertReaction(row: MessageReactionRow): Long

    @Query(
        "UPDATE message_reactions SET observedAtMs = MAX(observedAtMs, :observedAtMs) WHERE networkId = :networkId " +
            "AND conversation = :conversation AND msgid = :msgid AND emoji = :emoji AND sender = :sender",
    )
    public suspend fun refreshReaction(networkId: String, conversation: String, msgid: String, emoji: String, sender: String, observedAtMs: Long)

    @Transaction
    public suspend fun putReaction(row: MessageReactionRow) {
        if (insertReaction(row) == -1L) {
            refreshReaction(row.networkId, row.conversation, row.msgid, row.emoji, row.sender, row.observedAtMs)
        }
    }

    @Query("SELECT * FROM message_reactions WHERE networkId = :networkId AND conversation = :conversation")
    public suspend fun reactions(networkId: String, conversation: String): List<MessageReactionRow>

    @Query(
        "DELETE FROM message_reactions WHERE networkId = :networkId AND conversation = :conversation " +
            "AND msgid = :msgid AND sender = :sender AND emoji = :emoji",
    )
    public suspend fun removeReaction(networkId: String, conversation: String, msgid: String, sender: String, emoji: String)

    @Query("DELETE FROM message_reactions WHERE networkId = :networkId AND conversation = :conversation AND msgid = :msgid")
    public suspend fun removeReactions(networkId: String, conversation: String, msgid: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertTombstone(row: MessageTombstoneRow): Long

    @Query(
        "UPDATE message_tombstones SET observedAtMs = MAX(observedAtMs, :observedAtMs) " +
            "WHERE networkId = :networkId AND conversation = :conversation AND identity = :identity",
    )
    public suspend fun refreshTombstone(networkId: String, conversation: String, identity: String, observedAtMs: Long)

    @Transaction
    public suspend fun putTombstone(row: MessageTombstoneRow) {
        if (insertTombstone(row) == -1L) {
            refreshTombstone(row.networkId, row.conversation, row.identity, row.observedAtMs)
        }
    }

    @Query(
        "SELECT EXISTS(SELECT 1 FROM message_tombstones WHERE networkId = :networkId AND conversation = :conversation " +
            "AND identity = :identity)",
    )
    public suspend fun tombstoned(networkId: String, conversation: String, identity: String): Boolean

    @Query("SELECT * FROM message_tombstones WHERE networkId = :networkId AND conversation = :conversation")
    public suspend fun tombstones(networkId: String, conversation: String): List<MessageTombstoneRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun putRetention(row: MessageRetentionRow)

    @Query("SELECT throughTimestampMs FROM message_retention WHERE networkId = :networkId AND conversation = :conversation")
    public suspend fun retentionFloor(networkId: String, conversation: String): Long?

    @Query("UPDATE messages SET replyParentRowId = :toRowId WHERE replyParentRowId = :fromRowId")
    public suspend fun moveReplyParents(fromRowId: Long, toRowId: Long?)

    @Query(
        "UPDATE messages SET replyParentRowId = :rowId WHERE networkId = :networkId AND conversation = :conversation " +
            "AND replyToMsgid = :msgid AND redacted = 0",
    )
    public suspend fun attachReplies(networkId: String, conversation: String, msgid: String, rowId: Long)

    @RawQuery
    public suspend fun mutateRaw(query: SupportSQLiteQuery): Int

    @Transaction
    public suspend fun recordAtomic(incoming: MessageRow, echoRowId: Long? = null, freshLocal: Boolean = false): Long? {
        require(!freshLocal || incoming.sentByUs && incoming.msgid == null && !incoming.historyContext && !incoming.playback)
        val identified = incoming.msgid?.let { byMsgid(incoming.networkId, incoming.conversation, it) }
        val echo = echoRowId?.let { byRowId(it) }?.takeIf {
            it.networkId == incoming.networkId && it.conversation == incoming.conversation
        }
        val overlap = identified ?: echo ?: if (freshLocal) null else if (incoming.msgid == null) {
            byHash(incoming.networkId, incoming.conversation, incoming.contentHash)
        } else {
            rowIdWithoutMsgidByHash(incoming.networkId, incoming.conversation, incoming.contentHash)?.let { byRowId(it) }
        }
        if (overlap != null) {
            val erased = incoming.msgid?.let { tombstoned(incoming.networkId, incoming.conversation, "msgid:$it") } == true
            val base = if (echo != null && echo.rowId != overlap.rowId) mergeMetadata(overlap, echo) else overlap
            val merged = mergeMetadata(base, incoming.copy(redacted = incoming.redacted || erased), replaceEcho = echo?.rowId == overlap.rowId)
            update(merged)
            if (echo != null && echo.rowId != overlap.rowId) {
                moveReplyParents(echo.rowId, overlap.rowId)
                deleteIndexedRow(echo.rowId)
            }
            bindAndIndex(merged, replaceIndex = echo?.rowId == overlap.rowId)
            return null
        }
        if (!freshLocal && blocked(incoming)) return null
        val parent = incoming.replyParentRowId?.let { byRowId(it) }?.takeIf {
            it.networkId == incoming.networkId && it.conversation == incoming.conversation
        } ?: incoming.replyToMsgid?.let { byMsgid(incoming.networkId, incoming.conversation, it) }
        val candidate = incoming.copy(replyParentRowId = parent?.rowId)
        val row = if (candidate.redacted) mergeMetadata(candidate, candidate) else candidate
        val inserted = insert(row)
        if (inserted == -1L) return null
        bindAndIndex(row.copy(rowId = inserted))
        return inserted
    }

    public suspend fun identityTombstoned(row: MessageRow): Boolean =
        if (row.msgid != null) {
            tombstoned(row.networkId, row.conversation, "msgid:${row.msgid}")
        } else {
            tombstoned(row.networkId, row.conversation, "hash:${row.contentHash}") ||
                tombstoned(row.networkId, row.conversation, "content:${MessageStore.identityHash(row)}")
        }

    public suspend fun blocked(row: MessageRow): Boolean {
        if (identityTombstoned(row)) return true
        val localFloor = retentionFloor(row.networkId, row.conversation)
        val networkFloor = retentionFloor(row.networkId, NETWORK_RETENTION_TARGET)
        val floor = when {
            localFloor == null -> networkFloor ?: return false
            networkFloor == null -> localFloor
            else -> maxOf(localFloor, networkFloor)
        }
        return row.timestampMs < floor || (row.timestampMs == floor && (row.historyContext || row.playback))
    }

    @Query(
        "SELECT r.* FROM message_reactions r JOIN messages m ON m.networkId = r.networkId " +
            "AND m.conversation = r.conversation AND m.msgid = r.msgid WHERE r.networkId = :networkId " +
            "AND r.conversation = :conversation AND m.redacted = 0",
    )
    public suspend fun visibleReactions(networkId: String, conversation: String): List<MessageReactionRow>

    @Query(
        "SELECT r.* FROM message_reactions r JOIN messages m ON m.networkId = r.networkId " +
            "AND m.conversation = r.conversation AND m.msgid = r.msgid WHERE r.networkId = :networkId " +
            "AND r.conversation = :conversation AND r.msgid = :msgid AND m.redacted = 0",
    )
    public suspend fun visibleReactionsForMessage(networkId: String, conversation: String, msgid: String): List<MessageReactionRow>

    @Query(
        "SELECT r.* FROM message_reactions r JOIN messages m ON m.networkId = r.networkId " +
            "AND m.conversation = r.conversation AND m.msgid = r.msgid WHERE r.networkId = :networkId " +
            "AND r.conversation = :conversation AND r.msgid IN (:msgids) AND m.redacted = 0",
    )
    public suspend fun visibleReactionsForMessages(networkId: String, conversation: String, msgids: List<String>): List<MessageReactionRow>

    public fun mergeMetadata(existing: MessageRow, incoming: MessageRow, replaceEcho: Boolean = false): MessageRow {
        val redacted = existing.redacted || incoming.redacted
        return existing.copy(
            msgid = existing.msgid ?: incoming.msgid,
            contentHash = if (replaceEcho) incoming.contentHash else existing.contentHash,
            originalIdentityHash = if (replaceEcho && !existing.redacted) MessageStore.identityHash(incoming) else MessageStore.identityHash(existing),
            timestampMs = if (replaceEcho) incoming.timestampMs else existing.timestampMs,
            text = if (redacted) "" else if (replaceEcho) incoming.text else existing.text,
            senderNick = if (replaceEcho) incoming.senderNick else existing.senderNick,
            kind = if (replaceEcho) incoming.kind else existing.kind,
            senderUser = existing.senderUser ?: incoming.senderUser,
            senderHost = existing.senderHost ?: incoming.senderHost,
            senderAccount = incoming.senderAccount ?: existing.senderAccount,
            sentByUs = existing.sentByUs || incoming.sentByUs,
            highlightsMe = !redacted &&
                ((existing.highlightsKnown && existing.highlightsMe) || (incoming.highlightsKnown && incoming.highlightsMe)),
            highlightsKnown = existing.highlightsKnown || incoming.highlightsKnown,
            historyContext = existing.historyContext && incoming.historyContext,
            playback = existing.playback && incoming.playback,
            channelContext = existing.channelContext ?: incoming.channelContext,
            replyToMsgid = if (redacted) null else existing.replyToMsgid ?: incoming.replyToMsgid,
            replyParentRowId = if (redacted) null else existing.replyParentRowId ?: incoming.replyParentRowId,
            attachmentUrl = if (redacted) null else existing.attachmentUrl ?: incoming.attachmentUrl,
            attachmentName = if (redacted) null else existing.attachmentName ?: incoming.attachmentName,
            attachmentMimeType = if (redacted) null else existing.attachmentMimeType ?: incoming.attachmentMimeType,
            attachmentSizeBytes = if (redacted) null else existing.attachmentSizeBytes ?: incoming.attachmentSizeBytes,
            attachmentWidth = if (redacted) null else existing.attachmentWidth ?: incoming.attachmentWidth,
            attachmentHeight = if (redacted) null else existing.attachmentHeight ?: incoming.attachmentHeight,
            redacted = redacted,
            reactionsTruncated = !redacted && (existing.reactionsTruncated || incoming.reactionsTruncated),
        )
    }

    public suspend fun bindAndIndex(row: MessageRow, replaceIndex: Boolean = false) {
        if (replaceIndex || row.redacted) {
            indexMessageRaw(SimpleSQLiteQuery("DELETE FROM message_fts WHERE rowid = ?", arrayOf<Any>(row.rowId)))
        }
        if (row.redacted) {
            row.msgid?.let { removeReactions(row.networkId, row.conversation, it) }
            return
        }
        row.msgid?.let { attachReplies(row.networkId, row.conversation, it, row.rowId) }
        if (row.replyParentRowId == null) {
            row.replyToMsgid?.let { byMsgid(row.networkId, row.conversation, it) }?.let {
                update(row.copy(replyParentRowId = it.rowId))
            }
        }
        indexMessageRaw(
            SimpleSQLiteQuery(
                "INSERT INTO message_fts(rowid, sender, body) SELECT ?, ?, ? WHERE NOT EXISTS " +
                    "(SELECT 1 FROM message_fts WHERE rowid = ?)",
                arrayOf<Any>(row.rowId, row.senderNick, row.text, row.rowId),
            ),
        )
    }

    public suspend fun deleteIndexedRow(rowId: Long) {
        indexMessageRaw(SimpleSQLiteQuery("DELETE FROM message_fts WHERE rowid = ?", arrayOf<Any>(rowId)))
        deleteRow(rowId)
    }

    @Transaction
    public suspend fun applyReactionAtomic(
        row: MessageReactionRow,
        added: Boolean,
        perParentLimit: Int = MESSAGE_REACTIONS_PER_PARENT_LIMIT,
        networkLimit: Int = MESSAGE_REACTIONS_PER_NETWORK_LIMIT,
    ): Set<Long> {
        require(perParentLimit >= 0 && networkLimit >= 0)
        if (tombstoned(row.networkId, row.conversation, "msgid:${row.msgid}")) return emptySet()
        val parent = byMsgid(row.networkId, row.conversation, row.msgid)
        if (parent?.redacted == true) return emptySet()
        if (added) putReaction(row) else removeReaction(row.networkId, row.conversation, row.msgid, row.sender, row.emoji)
        pruneOrphanReactions(row.observedAtMs)
        if (parent != null) capParentReactions(row.networkId, row.conversation, row.msgid, perParentLimit)
        val changed = linkedSetOf<Long>()
        if (parent != null) changed.add(parent.rowId)
        changed.addAll(networkReactionVictimRows(row.networkId, networkLimit))
        capNetworkReactions(row.networkId, networkLimit)
        return changed
    }

    @Query(
        "UPDATE messages SET reactionsTruncated = 1 WHERE networkId = :networkId AND conversation = :conversation " +
            "AND msgid = :msgid AND redacted = 0 AND EXISTS (SELECT 1 FROM message_reactions WHERE networkId = :networkId " +
            "AND conversation = :conversation AND msgid = :msgid LIMIT 1 OFFSET :keep)",
    )
    public suspend fun markParentReactionsTruncated(networkId: String, conversation: String, msgid: String, keep: Int)

    @Query(
        "DELETE FROM message_reactions WHERE rowid IN (SELECT rowid FROM message_reactions WHERE networkId = :networkId " +
            "AND conversation = :conversation AND msgid = :msgid ORDER BY observedAtMs DESC, emoji ASC, sender ASC LIMIT -1 OFFSET :keep)",
    )
    public suspend fun trimParentReactions(networkId: String, conversation: String, msgid: String, keep: Int)

    @Transaction
    public suspend fun capParentReactions(networkId: String, conversation: String, msgid: String, keep: Int) {
        markParentReactionsTruncated(networkId, conversation, msgid, keep)
        trimParentReactions(networkId, conversation, msgid, keep)
    }

    @Query(
        "UPDATE messages SET reactionsTruncated = 1 WHERE networkId = :networkId AND redacted = 0 AND rowId IN " +
            "(SELECT m.rowId FROM messages m JOIN (SELECT conversation, msgid FROM message_reactions WHERE networkId = :networkId " +
            "ORDER BY observedAtMs DESC, conversation ASC, msgid ASC, emoji ASC, sender ASC LIMIT -1 OFFSET :keep) overflow " +
            "ON m.networkId = :networkId AND m.conversation = overflow.conversation AND m.msgid = overflow.msgid)",
    )
    public suspend fun markNetworkReactionsTruncated(networkId: String, keep: Int)

    @Query(
        "SELECT DISTINCT m.rowId FROM messages m JOIN (SELECT conversation, msgid FROM message_reactions WHERE networkId = :networkId " +
            "ORDER BY observedAtMs DESC, conversation ASC, msgid ASC, emoji ASC, sender ASC LIMIT -1 OFFSET :keep) overflow " +
            "ON m.networkId = :networkId AND m.conversation = overflow.conversation AND m.msgid = overflow.msgid WHERE m.redacted = 0",
    )
    public suspend fun networkReactionVictimRows(networkId: String, keep: Int): List<Long>

    @Query(
        "DELETE FROM message_reactions WHERE rowid IN (SELECT rowid FROM message_reactions WHERE networkId = :networkId " +
            "ORDER BY observedAtMs DESC, conversation ASC, msgid ASC, emoji ASC, sender ASC LIMIT -1 OFFSET :keep)",
    )
    public suspend fun trimNetworkReactions(networkId: String, keep: Int)

    @Transaction
    public suspend fun capNetworkReactions(networkId: String, keep: Int) {
        markNetworkReactionsTruncated(networkId, keep)
        trimNetworkReactions(networkId, keep)
    }

    @Query(
        "SELECT r.conversation, r.msgid FROM message_reactions r JOIN messages m ON m.networkId = r.networkId " +
            "AND m.conversation = r.conversation AND m.msgid = r.msgid WHERE r.networkId = :networkId AND m.redacted = 0 " +
            "GROUP BY r.conversation, r.msgid HAVING COUNT(*) > :keep ORDER BY r.conversation ASC, r.msgid ASC",
    )
    public suspend fun overflowReactionParents(networkId: String, keep: Int): List<MessageReactionParent>

    @Query("SELECT DISTINCT networkId FROM message_reactions ORDER BY networkId")
    public suspend fun reactionNetworks(): List<String>

    @Transaction
    public suspend fun pruneReactionMemberships(networkId: String, perParentLimit: Int, networkLimit: Int) {
        require(perParentLimit >= 0 && networkLimit >= 0)
        capNetworkReactions(networkId, networkLimit)
        for (parent in overflowReactionParents(networkId, perParentLimit)) {
            capParentReactions(networkId, parent.conversation, parent.msgid, perParentLimit)
        }
    }

    @Query(
        "DELETE FROM message_reactions WHERE NOT EXISTS (SELECT 1 FROM messages m WHERE m.networkId = message_reactions.networkId " +
            "AND m.conversation = message_reactions.conversation AND m.msgid = message_reactions.msgid) " +
            "AND observedAtMs < :beforeMs",
    )
    public suspend fun deleteOldOrphans(beforeMs: Long)

    @Query(
        "DELETE FROM message_reactions WHERE rowid IN (SELECT r.rowid FROM message_reactions r WHERE NOT EXISTS " +
            "(SELECT 1 FROM messages m WHERE m.networkId = r.networkId AND m.conversation = r.conversation AND m.msgid = r.msgid) " +
            "ORDER BY r.observedAtMs DESC, r.rowid DESC LIMIT -1 OFFSET :keep)",
    )
    public suspend fun capOrphans(keep: Int)

    @Transaction
    public suspend fun pruneOrphanReactions(nowMs: Long) {
        deleteOldOrphans(nowMs - MESSAGE_INTERACTION_RETENTION_MS)
        capOrphans(MESSAGE_ORPHAN_REACTION_LIMIT)
    }

    @Query("DELETE FROM message_tombstones WHERE observedAtMs < :beforeMs")
    public suspend fun deleteOldTombstones(beforeMs: Long)

    @Query("SELECT DISTINCT networkId, conversation FROM message_tombstones")
    public suspend fun tombstoneConversations(): List<MessageInteractionConversation>

    @Query("SELECT MAX(timestampMs) FROM message_tombstones WHERE networkId = :networkId AND conversation = :conversation")
    public suspend fun latestTombstoneTimestamp(networkId: String, conversation: String): Long?

    @Query(
        "DELETE FROM message_tombstones WHERE rowid IN (SELECT rowid FROM message_tombstones " +
            "WHERE networkId = :networkId AND conversation = :conversation " +
            "ORDER BY observedAtMs DESC, rowid DESC LIMIT -1 OFFSET :keep)",
    )
    public suspend fun capTombstones(networkId: String, conversation: String, keep: Int)

    @Transaction
    public suspend fun pruneTombstones(nowMs: Long) {
        deleteOldTombstones(nowMs - MESSAGE_INTERACTION_RETENTION_MS)
        for (key in tombstoneConversations()) capTombstones(key.networkId, key.conversation, MESSAGE_TOMBSTONE_LIMIT)
    }

    @Transaction
    public suspend fun pruneTombstonesIn(networkId: String, conversation: String, nowMs: Long) {
        deleteOldTombstones(nowMs - MESSAGE_INTERACTION_RETENTION_MS)
        capTombstones(networkId, conversation, MESSAGE_TOMBSTONE_LIMIT)
    }

    @Transaction
    public suspend fun redactAtomic(networkId: String, conversation: String, msgid: String, nowMs: Long) {
        val row = byMsgid(networkId, conversation, msgid)
        putTombstone(MessageTombstoneRow(networkId, conversation, "msgid:$msgid", row?.timestampMs ?: nowMs, nowMs))
        removeReactions(networkId, conversation, msgid)
        if (row != null) {
            putTombstone(MessageTombstoneRow(networkId, conversation, "hash:${row.contentHash}", row.timestampMs, nowMs))
            putTombstone(MessageTombstoneRow(networkId, conversation, "content:${MessageStore.identityHash(row)}", row.timestampMs, nowMs))
            val redacted = mergeMetadata(row, row.copy(redacted = true))
            update(redacted)
            bindAndIndex(redacted)
        }
        pruneTombstonesIn(networkId, conversation, nowMs)
    }

    @Transaction
    public suspend fun trimAtomic(networkId: String, conversation: String, keep: Int, nowMs: Long) {
        require(keep >= 0)
        deleteOldTombstones(nowMs - MESSAGE_INTERACTION_RETENTION_MS)
        while (true) {
            val deleting = rowsToTrim(networkId, conversation, keep, 500)
            if (deleting.isEmpty()) break
            deleteRetainedRows(networkId, conversation, deleting, nowMs)
        }
        if (keep == 0) mutateRaw(
            SimpleSQLiteQuery("DELETE FROM message_reactions WHERE networkId = ? AND conversation = ?", arrayOf<Any>(networkId, conversation)),
        )
    }

    public suspend fun deleteRetainedRows(networkId: String, conversation: String, deleting: List<MessageRow>, nowMs: Long) {
        val floor = deleting.maxOfOrNull { it.timestampMs }
        if (floor != null) putRetention(MessageRetentionRow(networkId, conversation, maxOf(floor, retentionFloor(networkId, conversation) ?: Long.MIN_VALUE)))
        for (row in deleting) {
            putTombstone(MessageTombstoneRow(networkId, conversation, "hash:${row.contentHash}", row.timestampMs, nowMs))
            putTombstone(MessageTombstoneRow(networkId, conversation, "content:${MessageStore.identityHash(row)}", row.timestampMs, nowMs))
            row.msgid?.let {
                putTombstone(MessageTombstoneRow(networkId, conversation, "msgid:$it", row.timestampMs, nowMs))
                removeReactions(networkId, conversation, it)
            }
            moveReplyParents(row.rowId, null)
            deleteIndexedRow(row.rowId)
        }
        capTombstones(networkId, conversation, MESSAGE_TOMBSTONE_LIMIT)
    }

    @Query(
        "SELECT m.conversation, m.timestampMs, m.rowId FROM messages m WHERE m.networkId = :networkId AND NOT EXISTS " +
            "(SELECT 1 FROM messages newer WHERE newer.networkId = m.networkId AND newer.conversation = m.conversation " +
            "AND (newer.timestampMs > m.timestampMs OR (newer.timestampMs = m.timestampMs AND newer.rowId > m.rowId))) " +
            "ORDER BY m.timestampMs DESC, m.rowId DESC, m.conversation ASC LIMIT :limit",
    )
    public suspend fun newestConversations(networkId: String, limit: Int): List<MessageActivityRow>

    @Query("SELECT COUNT(*) FROM messages WHERE networkId = :networkId AND conversation = :conversation")
    public suspend fun messageCountIn(networkId: String, conversation: String): Int

    @Query(
        "SELECT conversation, timestampMs, rowId FROM messages WHERE networkId = :networkId AND conversation = :conversation " +
            "ORDER BY timestampMs DESC, rowId DESC LIMIT 1",
    )
    public suspend fun latestActivity(networkId: String, conversation: String): MessageActivityRow?

    @Query(
        "SELECT conversation, timestampMs, rowId FROM messages WHERE networkId = :networkId AND conversation NOT IN (:protectedConversations) " +
            "ORDER BY timestampMs DESC, rowId DESC LIMIT 1 OFFSET :offset",
    )
    public suspend fun networkBudgetCutoff(networkId: String, protectedConversations: List<String>, offset: Int): MessageActivityRow?

    @Query(
        "SELECT conversation FROM (SELECT conversation, observedAtMs FROM message_tombstones WHERE networkId = :networkId " +
            "UNION ALL SELECT conversation, observedAtMs FROM message_reactions WHERE networkId = :networkId) " +
            "GROUP BY conversation ORDER BY MAX(observedAtMs) DESC, conversation ASC LIMIT :limit",
    )
    public suspend fun pendingInteractionTargets(networkId: String, limit: Int): List<String>

    @Query(
        "SELECT conversation FROM message_tombstones WHERE networkId = :networkId " +
            "UNION SELECT conversation FROM message_reactions WHERE networkId = :networkId " +
            "UNION SELECT conversation FROM message_retention WHERE networkId = :networkId AND conversation != :sentinel",
    )
    public suspend fun interactionTargets(networkId: String, sentinel: String): List<String>

    public suspend fun advanceNetworkFloor(networkId: String, timestampMs: Long) {
        putRetention(MessageRetentionRow(networkId, NETWORK_RETENTION_TARGET, maxOf(timestampMs, retentionFloor(networkId, NETWORK_RETENTION_TARGET) ?: Long.MIN_VALUE)))
    }

    public suspend fun deleteTargetState(networkId: String, conversation: String) {
        val oldFloor = retentionFloor(networkId, conversation)
        val tombstoneTimestamp = latestTombstoneTimestamp(networkId, conversation)
        if (oldFloor != null || tombstoneTimestamp != null) {
            advanceNetworkFloor(networkId, maxOf(oldFloor ?: Long.MIN_VALUE, tombstoneTimestamp ?: Long.MIN_VALUE))
        }
        for (table in listOf("message_reactions", "message_tombstones", "message_retention")) {
            mutateRaw(SimpleSQLiteQuery("DELETE FROM $table WHERE networkId = ? AND conversation = ?", arrayOf<Any>(networkId, conversation)))
        }
    }

    public suspend fun dropWholeConversation(networkId: String, conversation: String) {
        latestTimestamp(networkId, conversation)?.let { advanceNetworkFloor(networkId, it) }
        mutateRaw(
            SimpleSQLiteQuery(
                "UPDATE messages SET replyParentRowId = NULL WHERE replyParentRowId IN " +
                    "(SELECT rowId FROM messages WHERE networkId = ? AND conversation = ?)",
                arrayOf<Any>(networkId, conversation),
            ),
        )
        mutateRaw(
            SimpleSQLiteQuery(
                "DELETE FROM message_fts WHERE rowid IN (SELECT rowId FROM messages WHERE networkId = ? AND conversation = ?)",
                arrayOf<Any>(networkId, conversation),
            ),
        )
        mutateRaw(SimpleSQLiteQuery("DELETE FROM messages WHERE networkId = ? AND conversation = ?", arrayOf<Any>(networkId, conversation)))
        deleteTargetState(networkId, conversation)
    }

    @Transaction
    public suspend fun maintainNetworkAtomic(
        networkId: String,
        keepMessages: Int,
        keepConversations: Int,
        protectedConversations: List<String>,
        nowMs: Long,
    ) {
        require(keepMessages >= 0 && keepConversations >= 0)
        val retained = linkedSetOf<String>()
        if (keepMessages > 0) {
            for (target in protectedConversations) {
                if (retained.size == keepConversations) break
                retained.add(target)
            }
            for (row in newestConversations(networkId, keepConversations)) {
                if (retained.size == keepConversations) break
                retained.add(row.conversation)
            }
        }
        for (target in conversations(networkId)) {
            if (target !in retained) dropWholeConversation(networkId, target)
        }
        var remaining = keepMessages
        val protected = mutableListOf<String>()
        for (target in protectedConversations) {
            if (target !in retained) continue
            protected.add(target)
            if (remaining == 0) dropWholeConversation(networkId, target)
            else trimAtomic(networkId, target, minOf(remaining, MESSAGE_PER_CONVERSATION_LIMIT), nowMs)
            remaining -= messageCountIn(networkId, target)
        }
        for (target in retained) {
            if (target !in protected && remaining > 0) trimAtomic(networkId, target, minOf(remaining, MESSAGE_PER_CONVERSATION_LIMIT), nowMs)
        }
        val cutoff = if (remaining > 0) networkBudgetCutoff(networkId, protected, remaining - 1) else null
        val iterator = retained.iterator()
        while (iterator.hasNext()) {
            val target = iterator.next()
            if (target in protected) continue
            val latest = latestActivity(networkId, target)
            val completelyDropped = remaining == 0 || (cutoff != null && latest != null &&
                (latest.timestampMs < cutoff.timestampMs || (latest.timestampMs == cutoff.timestampMs && latest.rowId < cutoff.rowId)))
            if (completelyDropped) {
                dropWholeConversation(networkId, target)
                iterator.remove()
            } else if (cutoff != null) {
                deleteRetainedRows(networkId, target, before(networkId, target, cutoff.timestampMs, cutoff.rowId, MESSAGE_PER_CONVERSATION_LIMIT), nowMs)
            }
        }
        pruneOrphanReactions(nowMs)
        deleteOldTombstones(nowMs - MESSAGE_INTERACTION_RETENTION_MS)
        pruneReactionMemberships(networkId, MESSAGE_REACTIONS_PER_PARENT_LIMIT, MESSAGE_REACTIONS_PER_NETWORK_LIMIT)
        if (keepMessages > 0) {
            for (target in pendingInteractionTargets(networkId, keepConversations)) {
                if (retained.size == keepConversations) break
                retained.add(target)
            }
        }
        for (target in interactionTargets(networkId, NETWORK_RETENTION_TARGET)) {
            if (target !in retained) {
                deleteTargetState(networkId, target)
            } else {
                capTombstones(networkId, target, MESSAGE_TOMBSTONE_LIMIT)
            }
        }
        mutateRaw(SimpleSQLiteQuery("DELETE FROM message_fts WHERE rowid NOT IN (SELECT rowId FROM messages)"))
    }

    @Transaction
    public suspend fun renameAtomic(networkId: String, from: String, to: String): ConversationMerge {
        val moving = allIn(networkId, from)
        val merged = mutableListOf<RowMerge>()
        for (original in moving) {
            val current = byRowId(original.rowId) ?: continue
            val row = current.copy(
                conversation = to,
                contentHash = if (current.redacted) current.contentHash else MessageStore.renamedHash(current, to),
            )
            val duplicate = row.msgid?.let { byMsgid(networkId, to, it) }
                ?: if (row.msgid == null) byHash(networkId, to, row.contentHash)
                else rowIdWithoutMsgidByHash(networkId, to, row.contentHash)?.let { byRowId(it) }
            if (duplicate != null) {
                val survivor = mergeMetadata(duplicate, row)
                update(survivor)
                moveReplyParents(row.rowId, duplicate.rowId)
                deleteIndexedRow(row.rowId)
                bindAndIndex(survivor)
                merged.add(RowMerge(row.rowId, duplicate.rowId))
            } else {
                update(row.copy(conversation = to))
            }
        }
        for (reaction in reactions(networkId, from)) putReaction(reaction.copy(conversation = to))
        for (tombstone in tombstones(networkId, from)) putTombstone(tombstone.copy(conversation = to))
        retentionFloor(networkId, from)?.let { putRetention(MessageRetentionRow(networkId, to, maxOf(it, retentionFloor(networkId, to) ?: Long.MIN_VALUE))) }
        for (table in listOf("message_reactions", "message_tombstones", "message_retention")) {
            mutateRaw(SimpleSQLiteQuery("DELETE FROM $table WHERE networkId = ? AND conversation = ?", arrayOf<Any>(networkId, from)))
        }
        for (row in allIn(networkId, to)) {
            if (identityTombstoned(row)) {
                row.msgid?.let { redactAtomic(networkId, to, it, System.currentTimeMillis()) }
                    ?: run {
                        val redacted = mergeMetadata(row, row.copy(redacted = true))
                        update(redacted)
                        bindAndIndex(redacted)
                    }
            } else {
                bindAndIndex(row)
            }
        }
        capTombstones(networkId, to, MESSAGE_TOMBSTONE_LIMIT)
        capOrphans(MESSAGE_ORPHAN_REACTION_LIMIT)
        pruneReactionMemberships(networkId, MESSAGE_REACTIONS_PER_PARENT_LIMIT, MESSAGE_REACTIONS_PER_NETWORK_LIMIT)
        return ConversationMerge(moving.size, merged)
    }

    @Transaction
    public suspend fun deleteNetworkAtomic(networkId: String) {
        mutateRaw(SimpleSQLiteQuery("DELETE FROM message_fts WHERE rowid IN (SELECT rowId FROM messages WHERE networkId = ?)", arrayOf<Any>(networkId)))
        deleteNetwork(networkId)
        for (table in listOf("message_reactions", "message_tombstones", "message_retention")) {
            mutateRaw(SimpleSQLiteQuery("DELETE FROM $table WHERE networkId = ?", arrayOf<Any>(networkId)))
        }
    }

    @Transaction
    public suspend fun reindexAtomic() {
        mutateRaw(SimpleSQLiteQuery("DELETE FROM message_fts"))
        for (row in allRows()) if (!row.redacted) bindAndIndex(row)
    }
}

public data class FtsHit(
    public val rowId: Long,
    public val networkId: String,
    public val conversation: String,
    public val sender: String,
    public val timestampMs: Long,
    public val snippet: String,
)

public data class RowMerge(public val removedRowId: Long, public val survivingRowId: Long)
public data class ConversationMerge(public val movedCount: Int, public val rows: List<RowMerge>)
