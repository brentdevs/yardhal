package dev.brentdevs.yardhal.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineFramerTests {

    @Test
    fun splitsSingleCompleteLine() {
        val lines = mutableListOf<String>()
        val framer = LineFramer(sink = lines::add)
        framer.feed("PRIVMSG #a :hi\r\n".toByteArray())
        assertEquals(listOf("PRIVMSG #a :hi"), lines)
    }

    @Test
    fun splitsMultipleLinesInOneChunk() {
        val lines = mutableListOf<String>()
        val framer = LineFramer(sink = lines::add)
        framer.feed("A\r\nB\r\nC\n".toByteArray())
        assertEquals(listOf("A", "B", "C"), lines)
    }

    @Test
    fun reassemblesLineAcrossChunks() {
        val lines = mutableListOf<String>()
        val framer = LineFramer(sink = lines::add)
        framer.feed("PRIV".toByteArray())
        assertTrue(lines.isEmpty())
        framer.feed("MSG #a :hel".toByteArray())
        assertTrue(lines.isEmpty())
        framer.feed("lo\r\nnext\r".toByteArray())
        assertEquals(listOf("PRIVMSG #a :hello", "next"), lines)
    }

    @Test
    fun crlfSplitAcrossChunkBoundaryProducesNoEmptyLine() {
        val lines = mutableListOf<String>()
        val framer = LineFramer(sink = lines::add)
        framer.feed("PING\r".toByteArray())
        framer.feed("\nPONG\r\n".toByteArray())
        assertEquals(listOf("PING", "PONG"), lines)
    }

    @Test
    fun emptyLinesAreSkipped() {
        val lines = mutableListOf<String>()
        val framer = LineFramer(sink = lines::add)
        framer.feed("\r\n\r\nA\r\n".toByteArray())
        assertEquals(listOf("A"), lines)
    }


    @Test
    fun finishReturnsTrailingPartialLine() {
        val lines = mutableListOf<String>()
        val framer = LineFramer(sink = lines::add)
        framer.feed("complete\r\npartial-without-newline".toByteArray())
        assertEquals(listOf("complete"), lines)
        val remainder = framer.finish()
        assertEquals(listOf("partial-without-newline"), remainder)
    }

    @Test
    fun malformedUtf8IsReplacedNotDecodedAsLatin1() {
        val lines = mutableListOf<String>()
        val framer = LineFramer(sink = lines::add)
        val latin1 = "PRIVMSG #a :caf".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xE9.toByte()) + "!\r\n".toByteArray()
        framer.feed(latin1)
        val overlong = "PRIVMSG #a :x".toByteArray() + byteArrayOf(0xC0.toByte(), 0xAF.toByte()) + "\r\n".toByteArray()
        framer.feed(overlong)
        val stray = "PRIVMSG #a :".toByteArray() + byteArrayOf(0x80.toByte(), 0xFF.toByte()) + "ok\r\n".toByteArray()
        framer.feed(stray)
        assertEquals("PRIVMSG #a :caf\uFFFD!", lines[0])
        assertFalse(lines[0].contains('\u00E9'))
        assertTrue(lines[1].startsWith("PRIVMSG #a :x\uFFFD"))
        assertFalse(lines[1].contains('/'))
        assertEquals("PRIVMSG #a :\uFFFD\uFFFDok", lines[2])
    }

    @Test
    fun multibyteSequenceSplitAcrossReadsDecodes() {
        val lines = mutableListOf<String>()
        val framer = LineFramer(sink = lines::add)
        val bytes = "PRIVMSG #a :h\u00E9llo \uD83D\uDE00\r\n".toByteArray(Charsets.UTF_8)
        for (byte in bytes) framer.feed(byteArrayOf(byte))
        assertEquals(listOf("PRIVMSG #a :h\u00E9llo \uD83D\uDE00"), lines)
    }

    @Test
    fun maximumTagsAndBaseMessageSurviveIntact() {
        val lines = mutableListOf<String>()
        val rejected = mutableListOf<LineRejection>()
        val framer = LineFramer(onRejected = rejected::add, sink = lines::add)
        val tags = maximumTagSection()
        val base = baseMessage(510)
        val line = tags + base
        val wire = "$line\r\n".toByteArray(Charsets.UTF_8)
        assertEquals(8191, tags.toByteArray(Charsets.UTF_8).size)
        assertEquals(512, "$base\r\n".toByteArray(Charsets.UTF_8).size)
        assertEquals(8703, wire.size)
        framer.feed(wire)
        assertEquals(listOf(line), lines)
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun finishRetainsExactTaggedAndBaseBudgetsWithoutDispatchingThroughSink() {
        val lines = mutableListOf<String>()
        val rejected = mutableListOf<LineRejection>()
        val framer = LineFramer(onRejected = rejected::add, sink = lines::add)
        val tagged = maximumTagSection() + baseMessage(510)
        for (fragment in tagged.chunked(17)) framer.feed(fragment.toByteArray())
        assertEquals(listOf(tagged), framer.finish())
        framer.feed(baseMessage(510).toByteArray())
        assertEquals(listOf(baseMessage(510)), framer.finish())
        assertTrue(lines.isEmpty())
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun malformedUtf8ReplacementDoesNotChangeWireByteBudgets() {
        val lines = mutableListOf<String>()
        val rejected = mutableListOf<LineRejection>()
        val framer = LineFramer(onRejected = rejected::add, sink = lines::add)
        val prefix = "PRIVMSG #a :"
        val body = "x".repeat(510 - prefix.toByteArray().size - 1)
        val legal = (prefix + body).toByteArray() + byteArrayOf(0xE9.toByte())
        assertEquals(510, legal.size)
        framer.feed(legal + "\r\n".toByteArray())
        framer.feed(legal + "!\r\n".toByteArray())
        framer.feed("PING :recovered\r\n".toByteArray())
        assertEquals(listOf(prefix + body + "\uFFFD", "PING :recovered"), lines)
        assertEquals(listOf(LineRejection.BASE_TOO_LONG), rejected)
    }

    @Test
    fun baseBudgetIncludesPrefixAndReservesCrLf() {
        val lines = mutableListOf<String>()
        val rejected = mutableListOf<LineRejection>()
        val framer = LineFramer(onRejected = rejected::add, sink = lines::add)
        val legal = baseMessage(510)
        framer.feed("$legal\r\n${baseMessage(511)}\r\nPING :recovered\r\n".toByteArray())
        assertEquals(listOf(legal, "PING :recovered"), lines)
        assertEquals(listOf(LineRejection.BASE_TOO_LONG), rejected)
    }

    @Test
    fun tagSectionBudgetIncludesAtAndSeparatingSpace() {
        val lines = mutableListOf<String>()
        val rejected = mutableListOf<LineRejection>()
        val framer = LineFramer(onRejected = rejected::add, sink = lines::add)
        val tags = maximumTagSection()
        val legal = tags + "TAGMSG #a"
        val oversizedTags = tags.dropLast(1) + "x "
        assertEquals(8192, oversizedTags.toByteArray().size)
        framer.feed("$legal\r\n${oversizedTags}TAGMSG #a\r\nPING :recovered\r\n".toByteArray())
        assertEquals(listOf(legal, "PING :recovered"), lines)
        assertEquals(listOf(LineRejection.TAGS_TOO_LONG), rejected)
    }

    @Test
    fun taggedBaseCannotBorrowUnusedTagBudget() {
        val lines = mutableListOf<String>()
        val rejected = mutableListOf<LineRejection>()
        val framer = LineFramer(onRejected = rejected::add, sink = lines::add)
        val legal = "@t=x " + baseMessage(510)
        framer.feed("$legal\r\n@t=x ${baseMessage(511)}\r\nPING :recovered\r\n".toByteArray())
        assertEquals(listOf(legal, "PING :recovered"), lines)
        assertEquals(listOf(LineRejection.BASE_TOO_LONG), rejected)
    }

    @Test
    fun maximumTagSeparatorAndCrLfCanEachSpanFeeds() {
        val lines = mutableListOf<String>()
        val framer = LineFramer(sink = lines::add)
        val tags = maximumTagSection()
        val base = baseMessage(510)
        framer.feed(tags.dropLast(1).toByteArray())
        assertTrue(lines.isEmpty())
        framer.feed(" ".toByteArray())
        framer.feed(base.toByteArray())
        assertTrue(lines.isEmpty())
        framer.feed("\r".toByteArray())
        framer.feed("\nPING :next\r".toByteArray())
        framer.feed("\n".toByteArray())
        assertEquals(listOf(tags + base, "PING :next"), lines)
    }

    @Test
    fun unicodeBaseAndTagsAreCountedInWireBytesAcrossSingleByteFeeds() {
        val lines = mutableListOf<String>()
        val rejected = mutableListOf<LineRejection>()
        val framer = LineFramer(onRejected = rejected::add, sink = lines::add)
        val tags = "@t=" + "\u00E9".repeat(4093) + "x "
        val base = "PRIVMSG #a :" + "\uD83D\uDE00".repeat(124) + "xy"
        assertEquals(8191, tags.toByteArray(Charsets.UTF_8).size)
        assertEquals(510, base.toByteArray(Charsets.UTF_8).size)
        val legal = tags + base
        val oversizedBase = tags + base + "z"
        val oversizedTags = tags.dropLast(1) + "x TAGMSG #a"
        val frames = "$legal\r\n$oversizedBase\r\n$oversizedTags\r\nPING :recovered\r\n"
        for (byte in frames.toByteArray(Charsets.UTF_8)) framer.feed(byteArrayOf(byte))
        assertEquals(listOf(legal, "PING :recovered"), lines)
        assertEquals(listOf(LineRejection.BASE_TOO_LONG, LineRejection.TAGS_TOO_LONG), rejected)
    }

    @Test
    fun missingTagSeparatorRejectsAtDelimiterAndFinish() {
        val lines = mutableListOf<String>()
        val rejected = mutableListOf<LineRejection>()
        val framer = LineFramer(onRejected = rejected::add, sink = lines::add)
        val incompleteTags = "@t=" + "x".repeat(8187)
        assertEquals(8190, incompleteTags.toByteArray().size)
        framer.feed("$incompleteTags\r\nPING :first\r\n".toByteArray())
        framer.feed(incompleteTags.toByteArray())
        assertTrue(framer.finish().isEmpty())
        framer.feed("PING :second\r\n".toByteArray())
        assertEquals(listOf("PING :first", "PING :second"), lines)
        assertEquals(List(2) { LineRejection.MISSING_TAG_SEPARATOR }, rejected)
    }

    @Test
    fun tagOnlyInputMustLeaveRoomForItsSeparator() {
        val lines = mutableListOf<String>()
        val rejected = mutableListOf<LineRejection>()
        val framer = LineFramer(onRejected = rejected::add, sink = lines::add)
        val tagsWithoutSeparator = "@t=" + "x".repeat(8188)
        assertEquals(8191, tagsWithoutSeparator.toByteArray().size)
        framer.feed(tagsWithoutSeparator.toByteArray())
        framer.feed(" PRIVMSG #a :must-not-dispatch\r\nPING :recovered\r\n".toByteArray())
        assertEquals(listOf("PING :recovered"), lines)
        assertEquals(listOf(LineRejection.TAGS_TOO_LONG), rejected)
    }

    @Test
    fun fragmentedHugeInputRejectsOnceAndRecoversWithoutRetainingPartialCommands() {
        val prefixes = listOf(
            "NOTICE #a :" to LineRejection.BASE_TOO_LONG,
            "@t=x NOTICE #a :" to LineRejection.BASE_TOO_LONG,
            "@t=" to LineRejection.TAGS_TOO_LONG,
        )
        val chunk = ByteArray(8192) { 'x'.code.toByte() }
        for ((prefix, reason) in prefixes) {
            val lines = mutableListOf<String>()
            val rejected = mutableListOf<LineRejection>()
            val framer = LineFramer(onRejected = rejected::add, sink = lines::add)
            framer.feed(prefix.toByteArray())
            repeat(512) { framer.feed(chunk) }
            assertTrue(lines.isEmpty())
            assertEquals(listOf(reason), rejected)
            framer.feed("\r".toByteArray())
            framer.feed("\nPING :recovered\r".toByteArray())
            framer.feed("\n".toByteArray())
            assertEquals(listOf("PING :recovered"), lines)
            assertEquals(listOf(reason), rejected)
        }
    }

    @Test
    fun finishDropsOversizedTaggedTagOnlyAndBaseInputThenResets() {
        val cases = listOf(
            baseMessage(511) to LineRejection.BASE_TOO_LONG,
            ("@t=x " + baseMessage(511)) to LineRejection.BASE_TOO_LONG,
            (maximumTagSection().dropLast(1) + "x TAGMSG #a") to LineRejection.TAGS_TOO_LONG,
            ("@t=" + "x".repeat(9000)) to LineRejection.TAGS_TOO_LONG,
        )
        for ((input, reason) in cases) {
            val lines = mutableListOf<String>()
            val rejected = mutableListOf<LineRejection>()
            val framer = LineFramer(onRejected = rejected::add, sink = lines::add)
            for (fragment in input.chunked(13)) framer.feed(fragment.toByteArray())
            assertTrue(framer.finish().isEmpty())
            assertTrue(framer.finish().isEmpty())
            framer.feed("PING :recovered\r\n".toByteArray())
            assertEquals(listOf("PING :recovered"), lines)
            assertEquals(listOf(reason), rejected)
        }
    }

    private fun maximumTagSection(): String =
        "@server=" + "s".repeat(4087) + ";+client=" + "c".repeat(4086) + " "

    private fun baseMessage(contentBytes: Int): String {
        val prefix = ":nick!u@h PRIVMSG #a :"
        return prefix + "b".repeat(contentBytes - prefix.toByteArray().size)
    }
}
