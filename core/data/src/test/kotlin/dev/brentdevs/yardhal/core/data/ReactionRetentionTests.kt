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
class ReactionRetentionTests {
    private fun ref(target: String = "#room", networkId: String = "n1") = ConversationRef.channel(networkId, target)

    private fun message(id: String, target: String = "#room", networkId: String = "n1") = StoredMessage(
        networkId = networkId, conversation = ref(target, networkId), msgid = id, senderNick = "alice", senderUser = "u",
        senderHost = "h", kind = MessageKind.PRIVMSG, text = "body-$id", sentByUs = false, timestampMs = 1000,
    )

    @Test
    fun parentCapRetainsNewestMembershipsAndPartialFlagEvenWhenLaterEmpty() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val rowId = assertNotNull(store.recordWithRowId(message("p")))
            for ((index, sender) in listOf("alice", "bob", "carol").withIndex()) {
                store.applyReaction(ref(), "p", sender, "heart", true, index + 1L, perParentLimit = 2, networkLimit = 10)
            }
            assertEquals(mapOf("heart" to setOf("bob", "carol")), store.reactions(ref(), "p"))
            assertTrue(assertNotNull(store.byRowId(ref(), rowId)).reactionsTruncated)
            store.record(message("p").copy(senderAccount = "account", reactionsTruncated = false))
            assertTrue(assertNotNull(store.byRowId(ref(), rowId)).reactionsTruncated)
            for (sender in listOf("bob", "carol")) store.applyReaction(ref(), "p", sender, "heart", false, 10, 2, 10)
            assertTrue(store.reactions(ref(), "p").isEmpty())
            assertTrue(assertNotNull(store.byRowId(ref(), rowId)).reactionsTruncated)
            store.redact(ref(), "p", 20)
            assertFalse(assertNotNull(store.byRowId(ref(), rowId)).reactionsTruncated)
        }
    }

    @Test
    fun networkCapMarksOnlyAffectedRetainedParentsAndLeavesOtherNetworksUntouched() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val first = assertNotNull(store.recordWithRowId(message("p")))
            val second = assertNotNull(store.recordWithRowId(message("q", "#other")))
            val foreign = assertNotNull(store.recordWithRowId(message("p", networkId = "n2")))
            store.applyReaction(ref(networkId = "n2"), "p", "foreign", "heart", true, 1, 10, 3)
            store.applyReaction(ref(), "p", "old", "heart", true, 1, 10, 3)
            store.applyReaction(ref(), "p", "newer", "heart", true, 2, 10, 3)
            store.applyReaction(ref("#other"), "q", "recent", "heart", true, 3, 10, 3)
            val changed = mutableSetOf<Long>()
            store.applyReaction(ref("#other"), "q", "newest", "heart", true, 4, 10, 3) {
                assertFalse(db.openHelper.writableDatabase.inTransaction())
                changed.addAll(it)
            }
            assertEquals(setOf(first, second), changed)
            assertEquals(3, db.messageDao().reactions("n1", "#room").size + db.messageDao().reactions("n1", "#other").size)
            assertEquals(mapOf("heart" to setOf("newer")), store.reactions(ref(), "p"))
            assertEquals(mapOf("heart" to setOf("recent", "newest")), store.reactions(ref("#other"), "q"))
            assertTrue(assertNotNull(store.byRowId(ref(), first)).reactionsTruncated)
            assertFalse(assertNotNull(store.byRowId(ref("#other"), second)).reactionsTruncated)
            assertFalse(assertNotNull(store.byRowId(ref(networkId = "n2"), foreign)).reactionsTruncated)
            assertEquals(mapOf("heart" to setOf("foreign")), store.reactions(ref(networkId = "n2"), "p"))
        }
    }

    @Test
    fun equalObservationTimesUseDeterministicMembershipIdentityOrder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            store.record(message("p"))
            for (sender in listOf("z", "a", "m")) store.applyReaction(ref(), "p", sender, "heart", true, 100, 2, 10)
            assertEquals(mapOf("heart" to setOf("a", "m")), store.reactions(ref(), "p"))
            assertTrue(store.recent(ref(), 1).single().reactionsTruncated)
        }
    }

    @Test
    fun runtimeMaintenanceCapsExistingMembershipsUsingInjectedSmallLimits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val rowId = assertNotNull(store.recordWithRowId(message("p")))
            for (index in 0..4) db.messageDao().putReaction(MessageReactionRow("n1", "#room", "p", "heart", "sender-$index", index.toLong()))
            store.maintainReactionMemberships("n1", perParentLimit = 2, networkLimit = 3)
            assertEquals(mapOf("heart" to setOf("sender-3", "sender-4")), store.reactions(ref(), "p"))
            assertTrue(assertNotNull(store.byRowId(ref(), rowId)).reactionsTruncated)
            store.maintainReactionMemberships("n1", perParentLimit = 0, networkLimit = 0)
            assertTrue(store.reactions(ref(), "p").isEmpty())
            assertTrue(assertNotNull(store.byRowId(ref(), rowId)).reactionsTruncated)
        }
    }

    @Test
    fun truncationSurvivesReopenEchoHealingRenameCollisionAndHistoricalOverlap() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "reaction-truncation-test.db"
        context.deleteDatabase(name)
        try {
            val originalId = YardhalDatabase.build(context, name).use { db ->
                val store = MessageStore(db.messageDao())
                val rowId = assertNotNull(store.recordWithRowId(message("p").copy(sentByUs = true)))
                for ((index, sender) in listOf("old", "new", "newest").withIndex()) {
                    store.applyReaction(ref(), "p", sender, "heart", true, index + 1L, 2, 10)
                }
                rowId
            }
            val target = ref("#renamed")
            YardhalDatabase.build(context, name).use { db ->
                val store = MessageStore(db.messageDao())
                assertTrue(assertNotNull(store.byRowId(ref(), originalId)).reactionsTruncated)
                val healed = message("p").copy(text = "canonical body", timestampMs = 2000, sentByUs = true)
                store.healEcho(ref(), originalId, healed)
                assertTrue(assertNotNull(store.byRowId(ref(), originalId)).reactionsTruncated)
                store.record(healed.copy(conversation = target))
                store.renameConversation(ref(), target)
                assertTrue(store.recent(target, 10).single().reactionsTruncated)
                store.record(healed.copy(conversation = target, playback = true, historyContext = true, reactionsTruncated = false))
                assertTrue(store.recent(target, 10).single().reactionsTruncated)
            }
            YardhalDatabase.build(context, name).use { db ->
                val store = MessageStore(db.messageDao())
                assertTrue(store.recent(target, 10).single().reactionsTruncated)
                assertEquals(mapOf("heart" to setOf("new", "newest")), store.reactions(target, "p"))
                store.redact(target, "p")
            }
            YardhalDatabase.build(context, name).use { db ->
                val restored = MessageStore(db.messageDao()).recent(target, 10).single()
                assertTrue(restored.redacted)
                assertFalse(restored.reactionsTruncated)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun renameReactionCollisionPreservesNewestObservedMembershipPriority() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val target = ref("#renamed")
            store.record(message("p"))
            store.record(message("p").copy(conversation = target))
            store.applyReaction(ref(), "p", "same", "heart", true, 1)
            store.applyReaction(target, "p", "same", "heart", true, 10)
            store.applyReaction(target, "p", "older", "heart", true, 5)
            store.renameConversation(ref(), target)
            store.maintainReactionMemberships("n1", perParentLimit = 1, networkLimit = 10)
            assertEquals(mapOf("heart" to setOf("same")), store.reactions(target, "p"))
            assertTrue(store.recent(target, 10).single().reactionsTruncated)
        }
    }

    @Test
    fun orphanEvictionIsBoundedWithoutCreatingUnattachedMessageMarkers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            for ((index, sender) in listOf("old", "new", "newest").withIndex()) {
                store.applyReaction(ref(), "future", sender, "heart", true, index + 1L, 2, 2)
            }
            assertEquals(2, db.messageDao().reactions("n1", "#room").size)
            assertTrue(db.messageDao().allRows().isEmpty())
            assertTrue(store.reactions(ref(), "future").isEmpty())
            store.record(message("future"))
            assertEquals(mapOf("heart" to setOf("new", "newest")), store.reactions(ref(), "future"))
        }
    }

    @Test
    fun failedEvictionDoesNotPublishMembershipOrTruncationFlag() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val rowId = assertNotNull(store.recordWithRowId(message("p")))
            store.applyReaction(ref(), "p", "old", "heart", true, 1, 2, 10)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_reaction_delete BEFORE DELETE ON message_reactions BEGIN SELECT RAISE(ABORT, 'fixture'); END",
            )
            var notified = false
            assertTrue(runCatching {
                store.applyReaction(ref(), "p", "new", "heart", true, 2, 1, 10) { notified = true }
            }.isFailure)
            assertFalse(notified)
            assertEquals(mapOf("heart" to setOf("old")), store.reactions(ref(), "p"))
            assertFalse(assertNotNull(store.byRowId(ref(), rowId)).reactionsTruncated)
        }
    }

    @Test
    fun batchReactionHydrationIsScopedChunkedAndExcludesOrphansAndRedactedParents() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            store.record(message("p"))
            store.record(message("q"))
            store.record(message("p", "#other"))
            store.applyReaction(ref(), "p", "bob", "heart", true, 1)
            store.applyReaction(ref(), "q", "alice", "smile", true, 2)
            store.applyReaction(ref("#other"), "p", "foreign", "heart", true, 3)
            store.applyReaction(ref(), "orphan", "bob", "heart", true, 4)
            val ids = mutableListOf("p", "orphan")
            repeat(501) { ids.add("missing-$it") }
            ids.add("q")
            ids.add("p")
            assertEquals(mapOf("p" to mapOf("heart" to setOf("bob")), "q" to mapOf("smile" to setOf("alice"))), store.reactions(ref(), ids))
            store.redact(ref(), "q")
            assertEquals(mapOf("p" to mapOf("heart" to setOf("bob"))), store.reactions(ref(), ids))
            assertTrue(store.reactions(ref(), emptyList<String>()).isEmpty())
        }
    }
}

private inline fun <T> YardhalDatabase.use(block: (YardhalDatabase) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
