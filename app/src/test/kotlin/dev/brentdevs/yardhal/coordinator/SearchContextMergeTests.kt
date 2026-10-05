package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.StoredMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchContextMergeTests {
    private val ref = ConversationRef.channel("network", "#room")

    private fun live(localId: Long, msgid: String? = null, rowId: Long? = null): ChatMessage = ChatMessage(
        localId = localId,
        sender = "alice",
        kind = MessageKind.PRIVMSG,
        text = "same text",
        timestampMs = 1_000,
        sentByUs = false,
        highlightsMe = false,
        msgid = msgid,
        storedRowId = rowId,
    )

    private fun stored(rowId: Long, msgid: String? = null): StoredMessage = StoredMessage(
        rowId = rowId,
        networkId = "network",
        conversation = ref,
        msgid = msgid,
        senderNick = "alice",
        senderUser = null,
        senderHost = null,
        kind = MessageKind.PRIVMSG,
        text = "same text",
        sentByUs = false,
        timestampMs = 1_000,
    )

    @Test
    fun identicalAnonymousMessagesDoNotBorrowAStoredRowId() {
        val merged = mergeSearchContext(listOf(live(1), live(2)), listOf(stored(42))) { 3 }

        assertEquals(mapOf(1L to null, 2L to null, 3L to 42L), merged.associate { it.localId to it.storedRowId })
    }

    @Test
    fun storedRowIdsAndMessageIdsMergeEqualMessagesIndependently() {
        val merged = mergeSearchContext(
            listOf(live(1, msgid = "first", rowId = 41), live(2, msgid = "second")),
            listOf(stored(41, "first"), stored(42, "second")),
        ) { 3 }

        assertEquals(listOf(1L, 2L), merged.map { it.localId })
        assertEquals(listOf(41L, 42L), merged.map { it.storedRowId })
    }
}
