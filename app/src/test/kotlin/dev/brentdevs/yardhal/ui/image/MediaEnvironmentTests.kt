package dev.brentdevs.yardhal.ui.image

import dev.brentdevs.yardhal.core.data.MediaPreferencesStore
import dev.brentdevs.yardhal.core.data.MediaRevealState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaEnvironmentTests {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun releasingOverflowedRevealPinsPersistsOnApplicationIoAfterTheUiScopeCloses() = runBlocking {
        val fixture = retentionOverflow()
        val applicationJob = SupervisorJob()
        val applicationScope = CoroutineScope(applicationJob + Dispatchers.Default)
        val uiScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val environment = MediaEnvironment(RemoteImageLoader(temporaryFolder.newFolder("cache")), fixture.store, applicationScope)
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val releaseThread = AtomicReference<Thread>()
        val callerThread = Thread.currentThread()
        try {
            val release = environment.releaseReveal(AutoCloseable {
                releaseThread.set(Thread.currentThread())
                started.countDown()
                assertTrue(proceed.await(5, TimeUnit.SECONDS))
                fixture.retained.close()
            })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            uiScope.cancel()
            proceed.countDown()
            release.join()
            assertFalse(release.isCancelled)
            assertNotSame(callerThread, releaseThread.get())
            assertEquals(MediaPreferencesStore.MAX_REVEAL_ENTRIES, fixture.store.reveals.value.size)
            assertEquals(fixture.store.reveals.value, MediaPreferencesStore(fixture.directory).reveals.value)
            assertNull(environment.retentionError.value)
        } finally {
            proceed.countDown()
            uiScope.cancel()
            applicationJob.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun actualRetentionWriteFailureIsReportedWithoutCancellingTheApplicationOrCrashingDisposal() = runBlocking {
        val fixture = retentionOverflow()
        val applicationJob = SupervisorJob()
        val applicationScope = CoroutineScope(applicationJob + Dispatchers.Default)
        val environment = MediaEnvironment(RemoteImageLoader(temporaryFolder.newFolder("cache")), fixture.store, applicationScope)
        val blockedTemporaryFile = File(fixture.directory, "media-preferences.json.tmp")
        assertTrue(blockedTemporaryFile.mkdir())
        try {
            val release = environment.releaseReveal(fixture.retained)
            release.join()
            assertFalse(release.isCancelled)
            assertTrue(applicationJob.isActive)
            assertNotNull(environment.retentionError.value)
            assertEquals(MediaPreferencesStore.MAX_REVEAL_ENTRIES + 1, fixture.store.reveals.value.size)
            assertTrue(blockedTemporaryFile.delete())
            fixture.store.setReveal("retry", fixture.url, MediaRevealState.HIDDEN)
            assertEquals(MediaPreferencesStore.MAX_REVEAL_ENTRIES, fixture.store.reveals.value.size)
            assertEquals(fixture.store.reveals.value, MediaPreferencesStore(fixture.directory).reveals.value)
            environment.releaseReveal(fixture.handles[1]).join()
            assertNull(environment.retentionError.value)
        } finally {
            blockedTemporaryFile.delete()
            applicationJob.cancelAndJoin()
            fixture.close()
        }
    }

    private fun retentionOverflow(): RetentionFixture {
        val directory = temporaryFolder.newFolder("preferences")
        val url = "https://files.test/image.png"
        val entries = (0 until MediaPreferencesStore.MAX_REVEAL_ENTRIES).joinToString(",") { row ->
            "\"${MediaPreferencesStore.revealKey("row-$row", url)}\":\"HIDDEN\""
        }
        File(directory, "media-preferences.json").writeText("""{"reveals":{$entries}}""")
        val store = MediaPreferencesStore(directory)
        val activeCount = MediaPreferencesStore.MAX_REVEAL_ENTRIES - MediaPreferencesStore.RECENT_REVEAL_ENTRIES + 1
        val handles = (0 until activeCount).map { store.retainReveal("row-$it", url) }
        store.setReveal("new", url, MediaRevealState.HIDDEN)
        assertEquals(MediaPreferencesStore.MAX_REVEAL_ENTRIES + 1, store.reveals.value.size)
        return RetentionFixture(directory, url, store, handles)
    }

    private class RetentionFixture(
        val directory: File,
        val url: String,
        val store: MediaPreferencesStore,
        val handles: List<AutoCloseable>,
    ) : AutoCloseable {
        val retained: AutoCloseable get() = handles[0]
        override fun close() { handles.forEach { it.close() } }
    }
}
