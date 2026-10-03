package dev.brentdevs.yardhal.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageStoreRenameTests {

    private fun newStore(): MessageStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return MessageStore(YardhalDatabase.inMemory(context).messageDao())
    }

    private fun message(ref: ConversationRef, text: String, timestampMs: Long, msgid: String? = null): StoredMessage =
        StoredMessage(
            networkId = ref.networkId,
            conversation = ref,
            msgid = msgid,
            senderNick = "alice",
            senderUser = null,
            senderHost = null,
            kind = MessageKind.PRIVMSG,
            text = text,
            sentByUs = false,
            timestampMs = timestampMs,
        )

    @Test
    fun renameRekeysRowsKeepingOtherConversationsAndNetworksUntouched() = runBlocking {
        val store = newStore()
        val old = ConversationRef.channel("n1", "#old")
        val renamed = ConversationRef.channel("n1", "#New")
        val other = ConversationRef.channel("n1", "#other")
        val elsewhere = ConversationRef.channel("n2", "#old")
        store.record(message(old, "first", 1000, msgid = "m1"))
        store.record(message(old, "second", 2000))
        store.record(message(other, "unrelated", 1500))
        store.record(message(elsewhere, "other network", 1500))

        assertEquals(2, store.renameConversation(old, renamed))

        assertTrue(store.recent(old, 10).isEmpty())
        assertEquals(listOf("first", "second"), store.recent(renamed, 10).map { it.text })
        assertEquals(listOf("unrelated"), store.recent(other, 10).map { it.text })
        assertEquals(listOf("other network"), store.recent(elsewhere, 10).map { it.text })
        assertEquals(listOf(renamed.normalizedTarget, other.normalizedTarget).sorted(),
            store.knownConversations("n1").map { it.normalizedTarget }.sorted())
    }

    @Test
    fun renamedRowsStaySearchableUnderTheNewConversation() = runBlocking {
        val store = newStore()
        val old = ConversationRef.channel("n1", "#old")
        val renamed = ConversationRef.channel("n1", "#new")
        store.record(message(old, "deploy finished", 1000))
        store.renameConversation(old, renamed)

        val hits = store.search("deploy", conversation = renamed.normalizedTarget)
        assertEquals(listOf("#new"), hits.map { it.conversation })
        assertTrue(store.search("deploy", conversation = old.normalizedTarget).isEmpty())
    }

    @Test
    fun renamedRowsDeduplicateReplayedCopiesWithoutMsgid() = runBlocking {
        val store = newStore()
        val old = ConversationRef.channel("n1", "#old")
        val renamed = ConversationRef.channel("n1", "#new")
        store.record(message(old, "hello", 1000))
        store.renameConversation(old, renamed)

        assertFalse(store.record(message(renamed, "hello", 1000)))
        assertEquals(1, store.recent(renamed, 10).size)
    }

    @Test
    fun caseOnlyRenameIsANoOp() = runBlocking {
        val store = newStore()
        val lower = ConversationRef.channel("n1", "#chan")
        store.record(message(lower, "x", 1))
        assertEquals(0, store.renameConversation(lower, ConversationRef.channel("n1", "#CHAN")))
        assertEquals(1, store.recent(lower, 10).size)
    }
}
