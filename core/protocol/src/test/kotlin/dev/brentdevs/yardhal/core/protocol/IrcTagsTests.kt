package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrcTagsTests {

    @Test
    fun escapesAllSpecialCharacters() {
        val raw = "back\\slash;semi space\rcr\nlf\u0000nul"
        val escaped = IrcTags.escape(raw)
        assertEquals("""back\\slash\:semi\sspace\rcr\nlf\0nul""", escaped)
        assertEquals(raw, IrcTags.unescape(escaped))
    }

    @Test
    fun unknownEscapeDropsBackslash() {
        assertEquals("x", IrcTags.unescape("""\x"""))
    }

    @Test
    fun trailingBackslashDropped() {
        assertEquals("abc", IrcTags.unescape("abc\\"))
    }

    @Test
    fun unescapePassesThroughWithoutBackslashes() {
        assertEquals("plain value", IrcTags.unescape("plain value"))
    }

    @Test
    fun keyValidation() {
        assertTrue(IrcTags.isValidKey("time"))
        assertTrue(IrcTags.isValidKey("+draft/reply"))
        assertTrue(IrcTags.isValidKey("account-tag_2.x"))
        assertFalse(IrcTags.isValidKey(""))
        assertFalse(IrcTags.isValidKey("+"))
        assertFalse(IrcTags.isValidKey("has space"))
        assertFalse(IrcTags.isValidKey("semi;colon"))
    }

    @Test
    fun parseSectionSplitsEntries() {
        val tags = IrcTags.parseSection("a=1;b;c=with\\sspace")
        assertEquals(3, tags.size)
        assertEquals("1", tags["a"])
        assertEquals(null, tags["b"])
        assertEquals("with space", tags["c"])
    }

    @Test
    fun parseSectionIgnoresEmptyEntriesAndInvalidKeys() {
        assertEquals(emptyMap(), IrcTags.parseSection(""))
        assertEquals(mapOf<String, String?>("ok" to null), IrcTags.parseSection(";bad key;;ok"))
    }

    @Test
    fun serializeSectionRoundTrip() {
        val original = linkedMapOf<String, String?>(
            "msgid" to "abc123",
            "+draft/react" to "👍",
            "flag" to null,
        )
        val section = IrcTags.serializeSection(original)
        assertEquals(original, IrcTags.parseSection(section))
    }

    @Test
    fun multibyteValuesSurviveEscaping() {
        val value = "emoji 🎉 and unicode"
        assertEquals(value, IrcTags.unescape(IrcTags.escape(value)))
    }
}
