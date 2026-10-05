package dev.brentdevs.yardhal.core.data

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
public interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insert(row: MessageRow): Long

    @Query(
        "SELECT rowId FROM messages WHERE contentHash = :hash AND conversation = :conversation AND networkId = :networkId " +
            "ORDER BY rowId LIMIT 1",
    )
    public suspend fun rowIdByHash(networkId: String, conversation: String, hash: String): Long?

    @Query(
        "SELECT rowId FROM messages WHERE msgid = :msgid AND networkId = :networkId AND conversation = :conversation LIMIT 1",
    )
    public suspend fun rowIdByMsgid(networkId: String, conversation: String, msgid: String): Long?

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

    @Query("UPDATE OR IGNORE messages SET msgid = :msgid WHERE rowId = :rowId AND msgid IS NULL")
    public suspend fun healMsgid(rowId: Long, msgid: String): Int

    @Query("UPDATE messages SET historyContext = 0 WHERE rowId = :rowId AND historyContext = 1")
    public suspend fun promoteHistoryContext(rowId: Long): Int

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

    @Query("SELECT DISTINCT conversation FROM messages WHERE networkId = :networkId")
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

    @Query(
        "DELETE FROM messages WHERE rowId IN (" +
            "SELECT rowId FROM messages WHERE networkId = :networkId AND conversation = :conversation " +
            "ORDER BY timestampMs DESC, rowId DESC LIMIT -1 OFFSET :keep)",
    )
    public suspend fun trim(networkId: String, conversation: String, keep: Int)

    @Query("DELETE FROM messages WHERE networkId = :networkId")
    public suspend fun deleteNetwork(networkId: String)

    @Query("SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation ORDER BY rowId")
    public suspend fun allIn(networkId: String, conversation: String): List<MessageRow>

    @Query("UPDATE messages SET conversation = :conversation, contentHash = :hash WHERE rowId = :rowId")
    public suspend fun renameRow(rowId: Long, conversation: String, hash: String)

    @Query("DELETE FROM messages WHERE rowId = :rowId")
    public suspend fun deleteRow(rowId: Long)

    @Query("SELECT * FROM messages")
    public suspend fun allRows(): List<MessageRow>

    @RawQuery(observedEntities = [MessageRow::class])
    public suspend fun indexMessageRaw(query: SupportSQLiteQuery): Int

    @RawQuery(observedEntities = [MessageRow::class])
    public suspend fun deleteFtsForNetworkRaw(query: SupportSQLiteQuery): Int

    @RawQuery(observedEntities = [MessageRow::class])
    public suspend fun searchFtsRaw(query: SupportSQLiteQuery): List<FtsHit>
}

public data class FtsHit(
    public val rowId: Long,
    public val networkId: String,
    public val conversation: String,
    public val sender: String,
    public val timestampMs: Long,
    public val snippet: String,
)
