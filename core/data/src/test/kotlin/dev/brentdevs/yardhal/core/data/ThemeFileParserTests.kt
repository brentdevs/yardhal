package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ThemeFileParserTests {

    @Test
    fun parsesSampleTheme() {
        val theme = requireNotNull(ThemeFileParser.parse(ThemeFileParser.SAMPLE_TOML))
        assertEquals("Yardhal Night", theme.name)
        assertTrue(theme.dark)
        assertEquals(0xFF101418, theme.colors.background)
        assertEquals(0xFF9ACBFF, theme.colors.primary)
        assertEquals(0xFF1C2126, theme.colors.surfaceVariant)
    }

    @Test
    fun missingNameRejected() {
        assertNull(ThemeFileParser.parse("[theme]\ndark = true\n"))
    }

    @Test
    fun commentsAndSectionsIgnored() {
        val theme = ThemeFileParser.parse(
            "# comment line\n[theme]\nname = \"T\" # trailing\n[colors]\nbackground = \"#000000\"\n",
        )
        assertNotNull(theme)
        assertEquals("T", theme.name)
    }

    @Test
    fun secondaryDefaultsToBlendOfBackgroundAndPrimary() {
        val theme = requireNotNull(ThemeFileParser.parse("[theme]\nname=\"m\"\n[colors]\nbackground=\"#000000\"\nprimary=\"#FFFFFF\"\n"))
        val expected = (0x00 + 0xFF) / 2
        val secondaryRed = (theme.colors.secondary shr 16) and 0xFF
        assertEquals(expected.toLong(), secondaryRed)
    }

    @Test
    fun malformedFieldsAndUnknownKeysAreRejectedWithActionableErrors() {
        val invalid = listOf(
            "[theme]\nname=\"Bad\"\ndark=yes",
            "[theme]\nname=\"Bad\"\nname=\"Duplicate\"",
            "[theme]\nname=\"Bad\"\n[colors]\nprimary=\"#xyzxyz\"",
            "[theme]\nname=\"Bad\"\n[colors]\nprimary=\"#00112233\"",
            "[theme]\nname=\"Bad\"\n[colors]\nbackground=\"#1234567\"",
            "[theme]\nname=\"Bad\"\n[colors]\nprimray=\"#123456\"",
            "[theme]\nname=\"Bad\"\n[unexpected]",
            "[theme]\nname=\"\"",
            "[theme]\nname=\"Bad\"\n[colors]\n[dark.colors]",
            "[theme]\nname=\"Bad\"\ndark=true\n[light.colors]",
            "[theme]\nname=\"Unterminated",
        )
        for (text in invalid) {
            val error = assertFailsWith<IllegalArgumentException> { ThemeFileParser.importTheme(text) }
            assertTrue(error.message.orEmpty().isNotEmpty(), text)
            assertNull(ThemeFileParser.parse(text))
        }
    }

    @Test
    fun fileAndPortableLinkRoundTripBothVariantsExactly() {
        val dark = requireNotNull(ThemeFileParser.parse(ThemeFileParser.SAMPLE_TOML))
        val light = dark.copy(dark = false, colors = dark.colors.copy(background = 0xFFFDFDFDL, primary = 0xFF00639AL))
        val variants = ThemeVariants("Portable \"theme\"", light, dark).validated()
        assertEquals(variants, ThemeFileParser.importTheme(ThemeFileParser.export(variants)))
        val link = ThemeShareLink.encode(variants)
        assertTrue(link.startsWith("yardhal://theme/v1/"))
        assertEquals(variants, ThemeShareLink.decode(link))
        assertEquals(light.colors, ThemeShareLink.decode(link).resolve(false).colors)
        assertEquals(dark.colors, ThemeShareLink.decode(link).resolve(true).colors)
    }

    @Test
    fun oversizedInputIsRejectedBeforeParsing() {
        assertFailsWith<IllegalArgumentException> { ThemeFileParser.importTheme("x".repeat(ThemeFileParser.MAX_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> { ThemeShareLink.decode("x".repeat(ThemeShareLink.MAX_LENGTH + 1)) }
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("x".repeat(ThemeFileParser.MAX_BYTES + 1).toByteArray())
        assertFailsWith<IllegalArgumentException> { ThemeShareLink.decode(ThemeShareLink.PREFIX + payload) }
    }

    @Test
    fun malformedAndWrongVersionLinksAreRejectedWithoutRemoteFallback() {
        for (link in listOf(
            "https://example.org/theme",
            "yardhal://theme/v2/e30",
            ThemeShareLink.PREFIX,
            ThemeShareLink.PREFIX + "e30=",
            ThemeShareLink.PREFIX + "%%%bad",
            ThemeShareLink.PREFIX + "e30",
            ThemeShareLink.PREFIX + "A",
        )) assertFailsWith<IllegalArgumentException> { ThemeShareLink.decode(link) }
        val json = """{"name":"Bad","dark":{"name":"Bad","dark":true,"colors":{"background":1,"primary":1,"secondary":1,"tertiary":1,"surfaceVariant":1}}}"""
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        assertFailsWith<IllegalArgumentException> { ThemeShareLink.decode(ThemeShareLink.PREFIX + payload) }
    }

    @Test
    fun portableLinkRejectsDuplicateFieldsAndInvalidUtf8() {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val duplicate = "[theme]\nname=\"First\"\nname=\"Second\"".toByteArray()
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        for (bytes in listOf(duplicate, invalidUtf8)) {
            assertFailsWith<IllegalArgumentException> {
                ThemeShareLink.decode(ThemeShareLink.PREFIX + encoder.encodeToString(bytes))
            }
        }
    }
}

