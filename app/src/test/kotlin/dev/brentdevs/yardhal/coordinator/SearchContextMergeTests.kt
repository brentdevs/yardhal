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

    @Test
    fun searchContextRetainsEqualTimeBeforeReplayWireOrderDespiteLaterStoredRowIds() {
        val replayed = orderBeforeReplay(
            listOf(
                live(1, msgid = "cached-first", rowId = 41),
                live(2, msgid = "cached-second", rowId = 42),
                live(3, msgid = "older-first"),
                live(4, msgid = "older-second"),
            ),
            firstNewId = 3,
        )
        val context = listOf(
            stored(41, "cached-first"),
            stored(42, "cached-second"),
            stored(43, "older-first"),
            stored(44, "older-second"),
        )
        val merged = mergeSearchContext(replayed, context) { error("All stored messages are already visible") }
        val repeated = mergeSearchContext(merged, context) { error("All stored messages are already visible") }

        assertEquals(listOf(3L, 4L, 1L, 2L), replayed.map { it.localId })
        assertEquals(replayed.map { it.localId }, merged.map { it.localId })
        assertEquals(listOf(43L, 44L, 41L, 42L), merged.map { it.storedRowId })
        assertEquals(merged, repeated)
    }

    @Test
    fun equalTimeLocalPagesPrecedeCachedRowsAndSearchKeepsTheirIdentityAndOrder() {
        var nextId = 3L
        val paged = mergeSearchContext(
            listOf(live(1, msgid = "cached-first", rowId = 43), live(2, msgid = "cached-second", rowId = 44)),
            listOf(stored(41, "older-first"), stored(42, "older-second")),
            prependEqualTimestamp = true,
        ) { nextId++ }
        val searched = mergeSearchContext(
            paged,
            listOf(stored(41, "older-first"), stored(42, "older-second"), stored(43, "cached-first"), stored(44, "cached-second")),
        ) { error("All stored messages are already visible") }

        assertEquals(listOf(3L, 4L, 1L, 2L), paged.map { it.localId })
        assertEquals(listOf("older-first", "older-second", "cached-first", "cached-second"), paged.map { it.msgid })
        assertEquals(paged, searched)
    }

    @Test
    fun localOverlapHealsRowsWithoutMovingEstablishedEqualTimeMessages() {
        var nextId = 4L
        val current = listOf(
            live(1, msgid = "wire-first"),
            live(2, msgid = "wire-second"),
            live(3, msgid = "wire-third"),
        )
        val merged = mergeSearchContext(
            current,
            listOf(
                stored(41, "missing-older"),
                stored(42, "wire-third"),
                stored(43, "wire-first"),
                stored(44, "wire-second"),
            ),
            prependEqualTimestamp = true,
        ) { nextId++ }

        assertEquals(listOf("missing-older", "wire-first", "wire-second", "wire-third"), merged.map { it.msgid })
        assertEquals(listOf(4L, 1L, 2L, 3L), merged.map { it.localId })
        assertEquals(listOf(41L, 43L, 44L, 42L), merged.map { it.storedRowId })
    }

    @Test
    fun searchAndLocalHistoryRestoreEveryDurableRenderField() {
        val row = stored(42, "reply").copy(
            highlightsMe = true,
            playback = true,
            historyContext = true,
            channelContext = "#context",
            replyToMsgid = "parent",
            replyParentRowId = 41,
            attachmentUrl = "https://example.test/image.png",
            attachmentName = "image.png",
            attachmentMimeType = "image/png",
            attachmentSizeBytes = 123,
            attachmentWidth = 320,
            attachmentHeight = 240,
            senderAccount = "alice-account",
        )
        val expected = row.toChatMessage(3)
        val searched = mergeSearchContext(emptyList(), listOf(row)) { 3 }
        val history = mergeSearchContext(emptyList(), listOf(row), prependEqualTimestamp = true) { 3 }

        assertEquals(expected, searched.single())
        assertEquals(expected, history.single())
        assertEquals(true, expected.highlightsMe)
        assertEquals(true, expected.playback)
        assertEquals(true, expected.historyContext)
        assertEquals("#context", expected.channelContext)
        assertEquals("parent", expected.replyToMsgid)
        assertEquals(41L, expected.localReplyParentRowId)
        assertEquals(row.attachmentUrl, expected.attachmentUrl)
        assertEquals(row.attachmentName, expected.attachmentName)
        assertEquals(row.attachmentMimeType, expected.attachmentMimeType)
        assertEquals(row.attachmentSizeBytes, expected.attachmentSizeBytes)
        assertEquals(row.attachmentWidth, expected.attachmentWidth)
        assertEquals(row.attachmentHeight, expected.attachmentHeight)
        assertEquals(row.senderAccount, expected.senderAccount)
    }

    @Test
    fun overlappingStoredHistoryHealsMetadataWithoutLosingLiveIdentityOrReplyPreview() {
        val preview = ReplyPreview("bob", "parent", "https://example.test/parent.png")
        val current = live(1, "reply").copy(replyPreview = preview)
        val row = stored(42, "reply").copy(
            highlightsMe = true,
            playback = true,
            historyContext = true,
            channelContext = "#context",
            replyToMsgid = "parent",
            replyParentRowId = 41,
            attachmentUrl = "https://example.test/image.png",
            attachmentName = "image.png",
            attachmentMimeType = "image/png",
            attachmentSizeBytes = 123,
            attachmentWidth = 320,
            attachmentHeight = 240,
            senderAccount = "alice-account",
        )
        val merged = mergeSearchContext(listOf(current), listOf(row)) { error("Already visible") }.single()

        assertEquals(row.toChatMessage(1).copy(playback = false, historyContext = false, replyPreview = preview), merged)
    }

    @Test
    fun durableTombstoneWinsInEitherSearchMergeDirectionAndClearsSensitiveFields() {
        val healthy = stored(42, "parent").copy(
            replyToMsgid = "older-parent",
            replyParentRowId = 40,
            attachmentUrl = "https://example.test/private.png",
            attachmentName = "private.png",
            attachmentMimeType = "image/png",
            attachmentSizeBytes = 123,
            attachmentWidth = 320,
            attachmentHeight = 240,
        )
        val deleted = healthy.copy(redacted = true)
        val expected = deleted.toChatMessage(1)
        val first = mergeSearchContext(listOf(healthy.toChatMessage(1)), listOf(deleted)) { error("Already visible") }
        val second = mergeSearchContext(first, listOf(healthy)) { error("Already visible") }
        val restored = mergeSearchContext(emptyList(), listOf(deleted)) { 1 }

        assertEquals(expected, first.single())
        assertEquals(first, second)
        assertEquals(first, restored)
        assertEquals("message deleted", expected.text)
        assertEquals(MessageKind.SYSTEM, expected.kind)
        assertEquals(null, expected.replyToMsgid)
        assertEquals(null, expected.localReplyParentRowId)
        assertEquals(null, expected.replyPreview)
        assertEquals(null, expected.attachmentUrl)
        assertEquals(null, expected.attachmentName)
        assertEquals(null, expected.attachmentMimeType)
        assertEquals(null, expected.attachmentSizeBytes)
        assertEquals(null, expected.attachmentWidth)
        assertEquals(null, expected.attachmentHeight)
    }

    @Test
    fun historyOverlapTombstonesAlreadyVisibleParentAndItsReplyPreview() {
        val parent = stored(41, "parent")
        val reply = stored(42, "reply").copy(replyToMsgid = "parent", replyParentRowId = 41)
            .toChatMessage(2).copy(replyPreview = ReplyPreview("alice", "private", "https://example.test/private.png"))
        val merged = mergeSearchContext(
            listOf(parent.toChatMessage(1), reply),
            listOf(parent.copy(redacted = true)),
        ) { error("Parent already visible") }

        assertEquals(parent.copy(redacted = true).toChatMessage(1), merged[0])
        assertEquals(reply.copy(replyPreview = ReplyPreview("alice", "message deleted", redacted = true)), merged[1])
    }
}
