package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.HistoryCoverageStore
import dev.brentdevs.yardhal.core.data.MessageCursor
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.StoredHistoryGap
import dev.brentdevs.yardhal.core.data.StoredMessage
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.ChatHistorySupport
import dev.brentdevs.yardhal.core.protocol.HistoryAnchor
import dev.brentdevs.yardhal.core.protocol.ISupport
import dev.brentdevs.yardhal.core.protocol.IrcChatHistory
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal interface HistoryHost {
    val historyBuffers: Map<String, ConversationBuffer>
    fun historyRef(networkId: String, target: String): ConversationRef
    fun ensureHistoryBuffer(ref: ConversationRef)
    fun updateHistoryBuffer(key: String, transform: (ConversationBuffer) -> ConversationBuffer)
    suspend fun mergeStoredHistory(ref: ConversationRef, messages: List<StoredMessage>)
    fun replayHistory(
        networkId: String,
        generation: Long,
        epoch: Long,
        result: HistoryResult,
        excludedKeys: Set<String>,
        beforeReplay: (List<InboundEffect>) -> Unit,
    )
    fun sendHistory(networkId: String, generation: Long, epoch: Long, line: String): Boolean
    fun historyConnectionActive(networkId: String, generation: Long, epoch: Long): Boolean
    fun reconnectHistory(networkId: String)
    fun historySessionLock(networkId: String, generation: Long, epoch: Long): Any?
    fun enqueueHistoryStorage(operation: suspend () -> Unit)
}

internal class HistoryCoordinator(
    private val messageStore: MessageStore,
    private val coverageStore: HistoryCoverageStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val elapsedClock: () -> Long,
    private val host: HistoryHost,
) {
    private enum class Purpose { INITIAL, OLDER, CATCH_UP, GAP, DISCOVERY, ZNC_DISCOVERY }

    private class Local(var ref: ConversationRef) {
        var opened = false
        var reading = false
        var exhausted = false
        var cursor: MessageCursor? = null
        var removed = false
        var zncBeforeMs: Long? = null
        var serverBefore: HistoryAnchor? = null
        var revision = 0L
        var redirect: Local? = null
        var migrating = false
        var pendingOpen = false
        var pendingOlder = false
    }

    private data class LocalRead(val state: Local, val ref: ConversationRef, val revision: Long)

    private data class Work(
        val purpose: Purpose,
        val ref: ConversationRef,
        val operation: HistoryOperation,
        val anchor: HistoryAnchor? = null,
        val to: HistoryAnchor? = null,
        val gapId: String? = null,
        val fromMs: Long? = null,
        val toMs: Long? = null,
    ) {
        var expiresAtMs = 0L

        fun retarget(target: ConversationRef): Work = copy(ref = target).also { it.expiresAtMs = expiresAtMs }
    }

    private data class SavedAnchor(val target: String, val value: HistoryAnchor, val stored: StoredMessage? = null)
    private data class HistoryPage(
        val count: Int,
        val first: HistoryAnchor?,
        val last: HistoryAnchor?,
        val unsupportedMetadata: Boolean,
    )

    private class Network(
        val id: String,
        val generation: Long,
        val epoch: Long,
        var mapping: CaseMapping,
        var nick: String,
        var support: ChatHistorySupport,
        var capabilities: Set<String>,
    ) {
        val tracker = HistoryRequestTracker({ mapping.fold(it) }, { nick })
        val queue = ArrayDeque<Work>()
        val completionLock = Any()
        val excludedKeys = mutableSetOf<String>()
        var enqueuing = 0
        val bootstrap = LinkedHashSet<String>()
        var anchors = mutableMapOf<String, SavedAnchor>()
        var seeded = false
        var current: Work? = null
        var currentId: Long? = null
        var finishing = false
        var retired = false
        @Volatile
        var disposed = false
        var deadline: Job? = null
    }

    private val lock = Any()
    private val coverageLock = Any()
    private val ids = AtomicLong(1)
    private val local = mutableMapOf<String, Local>()
    private val networks = mutableMapOf<String, Network>()
    private val retryWork = mutableMapOf<String, Work>()
    private val reconnectWork = mutableMapOf<String, Work>()

    fun open(key: String): Boolean {
        val buffer = host.historyBuffers[key] ?: return false
        val read = synchronized(lock) {
            val state = local.getOrPut(key) { Local(buffer.ref) }
            networks[buffer.ref.networkId]?.excludedKeys?.remove(key)
            if (state.migrating) { state.pendingOpen = true; return true }
            if (state.opened || state.reading || state.removed) return false
            state.opened = true
            state.reading = true
            state.pendingOpen = false
            LocalRead(state, state.ref, state.revision)
        }
        val state = read.state
        update(state.ref) { it.copy(initial = HistoryLoadState(HistoryLoadStatus.LOADING)) }
        host.enqueueHistoryStorage {
            try {
                var stored = messageStore.recent(read.ref, PAGE_SIZE + 1)
                val current = synchronized(lock) { readTarget(state) } ?: return@enqueueHistoryStorage
                if (stored.isEmpty() && current.ref != read.ref) stored = messageStore.recent(current.ref, PAGE_SIZE + 1)
                val page = stored.takeLast(PAGE_SIZE)
                val target = finishLocalRead(read, page.firstOrNull()?.cursor(), stored.size <= PAGE_SIZE)
                    ?: return@enqueueHistoryStorage
                host.mergeStoredHistory(target.ref, page)
                update(target.ref) { history -> history.copy(initial = HistoryLoadState()) }
                val unavailable = unavailable(target.ref.networkId)
                if (target.exhausted && historyProviderUnavailable(target.ref.networkId)) {
                    update(target.ref) { it.copy(older = unavailable) }
                }
                val network = synchronized(lock) { networks[target.ref.networkId] }
                if (page.isEmpty() && target.ref.kind != ConversationKind.SERVER && !target.pendingOpen) {
                    if (network == null) update(target.ref) { it.copy(older = unavailable) }
                    else bootstrap(network.id, target.ref)
                }
                continueLocal(target)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                synchronized(lock) { state.reading = false; state.opened = false }
                update(state.ref) { it.copy(initial = failed(error.message ?: "Unable to read stored history")) }
            }
        }
        return true
    }

    fun older(key: String) {
        val buffer = host.historyBuffers[key] ?: return
        val state = synchronized(lock) {
            local.getOrPut(key) { Local(buffer.ref) }.also {
                if (it.migrating) { it.pendingOlder = true; return }
                if (it.reading || it.removed || buffer.history.older.status == HistoryLoadStatus.LOADING) return
            }
        }
        if (!state.opened) {
            synchronized(lock) { state.pendingOlder = true }
            open(key)
            return
        }
        val cursor = state.cursor
        if (!state.exhausted && cursor != null) {
            val read = synchronized(lock) {
                state.reading = true
                LocalRead(state, state.ref, state.revision)
            }
            update(state.ref) { it.copy(older = HistoryLoadState(HistoryLoadStatus.LOADING)) }
            host.enqueueHistoryStorage {
                try {
                    var stored = messageStore.before(read.ref, cursor, PAGE_SIZE + 1)
                    val current = synchronized(lock) { readTarget(state) } ?: return@enqueueHistoryStorage
                    if (stored.isEmpty() && current.ref != read.ref) stored = messageStore.before(current.ref, cursor, PAGE_SIZE + 1)
                    val page = stored.takeLast(PAGE_SIZE)
                    val target = finishLocalRead(read, page.firstOrNull()?.cursor(), stored.size <= PAGE_SIZE)
                        ?: return@enqueueHistoryStorage
                    host.mergeStoredHistory(target.ref, page)
                    val olderState = if (target.exhausted && historyProviderUnavailable(target.ref.networkId))
                        unavailable(target.ref.networkId) else HistoryLoadState()
                    update(target.ref) { it.copy(older = olderState) }
                    if (page.isEmpty() && !target.pendingOpen) older(target.ref.storageKey)
                    continueLocal(target)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    synchronized(lock) { state.reading = false }
                    update(state.ref) { it.copy(older = failed(error.message ?: "Unable to read older stored messages")) }
                }
            }
            return
        }
        val network = synchronized(lock) { networks[state.ref.networkId] }
        if (network == null || !host.historyConnectionActive(network.id, network.generation, network.epoch)) {
            val unavailable = unavailable(state.ref.networkId)
            update(state.ref) { it.copy(older = unavailable) }
            return
        }
        if (state.ref.kind == ConversationKind.SERVER) {
            update(state.ref) { it.copy(older = HistoryLoadState(HistoryLoadStatus.EXHAUSTED)) }
            return
        }
        if (state.ref.rawTarget.startsWith("*")) {
            update(state.ref) { it.copy(older = HistoryLoadState(HistoryLoadStatus.UNSUPPORTED, "Control buffers have local history only")) }
            return
        }
        val anchor = state.serverBefore ?: host.historyBuffers[state.ref.storageKey]?.messages?.firstOrNull { it.historyEligible() }?.anchor()
        if (network.support.enabled) {
            enqueue(network, Work(Purpose.OLDER, state.ref, if (anchor == null) HistoryOperation.LATEST else HistoryOperation.BEFORE, anchor))
        } else if ("znc.in/playback" in network.capabilities) {
            val to = state.zncBeforeMs ?: anchor?.timestampMs ?: clock()
            if (to <= 0) {
                update(state.ref) { it.copy(older = HistoryLoadState(HistoryLoadStatus.UNSUPPORTED,
                    "Playback has no earlier representable range; archive completeness is unknown")) }
                return
            }
            val from = (to - ZNC_WINDOW_MS).coerceAtLeast(0)
            enqueue(network, Work(Purpose.OLDER, state.ref, HistoryOperation.BEFORE, anchor, fromMs = from, toMs = to))
        } else {
            val unavailable = unavailable(state.ref.networkId)
            update(state.ref) { it.copy(older = unavailable) }
        }
    }

    fun fillGap(key: String, gapId: String) {
        val buffer = host.historyBuffers[key] ?: return
        val gap = buffer.history.gaps.firstOrNull { it.id == gapId } ?: return
        if (gap.state.status == HistoryLoadStatus.LOADING) return
        val network = synchronized(lock) { networks[buffer.ref.networkId] }
        if (network == null || !host.historyConnectionActive(network.id, network.generation, network.epoch)) {
            setGap(buffer.ref, gapId, unavailable(buffer.ref.networkId))
            return
        }
        enqueue(network, Work(Purpose.GAP, buffer.ref, HistoryOperation.BETWEEN, gap.from, gap.to, gapId,
            gap.from.timestampMs - 1, gap.to.timestampMs + 1))
    }

    fun retry(key: String) {
        val buffer = host.historyBuffers[key] ?: return
        val network = synchronized(lock) { networks[buffer.ref.networkId] }
        val requiresReconnect = synchronized(lock) { network?.tracker?.requiresReconnect == true } ||
            buffer.history.catchUp.requiresReconnect || buffer.history.discovery.requiresReconnect ||
            buffer.history.older.requiresReconnect || buffer.history.gaps.any { it.state.requiresReconnect }
        if (requiresReconnect) {
            rememberReconnectWork(buffer)
            host.reconnectHistory(buffer.ref.networkId)
            return
        }
        if (network == null && (synchronized(lock) { retryWork.containsKey(key) } ||
            buffer.history.catchUp.status == HistoryLoadStatus.CANCELLED ||
            buffer.history.discovery.status == HistoryLoadStatus.CANCELLED ||
            buffer.history.older.status == HistoryLoadStatus.CANCELLED ||
            buffer.history.gaps.any { it.state.status == HistoryLoadStatus.CANCELLED })
        ) {
            rememberReconnectWork(buffer)
            host.reconnectHistory(buffer.ref.networkId)
            return
        }
        val work = synchronized(lock) { retryWork.remove(key) }
        if (work != null && network != null && host.historyConnectionActive(network.id, network.generation, network.epoch)) {
            enqueue(network, work)
        } else if (buffer.history.initial.status == HistoryLoadStatus.FAILED) {
            open(key)
        } else {
            older(key)
        }
    }

    private fun rememberReconnectWork(buffer: ConversationBuffer) {
        synchronized(lock) {
            val saved = retryWork[buffer.ref.storageKey]
            val gap = buffer.history.gaps.firstOrNull {
                it.state.requiresReconnect || it.state.status == HistoryLoadStatus.CANCELLED
            }
            val work = saved ?: gap?.let {
                Work(Purpose.GAP, buffer.ref, HistoryOperation.BETWEEN, it.from, it.to, it.id,
                    it.from.timestampMs - 1, it.to.timestampMs + 1)
            }
            if (work != null) reconnectWork[buffer.ref.storageKey] = work
        }
    }

    fun registered(networkId: String, generation: Long, epoch: Long, mapping: CaseMapping, nick: String,
                   isupport: ISupport, capabilities: Set<String>) {
        reset(networkId, "Connection replaced")
        val network = Network(networkId, generation, epoch, mapping, nick, IrcChatHistory.support(isupport, capabilities), capabilities)
        synchronized(lock) { networks[networkId] = network }
        for (buffer in host.historyBuffers.values) {
            if (buffer.ref.networkId != networkId) continue
            update(buffer.ref) { history ->
                history.copy(older = history.older.takeUnless { it.status in RECONNECT_STATES } ?: HistoryLoadState(),
                    catchUp = HistoryLoadState(), discovery = HistoryLoadState(),
                    gaps = history.gaps.map { it.copy(state = HistoryLoadState()) })
            }
        }
        host.enqueueHistoryStorage {
            val anchors = try {
                messageStore.historyAnchors(networkId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val state = failed(error.message ?: "Unable to read reconnect anchors", true)
                synchronized(lock) { if (networks[networkId] !== network) return@enqueueHistoryStorage; network.seeded = true }
                for (buffer in host.historyBuffers.values) if (buffer.ref.networkId == networkId) update(buffer.ref) { it.copy(catchUp = state) }
                return@enqueueHistoryStorage
            }
            if (!host.historyConnectionActive(networkId, generation, epoch)) return@enqueueHistoryStorage
            synchronized(lock) {
                if (networks[networkId] !== network) return@enqueueHistoryStorage
                for (row in anchors) {
                    network.anchors[network.mapping.fold(row.conversation.rawTarget)] =
                        SavedAnchor(row.conversation.rawTarget, HistoryAnchor(row.timestampMs, row.msgid), row)
                }
                network.seeded = true
            }
            val retries = synchronized(lock) {
                reconnectWork.values.filter { it.ref.networkId == networkId }.also { work ->
                    for (pending in work) {
                        reconnectWork.remove(pending.ref.storageKey)
                        retryWork.remove(pending.ref.storageKey)
                    }
                }
            }
            for (work in retries) enqueue(network, work)
            discover(network)
            val waiting = synchronized(lock) { network.bootstrap.toList().also { network.bootstrap.clear() } }
            for (target in waiting) bootstrap(networkId, host.historyRef(networkId, target))
            drain(network)
        }
    }

    fun support(networkId: String, generation: Long, epoch: Long, mapping: CaseMapping, nick: String,
                isupport: ISupport, capabilities: Set<String>) {
        val network: Network
        val activate: Boolean
        synchronized(lock) {
            network = networks[networkId] ?: return
            if (network.generation != generation || network.epoch != epoch) return
            val previous = network.support.enabled || "znc.in/playback" in network.capabilities
            if (network.mapping != mapping) {
                val rekeyed = mutableMapOf<String, SavedAnchor>()
                for (saved in network.anchors.values) {
                    val key = mapping.fold(saved.target)
                    if ((rekeyed[key]?.value?.timestampMs ?: Long.MIN_VALUE) <= saved.value.timestampMs) rekeyed[key] = saved
                }
                network.anchors = rekeyed
            }
            network.mapping = mapping
            network.nick = nick
            network.support = IrcChatHistory.support(isupport, capabilities)
            network.capabilities = capabilities
            activate = network.seeded && !previous && (network.support.enabled || "znc.in/playback" in network.capabilities)
        }
        if (activate) {
            discover(network)
            for (buffer in host.historyBuffers.values) {
                if (buffer.ref.networkId == networkId) update(buffer.ref) { history ->
                    history.copy(older = history.older.takeUnless { it.status in RECONNECT_STATES } ?: HistoryLoadState(),
                        catchUp = HistoryLoadState())
                }
                if (buffer.ref.networkId == networkId && buffer.ref.kind == ConversationKind.CHANNEL &&
                    buffer.joinState == JoinState.JOINED
                ) bootstrap(networkId, buffer.ref)
            }
        }
        drain(network)
    }

    private fun discover(network: Network) {
        val now = clock()
        val from = synchronized(lock) { network.anchors.values.minOfOrNull { it.value.timestampMs } }
            ?: (now - DISCOVERY_WINDOW_MS)
        val boundedFrom = maxOf(from, now - DISCOVERY_WINDOW_MS)
        val server = ConversationRef.server(network.id)
        if (network.support.enabled) {
            enqueue(network, Work(Purpose.DISCOVERY, server, HistoryOperation.TARGETS,
                fromMs = boundedFrom - 1, toMs = now + CLOCK_SKEW_MS))
            for (buffer in host.historyBuffers.values) {
                if (buffer.ref.networkId == network.id && buffer.ref.kind == ConversationKind.DIRECT_MESSAGE) bootstrap(network.id, buffer.ref)
            }
        } else if ("znc.in/playback" in network.capabilities) {
            val saved = synchronized(lock) { network.anchors.values.toList() }
            for (anchor in saved) {
                if (anchor.value.timestampMs < boundedFrom) addGap(network, host.historyRef(network.id, anchor.target), anchor.value, HistoryAnchor(boundedFrom))
            }
            enqueue(network, Work(Purpose.ZNC_DISCOVERY, server, HistoryOperation.LATEST,
                fromMs = boundedFrom - 1, toMs = now))
        }
    }

    fun bootstrap(networkId: String, ref: ConversationRef) {
        if (ref.kind == ConversationKind.SERVER || ref.rawTarget.startsWith("*")) return
        val network = synchronized(lock) {
            val current = networks[networkId] ?: return
            current.excludedKeys.remove(ref.storageKey)
            if (!current.seeded) { current.bootstrap.add(ref.rawTarget); return }
            current
        }
        if (!network.support.enabled) {
            if ("znc.in/playback" !in network.capabilities) {
                val unavailable = unavailable(networkId)
                update(ref) { it.copy(catchUp = unavailable) }
            }
            return
        }
        val anchor = synchronized(lock) { network.anchors[network.mapping.fold(ref.rawTarget)]?.value }
        enqueue(network, Work(if (anchor == null) Purpose.INITIAL else Purpose.CATCH_UP, ref, HistoryOperation.LATEST, anchor))
    }

    fun receive(networkId: String, generation: Long, epoch: Long, message: IrcMessage): Boolean {
        val network: Network
        val receipt: HistoryReceipt
        synchronized(lock) {
            network = networks[networkId] ?: return false
            if (network.generation != generation || network.epoch != epoch) return false
            receipt = network.tracker.receive(message, elapsedClock())
        }
        if (receipt.result != null) complete(network, receipt.result) else scheduleDeadline(network)
        return receipt.consumed
    }

    fun reset(networkId: String, reason: String) {
        val network = synchronized(lock) {
            val current = networks.remove(networkId) ?: return
            current.disposed = true
            current.deadline?.cancel()
            current.tracker.cancel("connection reset")
            current
        }
        network.current?.let { setState(it, HistoryLoadState(HistoryLoadStatus.CANCELLED, reason)) }
        for (work in network.queue) setState(work, HistoryLoadState(HistoryLoadStatus.CANCELLED, reason))
    }

    fun rename(from: ConversationRef, to: ConversationRef) {
        synchronized(lock) {
            local.remove(from.storageKey)?.let { previous ->
                val existing = local[to.storageKey]
                previous.ref = to
                previous.revision++
                previous.migrating = true
                previous.opened = false
                previous.pendingOpen = true
                if (existing == null) local[to.storageKey] = previous else {
                    existing.revision++
                    existing.migrating = true
                    existing.opened = false
                    existing.pendingOpen = true
                    existing.pendingOlder = existing.pendingOlder || previous.pendingOlder
                    previous.redirect = existing
                    existing.opened = existing.opened && previous.opened
                    existing.exhausted = existing.exhausted && previous.exhausted
                    existing.cursor = newerCursor(existing.cursor, previous.cursor)
                    previous.removed = true
                }
            }
            retryWork.remove(from.storageKey)?.let { retryWork[to.storageKey] = it.retarget(to) }
            reconnectWork.remove(from.storageKey)?.let { reconnectWork[to.storageKey] = it.retarget(to) }
            val network = networks[from.networkId]
            if (network != null) {
                val queued = network.queue.map { if (it.ref.storageKey == from.storageKey) it.retarget(to) else it }
                network.queue.clear(); network.queue.addAll(queued)
                val anchor = network.anchors.remove(network.mapping.fold(from.rawTarget))
                if (anchor != null) network.anchors[network.mapping.fold(to.rawTarget)] = anchor.copy(target = to.rawTarget)
                network.excludedKeys.add(from.storageKey)
                network.current?.takeIf { it.ref.storageKey == from.storageKey }?.let { current ->
                    network.retired = true
                    val replacement = current.retarget(to)
                    replacement.expiresAtMs = elapsedClock() + QUEUE_TIMEOUT_MS
                    if (replacement !in network.queue) network.queue.addFirst(replacement)
                }
            }
        }
        synchronized(coverageLock) { coverageStore.rename(from.storageKey, to.storageKey) }
    }

    fun remove(key: String) {
        val ref = host.historyBuffers[key]?.ref
        synchronized(lock) {
            local.remove(key)?.removed = true
            retryWork.remove(key)
            reconnectWork.remove(key)
            val current = ref?.networkId?.let(networks::get)
            current?.excludedKeys?.add(key)
            current?.queue?.removeAll { it.ref.storageKey == key }
            if (current?.current?.ref?.storageKey == key) current.retired = true
        }
    }

    fun removeNetwork(networkId: String) {
        reset(networkId, "Network removed")
        synchronized(lock) {
            val keys = local.filterValues { it.ref.networkId == networkId }.keys.toList()
            for (key in keys) { local.remove(key)?.removed = true; retryWork.remove(key) }
            reconnectWork.values.removeAll { it.ref.networkId == networkId }
            retryWork.values.removeAll { it.ref.networkId == networkId }
        }
        synchronized(coverageLock) { coverageStore.removeNetwork(networkId) }
    }

    private fun enqueue(network: Network, work: Work) {
        synchronized(lock) {
            if (networks[network.id] !== network) return
            if (network.current == work || work in network.queue) return
            work.expiresAtMs = elapsedClock() + QUEUE_TIMEOUT_MS
            if (work.purpose == Purpose.OLDER || work.purpose == Purpose.GAP) network.queue.addFirst(work)
            else network.queue.addLast(work)
            network.enqueuing++
        }
        try {
            setState(work, HistoryLoadState(HistoryLoadStatus.LOADING))
        } finally {
            synchronized(lock) { network.enqueuing-- }
        }
        drain(network)
    }

    private fun drain(network: Network) {
        while (true) {
            var rejectedWork: Work? = null
            var rejectedState: HistoryLoadState? = null
            var outgoing: PendingHistoryRequest? = null
            val active = host.historyConnectionActive(network.id, network.generation, network.epoch)
            synchronized(lock) {
                if (networks[network.id] !== network || !network.seeded || network.current != null ||
                    network.finishing || network.enqueuing > 0
                ) return
                val work = network.queue.removeFirstOrNull() ?: return
                val state = when {
                    !active ->
                        HistoryLoadState(HistoryLoadStatus.CANCELLED, "Connection closed")
                    network.tracker.requiresReconnect -> failed("History request timed out; reconnect before retrying", true)
                    elapsedClock() >= work.expiresAtMs -> failed("History request expired while waiting for another reply")
                    else -> null
                }
                if (state != null) {
                    rejectedWork = work
                    rejectedState = state
                } else {
                    val znc = !network.support.enabled && "znc.in/playback" in network.capabilities
                    val raw = requestLine(network, work, znc)
                    if (raw == null) {
                        rejectedWork = work
                        rejectedState = HistoryLoadState(HistoryLoadStatus.UNSUPPORTED, "Server does not support the required history reference")
                    } else {
                        val id = ids.getAndIncrement()
                        val label = if (!znc && "labeled-response" in network.capabilities) "yh-history-${network.generation}-${network.epoch}-$id" else null
                        val wire = if (label == null) raw else "@label=$label $raw"
                        val request = PendingHistoryRequest(
                            id = id,
                            target = when {
                                work.operation == HistoryOperation.TARGETS -> null
                                work.purpose == Purpose.ZNC_DISCOVERY -> "*"
                                else -> work.ref.rawTarget
                            },
                            operation = work.operation,
                            line = wire,
                            limit = if (znc) ZNC_CAPTURE_LIMIT else network.support.limit,
                            label = label,
                            transport = if (znc) HistoryTransport.ZNC_PLAYBACK else HistoryTransport.CHATHISTORY,
                            fromTimestampMs = work.fromMs ?: work.anchor?.timestampMs,
                            toTimestampMs = work.toMs ?: work.to?.timestampMs,
                        )
                        if (network.tracker.begin(request, elapsedClock())) {
                            network.current = work
                            network.currentId = id
                            network.retired = false
                            outgoing = request
                        } else {
                            rejectedWork = work
                            rejectedState = failed("Another history request is still active", network.tracker.requiresReconnect)
                        }
                    }
                }
            }
            val rejected = rejectedWork
            if (rejected != null) {
                synchronized(lock) { retryWork[rejected.ref.storageKey] = rejected }
                setState(rejected, requireNotNull(rejectedState))
                continue
            }
            val request = outgoing ?: return
            if (!host.sendHistory(network.id, network.generation, network.epoch, request.line)) {
                cancelWork(network, "Connection closed")
            } else scheduleDeadline(network)
            return
        }
    }

    private fun requestLine(network: Network, work: Work, znc: Boolean): String? {
        if (znc) {
            val target = if (work.purpose == Purpose.ZNC_DISCOVERY) "*" else work.ref.rawTarget
            val from = work.fromMs ?: 0
            val to = work.toMs ?: clock()
            if (from >= to) return null
            return "ZNC *playback PLAY $target ${seconds(from)} ${seconds(to)}"
        }
        return when (work.operation) {
            HistoryOperation.LATEST -> IrcChatHistory.latest(
                work.ref.rawTarget, work.anchor?.let { overlapping(network, it, -LATEST_FUZZ_MS) }, network.support,
            )
            HistoryOperation.BEFORE -> work.anchor?.let {
                IrcChatHistory.before(work.ref.rawTarget, overlapping(network, it, 1), network.support)
            }
            HistoryOperation.BETWEEN -> work.anchor?.let { from -> work.to?.let { to ->
                IrcChatHistory.between(work.ref.rawTarget, overlapping(network, from, -1), overlapping(network, to, 1), network.support)
            } }
            HistoryOperation.TARGETS -> IrcChatHistory.targets(work.fromMs ?: 0, work.toMs ?: clock(), network.support)
        }
    }

    private fun usableMsgid(network: Network, anchor: HistoryAnchor): Boolean =
        anchor.msgid != null && "msgid" in network.support.referenceTypes

    private fun overlapping(network: Network, anchor: HistoryAnchor, deltaMs: Long): HistoryAnchor =
        if (usableMsgid(network, anchor)) anchor else anchor.copy(timestampMs = anchor.timestampMs + deltaMs, msgid = null)

    fun resume(networkId: String) {
        val readers = synchronized(lock) {
            local.values.filter { it.ref.networkId == networkId && it.migrating }.onEach { it.migrating = false }
        }
        for (reader in readers) continueLocal(reader)
        val network = synchronized(lock) { networks[networkId] } ?: return
        drain(network)
    }

    private fun cancelWork(network: Network, reason: String, resume: Boolean = true) {
        var cancelledWork: Work? = null
        val result = synchronized(lock) {
            if (networks[network.id] !== network) return
            network.deadline?.cancel()
            network.tracker.cancel(reason).also {
                if (it == null) {
                    cancelledWork = network.current
                    network.current = null
                    network.currentId = null
                    network.retired = false
                }
            }
        }
        cancelledWork?.let { setState(it, HistoryLoadState(HistoryLoadStatus.CANCELLED, reason)) }
        if (result != null) complete(network, result, cancelled = true, resume = resume)
        else if (resume) drain(network)
    }

    private fun scheduleDeadline(network: Network) {
        synchronized(lock) {
            if (networks[network.id] !== network) return
            val deadline = network.tracker.deadlineMs ?: return
            network.deadline?.cancel()
            network.deadline = scope.launch {
                delay((deadline - elapsedClock()).coerceAtLeast(1))
                val result = synchronized(lock) {
                    if (networks[network.id] !== network) return@launch
                    network.tracker.expire(elapsedClock())
                }
                if (result != null) complete(network, result) else scheduleDeadline(network)
            }
        }
    }

    private fun complete(network: Network, result: HistoryResult, cancelled: Boolean = false, resume: Boolean = true) {
        val sessionLock = host.historySessionLock(network.id, network.generation, network.epoch) ?: network.completionLock
        synchronized(sessionLock) { finish(network, result, cancelled, resume) }
    }

    private fun finish(network: Network, result: HistoryResult, cancelled: Boolean, resume: Boolean) {
        val work = synchronized(lock) {
            if (networks[network.id] !== network || network.currentId != result.request.id) return
            val current = network.current ?: return
            network.finishing = true
            network.deadline?.cancel()
            current
        }
        try {
            if (synchronized(lock) { network.retired }) return
            if (!host.historyConnectionActive(network.id, network.generation, network.epoch)) {
                setState(work, HistoryLoadState(HistoryLoadStatus.CANCELLED, "Connection closed"))
                return
            }
            if (result.error != null) {
                val fallback = if (!cancelled && (result.errorCode == "MESSAGE_ERROR" || result.errorCode == "INVALID_MSGREFTYPE") &&
                    "timestamp" in network.support.referenceTypes && work.anchor?.msgid != null
                ) work.copy(anchor = work.anchor.copy(msgid = null), to = work.to?.copy(msgid = null)) else work
                synchronized(lock) { retryWork[work.ref.storageKey] = fallback }
                setState(work, if (cancelled) HistoryLoadState(HistoryLoadStatus.CANCELLED, result.error)
                    else failed(result.error, synchronized(lock) { network.tracker.requiresReconnect }))
                return
            }
            if (work.purpose == Purpose.DISCOVERY) {
                val incomplete = result.targets.isNotEmpty() && !result.end
                update(work.ref) { it.copy(discovery = HistoryLoadState(HistoryLoadStatus.EXHAUSTED), discoveryTruncated = incomplete) }
                for (target in result.targets) {
                    val ref = host.historyRef(network.id, target.target)
                    if (ref.kind != ConversationKind.DIRECT_MESSAGE) continue
                    host.ensureHistoryBuffer(ref)
                    bootstrap(network.id, ref)
                }
                return
            }
            val page = historyPage(result.frames)
            var lowerWitnessed = false
            val excludedKeys = synchronized(lock) { network.excludedKeys.toSet() }
            host.replayHistory(network.id, network.generation, network.epoch, result, excludedKeys) { effects ->
                check(!page.unsupportedMetadata) { "History response lacks usable sender/server-time metadata" }
                if (result.request.transport == HistoryTransport.ZNC_PLAYBACK) {
                    if (work.purpose == Purpose.ZNC_DISCOVERY) playbackGaps(network, effects)
                    if (work.purpose == Purpose.GAP) {
                        lowerWitnessed = work.anchor?.let { playbackWitness(work.ref, it, effects) } == true
                    }
                }
                if (work.operation == HistoryOperation.LATEST && work.anchor != null && page.count > 0 && !result.end) {
                    val first = page.first
                    val from = overlapping(network, work.anchor, -LATEST_FUZZ_MS)
                    if (first != null && first != from) addGap(network, work.ref, from, first)
                }
            }
            synchronized(lock) {
                if (networks[network.id] !== network || network.currentId != result.request.id) return
            }
            if (work.purpose == Purpose.ZNC_DISCOVERY) {
                update(work.ref) { it.copy(discovery = HistoryLoadState(
                    if (page.count == 0) HistoryLoadStatus.WINDOW_EMPTY else HistoryLoadStatus.IDLE,
                )) }
                return
            }
            if (work.purpose == Purpose.GAP) {
                finishGap(network, work, result, page, lowerWitnessed)
                return
            }
            if (work.purpose == Purpose.OLDER) {
                val state = synchronized(lock) { local.getOrPut(work.ref.storageKey) { Local(work.ref) } }
                if (page.first != null) state.serverBefore = page.first
                if (result.request.transport == HistoryTransport.ZNC_PLAYBACK) {
                    val to = work.toMs ?: clock()
                    val lower = page.first?.takeIf { it.timestampMs < to }
                    state.zncBeforeMs = lower?.timestampMs ?: work.fromMs
                    update(work.ref) { it.copy(older = HistoryLoadState(
                        if (page.count == 0 || lower == null) HistoryLoadStatus.WINDOW_EMPTY else HistoryLoadStatus.IDLE,
                        windowBeforeMs = state.zncBeforeMs,
                    )) }
                } else {
                    val stuck = page.count > 0 && page.first != null && work.anchor != null &&
                        page.first.timestampMs == work.anchor.timestampMs && !usableMsgid(network, page.first)
                    update(work.ref) { it.copy(older = when {
                        result.end -> HistoryLoadState(HistoryLoadStatus.EXHAUSTED)
                        stuck -> failed("Server cannot advance a timestamp-only reference; older history is retained")
                        page.first == null -> failed("History response has no usable pagination reference")
                        else -> HistoryLoadState()
                    }) }
                    if (stuck && !result.end) synchronized(lock) { retryWork[work.ref.storageKey] = work }
                }
            } else {
                setState(work, HistoryLoadState())
                if (work.anchor == null && result.end) {
                    update(work.ref) { it.copy(older = HistoryLoadState(HistoryLoadStatus.EXHAUSTED)) }
                }
            }
            if (page.last != null && work.operation == HistoryOperation.LATEST) synchronized(lock) {
                network.anchors[network.mapping.fold(work.ref.rawTarget)] = SavedAnchor(work.ref.rawTarget, page.last)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            synchronized(lock) { retryWork[work.ref.storageKey] = work }
            setState(work, failed(error.message ?: "Unable to apply historical messages"))
        } finally {
            synchronized(lock) {
                if (network.currentId == result.request.id) {
                    network.current = null
                    network.currentId = null
                    network.finishing = false
                    network.retired = false
                }
            }
            if (resume) drain(network)
        }
    }

    private fun finishGap(network: Network, work: Work, result: HistoryResult, page: HistoryPage, lowerWitnessed: Boolean) {
        val gap = synchronized(coverageLock) {
            coverageStore.gaps(work.ref.storageKey).firstOrNull { it.id == work.gapId }?.toHistoryGap()
        } ?: return
        if (gap.from != work.anchor || gap.to != work.to) {
            setGap(work.ref, gap.id, HistoryLoadState())
            return
        }
        if (result.request.transport == HistoryTransport.ZNC_PLAYBACK) {
            val first = page.first
            when {
                lowerWitnessed && result.batchComplete ->
                    mutateGaps(network, work.ref) { it.filterNot { existing -> existing.id == gap.id } }
                page.count == 0 -> setGap(work.ref, gap.id, HistoryLoadState(HistoryLoadStatus.WINDOW_EMPTY))
                first != null && first != gap.to && first.timestampMs > gap.from.timestampMs ->
                    mutateGaps(network, work.ref) { it.map { existing ->
                        if (existing.id == gap.id) existing.copy(to = first) else existing
                    } }
                else -> setGap(work.ref, gap.id, failed("Playback cannot advance its range; the remaining gap is retained"))
            }
        } else if (result.end) {
            mutateGaps(network, work.ref) { it.filterNot { existing -> existing.id == work.gapId } }
        } else {
            val backwards = gap.from.timestampMs > gap.to.timestampMs &&
                !(usableMsgid(network, gap.from) && usableMsgid(network, gap.to))
            val next = if (backwards) page.first else page.last
            if (next != null && next != gap.from &&
                (usableMsgid(network, next) || next.timestampMs != gap.from.timestampMs)
            ) mutateGaps(network, work.ref) { it.map { existing ->
                if (existing.id == gap.id) existing.copy(from = next) else existing
            } }
            else setGap(work.ref, gap.id, failed("History cannot advance its reference; the remaining gap is retained"))
        }
    }

    private fun playbackWitness(
        ref: ConversationRef,
        anchor: HistoryAnchor,
        effects: List<InboundEffect>,
        stored: StoredMessage? = null,
    ): Boolean {
        val cached = host.historyBuffers[ref.storageKey]?.messages?.let { messages ->
            if (anchor.msgid != null) messages.firstOrNull { it.msgid == anchor.msgid }
            else messages.singleOrNull { it.historyEligible() && it.timestampMs == anchor.timestampMs }
        }
        return effects.any { effect ->
            effect is InboundEffect.AppendMessage && effect.ref.storageKey == ref.storageKey && !effect.historyContext &&
                (anchor.msgid != null && effect.msgid == anchor.msgid ||
                    cached != null && effect.timestampMs == cached.timestampMs && effect.sender == cached.sender &&
                    effect.kind == cached.kind && effect.text == cached.text ||
                    stored != null && effect.timestampMs == stored.timestampMs && effect.sender == stored.senderNick &&
                    effect.kind == stored.kind && effect.text == stored.text)
        }
    }

    private fun playbackGaps(network: Network, effects: List<InboundEffect>) {
        val anchors = synchronized(lock) { network.anchors.values.toList() }
        for (saved in anchors) {
            val ref = host.historyRef(network.id, saved.target)
            if (playbackWitness(ref, saved.value, effects, saved.stored)) continue
            val first = effects.firstOrNull { effect ->
                effect is InboundEffect.AppendMessage && effect.ref.storageKey == ref.storageKey &&
                    !effect.historyContext && effect.timestampMs >= saved.value.timestampMs
            } as? InboundEffect.AppendMessage ?: continue
            host.ensureHistoryBuffer(ref)
            addGap(network, ref, saved.value, HistoryAnchor(first.timestampMs, first.msgid))
        }
    }

    private fun addGap(network: Network, ref: ConversationRef, from: HistoryAnchor, to: HistoryAnchor) {
        if (from == to) return
        val id = "${from.timestampMs}:${from.msgid.orEmpty()}-${to.timestampMs}:${to.msgid.orEmpty()}"
        mutateGaps(network, ref) { normalizeGaps(it + HistoryGap(id, from, to)) }
    }

    private fun normalizeGaps(gaps: List<HistoryGap>): List<HistoryGap> {
        val forwards = gaps.filter { it.from.timestampMs < it.to.timestampMs }.sortedBy { it.from.timestampMs }
        val merged = mutableListOf<HistoryGap>()
        for (gap in forwards) {
            val previous = merged.lastOrNull()
            if (previous != null && gap.from.timestampMs <= previous.to.timestampMs &&
                (gap.from.timestampMs != previous.from.timestampMs || gap.from == previous.from) &&
                (gap.to.timestampMs != previous.to.timestampMs || gap.to == previous.to)
            ) {
                val to = if (gap.to.timestampMs > previous.to.timestampMs) gap.to else previous.to
                merged[merged.lastIndex] = previous.copy(to = to)
            } else merged.add(gap)
        }
        return merged + gaps.filter { it.from.timestampMs >= it.to.timestampMs }.distinctBy { it.id }
    }

    private fun mutateGaps(network: Network, ref: ConversationRef, transform: (List<HistoryGap>) -> List<HistoryGap>) {
        synchronized(coverageLock) {
            if (network.disposed) return
            val current = coverageStore.gaps(ref.storageKey).map { it.toHistoryGap() }
            transform(current).also { updated ->
                coverageStore.put(ref.storageKey, updated.map {
                    StoredHistoryGap(it.id, it.from.timestampMs, it.from.msgid, it.to.timestampMs, it.to.msgid)
                })
            }
        }
        update(ref) { history ->
            if (network.disposed) return@update history
            val gaps = coverageStore.gaps(ref.storageKey).map { it.toHistoryGap() }
            history.copy(gaps = gaps.map { gap ->
                history.gaps.firstOrNull { it.id == gap.id && it.from == gap.from && it.to == gap.to } ?: gap
            })
        }
    }

    private fun StoredHistoryGap.toHistoryGap(): HistoryGap =
        HistoryGap(id, HistoryAnchor(fromTimestampMs, fromMsgid), HistoryAnchor(toTimestampMs, toMsgid))

    private fun readTarget(source: Local): Local? {
        var target = source
        while (true) target = target.redirect ?: break
        return target.takeUnless { it.removed }
    }

    private fun finishLocalRead(read: LocalRead, cursor: MessageCursor?, exhausted: Boolean): Local? = synchronized(lock) {
        read.state.reading = false
        val target = readTarget(read.state) ?: return@synchronized null
        if (target !== read.state || target.revision != read.revision) {
            target.exhausted = target.exhausted && exhausted
            target.cursor = newerCursor(target.cursor, cursor)
        } else {
            target.exhausted = exhausted
            target.cursor = cursor ?: target.cursor
        }
        target
    }

    private fun continueLocal(state: Local) {
        val action = synchronized(lock) {
            if (state.removed || state.migrating || state.reading) return
            when {
                state.pendingOpen -> 1
                state.pendingOlder -> { state.pendingOlder = false; 2 }
                else -> 0
            }
        }
        if (action == 1) open(state.ref.storageKey)
        else if (action == 2) older(state.ref.storageKey)
    }

    private fun historyProviderUnavailable(networkId: String): Boolean {
        val network = synchronized(lock) { networks[networkId] } ?: return true
        return !host.historyConnectionActive(network.id, network.generation, network.epoch) ||
            (!network.support.enabled && "znc.in/playback" !in network.capabilities)
    }

    private fun newerCursor(first: MessageCursor?, second: MessageCursor?): MessageCursor? = when {
        first == null -> second
        second == null -> first
        first.timestampMs > second.timestampMs -> first
        first.timestampMs < second.timestampMs -> second
        first.rowId > second.rowId -> first
        else -> second
    }

    private fun setGap(ref: ConversationRef, id: String, state: HistoryLoadState) {
        update(ref) { it.copy(gaps = it.gaps.map { gap -> if (gap.id == id) gap.copy(state = state) else gap }) }
    }

    private fun setState(work: Work, state: HistoryLoadState) {
        if (work.purpose == Purpose.GAP) { setGap(work.ref, work.gapId.orEmpty(), state); return }
        update(work.ref) { history -> when (work.purpose) {
            Purpose.INITIAL -> history.copy(catchUp = state)
            Purpose.OLDER -> history.copy(older = state)
            Purpose.CATCH_UP -> history.copy(catchUp = state)
            Purpose.DISCOVERY, Purpose.ZNC_DISCOVERY -> history.copy(discovery = state)
            Purpose.GAP -> history
        } }
    }

    private fun update(ref: ConversationRef, transform: (ConversationHistory) -> ConversationHistory) {
        host.updateHistoryBuffer(ref.storageKey) { it.copy(history = transform(it.history)) }
    }

    private fun unavailable(networkId: String): HistoryLoadState {
        val network = synchronized(lock) { networks[networkId] }
        val offline = network == null || !host.historyConnectionActive(networkId, network.generation, network.epoch)
        return HistoryLoadState(if (offline) HistoryLoadStatus.EXHAUSTED else HistoryLoadStatus.UNSUPPORTED,
            if (offline) "End of history stored on this device; connect to fetch older messages" else "This server has no supported history provider; stored messages remain available")
    }

    private fun StoredMessage.cursor(): MessageCursor = MessageCursor(timestampMs, rowId)
    private fun ChatMessage.anchor(): HistoryAnchor = HistoryAnchor(timestampMs, msgid)
    private fun ChatMessage.historyEligible(): Boolean =
        !historyContext && (kind == MessageKind.PRIVMSG || kind == MessageKind.NOTICE || kind == MessageKind.ACTION)
    private fun failed(message: String, reconnect: Boolean = false): HistoryLoadState = HistoryLoadState(HistoryLoadStatus.FAILED, message, reconnect)
    private fun seconds(timestampMs: Long): String = java.math.BigDecimal.valueOf(timestampMs, 3).toPlainString()

    private fun historyPage(frames: List<IrcMessage>): HistoryPage {
        var multiline: MutableMap<String, IrcMessage>? = null
        var counted: MutableSet<String>? = null
        var count = 0
        var firstTime: Long? = null
        var firstId: String? = null
        var lastTime: Long? = null
        var lastId: String? = null
        var unsupported = false
        for (frame in frames) {
            if (frame.command == "BATCH" && frame.parameters.getOrNull(1) == "draft/multiline" &&
                frame.parameters.firstOrNull()?.startsWith("+") == true
            ) {
                val ref = frame.parameters.first().drop(1)
                val roots = multiline ?: mutableMapOf<String, IrcMessage>().also { multiline = it }
                roots[ref] = frame
                continue
            }
            if (frame.command != "PRIVMSG" && frame.command != "NOTICE" && frame.command != "TAGMSG") continue
            val batch = frame.tag("batch")
            val root = multiline?.get(batch)
            if (frame.tags.any { it.key == "draft/chathistory-context" } ||
                root?.tags?.any { it.key == "draft/chathistory-context" } == true
            ) continue
            if (root != null && batch != null) {
                val refs = counted ?: mutableSetOf<String>().also { counted = it }
                if (!refs.add(batch)) continue
            }
            count++
            val timestamp = parseServerTime(frame.tag("time") ?: root?.tag("time"))
            if (timestamp == null || (root?.prefix ?: frame.prefix) == null) {
                unsupported = true
                continue
            }
            val msgid = root?.tag("msgid") ?: frame.tag("msgid")
            if (firstTime == null) {
                firstTime = timestamp
                firstId = msgid
            }
            lastTime = timestamp
            lastId = msgid
        }
        val first = firstTime?.let { HistoryAnchor(it, firstId) }
        val last = if (lastTime == firstTime && lastId == firstId) first else lastTime?.let { HistoryAnchor(it, lastId) }
        return HistoryPage(count, first, last, unsupported)
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val ZNC_WINDOW_MS = 6L * 60 * 60 * 1000
        const val DISCOVERY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        const val CLOCK_SKEW_MS = 60_000L
        const val QUEUE_TIMEOUT_MS = 30_000L
        const val LATEST_FUZZ_MS = 10_000L
        const val ZNC_CAPTURE_LIMIT = 4096
        val RECONNECT_STATES = setOf(HistoryLoadStatus.CANCELLED, HistoryLoadStatus.FAILED, HistoryLoadStatus.UNSUPPORTED, HistoryLoadStatus.EXHAUSTED)
    }
}
