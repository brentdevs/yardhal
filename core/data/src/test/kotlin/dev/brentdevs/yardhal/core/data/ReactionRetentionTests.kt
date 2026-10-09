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
    fun networkOverflowRetainsPreferredConversationAndSenderIdentitiesAtEqualObservationTimes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val other = ref("#other")
            store.record(message("p"))
            store.record(message("q").copy(conversation = other))
            for (sender in listOf("alice", "bob")) {
                store.applyReaction(ref(), "p", sender, "heart", true, 100, perParentLimit = 10, networkLimit = 2)
            }
            for (sender in listOf("z", "a", "m")) {
                store.applyReaction(other, "q", sender, "heart", true, 100, perParentLimit = 10, networkLimit = 2)
            }
            assertTrue(store.reactions(ref(), "p").isEmpty())
            assertEquals(mapOf("heart" to setOf("a", "m")), store.reactions(other, "q"))
            assertTrue(store.recent(ref(), 1).single().reactionsTruncated)
            assertTrue(store.recent(other, 1).single().reactionsTruncated)
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
    fun networkOverflowAcrossMultipleVictimBatchesPublishesEveryAffectedParent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val first = assertNotNull(store.recordWithRowId(message("p")))
            val second = assertNotNull(store.recordWithRowId(message("q", "#other")))
            store.record(message("foreign", networkId = "n2"))
            db.messageDao().putReaction(MessageReactionRow("n2", "#room", "foreign", "heart", "untouched", 1))
            repeat(1200) { index ->
                val target = if (index % 2 == 0) "#room" else "#other"
                val msgid = if (index % 2 == 0) "p" else "q"
                db.messageDao().putReaction(MessageReactionRow("n1", target, msgid, "heart", "sender-$index", index.toLong()))
            }
            val changed = mutableSetOf<Long>()
            store.applyReaction(ref(), "p", "newest", "heart", true, 2000, perParentLimit = 2000, networkLimit = 100) {
                assertFalse(db.openHelper.writableDatabase.inTransaction())
                changed.addAll(it)
            }
            assertEquals(setOf(first, second), changed)
            assertEquals(100, db.messageDao().reactions("n1", "#room").size + db.messageDao().reactions("n1", "#other").size)
            assertEquals(100L, db.messageDao().reactionMembershipCount("n1"))
            assertEquals(1L, db.messageDao().reactionMembershipCount("n2"))
            assertTrue(assertNotNull(store.byRowId(ref(), first)).reactionsTruncated)
            assertTrue(assertNotNull(store.byRowId(ref("#other"), second)).reactionsTruncated)
            assertEquals((1101..1199).map { "sender-$it" }.toSet() + "newest",
                (store.reactions(ref(), "p")["heart"].orEmpty() + store.reactions(ref("#other"), "q")["heart"].orEmpty()))
            assertEquals(mapOf("heart" to setOf("untouched")), store.reactions(ref(networkId = "n2"), "foreign"))
        }
    }

    @Test
    fun parentOverflowAcrossMultipleBatchesKeepsOnlyNewestMemberships() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val parent = assertNotNull(store.recordWithRowId(message("p")))
            repeat(1200) { index ->
                db.messageDao().putReaction(MessageReactionRow("n1", "#room", "p", "heart", "sender-$index", index.toLong()))
            }
            store.applyReaction(ref(), "p", "newest", "heart", true, 2000, perParentLimit = 3, networkLimit = 5000)
            assertEquals(mapOf("heart" to setOf("sender-1198", "sender-1199", "newest")), store.reactions(ref(), "p"))
            assertEquals(3L, db.messageDao().reactionMembershipCount("n1"))
            assertTrue(assertNotNull(store.byRowId(ref(), parent)).reactionsTruncated)
        }
    }

    @Test
    fun individualReactionEventsDoNotRunGlobalOrphanAgeMaintenance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val orphan = MessageReactionRow("n2", "#room", "future", "heart", "bob", 1)
            db.messageDao().putReaction(orphan)
            store.record(message("p"))
            store.applyReaction(ref(), "p", "alice", "heart", true, MESSAGE_INTERACTION_RETENTION_MS + 2)
            assertEquals(listOf(orphan.copy(orphan = true)), db.messageDao().reactions("n2", "#room"))
            store.maintain(MESSAGE_INTERACTION_RETENTION_MS + 2)
            assertTrue(db.messageDao().reactions("n2", "#room").isEmpty())
            assertEquals(mapOf("heart" to setOf("alice")), store.reactions(ref(), "p"))
        }
    }

    @Test
    fun membershipCountsTrackDuplicateReplaceNetworkMoveRemovalAndDeletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val dao = db.messageDao()
            store.record(message("p"))
            store.record(message("p", networkId = "n2"))
            store.applyReaction(ref(), "p", "a", "heart", true, 1)
            store.applyReaction(ref(), "p", "b", "heart", true, 2)
            store.applyReaction(ref(networkId = "n2"), "p", "foreign", "heart", true, 1)
            assertEquals(2L, dao.reactionMembershipCount("n1"))
            assertEquals(1L, dao.reactionMembershipCount("n2"))
            store.applyReaction(ref(), "p", "a", "heart", true, 3)
            assertEquals(2L, dao.reactionMembershipCount("n1"))
            db.openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO message_reactions(networkId, conversation, msgid, emoji, sender, observedAtMs) " +
                    "VALUES('n1', '#room', 'p', 'heart', 'a', 4)",
            )
            assertEquals(2L, dao.reactionMembershipCount("n1"))
            assertEquals(mapOf("heart" to setOf("a", "b")), store.reactions(ref(), "p"))
            db.openHelper.writableDatabase.execSQL(
                "UPDATE message_reactions SET networkId = 'n2' WHERE networkId = 'n1' AND sender = 'b'",
            )
            assertEquals(1L, dao.reactionMembershipCount("n1"))
            assertEquals(2L, dao.reactionMembershipCount("n2"))
            store.applyReaction(ref(), "p", "a", "heart", false, 5)
            assertNull(dao.reactionMembershipCount("n1"))
            assertEquals(mapOf("heart" to setOf("b", "foreign")), store.reactions(ref(networkId = "n2"), "p"))
            store.applyReaction(ref(networkId = "n2"), "p", "newest", "heart", true, 6, networkLimit = 1)
            assertEquals(1L, dao.reactionMembershipCount("n2"))
            assertEquals(mapOf("heart" to setOf("newest")), store.reactions(ref(networkId = "n2"), "p"))
            store.applyReaction(ref(), "p", "retained", "heart", true, 7)
            store.deleteNetwork("n2")
            assertNull(dao.reactionMembershipCount("n2"))
            assertEquals(1L, dao.reactionMembershipCount("n1"))
            assertEquals(mapOf("heart" to setOf("retained")), store.reactions(ref(), "p"))
        }
    }

    @Test
    fun membershipCountsTrackRenameUnionAttachmentRedactionRetentionAndOrphanMaintenance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val dao = db.messageDao()
            val target = ref("#renamed")
            store.record(message("p"))
            store.record(message("p").copy(conversation = target))
            for (sender in listOf("shared", "old-only")) store.applyReaction(ref(), "p", sender, "heart", true, 1)
            for (sender in listOf("shared", "new-only")) store.applyReaction(target, "p", sender, "heart", true, 2)
            store.applyReaction(ref(), "future", "orphan", "heart", true, 3)
            assertEquals(5L, dao.reactionMembershipCount("n1"))
            assertEquals(1L, dao.orphanReactionCount())
            store.renameConversation(ref(), target)
            assertEquals(4L, dao.reactionMembershipCount("n1"))
            assertEquals(1L, dao.orphanReactionCount())
            assertEquals(mapOf("heart" to setOf("shared", "old-only", "new-only")), store.reactions(target, "p"))
            store.record(message("future").copy(conversation = target))
            assertEquals(4L, dao.reactionMembershipCount("n1"))
            assertEquals(0L, dao.orphanReactionCount())
            assertEquals(mapOf("heart" to setOf("orphan")), store.reactions(target, "future"))
            store.redact(target, "p", 4)
            assertEquals(1L, dao.reactionMembershipCount("n1"))
            store.trimTo(target, 0, 5)
            assertNull(dao.reactionMembershipCount("n1"))
            assertEquals(0L, dao.orphanReactionCount())
            store.applyReaction(target, "orphan-later", "orphan", "heart", true, 10)
            assertEquals(1L, dao.reactionMembershipCount("n1"))
            assertEquals(1L, dao.orphanReactionCount())
            store.maintain(MESSAGE_INTERACTION_RETENTION_MS + 11)
            assertNull(dao.reactionMembershipCount("n1"))
            assertEquals(0L, dao.orphanReactionCount())
            assertTrue(dao.reactions("n1", "#renamed").isEmpty())
        }
    }

    @Test
    fun reopenedMembershipCountsRemainAccurateWhenReplaceAndCapRemoveExistingMemberships() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "reaction-counts-reopen-test.db"
        context.deleteDatabase(name)
        try {
            YardhalDatabase.build(context, name).use { db ->
                val store = MessageStore(db.messageDao())
                store.record(message("p"))
                for ((index, sender) in listOf("a", "b", "c").withIndex()) {
                    store.applyReaction(ref(), "p", sender, "heart", true, index + 1L)
                }
                assertEquals(3L, db.messageDao().reactionMembershipCount("n1"))
            }
            YardhalDatabase.build(context, name).use { db ->
                val store = MessageStore(db.messageDao())
                assertEquals(3L, db.messageDao().reactionMembershipCount("n1"))
                db.openHelper.writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO message_reactions(networkId, conversation, msgid, emoji, sender, observedAtMs) " +
                        "VALUES('n1', '#room', 'p', 'heart', 'a', 1)",
                )
                assertEquals(3L, db.messageDao().reactionMembershipCount("n1"))
                store.applyReaction(ref(), "p", "newest", "heart", true, 4, networkLimit = 3)
                assertEquals(3L, db.messageDao().reactionMembershipCount("n1"))
                assertEquals(mapOf("heart" to setOf("b", "c", "newest")), store.reactions(ref(), "p"))
                store.trimTo(ref(), 0, 5)
                assertNull(db.messageDao().reactionMembershipCount("n1"))
                store.applyReaction(ref(), "future", "orphan", "heart", true, 6)
            }
            YardhalDatabase.build(context, name).use { db ->
                assertEquals(1L, db.messageDao().reactionMembershipCount("n1"))
                assertEquals(1L, db.messageDao().orphanReactionCount())
                MessageStore(db.messageDao()).deleteNetwork("n1")
                assertNull(db.messageDao().reactionMembershipCount("n1"))
                assertEquals(0L, db.messageDao().orphanReactionCount())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun eventPublicationCapsGlobalOrphansAcrossNetworksAndMultipleVictimBatches() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val dao = db.messageDao()
            repeat(2200) { index ->
                val network = if (index % 2 == 0) "n1" else "n2"
                dao.putReaction(MessageReactionRow(network, "#room", "future-$index", "heart", "bob", index.toLong()))
            }
            store.record(message("attached", networkId = "n3"))
            store.applyReaction(ref(networkId = "n3"), "attached", "bob", "heart", true, 3000)
            assertEquals(1000L, dao.orphanReactionCount())
            assertEquals(500L, dao.reactionMembershipCount("n1"))
            assertEquals(500L, dao.reactionMembershipCount("n2"))
            assertEquals(1L, dao.reactionMembershipCount("n3"))
            val remaining = dao.reactions("n1", "#room") + dao.reactions("n2", "#room")
            assertEquals((1200..2199).map { "future-$it" }.toSet(), remaining.map { it.msgid }.toSet())
            assertTrue(remaining.all { it.orphan })
            store.record(message("future-2198"))
            store.record(message("future-2199", networkId = "n2"))
            assertEquals(998L, dao.orphanReactionCount())
            store.maintain(MESSAGE_INTERACTION_RETENTION_MS + 3001)
            assertEquals(0L, dao.orphanReactionCount())
            for (network in listOf("n1", "n2", "n3")) assertEquals(1L, dao.reactionMembershipCount(network))
            assertEquals(mapOf("heart" to setOf("bob")), store.reactions(ref(), "future-2198"))
            assertEquals(mapOf("heart" to setOf("bob")), store.reactions(ref(networkId = "n2"), "future-2199"))
        }
    }

    @Test
    fun orphanCountersFollowCanonicalPromotionConversationMovesAndParentDeletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val dao = db.messageDao()
            store.applyReaction(ref(), "p", "bob", "heart", true, 1)
            store.applyReaction(ref(), "q", "bob", "heart", true, 2)
            assertEquals(2L, dao.orphanReactionCount())
            val outgoing = message("optimistic").copy(msgid = null, sentByUs = true, pendingEcho = true)
            val echoId = assertNotNull(store.recordLocalEcho(outgoing))
            store.healEcho(ref(), echoId, outgoing.copy(msgid = "p", pendingEcho = false))
            assertEquals(1L, dao.orphanReactionCount())
            val target = ref("#renamed")
            store.renameConversation(ref(), target)
            assertEquals(1L, dao.orphanReactionCount())
            assertEquals(2L, dao.reactionMembershipCount("n1"))
            assertEquals(mapOf("heart" to setOf("bob")), store.reactions(target, "p"))
            val qId = assertNotNull(store.recordWithRowId(message("q").copy(conversation = target)))
            assertEquals(0L, dao.orphanReactionCount())
            dao.deleteRow(qId)
            assertEquals(1L, dao.orphanReactionCount())
            assertTrue(store.reactions(target, "q").isEmpty())
            store.record(message("q").copy(conversation = target))
            assertEquals(0L, dao.orphanReactionCount())
            store.redact(target, "p", 3)
            assertEquals(1L, dao.reactionMembershipCount("n1"))
            store.trimTo(target, 0, 4)
            assertNull(dao.reactionMembershipCount("n1"))
            assertEquals(0L, dao.orphanReactionCount())
        }
    }

    @Test
    fun orphanReplacementAndReactionIdentityMovesDoNotDriftGlobalCounter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val dao = db.messageDao()
            store.applyReaction(ref(), "future", "bob", "heart", true, 1)
            db.openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO message_reactions(networkId, conversation, msgid, emoji, sender, observedAtMs) " +
                    "VALUES('n1', '#room', 'future', 'heart', 'bob', 2)",
            )
            assertEquals(1L, dao.orphanReactionCount())
            assertEquals(1L, dao.reactionMembershipCount("n1"))
            store.record(message("future", networkId = "n2"))
            db.openHelper.writableDatabase.execSQL(
                "UPDATE message_reactions SET networkId = 'n2' WHERE networkId = 'n1'",
            )
            assertEquals(0L, dao.orphanReactionCount())
            assertNull(dao.reactionMembershipCount("n1"))
            assertEquals(1L, dao.reactionMembershipCount("n2"))
            assertEquals(mapOf("heart" to setOf("bob")), store.reactions(ref(networkId = "n2"), "future"))
            db.openHelper.writableDatabase.execSQL(
                "UPDATE message_reactions SET conversation = '#missing' WHERE networkId = 'n2'",
            )
            assertEquals(1L, dao.orphanReactionCount())
            store.deleteNetwork("n2")
            assertEquals(0L, dao.orphanReactionCount())
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
            assertEquals(1L, db.messageDao().reactionMembershipCount("n1"))
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
