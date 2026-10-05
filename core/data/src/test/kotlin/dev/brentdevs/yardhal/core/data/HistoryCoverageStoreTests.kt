package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
