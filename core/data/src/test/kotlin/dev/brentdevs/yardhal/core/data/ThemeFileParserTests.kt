package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeFileParserTests {

    @Test
    fun parsesSampleTheme() {
        val theme = ThemeFileParser.parse(ThemeFileParser.SAMPLE_TOML)!!
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
        assertEquals("T", theme!!.name)
    }

    @Test
    fun secondaryDefaultsToBlendOfBackgroundAndPrimary() {
        val theme = ThemeFileParser.parse("[theme]\nname=\"m\"\n[colors]\nbackground=\"#000000\"\nprimary=\"#FFFFFF\"\n")!!
        val expected = (0x00 + 0xFF) / 2
        val secondaryRed = (theme.colors.secondary shr 16) and 0xFF
        assertEquals(expected.toLong(), secondaryRed)
    }
}

