package dev.brentdevs.yardhal.core.data

import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeLibraryStoreTests {
    @Test
    fun editedImportedThemeSelectionVariantsAndConversationAccentSurviveRelaunch() {
        val directory = Files.createTempDirectory("yardhal-themes-durable").toFile()
        try {
            val store = ThemeLibraryStore(directory)
            val original = store.snapshot().themes.single()
            val dark = requireNotNull(original.variants.dark)
            val edited = original.variants.copy(
                name = "Edited Night",
                light = dark.copy(dark = false, colors = dark.colors.copy(background = 0xFFFAFAFAL)),
                dark = dark.copy(colors = dark.colors.copy(primary = 0xFF123456L)),
            ).validated()
            store.saveTheme(original.id, edited)
            val imported = ThemeShareLink.decode(ThemeShareLink.encode(edited.copy(name = "Shared")))
            val importedId = store.saveTheme(null, imported)
            store.select(importedId)
            val conversation = ConversationRef.channel("network", "#Example")
            store.setAccent(conversation, 0xFFABCDEF)
            val restored = ThemeLibraryStore(directory).snapshot()
            assertEquals(imported, restored.themes.first { it.id == importedId }.variants)
            assertEquals(importedId, restored.selectedId)
            assertEquals(0xFFABCDEF, restored.accent(ConversationRef.channel("network", "#example")))
            assertNull(restored.accent(ConversationRef.channel("other-network", "#example")))
            assertEquals(0xFFFAFAFA, requireNotNull(restored.definition(false)).colors.background)
            assertEquals(0xFF123456, requireNotNull(restored.definition(true)).colors.primary)
            assertEquals(edited, restored.themes.first { it.id == original.id }.variants)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun duplicationIsIndependentAndDeletingSelectedThemeReturnsToSystemWithoutLosingAccents() {
        val directory = Files.createTempDirectory("yardhal-themes-copy").toFile()
        try {
            val store = ThemeLibraryStore(directory)
            val original = store.snapshot().themes.single()
            val duplicateId = store.duplicate(original.id)
            val duplicate = store.snapshot().themes.first { it.id == duplicateId }
            store.saveTheme(duplicateId, duplicate.variants.copy(name = "Independent"))
            assertEquals(original, store.snapshot().themes.first { it.id == original.id })
            store.select(duplicateId)
            val conversation = ConversationRef.directMessage("network", "Nick")
            store.setAccent(conversation, 0xFF556677)
            store.delete(duplicateId)
            val restored = ThemeLibraryStore(directory).snapshot()
            assertNull(restored.selectedId)
            assertNull(restored.definition(true))
            assertEquals(listOf(original), restored.themes)
            assertEquals(0xFF556677, restored.accent(conversation))
            store.setAccent(conversation, null)
            assertNull(ThemeLibraryStore(directory).snapshot().accent(conversation))
            assertFailsWith<IllegalArgumentException> { store.select(duplicateId) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedEditorSaveRetainsExactDraftPublishedLibraryAndPersistedBytesThenCanRetry() {
        val directory = Files.createTempDirectory("yardhal-themes-failure").toFile()
        try {
            val store = ThemeLibraryStore(directory)
            val original = store.snapshot().themes.single()
            store.select(original.id)
            val persisted = directory.resolve("theme-library.json").readText()
            val draftText = ThemeFileParser.export(original.variants.copy(name = "Unsaved edit"))
            val draft = ThemeEditorDraft(original.id, draftText)
            val blockedTemporary = directory.resolve("theme-library.json.tmp")
            blockedTemporary.mkdir()
            assertNull(draft.save(store))
            assertEquals(draftText, draft.text)
            assertNotNull(draft.error)
            assertEquals(original, store.state.value.themes.single())
            assertEquals(persisted, directory.resolve("theme-library.json").readText())
            assertEquals(store.snapshot(), ThemeLibraryStore(directory).snapshot())
            blockedTemporary.delete()
            assertEquals(original.id, draft.save(store))
            assertNull(draft.error)
            assertEquals("Unsaved edit", ThemeLibraryStore(directory).snapshot().themes.single().variants.name)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedDuplicationDeletionSelectionAndAccentSaveNeverPublishPartialChanges() {
        val directory = Files.createTempDirectory("yardhal-themes-atomic").toFile()
        try {
            val store = ThemeLibraryStore(directory)
            val original = store.snapshot().themes.single()
            store.select(original.id)
            val previous = store.snapshot()
            val persisted = directory.resolve("theme-library.json").readText()
            directory.resolve("theme-library.json.tmp").mkdir()
            assertFailsWith<IOException> { store.duplicate(original.id) }
            assertFailsWith<IOException> { store.delete(original.id) }
            assertFailsWith<IOException> { store.select(null) }
            assertFailsWith<IOException> { store.setAccent(ConversationRef.channel("n", "#c"), 0xFF123456) }
            assertEquals(previous, store.snapshot())
            assertEquals(previous, store.state.value)
            assertEquals(persisted, directory.resolve("theme-library.json").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidDraftIsRetainedAndNeverCreatesLibraryEntry() {
        val directory = Files.createTempDirectory("yardhal-themes-invalid").toFile()
        try {
            val store = ThemeLibraryStore(directory)
            val previous = store.snapshot()
            val draft = ThemeEditorDraft(null, "[theme]\nname=\"Invalid\"\n[colors]\nprimary=\"#wrong\"")
            val text = draft.text
            assertNull(draft.save(store))
            assertTrue(draft.error.orEmpty().contains("color"))
            assertEquals(text, draft.text)
            assertEquals(previous, store.snapshot())
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun accentsFollowConversationRenamesPreserveDestinationAndAreRemovedWithNetwork() {
        val directory = Files.createTempDirectory("yardhal-themes-identity").toFile()
        try {
            val store = ThemeLibraryStore(directory)
            val old = ConversationRef.directMessage("stable-soju-id", "OldNick")
            val renamed = ConversationRef.directMessage("stable-soju-id", "NewNick")
            val occupied = ConversationRef.directMessage("stable-soju-id", "Occupied")
            val other = ConversationRef.directMessage("other", "NewNick")
            store.setAccent(old, 0xFF123456)
            store.setAccent(occupied, 0xFF654321)
            store.setAccent(other, 0xFFABCDEF)
            store.rename(old.storageKey, renamed.storageKey)
            var restored = ThemeLibraryStore(directory)
            assertNull(restored.snapshot().accent(old))
            assertEquals(0xFF123456, restored.snapshot().accent(renamed))
            assertTrue(renamed.storageKey in restored.metadataKeys())
            restored.rename(renamed.storageKey, occupied.storageKey)
            restored = ThemeLibraryStore(directory)
            assertNull(restored.snapshot().accent(renamed))
            assertEquals(0xFF654321, restored.snapshot().accent(occupied))
            restored.deleteNetwork("stable-soju-id")
            val remaining = ThemeLibraryStore(directory)
            assertNull(remaining.snapshot().accent(occupied))
            assertEquals(0xFFABCDEF, remaining.snapshot().accent(other))
            assertEquals(setOf(other.storageKey), remaining.metadataKeys())
        } finally {
            directory.deleteRecursively()
        }
    }
}
