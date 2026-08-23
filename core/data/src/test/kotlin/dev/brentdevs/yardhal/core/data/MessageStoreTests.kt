package dev.brentdevs.yardhal.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageStoreTests {

    private fun newStore(): MessageStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = YardhalDatabase.inMemory(context)
        return MessageStore(db.messageDao())
    }

    private fun message(
        conversation: ConversationRef = ConversationRef.channel("n1", "#room"),
        msgid: String? = "m-1",
        text: String = "hello",
        timestampMs: Long = 1000,
        senderNick: String = "alice",
    ): StoredMessage = StoredMessage(
        networkId = conversation.networkId,
        conversation = conversation,
        msgid = msgid,
        senderNick = senderNick,
        senderUser = "u",
        senderHost = "h",
        kind = MessageKind.PRIVMSG,
        text = text,
        sentByUs = false,
        timestampMs = timestampMs,
    )

    @Test
    fun recordDeduplicatesByMsgid() = runBlocking {
        val store = newStore()
        assertTrue(store.record(message()))
        assertFalse(store.record(message(timestampMs = 2000)))
        assertEquals(1, store.recent(ConversationRef.channel("n1", "#ROOM"), 10).size)
    }

    @Test
    fun recordDeduplicatesByContentHashWhenNoMsgid() = runBlocking {
        val store = newStore()
        val ref = ConversationRef.directMessage("n1", "bob")
        assertTrue(store.record(message(conversation = ref, msgid = null)))
        assertTrue(store.record(message(conversation = ref, msgid = null, timestampMs = 9999)))
        assertFalse(store.record(message(conversation = ref, msgid = null)))
        assertTrue(store.record(message(conversation = ref, msgid = null, text = "different", timestampMs = 9999)))
        assertEquals(3, store.recent(ref, 10).size)
    }

    @Test
    fun sameTextDifferentConversationsBothStored() = runBlocking {
        val store = newStore()
        val a = ConversationRef.channel("n1", "#a")
        val b = ConversationRef.channel("n1", "#b")
        assertTrue(store.record(message(conversation = a, msgid = null)))
        assertTrue(store.record(message(conversation = b, msgid = null)))
    }

    @Test
    fun recentReturnsChronologicalOrder() = runBlocking {
        val store = newStore()
        val ref = ConversationRef.channel("n1", "#room")
        for (i in 1..5) store.record(message(msgid = "m-$i", timestampMs = i * 100L))
        val recent = store.recent(ref, 3)
        assertEquals(listOf(300L, 400L, 500L), recent.map { it.timestampMs })
    }

    @Test
    fun beforeAndAfterQueries() = runBlocking {
        val store = newStore()
        val ref = ConversationRef.channel("n1", "#room")
        for (i in 1..5) store.record(message(msgid = "m-$i", timestampMs = i * 100L))

        val after = store.after(ref, 250)
        assertEquals(listOf(300L, 400L, 500L), after.map { it.timestampMs })

        val before = store.before(ref, 450, 2)
        assertEquals(listOf(300L, 400L), before.map { it.timestampMs })
    }

    @Test
    fun trimKeepsNewest() = runBlocking {
        val store = newStore()
        val ref = ConversationRef.channel("n1", "#room")
        for (i in 1..6) store.record(message(msgid = "m-$i", timestampMs = i * 100L))
        store.trimTo(ref, keep = 3)
        val remaining = store.recent(ref, 10)
        assertEquals(listOf(400L, 500L, 600L), remaining.map { it.timestampMs })
        assertNull(store.latestTimestamp(ConversationRef.channel("n1", "#empty")))
    }

    @Test
    fun deleteNetworkRemovesAllConversations() = runBlocking {
        val store = newStore()
        store.record(message(conversation = ConversationRef.channel("n1", "#a"), msgid = "a-1"))
        store.record(message(conversation = ConversationRef.channel("n2", "#a"), msgid = "b-1"))
        store.deleteNetwork("n1")

        assertTrue(store.recent(ConversationRef.channel("n1", "#a"), 5).isEmpty())
        assertEquals(1, store.recent(ConversationRef.channel("n2", "#a"), 5).size)
    }

    @Test
    fun contentHashIsStableAndDistinct() {
        val base = message()
        val same = message()
        val other = message(text = "other")
        assertEquals(MessageStore.contentHash(base), MessageStore.contentHash(same))
        assertFalse(MessageStore.contentHash(base) == MessageStore.contentHash(other))
    }
}
