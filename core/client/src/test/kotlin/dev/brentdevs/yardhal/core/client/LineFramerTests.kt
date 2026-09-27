package dev.brentdevs.yardhal.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
