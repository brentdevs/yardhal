package dev.brentdevs.yardhal.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SaslPrepTests {
    private fun prepare(value: String, policy: SaslPrep.Policy = SaslPrep.Policy.STORED): String =
        SaslPrep.prepare(value, policy)

    private fun codePoint(value: Int): String = String(Character.toChars(value))

    @Test
    fun rfc4013Examples() {
        assertEquals("IX", prepare("I\u00ADX"))
        assertEquals("user", prepare("user"))
        assertEquals("USER", prepare("USER"))
        assertEquals("a", prepare("\u00AA"))
        assertEquals("IX", prepare("\u2168"))
        assertEquals(
            SaslMechanismFailure.PROHIBITED_CHARACTER,
            assertFailsWith<SaslMechanismException> { prepare("\u0007") }.failure,
        )
        assertEquals(
            SaslMechanismFailure.BIDIRECTIONAL_STRING,
            assertFailsWith<SaslMechanismException> { prepare("\u06271") }.failure,
        )
    }

    @Test
    fun mapsAllNonAsciiSpacesAndCommonlyIgnoredCharacters() {
        val spaces = listOf(
            0x00A0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
            0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x202F, 0x205F, 0x3000,
        )
        for (space in spaces) assertEquals("a b", prepare("a${codePoint(space)}b"))
        val ignored = listOf(
            0x00AD, 0x034F, 0x1806, 0x180B, 0x180C, 0x180D, 0x200B,
            0x200C, 0x200D, 0x2060, 0xFEFF,
        ) + (0xFE00..0xFE0F)
        for (character in ignored) assertEquals("ab", prepare("a${codePoint(character)}b"))
    }

    @Test
    fun performsCompatibilityDecompositionCanonicalOrderingAndComposition() {
        val examples = listOf(
            "\uFB01\u00ADx" to "fix",
            "\uFF21\uFF22\uFF23" to "ABC",
            "\u212B" to "\u00C5",
            "A\u030A" to "\u00C5",
            "e\u0301" to "\u00E9",
            "\u1E9B\u0323" to "\u1E69",
            "A\u0315\u0300" to "\u00C0\u0315",
            "\u0315\u0300" to "\u0300\u0315",
            "A\u0305\u030A" to "A\u0305\u030A",
            "\u0344" to "\u0308\u0301",
            "\u0340\u0341" to "\u0300\u0301",
            "\u0F73" to "\u0F71\u0F72",
            "\u1100\u1161\u11A8" to "\uAC01",
            "\uAC00\u11A8" to "\uAC01",
            "\uAC01" to "\uAC01",
            "\uD835\uDC00" to "A",
            "\uD834\uDD5E" to "\uD834\uDD57\uD834\uDD65",
        )
        for ((input, expected) in examples) assertEquals(expected, prepare(input))
    }

    @Test
    fun retainsUnicode32NormalizationCorrectionsInsteadOfModernMappings() {
        val corrections = listOf(
            0xF951 to 0x964B,
            0x2F868 to 0x2136A,
            0x2F874 to 0x5F33,
            0x2F91F to 0x43AB,
            0x2F95F to 0x7AAE,
            0x2F9BF to 0x4D57,
        )
        for ((input, expected) in corrections) assertEquals(codePoint(expected), prepare(codePoint(input)))
    }

    @Test
    fun rejectsEveryProhibitedCategoryIncludingSupplementaryBoundaries() {
        val prohibited = listOf(
            0x0000, 0x001F, 0x007F, 0x0080, 0x009F,
            0x06DD, 0x070F, 0x180E, 0x2028, 0x2029, 0x2061, 0x2063, 0x206A, 0x206F,
            0xE000, 0xF8FF, 0xF0000, 0xFFFFD, 0x100000, 0x10FFFD,
            0xFDD0, 0xFDEF, 0xFFFE, 0xFFFF, 0x1FFFE, 0x10FFFF,
            0xFFF9, 0xFFFB, 0xFFFC, 0x2FF0, 0x2FFB,
            0x200E, 0x200F, 0x202A, 0x202E, 0x1D173, 0x1D17A,
            0xE0001, 0xE0020, 0xE007F,
        )
        for (character in prohibited) {
            val failure = assertFailsWith<SaslMechanismException> { prepare(codePoint(character), SaslPrep.Policy.QUERY) }
            assertEquals(SaslMechanismFailure.PROHIBITED_CHARACTER, failure.failure)
            assertEquals(character, failure.codePoint)
        }
    }

    @Test
    fun rejectsUnpairedUtf16SurrogatesButAcceptsValidSupplementaryPairs() {
        for (surrogate in listOf('\uD800', '\uDBFF', '\uDC00', '\uDFFF')) {
            val failure = assertFailsWith<SaslMechanismException> { prepare("a${surrogate}b") }
            assertEquals(SaslMechanismFailure.PROHIBITED_CHARACTER, failure.failure)
            assertEquals(surrogate.code, failure.codePoint)
        }
        assertEquals("\uD834\uDD57", prepare("\uD834\uDD57"))
    }

    @Test
    fun checksUnassignedAgainstUnicode32UsingTheSelectedPolicy() {
        for (character in listOf(0x0221, 0x1D2C, 0x1D455, 0x1F600, 0xE0002)) {
            val input = "x${codePoint(character)}y"
            assertEquals(input, prepare(input, SaslPrep.Policy.QUERY))
            val failure = assertFailsWith<SaslMechanismException> { prepare(input) }
            assertEquals(SaslMechanismFailure.UNASSIGNED_CHARACTER, failure.failure)
            assertEquals(character, failure.codePoint)
        }
        for ((character, expected) in listOf(0x0220 to codePoint(0x0220), 0x0222 to codePoint(0x0222), 0x1D454 to "g", 0x1D456 to "i")) {
            assertEquals(expected, prepare(codePoint(character)))
        }
    }

    @Test
    fun enforcesBothBidirectionalRestrictionsAfterMappingAndNormalization() {
        for (input in listOf("\u0627", "\u06271\u0628", "\u05D0\u0031\u05D1")) {
            assertEquals(input, prepare(input))
        }
        assertEquals("\u0627", prepare("\u00AD\u0627\u200B"))
        assertEquals("\u0627", prepare("\uFE8D"))
        val invalid = listOf(
            "\u0627a\u0628", "a\u0627", "\u0627a", "1\u0627", "\u06271",
            "\u0627\u0301", "\u0627\u2168\u0628", " \u0627", "\u0627 ",
        )
        for (input in invalid) {
            val failure = assertFailsWith<SaslMechanismException> { prepare(input) }
            assertEquals(SaslMechanismFailure.BIDIRECTIONAL_STRING, failure.failure)
            assertEquals(null, failure.codePoint)
        }
    }

    @Test
    fun queryBidiPropertiesRemainFrozenForCharactersAssignedAfterUnicode32() {
        assertEquals("\u0627\u08A0\u0628", prepare("\u0627\u08A0\u0628", SaslPrep.Policy.QUERY))
        val failure = assertFailsWith<SaslMechanismException> { prepare("\u0627\u08A0", SaslPrep.Policy.QUERY) }
        assertEquals(SaslMechanismFailure.BIDIRECTIONAL_STRING, failure.failure)
    }

    @Test
    fun queryCombiningClassesRemainFrozenForCharactersAssignedAfterUnicode32() {
        val value = "\u0323\u0618"
        assertEquals(value, prepare(value, SaslPrep.Policy.QUERY))
        val failure = assertFailsWith<SaslMechanismException> { prepare(value) }
        assertEquals(SaslMechanismFailure.UNASSIGNED_CHARACTER, failure.failure)
        assertEquals(0x0618, failure.codePoint)
    }
}
