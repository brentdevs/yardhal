package dev.brentdevs.yardhal.core.data

import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "offline_conversations",
    primaryKeys = ["networkId", "conversation"],
    indices = [Index(value = ["networkId", "observedAtMs"])],
)
public data class OfflineConversationRow(
    public val networkId: String,
    public val conversation: String,
    public val rawTarget: String,
    public val kind: String,
    public val displayName: String,
    public val caseMapping: String,
    public val observedAtMs: Long,
    public val topic: String?,
    public val topicObservedAtMs: Long?,
    public val modesJson: String,
    public val modesObservedAtMs: Long?,
    public val rosterJson: String?,
    public val rosterObservedAtMs: Long?,
)

public data class OfflineShellRow(
    public val networkId: String,
    public val conversation: String,
    public val rawTarget: String,
    public val kind: String,
    public val displayName: String,
    public val caseMapping: String,
    public val observedAtMs: Long,
    public val topic: String?,
    public val topicObservedAtMs: Long?,
    public val modesJson: String,
    public val modesObservedAtMs: Long?,
)

public data class OfflineRosterRow(
    public val conversation: String,
    public val rosterJson: String,
)
