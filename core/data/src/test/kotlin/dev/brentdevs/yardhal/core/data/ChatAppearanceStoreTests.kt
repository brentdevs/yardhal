package dev.brentdevs.yardhal.core.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatAppearanceStoreTests {

    @Test
    fun preferencesSurviveReopeningAndClampTextScale() {
        val directory = Files.createTempDirectory("yardhal-appearance").toFile()
        try {
            val store = ChatAppearanceStore(directory)
            store.update(ChatAppearancePreferences(compact = true, textScale = 2f))

            assertEquals(ChatAppearancePreferences(compact = true, textScale = 1.25f), ChatAppearanceStore(directory).snapshot())
        } finally {
            directory.deleteRecursively()
        }
    }
}
