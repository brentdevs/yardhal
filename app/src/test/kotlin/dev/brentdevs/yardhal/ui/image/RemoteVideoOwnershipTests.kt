package dev.brentdevs.yardhal.ui.image

import java.io.File
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteVideoOwnershipTests {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun timeoutAfterInnerRequestCompletesStillDeletesUntransferredVideo() = runTest {
        val file = temporaryFolder.newFile("completed.media")
        var releases = 0
        val video = RemoteVideo(file) { releases++; file.delete() }
        val ownership = RemoteVideoOwnership()
        try {
            val result = withTimeoutOrNull(1_000) {
                ownership.retain(video)
                delay(1_001)
                video
            }
            assertNull(result)
            assertTrue(file.exists())
        } finally {
            ownership.close()
        }
        assertFalse(file.exists())
        assertEquals(1, releases)
        ownership.close()
        video.close()
        assertEquals(1, releases)
    }

    @Test
    fun requestCompletionAfterCallerCancellationClosesInsteadOfRetainingTheFile() {
        val file = temporaryFolder.newFile("late.media")
        val video = RemoteVideo(file) { file.delete() }
        val ownership = RemoteVideoOwnership()
        ownership.close()
        assertFailsWith<CancellationException> { ownership.retain(video) }
        assertFalse(file.exists())
    }

    @Test
    fun outerTransferLeavesVideoAliveUntilPlaybackClosesIt() {
        val file: File = temporaryFolder.newFile("playing.media")
        var releases = 0
        val video = RemoteVideo(file) { releases++; file.delete() }
        val ownership = RemoteVideoOwnership()
        ownership.retain(video)
        assertSame(video, ownership.transfer(video))
        ownership.close()
        assertTrue(file.exists())
        video.close()
        video.close()
        assertFalse(file.exists())
        assertEquals(1, releases)
    }
}
