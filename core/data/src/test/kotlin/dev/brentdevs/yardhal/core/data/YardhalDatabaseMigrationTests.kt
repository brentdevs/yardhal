package dev.brentdevs.yardhal.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YardhalDatabaseMigrationTests {

    @Test
    fun versionOneUpgradePreservesTranscriptHashesRowIdsAndSearch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "message-migration-test.db"
        val ref = ConversationRef.server("n1")
        val legacy = listOf(
            message(ref, 41, "legacy-1", "first legacy notice", 1000),
            message(ref, 57, null, "second legacy notice", 2000),
        ).map { it.copy(highlightsKnown = false) }
        context.deleteDatabase(name)
        try {
            createVersionOneDatabase(context, name, legacy)
            val db = YardhalDatabase.build(context, name)
            val added: StoredMessage
            try {
                val store = MessageStore(db.messageDao())
                assertEquals(legacy, store.recent(ref, 10))
                assertEquals(UnreadCounts(2, 0, false), store.unreadCounts(ref, ReadCursor()))
                assertEquals(legacy, store.after(ref, 0))
                assertEquals(legacy, store.before(ref, MessageCursor(3000, 0), 10))
                assertEquals(legacy, store.around(ref, 57, 2000))
                assertEquals(4, db.openHelper.writableDatabase.version)
                assertEquals(
                    legacy.map { it.rowId to MessageStore.contentHash(it) },
                    db.messageDao().allRows().sortedBy { it.rowId }.map { it.rowId to it.contentHash },
                )
                assertTrue(db.messageDao().allRows().none { it.historyContext })
                assertEquals(listOf(57L, 41L), store.search("archiveindex").map { it.rowId })

                val contextual = message(ref, 0, "new-1", "contextual notice", 3000).copy(channelContext = "#Room")
                val rowId = assertNotNull(store.recordWithRowId(contextual))
                assertEquals(201L, rowId)
                added = contextual.copy(rowId = rowId)
                assertEquals(legacy + added, store.recent(ref, 10))
                assertEquals(listOf(rowId), store.search("contextual").map { it.rowId })
                assertFalse(store.record(legacy[0].copy(timestampMs = 9000)))
                assertFalse(store.record(legacy[1]))
            } finally {
                db.close()
            }

            val reopened = YardhalDatabase.build(context, name)
            try {
                val store = MessageStore(reopened.messageDao())
                assertEquals(legacy + added, store.recent(ref, 10))
                assertEquals(listOf(added), store.after(ref, 2000))
                assertEquals(legacy + added, store.before(ref, MessageCursor(4000, 0), 10))
                assertEquals(legacy + added, store.around(ref, added.rowId, added.timestampMs))
                assertEquals(listOf(57L, 41L), store.search("archiveindex").map { it.rowId })
                assertEquals(listOf(added.rowId), store.search("contextual").map { it.rowId })
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionTwoUpgradeScopesMsgidsWithoutRebuildingRowsOrSearch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "message-migration-v2-test.db"
        val ref = ConversationRef.channel("n1", "#room")
        val legacy = listOf(
            message(ref, 41, "shared-id", "first legacy notice", 1000).copy(channelContext = "#Context"),
            message(ref, 57, null, "second legacy notice", 2000),
        ).map { it.copy(highlightsKnown = false) }
        val added = mutableListOf<StoredMessage>()
        context.deleteDatabase(name)
        try {
            createVersionTwoDatabase(context, name, legacy)
            val db = YardhalDatabase.build(context, name)
            try {
                val store = MessageStore(db.messageDao())
                assertEquals(legacy, store.recent(ref, 10))
                assertEquals(UnreadCounts(2, 0, false), store.unreadCounts(ref, ReadCursor()))
                assertEquals(legacy, store.after(ref, 0))
                assertEquals(legacy, store.before(ref, MessageCursor(3000, 0), 10))
                assertEquals(legacy, store.around(ref, 57, 2000))
                assertEquals(listOf(legacy.last()), store.historyAnchors("n1"))
                assertTrue(db.messageDao().allRows().none { it.historyContext })
                db.openHelper.writableDatabase.query("PRAGMA table_info(messages)").use { columns ->
                    var found = false
                    while (columns.moveToNext()) {
                        if (columns.getString(columns.getColumnIndexOrThrow("name")) == "historyContext") {
                            assertEquals("INTEGER", columns.getString(columns.getColumnIndexOrThrow("type")))
                            assertEquals(1, columns.getInt(columns.getColumnIndexOrThrow("notnull")))
                            assertEquals("0", columns.getString(columns.getColumnIndexOrThrow("dflt_value")))
                            found = true
                        }
                    }
                    assertTrue(found)
                }
                assertEquals(4, db.openHelper.writableDatabase.version)
                assertEquals(
                    legacy.map { it.rowId to MessageStore.contentHash(it) },
                    db.messageDao().allRows().sortedBy { it.rowId }.map { it.rowId to it.contentHash },
                )
                assertEquals(listOf(57L, 41L), store.search("archiveindex").map { it.rowId })
                for (other in listOf(
                    ConversationRef.channel("n1", "#other"),
                    ConversationRef.directMessage("n1", "bob"),
                    ConversationRef.channel("n2", "#room"),
                )) {
                    val replay = message(other, 0, "shared-id", "new replay notice", 3000).copy(historyContext = true)
                    val rowId = assertNotNull(store.recordWithRowId(replay))
                    added.add(replay.copy(rowId = rowId))
                    assertEquals(listOf(replay.copy(rowId = rowId)), store.recent(other, 10))
                    assertEquals(rowId, store.findStoredMessage(replay)?.rowId)
                }
                assertEquals(listOf(201L, 202L, 203L), added.map { it.rowId })
                assertFalse(store.record(legacy.first().copy(text = "overlapping replay")))
                assertEquals(legacy, store.recent(ref, 10))
            } finally {
                db.close()
            }
            val reopened = YardhalDatabase.build(context, name)
            try {
                val store = MessageStore(reopened.messageDao())
                assertEquals(legacy, store.recent(ref, 10))
                assertEquals(listOf(57L, 41L), store.search("archiveindex").map { it.rowId })
                for (replay in added) {
                    assertEquals(listOf(replay), store.recent(replay.conversation, 10))
                    assertEquals(replay.rowId, store.findStoredMessage(replay)?.rowId)
                    assertTrue(store.historyAnchors(replay.networkId).none { it.conversation == replay.conversation })
                }
                assertEquals(added.map { it.rowId }.toSet(), store.search("new replay").map { it.rowId }.toSet())
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionThreeUpgradePreservesHistoryMetadataAndRepairsOnlyMissingFtsRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "message-migration-v3-test.db"
        val ref = ConversationRef.channel("n1", "#room")
        val legacy = listOf(
            message(ref, 41, "live", "live body", 1000).copy(channelContext = "#Context"),
            message(ref, 57, "history", "historic body", 2000).copy(historyContext = true),
        ).map { it.copy(highlightsKnown = false) }
        context.deleteDatabase(name)
        try {
            createVersionTwoDatabase(context, name, legacy)
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { fixture ->
                fixture.execSQL("ALTER TABLE messages ADD COLUMN historyContext INTEGER NOT NULL DEFAULT 0")
                fixture.execSQL("UPDATE messages SET historyContext = 1 WHERE rowId = 57")
                fixture.execSQL("DROP INDEX index_messages_msgid")
                fixture.execSQL(
                    "CREATE UNIQUE INDEX index_messages_networkId_conversation_msgid ON messages(networkId, conversation, msgid)",
                )
                fixture.execSQL("DELETE FROM message_fts WHERE rowid = 57")
                fixture.execSQL("INSERT INTO message_fts(rowid, sender, body) VALUES(999, 'orphan', 'orphanindex')")
                fixture.execSQL("UPDATE room_master_table SET identity_hash = '7372bbb340fb21877bbf4725649347e3' WHERE id = 42")
                fixture.version = 3
            }
            YardhalDatabase.build(context, name).use { db ->
                val store = MessageStore(db.messageDao())
                assertEquals(legacy, store.recent(ref, 10))
                assertEquals(legacy.map { it.rowId to MessageStore.contentHash(it) }, db.messageDao().allRows().map { it.rowId to it.contentHash })
                assertEquals(listOf(41L), store.search("archiveindex").map { it.rowId })
                assertEquals(listOf(57L), store.search("historic").map { it.rowId })
                assertTrue(store.search("orphanindex").isEmpty())
                assertTrue(store.reactions(ref).isEmpty())
                assertEquals(UnreadCounts(1, 0, false), store.unreadCounts(ref, ReadCursor()))
                assertEquals(UnreadCounts(), store.unreadCounts(ref, ReadCursor(1000, 41)))
                val enriched = legacy.first().copy(
                    highlightsMe = true, senderAccount = "account", attachmentUrl = "https://example.test/a.png",
                    highlightsKnown = true,
                    attachmentName = "a.png", attachmentMimeType = "image/png", attachmentWidth = 10, attachmentHeight = 20,
                )
                assertFalse(store.record(enriched))
                assertEquals(enriched, store.byRowId(ref, 41))
                assertEquals(UnreadCounts(1, 1, true), store.unreadCounts(ref, ReadCursor()))
                assertEquals(201L, assertNotNull(store.recordWithRowId(message(ref, 0, "new", "new body", 3000))))
                val pending = message(ref, 0, null, "pending body", 4000).copy(
                    sentByUs = true, pendingEcho = true, replyParentRowId = 41,
                )
                assertEquals(202L, store.recordLocalEcho(pending))
            }
            YardhalDatabase.build(context, name).use { db ->
                val restored = MessageStore(db.messageDao()).recent(ref, 10).first()
                assertEquals("account", restored.senderAccount)
                assertEquals(10, restored.attachmentWidth)
                assertTrue(restored.highlightsMe)
                val store = MessageStore(db.messageDao())
                assertTrue(assertNotNull(store.byRowId(ref, 202)).pendingEcho)
                val confirmed = message(ref, 0, "confirmed", "pending body", 4500).copy(sentByUs = true, historyContext = true)
                assertFalse(store.record(confirmed))
                val healed = assertNotNull(store.byRowId(ref, 202))
                assertFalse(healed.pendingEcho)
                assertEquals("confirmed", healed.msgid)
                assertEquals(41L, healed.replyParentRowId)
                store.applyReaction(ref, "confirmed", "bob", "heart", true, 5000)
                assertEquals(1L, db.messageDao().reactionMembershipCount(ref.networkId))
                assertEquals(mapOf("heart" to setOf("bob")), store.reactions(ref, "confirmed"))
                store.applyReaction(ref, "confirmed", "bob", "heart", false, 5001)
                assertEquals(null, db.messageDao().reactionMembershipCount(ref.networkId))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun createVersionTwoDatabase(context: Context, name: String, messages: List<StoredMessage>) {
        createVersionOneDatabase(context, name, messages)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("ALTER TABLE messages ADD COLUMN channelContext TEXT")
            for (message in messages) {
                db.execSQL(
                    "UPDATE messages SET channelContext = ? WHERE rowId = ?",
                    arrayOf<Any?>(message.channelContext, message.rowId),
                )
            }
            db.execSQL(
                "UPDATE room_master_table SET identity_hash = 'b56564ddcdccf4e400805570c63ed494' WHERE id = 42",
            )
            db.version = 2
        }
    }

    private fun createVersionOneDatabase(
        context: Context,
        name: String,
        messages: List<StoredMessage>,
    ): Unit = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `messages` (" +
                "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `networkId` TEXT NOT NULL, " +
                "`conversation` TEXT NOT NULL, `msgid` TEXT, `contentHash` TEXT NOT NULL, " +
                "`senderNick` TEXT NOT NULL, `senderUser` TEXT, `senderHost` TEXT, `kind` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, `sentByUs` INTEGER NOT NULL, `timestampMs` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_msgid` ON `messages` (`msgid`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_messages_networkId_conversation_timestampMs` " +
                "ON `messages` (`networkId`, `conversation`, `timestampMs`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_contentHash` ON `messages` (`contentHash`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '6b750802119638f32c9bf2fbe6e7526b')")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts4(sender, body, tokenize=unicode61)")
        for (message in messages) {
            db.execSQL(
                "INSERT INTO messages (rowId, networkId, conversation, msgid, contentHash, senderNick, " +
                    "senderUser, senderHost, kind, text, sentByUs, timestampMs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    message.rowId,
                    message.networkId,
                    message.conversation.normalizedTarget,
                    message.msgid,
                    MessageStore.contentHash(message),
                    message.senderNick,
                    message.senderUser,
                    message.senderHost,
                    message.kind.name,
                    message.text,
                    if (message.sentByUs) 1 else 0,
                    message.timestampMs,
                ),
            )
            db.execSQL(
                "INSERT INTO message_fts(rowid, sender, body) VALUES (?, ?, ?)",
                arrayOf<Any?>(message.rowId, message.senderNick, "${message.text} archiveindex"),
            )
        }
        db.execSQL("UPDATE sqlite_sequence SET seq = 200 WHERE name = 'messages'")
        db.version = 1
    }

    private fun message(
        ref: ConversationRef,
        rowId: Long,
        msgid: String?,
        text: String,
        timestampMs: Long,
    ): StoredMessage = StoredMessage(
        rowId = rowId,
        networkId = ref.networkId,
        conversation = ref,
        msgid = msgid,
        senderNick = "alice",
        senderUser = "user",
        senderHost = "host",
        kind = MessageKind.NOTICE,
        text = text,
        sentByUs = false,
        timestampMs = timestampMs,
    )
}

private inline fun <T> YardhalDatabase.use(block: (YardhalDatabase) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
