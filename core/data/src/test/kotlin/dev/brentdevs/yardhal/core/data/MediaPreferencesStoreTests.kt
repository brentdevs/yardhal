package dev.brentdevs.yardhal.core.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MediaPreferencesStoreTests {
    @Test
    fun preferencesAndMessageRevealChoicesSurviveReopeningWithoutSavingUrls() {
        val directory = Files.createTempDirectory("yardhal-media").toFile()
        val url = "https://files.test/asset?signature=private"
        try {
            val store = MediaPreferencesStore(directory)
            val preferences = MediaPreferences(autoLoadImages = true, loadAvatars = false, enableVideos = true, animateImages = false, reducedMotion = true, mediaCacheBytes = 64L * 1024 * 1024, avatarCacheBytes = 0)
            store.update(preferences)
            store.setReveal("network-a/buffer-one/row-20", url, MediaRevealState.HIDDEN)
            store.setReveal("network-a/buffer-two/row-20", url, MediaRevealState.REVEALED)
            val reopened = MediaPreferencesStore(directory)
            assertEquals(preferences, reopened.snapshot())
            assertEquals(MediaRevealState.HIDDEN, reopened.reveal("network-a/buffer-one/row-20", url))
            assertEquals(MediaRevealState.REVEALED, reopened.reveal("network-a/buffer-two/row-20", url))
            assertEquals(MediaRevealState.DEFAULT, reopened.reveal("network-b/buffer-one/row-20", url))
            assertEquals(false, directory.resolve("media-preferences.json").readText().contains("signature=private"))
            reopened.setReveal("network-a/buffer-one/row-20", url, MediaRevealState.DEFAULT)
            assertEquals(MediaRevealState.DEFAULT, MediaPreferencesStore(directory).reveal("network-a/buffer-one/row-20", url))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun budgetsClampAtInclusiveBoundsOnUpdateAndLoad() {
        val directory = Files.createTempDirectory("yardhal-media-budget").toFile()
        try {
            val store = MediaPreferencesStore(directory)
            store.update(MediaPreferences(mediaCacheBytes = -1, avatarCacheBytes = Long.MAX_VALUE))
            assertEquals(0L, store.snapshot().mediaCacheBytes)
            assertEquals(MediaPreferences.MAX_CACHE_BYTES, store.snapshot().avatarCacheBytes)
            directory.resolve("media-preferences.json").writeText("""{"preferences":{"mediaCacheBytes":9999999999,"avatarCacheBytes":-42}}""")
            val loaded = MediaPreferencesStore(directory).snapshot()
            assertEquals(MediaPreferences.MAX_CACHE_BYTES, loaded.mediaCacheBytes)
            assertEquals(0L, loaded.avatarCacheBytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun revealIdentityUsesUnambiguousScopeAndCompleteSignedUrl() {
        assertNotEquals(MediaPreferencesStore.revealKey("a", "bc"), MediaPreferencesStore.revealKey("ab", "c"))
        assertNotEquals(MediaPreferencesStore.revealKey("scope", "https://files.test/a?token=1"), MediaPreferencesStore.revealKey("scope", "https://files.test/a?token=2"))
    }
}
