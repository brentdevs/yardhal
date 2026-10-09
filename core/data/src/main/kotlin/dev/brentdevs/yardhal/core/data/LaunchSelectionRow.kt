package dev.brentdevs.yardhal.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "launch_selection")
public data class LaunchSelectionRow(
    @PrimaryKey public val slot: Int = 0,
    public val networkId: String,
    public val conversation: String,
    public val rawTarget: String,
    public val kind: String,
    public val observedAtMs: Long,
)
