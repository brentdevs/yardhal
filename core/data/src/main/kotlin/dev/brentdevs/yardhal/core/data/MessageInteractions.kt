package dev.brentdevs.yardhal.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_reactions",
    primaryKeys = ["networkId", "conversation", "msgid", "emoji", "sender"],
    indices = [
        Index(
            value = ["networkId", "conversation", "msgid", "observedAtMs", "emoji", "sender"],
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.DESC, Index.Order.ASC, Index.Order.ASC],
        ),
        Index(
            value = ["networkId", "observedAtMs", "conversation", "msgid", "emoji", "sender"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.ASC],
        ),
        Index(value = ["orphan", "observedAtMs"]),
    ],
)
public data class MessageReactionRow(
    public val networkId: String,
    public val conversation: String,
    public val msgid: String,
    public val emoji: String,
    public val sender: String,
    public val observedAtMs: Long,
    @ColumnInfo(defaultValue = "0") public val orphan: Boolean = false,
)

@Entity(tableName = "message_reaction_counts")
public data class MessageReactionCountRow(
    @PrimaryKey public val networkId: String,
    @ColumnInfo(defaultValue = "0") public val membershipCount: Long = 0,
)

@Entity(tableName = "message_orphan_reaction_count")
public data class MessageOrphanReactionCountRow(
    @PrimaryKey public val singletonId: Int = 0,
    @ColumnInfo(defaultValue = "0") public val membershipCount: Long = 0,
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
    @ColumnInfo(defaultValue = "1") public val privacyDeletion: Boolean = true,
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
internal const val PENDING_ECHO_HISTORY_WINDOW_MS: Long = 120_000

public data class MessageInteractionConversation(public val networkId: String, public val conversation: String)

public data class MessageActivityRow(public val conversation: String, public val timestampMs: Long, public val rowId: Long)
public data class MessageReactionParent(public val conversation: String, public val msgid: String)
public data class MessageReactionVictim(public val reactionRowId: Long, public val parentRowId: Long?)

internal fun createInteractionTables(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS message_reactions (networkId TEXT NOT NULL, conversation TEXT NOT NULL, " +
            "msgid TEXT NOT NULL, emoji TEXT NOT NULL, sender TEXT NOT NULL, observedAtMs INTEGER NOT NULL, orphan INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(networkId, conversation, msgid, emoji, sender))",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_message_reactions_networkId_conversation_msgid_observedAtMs_emoji_sender " +
            "ON message_reactions(networkId ASC, conversation ASC, msgid ASC, observedAtMs DESC, emoji ASC, sender ASC)",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_message_reactions_networkId_observedAtMs_conversation_msgid_emoji_sender " +
            "ON message_reactions(networkId ASC, observedAtMs DESC, conversation ASC, msgid ASC, emoji ASC, sender ASC)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_message_reactions_orphan_observedAtMs ON message_reactions(orphan, observedAtMs)")
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS message_reaction_counts (networkId TEXT NOT NULL PRIMARY KEY, " +
            "membershipCount INTEGER NOT NULL DEFAULT 0)",
    )
    db.execSQL(
        "INSERT OR REPLACE INTO message_reaction_counts(networkId, membershipCount) " +
            "SELECT networkId, COUNT(*) FROM message_reactions GROUP BY networkId",
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS message_orphan_reaction_count (singletonId INTEGER NOT NULL PRIMARY KEY, " +
            "membershipCount INTEGER NOT NULL DEFAULT 0)",
    )
    db.execSQL(
        "UPDATE message_reactions SET orphan = NOT EXISTS (SELECT 1 FROM messages m WHERE " +
            "m.networkId = message_reactions.networkId AND m.conversation = message_reactions.conversation AND m.msgid = message_reactions.msgid)",
    )
    db.execSQL(
        "INSERT OR REPLACE INTO message_orphan_reaction_count(singletonId, membershipCount) " +
            "SELECT 0, COUNT(*) FROM message_reactions WHERE orphan = 1",
    )
    createReactionCountTriggers(db)
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS message_tombstones (networkId TEXT NOT NULL, conversation TEXT NOT NULL, " +
            "identity TEXT NOT NULL, timestampMs INTEGER NOT NULL, observedAtMs INTEGER NOT NULL, privacyDeletion INTEGER NOT NULL DEFAULT 1, " +
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

internal fun createReactionCountTriggers(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    db.execSQL("PRAGMA recursive_triggers = ON")
    db.execSQL(
        "INSERT INTO message_orphan_reaction_count(singletonId, membershipCount) SELECT 0, 0 " +
            "WHERE NOT EXISTS (SELECT 1 FROM message_orphan_reaction_count WHERE singletonId = 0)",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS trigger_message_reactions_insert_count AFTER INSERT ON message_reactions BEGIN " +
            "INSERT INTO message_reaction_counts(networkId, membershipCount) SELECT NEW.networkId, 0 " +
            "WHERE NOT EXISTS (SELECT 1 FROM message_reaction_counts WHERE networkId = NEW.networkId); " +
            "UPDATE message_reaction_counts SET membershipCount = membershipCount + 1 WHERE networkId = NEW.networkId; " +
            "UPDATE message_orphan_reaction_count SET membershipCount = membershipCount + NEW.orphan WHERE singletonId = 0 AND NEW.orphan = 1; " +
            "UPDATE message_reactions SET orphan = NOT EXISTS (SELECT 1 FROM messages m WHERE " +
            "m.networkId = NEW.networkId AND m.conversation = NEW.conversation AND m.msgid = NEW.msgid) " +
            "WHERE networkId = NEW.networkId AND conversation = NEW.conversation AND msgid = NEW.msgid AND emoji = NEW.emoji AND sender = NEW.sender " +
            "AND orphan != (NOT EXISTS (SELECT 1 FROM messages m WHERE m.networkId = NEW.networkId AND m.conversation = NEW.conversation AND m.msgid = NEW.msgid)); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS trigger_message_reactions_delete_count AFTER DELETE ON message_reactions BEGIN " +
            "UPDATE message_reaction_counts SET membershipCount = membershipCount - 1 WHERE networkId = OLD.networkId; " +
            "DELETE FROM message_reaction_counts WHERE networkId = OLD.networkId AND membershipCount = 0; " +
            "UPDATE message_orphan_reaction_count SET membershipCount = membershipCount - OLD.orphan WHERE singletonId = 0 AND OLD.orphan = 1; END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS trigger_message_reactions_move_count AFTER UPDATE OF networkId ON message_reactions " +
            "WHEN OLD.networkId != NEW.networkId BEGIN " +
            "UPDATE message_reaction_counts SET membershipCount = membershipCount - 1 WHERE networkId = OLD.networkId; " +
            "DELETE FROM message_reaction_counts WHERE networkId = OLD.networkId AND membershipCount = 0; " +
            "INSERT INTO message_reaction_counts(networkId, membershipCount) SELECT NEW.networkId, 0 " +
            "WHERE NOT EXISTS (SELECT 1 FROM message_reaction_counts WHERE networkId = NEW.networkId); " +
            "UPDATE message_reaction_counts SET membershipCount = membershipCount + 1 WHERE networkId = NEW.networkId; END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS trigger_message_reactions_orphan_count AFTER UPDATE OF orphan ON message_reactions " +
            "WHEN OLD.orphan != NEW.orphan BEGIN UPDATE message_orphan_reaction_count " +
            "SET membershipCount = membershipCount + NEW.orphan - OLD.orphan WHERE singletonId = 0; END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS trigger_message_reactions_parent_key AFTER UPDATE OF networkId, conversation, msgid ON message_reactions " +
            "WHEN OLD.networkId != NEW.networkId OR OLD.conversation != NEW.conversation OR OLD.msgid != NEW.msgid BEGIN " +
            "UPDATE message_reactions SET orphan = NOT EXISTS (SELECT 1 FROM messages m WHERE " +
            "m.networkId = NEW.networkId AND m.conversation = NEW.conversation AND m.msgid = NEW.msgid) " +
            "WHERE networkId = NEW.networkId AND conversation = NEW.conversation AND msgid = NEW.msgid AND emoji = NEW.emoji AND sender = NEW.sender; END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS trigger_messages_attach_reactions AFTER INSERT ON messages WHEN NEW.msgid IS NOT NULL BEGIN " +
            "UPDATE message_reactions SET orphan = 0 WHERE networkId = NEW.networkId AND conversation = NEW.conversation AND msgid = NEW.msgid AND orphan = 1; END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS trigger_messages_detach_reactions AFTER DELETE ON messages WHEN OLD.msgid IS NOT NULL BEGIN " +
            "UPDATE message_reactions SET orphan = 1 WHERE networkId = OLD.networkId AND conversation = OLD.conversation AND msgid = OLD.msgid " +
            "AND orphan = 0 AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.networkId = OLD.networkId AND m.conversation = OLD.conversation AND m.msgid = OLD.msgid); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS trigger_messages_move_reactions AFTER UPDATE OF networkId, conversation, msgid ON messages " +
            "WHEN OLD.networkId != NEW.networkId OR OLD.conversation != NEW.conversation OR OLD.msgid IS NOT NEW.msgid BEGIN " +
            "UPDATE message_reactions SET orphan = 1 WHERE networkId = OLD.networkId AND conversation = OLD.conversation AND msgid = OLD.msgid " +
            "AND orphan = 0 AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.networkId = OLD.networkId AND m.conversation = OLD.conversation AND m.msgid = OLD.msgid); " +
            "UPDATE message_reactions SET orphan = 0 WHERE networkId = NEW.networkId AND conversation = NEW.conversation AND msgid = NEW.msgid AND orphan = 1; END",
    )
}
