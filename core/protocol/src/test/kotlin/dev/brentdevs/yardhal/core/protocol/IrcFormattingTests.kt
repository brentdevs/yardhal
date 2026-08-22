package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrcFormattingTests {

    @Test
    fun plainTextSingleSpan() {
        val spans = IrcFormatting.parse("no formatting here")
        assertEquals(listOf(IrcFormatting.StyledSpan("no formatting here", TextStyle())), spans)
    }

    @Test
    fun emptyInputEmptyOutput() {
        assertEquals(emptyList(), IrcFormatting.parse(""))
    }

    @Test
    fun boldToggleSplitsSpans() {
        val spans = IrcFormatting.parse("a\u0002bold\u0002after")
        assertEquals(
            listOf(
                IrcFormatting.StyledSpan("a", TextStyle()),
                IrcFormatting.StyledSpan("bold", TextStyle(bold = true)),
                IrcFormatting.StyledSpan("after", TextStyle()),
            ),
            spans,
        )
    }

    @Test
    fun resetClearsAllStyles() {
        val spans = IrcFormatting.parse("\u0002\u001f\u0003 4mixed\u000f plain")
        val last = spans.last()
        assertEquals(" plain", last.text)
        assertEquals(TextStyle(), last.style)
    }

    @Test
    fun colorParsingForegroundAndBackground() {
        val spans = IrcFormatting.parse("\u00034,8colored")
        val style = spans.single().style
        assertEquals(IrcColor.Standard(4), style.foreground)
        assertEquals(IrcColor.Standard(8), style.background)
    }

    @Test
    fun colorCodeAloneResetsColors() {
        val spans = IrcFormatting.parse("\u00032red\u0003default")
        val last = spans.last()
        assertNull(last.style.foreground)
        assertNull(last.style.background)
    }

    @Test
    fun backgroundOnlyAfterCommaRequiresForeground() {
        val spans = IrcFormatting.parse("\u00037text")
        assertEquals(IrcColor.Standard(7), spans.single().style.foreground)
        assertNull(spans.single().style.background)
    }

    @Test
    fun invalidIndexIgnoredAsText() {
        val spans = IrcFormatting.parse("\u000399notacolor")
        assertTrue(spans.first().style.foreground == null || spans.size > 1)
        assertEquals(true, IrcFormatting.plainText(spans).contains("99"))
    }

    @Test
    fun hexColorSixDigits() {
        val spans = IrcFormatting.parse("\u0004ff8800hex")
        assertEquals(IrcColor.Rgb(0xff, 0x88, 0x00), spans.single().style.foreground)
    }

    @Test
    fun rgbaColorEightDigits() {
        val spans = IrcFormatting.parse("\u001088ff8800alpha")
        assertEquals(IrcColor.Rgba(0x88, 0xff, 0x88, 0x00), spans.single().style.foreground)
    }

    @Test
    fun incompleteHexResetsColors() {
        val spans = IrcFormatting.parse("x\u0004abc rest")
        val after = IrcFormatting.plainText(spans)
        assertTrue(after.contains("abc rest"))
        assertNull(spans.last().style.foreground)
    }

    @Test
    fun adjacentSameStateMergesIntoOneSpan() {
        val spans = IrcFormatting.parse("\u0002one\u0002\u0002two")
        assertEquals(1, spans.size)
        assertEquals("onetwo", spans.single().text)
    }

    @Test
    fun allToggleFlagsTracked() {
        val spans = IrcFormatting.parse("\u0002b\u001di\u001fu\u001es\u0011m\u0016r")
        assertEquals(6, spans.size)
        val style = spans.last().style
        assertTrue(style.bold && style.italic && style.underline && style.strikethrough && style.monospace && style.reverse)
        assertEquals("r", spans.last().text)
    }

    @Test
    fun plainTextStripsControlCodes() {
        val text = "\u0002bold\u0002 and \u00033,4colored\u0003 normal"
        assertEquals("bold and colored normal", IrcFormatting.plainText(IrcFormatting.parse(text)))
    }

    @Test
    fun trailingControlCodesProduceNoEmptySpan() {
        val spans = IrcFormatting.parse("text\u0002")
        assertEquals(1, spans.size)
        assertEquals("text", spans.single().text)
    }
}
