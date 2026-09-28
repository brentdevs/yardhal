package dev.brentdevs.yardhal.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageSearchTests {

    private fun newStore(): MessageStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return MessageStore(YardhalDatabase.inMemory(context).messageDao())
    }

    private fun message(
        conversation: String,
        sender: String,
        text: String,
        timestampMs: Long,
        networkId: String = "n1",
    ): StoredMessage =
        StoredMessage(
            networkId = networkId,
            conversation = ConversationRef.channel(networkId, conversation),
            msgid = null,
            senderNick = sender,
            senderUser = null,
            senderHost = null,
            kind = MessageKind.PRIVMSG,
            text = text,
            sentByUs = false,
            timestampMs = timestampMs,
        )

    @Test
    fun findsMessagesByTermWithHighlightedSnippet() = runBlocking {
        val store = newStore()
        store.record(message("#room", "alice", "the deploy is going out today", 1000))
        store.record(message("#room", "bob", "unrelated chatter", 2000))
        store.record(message("#other", "carol", "deploy the other thing", 3000))

        val hits = store.search("deploy")
        assertEquals(2, hits.size, hits.toString())
        assertTrue(hits.all { it.networkId == "n1" })
        assertEquals(setOf("#other", "#room"), hits.map { it.conversation }.toSet())
        assertTrue(
            hits.any { it.snippet.contains("[deploy]", ignoreCase = true) },
            "snippet must come from the message body, got: " + hits.joinToString { it.snippet },
        )
    }

    @Test
    fun multiTermSearchIsConjunctive() = runBlocking {
        val store = newStore()
        store.record(message("#room", "alice", "deploy is today", 1000))
        store.record(message("#room", "bob", "deploy was yesterday", 2000))

        assertEquals(1, store.search("deploy today").size)
        assertEquals(2, store.search("deploy").size)
    }

    @Test
    fun deletingNetworkPurgesSearchIndex() = runBlocking {
        val store = newStore()
        store.record(message("#room", "alice", "secret deploy plan", 1000))
        assertEquals(1, store.search("secret").size)

        store.deleteNetwork("n1")
        assertTrue(store.search("secret").isEmpty())
    }

    @Test
    fun emptyQueryReturnsNothing() = runBlocking {
        val store = newStore()
        store.record(message("#room", "alice", "hello", 1000))
        assertTrue(store.search("  ").isEmpty())
    }

    @Test
    fun searchScopesBeforeApplyingResultLimit() = runBlocking {
        val store = newStore()
        store.record(message("#room", "alice", "deploy in room", 1000))
        repeat(55) { index ->
            store.record(message("#other", "bob", "deploy elsewhere $index", 2000L + index))
        }
        store.record(message("#room", "carol", "deploy on another network", 3000, networkId = "n2"))

        val hits = store.search("deploy", limit = 1, networkId = "n1", conversation = "#room")
        assertEquals(1, hits.size)
        assertEquals("n1", hits.single().networkId)
        assertEquals("#room", hits.single().conversation)
    }

    @Test
    fun contextAroundSearchHitIncludesExactRowAndNeighbors() = runBlocking {
        val store = newStore()
        val ref = ConversationRef.channel("n1", "#room")
        store.record(message("#room", "alice", "before", 1000))
        store.record(message("#room", "alice", "target deploy", 1000))
        store.record(message("#room", "alice", "after", 1000))
        store.record(message("#other", "bob", "unrelated", 1000))

        val hit = store.search("deploy").single()
        val context = store.around(ref, hit.rowId, hit.timestampMs)
        assertEquals(listOf("before", "target deploy", "after"), context.map { it.text })
        assertEquals(hit.rowId, context[1].rowId)
    }
}
