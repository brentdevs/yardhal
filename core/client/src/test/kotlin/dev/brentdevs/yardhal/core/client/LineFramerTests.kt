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
    fun oversizedLineIsFlushedAtLimit() {
        val lines = mutableListOf<String>()
        val framer = LineFramer(maxLineBytes = 16, sink = lines::add)
        framer.feed("x".toByteArray().let { ByteArray(40) { _ -> 'a'.code.toByte() } } + "\r\n".toByteArray())
        assertEquals(1, lines.size)
        assertEquals(16, lines[0].length)
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
    fun truncationNeverSplitsACodepoint() {
        val lines = mutableListOf<String>()
        val framer = LineFramer(maxLineBytes = 6, sink = lines::add)
        framer.feed("ab\u20AC\u20AC\r\n".toByteArray(Charsets.UTF_8))
        assertEquals(listOf("ab\u20AC"), lines)
    }
}
