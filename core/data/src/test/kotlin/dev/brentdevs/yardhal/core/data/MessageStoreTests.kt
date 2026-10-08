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
        assertFalse(store.record(message(conversation = ref, msgid = null).copy(channelContext = "#room")))
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

        val before = store.before(ref, MessageCursor(450, 0), 2)
        assertEquals(listOf(300L, 400L), before.map { it.timestampMs })
    }

    @Test
    fun channelContextRoundTripsThroughRecentAndHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = YardhalDatabase.inMemory(context)
        try {
            val store = MessageStore(db.messageDao())
            val ref = ConversationRef.server("n1")
            val contexts = listOf(null, "#room", "#Other")
            val expected = contexts.mapIndexed { index, channelContext ->
                val stored = message(
                    conversation = ref,
                    msgid = "context-$index",
                    timestampMs = (index + 1) * 100L,
                ).copy(kind = MessageKind.NOTICE, channelContext = channelContext)
                stored.copy(rowId = assertNotNull(store.recordWithRowId(stored)))
            }

            assertEquals(expected, store.recent(ref, 10))
            assertEquals(expected, store.after(ref, 0))
            assertEquals(expected, store.before(ref, MessageCursor(400, 0), 10))
            assertEquals(expected, store.around(ref, expected[1].rowId, expected[1].timestampMs))
        } finally {
            db.close()
        }
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
}
