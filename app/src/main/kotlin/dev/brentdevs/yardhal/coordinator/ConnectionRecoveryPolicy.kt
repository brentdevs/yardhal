package dev.brentdevs.yardhal.coordinator

public enum class RecoveryPhase {
    DISCONNECTED,
    USER_DISCONNECTED,
    OFFLINE,
    CONNECTING,
    REGISTERED,
    SERVER_UNREACHABLE,
    AUTHENTICATION_REJECTED,
    CERTIFICATE_REJECTED,
    IDENTIFYING,
}

public enum class RecoverySignal {
    START,
    MANUAL_CONNECT,
    USER_DISCONNECT,
    NETWORK_LOST,
    NETWORK_AVAILABLE,
    TRANSPORT_CHANGED,
    RESUME,
    CONNECTING,
    REGISTERED,
    SERVER_FAILED,
    AUTH_REJECTED,
    CERT_REJECTED,
    IDENTIFYING,
}

public enum class RecoveryAction {
    NONE,
    START,
    PAUSE,
    NUDGE,
    PROBE,
    STOP,
}

public class ConnectionRecoveryPolicy(
    autoConnect: Boolean = true,
    userDisconnected: Boolean = false,
    available: Boolean = true,
) {
    public var desiredConnection: Boolean = autoConnect && !userDisconnected
        private set

    public var phase: RecoveryPhase = when {
        userDisconnected -> RecoveryPhase.USER_DISCONNECTED
        !desiredConnection -> RecoveryPhase.DISCONNECTED
        !available -> RecoveryPhase.OFFLINE
        else -> RecoveryPhase.DISCONNECTED
    }
        private set

    private var networkAvailable = available
    private var currentEpoch: Long? = null
    private var awaitingNewEpoch = false
    private var validationPending = false
    private var offlineTerminalEpoch: Long? = null

    public fun handle(signal: RecoverySignal, epoch: Long? = null): RecoveryAction = when (signal) {
        RecoverySignal.START -> recover(start = true)
        RecoverySignal.MANUAL_CONNECT -> manualConnect()
        RecoverySignal.USER_DISCONNECT -> disconnect()
        RecoverySignal.NETWORK_LOST -> networkLost()
        RecoverySignal.NETWORK_AVAILABLE -> {
            networkAvailable = true
            recover()
        }
        RecoverySignal.TRANSPORT_CHANGED, RecoverySignal.RESUME -> recover()
        RecoverySignal.CONNECTING -> connecting(epoch)
        RecoverySignal.REGISTERED, RecoverySignal.IDENTIFYING,
        RecoverySignal.SERVER_FAILED, RecoverySignal.AUTH_REJECTED,
        RecoverySignal.CERT_REJECTED -> connectionSignal(signal, epoch)
    }

    private fun manualConnect(): RecoveryAction {
        desiredConnection = true
        if (phase == RecoveryPhase.CONNECTING || phase == RecoveryPhase.REGISTERED ||
            phase == RecoveryPhase.IDENTIFYING
        ) return RecoveryAction.NONE
        if (!networkAvailable) {
            phase = RecoveryPhase.OFFLINE
            invalidateTransport()
            return RecoveryAction.PAUSE
        }
        return begin(RecoveryAction.START)
    }

    private fun disconnect(): RecoveryAction {
        val stopped = phase == RecoveryPhase.USER_DISCONNECTED
        desiredConnection = false
        phase = RecoveryPhase.USER_DISCONNECTED
        invalidateTransport()
        return if (stopped) RecoveryAction.NONE else RecoveryAction.STOP
    }

    private fun networkLost(): RecoveryAction {
        networkAvailable = false
        if (!desiredConnection || blocked()) return RecoveryAction.NONE
        if (phase == RecoveryPhase.OFFLINE) return RecoveryAction.NONE
        val terminalEpoch = currentEpoch.takeUnless { awaitingNewEpoch }
        phase = RecoveryPhase.OFFLINE
        invalidateTransport()
        offlineTerminalEpoch = terminalEpoch
        return RecoveryAction.PAUSE
    }

    private fun recover(start: Boolean = false): RecoveryAction {
        if (!desiredConnection || blocked()) return RecoveryAction.NONE
        if (!networkAvailable) {
            if (phase == RecoveryPhase.OFFLINE) {
                return if (start) RecoveryAction.PAUSE else RecoveryAction.NONE
            }
            phase = RecoveryPhase.OFFLINE
            invalidateTransport()
            return RecoveryAction.PAUSE
        }
        return when (phase) {
            RecoveryPhase.REGISTERED -> {
                if (start || validationPending) RecoveryAction.NONE else {
                    validationPending = true
                    RecoveryAction.PROBE
                }
            }
            RecoveryPhase.CONNECTING, RecoveryPhase.IDENTIFYING -> RecoveryAction.NONE
            RecoveryPhase.DISCONNECTED -> begin(RecoveryAction.START)
            RecoveryPhase.OFFLINE, RecoveryPhase.SERVER_UNREACHABLE -> begin(RecoveryAction.NUDGE)
            RecoveryPhase.USER_DISCONNECTED, RecoveryPhase.AUTHENTICATION_REJECTED,
            RecoveryPhase.CERTIFICATE_REJECTED -> RecoveryAction.NONE
        }
    }

    private fun begin(action: RecoveryAction): RecoveryAction {
        phase = RecoveryPhase.CONNECTING
        invalidateTransport()
        return action
    }

    private fun connecting(epoch: Long?): RecoveryAction {
        if (!desiredConnection || !networkAvailable || blocked()) return RecoveryAction.NONE
        val previous = currentEpoch
        if (epoch == null) {
            if (previous != null || phase == RecoveryPhase.REGISTERED ||
                phase == RecoveryPhase.IDENTIFYING
            ) return RecoveryAction.NONE
        } else {
            if (previous != null && epoch <= previous) return RecoveryAction.NONE
            currentEpoch = epoch
        }
        awaitingNewEpoch = false
        validationPending = false
        phase = RecoveryPhase.CONNECTING
        return RecoveryAction.NONE
    }

    private fun connectionSignal(signal: RecoverySignal, epoch: Long?): RecoveryAction {
        if (!desiredConnection || blocked()) return RecoveryAction.NONE
        val offlineTerminal = !networkAvailable && epoch != null && epoch == offlineTerminalEpoch &&
            (signal == RecoverySignal.AUTH_REJECTED || signal == RecoverySignal.CERT_REJECTED)
        if (!networkAvailable && !offlineTerminal) return RecoveryAction.NONE
        if (phase == RecoveryPhase.DISCONNECTED) return RecoveryAction.NONE
        val previous = currentEpoch
        if (previous != null && (epoch != previous || awaitingNewEpoch && !offlineTerminal)) {
            return RecoveryAction.NONE
        }
        if (previous == null && epoch != null) currentEpoch = epoch
        return when (signal) {
            RecoverySignal.REGISTERED -> {
                if (phase != RecoveryPhase.CONNECTING && phase != RecoveryPhase.IDENTIFYING &&
                    phase != RecoveryPhase.REGISTERED
                ) return RecoveryAction.NONE
                phase = RecoveryPhase.REGISTERED
                validationPending = false
                awaitingNewEpoch = false
                RecoveryAction.NONE
            }
            RecoverySignal.IDENTIFYING -> {
                if (phase != RecoveryPhase.CONNECTING && phase != RecoveryPhase.IDENTIFYING) {
                    return RecoveryAction.NONE
                }
                phase = RecoveryPhase.IDENTIFYING
                validationPending = false
                RecoveryAction.NONE
            }
            RecoverySignal.SERVER_FAILED -> {
                phase = RecoveryPhase.SERVER_UNREACHABLE
                validationPending = false
                RecoveryAction.NONE
            }
            RecoverySignal.AUTH_REJECTED -> block(RecoveryPhase.AUTHENTICATION_REJECTED)
            RecoverySignal.CERT_REJECTED -> block(RecoveryPhase.CERTIFICATE_REJECTED)
            else -> RecoveryAction.NONE
        }
    }

    private fun block(rejectedPhase: RecoveryPhase): RecoveryAction {
        phase = rejectedPhase
        invalidateTransport()
        return RecoveryAction.STOP
    }

    private fun blocked(): Boolean = phase == RecoveryPhase.AUTHENTICATION_REJECTED ||
        phase == RecoveryPhase.CERTIFICATE_REJECTED

    private fun invalidateTransport() {
        awaitingNewEpoch = true
        validationPending = false
        offlineTerminalEpoch = null
    }
}
