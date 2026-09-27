package dev.brentdevs.yardhal.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
public interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insert(row: MessageRow): Long

    @Query(
        "SELECT EXISTS(SELECT 1 FROM messages WHERE contentHash = :hash AND conversation = :conversation AND networkId = :networkId)",
    )
    public suspend fun existsByHash(networkId: String, conversation: String, hash: String): Boolean

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
        "SELECT * FROM messages WHERE networkId = :networkId AND conversation = :conversation AND timestampMs < :beforeMs " +
            "ORDER BY timestampMs DESC, rowId DESC LIMIT :limit",
    )
    public suspend fun before(networkId: String, conversation: String, beforeMs: Long, limit: Int): List<MessageRow>

    @Query("SELECT DISTINCT conversation FROM messages WHERE networkId = :networkId")
    public suspend fun conversations(networkId: String): List<String>

    @Query("SELECT MAX(timestampMs) FROM messages WHERE networkId = :networkId AND conversation = :conversation")
    public suspend fun latestTimestamp(networkId: String, conversation: String): Long?

    @Query(
        "DELETE FROM messages WHERE rowId IN (" +
            "SELECT rowId FROM messages WHERE networkId = :networkId AND conversation = :conversation " +
            "ORDER BY timestampMs DESC, rowId DESC LIMIT -1 OFFSET :keep)",
    )
    public suspend fun trim(networkId: String, conversation: String, keep: Int)

    @Query("DELETE FROM messages WHERE networkId = :networkId")
    public suspend fun deleteNetwork(networkId: String)
}
