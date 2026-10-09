package dev.brentdevs.yardhal.ui

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

}
