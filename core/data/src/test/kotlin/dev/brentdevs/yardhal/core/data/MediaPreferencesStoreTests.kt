package dev.brentdevs.yardhal.core.data

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MediaPreferencesStoreTests {
    @Test
    fun preferencesAndMessageRevealChoicesSurviveReopeningWithoutSavingUrls() {
        val directory = Files.createTempDirectory("yardhal-media").toFile()
        val url = "https://files.test/asset?signature=private"
        try {
            val store = MediaPreferencesStore(directory)
            val preferences = MediaPreferences(autoLoadImages = true, loadAvatars = false, enableVideos = true, animateImages = false, reducedMotion = true, mediaCacheBytes = 64L * 1024 * 1024, avatarCacheBytes = 0)
            store.update { preferences }
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
            store.update { it.copy(mediaCacheBytes = -1, avatarCacheBytes = Long.MAX_VALUE) }
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
    fun overlappingFieldTransformsReadCurrentPreferencesInsteadOfLosingOtherSettings() {
        val directory = Files.createTempDirectory("yardhal-media-overlap").toFile()
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val store = MediaPreferencesStore(directory)
            val first = executor.submit {
                store.update {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    it.copy(autoLoadImages = true, mediaCacheBytes = 0)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit {
                store.update { it.copy(loadAvatars = false, avatarCacheBytes = MediaPreferences.MAX_CACHE_BYTES) }
            }
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            val expected = MediaPreferences(autoLoadImages = true, loadAvatars = false, mediaCacheBytes = 0, avatarCacheBytes = MediaPreferences.MAX_CACHE_BYTES)
            assertEquals(expected, store.snapshot())
            assertEquals(expected, MediaPreferencesStore(directory).snapshot())
        } finally {
            release.countDown()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun revealRetentionKeepsActiveAndMostRecentlyChangedEntriesAtTheExactCap() {
        val directory = Files.createTempDirectory("yardhal-media-retention").toFile()
        val url = "https://files.test/image.png"
        try {
            seedReveals(directory, url, MediaPreferencesStore.MAX_REVEAL_ENTRIES, setOf(0))
            val store = MediaPreferencesStore(directory)
            val active = store.retainReveal("row-0", url)
            val secondConsumer = store.retainReveal("row-0", url)
            active.close()
            active.close()
            store.setReveal("row-1", url, MediaRevealState.REVEALED)
            store.setReveal("new", url, MediaRevealState.HIDDEN)
            assertEquals(MediaPreferencesStore.MAX_REVEAL_ENTRIES, store.reveals.value.size)
            assertEquals(MediaRevealState.REVEALED, store.reveal("row-0", url))
            assertEquals(MediaRevealState.REVEALED, store.reveal("row-1", url))
            assertEquals(MediaRevealState.DEFAULT, store.reveal("row-2", url))
            assertEquals(MediaRevealState.HIDDEN, store.reveal("new", url))
            secondConsumer.close()
            store.setReveal("newest", url, MediaRevealState.REVEALED)
            assertEquals(MediaRevealState.DEFAULT, store.reveal("row-0", url))
            val reopened = MediaPreferencesStore(directory)
            assertEquals(store.reveals.value, reopened.reveals.value)
            assertEquals(MediaRevealState.REVEALED, reopened.reveal("row-1", url))
            assertEquals(MediaRevealState.REVEALED, reopened.reveal("newest", url))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun oversizedExistingRevealStoreRetainsLatestEntriesDeterministicallyOnLoad() {
        val directory = Files.createTempDirectory("yardhal-media-retention-load").toFile()
        val url = "https://files.test/image.png"
        try {
            seedReveals(directory, url, MediaPreferencesStore.MAX_REVEAL_ENTRIES + 2)
            val store = MediaPreferencesStore(directory)
            assertEquals(MediaPreferencesStore.MAX_REVEAL_ENTRIES, store.reveals.value.size)
            assertEquals(MediaRevealState.DEFAULT, store.reveal("row-0", url))
            assertEquals(MediaRevealState.DEFAULT, store.reveal("row-1", url))
            assertEquals(MediaRevealState.HIDDEN, store.reveal("row-2", url))
            assertEquals(MediaRevealState.HIDDEN, store.reveal("row-${MediaPreferencesStore.MAX_REVEAL_ENTRIES + 1}", url))
            store.setReveal("latest", url, MediaRevealState.REVEALED)
            assertEquals(store.reveals.value, MediaPreferencesStore(directory).reveals.value)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun oldRevealedChoicesExpireBeforeHiddenChoicesWhileTheLatestChangesRemainProtected() {
        val directory = Files.createTempDirectory("yardhal-media-hidden-priority").toFile()
        val url = "https://files.test/image.png"
        val recentBoundary = MediaPreferencesStore.MAX_REVEAL_ENTRIES - MediaPreferencesStore.RECENT_REVEAL_ENTRIES + 1
        try {
            seedReveals(directory, url, MediaPreferencesStore.MAX_REVEAL_ENTRIES, setOf(1, recentBoundary))
            val store = MediaPreferencesStore(directory)
            store.setReveal("new", url, MediaRevealState.HIDDEN)
            assertEquals(MediaRevealState.DEFAULT, store.reveal("row-1", url))
            assertEquals(MediaRevealState.HIDDEN, store.reveal("row-0", url))
            assertEquals(MediaRevealState.REVEALED, store.reveal("row-$recentBoundary", url))
            store.setReveal("newer", url, MediaRevealState.HIDDEN)
            assertEquals(MediaRevealState.DEFAULT, store.reveal("row-$recentBoundary", url))
            assertEquals(MediaRevealState.HIDDEN, store.reveal("row-0", url))
            store.setReveal("newest", url, MediaRevealState.HIDDEN)
            assertEquals(MediaRevealState.DEFAULT, store.reveal("row-0", url))
            assertEquals(MediaRevealState.HIDDEN, store.reveal("new", url))
            assertEquals(MediaRevealState.HIDDEN, store.reveal("newest", url))
            assertEquals(MediaPreferencesStore.MAX_REVEAL_ENTRIES, store.reveals.value.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun seedReveals(directory: java.io.File, url: String, count: Int, revealedRows: Set<Int> = emptySet()) {
        val entries = (0 until count).joinToString(",") { row ->
            val reveal = if (row in revealedRows) MediaRevealState.REVEALED else MediaRevealState.HIDDEN
            "\"${MediaPreferencesStore.revealKey("row-$row", url)}\":\"$reveal\""
        }
        directory.resolve("media-preferences.json").writeText("""{"reveals":{$entries}}""")
    }

    @Test
    fun revealIdentityUsesUnambiguousScopeAndCompleteSignedUrl() {
        assertNotEquals(MediaPreferencesStore.revealKey("a", "bc"), MediaPreferencesStore.revealKey("ab", "c"))
        assertNotEquals(MediaPreferencesStore.revealKey("scope", "https://files.test/a?token=1"), MediaPreferencesStore.revealKey("scope", "https://files.test/a?token=2"))
    }
}
