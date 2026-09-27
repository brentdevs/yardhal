package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageGrouperTests {

    private fun groupNewestFirst(vararg times: Long, senders: List<String>? = null, groupable: (Int) -> Boolean = { true }) =
        MessageGrouper.group(
            messages = times.toList(),
            isGroupable = groupable,
            senderOf = { senders?.getOrNull(it) ?: "nick" },
        )

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
    fun differentSendersNeverGroupEvenWithinWindow() {
        val grouped = groupNewestFirst(
            60_000L,
            59_000L,
            senders = listOf("bob", "alice"),
        )
        assertFalse(grouped[1].groupedWithPrevious)
    }

    @Test
    fun groupBreaksWhenSenderChangesMidRun() {
        val grouped = groupNewestFirst(
            90_000L,
            60_000L,
            30_000L,
            senders = listOf("carol", "bob", "bob"),
        )
        assertFalse(grouped[0].groupedWithPrevious)
        assertFalse(grouped[1].groupedWithPrevious)
        assertTrue(grouped[2].groupedWithPrevious)
    }

    @Test
    fun exactlyAtWindowBoundaryDoesNotGroup() {
        val grouped = groupNewestFirst(MessageGrouper.GROUP_WINDOW_MS, 0L)
        assertFalse(grouped[1].groupedWithPrevious)
    }
}
