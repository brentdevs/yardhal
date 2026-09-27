package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaseMappingTests {

    @Test
    fun asciiFoldsOnlyAsciiLetters() {
        val mapping = CaseMapping.ASCII
        assertEquals("hello[]\\^", mapping.fold("HELLO[]\\^"))
        assertEquals("abc123", mapping.fold("ABC123"))
    }

    @Test
    fun rfc1459FoldsBracketFamily() {
        val mapping = CaseMapping.RFC1459
        assertEquals("abc{}|~", mapping.fold("ABC[]\\^"))
    }

    @Test
    fun strictRfc1459LeavesCaretAlone() {
        val mapping = CaseMapping.STRICT_RFC1459
        assertEquals("abc{}|^", mapping.fold("ABC[]\\^"))
    }

    @Test
    fun equalityRespectsMapping() {
        assertTrue(CaseMapping.RFC1459.equal("Alice[]", "alice{}"))
        assertFalse(CaseMapping.ASCII.equal("Alice[]", "alice{}"))
        assertFalse(CaseMapping.RFC1459.equal("ab", "abc"))
    }

    @Test
    fun foldIsIdempotent() {
        for (mapping in CaseMapping.entries) {
            val once = mapping.fold("MiXeD[Brackets]")
            assertEquals(once, mapping.fold(once))
        }
    }

    @Test
    fun wireNameRoundTrip() {
        for (mapping in CaseMapping.entries) {
            assertEquals(mapping, CaseMapping.fromWireName(mapping.wireName))
        }
        assertNull(CaseMapping.fromWireName(null))
        assertEquals(CaseMapping.RFC1459, CaseMapping.fromWireName("RFC1459"))
        assertNull(CaseMapping.fromWireName("unknown"))
    }
}
