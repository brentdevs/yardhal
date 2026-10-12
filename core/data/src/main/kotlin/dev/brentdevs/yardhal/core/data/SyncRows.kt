package dev.brentdevs.yardhal.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sync_entities", primaryKeys = ["accountId", "kind", "entityKey"])
public data class SyncEntityRow(
    public val accountId: String,
    public val kind: String,
    public val entityKey: String,
    public val valueJson: String?,
    public val remoteSeq: Long,
)

@Entity(tableName = "sync_outbox", indices = [Index(value = ["accountId", "kind", "entityKey"]), Index(value = ["accountId", "ordinal"])])
public data class SyncOutboxRow(
    @PrimaryKey public val id: String,
    public val accountId: String,
    public val kind: String,
    public val entityKey: String,
    public val valueJson: String?,
    public val baseSeq: Long,
    public val ordinal: Long,
    public val failure: String? = null,
)

@Entity(tableName = "sync_inbox", primaryKeys = ["accountId", "kind", "entityKey"])
public data class SyncInboxRow(
    public val accountId: String,
    public val kind: String,
    public val entityKey: String,
    public val valueJson: String?,
    public val remoteSeq: Long,
    public val preimagesJson: String,
)

@Entity(tableName = "sync_baselines", primaryKeys = ["accountId", "kind", "entityKey"])
public data class SyncBaselineRow(
    public val accountId: String,
    public val kind: String,
    public val entityKey: String,
    public val valueJson: String?,
)

@Entity(tableName = "sync_state")
public data class SyncStateRow(
    @PrimaryKey public val accountId: String,
    public val epoch: String? = null,
    public val cursor: Long = 0,
    public val refreshRequired: Boolean = false,
)
