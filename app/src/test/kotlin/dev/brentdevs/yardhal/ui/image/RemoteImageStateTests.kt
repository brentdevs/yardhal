package dev.brentdevs.yardhal.ui.image

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import kotlin.test.assertEquals
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

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
