package dev.brentdevs.yardhal.ui.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.brentdevs.yardhal.core.data.MediaPreferencesStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaCacheLifecycleTests {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun avatarBudgetUsageAndClearAreIndependentOfExistingMediaCache() {
        val media = cachedFile("remote-images", "media", 16)
        val avatar = cachedFile("remote-avatars", "avatar", 8)
        val loader = RemoteImageLoader(temporaryFolder.root)
        loader.configureBudgets(16, 8)
        assertTrue(media.exists())
        assertTrue(avatar.exists())
        assertEquals(ImageCacheUsage(16, 8), loader.usage())
        loader.clear(ImageCacheCategory.AVATAR)
        assertTrue(media.exists())
        assertFalse(avatar.exists())
        assertEquals(ImageCacheUsage(16, 0), loader.usage())
        loader.configureBudgets(0, 8)
        assertFalse(media.exists())
        assertEquals(ImageCacheUsage(0, 0), loader.usage())
    }

    @Test
    fun avatarsKeepExactTtlBoundaryWhileExpiredMediaAndOrphanPlaybackFilesAreRemoved() {
        val now = 2_000_000_000L
        val lifetime = 7L * 24 * 60 * 60 * 1000
        val boundary = cachedFile("remote-avatars", "boundary", 8).also { assertTrue(it.setLastModified(now - lifetime)) }
        val expired = cachedFile("remote-images", "expired", 8).also { assertTrue(it.setLastModified(now - lifetime - 1)) }
        val orphan = cachedFile("remote-video-playback", "orphan", 8)
        RemoteImageLoader(temporaryFolder.root) { now }.maintain()
        assertTrue(boundary.exists())
        assertFalse(expired.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun sharedRequestDisconnectsOnlyAfterLastVisibleConsumerCancels() = runBlocking {
        val connection = BlockingConnection()
        val loader = RemoteImageLoader(temporaryFolder.root, connectionFactory = { connection })
        val first = async(start = CoroutineStart.UNDISPATCHED) { loader.load(connection.url.toString(), 64) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { loader.load(connection.url.toString(), 64) }
        try {
            assertTrue(withContext(Dispatchers.IO) { connection.readStarted.await(5, TimeUnit.SECONDS) })
            first.cancelAndJoin()
            assertFalse(connection.disconnected)
            second.cancelAndJoin()
            assertTrue(connection.disconnected)
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
            loader.cancelRequests()
        }
    }

    @Test
    fun advertisedImageMimeCannotPublishHtmlAsAnImageWithoutSuccessfulDecoding() = runBlocking {
        val url = "https://files.test/not-an-image.png"
        val bytes = "<html><body>Not an image</body></html>".toByteArray()
        val loader = RemoteImageLoader(temporaryFolder.root, connectionFactory = { ImageConnection(it, bytes) })
        assertNull(loader.load(url, 64))
        assertNull(loader.cached(url, 64))
    }

    @Test
    fun cacheClearRemovesDecodedAndDiskImagesAfterPendingPublicationCompletes() = runBlocking<Unit> {
        val bytes = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
        }.toByteArray()
        val url = "https://files.test/photo.png"
        val loader = RemoteImageLoader(temporaryFolder.root, connectionFactory = { ImageConnection(it, bytes) })
        assertNotNull(loader.load(url, 64))
        val diskLock = assertNotNull(loader.javaClass.getDeclaredField("diskLock").apply { isAccessible = true }.get(loader))
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val clearing = Thread {
            started.countDown()
            try {
                loader.clear(ImageCacheCategory.MEDIA)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        try {
            synchronized(diskLock) {
                clearing.start()
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (clearing.state != Thread.State.BLOCKED && clearing.isAlive && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, clearing.state)
                assertNotNull(loader.cached(url, 64))
                assertEquals(bytes.size.toLong(), loader.usage().mediaBytes)
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertNull(loader.cached(url, 64))
            assertEquals(0L, loader.usage().mediaBytes)
            assertNotNull(loader.load(url, 64))
        } finally {
            clearing.join(5_000)
            loader.cancelRequests()
        }
    }

    @Test
    fun avatarRenditionsRetainTemplateIdentityInUiAndDisappearOnLruEvictionAndClear() = runBlocking {
        fun png(size: Int, color: Int): ByteArray = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
        }.toByteArray()
        val small = png(64, Color.RED)
        val large = png(1024, Color.BLUE)
        var requests = 0
        val loader = RemoteImageLoader(temporaryFolder.root, connectionFactory = { url ->
            requests++
            ImageConnection(url, if (url.path == "/pressure.png") large else small)
        })
        val url = "https://profiles.test/avatar/{size}.png"
        assertNotNull(loader.load(url, 16, ImageCacheCategory.AVATAR))
        assertNotNull(loader.load(url, 32, ImageCacheCategory.AVATAR))
        assertEquals(Color.RED, assertNotNull(loader.cachedAvatar(url)).getPixel(0, 0))
        assertEquals(Color.RED, assertNotNull(loader.cached(url, 32, ImageCacheCategory.AVATAR)).getPixel(0, 0))
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        try {
            var image: RemoteImageState? = null
            composition.setContent {
                image = rememberRemoteImage(loader, url, 16, ImageCacheCategory.AVATAR).value
            }
            assertEquals(Color.RED, assertIs<RemoteImageState.Success>(image).bitmap.asAndroidBitmap().getPixel(0, 0))
            assertEquals(2, requests)
            val pressureUrl = "https://profiles.test/pressure.png"
            assertNotNull(loader.load(pressureUrl, 1024, ImageCacheCategory.AVATAR))
            assertNull(loader.cachedAvatar(url))
            assertNull(loader.cached(url, 16, ImageCacheCategory.AVATAR))
            assertNull(loader.cached(url, 32, ImageCacheCategory.AVATAR))
            assertEquals(Color.BLUE, assertNotNull(loader.cachedAvatar(pressureUrl)).getPixel(0, 0))
            loader.clear(ImageCacheCategory.AVATAR)
            assertNull(loader.cachedAvatar(pressureUrl))
            assertEquals(3, requests)
        } finally {
            composition.dispose()
            recomposer.cancel()
            loader.cancelRequests()
        }
    }

    @Test
    fun cancelledVideoDownloadRemovesItsActiveTemporaryFileRatherThanLeavingAnOrphan() = runBlocking {
        val connection = BlockingConnection("video/mp4")
        val loader = RemoteImageLoader(temporaryFolder.root, connectionFactory = { connection })
        val loading = async(start = CoroutineStart.UNDISPATCHED) { loader.loadVideo("https://files.test/video.mp4") }
        try {
            assertTrue(withContext(Dispatchers.IO) { connection.readStarted.await(5, TimeUnit.SECONDS) })
            val playbackDirectory = File(temporaryFolder.root, "remote-video-playback")
            val file = assertNotNull(playbackDirectory.listFiles()?.singleOrNull())
            loader.maintain()
            assertTrue(file.exists())
            loading.cancelAndJoin()
            withTimeout(5_000) {
                while (file.exists()) delay(1)
            }
            assertTrue(connection.disconnected)
            assertEquals(emptyList(), playbackDirectory.listFiles()?.toList())
        } finally {
            loading.cancelAndJoin()
            loader.cancelRequests()
        }
    }

    @Test
    fun onlineReturnClearsFailureBackoffAndExactRetryBoundaryIsEligible() = runBlocking {
        var count = 0
        var now = 1000L
        val loader = RemoteImageLoader(temporaryFolder.root, connectionFactory = { url -> count++; FailedConnection(url) }, clock = { now })
        val url = "https://files.test/photo.png"
        assertNull(loader.load(url, 64))
        assertEquals(1, count)
        now += 10L * 60 * 1000 - 1
        assertNull(loader.load(url, 64))
        assertEquals(1, count)
        now++
        assertNull(loader.load(url, 64))
        assertEquals(2, count)
        val environment = MediaEnvironment(loader, MediaPreferencesStore(temporaryFolder.newFolder("preferences")), this)
        environment.setOnline(false)
        assertFalse(environment.online.value)
        environment.setOnline(true)
        environment.setAppActive(true)
        assertNull(loader.load(url, 64))
        assertEquals(3, count)
    }

    @Test
    fun savedZeroBudgetsApplyBeforeNetworkingAndInactiveLoadsDoNotPoisonFailureRetry() = runBlocking {
        cachedFile("remote-images", "media", 16)
        cachedFile("remote-avatars", "avatar", 8)
        val preferences = MediaPreferencesStore(temporaryFolder.newFolder("preferences"))
        preferences.update { it.copy(mediaCacheBytes = 0, avatarCacheBytes = 0) }
        var requests = 0
        val loader = RemoteImageLoader(temporaryFolder.root, connectionFactory = { url -> requests++; FailedConnection(url) })
        val environment = MediaEnvironment(loader, preferences, this)
        assertEquals(ImageCacheUsage(0, 0), loader.usage())
        assertNull(loader.load("https://files.test/photo.png", 64))
        assertEquals(0, requests)
        environment.setAppActive(true)
        assertNull(loader.load("https://files.test/photo.png", 64))
        assertEquals(1, requests)
    }

    @Test
    fun offlineAndOffscreenImagesAreUnavailableWithoutStartingNetworkOrSpinner() = runBlocking {
        var requests = 0
        val loader = RemoteImageLoader(temporaryFolder.root, connectionFactory = { url -> requests++; FailedConnection(url) })
        val environment = MediaEnvironment(loader, MediaPreferencesStore(temporaryFolder.newFolder("preferences")), this)
        environment.setAppActive(true)
        environment.setOnline(false)
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        try {
            var image: RemoteImageState? = null
            composition.setContent {
                CompositionLocalProvider(LocalMediaEnvironment provides environment) {
                    image = rememberRemoteImage(loader, "https://files.test/photo.png", 64).value
                }
            }
            assertEquals(RemoteImageState.Unavailable, image)
            assertEquals(0, requests)
            composition.setContent {
                image = rememberRemoteImage(loader, "https://files.test/photo.png", 64, visible = false).value
            }
            assertEquals(RemoteImageState.Unavailable, image)
            assertEquals(0, requests)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private fun cachedFile(directoryName: String, name: String, size: Long): File {
        val directory = File(temporaryFolder.root, directoryName)
        assertTrue(directory.isDirectory || directory.mkdirs())
        return File(directory, name).also { RandomAccessFile(it, "rw").use { output -> output.setLength(size) } }
    }

    private class FailedConnection(url: URL) : HttpURLConnection(url) {
        override fun getResponseCode(): Int = 500
        override fun disconnect() = Unit
        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
    }

    private class ImageConnection(url: URL, private val bytes: ByteArray) : HttpURLConnection(url) {
        override fun getResponseCode(): Int = 200
        override fun getContentType(): String = "image/png"
        override fun getContentLengthLong(): Long = bytes.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
    }

    private class BlockingConnection(private val type: String = "image/png") : HttpURLConnection(URL("https://files.test/photo.png")) {
        val readStarted = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        @Volatile var disconnected = false
        override fun getResponseCode(): Int = 200
        override fun getContentType(): String = type
        override fun getContentLengthLong(): Long = -1
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                readStarted.countDown()
                if (!stopped.await(5, TimeUnit.SECONDS)) throw IOException("Request was not cancelled")
                throw IOException("Disconnected")
            }
        }
        override fun disconnect() { disconnected = true; stopped.countDown() }
        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
