package dev.brentdevs.yardhal.core.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

public data class ReconnectPolicy(
    public val initialDelayMillis: Long = 1_000,
    public val maxDelayMillis: Long = 60_000,
    public val multiplier: Double = 2.0,
    public val jitterRatio: Double = 0.2,
    public val maxAttempts: Int = 10,
) {
    init {
        require(initialDelayMillis > 0)
        require(maxDelayMillis >= initialDelayMillis)
        require(multiplier >= 1.0)
        require(jitterRatio in 0.0..1.0)
        require(maxAttempts > 0)
    }
}

public sealed interface ReconnectState {
    public data object Idle : ReconnectState
    public data object Connecting : ReconnectState
    public data class BackingOff(public val delayMillis: Long, public val nextAttemptNumber: Int) : ReconnectState
    public data object Stopped : ReconnectState
}

public class IrcReconnector(
    private val scope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val connectionFactory: () -> IrcConnection,
    private val random: Random = Random.Default,
) {
    private val stateFlow = MutableStateFlow<ReconnectState>(ReconnectState.Idle)
    public val state: StateFlow<ReconnectState> = stateFlow.asStateFlow()

    private val eventsFlow = MutableSharedFlow<IrcEvent>(extraBufferCapacity = 1024)
    public val events: SharedFlow<IrcEvent> = eventsFlow.asSharedFlow()

    @Volatile
    private var desiredRunning: Boolean = false

    @Volatile
    private var currentConnection: IrcConnection? = null

    @Volatile
    private var consecutiveFailures: Int = 0

    private var loopJob: Job? = null

    public fun start() {
        if (desiredRunning) return
        desiredRunning = true
        loopJob = scope.launch { runLoop() }
    }

    public fun stop() {
        desiredRunning = false
        currentConnection?.disconnect()
        loopJob?.cancel()
        loopJob = null
        stateFlow.value = ReconnectState.Stopped
    }

    private suspend fun runLoop() {
        while (desiredRunning && consecutiveFailures < policy.maxAttempts) {
            stateFlow.value = ReconnectState.Connecting
            val registeredDuringRun = runOnce()
            if (!desiredRunning) break
            if (registeredDuringRun) continue
            consecutiveFailures += 1
            if (consecutiveFailures >= policy.maxAttempts) break
            val wait = computeDelayMillis(consecutiveFailures - 1, policy, random.nextDouble())
            stateFlow.value = ReconnectState.BackingOff(wait, consecutiveFailures + 1)
            delay(wait)
        }
        if (consecutiveFailures >= policy.maxAttempts) {
            stateFlow.value = ReconnectState.Stopped
        }
    }

    private suspend fun runOnce(): Boolean {
        val connection = connectionFactory()
        currentConnection = connection
        var sawRegistered = false
        val disconnected = CompletableDeferred<Unit>()
        val collector = scope.launch {
            connection.events.collect { event ->
                if (event is IrcEvent.Registered) {
                    sawRegistered = true
                    consecutiveFailures = 0
                }
                eventsFlow.emit(event)
                if (event is IrcEvent.Disconnected) disconnected.complete(Unit)
            }
        }
        connection.start()
        disconnected.await()
        currentConnection = null
        return sawRegistered
    }

    public companion object {

        public fun computeDelayMillis(
            failureIndexZeroBased: Int,
            policy: ReconnectPolicy,
            unitRandom: Double,
        ): Long {
            val exponentiated = policy.initialDelayMillis *
                Math.pow(policy.multiplier, failureIndexZeroBased.coerceAtLeast(0).toDouble())
            val clamped = exponentiated.toLong().coerceIn(0L, policy.maxDelayMillis)
            val jitterSpan = clamped * policy.jitterRatio
            val jittered = clamped + ((unitRandom * 2.0) - 1.0) * jitterSpan
            return jittered.toLong().coerceIn(0L, policy.maxDelayMillis)
        }
    }
}
