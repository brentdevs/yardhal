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
class DurableMessageTests {
    private val ref = ConversationRef.channel("n1", "#room")

    private fun message(id: String?, time: Long = 1000, text: String = "message $id") = StoredMessage(
        networkId = ref.networkId, conversation = ref, msgid = id, senderNick = "alice", senderUser = "u",
        senderHost = "h", kind = MessageKind.PRIVMSG, text = text, sentByUs = false, timestampMs = time,
    )

    @Test
    fun reopenedStoreRestoresReactionsRepliesAttachmentsAndSenderState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "durable-interactions-test.db"
        context.deleteDatabase(name)
        try {
            val (expected, parentId) = YardhalDatabase.build(context, name).use { db ->
                val store = MessageStore(db.messageDao())
                store.applyReaction(ref, "parent", "bob", "heart", true, 1000)
                val parentId = assertNotNull(store.recordWithRowId(message("parent")))
                val reply = message("reply", 2000).copy(
                    highlightsMe = true, replyToMsgid = "parent", attachmentUrl = "https://example.test/file",
                    attachmentName = "image.png", attachmentMimeType = "image/png", attachmentSizeBytes = 123,
                    attachmentWidth = 640, attachmentHeight = 480, senderAccount = "account",
                )
                val id = assertNotNull(store.recordWithRowId(reply))
                val expected = reply.copy(rowId = id, replyParentRowId = parentId)
                assertEquals(expected, store.byRowId(ref, id))
                expected to parentId
            }
            YardhalDatabase.build(context, name).use { db ->
                val store = MessageStore(db.messageDao())
                assertEquals(expected, store.recent(ref, 1).single())
                assertEquals(mapOf("parent" to mapOf("heart" to setOf("bob"))), store.reactions(ref))
                assertEquals(mapOf("heart" to setOf("bob")), store.reactions(ref, "parent"))
                assertEquals(parentId, store.replyParents(ref, listOf(expected))[expected.rowId]?.rowId)
                assertEquals(UnreadCounts(2, 1), store.unreadCounts(ref, ReadCursor()))
                store.applyReaction(ref, "parent", "bob", "heart", false, 3000)
                assertTrue(store.reactions(ref).isEmpty())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun identicalFreshLocalSendsKeepDistinctRowsAndQuoteParentsAcrossCanonicalEchoes() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val parent = assertNotNull(store.recordWithRowId(message("parent")))
            val outgoing = message(null, 1000, "same body").copy(sentByUs = true, replyParentRowId = parent)
            val first = assertNotNull(store.recordLocalEcho(outgoing))
            val second = assertNotNull(store.recordLocalEcho(outgoing))
            assertTrue(first != second)
            assertEquals(second, store.healEcho(ref, second, outgoing))
            assertEquals(first, store.healEcho(ref, first, outgoing))
            assertEquals(first, store.healEcho(ref, first, outgoing.copy(msgid = "first")))
            assertEquals(second, store.healEcho(ref, second, outgoing.copy(msgid = "second")))
            assertEquals(listOf(first, second), store.recent(ref, 10).filter { it.sentByUs }.map { it.rowId })
            assertEquals(listOf(parent, parent), store.recent(ref, 10).filter { it.sentByUs }.map { it.replyParentRowId })
            assertEquals(setOf(first, second), store.search("same body").map { it.rowId }.toSet())
        }
    }

    @Test
    fun replyBeforeParentAndEchoHealingKeepLocalQuoteIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val replyId = assertNotNull(store.recordWithRowId(message("reply").copy(replyToMsgid = "wire")))
            val echo = message(null, 1000, "optimistic").copy(sentByUs = true)
            val echoId = assertNotNull(store.recordWithRowId(echo))
            val localId = assertNotNull(store.recordWithRowId(message("local", 2000).copy(replyParentRowId = echoId)))
            store.applyReaction(ref, "wire", "bob", "heart", true, 1000)
            assertTrue(store.reactions(ref, "wire").isEmpty())
            val healed = echo.copy(msgid = "wire", text = "server text", timestampMs = 1500, senderAccount = "own")
            assertEquals(echoId, store.healEcho(ref, echoId, healed))
            assertEquals(echoId, store.byRowId(ref, replyId)?.replyParentRowId)
            assertEquals(echoId, store.byRowId(ref, localId)?.replyParentRowId)
            assertEquals(setOf("bob"), store.reactions(ref)["wire"]?.get("heart"))
            assertEquals(mapOf("heart" to setOf("bob")), store.reactions(ref, "wire"))
            assertEquals(listOf(echoId), store.search("server text").map { it.rowId })
            assertTrue(store.search("optimistic").isEmpty())
        }
    }

    @Test
    fun echoCollisionMergesIntoIdentifiedRowAndRemapsQuotes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val retainedId = assertNotNull(store.recordWithRowId(message("wire", 1500, "confirmed")))
            val echo = message(null, 1000, "optimistic").copy(sentByUs = true, attachmentUrl = "https://example.test/file")
            val echoId = assertNotNull(store.recordWithRowId(echo))
            val replyId = assertNotNull(store.recordWithRowId(message("reply", 2000).copy(replyParentRowId = echoId)))
            assertEquals(retainedId, store.healEcho(ref, echoId, echo.copy(msgid = "wire", timestampMs = 1500, text = "confirmed")))
            assertNull(store.byRowId(ref, echoId))
            assertEquals(retainedId, store.byRowId(ref, replyId)?.replyParentRowId)
            assertEquals("https://example.test/file", store.byRowId(ref, retainedId)?.attachmentUrl)
            assertTrue(store.search("optimistic").isEmpty())
        }
    }

    @Test
    fun distinctIdentifiedCanonicalMessagesRemainInsertableAfterSiblingRedaction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val first = message("first", 1000, "identical body")
            val firstId = assertNotNull(store.recordWithRowId(first))
            store.redact(ref, "first", 1000)
            store.applyReaction(ref, "second", "bob", "heart", true, 1000)
            val second = first.copy(msgid = "second", attachmentUrl = "https://example.test/second")
            val secondId = assertNotNull(store.recordWithRowId(second))
            assertTrue(assertNotNull(store.byRowId(ref, firstId)).redacted)
            assertEquals(second.copy(rowId = secondId), store.byRowId(ref, secondId))
            assertEquals(mapOf("heart" to setOf("bob")), store.reactions(ref, "second"))
            assertEquals(listOf(secondId), store.search("identical body").map { it.rowId })
            assertFalse(store.record(first.copy(msgid = null)))
        }
    }

    @Test
    fun renamePreservesDistinctIdentifiedCanonicalSiblingBodyAndInteractions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val first = message("first", 1000, "identical body")
            val firstId = assertNotNull(store.recordWithRowId(first))
            val second = first.copy(msgid = "second", attachmentUrl = "https://example.test/second")
            val secondId = assertNotNull(store.recordWithRowId(second))
            store.applyReaction(ref, "second", "bob", "heart", true, 1000)
            store.redact(ref, "first", 1000)
            val target = ConversationRef.channel("n1", "#renamed")
            assertEquals(2, store.renameConversation(ref, target))
            assertTrue(assertNotNull(store.byRowId(target, firstId)).redacted)
            assertEquals(second.copy(rowId = secondId, conversation = target), store.byRowId(target, secondId))
            assertEquals(mapOf("heart" to setOf("bob")), store.reactions(target, "second"))
            assertEquals(listOf(secondId), store.search("identical body").map { it.rowId })
        }
    }

    @Test
    fun laterTrimRefreshesRedactionAliasesForFullRemovalHorizonAcrossRename() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val day = 24L * 60 * 60 * 1000
            val redactedAt = 50_000L
            val trimmedAt = redactedAt + 29 * day
            val original = message("redacted", 1000, "original body")
            store.record(original)
            store.redact(ref, "redacted", redactedAt)
            store.trimTo(ref, 0, trimmedAt)
            store.maintain(redactedAt + 31 * day)
            assertTrue(db.messageDao().tombstones("n1", "#room").all { it.observedAtMs == trimmedAt })
            assertFalse(store.record(original))
            assertFalse(store.record(original.copy(msgid = null)))
            val target = ConversationRef.channel("n1", "#renamed")
            store.renameConversation(ref, target)
            assertFalse(store.record(original.copy(conversation = target)))
            assertFalse(store.record(original.copy(conversation = target, msgid = null)))
            store.maintain(trimmedAt + MESSAGE_INTERACTION_RETENTION_MS)
            assertFalse(store.record(original.copy(conversation = target)))
            store.maintain(trimmedAt + MESSAGE_INTERACTION_RETENTION_MS + 1)
            assertTrue(db.messageDao().tombstones(target.networkId, target.normalizedTarget).isEmpty())
        }
    }

    @Test
    fun renameTombstoneCollisionNeverRegressesNewestRemovalObservation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val target = ConversationRef.channel("n1", "#renamed")
            val day = 24L * 60 * 60 * 1000
            val firstRemovalAt = 50_000L
            val newestRemovalAt = firstRemovalAt + 29 * day
            store.redact(ref, "future", firstRemovalAt)
            store.redact(target, "future", newestRemovalAt)
            store.renameConversation(ref, target)
            assertEquals(newestRemovalAt, db.messageDao().tombstones("n1", "#renamed").single().observedAtMs)
            val newerOrigin = ConversationRef.channel("n1", "#newer")
            store.redact(newerOrigin, "future", newestRemovalAt + day)
            store.renameConversation(newerOrigin, target)
            assertEquals(newestRemovalAt + day, db.messageDao().tombstones("n1", "#renamed").single().observedAtMs)
            store.maintain(firstRemovalAt + 31 * day)
            assertFalse(store.record(message("future").copy(conversation = target)))
        }
    }

    @Test
    fun redactionBeforeParentAndAfterRenameNeverResurrectsBodyOrInteraction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            store.redact(ref, "not-yet", 1000)
            store.applyReaction(ref, "not-yet", "bob", "heart", true, 2000)
            assertFalse(store.record(message("not-yet")))
            val id = assertNotNull(store.recordWithRowId(message("secret").copy(attachmentUrl = "https://example.test/secret")))
            val replyId = assertNotNull(store.recordWithRowId(message("reply", 2000).copy(replyParentRowId = id)))
            store.applyReaction(ref, "secret", "bob", "heart", true, 1000)
            store.redact(ref, "secret")
            val redacted = assertNotNull(store.byRowId(ref, id))
            assertTrue(redacted.redacted)
            assertEquals("", redacted.text)
            assertNull(redacted.attachmentUrl)
            assertEquals(id, store.byRowId(ref, replyId)?.replyParentRowId)
            val child = assertNotNull(store.byRowId(ref, replyId))
            assertTrue(store.replyParents(ref, listOf(child))[replyId]?.redacted == true)
            assertTrue(store.search("secret").isEmpty())
            val target = ConversationRef.channel("n1", "#new")
            store.renameConversation(ref, target)
            assertFalse(store.record(message("secret").copy(conversation = target)))
            assertTrue(store.reactions(target).isEmpty())
            assertTrue(store.byRowId(target, id)?.redacted == true)
        }
    }

    @Test
    fun renameCollisionUnionsReactionsAndPreservesRedactionPrecedence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val target = ConversationRef.channel("n1", "#new")
            val fromId = assertNotNull(store.recordWithRowId(message("shared")))
            val toId = assertNotNull(store.recordWithRowId(message("shared").copy(conversation = target)))
            val replyId = assertNotNull(store.recordWithRowId(message("reply", 2000).copy(replyParentRowId = fromId)))
            store.applyReaction(ref, "shared", "alice", "heart", true, 1000)
            store.applyReaction(target, "shared", "bob", "heart", true, 1000)
            val merges = mutableListOf<Pair<Long, Long>>()
            assertEquals(2, store.renameConversation(ref, target) { from, to -> merges.add(from to to) })
            assertEquals(listOf(fromId to toId), merges)
            assertEquals(toId, store.byRowId(target, replyId)?.replyParentRowId)
            assertEquals(setOf("alice", "bob"), store.reactions(target)["shared"]?.get("heart"))
            assertTrue(store.recent(ref, 10).isEmpty())
            store.redact(target, "shared")
            assertTrue(store.reactions(target).isEmpty())
            assertTrue(store.search("message shared").isEmpty())
        }
    }

    @Test
    fun aggregatesSpanUnloadedPagesAndUseEqualTimestampRowCursor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val first = assertNotNull(store.recordWithRowId(message("first").copy(highlightsMe = true)))
            val second = assertNotNull(store.recordWithRowId(message("second").copy(highlightsMe = true)))
            for (i in 1..150) store.record(message("later-$i", 1000 + i.toLong()))
            store.record(message("playback", 2000).copy(playback = true, highlightsMe = true))
            store.record(message("history", 2000).copy(historyContext = true, highlightsMe = true))
            store.record(message("sent", 2000).copy(sentByUs = true, highlightsMe = true))
            store.record(message("system", 2000).copy(kind = MessageKind.SYSTEM, highlightsMe = true))
            store.record(message("redacted", 2000).copy(highlightsMe = true))
            store.redact(ref, "redacted")
            assertEquals(10, store.recent(ref, 10).size)
            assertEquals(UnreadCounts(152, 2), store.unreadCounts(ref, ReadCursor()))
            assertEquals(UnreadCounts(151, 1), store.unreadCounts(ref, ReadCursor(1000, first)))
            assertEquals(UnreadCounts(150, 0), store.unreadCounts(ref, ReadCursor(1000, second)))
            assertEquals(ReadCursor(1150, 152), store.newestCursor(ref))
        }
    }

    @Test
    fun unknownReplayHighlightsNeverChangeKnownMentionObservations() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val nonmention = message("known-nonmention")
            val mention = message("known-mention", 2000).copy(highlightsMe = true)
            val unknown = message("unknown-guessed", 3000).copy(highlightsMe = true, highlightsKnown = false)
            store.record(nonmention)
            store.record(mention)
            store.record(unknown)
            assertEquals(UnreadCounts(3, 1, false), store.unreadCounts(ref, ReadCursor()))
            assertFalse(store.record(nonmention.copy(highlightsMe = true, highlightsKnown = false, playback = true)))
            assertFalse(store.record(mention.copy(highlightsMe = false, highlightsKnown = false, playback = true)))
            assertFalse(store.record(unknown.copy(playback = true)))
            assertFalse(assertNotNull(store.findStoredMessage(nonmention)).highlightsMe)
            assertTrue(assertNotNull(store.findStoredMessage(mention)).highlightsMe)
            assertFalse(assertNotNull(store.findStoredMessage(unknown)).highlightsKnown)
            assertEquals(UnreadCounts(3, 1, false), store.unreadCounts(ref, ReadCursor()))
            assertFalse(store.record(unknown.copy(highlightsKnown = true, highlightsMe = false)))
            assertFalse(assertNotNull(store.findStoredMessage(unknown)).highlightsMe)
            assertEquals(UnreadCounts(3, 1, true), store.unreadCounts(ref, ReadCursor()))
        }
    }

    @Test
    fun mentionCompletenessIncludesOnlyUnreadEligibleLegacyRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val unknown = message("unknown").copy(highlightsKnown = false)
            val unknownId = assertNotNull(store.recordWithRowId(unknown))
            store.record(message("known", 2000).copy(highlightsMe = true))
            store.record(message("sent-unknown", 3000).copy(sentByUs = true, highlightsKnown = false))
            store.record(message("history-unknown", 3000).copy(historyContext = true, highlightsKnown = false))
            store.record(message("playback-unknown", 3000).copy(playback = true, highlightsKnown = false))
            store.record(message("system-unknown", 3000).copy(kind = MessageKind.SYSTEM, highlightsKnown = false))
            store.record(message("redacted-unknown", 3000).copy(redacted = true, highlightsKnown = false))
            assertEquals(UnreadCounts(2, 1, false), store.unreadCounts(ref, ReadCursor()))
            assertEquals(UnreadCounts(1, 1, true), store.unreadCounts(ref, ReadCursor(1000, unknownId)))
            assertEquals(UnreadCounts(), store.unreadCounts(ref, ReadCursor(3000, Long.MAX_VALUE)))
            assertTrue(store.recent(ref, 20).any { it.msgid == "unknown" && !it.highlightsKnown })
        }
    }

    @Test
    fun trimCleansFtsQuotesReactionsAndRejectsBoundaryReplaysButAllowsNewEqualTimeMessages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val first = message(null, 1000, "trimmedbody")
            val id = assertNotNull(store.recordWithRowId(first))
            store.record(message("same-time", 1000))
            val replyId = assertNotNull(store.recordWithRowId(message("reply", 2000).copy(replyParentRowId = id)))
            store.applyReaction(ref, "same-time", "bob", "heart", true, 1000)
            store.trimTo(ref, 1)
            assertEquals(listOf(replyId), store.recent(ref, 10).map { it.rowId })
            assertTrue(store.search("trimmedbody").isEmpty())
            assertNull(store.byRowId(ref, replyId)?.replyParentRowId)
            assertFalse(store.record(first))
            assertFalse(store.record(message("same-time", 1000)))
            store.applyReaction(ref, "same-time", "bob", "heart", true, 2000)
            assertTrue(store.reactions(ref).isEmpty())
            assertTrue(store.record(message("new-equal-time", 1000)))
            val target = ConversationRef.channel("n1", "#new")
            store.renameConversation(ref, target)
            assertFalse(store.record(first.copy(conversation = target)))
            store.trimTo(target, 0)
            assertTrue(store.recent(target, 10).isEmpty())
            assertTrue(store.search("reply").isEmpty())
        }
    }

    @Test
    fun orphanRetentionBoundAndNetworkDeletionIncludeBeforeParentState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            for (i in 0..1000) store.applyReaction(ref, "orphan-$i", "bob", "heart", true, i.toLong())
            assertEquals(1000, db.messageDao().reactions("n1", "#room").size)
            assertFalse(db.messageDao().reactions("n1", "#room").any { it.msgid == "orphan-0" })
            store.maintain(30L * 24 * 60 * 60 * 1000 + 1001)
            assertTrue(store.reactions(ref).isEmpty())
            store.applyReaction(ref, "future", "bob", "heart", true, 1000)
            store.redact(ref, "gone", 1000)
            store.record(message("present"))
            store.deleteNetwork("n1")
            assertTrue(db.messageDao().reactions("n1", "#room").isEmpty())
            assertTrue(db.messageDao().tombstones("n1", "#room").isEmpty())
            assertTrue(store.search("present").isEmpty())
            assertTrue(store.record(message("gone")))
        }
    }

    @Test
    fun repeatedEqualTimeTrimBoundsLedgerAndSeparatesLiveFromHistoricalBoundary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val removalsAt = 50_000L
            val other = ConversationRef.channel("n1", "#other")
            store.redact(other, "other-identity", removalsAt)
            repeat(35) { batch ->
                repeat(100) { index ->
                    assertTrue(store.record(message("boundary-$batch-$index", 1000)))
                }
                store.trimTo(ref, 1, removalsAt + batch)
            }
            assertEquals(MESSAGE_TOMBSTONE_LIMIT, db.messageDao().tombstones(ref.networkId, ref.normalizedTarget).size)
            assertEquals(1, db.messageDao().tombstones(other.networkId, other.normalizedTarget).size)
            assertFalse(store.record(message("boundary-0-0", 1000).copy(historyContext = true)))
            assertFalse(store.record(message("unknown-history", 1000).copy(historyContext = true)))
            assertFalse(store.record(message("unknown-playback", 1000).copy(playback = true)))
            assertFalse(store.record(message("older-live", 999)))
            assertTrue(store.record(message("valid-live", 1000)))
            val retained = store.recent(ref, 1).single()
            assertFalse(store.record(retained.copy(historyContext = true)))
            assertEquals(retained.rowId, store.findStoredMessage(retained)?.rowId)
            store.maintain(removalsAt + 34 + MESSAGE_INTERACTION_RETENTION_MS)
            assertTrue(db.messageDao().tombstones(ref.networkId, ref.normalizedTarget).isNotEmpty())
            store.maintain(removalsAt + 35 + MESSAGE_INTERACTION_RETENTION_MS)
            assertTrue(db.messageDao().tombstones(ref.networkId, ref.normalizedTarget).isEmpty())
            assertFalse(store.record(message("boundary-0-0", 1000).copy(historyContext = true)))
        }
    }

    @Test
    fun redactionIdentityWindowUsesRemovalTimeNotOldServerTimestamp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val old = message("old", 1)
            store.record(old)
            store.redact(ref, "old", 50_000)
            store.maintain(50_000 + MESSAGE_INTERACTION_RETENTION_MS)
            assertTrue(db.messageDao().tombstones(ref.networkId, ref.normalizedTarget).any { it.identity == "msgid:old" })
            assertFalse(store.record(old.copy(timestampMs = 100_000)))
            store.maintain(50_001 + MESSAGE_INTERACTION_RETENTION_MS)
            assertTrue(db.messageDao().tombstones(ref.networkId, ref.normalizedTarget).isEmpty())
            assertFalse(store.record(old))
            assertTrue(store.recent(ref, 10).single().redacted)
        }
    }

    @Test
    fun failedFtsWriteRollsBackMessageAndRenamePublishesNoMergeCallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            db.openHelper.writableDatabase.execSQL("DROP TABLE message_fts")
            assertTrue(runCatching { store.record(message("failure")) }.isFailure)
            assertTrue(db.messageDao().allRows().isEmpty())
        }
        YardhalDatabase.inMemory(context).use { db ->
            val store = MessageStore(db.messageDao())
            val target = ConversationRef.channel("n1", "#new")
            store.record(message("shared"))
            store.record(message("shared").copy(conversation = target))
            db.openHelper.writableDatabase.execSQL("DROP TABLE message_fts")
            var notified = false
            assertTrue(runCatching { store.renameConversation(ref, target) { _, _ -> notified = true } }.isFailure)
            assertFalse(notified)
            assertEquals(1, store.recent(ref, 10).size)
            assertEquals(1, store.recent(target, 10).size)
        }
    }
}

private inline fun <T> YardhalDatabase.use(block: (YardhalDatabase) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
