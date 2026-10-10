package dev.brentdevs.yardhal.core.data

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotificationPreferencesStoreTests {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun perKindAndPresentationChoicesSurviveRestartIndependently() {
        val directory = temporary.newFolder()
        val store = NotificationPreferencesStore(directory)
        store.update { it.copy(showAvatars = false, showPreview = false).updateKind(NotificationKind.MENTION) { option -> option.copy(enabled = false) } }
        store.update { it.updateKind(NotificationKind.DIRECT_MESSAGE) { option -> option.copy(sound = false, priority = NotificationPriority.QUIET) } }
        store.update { it.updateKind(NotificationKind.INVITE) { option -> option.copy(priority = NotificationPriority.DEFAULT) } }
        val restored = NotificationPreferencesStore(directory).snapshot()
        assertEquals(store.snapshot(), restored)
        assertFalse(restored.mentions.enabled)
        assertTrue(restored.directMessages.enabled)
        assertTrue(restored.invites.enabled)
        assertFalse(restored.directMessages.sound)
        assertEquals(NotificationPriority.QUIET, restored.directMessages.priority)
        assertEquals(NotificationPriority.DEFAULT, restored.invites.priority)
        assertFalse(restored.showAvatars)
        assertFalse(restored.showPreview)
    }

    @Test
    fun failedSaveDoesNotPublishAnUndurablePreferenceSnapshot() {
        val directory = temporary.newFolder()
        val store = NotificationPreferencesStore(directory)
        val before = store.snapshot()
        assertTrue(File(directory, "notification-preferences.json").mkdir())
        assertFailsWith<IOException> { store.update { it.copy(showPreview = false) } }
        assertEquals(before, store.snapshot())
        assertEquals(before, store.preferences.value)
    }

    @Test
    fun concurrentTransformsCannotOverwriteAnotherKindWithAStaleSnapshot() {
        val directory = temporary.newFolder()
        val store = NotificationPreferencesStore(directory)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val first = thread {
            try {
                store.update {
                    firstEntered.countDown()
                    releaseFirst.await()
                    it.updateKind(NotificationKind.MENTION) { option -> option.copy(enabled = false) }
                }
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }
        firstEntered.await()
        val second = thread {
            secondStarted.countDown()
            try {
                store.update { it.updateKind(NotificationKind.INVITE) { option -> option.copy(sound = false) } }
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }
        secondStarted.await()
        releaseFirst.countDown()
        first.join()
        second.join()
        failure.get()?.let { throw it }
        val restored = NotificationPreferencesStore(directory).snapshot()
        assertFalse(restored.mentions.enabled)
        assertFalse(restored.invites.sound)
        assertEquals(restored, store.preferences.value)
    }
}
