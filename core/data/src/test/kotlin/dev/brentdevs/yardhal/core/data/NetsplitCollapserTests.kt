package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetsplitCollapserTests {

    @Test
    fun tracksOnlyNetsplitBatches() {
        val collapser = NetsplitCollapser()
        assertTrue(collapser.onStart("1", "netsplit", listOf("a", "b")))
        assertFalse(collapser.onStart("2", "chathistory", emptyList()))
    }

    @Test
    fun suppressesQuitsDuringNetsplitAndSummarizes() {
        val collapser = NetsplitCollapser()
        collapser.onStart("b1", "netsplit", listOf("a.example", "b.example"))
        assertTrue(collapser.isSuppressing("QUIT"))
        assertFalse(collapser.isSuppressing("JOIN"))
        collapser.recordSuppressed("QUIT")
        collapser.recordSuppressed("QUIT")
        collapser.recordSuppressed("QUIT")

        val summary = collapser.onEnd("b1")!!
        assertEquals(3, summary.count)
        assertTrue(summary.isSplit)
        assertEquals("⇅ 3 users quit during netsplit (a.example ↔ b.example)", summary.toString())
        assertFalse(collapser.isSuppressing("QUIT"))
        assertNull(collapser.onEnd("b1"))
    }

    @Test
    fun netjoinSuppressesJoins() {
        val collapser = NetsplitCollapser()
        collapser.onStart("j1", "netjoin", emptyList())
        assertTrue(collapser.isSuppressing("JOIN"))
        collapser.recordSuppressed("JOIN")
        collapser.recordSuppressed("JOIN")
        val summary = collapser.onEnd("j1")!!
        assertEquals(2, summary.count)
        assertFalse(summary.isSplit)
        assertEquals("⇜ 2 users returned from netsplit", summary.toString())
    }
}
