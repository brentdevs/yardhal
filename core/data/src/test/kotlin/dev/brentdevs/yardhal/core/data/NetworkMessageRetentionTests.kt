package dev.brentdevs.yardhal.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkMessageRetentionTests {
    private fun ref(target: String, networkId: String = "n1") = ConversationRef.directMessage(networkId, target)

    private fun message(target: String, id: String, timestamp: Long, networkId: String = "n1") = StoredMessage(
        networkId = networkId, conversation = ref(target, networkId), msgid = id, senderNick = "alice",
        senderUser = "u", senderHost = "h", kind = MessageKind.PRIVMSG, text = "body-$id", sentByUs = false,
        timestampMs = timestamp,
    )

    @Test
    fun retiredPendingRedactionsBlockParentReplayWithoutKeepingTargetLedgers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            store.redact(ref("retired"), "victim", 100)
            store.maintainNetwork("n1", 10, 1, protectedConversation = ConversationRef.SERVER_TARGET, nowMs = 100)
            assertTrue(db.messageDao().tombstones("n1", "retired").isEmpty())
            assertNull(db.messageDao().retentionFloor("n1", "retired"))
            assertFalse(store.record(message("retired", "victim", 50).copy(historyContext = true)))
            assertTrue(store.search("body-victim").isEmpty())
        }
    }

    @Test
    fun bulkRowHydrationIsScopedDeduplicatedAndChunked() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val firstId = assertNotNull(store.recordWithRowId(message("a", "first", 1)))
            val secondId = assertNotNull(store.recordWithRowId(message("a", "second", 2).copy(senderAccount = "account")))
            val otherTarget = assertNotNull(store.recordWithRowId(message("b", "other", 3)))
            val otherNetwork = assertNotNull(store.recordWithRowId(message("a", "network", 4, "n2")))
            val ids = mutableListOf(firstId, otherTarget, otherNetwork)
            repeat(501) { ids.add(10_000L + it) }
            ids.add(secondId)
            ids.add(firstId)
            val rows = store.byRowIds(ref("a"), ids)
            assertEquals(setOf(firstId, secondId), rows.map { it.rowId }.toSet())
            assertEquals(2, rows.size)
            assertEquals("account", rows.single { it.rowId == secondId }.senderAccount)
            assertTrue(store.byRowIds(ref("a"), emptyList()).isEmpty())
        }
    }

    @Test
    fun networkCapsPrioritizeSelectedWithinTotalAndCleanDroppedInteractionsAndSearch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            store.record(message("a", "a1", 1))
            store.record(message("a", "a2", 2))
            store.record(message("b", "b1", 3))
            val dropped = message("b", "b2", 4)
            store.record(dropped)
            store.redact(ref("b"), "unseen", 100)
            store.applyReaction(ref("b"), "future", "bob", "heart", true, 100)
            val parentId = assertNotNull(store.recordWithRowId(message("c", "c1", 5)))
            val replyId = assertNotNull(store.recordWithRowId(message("c", "c2", 6).copy(replyParentRowId = parentId)))
            store.applyReaction(ref("c"), "c1", "bob", "heart", true, 100)
            store.record(message("other", "untouched", 7, "n2"))
            store.maintainNetwork("n1", 3, 2, protectedConversation = "a", nowMs = 100)
            assertEquals(3, db.messageDao().allRows().count { it.networkId == "n1" })
            assertEquals(listOf("c", "a"), store.knownConversations("n1").map { it.normalizedTarget })
            assertEquals(listOf("a1", "a2"), store.recent(ref("a"), 10).map { it.msgid })
            assertEquals(listOf(replyId), store.recent(ref("c"), 10).map { it.rowId })
            assertNull(store.byRowId(ref("c"), replyId)?.replyParentRowId)
            assertTrue(db.messageDao().reactions("n1", "b").isEmpty())
            assertTrue(db.messageDao().tombstones("n1", "b").isEmpty())
            assertNull(db.messageDao().retentionFloor("n1", "b"))
            assertTrue(store.reactions(ref("c")).isEmpty())
            assertTrue(store.search("body-b2").isEmpty())
            assertTrue(store.search("body-c1").isEmpty())
            assertEquals(1, store.recent(ref("other", "n2"), 10).size)
            assertEquals(100, db.messageDao().retentionFloor("n1", NETWORK_RETENTION_TARGET))
            assertFalse(store.record(dropped.copy(historyContext = true)))
            assertFalse(store.record(message("new", "historical", 100).copy(playback = true)))
            assertTrue(store.record(message("new", "live-equal", 100)))
            assertFalse(store.record(message("a", "a1", 1).copy(historyContext = true)))
        }
    }

    @Test
    fun equalTimeNetworkBudgetUsesRowIdentityAndBoundsRepeatedTargetBookkeeping() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            repeat(30) { index ->
                store.record(message("target-$index", "m-$index", 1000))
                store.maintainNetwork("n1", 2, 2, nowMs = 50_000 + index.toLong())
                assertTrue(db.messageDao().allRows().size <= 2)
                assertTrue(store.knownConversations("n1").size <= 2)
                assertTrue(db.messageDao().interactionTargets("n1", NETWORK_RETENTION_TARGET).size <= 2)
            }
            assertEquals(setOf("m-28", "m-29"), db.messageDao().allRows().map { it.msgid }.toSet())
            assertEquals(1000, db.messageDao().retentionFloor("n1", NETWORK_RETENTION_TARGET))
            repeat(28) { index ->
                assertNull(db.messageDao().retentionFloor("n1", "target-$index"))
                assertTrue(db.messageDao().tombstones("n1", "target-$index").isEmpty())
            }
            assertFalse(store.record(message("target-0", "m-0", 1000).copy(historyContext = true)))
            assertFalse(store.record(message("target-28", "m-28", 1000).copy(historyContext = true)))
            assertTrue(store.record(message("equal-live", "new-live", 1000)))
            store.maintainNetwork("n1", 2, 2, nowMs = 60_000)
            assertEquals(setOf("m-29", "new-live"), db.messageDao().allRows().map { it.msgid }.toSet())
            assertTrue(store.search("body-m-0").isEmpty())
        }
    }

    @Test
    fun pluralProtectedTargetsAndEmptyServerReservationStayInsideBudgets() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            store.record(message("current", "current", 1))
            store.record(message("last", "last", 2))
            store.record(message("newer", "newer", 3))
            store.record(message("newest", "newest", 4))
            store.maintainNetwork("n1", 3, 3, nowMs = 100, protectedConversations = linkedSetOf("current", "last"))
            assertEquals(setOf("current", "last", "newest"), store.knownConversations("n1").map { it.normalizedTarget }.toSet())
            assertEquals(3, db.messageDao().allRows().size)
            store.maintainNetwork(
                "n1", 2, 2, nowMs = 101,
                protectedConversations = linkedSetOf("current", ConversationRef.SERVER_TARGET),
            )
            assertEquals(listOf("current"), store.knownConversations("n1").map { it.normalizedTarget })
            assertEquals(1, db.messageDao().allRows().size)
            assertTrue(db.messageDao().interactionTargets("n1", NETWORK_RETENTION_TARGET).size <= 2)
        }
    }

    @Test
    fun protectedPriorityNeverExceedsMessageBudgetAndZeroBudgetRetiresAllTargetState() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            repeat(4) { index -> store.record(message("current", "current-$index", index.toLong())) }
            store.record(message("last", "last", 10))
            store.record(message("newest", "newest", 20))
            store.maintainNetwork("n1", 2, 3, nowMs = 100, protectedConversations = linkedSetOf("current", "last"))
            assertEquals(listOf("current-2", "current-3"), store.recent(ref("current"), 10).map { it.msgid })
            assertEquals(2, db.messageDao().allRows().size)
            assertTrue(store.recent(ref("last"), 10).isEmpty())
            assertTrue(store.recent(ref("newest"), 10).isEmpty())
            store.maintainNetwork("n1", 0, 0, protectedConversation = "current", nowMs = 101)
            assertTrue(db.messageDao().allRows().isEmpty())
            assertTrue(db.messageDao().interactionTargets("n1", NETWORK_RETENTION_TARGET).isEmpty())
            assertTrue(store.search("body").isEmpty())
            assertNotNull(db.messageDao().retentionFloor("n1", NETWORK_RETENTION_TARGET))
        }
    }

    @Test
    fun pendingBeforeParentStateSurvivesWithinBoundedTargetBudget() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            repeat(12) { index ->
                store.redact(ref("pending-$index"), "gone-$index", 1000 + index.toLong())
                db.messageDao().putRetention(MessageRetentionRow("n1", "floor-$index", index.toLong()))
            }
            store.applyReaction(ref("selected"), "future", "bob", "heart", true, 2000)
            store.maintainNetwork("n1", 2, 2, protectedConversation = "selected", nowMs = 2000)
            assertTrue(db.messageDao().interactionTargets("n1", NETWORK_RETENTION_TARGET).size <= 2)
            assertEquals(setOf("pending-11", "selected"), db.messageDao().interactionTargets("n1", NETWORK_RETENTION_TARGET).toSet())
            assertTrue(store.record(message("selected", "future", 3000)))
            assertEquals(mapOf("heart" to setOf("bob")), store.reactions(ref("selected"), "future"))
            assertFalse(store.record(message("pending-11", "gone-11", 3000)))
            assertNotNull(db.messageDao().retentionFloor("n1", NETWORK_RETENTION_TARGET))
        }
    }

    @Test
    fun failedNetworkRetentionRollsBackRowsInteractionsAndNetworkFloor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            store.record(message("old", "old", 1))
            store.record(message("new", "new", 2))
            store.applyReaction(ref("old"), "old", "bob", "heart", true, 100)
            db.openHelper.writableDatabase.execSQL("DROP TABLE message_fts")
            assertTrue(runCatching { store.maintainNetwork("n1", 1, 1, nowMs = 100) }.isFailure)
            assertEquals(2, db.messageDao().allRows().size)
            assertEquals(mapOf("heart" to setOf("bob")), store.reactions(ref("old"), "old"))
            assertNull(db.messageDao().retentionFloor("n1", NETWORK_RETENTION_TARGET))
        }
    }
}

private inline fun <T> YardhalDatabase.use(block: (YardhalDatabase) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
