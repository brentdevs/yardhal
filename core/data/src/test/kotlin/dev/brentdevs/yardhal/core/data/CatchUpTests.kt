package dev.brentdevs.yardhal.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import java.io.File
import java.nio.file.Files
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
class CatchUpTests {
    private val ref = ConversationRef.channel("n", "#room")

    private fun message(
        id: String,
        time: Long = 1000,
        conversation: ConversationRef = ref,
        text: String = "message $id",
    ): StoredMessage = StoredMessage(
        networkId = conversation.networkId, conversation = conversation, msgid = id,
        senderNick = "alice", senderUser = null, senderHost = null, kind = MessageKind.PRIVMSG,
        text = text, sentByUs = false, timestampMs = time,
    )

    private suspend fun withStore(block: suspend (MessageStore, File) -> Unit) {
        val directory = Files.createTempDirectory("catch-up-test").toFile()
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            YardhalDatabase.inMemory(context).use { block(MessageStore(it.messageDao()), directory) }
        } finally {
            directory.deleteRecursively()
        }
    }

    private class Consumer(store: MessageStore, directory: File, gaps: List<StoredHistoryGap> = emptyList()) {
        val markers = ReadMarkerStore(directory)
        val jumps = mutableListOf<StoredMessage>()
        val reads = mutableListOf<Pair<ConversationRef, ReadCursor>>()
        val controller = CatchUpController(
            store, CatchUpStore(directory), { markers.cursor(it.storageKey) },
            { gaps },
            { jumps.add(it) },
            { conversation, cursor ->
                markers.advance(conversation.storageKey, cursor.timestampMs, cursor.rowId)
                reads.add(conversation to cursor)
            },
        )
    }

    @Test
    fun durableQueriesPageEveryTieAndIncludeRecentReactionsOnOldParents() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "catch-up-retained-test.db"
        context.deleteDatabase(name)
        try {
            val oldId = YardhalDatabase.build(context, name).use { db ->
                val store = MessageStore(db.messageDao())
                val oldId = assertNotNull(store.recordWithRowId(message("old", 1).copy(sentByUs = true)))
                repeat(205) { store.record(message("tie-$it")) }
                store.record(message("outside", 5000, ConversationRef.channel("other", "#room")))
                store.record(message("context", 5000).copy(historyContext = true))
                store.record(message("redacted", 5000).copy(redacted = true))
                store.applyReaction(ref, "old", "bob", "👍", true, nowMs = 9000)
                oldId
            }
            YardhalDatabase.build(context, name).use { db ->
                val store = MessageStore(db.messageDao())
                val first = store.catchUpPage(listOf("n"))
                assertEquals(200, first.candidates.size)
                assertEquals(oldId, first.candidates.first().message.rowId)
                assertEquals(9000L, first.candidates.first().activityTimestampMs)
                assertEquals("bob", first.candidates.first().reactions.single().sender)
                assertEquals(206L, first.scopes.sumOf { it.retainedCount })
                assertTrue(first.hasMore)
                val second = store.catchUpPage(listOf("n"), assertNotNull(first.next))
                assertEquals(6, second.candidates.size)
                assertFalse(second.hasMore)
                assertEquals(206, (first.candidates + second.candidates).map { it.message.rowId }.toSet().size)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun precedenceDoesNotDiscardOverlappingFiltersAndPlaybackIsNotUnread() = runBlocking {
        withStore { store, directory ->
            val dm = ConversationRef.directMessage("n", "alice")
            val parent = assertNotNull(store.recordWithRowId(message("parent", 1, dm).copy(sentByUs = true)))
            store.record(message("reply", 1000, dm).copy(replyParentRowId = parent, highlightsMe = true))
            store.record(message("historical", 2000).copy(playback = true, highlightsMe = true))
            store.record(message("unknown", 3000).copy(highlightsMe = true, highlightsKnown = false))
            store.applyReaction(dm, "parent", "bob", "❤", true, nowMs = 4000)
            val consumer = Consumer(store, directory)
            consumer.controller.refresh(listOf("n"))
            val all = consumer.controller.state.value.groups.flatMap { it.activities }
            val reply = all.single { it.message.msgid == "reply" }
            assertEquals(CatchUpReason.MENTION, reply.reason)
            assertEquals(setOf(CatchUpFilter.MENTIONS, CatchUpFilter.DIRECT_MESSAGES, CatchUpFilter.REPLIES, CatchUpFilter.UNREAD), reply.filters)
            assertFalse(CatchUpFilter.UNREAD in all.single { it.message.msgid == "historical" }.filters)
            assertFalse(CatchUpFilter.MENTIONS in all.single { it.message.msgid == "unknown" }.filters)
            for (filter in listOf(CatchUpFilter.MENTIONS, CatchUpFilter.DIRECT_MESSAGES, CatchUpFilter.REPLIES, CatchUpFilter.UNREAD)) {
                consumer.controller.setFilter(filter)
                assertTrue(consumer.controller.state.value.groups.flatMap { it.activities }.any { it.anchor == reply.anchor })
            }
            consumer.controller.setFilter(CatchUpFilter.REACTIONS)
            assertEquals(listOf("parent"), consumer.controller.state.value.groups.flatMap { it.activities }.map { it.message.msgid })
            assertEquals(1, consumer.controller.state.value.coverage.unknownMentionMessages)
        }
    }

    @Test
    fun linkDedupKeepsEveryExactSourceAndDoesNotEraseMeaningfulUrlDifferences() = runBlocking {
        withStore { store, directory ->
            val first = assertNotNull(store.recordWithRowId(message("first", text = "HTTPS://EXAMPLE.COM:443/a?q=1#one https://example.com/a?q=1#one")))
            val second = assertNotNull(store.recordWithRowId(message("second", 2000, text = "https://example.com/a?q=1#one https://example.com/a?q=2#one https://example.com/a?q=1#two")))
            store.record(message("unsafe", 3000, text = "https://user:secret@example.com/private javascript:alert(1)"))
            val consumer = Consumer(store, directory)
            consumer.controller.refresh(listOf("n"))
            val links = consumer.controller.state.value.links
            assertEquals(3, links.size)
            val duplicate = links.single { it.canonicalUrl == "https://example.com/a?q=1#one" }
            assertEquals(listOf(second, first), duplicate.occurrences.map { it.anchor.rowId })
            assertTrue(consumer.controller.jump(duplicate.occurrences.last().anchor))
            assertEquals(first, consumer.jumps.single().rowId)
            assertEquals("first", consumer.jumps.single().msgid)
            consumer.controller.markRead(duplicate.occurrences.last().anchor)
            assertEquals(ReadCursor(1000, first), consumer.markers.cursor(ref.storageKey))
        }
        assertEquals("https://example.com/", canonicalCatchUpUrl("HTTPS://EXAMPLE.COM:443"))
        assertEquals("https://example.com/a%2Fb", canonicalCatchUpUrl("https://example.com/a%2fb"))
        assertNull(canonicalCatchUpUrl("https://user:password@example.com/"))
        assertNull(canonicalCatchUpUrl("https://example.com/\n"))
        assertNull(canonicalCatchUpUrl("file:///tmp/secret"))
        assertFalse(canonicalCatchUpUrl("https://example.com/a%2Fb") == canonicalCatchUpUrl("https://example.com/a/b"))
    }

    @Test
    fun coverageReportsActualQueryBoundsNotFilteredResultsOrServerCompleteness() = runBlocking {
        withStore { store, directory ->
            repeat(205) { store.record(message("item-$it", it.toLong() + 1)) }
            val consumer = Consumer(store, directory, listOf(StoredHistoryGap("gap", 0, toTimestampMs = 1)))
            consumer.controller.refresh(listOf("n"))
            consumer.controller.setFilter(CatchUpFilter.REACTIONS)
            assertTrue(consumer.controller.state.value.groups.isEmpty())
            val first = consumer.controller.state.value.coverage
            assertEquals(200, first.queriedMessages)
            assertEquals(205L, first.retainedMessages)
            assertEquals(6L, first.queriedOldestTimestampMs)
            assertEquals(205L, first.queriedNewestTimestampMs)
            assertEquals(1, first.knownHistoryGaps)
            assertTrue(first.hasMore)
            assertTrue(first.label.contains("More retained messages remain"))
            assertTrue(first.label.contains("does not establish complete server history"))
            consumer.controller.loadMore()
            val second = consumer.controller.state.value.coverage
            assertEquals(205, second.queriedMessages)
            assertEquals(1L, second.queriedOldestTimestampMs)
            assertFalse(second.hasMore)
            assertEquals(1, second.knownHistoryGaps)
            assertTrue(second.label.contains("known server-history gaps"))
        }
    }

    @Test
    fun dismissalsSurviveRestartAndNewReactionActivityCanReappear() = runBlocking {
        withStore { store, directory ->
            store.record(message("mine").copy(sentByUs = true))
            store.applyReaction(ref, "mine", "bob", "👍", true, nowMs = 2000)
            val first = Consumer(store, directory)
            first.controller.refresh(listOf("n"))
            val anchor = first.controller.state.value.groups.single().activities.single().anchor
            first.controller.dismiss(anchor)
            assertTrue(first.controller.state.value.groups.isEmpty())
            assertEquals(ReadCursor(), first.markers.cursor(ref.storageKey))
            val second = Consumer(store, directory)
            second.controller.refresh(listOf("n"))
            assertTrue(second.controller.state.value.groups.isEmpty())
            store.applyReaction(ref, "mine", "carol", "❤", true, nowMs = 3000)
            second.controller.reload()
            assertEquals(anchor, second.controller.state.value.groups.single().activities.single().anchor)
            second.controller.dismiss(anchor)
            second.controller.restoreDismissed()
            assertEquals(anchor, second.controller.state.value.groups.single().activities.single().anchor)
        }
    }

    @Test
    fun exactActionsFollowRowPreservingRenameAndRefuseRedactedAndPrunedAnchors() = runBlocking {
        withStore { store, directory ->
            val from = ConversationRef.directMessage("n", "[alice]", CaseMapping.ASCII)
            val to = ConversationRef.directMessage("n", "{alice}", CaseMapping.RFC1459)
            store.record(message("rename", 1000, from, "https://example.com/"))
            store.record(message("other", 2000))
            val consumer = Consumer(store, directory)
            consumer.controller.refresh(listOf("n"))
            val anchor = consumer.controller.state.value.groups.flatMap { it.activities }.single { it.message.msgid == "rename" }.anchor
            store.renameConversation(from, to)
            assertTrue(consumer.controller.jump(anchor))
            assertEquals(to.normalizedTarget, consumer.jumps.single().conversation.normalizedTarget)
            consumer.controller.markRead(anchor)
            assertEquals(ReadCursor(1000, anchor.rowId), consumer.markers.cursor(to.storageKey))
            assertEquals(ReadCursor(), consumer.markers.cursor(from.storageKey))
            store.redact(to, "rename")
            assertFalse(consumer.controller.jump(anchor))
            var opened: String? = null
            consumer.controller.openLink(anchor, "https://example.com/") { opened = it }
            assertNull(opened)
            assertEquals(1, consumer.jumps.size)
            assertNotNull(consumer.controller.state.value.error)
            consumer.controller.reload()
            val pruned = consumer.controller.state.value.groups.flatMap { it.activities }.single().anchor
            store.trimTo(ref, 0)
            assertFalse(consumer.controller.jump(pruned))
            consumer.controller.markRead(pruned)
            assertEquals(1, consumer.reads.size)
            consumer.controller.reload()
            assertEquals(0L, consumer.controller.state.value.coverage.retainedMessages)
            assertTrue(consumer.controller.state.value.coverage.prunedScopes > 0)
        }
    }

    @Test
    fun boundedViewStopsWithExplicitRemainingCoverageRatherThanClaimingAllRetainedHistory() = runBlocking {
        withStore { store, directory ->
            repeat(CATCH_UP_VISIBLE_LIMIT + 1) { store.record(message("bound-$it", it.toLong() + 1)) }
            val consumer = Consumer(store, directory)
            consumer.controller.refresh(listOf("n"))
            repeat(CATCH_UP_VISIBLE_LIMIT / CATCH_UP_PAGE_LIMIT) { consumer.controller.loadMore() }
            val coverage = consumer.controller.state.value.coverage
            assertEquals(CATCH_UP_VISIBLE_LIMIT, coverage.queriedMessages)
            assertEquals(CATCH_UP_VISIBLE_LIMIT.toLong() + 1, coverage.retainedMessages)
            assertTrue(coverage.hasMore)
            assertTrue(coverage.limitReached)
            assertTrue(coverage.label.contains("older retained messages are not shown"))
            consumer.controller.loadMore()
            assertEquals(coverage, consumer.controller.state.value.coverage)
            assertEquals(CATCH_UP_VISIBLE_LIMIT, consumer.controller.state.value.groups.sumOf { it.activities.size })
        }
    }

    @Test
    fun explicitReadAdvancesOnlyTheChosenTimestampTieAndPersistsWithoutJump() = runBlocking {
        withStore { store, directory ->
            val first = assertNotNull(store.recordWithRowId(message("first")))
            val second = assertNotNull(store.recordWithRowId(message("second")))
            val consumer = Consumer(store, directory)
            consumer.controller.refresh(listOf("n"))
            val anchor = consumer.controller.state.value.groups.single().activities.single { it.anchor.rowId == first }.anchor
            consumer.controller.markRead(anchor)
            assertEquals(ReadCursor(1000, first), ReadMarkerStore(directory).cursor(ref.storageKey))
            consumer.controller.setFilter(CatchUpFilter.UNREAD)
            assertEquals(listOf(second), consumer.controller.state.value.groups.single().activities.map { it.anchor.rowId })
            assertTrue(consumer.jumps.isEmpty())
            consumer.controller.jump(anchor)
            assertEquals(ReadCursor(1000, first), consumer.markers.cursor(ref.storageKey))
        }
    }
}

private inline fun <T> YardhalDatabase.use(block: (YardhalDatabase) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
