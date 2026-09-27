package dev.brentdevs.yardhal.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconnectPolicyTests {

    private val policy = ReconnectPolicy(
        initialDelayMillis = 1_000,
        maxDelayMillis = 60_000,
        multiplier = 2.0,
        jitterRatio = 0.0,
    )

    @Test
    fun exponentialGrowth() {
        assertEquals(1_000, IrcReconnector.computeDelayMillis(0, policy, 0.5))
        assertEquals(2_000, IrcReconnector.computeDelayMillis(1, policy, 0.5))
        assertEquals(4_000, IrcReconnector.computeDelayMillis(2, policy, 0.5))
    }

    @Test
    fun clampedAtMaximum() {
        assertEquals(60_000, IrcReconnector.computeDelayMillis(20, policy, 0.5))
    }

    @Test
    fun jitterStaysWithinBounds() {
        for (unit in listOf(0.0, 0.25, 0.75, 1.0)) {
            val delay = IrcReconnector.computeDelayMillis(3, policy, unit)
            assertTrue(delay >= 0 && delay <= 60_000)
        }
        assertEquals(4_000, IrcReconnector.computeDelayMillis(2, policy, 0.5))
        assertEquals(8_000, IrcReconnector.computeDelayMillis(3, policy, 0.5))
    }
}
