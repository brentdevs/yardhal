package dev.brentdevs.yardhal.core.client

import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun failedStartupConsumesItsWakeupBeforeBackoffAndNudgeRetriesImmediately() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val attemptTimes = mutableListOf<Long>()
        val reconnector = IrcReconnector(
            CoroutineScope(backgroundScope.coroutineContext + dispatcher),
            policy.copy(maxDelayMillis = 4_000),
            connectionFactory = {
                attemptTimes += testScheduler.currentTime
                throw IOException("temporarily unreachable")
            },
            Random.Default,
            dispatcher,
        )
        reconnector.start()
        assertEquals(listOf(0L), attemptTimes)
        assertEquals(ReconnectState.BackingOff(1_000, 2), reconnector.state.value)

        advanceTimeBy(999)
        runCurrent()
        assertEquals(listOf(0L), attemptTimes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 1_000L), attemptTimes)
        assertEquals(ReconnectState.BackingOff(2_000, 3), reconnector.state.value)

        advanceTimeBy(500)
        reconnector.nudge()
        assertEquals(listOf(0L, 1_000L, 1_500L), attemptTimes)
        assertEquals(ReconnectState.BackingOff(1_000, 2), reconnector.state.value)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 1_500L), attemptTimes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 1_500L, 2_500L), attemptTimes)
        reconnector.stop()
    }

    @Test
    fun startWakesTheExistingStoppedLoopWithoutSkippingItsNextBackoff() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val attemptTimes = mutableListOf<Long>()
        val reconnector = IrcReconnector(
            CoroutineScope(backgroundScope.coroutineContext + dispatcher),
            policy,
            connectionFactory = {
                attemptTimes += testScheduler.currentTime
                throw IOException("temporarily unreachable")
            },
            Random.Default,
            dispatcher,
        )
        reconnector.start()
        reconnector.stop()
        assertEquals(ReconnectState.Stopped, reconnector.state.value)
        reconnector.start()
        assertEquals(listOf(0L, 0L), attemptTimes)
        assertEquals(ReconnectState.BackingOff(1_000, 2), reconnector.state.value)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(listOf(0L, 0L), attemptTimes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 0L, 1_000L), attemptTimes)
        reconnector.stop()
    }

    @Test
    fun wrappedCertificateFailuresStopRetriesButGenericTlsFailuresBackOff() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        for (failure in listOf(
            SSLHandshakeException("certificate rejected").apply { initCause(CertificateException("expired")) },
            SSLException("transient transport failure"),
        )) {
            var attempts = 0
            val reconnector = IrcReconnector(
                CoroutineScope(backgroundScope.coroutineContext + dispatcher),
                policy,
                connectionFactory = {
                    attempts += 1
                    throw failure
                },
                Random.Default,
                dispatcher,
            )
            reconnector.start()
            assertEquals(1, attempts)
            if (failure is SSLHandshakeException) {
                assertEquals(ReconnectState.Stopped, reconnector.state.value)
                reconnector.nudge()
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, attempts)
            } else {
                assertEquals(ReconnectState.BackingOff(1_000, 2), reconnector.state.value)
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(2, attempts)
            }
            reconnector.stop()
        }
    }
}
