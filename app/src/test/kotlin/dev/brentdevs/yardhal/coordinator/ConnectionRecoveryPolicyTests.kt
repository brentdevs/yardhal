package dev.brentdevs.yardhal.coordinator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionRecoveryPolicyTests {
    @Test
    fun offlineLaunchPausesAndOnlineRecoveryNudgesOnlyOnce() {
        val policy = ConnectionRecoveryPolicy(available = false)
        assertTrue(policy.desiredConnection)
        assertEquals(RecoveryPhase.OFFLINE, policy.phase)
        assertEquals(RecoveryAction.PAUSE, policy.handle(RecoverySignal.START))
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.RESUME))
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.CONNECTING, 1))
        assertEquals(RecoveryPhase.OFFLINE, policy.phase)
        assertEquals(RecoveryAction.NUDGE, policy.handle(RecoverySignal.NETWORK_AVAILABLE))
        assertEquals(RecoveryPhase.CONNECTING, policy.phase)
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.NETWORK_AVAILABLE))
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.RESUME))
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.START))
    }

    @Test
    fun startupHonorsAutomaticPreferenceAndDurableManualOptOut() {
        val disabled = ConnectionRecoveryPolicy(autoConnect = false)
        val optedOut = ConnectionRecoveryPolicy(userDisconnected = true)
        val both = ConnectionRecoveryPolicy(autoConnect = false, userDisconnected = true)
        for (policy in listOf(disabled, optedOut, both)) {
            assertFalse(policy.desiredConnection)
            for (signal in listOf(
                RecoverySignal.START,
                RecoverySignal.NETWORK_LOST,
                RecoverySignal.NETWORK_AVAILABLE,
                RecoverySignal.TRANSPORT_CHANGED,
                RecoverySignal.RESUME,
                RecoverySignal.CONNECTING,
                RecoverySignal.REGISTERED,
            )) assertEquals(RecoveryAction.NONE, policy.handle(signal))
        }
        assertEquals(RecoveryPhase.DISCONNECTED, disabled.phase)
        assertEquals(RecoveryPhase.USER_DISCONNECTED, optedOut.phase)
        assertEquals(RecoveryPhase.USER_DISCONNECTED, both.phase)
    }

    @Test
    fun manualConnectOverridesStartupPreferenceForOnlyTheCurrentSession() {
        val policy = ConnectionRecoveryPolicy(autoConnect = false, userDisconnected = true)
        assertEquals(RecoveryAction.START, policy.handle(RecoverySignal.MANUAL_CONNECT))
        assertTrue(policy.desiredConnection)
        assertEquals(RecoveryPhase.CONNECTING, policy.phase)
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.MANUAL_CONNECT))
        policy.handle(RecoverySignal.CONNECTING, 1)
        policy.handle(RecoverySignal.SERVER_FAILED, 1)
        assertEquals(RecoveryAction.NUDGE, policy.handle(RecoverySignal.RESUME))
        val nextLaunch = ConnectionRecoveryPolicy(autoConnect = false, userDisconnected = false)
        assertFalse(nextLaunch.desiredConnection)
        assertEquals(RecoveryAction.NONE, nextLaunch.handle(RecoverySignal.START))
    }

    @Test
    fun explicitDisconnectRetiresEstablishedEpochAndDisablesLifecycleRecovery() {
        val policy = registered()
        assertEquals(RecoveryAction.STOP, policy.handle(RecoverySignal.USER_DISCONNECT))
        assertFalse(policy.desiredConnection)
        assertEquals(RecoveryPhase.USER_DISCONNECTED, policy.phase)
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.USER_DISCONNECT))
        for (signal in listOf(
            RecoverySignal.RESUME,
            RecoverySignal.NETWORK_AVAILABLE,
            RecoverySignal.TRANSPORT_CHANGED,
            RecoverySignal.REGISTERED,
            RecoverySignal.SERVER_FAILED,
            RecoverySignal.CONNECTING,
        )) assertEquals(RecoveryAction.NONE, policy.handle(signal, 1))
        assertEquals(RecoveryPhase.USER_DISCONNECTED, policy.phase)
        assertEquals(RecoveryAction.START, policy.handle(RecoverySignal.MANUAL_CONNECT))
        policy.handle(RecoverySignal.REGISTERED, 1)
        assertEquals(RecoveryPhase.CONNECTING, policy.phase)
        policy.handle(RecoverySignal.CONNECTING, 2)
        policy.handle(RecoverySignal.REGISTERED, 2)
        assertEquals(RecoveryPhase.REGISTERED, policy.phase)
    }

    @Test
    fun resumeAndOnlineInterruptUnreachableBackoffWithoutParallelStarts() {
        for (recovery in listOf(RecoverySignal.RESUME, RecoverySignal.NETWORK_AVAILABLE)) {
            val policy = registered()
            assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.SERVER_FAILED, 1))
            assertEquals(RecoveryPhase.SERVER_UNREACHABLE, policy.phase)
            assertEquals(RecoveryAction.NUDGE, policy.handle(recovery))
            assertEquals(RecoveryPhase.CONNECTING, policy.phase)
            assertEquals(RecoveryAction.NONE, policy.handle(recovery))
            assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.RESUME))
            policy.handle(RecoverySignal.CONNECTING, 2)
            assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.CONNECTING, 2))
            policy.handle(RecoverySignal.REGISTERED, 2)
            assertEquals(RecoveryPhase.REGISTERED, policy.phase)
        }
    }

    @Test
    fun establishedValidationCoalescesResumeOnlineAndRouteSignalsUntilAcknowledged() {
        val policy = registered()
        assertEquals(RecoveryAction.PROBE, policy.handle(RecoverySignal.RESUME))
        repeat(10) {
            assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.RESUME))
            assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.NETWORK_AVAILABLE))
            assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.TRANSPORT_CHANGED))
        }
        policy.handle(RecoverySignal.REGISTERED, 1)
        assertEquals(RecoveryAction.PROBE, policy.handle(RecoverySignal.TRANSPORT_CHANGED))
        policy.handle(RecoverySignal.REGISTERED, 1)
        assertEquals(RecoveryAction.PROBE, policy.handle(RecoverySignal.NETWORK_AVAILABLE))
        assertEquals(RecoveryPhase.REGISTERED, policy.phase)
    }

    @Test
    fun staleProbeCompletionCannotRearmOrFailNewConnection() {
        val policy = registered()
        assertEquals(RecoveryAction.PROBE, policy.handle(RecoverySignal.RESUME))
        policy.handle(RecoverySignal.CONNECTING, 2)
        policy.handle(RecoverySignal.REGISTERED, 2)
        assertEquals(RecoveryAction.PROBE, policy.handle(RecoverySignal.RESUME))
        for (signal in listOf(
            RecoverySignal.REGISTERED,
            RecoverySignal.SERVER_FAILED,
            RecoverySignal.AUTH_REJECTED,
            RecoverySignal.CERT_REJECTED,
            RecoverySignal.IDENTIFYING,
            RecoverySignal.CONNECTING,
        )) assertEquals(RecoveryAction.NONE, policy.handle(signal, 1))
        assertEquals(RecoveryPhase.REGISTERED, policy.phase)
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.RESUME))
        policy.handle(RecoverySignal.REGISTERED, 2)
        assertEquals(RecoveryAction.PROBE, policy.handle(RecoverySignal.RESUME))
    }

    @Test
    fun offlinePauseInvalidatesProbeAndRetiresSocketUntilNewAttempt() {
        val policy = registered()
        assertEquals(RecoveryAction.PROBE, policy.handle(RecoverySignal.RESUME))
        assertEquals(RecoveryAction.PAUSE, policy.handle(RecoverySignal.NETWORK_LOST))
        assertEquals(RecoveryPhase.OFFLINE, policy.phase)
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.NETWORK_LOST))
        policy.handle(RecoverySignal.REGISTERED, 1)
        policy.handle(RecoverySignal.AUTH_REJECTED, 0)
        assertEquals(RecoveryPhase.OFFLINE, policy.phase)
        assertEquals(RecoveryAction.NUDGE, policy.handle(RecoverySignal.NETWORK_AVAILABLE))
        policy.handle(RecoverySignal.REGISTERED, 1)
        policy.handle(RecoverySignal.SERVER_FAILED, 1)
        policy.handle(RecoverySignal.CONNECTING, 1)
        assertEquals(RecoveryPhase.CONNECTING, policy.phase)
        policy.handle(RecoverySignal.CONNECTING, 2)
        policy.handle(RecoverySignal.REGISTERED, 2)
        assertEquals(RecoveryAction.PROBE, policy.handle(RecoverySignal.RESUME))
    }

    @Test
    fun authenticationAndCertificateFailuresBlockAutomaticRecoveryUntilManualRetry() {
        for ((signal, phase) in listOf(
            RecoverySignal.AUTH_REJECTED to RecoveryPhase.AUTHENTICATION_REJECTED,
            RecoverySignal.CERT_REJECTED to RecoveryPhase.CERTIFICATE_REJECTED,
        )) {
            val policy = registered()
            assertEquals(RecoveryAction.STOP, policy.handle(signal, 1))
            assertTrue(policy.desiredConnection)
            assertEquals(phase, policy.phase)
            for (automatic in listOf(
                RecoverySignal.START,
                RecoverySignal.RESUME,
                RecoverySignal.TRANSPORT_CHANGED,
                RecoverySignal.NETWORK_LOST,
                RecoverySignal.NETWORK_AVAILABLE,
                RecoverySignal.CONNECTING,
                RecoverySignal.REGISTERED,
            )) assertEquals(RecoveryAction.NONE, policy.handle(automatic, 2))
            assertEquals(phase, policy.phase)
            assertEquals(RecoveryAction.START, policy.handle(RecoverySignal.MANUAL_CONNECT))
            policy.handle(signal, 1)
            assertEquals(RecoveryPhase.CONNECTING, policy.phase)
            policy.handle(RecoverySignal.CONNECTING, 2)
            assertEquals(RecoveryAction.STOP, policy.handle(signal, 2))
            assertEquals(phase, policy.phase)
        }
    }

    @Test
    fun offlineManualRetryClearsBlockButDoesNotOpenTransport() {
        val policy = registered()
        policy.handle(RecoverySignal.AUTH_REJECTED, 1)
        policy.handle(RecoverySignal.NETWORK_LOST)
        assertEquals(RecoveryAction.PAUSE, policy.handle(RecoverySignal.MANUAL_CONNECT))
        assertEquals(RecoveryPhase.OFFLINE, policy.phase)
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.RESUME))
        assertEquals(RecoveryAction.NUDGE, policy.handle(RecoverySignal.NETWORK_AVAILABLE))
    }

    @Test
    fun failedValidationEntersUnreachableRecoveryAndNextEpochRearmsProbes() {
        val policy = registered()
        policy.handle(RecoverySignal.RESUME)
        policy.handle(RecoverySignal.SERVER_FAILED, 1)
        assertEquals(RecoveryPhase.SERVER_UNREACHABLE, policy.phase)
        assertEquals(RecoveryAction.NUDGE, policy.handle(RecoverySignal.RESUME))
        policy.handle(RecoverySignal.CONNECTING, 2)
        policy.handle(RecoverySignal.REGISTERED, 2)
        assertEquals(RecoveryAction.PROBE, policy.handle(RecoverySignal.RESUME))
    }

    @Test
    fun unknownOrUntaggedEpochCannotMutateAnEstablishedTrackedConnection() {
        val policy = registered()
        for (epoch in listOf(null, 0L, 2L)) {
            assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.SERVER_FAILED, epoch))
            assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.AUTH_REJECTED, epoch))
            assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.REGISTERED, epoch))
        }
        assertEquals(RecoveryPhase.REGISTERED, policy.phase)
        assertEquals(RecoveryAction.PROBE, policy.handle(RecoverySignal.RESUME))
    }

    @Test
    fun identifyingDoesNotProbeOrCompleteBeforeIntendedAuthentication() {
        val policy = ConnectionRecoveryPolicy()
        assertEquals(RecoveryAction.START, policy.handle(RecoverySignal.START))
        policy.handle(RecoverySignal.CONNECTING, 1)
        policy.handle(RecoverySignal.IDENTIFYING, 1)
        assertEquals(RecoveryPhase.IDENTIFYING, policy.phase)
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.RESUME))
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.TRANSPORT_CHANGED))
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.NETWORK_AVAILABLE))
        policy.handle(RecoverySignal.REGISTERED, 1)
        assertEquals(RecoveryPhase.REGISTERED, policy.phase)
        assertEquals(RecoveryAction.PROBE, policy.handle(RecoverySignal.RESUME))
    }

    @Test
    fun initialFactoryAuthenticationFailureCanBlockBeforeTransportOpens() {
        val policy = ConnectionRecoveryPolicy()
        policy.handle(RecoverySignal.START)
        assertEquals(RecoveryAction.STOP, policy.handle(RecoverySignal.AUTH_REJECTED, 0))
        assertEquals(RecoveryPhase.AUTHENTICATION_REJECTED, policy.phase)
        assertEquals(RecoveryAction.START, policy.handle(RecoverySignal.MANUAL_CONNECT))
        policy.handle(RecoverySignal.CONNECTING, 1)
        assertEquals(RecoveryAction.STOP, policy.handle(RecoverySignal.AUTH_REJECTED, 1))
    }

    @Test
    fun ignoredEventsBeforeStartCannotClaimTheFirstConnectionEpoch() {
        val policy = ConnectionRecoveryPolicy()
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.REGISTERED, 1))
        assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.AUTH_REJECTED, 1))
        assertEquals(RecoveryPhase.DISCONNECTED, policy.phase)
        assertEquals(RecoveryAction.START, policy.handle(RecoverySignal.START))
        policy.handle(RecoverySignal.CONNECTING, 1)
        policy.handle(RecoverySignal.REGISTERED, 1)
        assertEquals(RecoveryPhase.REGISTERED, policy.phase)
    }

    @Test
    fun repeatedUntaggedConnectingCannotDemoteRegisteredOrIdentifyingSessions() {
        val policy = ConnectionRecoveryPolicy()
        policy.handle(RecoverySignal.START)
        policy.handle(RecoverySignal.CONNECTING)
        policy.handle(RecoverySignal.IDENTIFYING)
        policy.handle(RecoverySignal.CONNECTING)
        assertEquals(RecoveryPhase.IDENTIFYING, policy.phase)
        policy.handle(RecoverySignal.REGISTERED)
        policy.handle(RecoverySignal.CONNECTING)
        assertEquals(RecoveryPhase.REGISTERED, policy.phase)
        assertEquals(RecoveryAction.PROBE, policy.handle(RecoverySignal.RESUME))
    }

    @Test
    fun bufferedCurrentAuthenticationAndCertificateFailuresRemainTerminalAfterNetworkLoss() {
        for ((signal, phase) in listOf(
            RecoverySignal.AUTH_REJECTED to RecoveryPhase.AUTHENTICATION_REJECTED,
            RecoverySignal.CERT_REJECTED to RecoveryPhase.CERTIFICATE_REJECTED,
        )) {
            val policy = registered()
            policy.handle(RecoverySignal.NETWORK_LOST)
            policy.handle(RecoverySignal.NETWORK_LOST)
            policy.handle(RecoverySignal.START)
            assertEquals(RecoveryAction.STOP, policy.handle(signal, 1))
            assertEquals(phase, policy.phase)
            assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.NETWORK_AVAILABLE))
            assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.RESUME))
        }
    }

    @Test
    fun offlineTerminalRejectionsCannotSurviveManualRetirementOrOptOut() {
        for (signal in listOf(RecoverySignal.AUTH_REJECTED, RecoverySignal.CERT_REJECTED)) {
            val manuallyRetired = registered()
            manuallyRetired.handle(RecoverySignal.NETWORK_LOST)
            manuallyRetired.handle(RecoverySignal.MANUAL_CONNECT)
            assertEquals(RecoveryAction.NONE, manuallyRetired.handle(signal, 1))
            assertEquals(RecoveryPhase.OFFLINE, manuallyRetired.phase)
            val optedOut = registered()
            optedOut.handle(RecoverySignal.NETWORK_LOST)
            optedOut.handle(RecoverySignal.USER_DISCONNECT)
            assertEquals(RecoveryAction.NONE, optedOut.handle(signal, 1))
            assertEquals(RecoveryPhase.USER_DISCONNECTED, optedOut.phase)
            optedOut.handle(RecoverySignal.MANUAL_CONNECT)
            assertEquals(RecoveryAction.NONE, optedOut.handle(signal, 1))
            assertEquals(RecoveryPhase.OFFLINE, optedOut.phase)
        }
    }

    @Test
    fun bufferedOfflineFailureCannotBlockOnlineReplacementOrClaimUnknownOrigin() {
        for (signal in listOf(RecoverySignal.AUTH_REJECTED, RecoverySignal.CERT_REJECTED)) {
            val policy = registered()
            policy.handle(RecoverySignal.NETWORK_LOST)
            for (epoch in listOf(null, 0L, 2L)) {
                assertEquals(RecoveryAction.NONE, policy.handle(signal, epoch))
            }
            assertEquals(RecoveryAction.NUDGE, policy.handle(RecoverySignal.NETWORK_AVAILABLE))
            assertEquals(RecoveryAction.NONE, policy.handle(signal, 1))
            assertEquals(RecoveryPhase.CONNECTING, policy.phase)
            policy.handle(RecoverySignal.NETWORK_LOST)
            assertEquals(RecoveryAction.NONE, policy.handle(signal, 1))
            assertEquals(RecoveryPhase.OFFLINE, policy.phase)
        }
    }

    private fun registered(): ConnectionRecoveryPolicy = ConnectionRecoveryPolicy().also {
        assertEquals(RecoveryAction.START, it.handle(RecoverySignal.START))
        it.handle(RecoverySignal.CONNECTING, 1)
        it.handle(RecoverySignal.REGISTERED, 1)
        assertEquals(RecoveryPhase.REGISTERED, it.phase)
    }
}
