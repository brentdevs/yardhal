package dev.brentdevs.yardhal.core.data

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class HistoryCoverageStoreTests {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun explicitUnresolvedIntervalsPersistWithMsgidsAndEmptyPutClearsThem() {
        val store = HistoryCoverageStore(tmp.root)
        val gap = StoredHistoryGap("gap", 1000, "start-id", 2000, "end-id")
        assertTrue(store.gaps("n1|#room").isEmpty())
        store.put("n1|#room", listOf(gap, gap))
        assertEquals(listOf(gap), store.gaps("n1|#room"))
        assertEquals(listOf(gap), HistoryCoverageStore(tmp.root).gaps("n1|#room"))
        store.put("n1|#room", emptyList())
        assertTrue(HistoryCoverageStore(tmp.root).gaps("n1|#room").isEmpty())
    }

    @Test
    fun renameMergesKnownIntervalsDeduplicatesAndPersistsWithoutJoiningGaps() {
        val store = HistoryCoverageStore(tmp.root)
        val first = StoredHistoryGap("first", 1000, toTimestampMs = 2000)
        val second = StoredHistoryGap("second", 2000, toTimestampMs = 3000)
        val shared = StoredHistoryGap("shared", 4000, toTimestampMs = 5000)
        store.put("n1|oldnick", listOf(first, shared))
        store.put("n1|newnick", listOf(second, shared))
        assertTrue(store.rename("n1|oldnick", "n1|newnick"))
        assertTrue(store.gaps("n1|oldnick").isEmpty())
        assertEquals(listOf(second, shared, first), store.gaps("n1|newnick"))
        val reopened = HistoryCoverageStore(tmp.root)
        assertTrue(reopened.gaps("n1|oldnick").isEmpty())
        assertEquals(listOf(second, shared, first), reopened.gaps("n1|newnick"))
        assertFalse(store.rename("n1|missing", "n1|newnick"))
        assertFalse(store.rename("n1|newnick", "n1|newnick"))
    }

    @Test
    fun removingNetworkCannotRemoveSimilarPrefixesOrOtherConversations() {
        val store = HistoryCoverageStore(tmp.root)
        val gap = StoredHistoryGap("gap", 1000, toTimestampMs = 2000)
        for (key in listOf("n1|#room", "n1|bob", "n10|#room", "n2|#room")) store.put(key, listOf(gap))
        store.removeNetwork("n1")
        val reopened = HistoryCoverageStore(tmp.root)
        assertTrue(reopened.gaps("n1|#room").isEmpty())
        assertTrue(reopened.gaps("n1|bob").isEmpty())
        assertEquals(listOf(gap), reopened.gaps("n10|#room"))
        assertEquals(listOf(gap), reopened.gaps("n2|#room"))
    }

    @Test
    fun conversationUpdatesRemainIsolatedAndInputMutationCannotChangeStoredCoverage() {
        val store = HistoryCoverageStore(tmp.root)
        val gap = StoredHistoryGap("gap", 1000, toTimestampMs = 2000)
        val input = mutableListOf(gap)
        store.put("n1|#room", input)
        input.clear()
        assertEquals(listOf(gap), store.gaps("n1|#room"))
        assertTrue(store.gaps("n1|bob").isEmpty())
        assertTrue(store.gaps("n2|#room").isEmpty())
        store.put("n1|bob", listOf(gap.copy(id = "dm-gap")))
        assertEquals(listOf(gap), store.gaps("n1|#room"))
        assertEquals(listOf(gap.copy(id = "dm-gap")), HistoryCoverageStore(tmp.root).gaps("n1|bob"))
    }

    @Test
    fun failedPutRetainsDurableCoverageAndIdenticalRetryPersists() {
        val store = HistoryCoverageStore(tmp.root)
        val original = StoredHistoryGap("original", 1000, "first", 2000, "second")
        val replacement = StoredHistoryGap("replacement", 3000, "third", 4000, "fourth")
        store.put("n1|#room", listOf(original))
        val blockedWrite = tmp.newFolder("history-coverage.json.tmp")

        assertFailsWith<IOException> { store.put("n1|#room", listOf(replacement, replacement)) }
        assertEquals(listOf(original), store.gaps("n1|#room"))
        assertEquals(listOf(original), HistoryCoverageStore(tmp.root).gaps("n1|#room"))
        assertTrue(blockedWrite.delete())

        store.put("n1|#room", listOf(replacement, replacement))
        assertEquals(listOf(replacement), store.gaps("n1|#room"))
        assertEquals(listOf(replacement), HistoryCoverageStore(tmp.root).gaps("n1|#room"))
    }

    @Test
    fun failedEmptyPutRetainsGapAndIdenticalRetryClearsDurableCoverage() {
        val store = HistoryCoverageStore(tmp.root)
        val gap = StoredHistoryGap("gap", 1000, toTimestampMs = 2000)
        store.put("n1|#room", listOf(gap))
        val blockedWrite = tmp.newFolder("history-coverage.json.tmp")

        assertFailsWith<IOException> { store.put("n1|#room", emptyList()) }
        assertEquals(listOf(gap), store.gaps("n1|#room"))
        assertEquals(listOf(gap), HistoryCoverageStore(tmp.root).gaps("n1|#room"))
        assertTrue(blockedWrite.delete())

        store.put("n1|#room", emptyList())
        assertTrue(store.gaps("n1|#room").isEmpty())
        assertTrue(HistoryCoverageStore(tmp.root).gaps("n1|#room").isEmpty())
    }

    @Test
    fun failedRenameRetainsBothConversationsAndIdenticalRetryPersistsMergedGaps() {
        val store = HistoryCoverageStore(tmp.root)
        val source = StoredHistoryGap("source", 1000, toTimestampMs = 2000)
        val target = StoredHistoryGap("target", 3000, toTimestampMs = 4000)
        val shared = StoredHistoryGap("shared", 5000, toTimestampMs = 6000)
        store.put("n1|oldnick", listOf(source, shared))
        store.put("n1|newnick", listOf(target, shared))
        val blockedWrite = tmp.newFolder("history-coverage.json.tmp")

        assertFailsWith<IOException> { store.rename("n1|oldnick", "n1|newnick") }
        assertEquals(listOf(source, shared), store.gaps("n1|oldnick"))
        assertEquals(listOf(target, shared), store.gaps("n1|newnick"))
        val unchanged = HistoryCoverageStore(tmp.root)
        assertEquals(listOf(source, shared), unchanged.gaps("n1|oldnick"))
        assertEquals(listOf(target, shared), unchanged.gaps("n1|newnick"))
        assertTrue(blockedWrite.delete())

        assertTrue(store.rename("n1|oldnick", "n1|newnick"))
        assertTrue(store.gaps("n1|oldnick").isEmpty())
        assertEquals(listOf(target, shared, source), store.gaps("n1|newnick"))
        val reopened = HistoryCoverageStore(tmp.root)
        assertTrue(reopened.gaps("n1|oldnick").isEmpty())
        assertEquals(listOf(target, shared, source), reopened.gaps("n1|newnick"))
    }

    @Test
    fun failedNetworkRemovalRetainsCoverageAndIdenticalRetryPersistsOnlyRequestedRemoval() {
        val store = HistoryCoverageStore(tmp.root)
        val gap = StoredHistoryGap("gap", 1000, toTimestampMs = 2000)
        val keys = listOf("n1|#room", "n1|bob", "n10|#room", "n2|#room")
        for (key in keys) store.put(key, listOf(gap))
        val blockedWrite = tmp.newFolder("history-coverage.json.tmp")

        assertFailsWith<IOException> { store.removeNetwork("n1") }
        val unchanged = HistoryCoverageStore(tmp.root)
        for (key in keys) {
            assertEquals(listOf(gap), store.gaps(key))
            assertEquals(listOf(gap), unchanged.gaps(key))
        }
        assertTrue(blockedWrite.delete())

        store.removeNetwork("n1")
        val reopened = HistoryCoverageStore(tmp.root)
        for (key in listOf("n1|#room", "n1|bob")) {
            assertTrue(store.gaps(key).isEmpty())
            assertTrue(reopened.gaps(key).isEmpty())
        }
        for (key in listOf("n10|#room", "n2|#room")) {
            assertEquals(listOf(gap), store.gaps(key))
            assertEquals(listOf(gap), reopened.gaps(key))
        }
    }
}
