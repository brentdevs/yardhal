package dev.brentdevs.yardhal.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
class EmojiCatalogTests {
    @Test
    fun pickerFiltersUnsupportedDeviceGlyphsInsteadOfShowingBrokenChoices() {
        val lines = sequenceOf(
            "# group: Smileys & Emotion",
            "1F600 ; fully-qualified # 😀 E1.0 grinning face",
            "1F603 ; fully-qualified # 😃 E0.6 grinning face with big eyes",
            "1F600 ; unqualified # 😀 E1.0 grinning face",
        )
        val entries = parseEmojiCatalog(lines) { it == "😀" }
        assertEquals(listOf(EmojiEntry("😀", "grinning face", "Smileys & Emotion")), entries)
    }

    @Test
    fun categoryTransitionsKeepMultiCodepointEmojiAndSearchableNamesIntact() {
        val entries = parseEmojiCatalog(sequenceOf(
            "# group: People & Body",
            "1F469 200D 1F680 ; fully-qualified # 👩‍🚀 E4.0 woman astronaut",
            "# group: Flags",
            "1F1FA 1F1F8 ; fully-qualified # 🇺🇸 E0.6 flag: United States",
        )) { true }
        assertEquals(listOf(
            EmojiEntry("👩‍🚀", "woman astronaut", "People & Body"),
            EmojiEntry("🇺🇸", "flag: United States", "Flags"),
        ), entries)
    }
}
