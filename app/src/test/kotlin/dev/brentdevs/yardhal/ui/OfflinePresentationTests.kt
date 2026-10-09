package dev.brentdevs.yardhal.ui

import dev.brentdevs.yardhal.core.data.StorageRecoveryNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfflinePresentationTests {
    private val availableKeys = setOf("network|#saved", "network|#rotated", "network|#chosen")

    @Test
    fun coldSelectionWaitsForShellAndSelectedHistoryRestoration() {
        assertNull(restoredLaunchSelection(null, false, false, "network|#saved", availableKeys))
        assertEquals("network|#saved", restoredLaunchSelection(null, false, true, "network|#saved", availableKeys))
        assertNull(restoredLaunchSelection(null, false, true, "removed|#saved", availableKeys))
    }

    @Test
    fun rotationSelectionWinsOverNormalColdLaunchSelection() {
        assertEquals("network|#rotated", restoredLaunchSelection("network|#rotated", false, true, "network|#saved", availableKeys))
        assertEquals("network|#rotated", restoredLaunchSelection("network|#rotated", true, true, "network|#saved", availableKeys))
    }

    @Test
    fun asynchronousRestoreCannotUndoUserNavigationOrOverviewSelection() {
        assertEquals("network|#chosen", restoredLaunchSelection("network|#chosen", true, true, "network|#saved", availableKeys))
        assertNull(restoredLaunchSelection(null, true, true, "network|#saved", availableKeys))
        assertNull(restoredLaunchSelection(null, true, false, "network|#saved", availableKeys))
    }

    @Test
    fun remainingTemporaryNoticeKeepsMandatoryBannerAfterCompletedNoticeRemoval() {
        val completed = StorageRecoveryNotice("Recovered storage", "/evidence/completed", temporary = false)
        val temporary = StorageRecoveryNotice("Temporary session", "/evidence/active", temporary = true)

        assertEquals(StorageRecoveryBanner.TEMPORARY_SESSION, storageRecoveryBanner(listOf(completed, temporary)))
        assertEquals(StorageRecoveryBanner.TEMPORARY_SESSION, storageRecoveryBanner(listOf(temporary)))
    }

    @Test
    fun completedRecoveryBannerDisappearsOnlyWhenItsActiveNoticeIsRemoved() {
        val completed = StorageRecoveryNotice("Recovered storage", "/evidence/completed", temporary = false)

        assertEquals(StorageRecoveryBanner.WARNING, storageRecoveryBanner(listOf(completed)))
        assertNull(storageRecoveryBanner(emptyList()))
    }

}
