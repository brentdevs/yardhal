package dev.brentdevs.yardhal.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["msgid"], unique = true),
        Index(value = ["networkId", "conversation", "timestampMs"]),
        Index(value = ["contentHash"]),
    ],
)
public data class MessageRow(
    @PrimaryKey(autoGenerate = true) public val rowId: Long = 0,
    @ColumnInfo(name = "networkId") public val networkId: String,
    @ColumnInfo(name = "conversation") public val conversation: String,
    @ColumnInfo(name = "msgid") public val msgid: String?,
    @ColumnInfo(name = "contentHash") public val contentHash: String,
    @ColumnInfo(name = "senderNick") public val senderNick: String,
    @ColumnInfo(name = "senderUser") public val senderUser: String?,
    @ColumnInfo(name = "senderHost") public val senderHost: String?,
    @ColumnInfo(name = "kind") public val kind: String,
    @ColumnInfo(name = "text") public val text: String,
    @ColumnInfo(name = "sentByUs") public val sentByUs: Boolean,
    @ColumnInfo(name = "timestampMs") public val timestampMs: Long,
)
