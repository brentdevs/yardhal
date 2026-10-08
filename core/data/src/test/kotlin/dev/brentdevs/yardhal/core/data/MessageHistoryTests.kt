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
class MessageHistoryTests {

    private suspend fun withStore(block: suspend (MessageStore) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = YardhalDatabase.inMemory(context)
        try {
            block(MessageStore(db.messageDao()))
        } finally {
            db.close()
        }
    }

    private fun message(
        conversation: ConversationRef,
        text: String,
        timestampMs: Long = 1000,
        msgid: String? = null,
        kind: MessageKind = MessageKind.PRIVMSG,
    ): StoredMessage = StoredMessage(
        networkId = conversation.networkId,
        conversation = conversation,
        msgid = msgid,
        senderNick = "alice",
        senderUser = "user",
        senderHost = "host",
        kind = kind,
        text = text,
        sentByUs = false,
        timestampMs = timestampMs,
    )

    @Test
    fun repeatedOlderPagesRetainEveryTimestampTieAndStayInConversationScope() = runBlocking {
        withStore { store ->
            val ref = ConversationRef.channel("n1", "#Room[IRC]")
            val expected = (listOf(2000L, 500L) + List(9) { 1000L }).mapIndexed { index, timestamp ->
                val value = message(ref, "message-$index", timestamp)
                value.copy(rowId = assertNotNull(store.recordWithRowId(value)))
            }.sortedWith(compareBy<StoredMessage> { it.timestampMs }.thenBy { it.rowId })
            for (other in listOf(
                ConversationRef.channel("n1", "#other"),
                ConversationRef.directMessage("n1", "bob"),
                ConversationRef.channel("n2", "#Room[IRC]"),
            )) {
                assertTrue(store.record(message(other, "outside scope")))
            }
            val foldedRef = ConversationRef.channel("n1", "#ROOM{IRC}")
            var page = store.recent(foldedRef, 3)
            val loaded = page.toMutableList()
            var requests = 0
            while (page.isNotEmpty()) {
                val first = page.first()
                page = store.before(foldedRef, MessageCursor(first.timestampMs, first.rowId), 3)
                loaded.addAll(0, page)
                requests++
            }
            assertTrue(requests > 2)
            assertEquals(expected.map { it.rowId }, loaded.map { it.rowId })
            assertEquals(expected.map { it.text }, loaded.map { it.text })
            assertEquals(expected.size, loaded.map { it.rowId }.toSet().size)
        }
    }

    @Test
    fun scopedMsgidsRemainIntactAcrossChannelsDmsAndNetworks() = runBlocking {
        withStore { store ->
            val refs = listOf(
                ConversationRef.channel("n1", "#one"),
                ConversationRef.channel("n1", "#two"),
                ConversationRef.directMessage("n1", "bob"),
                ConversationRef.channel("n2", "#one"),
            )
            val ids = refs.map { ref ->
                val value = message(ref, "same body", msgid = "shared-id")
                val rowId = assertNotNull(store.recordWithRowId(value))
                assertEquals(rowId, store.findStoredMessage(value.copy(text = "replayed body", timestampMs = 2000))?.rowId)
                assertFalse(store.record(value))
                assertEquals("shared-id", store.recent(ref, 10).single().msgid)
                rowId
            }
            assertEquals(refs.size, ids.toSet().size)
            assertNull(store.findStoredMessage(message(ConversationRef.channel("n3", "#one"), "same body", msgid = "shared-id"))?.rowId)
            assertNull(store.findStoredMessage(message(ConversationRef.directMessage("n1", "carol"), "same body", msgid = "shared-id"))?.rowId)
        }
    }

    @Test
    fun contentLookupDistinguishesSameSenderAndTimestampByCanonicalBody() = runBlocking {
        withStore { store ->
            val ref = ConversationRef.directMessage("n1", "bob")
            val first = message(ref, "first body")
            val second = message(ref, "second body")
            val firstId = assertNotNull(store.recordWithRowId(first))
            val secondId = assertNotNull(store.recordWithRowId(second))
            assertEquals(firstId, store.findStoredMessage(first.copy(msgid = "new-first-id"))?.rowId)
            assertEquals(secondId, store.findStoredMessage(second.copy(msgid = "new-second-id"))?.rowId)
            assertNull(store.findStoredMessage(first.copy(text = "third body"))?.rowId)
            assertNull(store.findStoredMessage(first.copy(timestampMs = 1001))?.rowId)
            assertNull(store.findStoredMessage(first.copy(kind = MessageKind.NOTICE))?.rowId)
            assertNull(store.findStoredMessage(first.copy(senderNick = "carol"))?.rowId)
        }
    }

    @Test
    fun healingCanReuseAMsgidOwnedByAnotherConversationWithoutChangingEitherIdentity() = runBlocking {
        withStore { store ->
            val local = message(ConversationRef.channel("n1", "#room"), "local overlap")
            val remote = message(ConversationRef.channel("n2", "#room"), "remote message", msgid = "shared-id")
            val localId = assertNotNull(store.recordWithRowId(local))
            val remoteId = assertNotNull(store.recordWithRowId(remote))
            val replay = local.copy(msgid = "shared-id")
            assertNull(store.recordWithRowId(replay))
            assertEquals(localId, store.findStoredMessage(replay)?.rowId)
            assertEquals(remoteId, store.findStoredMessage(remote)?.rowId)
            assertEquals(listOf(local.copy(rowId = localId, msgid = "shared-id")), store.recent(local.conversation, 10))
            assertEquals(listOf(remote.copy(rowId = remoteId)), store.recent(remote.conversation, 10))
            assertEquals(listOf(localId), store.search("overlap").map { it.rowId })
            assertEquals(listOf(remoteId), store.search("remote").map { it.rowId })
        }
    }

    @Test
    fun acquiringMsgidHealsOriginalRowWithoutDuplicateInsertionOrFtsChanges() = runBlocking {
        withStore { store ->
            val ref = ConversationRef.channel("n1", "#room")
            val original = message(ref, "searchable overlap").copy(channelContext = "#context")
            val rowId = assertNotNull(store.recordWithRowId(original))
            val replay = original.copy(msgid = "replay-id", channelContext = "#different")
            assertNull(store.recordWithRowId(replay))
            assertEquals(rowId, store.findStoredMessage(replay)?.rowId)
            assertEquals(listOf(original.copy(rowId = rowId, msgid = "replay-id")), store.recent(ref, 10))
            assertEquals(listOf(rowId), store.search("searchable").map { it.rowId })
            assertFalse(store.record(original))
            assertFalse(store.record(replay))
        }
    }

    @Test
    fun distinctMsgidsKeepDistinctRowsEvenWithIdenticalCanonicalContent() = runBlocking {
        withStore { store ->
            val ref = ConversationRef.channel("n1", "#room")
            val first = message(ref, "identical body", msgid = "first-id")
            val second = first.copy(msgid = "second-id")
            val firstId = assertNotNull(store.recordWithRowId(first))
            val secondId = assertNotNull(store.recordWithRowId(second))
            assertEquals(firstId, store.findStoredMessage(first)?.rowId)
            assertEquals(secondId, store.findStoredMessage(second)?.rowId)
            assertEquals(listOf("first-id", "second-id"), store.recent(ref, 10).map { it.msgid })
            assertFalse(store.record(first.copy(msgid = null)))
        }
    }

    @Test
    fun healingDoesNotOverwriteAnAlreadyAssignedMsgidInTheSameConversation() = runBlocking {
        withStore { store ->
            val ref = ConversationRef.channel("n1", "#room")
            val identified = message(ref, "identified body", msgid = "existing-id")
            val unidentified = message(ref, "unidentified body")
            val identifiedId = assertNotNull(store.recordWithRowId(identified))
            val unidentifiedId = assertNotNull(store.recordWithRowId(unidentified))
            assertFalse(store.record(unidentified.copy(msgid = "existing-id")))
            assertEquals(identifiedId, store.findStoredMessage(identified)?.rowId)
            assertEquals(unidentifiedId, store.findStoredMessage(unidentified)?.rowId)
            assertEquals(listOf("existing-id", null), store.recent(ref, 10).map { it.msgid })
        }
    }

    @Test
    fun anchorsChooseLatestEligibleMessagePerConversationUsingRowIdForTies() = runBlocking {
        withStore { store ->
            val channel = ConversationRef.channel("n1", "#room")
            val dm = ConversationRef.directMessage("n1", "bob")
            val other = ConversationRef.channel("n1", "#other")
            assertTrue(store.record(message(channel, "old message", 100, "old")))
            val latestChannel = message(channel, "notice", 250, "notice", MessageKind.NOTICE)
            val channelId = assertNotNull(store.recordWithRowId(latestChannel))
            assertTrue(store.record(message(dm, "earlier tie", 200, "dm-first")))
            val latestDm = message(dm, "action", 200, "action", MessageKind.ACTION)
            val dmId = assertNotNull(store.recordWithRowId(latestDm))
            val latestOther = message(other, "other", 200, "other")
            val otherId = assertNotNull(store.recordWithRowId(latestOther))
            for (kind in MessageKind.entries.filter { it !in listOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION) }) {
                assertTrue(store.record(message(channel, "noise-$kind", 1000, "noise-$kind", kind)))
            }
            assertTrue(store.record(message(ConversationRef.channel("n1", "#noise"), "only join", 2000, "join", MessageKind.JOIN)))
            assertTrue(store.record(message(ConversationRef.server("n1"), "system", 3000, "system", MessageKind.SYSTEM)))
            assertTrue(store.record(message(ConversationRef.server("n1"), "registration notice", 5000, "registration", MessageKind.NOTICE)))
            assertTrue(store.record(message(ConversationRef.channel("n2", "#room"), "other network", 4000, "other-network")))
            val anchors = store.historyAnchors("n1")
            assertEquals(
                listOf(latestDm.copy(rowId = dmId), latestOther.copy(rowId = otherId), latestChannel.copy(rowId = channelId)),
                anchors,
            )
            assertEquals(listOf("other-network"), store.historyAnchors("n2").map { it.msgid })
            assertTrue(store.historyAnchors("missing").isEmpty())
        }
    }

    @Test
    fun registrationNoticesDoNotProvideCatchUpAnchors() = runBlocking {
        withStore { store ->
            val server = ConversationRef.server("n1")
            val notice = message(server, "welcome", 5000, "registration", MessageKind.NOTICE)
            val module = ConversationRef.directMessage("n1", "*status")
            val control = message(module, "module status", 6000, "control", MessageKind.NOTICE)
            val controlId = assertNotNull(store.recordWithRowId(control))
            val rowId = assertNotNull(store.recordWithRowId(notice))
            assertTrue(store.historyAnchors("n1").isEmpty())
            assertEquals(listOf(notice.copy(rowId = rowId)), store.recent(server, 10))
            assertEquals(listOf(rowId), store.search("welcome").map { it.rowId })
            assertEquals(listOf(control.copy(rowId = controlId)), store.recent(module, 10))
        }
    }

    @Test
    fun laterContextAndContextOnlyConversationsCannotAdvanceAnchors() = runBlocking {
        withStore { store ->
            val ref = ConversationRef.channel("n1", "#room")
            val primary = message(ref, "primary", 1000, "primary")
            val primaryId = assertNotNull(store.recordWithRowId(primary))
            val context = message(ref, "later context", 2000, "context").copy(historyContext = true)
            val contextId = assertNotNull(store.recordWithRowId(context))
            assertTrue(store.record(message(ref, "tied context", 1000, "tied-context").copy(historyContext = true)))
            val contextOnly = ConversationRef.directMessage("n1", "bob")
            assertTrue(store.record(message(contextOnly, "context only", 3000).copy(historyContext = true)))
            assertEquals(listOf(primary.copy(rowId = primaryId)), store.historyAnchors("n1"))
            assertEquals(contextId, store.recent(ref, 10).last().rowId)
            assertTrue(store.recent(ref, 10).last().historyContext)
            assertEquals(listOf(contextId), store.search("later").map { it.rowId })
        }
    }

    @Test
    fun ordinaryDuplicatesPromoteContextWithoutChangingIdentityContentOrSearch() = runBlocking {
        withStore { store ->
            val cases = listOf(
                "identified" to ("shared-id" to "shared-id"),
                "canonical" to (null to null),
                "acquiring" to (null to "shared-id"),
                "unidentified" to ("shared-id" to null),
            )
            for ((target, ids) in cases) {
                val ref = ConversationRef.channel("n1", "#$target")
                val original = message(ref, "searchable overlap", 2000, ids.first).copy(historyContext = true)
                val rowId = assertNotNull(store.recordWithRowId(original))
                assertTrue(store.historyAnchors("n1").none { it.conversation == ref })
                val ordinary = original.copy(msgid = ids.second, historyContext = false)
                assertNull(store.recordWithRowId(ordinary))
                val expected = original.copy(rowId = rowId, msgid = ids.first ?: ids.second, historyContext = false)
                assertEquals(listOf(expected), store.recent(ref, 10))
                assertEquals(expected, store.historyAnchors("n1").single { it.conversation == ref })
                assertEquals(rowId, store.findStoredMessage(ordinary)?.rowId)
                assertEquals(listOf(rowId), store.search("searchable", conversation = ref.normalizedTarget).map { it.rowId })
                assertFalse(store.record(original))
                assertFalse(store.record(ordinary.copy(historyContext = true)))
                assertEquals(listOf(expected), store.recent(ref, 10))
            }
        }
    }

    @Test
    fun identifiedPromotionKeepsOriginalCanonicalContentWhenReplayMetadataDiffers() = runBlocking {
        withStore { store ->
            val ref = ConversationRef.channel("n1", "#room")
            val original = message(ref, "original searchable body", 1000, "same-id").copy(historyContext = true)
            val rowId = assertNotNull(store.recordWithRowId(original))
            assertFalse(store.record(original.copy(text = "different replay body", timestampMs = 5000, historyContext = false)))
            val expected = original.copy(rowId = rowId, historyContext = false)
            assertEquals(listOf(expected), store.historyAnchors("n1"))
            assertEquals(listOf(expected), store.recent(ref, 10))
            assertEquals(listOf(rowId), store.search("original").map { it.rowId })
            assertTrue(store.search("different").isEmpty())
        }
    }

    @Test
    fun renameCollisionsKeepPrimaryEligibilityTargetIdentityAndScopedMsgids() = runBlocking {
        withStore { store ->
            for (sourceContext in listOf(false, true)) {
                for (targetContext in listOf(false, true)) {
                    for ((sourceMsgid, targetMsgid) in listOf(null to null, null to "shared-id", "shared-id" to null, "shared-id" to "shared-id")) {
                        val suffix = "$sourceContext-$targetContext-$sourceMsgid-$targetMsgid"
                        val old = ConversationRef.directMessage("n1", "old-$suffix")
                        val renamed = ConversationRef.directMessage("n1", "new-$suffix")
                        val unrelated = ConversationRef.channel("n1", "#other-$suffix")
                        val source = message(old, "searchable overlap", msgid = sourceMsgid).copy(historyContext = sourceContext)
                        val target = message(renamed, "searchable overlap", msgid = targetMsgid).copy(historyContext = targetContext)
                        val other = message(unrelated, "unrelated body", msgid = "shared-id")
                        val sourceId = assertNotNull(store.recordWithRowId(source))
                        val targetId = assertNotNull(store.recordWithRowId(target))
                        val otherId = assertNotNull(store.recordWithRowId(other))
                        val merged = mutableListOf<Pair<Long, Long>>()
                        assertEquals(1, store.renameConversation(old, renamed) { from, to -> merged.add(from to to) })
                        val expected = target.copy(rowId = targetId, msgid = targetMsgid ?: sourceMsgid, historyContext = sourceContext && targetContext)
                        assertEquals(listOf(sourceId to targetId), merged)
                        assertTrue(store.recent(old, 10).isEmpty())
                        assertEquals(listOf(expected), store.recent(renamed, 10))
                        assertEquals(
                            if (expected.historyContext) emptyList() else listOf(expected),
                            store.historyAnchors("n1").filter { it.conversation == renamed },
                        )
                        assertEquals(targetId, store.findStoredMessage(expected)?.rowId)
                        assertEquals(listOf(targetId), store.search("searchable", conversation = renamed.normalizedTarget).map { it.rowId })
                        assertTrue(store.search("searchable", conversation = old.normalizedTarget).isEmpty())
                        assertEquals(listOf(other.copy(rowId = otherId)), store.recent(unrelated, 10))
                    }
                }
            }
        }
    }

    @Test
    fun renamingOntoTheSameScopedMsgidMergesRowsAndKeepsTargetSearchIdentity() = runBlocking {
        withStore { store ->
            val old = ConversationRef.directMessage("n1", "oldnick")
            val renamed = ConversationRef.directMessage("n1", "newnick")
            val oldMessage = message(old, "renamable overlap", msgid = "shared-id")
            val newMessage = message(renamed, "renamable overlap", msgid = "shared-id")
            val oldId = assertNotNull(store.recordWithRowId(oldMessage))
            val newId = assertNotNull(store.recordWithRowId(newMessage))
            val merged = mutableListOf<Pair<Long, Long>>()
            assertEquals(1, store.renameConversation(old, renamed) { from, to -> merged.add(from to to) })
            assertEquals(listOf(oldId to newId), merged)
            assertTrue(store.recent(old, 10).isEmpty())
            assertEquals(listOf(newId), store.recent(renamed, 10).map { it.rowId })
            assertEquals(listOf(newId), store.search("renamable").map { it.rowId })
            assertEquals(newId, store.findStoredMessage(newMessage)?.rowId)
        }
    }
}
