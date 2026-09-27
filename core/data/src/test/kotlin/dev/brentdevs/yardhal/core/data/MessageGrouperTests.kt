package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageGrouperTests {

    private fun groupNewestFirst(vararg times: Long, groupable: (Int) -> Boolean = { true }) =
        MessageGrouper.group(times.toList(), groupable)

    @Test
    fun consecutiveSameSenderWithinWindowGroup() {
        val grouped = groupNewestFirst(60_000L, 30_000L, 0L)
        assertFalse(grouped[0].groupedWithPrevious)
        assertTrue(grouped[1].groupedWithPrevious)
        assertTrue(grouped[2].groupedWithPrevious)
    }

    @Test
    fun sameSenderHoursApartDoesNotGroup() {
        val grouped = groupNewestFirst(2 * 3600_000L, 0L)
        assertFalse(grouped[1].groupedWithPrevious)
    }

    @Test
    fun differentSendersNeverGroup() {
        val grouped = MessageGrouper.group(
            listOf(60_000L, 59_000L),
            isGroupable = { it == 0 },
        )
        assertFalse(grouped[1].groupedWithPrevious)
    }

    @Test
    fun exactlyAtWindowBoundaryDoesNotGroup() {
        val grouped = groupNewestFirst(MessageGrouper.GROUP_WINDOW_MS, 0L)
        assertFalse(grouped[1].groupedWithPrevious)
    }
}
