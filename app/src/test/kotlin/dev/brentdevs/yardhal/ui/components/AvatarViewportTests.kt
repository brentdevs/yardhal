package dev.brentdevs.yardhal.ui.components

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvatarViewportTests {
    private val viewport = Rect(0f, 24f, 400f, 800f)

    @Test
    fun positiveSizedButOffscreenAvatarsDoNotFetch() {
        for (bounds in listOf(
            Rect(-40f, 40f, 0f, 80f),
            Rect(400f, 40f, 440f, 80f),
            Rect(40f, -16f, 80f, 24f),
            Rect(40f, 800f, 80f, 840f),
        )) {
            assertFalse(avatarIntersectsViewport(bounds, viewport))
        }
    }

    @Test
    fun partiallyVisibleAvatarsFetchOnlyWhenTheirBoundsOverlapTheScreen() {
        assertTrue(avatarIntersectsViewport(Rect(-39f, 40f, 1f, 80f), viewport))
        assertTrue(avatarIntersectsViewport(Rect(399f, 40f, 439f, 80f), viewport))
        assertTrue(avatarIntersectsViewport(Rect(40f, -15f, 80f, 25f), viewport))
        assertTrue(avatarIntersectsViewport(Rect(40f, 799f, 80f, 839f), viewport))
        assertTrue(avatarIntersectsViewport(Rect(40f, 40f, 80f, 80f), viewport))
    }

    @Test
    fun emptyBoundsAndEmptyScreenNeverStartAvatarWork() {
        assertFalse(avatarIntersectsViewport(Rect(40f, 40f, 40f, 80f), viewport))
        assertFalse(avatarIntersectsViewport(Rect(40f, 40f, 80f, 40f), viewport))
        assertFalse(avatarIntersectsViewport(Rect(40f, 40f, 80f, 80f), Rect.Zero))
    }

    @Test
    fun visibleWindowOffsetsAreComparedInTheSameCoordinateSpace() {
        val shiftedViewport = Rect(100f, 224f, 500f, 1_000f)
        assertFalse(avatarIntersectsViewport(Rect(40f, 40f, 80f, 80f), shiftedViewport))
        assertTrue(avatarIntersectsViewport(Rect(140f, 240f, 180f, 280f), shiftedViewport))
    }
}
