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

    private fun message(conversation: String, sender: String, text: String, timestampMs: Long): StoredMessage =
        StoredMessage(
            networkId = "n1",
            conversation = ConversationRef.channel("n1", conversation),
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
}
