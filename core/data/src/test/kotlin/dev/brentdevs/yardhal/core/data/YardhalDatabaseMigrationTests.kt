package dev.brentdevs.yardhal.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        )
        context.deleteDatabase(name)
        try {
            createVersionOneDatabase(context, name, legacy)
            val db = YardhalDatabase.build(context, name)
            val added: StoredMessage
            try {
                val store = MessageStore(db.messageDao())
                assertEquals(legacy, store.recent(ref, 10))
                assertEquals(legacy, store.after(ref, 0))
                assertEquals(legacy, store.before(ref, 3000, 10))
                assertEquals(legacy, store.around(ref, 57, 2000))
                assertEquals(2, db.openHelper.writableDatabase.version)
                assertEquals(
                    legacy.map { it.rowId to MessageStore.contentHash(it) },
                    db.messageDao().allRows().sortedBy { it.rowId }.map { it.rowId to it.contentHash },
                )
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
                assertEquals(legacy + added, store.before(ref, 4000, 10))
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
