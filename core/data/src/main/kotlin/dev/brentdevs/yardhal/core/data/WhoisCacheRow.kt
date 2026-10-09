package dev.brentdevs.yardhal.core.data

import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "whois_cache",
    primaryKeys = ["networkId", "nick"],
    indices = [Index(value = ["networkId", "observedAtMs"])],
)
public data class WhoisCacheRow(
    public val networkId: String,
    public val nick: String,
    public val rawNick: String,
    public val caseMapping: String,
    public val observedAtMs: Long,
    public val whoisJson: String?,
    public val whoisObservedAtMs: Long?,
    public val userJson: String?,
    public val userObservedAtMs: Long?,
)
