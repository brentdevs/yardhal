package dev.brentdevs.yardhal.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
public interface SyncDao {
    @Query("SELECT * FROM sync_entities WHERE accountId = :accountId AND kind = :kind AND entityKey = :key")
    public suspend fun entity(accountId: String, kind: String, key: String): SyncEntityRow?

    @Query("SELECT * FROM sync_entities WHERE accountId = :accountId AND kind = :kind")
    public suspend fun entities(accountId: String, kind: String): List<SyncEntityRow>

    @Query("DELETE FROM sync_entities WHERE accountId = :accountId AND kind = :kind AND entityKey = :key")
    public suspend fun deleteEntity(accountId: String, kind: String, key: String)
    @Query(
        "DELETE FROM sync_entities WHERE accountId = :accountId AND NOT EXISTS " +
            "(SELECT 1 FROM sync_outbox o WHERE o.accountId = sync_entities.accountId AND o.kind = sync_entities.kind " +
            "AND o.entityKey = sync_entities.entityKey AND o.failure IS NULL)",
    )
    public suspend fun deleteAcceptedEntities(accountId: String)



    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun putEntity(row: SyncEntityRow)

    @Query("SELECT * FROM sync_outbox WHERE accountId = :accountId AND kind = :kind AND entityKey = :key AND failure IS NULL ORDER BY ordinal")
    public suspend fun outboxFor(accountId: String, kind: String, key: String): List<SyncOutboxRow>
    @Query("UPDATE sync_outbox SET baseSeq = :seq WHERE accountId = :accountId AND kind = :kind AND entityKey = :key AND failure IS NULL")
    public suspend fun rebasePending(accountId: String, kind: String, key: String, seq: Long)


    @Query("SELECT * FROM sync_outbox WHERE accountId = :accountId AND failure IS NULL ORDER BY ordinal LIMIT :limit")
    public suspend fun pending(accountId: String, limit: Int): List<SyncOutboxRow>
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE accountId = :accountId AND failure IS NULL")
    public suspend fun pendingCount(accountId: String): Int


    @Query("SELECT * FROM sync_outbox WHERE id = :id")
    public suspend fun outboxById(id: String): SyncOutboxRow?

    @Query("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM sync_outbox")
    public suspend fun nextOrdinal(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun putOutbox(row: SyncOutboxRow)

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    public suspend fun deleteOutbox(id: String)

    @Query("UPDATE sync_outbox SET failure = :reason WHERE id = :id")
    public suspend fun rejectOutbox(id: String, reason: String)

    @Query("SELECT * FROM sync_outbox WHERE accountId = :accountId AND failure IS NOT NULL ORDER BY ordinal")
    public suspend fun failures(accountId: String): List<SyncOutboxRow>
    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE accountId = :accountId AND kind = :kind AND entityKey = :key AND failure IS NOT NULL)")
    public suspend fun hasFailure(accountId: String, kind: String, key: String): Boolean


    @Query("SELECT * FROM sync_inbox WHERE accountId = :accountId AND kind = :kind AND entityKey = :key")
    public suspend fun inboxFor(accountId: String, kind: String, key: String): SyncInboxRow?

    @Query("SELECT * FROM sync_inbox WHERE accountId = :accountId ORDER BY remoteSeq, kind, entityKey")
    public suspend fun inbox(accountId: String): List<SyncInboxRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun putInbox(row: SyncInboxRow)

    @Query("DELETE FROM sync_inbox WHERE accountId = :accountId AND kind = :kind AND entityKey = :key AND remoteSeq = :seq")
    public suspend fun deleteInbox(accountId: String, kind: String, key: String, seq: Long)
    @Query("DELETE FROM sync_inbox WHERE accountId = :accountId")
    public suspend fun clearInbox(accountId: String)

    @Query("SELECT * FROM sync_baselines WHERE accountId = :accountId AND kind = :kind AND entityKey = :key")
    public suspend fun baselineFor(accountId: String, kind: String, key: String): SyncBaselineRow?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun putBaseline(row: SyncBaselineRow)

    @Query("DELETE FROM sync_baselines WHERE accountId = :accountId")
    public suspend fun clearBaselines(accountId: String)


    @Query("SELECT * FROM sync_state WHERE accountId = :accountId")
    public suspend fun state(accountId: String): SyncStateRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun putState(state: SyncStateRow)

    @Query("UPDATE sync_entities SET remoteSeq = 0 WHERE accountId = :accountId")
    public suspend fun resetEntitySequences(accountId: String)

    @Query("UPDATE sync_outbox SET baseSeq = 0 WHERE accountId = :accountId")
    public suspend fun resetOutboxBases(accountId: String)

    @Query("SELECT accountId FROM sync_state UNION SELECT accountId FROM sync_entities UNION SELECT accountId FROM sync_outbox UNION SELECT accountId FROM sync_inbox UNION SELECT accountId FROM sync_baselines")
    public suspend fun accountIds(): List<String>

    @Query("DELETE FROM sync_entities WHERE accountId = :accountId")
    public suspend fun clearEntities(accountId: String)

    @Query("DELETE FROM sync_outbox WHERE accountId = :accountId")
    public suspend fun clearOutbox(accountId: String)

    @Query("DELETE FROM sync_state WHERE accountId = :accountId")
    public suspend fun clearState(accountId: String)

}
