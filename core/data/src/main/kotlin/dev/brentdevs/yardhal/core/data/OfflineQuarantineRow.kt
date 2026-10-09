package dev.brentdevs.yardhal.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "offline_quarantine",
    indices = [Index(value = ["networkId", "noticedAtMs"])],
)
public data class OfflineQuarantineRow(
    @PrimaryKey public val evidenceId: String,
    public val networkId: String,
    public val kind: String,
    public val identity: String,
    public val payloadJson: String,
    public val noticedAtMs: Long,
)

public data class OfflineEvidenceNoticeRow(
    public val networkId: String,
    public val kind: String,
    public val identity: String,
    public val evidenceId: String,
)
