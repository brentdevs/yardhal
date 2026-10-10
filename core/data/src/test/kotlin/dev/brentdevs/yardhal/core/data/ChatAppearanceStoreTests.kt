package dev.brentdevs.yardhal.core.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import java.io.IOException

class ChatAppearanceStoreTests {

    @Test
    fun preferencesSurviveReopeningAndClampTextScale() {
        val directory = Files.createTempDirectory("yardhal-appearance").toFile()
        try {
            val store = ChatAppearanceStore(directory)
            store.update { it.copy(compact = true, textScale = 2f) }

            assertEquals(ChatAppearancePreferences(compact = true, textScale = 1.25f), ChatAppearanceStore(directory).snapshot())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun loadedPreferencesClampOutOfRangeTextScale() {
        val directory = Files.createTempDirectory("yardhal-appearance-load").toFile()
        try {
            directory.resolve("chat-appearance.json").writeText("""{"compact":true,"textScale":9.0}""")

            assertEquals(
                ChatAppearancePreferences(compact = true, textScale = 1.25f),
                ChatAppearanceStore(directory).snapshot(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun legacyMonospaceIsMigratedOnDiskWithoutLosingExistingAppearance() {
        val directory = Files.createTempDirectory("yardhal-appearance-migration").toFile()
        try {
            val file = directory.resolve("chat-appearance.json")
            file.writeText("""{"compact":true,"textScale":1.15,"dynamicColor":true,"amoledDark":true,"monospaceFont":true}""")
            val migrated = ChatAppearanceStore(directory).snapshot()
            assertEquals(MessageFont.MONOSPACE, migrated.font)
            assertEquals(true, migrated.compact)
            assertEquals(1.15f, migrated.textScale)
            assertEquals(true, migrated.dynamicColor)
            assertEquals(true, migrated.amoledDark)
            assertFalse(file.readText().contains("monospaceFont"))
            assertEquals(migrated, ChatAppearanceStore(directory).snapshot())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun explicitFontWinsOverLegacyFieldDuringMigration() {
        val directory = Files.createTempDirectory("yardhal-appearance-font").toFile()
        try {
            directory.resolve("chat-appearance.json").writeText("""{"monospaceFont":true,"font":"SERIF"}""")
            assertEquals(MessageFont.SERIF, ChatAppearanceStore(directory).snapshot().font)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fullAppearanceSurvivesRelaunchAndTransformsPreserveUnchangedFields() {
        val directory = Files.createTempDirectory("yardhal-appearance-full").toFile()
        try {
            val store = ChatAppearanceStore(directory)
            val changed = store.update { it.copy(
                timestampFormat = TimestampFormat.ISO_DATE_TIME,
                timestampStyle = TimestampStyle.NORMAL,
                timestampPosition = TimestampPosition.BELOW,
                font = MessageFont.SERIF,
                nicknameSuggestionOrder = NicknameSuggestionOrder.RECENT,
                showAvatars = false,
                showUnreadCounts = false,
                compact = true,
                textScale = 1.1f,
            ) }
            store.update { it.copy(dynamicColor = true) }
            assertEquals(changed.copy(dynamicColor = true), ChatAppearanceStore(directory).snapshot())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedSaveDoesNotPublishOrReplacePersistedAppearance() {
        val directory = Files.createTempDirectory("yardhal-appearance-failure").toFile()
        try {
            val store = ChatAppearanceStore(directory)
            val previous = store.update { it.copy(font = MessageFont.MONOSPACE, compact = true) }
            val bytes = directory.resolve("chat-appearance.json").readText()
            directory.resolve("chat-appearance.json.tmp").mkdir()
            assertFailsWith<IOException> { store.update { it.copy(showAvatars = false) } }
            assertEquals(previous, store.snapshot())
            assertEquals(previous, store.preferences.value)
            assertEquals(bytes, directory.resolve("chat-appearance.json").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedMigrationLeavesLegacyFileReadableAndLaterUpdateCompletesCleanCutover() {
        val directory = Files.createTempDirectory("yardhal-appearance-migration-failure").toFile()
        try {
            val file = directory.resolve("chat-appearance.json")
            val legacy = """{"monospaceFont":true,"compact":true}"""
            file.writeText(legacy)
            val blocked = directory.resolve("chat-appearance.json.tmp")
            blocked.mkdir()
            val store = ChatAppearanceStore(directory)
            assertEquals(MessageFont.MONOSPACE, store.snapshot().font)
            assertEquals(true, store.snapshot().compact)
            assertEquals(legacy, file.readText())
            blocked.delete()
            store.update { it.copy(showUnreadCounts = false) }
            assertFalse(file.readText().contains("monospaceFont"))
            assertEquals(store.snapshot(), ChatAppearanceStore(directory).snapshot())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun overlappingTransformsApplyToCurrentStateWithoutLostFields() {
        val directory = Files.createTempDirectory("yardhal-appearance-concurrent").toFile()
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val store = ChatAppearanceStore(directory)
            val ready = java.util.concurrent.CountDownLatch(2)
            val start = java.util.concurrent.CountDownLatch(1)
            val compact = workers.submit {
                ready.countDown()
                start.await()
                store.update { it.copy(compact = true) }
            }
            val avatars = workers.submit {
                ready.countDown()
                start.await()
                store.update { it.copy(showAvatars = false) }
            }
            ready.await()
            start.countDown()
            compact.get()
            avatars.get()
            val restored = ChatAppearanceStore(directory).snapshot()
            assertEquals(true, restored.compact)
            assertEquals(false, restored.showAvatars)
            assertEquals(store.snapshot(), restored)
        } finally {
            workers.shutdownNow()
            directory.deleteRecursively()
        }
    }
}
