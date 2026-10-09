package dev.brentdevs.yardhal.ui.image

import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteImageStateTests {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rejectedHttpPreviewIsUnavailableBeforeLoadingStarts() = runBlocking {
        val loader = RemoteImageLoader(temporaryFolder.root)
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        try {
            var image: RemoteImageState? = null
            composition.setContent {
                image = rememberRemoteImage(loader, "http://example.com/image.png", 512).value
            }
            assertEquals(RemoteImageState.Unavailable, image)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }

    @Test
    fun changingUrlOrCategoryCannotDisplayPreviousIdentityFromMemory() = runBlocking {
        val bytes = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
        }.toByteArray()
        val loader = RemoteImageLoader(temporaryFolder.root, connectionFactory = { url -> ImageConnection(url, bytes) })
        assertNotNull(loader.load("https://files.test/one.png?token=one", 64))
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        try {
            var image: RemoteImageState? = null
            composition.setContent {
                image = rememberRemoteImage(loader, "https://files.test/one.png?token=one", 64).value
            }
            assertTrue(image is RemoteImageState.Success)
            composition.setContent {
                image = rememberRemoteImage(loader, "https://files.test/one.png?token=two", 64).value
            }
            assertEquals(RemoteImageState.Loading, image)
            composition.setContent {
                image = rememberRemoteImage(loader, "https://files.test/one.png?token=one", 64, ImageCacheCategory.AVATAR).value
            }
            assertEquals(RemoteImageState.Loading, image)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
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

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
