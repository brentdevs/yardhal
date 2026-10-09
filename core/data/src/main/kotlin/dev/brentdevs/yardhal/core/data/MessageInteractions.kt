package dev.brentdevs.yardhal.core.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "message_reactions",
    primaryKeys = ["networkId", "conversation", "msgid", "emoji", "sender"],
    indices = [Index(value = ["networkId", "conversation", "observedAtMs"]), Index(value = ["networkId", "observedAtMs"])],
)
public data class MessageReactionRow(
    public val networkId: String,
    public val conversation: String,
    public val msgid: String,
    public val emoji: String,
    public val sender: String,
    public val observedAtMs: Long,
)

@Entity(
    tableName = "message_tombstones",
    primaryKeys = ["networkId", "conversation", "identity"],
    indices = [Index(value = ["networkId", "conversation", "observedAtMs"]), Index(value = ["observedAtMs"])],
)
public data class MessageTombstoneRow(
    public val networkId: String,
    public val conversation: String,
    public val identity: String,
    public val timestampMs: Long,
    public val observedAtMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "message_retention", primaryKeys = ["networkId", "conversation"])
public data class MessageRetentionRow(
    public val networkId: String,
    public val conversation: String,
    public val throughTimestampMs: Long,
)

public data class UnreadCounts(
    public val unreadCount: Int = 0,
    public val mentionCount: Int = 0,
    public val mentionCountKnown: Boolean = true,
)

public const val MESSAGE_INTERACTION_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
public const val MESSAGE_TOMBSTONE_LIMIT: Int = 10_000
public const val MESSAGE_ORPHAN_REACTION_LIMIT: Int = 1_000
public const val MESSAGE_NETWORK_LIMIT: Int = 250_000
public const val MESSAGE_CONVERSATION_LIMIT: Int = 1_000
public const val MESSAGE_PER_CONVERSATION_LIMIT: Int = 10_000
public const val MESSAGE_REACTIONS_PER_PARENT_LIMIT: Int = 1_024
public const val MESSAGE_REACTIONS_PER_NETWORK_LIMIT: Int = 100_000
internal const val NETWORK_RETENTION_TARGET: String = "\u0000network-retention"

public data class MessageInteractionConversation(public val networkId: String, public val conversation: String)

public data class MessageActivityRow(public val conversation: String, public val timestampMs: Long, public val rowId: Long)
public data class MessageReactionParent(public val conversation: String, public val msgid: String)

internal fun createInteractionTables(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS message_reactions (networkId TEXT NOT NULL, conversation TEXT NOT NULL, " +
            "msgid TEXT NOT NULL, emoji TEXT NOT NULL, sender TEXT NOT NULL, observedAtMs INTEGER NOT NULL, " +
            "PRIMARY KEY(networkId, conversation, msgid, emoji, sender))",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_message_reactions_networkId_conversation_observedAtMs " +
            "ON message_reactions(networkId, conversation, observedAtMs)",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_message_reactions_networkId_observedAtMs " +
            "ON message_reactions(networkId, observedAtMs)",
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS message_tombstones (networkId TEXT NOT NULL, conversation TEXT NOT NULL, " +
            "identity TEXT NOT NULL, timestampMs INTEGER NOT NULL, observedAtMs INTEGER NOT NULL, " +
            "PRIMARY KEY(networkId, conversation, identity))",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_message_tombstones_networkId_conversation_observedAtMs " +
            "ON message_tombstones(networkId, conversation, observedAtMs)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_message_tombstones_observedAtMs ON message_tombstones(observedAtMs)")
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS message_retention (networkId TEXT NOT NULL, conversation TEXT NOT NULL, " +
            "throughTimestampMs INTEGER NOT NULL, PRIMARY KEY(networkId, conversation))",
    )
}
