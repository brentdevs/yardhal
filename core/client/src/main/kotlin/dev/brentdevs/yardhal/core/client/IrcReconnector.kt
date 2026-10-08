package dev.brentdevs.yardhal.core.client

import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    public data object Paused : ReconnectState
    public data object Connecting : ReconnectState
    public data class BackingOff(public val delayMillis: Long, public val nextAttemptNumber: Int) : ReconnectState
    public data object Stopped : ReconnectState
}

public data class ReconnectionEvent(
    public val connection: IrcConnection?,
    public val epoch: Long,
    public val event: IrcEvent,
)

public class IrcReconnector internal constructor(
    private val scope: CoroutineScope,
    private val policy: ReconnectPolicy,
    private val connectionFactory: () -> IrcConnection,
    private val random: Random,
    private val factoryDispatcher: CoroutineDispatcher,
) {
    public constructor(
        scope: CoroutineScope,
        policy: ReconnectPolicy = ReconnectPolicy(),
        connectionFactory: () -> IrcConnection,
        random: Random = Random.Default,
    ) : this(scope, policy, connectionFactory, random, Dispatchers.IO)
    private val stateFlow = MutableStateFlow<ReconnectState>(ReconnectState.Idle)
    public val state: StateFlow<ReconnectState> = stateFlow.asStateFlow()

    private val eventsFlow = MutableSharedFlow<ReconnectionEvent>(extraBufferCapacity = 1024)
    public val events: SharedFlow<ReconnectionEvent> = eventsFlow.asSharedFlow()

    private val lock = Any()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var desiredRunning = false
    private var networkAvailable = true
    private var hardBlocked = false
    private var consecutiveFailures = 0
    private var controlRevision = 0L
    private var attemptEpoch = 0L
    private var latestAttemptEpoch = 0L
    private var currentRunEnded: CompletableDeferred<IrcEvent.Disconnected?>? = null
    private var loopJob: Job? = null

    @Volatile
    public var currentConnection: IrcConnection? = null
        private set

    public val currentEpoch: Long
        get() = synchronized(lock) { latestAttemptEpoch }

    public fun start() {
        val loop = synchronized(lock) {
            if (desiredRunning) return
            desiredRunning = true
            hardBlocked = false
            consecutiveFailures = 0
            controlRevision += 1
            wakeups.trySend(Unit)
            if (loopJob == null) {
                scope.launch(start = CoroutineStart.LAZY) { runLoop() }.also { loopJob = it }
            } else {
                loopJob
            }
        }
        loop?.start()
    }

    public fun stop() {
        val connection = synchronized(lock) {
            desiredRunning = false
            attemptEpoch += 1
            controlRevision += 1
            currentRunEnded?.complete(null)
            stateFlow.value = ReconnectState.Stopped
            currentConnection
        }
        connection?.disconnect()
        wakeups.trySend(Unit)
    }

    public fun setNetworkAvailable(available: Boolean) {
        val connection = synchronized(lock) {
            if (networkAvailable == available) return
            networkAvailable = available
            controlRevision += 1
            if (!available) {
                attemptEpoch += 1
                currentRunEnded?.complete(null)
                if (desiredRunning && !hardBlocked) stateFlow.value = ReconnectState.Paused
                currentConnection
            } else {
                if (desiredRunning && !hardBlocked) consecutiveFailures = 0
                null
            }
        }
        connection?.disconnect()
        wakeups.trySend(Unit)
    }

    public fun nudge() {
        synchronized(lock) {
            if (!desiredRunning || hardBlocked) return
            consecutiveFailures = 0
            controlRevision += 1
        }
        wakeups.trySend(Unit)
    }

    public fun sendLine(line: String) {
        currentConnection?.sendLine(line)
    }

    public fun send(message: dev.brentdevs.yardhal.core.protocol.IrcMessage) {
        currentConnection?.send(message)
    }

    private fun eligible(): Boolean = desiredRunning && networkAvailable && !hardBlocked && consecutiveFailures < policy.maxAttempts

    private suspend fun runLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                val revision = synchronized(lock) {
                    if (eligible()) {
                        wakeups.tryReceive()
                        stateFlow.value = ReconnectState.Connecting
                        controlRevision
                    } else {
                        if (desiredRunning && !networkAvailable && !hardBlocked) {
                            stateFlow.value = ReconnectState.Paused
                        } else {
                            stateFlow.value = ReconnectState.Stopped
                        }
                        null
                    }
                }
                if (revision == null) {
                    wakeups.receive()
                    continue
                }
                val result = runOnce()
                val backoff = synchronized(lock) {
                    when {
                        result.interrupted || result.epoch != attemptEpoch || !desiredRunning || !networkAvailable -> null
                        isHardFailure(result.cause) -> {
                            hardBlocked = true
                            stateFlow.value = ReconnectState.Stopped
                            null
                        }
                        controlRevision != revision || result.registered -> null
                        else -> {
                            consecutiveFailures += 1
                            if (consecutiveFailures >= policy.maxAttempts) {
                                stateFlow.value = ReconnectState.Stopped
                                null
                            } else {
                                computeDelayMillis(consecutiveFailures - 1, policy, random.nextDouble()).also {
                                    stateFlow.value = ReconnectState.BackingOff(it, consecutiveFailures + 1)
                                }
                            }
                        }
                    }
                }
                if (backoff != null) withTimeoutOrNull(backoff) { wakeups.receive() }
            }
        } finally {
            val connection = synchronized(lock) {
                attemptEpoch += 1
                currentRunEnded?.complete(null)
                currentConnection.also { currentConnection = null }
            }
            connection?.disconnect()
        }
    }

    private data class RunResult(
        val epoch: Long,
        val registered: Boolean = false,
        val cause: Throwable? = null,
        val interrupted: Boolean = false,
    )

    private suspend fun runOnce(): RunResult = coroutineScope {
        val epoch = synchronized(lock) {
            if (!eligible()) return@coroutineScope RunResult(attemptEpoch, interrupted = true)
            attemptEpoch += 1
            latestAttemptEpoch = attemptEpoch
            attemptEpoch
        }
        val connection = try {
            withContext(factoryDispatcher) { connectionFactory() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = if (isHardFailure(error)) error else IOException("Connection could not be created; check network settings", error)
            if (isCurrentAttempt(epoch)) emitEvent(null, epoch, IrcEvent.Disconnected(failure))
            return@coroutineScope RunResult(epoch, cause = failure, interrupted = !isCurrentAttempt(epoch))
        }
        val disconnected = CompletableDeferred<IrcEvent.Disconnected?>()
        val accepted = synchronized(lock) {
            if (!eligible() || attemptEpoch != epoch) {
                false
            } else {
                currentConnection = connection
                currentRunEnded = disconnected
                true
            }
        }
        if (!accepted) {
            connection.disconnect()
            return@coroutineScope RunResult(epoch, interrupted = true)
        }
        var sawRegistered = false
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            connection.events.collect { event ->
                val active = synchronized(lock) {
                    if (attemptEpoch != epoch || currentConnection !== connection || !desiredRunning || !networkAvailable) {
                        false
                    } else {
                        if (event is IrcEvent.Registered) {
                            sawRegistered = true
                            consecutiveFailures = 0
                        }
                        true
                    }
                }
                if (active) {
                    emitEvent(connection, epoch, event)
                    if (event is IrcEvent.Disconnected) disconnected.complete(event)
                }
            }
        }
        try {
            connection.start()
            val event = disconnected.await()
            RunResult(epoch, sawRegistered, event?.cause, interrupted = event == null)
        } finally {
            synchronized(lock) {
                if (currentConnection === connection) {
                    currentConnection = null
                    currentRunEnded = null
                }
            }
            connection.disconnect()
            withContext(NonCancellable) { collector.cancelAndJoin() }
        }
    }

    private fun isCurrentAttempt(epoch: Long): Boolean = synchronized(lock) {
        attemptEpoch == epoch && desiredRunning && networkAvailable
    }

    private suspend fun emitEvent(connection: IrcConnection?, epoch: Long, event: IrcEvent) {
        eventsFlow.emit(ReconnectionEvent(connection, epoch, event))
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

private fun isHardFailure(error: Throwable?): Boolean {
    val visited = HashSet<Throwable>()
    var current = error
    while (current != null && visited.add(current)) {
        if (current is AuthenticationRejectedException || current is CertificateException ||
            current is SSLPeerUnverifiedException ||
            current is TlsIdentityUnavailableException || (current is Socks5Exception && current.authenticationRejected)) return true
        current = current.cause
    }
    return false
}
