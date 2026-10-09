package dev.brentdevs.yardhal.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

@Database(
    entities = [
        MessageRow::class,
        MessageReactionRow::class,
        MessageTombstoneRow::class,
        MessageRetentionRow::class,
        OfflineConversationRow::class,
        WhoisCacheRow::class,
        LaunchSelectionRow::class,
        OfflineQuarantineRow::class,
    ],
    version = YardhalDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
public abstract class YardhalDatabase : RoomDatabase() {

    public abstract fun messageDao(): MessageDao
    public abstract fun offlineDao(): OfflineDao

    public companion object {
        internal const val SCHEMA_VERSION: Int = 4

        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN channelContext TEXT")
            }
        }

        private val migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN historyContext INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DROP INDEX IF EXISTS index_messages_msgid")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_networkId_conversation_msgid " +
                        "ON messages(networkId, conversation, msgid)",
                )
            }
        }

        private val migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("highlightsMe", "playback", "redacted", "reactionsTruncated")) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("ALTER TABLE messages ADD COLUMN highlightsKnown INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE messages SET highlightsKnown = 0")
                for (column in listOf("replyToMsgid", "attachmentUrl", "attachmentName", "attachmentMimeType", "senderAccount", "originalIdentityHash")) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN $column TEXT")
                }
                for (column in listOf("replyParentRowId", "attachmentSizeBytes", "attachmentWidth", "attachmentHeight")) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN $column INTEGER")
                }
                createInteractionTables(db)
                createOfflineTables(db)
                ensureFts(db)
                db.execSQL("DELETE FROM message_fts WHERE rowid NOT IN (SELECT rowId FROM messages)")
                db.execSQL(
                    "INSERT INTO message_fts(rowid, sender, body) SELECT rowId, senderNick, text FROM messages " +
                        "WHERE rowId NOT IN (SELECT rowid FROM message_fts)",
                )
            }
        }

        private fun ensureFts(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts4(sender, body, tokenize=unicode61)")
        }

        private val callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = ensureFts(db)

            override fun onOpen(db: SupportSQLiteDatabase) = ensureFts(db)
        }

        public fun build(
            context: Context,
            name: String = "yardhal.db",
            factory: SupportSQLiteOpenHelper.Factory? = null,
        ): YardhalDatabase {
            val builder = Room.databaseBuilder(context, YardhalDatabase::class.java, name)
                .addCallback(callback)
                .addMigrations(migration1To2, migration2To3, migration3To4)
            if (factory != null) builder.openHelperFactory(factory)
            return builder.build()
        }

        public fun inMemory(context: Context): YardhalDatabase =
            Room.inMemoryDatabaseBuilder(context, YardhalDatabase::class.java)
                .addCallback(callback)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .build()
    }
}
