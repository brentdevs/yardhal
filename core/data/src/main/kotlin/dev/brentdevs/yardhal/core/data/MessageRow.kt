package dev.brentdevs.yardhal.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["networkId", "conversation", "msgid"], unique = true),
        Index(value = ["networkId", "conversation", "timestampMs"]),
        Index(value = ["contentHash"]),
        Index(value = ["replyParentRowId"]),
        Index(value = ["networkId", "conversation", "replyToMsgid"]),
        Index(value = ["networkId", "conversation", "pendingEcho", "timestampMs"]),
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
    @ColumnInfo(name = "channelContext") public val channelContext: String? = null,
    @ColumnInfo(defaultValue = "0") public val historyContext: Boolean = false,
    @ColumnInfo(defaultValue = "0") public val highlightsMe: Boolean = false,
    @ColumnInfo(defaultValue = "0") public val playback: Boolean = false,
    public val replyToMsgid: String? = null,
    public val replyParentRowId: Long? = null,
    public val attachmentUrl: String? = null,
    public val attachmentName: String? = null,
    public val attachmentMimeType: String? = null,
    public val attachmentSizeBytes: Long? = null,
    public val attachmentWidth: Int? = null,
    public val attachmentHeight: Int? = null,
    public val senderAccount: String? = null,
    @ColumnInfo(defaultValue = "0") public val redacted: Boolean = false,
    @ColumnInfo(defaultValue = "1") public val highlightsKnown: Boolean = true,
    public val originalIdentityHash: String? = null,
    @ColumnInfo(defaultValue = "0") public val reactionsTruncated: Boolean = false,
    @ColumnInfo(defaultValue = "0") public val pendingEcho: Boolean = false,
)
