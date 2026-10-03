package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrcMultilineTests {

    private val roomy = MultilineLimits(maxBytes = 4096, maxLines = 100)

    @Test
    fun parsesCapabilityValueIgnoringUnknownKeys() {
        assertEquals(MultilineLimits(4096, 24), MultilineLimits.parse("max-bytes=4096,max-lines=24"))
        assertEquals(MultilineLimits(40000, null), MultilineLimits.parse("future=1,max-bytes=40000"))
        assertNull(MultilineLimits.parse("max-lines=10"))
        assertNull(MultilineLimits.parse(""))
        assertNull(MultilineLimits.parse(null))
    }

    @Test
    fun framesBatchWithTargetReferenceAndClientTagsOnOpening() {
        val lines = IrcMultiline.split("hello\n\nhow is everyone?", roomy, 400).single()
        val frames = IrcMultiline.frame("r1", "PRIVMSG", "#chan", lines, mapOf("+draft/reply" to "abc"))
        assertEquals(
            listOf(
                "@+draft/reply=abc BATCH +r1 draft/multiline #chan",
                "@batch=r1 PRIVMSG #chan hello",
                "@batch=r1 PRIVMSG #chan :",
                "@batch=r1 PRIVMSG #chan :how is everyone?",
                "BATCH -r1",
            ),
            frames.map { it.toWire() },
        )
    }

    @Test
    fun splitsLongLinesBetweenWordsWithConcatTagAndRoundTrips() {
        val long = (1..40).joinToString(" ") { "word$it" }
        val batches = IrcMultiline.split("intro\n$long", roomy, 64)
        assertEquals(1, batches.size)
        val lines = batches.single()
        assertEquals("intro", lines.first().text)
        assertTrue(lines.drop(2).all { it.concat }, lines.toString())
        assertTrue(lines.dropLast(1).drop(1).all { it.text.endsWith(" ") }, lines.toString())
        assertTrue(lines.all { it.text.toByteArray().size <= 64 })
        assertEquals("intro\n$long", IrcMultiline.combine(lines))
        val wire = IrcMultiline.frame("x", "PRIVMSG", "#c", lines).map { it.toWire() }
        assertTrue(wire.drop(3).dropLast(1).all { it.startsWith("@batch=x;draft/multiline-concat ") }, wire.toString())
    }

    @Test
    fun neverSplitsInsideMultiByteCharacters() {
        val emoji = "😀".repeat(30)
        val lines = IrcMultiline.split("a\n$emoji", roomy, 10).single()
        assertTrue(lines.drop(1).all { it.text == "😀😀" }, lines.toString())
        assertEquals("a\n$emoji", IrcMultiline.combine(lines))
    }

    @Test
    fun respectsMaxLinesByStartingNewBatchWithoutLeadingConcat() {
        val limits = MultilineLimits(maxBytes = 4096, maxLines = 2)
        val batches = IrcMultiline.split("one\ntwo\nthree four five six", limits, 8)
        assertEquals(listOf("one\ntwo", "three four ", "five six"), batches.map(IrcMultiline::combine))
        assertTrue(batches.all { it.size <= 2 && !it.first().concat }, batches.toString())
        assertTrue(batches[1][1].concat)
    }

    @Test
    fun respectsMaxBytesCountingLineFeeds() {
        val limits = MultilineLimits(maxBytes = 9)
        val batches = IrcMultiline.split("aaaa\nbbbb\ncccc", limits, 400)
        assertEquals(listOf("aaaa\nbbbb", "cccc"), batches.map(IrcMultiline::combine))
        assertTrue(batches.all { IrcMultiline.combinedByteLength(it) <= 9 })
    }

    @Test
    fun dropsBatchesConsistingOnlyOfBlankLinesAndNormalizesCarriageReturns() {
        assertEquals(listOf("a\nb"), IrcMultiline.split("a\r\nb\u0000", roomy, 400).map(IrcMultiline::combine))
        assertTrue(IrcMultiline.split("\n\n", roomy, 400).isEmpty())
    }

    @Test
    fun lineBudgetAccountsForNickTargetAndWorstCaseMask() {
        assertEquals(512 - 14 - 10 - 83 - 2 - 5, IrcMultiline.lineBudget("me", "#chan"))
    }
}
