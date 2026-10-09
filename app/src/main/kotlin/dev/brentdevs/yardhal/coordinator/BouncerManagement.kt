package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.BouncerNetworkDraft
import dev.brentdevs.yardhal.core.data.BouncerServCommand
import dev.brentdevs.yardhal.core.data.BouncerZncCommands
import dev.brentdevs.yardhal.core.data.BouncerZncParser
import dev.brentdevs.yardhal.core.data.NetworkMode
import dev.brentdevs.yardhal.core.data.ZncChannelDraft
import dev.brentdevs.yardhal.core.data.ZncCommand
import dev.brentdevs.yardhal.core.data.ZncCommandTarget
import dev.brentdevs.yardhal.core.data.ZncNetworkDraft
import dev.brentdevs.yardhal.core.data.ZncNetworkVariable
import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

public class BouncerManagement(
    private val scope: CoroutineScope,
    private val send: suspend (String, Long, String) -> Unit,
    private val timeoutMs: Long = 10_000,
) {
    private val lock = Any()
    private val mutableAccounts = MutableStateFlow<Map<String, BouncerAccountState>>(emptyMap())
    public val accounts: StateFlow<Map<String, BouncerAccountState>> = mutableAccounts.asStateFlow()
    private val operationLocks = mutableMapOf<String, Mutex>()
    private val managedModes = mutableMapOf<String, NetworkMode>()
    private val pending = mutableMapOf<String, Reply>()
    private val batches = mutableMapOf<Pair<String, String>, String>()
    private val historyBatches = mutableSetOf<Pair<String, String>>()
    private val nativeBatches = mutableSetOf<Pair<String, String>>()
    private val unresolvedGenerations = mutableSetOf<Pair<String, Long>>()
    private val recoveryFences = mutableMapOf<Pair<String, Long>, RecoveryFence>()
    private var serial = 0L

    private class Failure(val safeReason: String) : Exception(safeReason)

    private class RecoveryFence(val token: String) {
        val result = CompletableDeferred<Unit>()
    }

    private class Reply(
        val generation: Long,
        val label: String,
        val target: String?,
        val nativeCommand: String?,
        val nativeId: String?,
        val labeled: Boolean,
        val snapshotBaseline: Map<String, IrcBouncerNetworks.Attributes>,
    ) {
        val result = CompletableDeferred<List<IrcMessage>>()
        val messages = mutableListOf<IrcMessage>()
        var rootBatch: String? = null
        var snapshotBatch: String? = null
        var snapshotClosed = false
        val liveDeltas = mutableListOf<IrcBouncerNetworks.NetworkUpdate>()
        var invalidSnapshot = false
        override fun toString(): String = "Reply(generation=$generation, label=$label, target=$target)"
    }

    private data class Step(
        val wire: String,
        val target: String? = null,
        val nativeCommand: String? = null,
        val nativeId: String? = null,
        val accepted: (List<IrcMessage>) -> Boolean,
    ) {
        override fun toString(): String = "Step(target=$target, nativeCommand=$nativeCommand)"
    }

    public fun start(networkId: String, generation: Long, mode: NetworkMode, username: String = "", boundNetwork: String? = null) {
        synchronized(lock) {
            pending.remove(networkId)?.result?.completeExceptionally(Failure("Connection replaced."))
            batches.keys.removeAll { it.first == networkId }
            historyBatches.removeAll { it.first == networkId }
            nativeBatches.removeAll { it.first == networkId }
            recoveryFences.keys.filter { it.first == networkId }.forEach { key ->
                recoveryFences.remove(key)?.result?.completeExceptionally(Failure("Connection replaced."))
            }
            unresolvedGenerations.removeAll { it.first == networkId && it.second != generation }
            managedModes[networkId] = mode
            val previous = mutableAccounts.value[networkId]
            val sameAccount = previous?.let { it.mode == mode && it.username == username && it.boundNetwork == boundNetwork } == true
            mutableAccounts.value = mutableAccounts.value + (networkId to BouncerAccountState(
                networkId, generation, mode, username = username, boundNetwork = boundNetwork,
                sojuNetworks = if (sameAccount) previous?.sojuNetworks.orEmpty() else emptyMap(),
                zncNetworks = if (sameAccount) previous?.zncNetworks.orEmpty() else emptyList(),
                operation = if (sameAccount) previous?.operation?.takeUnless { it.status == BouncerOperationStatus.RUNNING } else null,
            ))
        }
    }

    public fun connected(networkId: String, generation: Long, mode: NetworkMode, capabilities: Set<String>, username: String = "", boundNetwork: String? = null) {
        synchronized(lock) {
            val current = mutableAccounts.value[networkId]
            if (current?.generation != generation || current.mode != mode || current.username != username || current.boundNetwork != boundNetwork) start(networkId, generation, mode, username, boundNetwork)
            update(networkId, generation) { it.copy(connected = true, capabilities = capabilities, username = username, boundNetwork = boundNetwork) }
        }
        if (mode != NetworkMode.DIRECT) scope.launch { refresh(networkId) }
    }

    public fun capabilitiesChanged(networkId: String, generation: Long, capabilities: Set<String>) {
        update(networkId, generation) { account ->
            val availability = when {
                account.mode != NetworkMode.SOJU -> account.sojuService
                IrcBouncerNetworks.CAPABILITY !in capabilities -> BouncerServiceAvailability.UNSUPPORTED
                account.sojuService == BouncerServiceAvailability.UNSUPPORTED -> BouncerServiceAvailability.UNKNOWN
                else -> account.sojuService
            }
            account.copy(capabilities = capabilities, sojuService = availability)
        }
    }

    public fun disconnected(networkId: String, generation: Long) {
        synchronized(lock) {
            if (mutableAccounts.value[networkId]?.generation != generation) return
            pending.remove(networkId)?.result?.completeExceptionally(Failure("Disconnected."))
            batches.keys.removeAll { it.first == networkId }
            historyBatches.removeAll { it.first == networkId }
            nativeBatches.removeAll { it.first == networkId }
            recoveryFences.remove(networkId to generation)?.result?.completeExceptionally(Failure("Disconnected."))
            update(networkId, generation) { it.copy(connected = false, sojuService = BouncerServiceAvailability.UNKNOWN, zncStatus = BouncerServiceAvailability.UNKNOWN, zncControlPanel = BouncerServiceAvailability.UNKNOWN) }
        }
    }

    public fun remove(networkId: String) {
        synchronized(lock) {
            pending.remove(networkId)?.result?.completeExceptionally(Failure("Account removed."))
            batches.keys.removeAll { it.first == networkId }
            historyBatches.removeAll { it.first == networkId }
            nativeBatches.removeAll { it.first == networkId }
            recoveryFences.keys.filter { it.first == networkId }.forEach { key ->
                recoveryFences.remove(key)?.result?.completeExceptionally(Failure("Account removed."))
            }
            unresolvedGenerations.removeAll { it.first == networkId }
            mutableAccounts.value = mutableAccounts.value - networkId
            operationLocks.remove(networkId)
        }
    }

    public fun receive(networkId: String, generation: Long, message: IrcMessage): Boolean = synchronized(lock) {
        val mode = managedModes[networkId] ?: return@synchronized false
        if (mode == NetworkMode.DIRECT) return@synchronized false
        val command = message.command.uppercase()
        val params = message.parameters
        val sender = message.prefix?.nick.orEmpty()
        val service = sender.equals("BouncerServ", true) || sender.equals("*status", true) || sender.equals("*controlpanel", true)
        val serviceTarget = command in setOf("NOTICE", "PRIVMSG", "TAGMSG") && params.firstOrNull()?.let { it.equals("BouncerServ", true) || it.equals("*status", true) || it.equals("*controlpanel", true) } == true
        val directLabel = message.tag("label")
        val label = directLabel ?: message.tag("batch")?.let { batches[networkId to it] }
        val ownLabel = directLabel?.startsWith(LABEL_PREFIX) == true || label?.startsWith(LABEL_PREFIX) == true
        val native = mode == NetworkMode.SOJU && command == "BOUNCER"
        val nativeFail = mode == NetworkMode.SOJU && command == "FAIL" && params.firstOrNull().equals("BOUNCER", true)
        val missingService = command == "401" && params.any { it.equals("BouncerServ", true) || it.equals("*status", true) || it.equals("*controlpanel", true) }
        val batchToken = params.firstOrNull().orEmpty()
        val batchId = batchToken.drop(1)
        val knownBatch = command == "BATCH" && batches.containsKey(networkId to batchId)
        val networkBatch = command == "BATCH" && params.getOrNull(1) == IrcBouncerNetworks.BATCH_TYPE
        val pong = command == "PONG" && params.lastOrNull()?.startsWith(LABEL_PREFIX) == true
        val nativeBatchBoundary = command == "BATCH" && (networkBatch || networkId to batchId in nativeBatches)
        val consumed = service || serviceTarget || native || nativeFail || missingService || ownLabel || knownBatch || nativeBatchBoundary || pong
        if (mutableAccounts.value[networkId]?.let { it.generation == generation && it.connected } != true) return@synchronized consumed
        val batchContext = message.tag("batch")
        val historical = message.tags.containsKey("draft/chathistory-context") ||
            batchContext?.let { networkId to it in historyBatches || networkId to it !in nativeBatches && !batches.containsKey(networkId to it) } == true ||
            command == "BATCH" && batchToken.startsWith("+") &&
            (params.getOrNull(1)?.contains("chathistory", true) == true || params.getOrNull(1)?.contains("playback", true) == true)
        val activeReply = pending[networkId]?.takeIf { it.generation == generation }
        if (historical && activeReply?.nativeCommand == "LISTNETWORKS" && label == activeReply.label) activeReply.invalidSnapshot = true
        if (command == "BATCH" && batchToken.startsWith("+")) {
            if (networkBatch) nativeBatches.add(networkId to batchId)
            if (historical) {
                historyBatches.add(networkId to batchId)
                return@synchronized consumed
            }
        }
        if (command == "BATCH" && batchToken.startsWith("-")) {
            nativeBatches.remove(networkId to batchId)
            if (historyBatches.remove(networkId to batchId)) return@synchronized consumed
        }
        if (historical || !consumed) return@synchronized consumed
        if (pong) {
            val fence = recoveryFences[networkId to generation]
            if (fence != null && params.lastOrNull() == fence.token && label == null) {
                unresolvedGenerations.remove(networkId to generation)
                fence.result.complete(Unit)
                return@synchronized true
            }
        }
        val reply = pending[networkId]?.takeIf { it.generation == generation }
        if (native && batchContext == null && (label == null || reply?.label == label) && params.firstOrNull().equals("NETWORK", true)) {
            IrcBouncerNetworks.parseNetwork(params)?.let { change ->
                if (reply?.nativeCommand == "LISTNETWORKS") reply.liveDeltas.add(change)
                update(networkId, generation) { state ->
                    val networks = when (val delta = change.change) {
                        is IrcBouncerNetworks.Change.Upsert -> state.sojuNetworks + (change.netId to (state.sojuNetworks[change.netId] ?: IrcBouncerNetworks.Attributes()).merged(delta.attributes.copy(pass = null)))
                        IrcBouncerNetworks.Change.Deleted -> state.sojuNetworks - change.netId
                    }
                    state.copy(sojuNetworks = networks)
                }
            }
        }
        if (command == "BATCH") {
            if (batchToken.startsWith("+")) {
                val routed = label ?: reply?.label?.takeIf { networkBatch && reply.nativeCommand == "LISTNETWORKS" && !reply.labeled }
                if (routed != null) {
                    batches[networkId to batchId] = routed
                    if (reply?.label == routed) {
                        if (reply.rootBatch == null) reply.rootBatch = batchId
                        if (networkBatch && reply.nativeCommand == "LISTNETWORKS") {
                            if (reply.snapshotBatch != null) reply.invalidSnapshot = true else reply.snapshotBatch = batchId
                        }
                    }
                }
            } else if (batchToken.startsWith("-")) {
                val routed = batches.remove(networkId to batchId)
                if (reply != null && routed == reply.label) {
                    if (reply.nativeCommand == "LISTNETWORKS" && reply.snapshotBatch == batchId) {
                        reply.snapshotClosed = true
                        if (reply.rootBatch == batchId) completeSnapshot(networkId, generation, reply)
                    } else if (reply.rootBatch == batchId) {
                        if (reply.nativeCommand == "LISTNETWORKS") {
                            if (reply.snapshotClosed) completeSnapshot(networkId, generation, reply)
                            else reply.result.completeExceptionally(Failure("Unsupported bouncer network snapshot response."))
                        } else reply.result.complete(reply.messages.toList())
                    }
                }
            }
            return@synchronized consumed
        }
        if (reply == null) return@synchronized consumed
        val hasForeignLabel = label != null && label != reply.label
        if (hasForeignLabel) return@synchronized true
        if (reply.labeled && label != reply.label) return@synchronized consumed
        if (pong && params.lastOrNull() == reply.label) {
            if (reply.target != null && label == null) reply.result.complete(reply.messages.toList())
            return@synchronized true
        }
        if (missingService && params.any { it.equals(reply.target, true) }) {
            availability(networkId, generation, reply.target, BouncerServiceAvailability.UNAVAILABLE)
            reply.result.completeExceptionally(Failure("Management service is unavailable."))
            return@synchronized true
        }
        if (service && sender.equals("*status", true) && reply.target == "*controlpanel" &&
            params.lastOrNull()?.contains("No such module controlpanel", true) == true) {
            availability(networkId, generation, "*controlpanel", BouncerServiceAvailability.UNAVAILABLE)
            reply.result.completeExceptionally(Failure("The controlpanel module is unavailable."))
            return@synchronized true
        }
        if (nativeFail && (label == reply.label || params.getOrNull(2).equals(reply.nativeCommand, true))) {
            if (reply.nativeId != null && (params.getOrNull(1) == "INVALID_NETID" || params.size >= 6) && params.getOrNull(3) != reply.nativeId) return@synchronized true
            reply.result.completeExceptionally(Failure("Bouncer rejected the request (${safeCode(params.getOrNull(1))})."))
            return@synchronized true
        }
        if (command == "FAIL" && ownLabel) {
            reply.result.completeExceptionally(Failure("Management request rejected (${safeCode(params.getOrNull(1))})."))
            return@synchronized true
        }
        if (service && sender.equals(reply.target, true) && command in setOf("NOTICE", "PRIVMSG")) {
            reply.messages.add(message)
            if (label == reply.label && message.tag("batch") == null) reply.result.complete(reply.messages.toList())
        } else if (native && (label == reply.label || !reply.labeled && label == null)) {
            if (reply.nativeCommand == "LISTNETWORKS") {
                if (params.firstOrNull().equals("NETWORK", true) && batchContext == reply.snapshotBatch && batchContext != null) reply.messages.add(message)
            } else {
                reply.messages.add(message)
                if (params.firstOrNull().equals(reply.nativeCommand, true) && (reply.nativeId == null || params.getOrNull(1) == reply.nativeId)) reply.result.complete(reply.messages.toList())
            }
        } else if (command == "ACK" && label == reply.label) {
            reply.result.complete(reply.messages.toList())
        }
        consumed
    }

    private fun completeSnapshot(networkId: String, generation: Long, reply: Reply) {
        val discovered = mutableMapOf<String, IrcBouncerNetworks.Attributes>()
        for (message in reply.messages) {
            val change = IrcBouncerNetworks.parseNetwork(message.parameters)
            val attributes = (change?.change as? IrcBouncerNetworks.Change.Upsert)?.attributes
            if (change == null || attributes == null || discovered.containsKey(change.netId)) {
                reply.invalidSnapshot = true
                break
            }
            discovered[change.netId] = attributes.copy(
                pass = null,
                unknown = reply.snapshotBaseline[change.netId]?.unknown.orEmpty().filterKeys { it == "enabled" } + attributes.unknown,
            )
        }
        if (reply.invalidSnapshot) {
            reply.result.completeExceptionally(Failure("Malformed bouncer network snapshot."))
            return
        }
        val fallback = reply.snapshotBaseline.toMutableMap()
        for (change in reply.liveDeltas) {
            when (val delta = change.change) {
                is IrcBouncerNetworks.Change.Upsert -> discovered[change.netId] =
                    (discovered[change.netId] ?: fallback[change.netId] ?: IrcBouncerNetworks.Attributes()).merged(delta.attributes.copy(pass = null))
                IrcBouncerNetworks.Change.Deleted -> {
                    discovered.remove(change.netId)
                    fallback.remove(change.netId)
                }
            }
        }
        update(networkId, generation) { it.copy(sojuNetworks = discovered.toMap()) }
        reply.result.complete(reply.messages.toList())
    }

    private suspend fun recover(networkId: String, generation: Long) {
        val key = networkId to generation
        val fence = synchronized(lock) {
            RecoveryFence("$LABEL_PREFIX${++serial}").also { recoveryFences[key] = it }
        }
        try {
            send(networkId, generation, "PING :${fence.token}")
            withTimeout(timeoutMs) { fence.result.await() }
        } catch (error: TimeoutCancellationException) {
            throw Failure("Reconnect before issuing another management request: the reply recovery fence timed out.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Failure) {
            throw error
        } catch (error: Exception) {
            throw Failure("Could not establish a safe management reply boundary.")
        } finally {
            synchronized(lock) { if (recoveryFences[key] === fence) recoveryFences.remove(key) }
        }
    }

    private fun safeCode(value: String?): String = value?.takeIf { it.length <= 64 && it.all { ch -> ch.isLetterOrDigit() || ch == '_' } } ?: "REJECTED"

    private fun update(networkId: String, generation: Long, transform: (BouncerAccountState) -> BouncerAccountState) {
        synchronized(lock) {
            val state = mutableAccounts.value[networkId]?.takeIf { it.generation == generation } ?: return
            mutableAccounts.value = mutableAccounts.value + (networkId to transform(state))
        }
    }

    private fun state(networkId: String): BouncerAccountState = accounts.value[networkId]?.takeIf { it.connected } ?: throw Failure("Bouncer is offline.")

    private fun availability(networkId: String, generation: Long, target: String?, value: BouncerServiceAvailability) {
        update(networkId, generation) { when (target?.lowercase()) {
            "bouncerserv" -> it.copy(sojuService = value)
            "*status" -> it.copy(zncStatus = value)
            "*controlpanel" -> it.copy(zncControlPanel = value)
            else -> it
        } }
    }

    private suspend fun request(networkId: String, generation: Long, step: Step): List<IrcMessage> {
        val state = state(networkId)
        if (state.generation != generation) throw Failure("Connection replaced.")
        val labeled = "labeled-response" in state.capabilities && "message-tags" in state.capabilities
        if (!labeled && synchronized(lock) { networkId to generation in unresolvedGenerations }) recover(networkId, generation)
        require(step.wire.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid management input." }
        val reply = synchronized(lock) {
            if (pending.containsKey(networkId)) throw Failure("Another management request is running.")
            val current = mutableAccounts.value[networkId]?.takeIf { it.generation == generation && it.connected } ?: throw Failure("Connection replaced.")
            Reply(generation, "$LABEL_PREFIX${++serial}", step.target, step.nativeCommand, step.nativeId, labeled, current.sojuNetworks).also { pending[networkId] = it }
        }
        try {
            send(networkId, generation, if (labeled) "@label=${reply.label} ${step.wire}" else step.wire)
            if (!labeled) send(networkId, generation, "PING :${reply.label}")
            val messages = withTimeout(timeoutMs) { reply.result.await() }
            if (reply.nativeCommand == "LISTNETWORKS" && reply.snapshotBatch == null) throw Failure("Unsupported bouncer network snapshot response.")
            if (step.target != null && lines(messages).any { it.trim().equals("Access denied!", true) }) {
                availability(networkId, generation, step.target, BouncerServiceAvailability.AVAILABLE)
                throw Failure("Access denied by the bouncer.")
            }
            if (!step.accepted(messages)) throw Failure("Management service rejected the request or returned an unsupported response.")
            if (step.target != null) availability(networkId, generation, step.target, BouncerServiceAvailability.AVAILABLE)
            return messages
        } catch (error: TimeoutCancellationException) {
            if (!labeled) synchronized(lock) { unresolvedGenerations.add(networkId to generation) }
            throw Failure("Management request timed out.")
        } catch (error: CancellationException) {
            if (!labeled) synchronized(lock) { unresolvedGenerations.add(networkId to generation) }
            throw error
        } catch (error: Failure) {
            throw error
        } catch (error: Exception) {
            if (!labeled) synchronized(lock) { unresolvedGenerations.add(networkId to generation) }
            throw Failure("Could not send management request.")
        } finally {
            synchronized(lock) {
                if (pending[networkId] === reply) pending.remove(networkId)
                batches.entries.removeAll { it.key.first == networkId && it.value == reply.label }
            }
        }
    }

    private fun native(command: String, id: String? = null, wire: String): Step = Step(wire, nativeCommand = command, nativeId = id, accepted = { messages ->
        if (command == "LISTNETWORKS") true else messages.any { it.command.equals("BOUNCER", true) && it.parameters.firstOrNull().equals(command, true) && (id == null || it.parameters.getOrNull(1) == id) }
    })

    private fun service(target: String, body: String, accepted: (List<String>) -> Boolean): Step = Step("PRIVMSG $target :$body", target = target, accepted = { accepted(lines(it)) })

    private fun lines(messages: List<IrcMessage>): List<String> = messages.filter { it.command.equals("NOTICE", true) || it.command.equals("PRIVMSG", true) }.mapNotNull { it.parameters.lastOrNull() }

    private suspend fun operate(networkId: String, build: (BouncerAccountState) -> List<Step>, refresh: suspend (Long) -> Unit): BouncerOperationOutcome =
        scope.async { operateOwned(networkId, build, refresh) }.await()

    private suspend fun operateOwned(networkId: String, build: (BouncerAccountState) -> List<Step>, refresh: suspend (Long) -> Unit): BouncerOperationOutcome {
        val mutex = synchronized(lock) { operationLocks.getOrPut(networkId) { Mutex() } }
        if (!mutex.tryLock()) return BouncerOperationOutcome(BouncerOperationStatus.ERROR, error = "Another management operation is running.")
        var applied = 0
        var total = 0
        var failure: String? = null
        var refreshFailure: String? = null
        var cancellation: CancellationException? = null
        var outcome = BouncerOperationOutcome(BouncerOperationStatus.RUNNING)
        val generation = accounts.value[networkId]?.generation ?: 0
        update(networkId, generation) { it.copy(operation = outcome) }
        try {
            val steps = build(state(networkId))
            total = steps.size
            for (step in steps) {
                request(networkId, generation, step)
                applied++
                update(networkId, generation) { it.copy(operation = BouncerOperationOutcome(BouncerOperationStatus.RUNNING, applied, total)) }
            }
        } catch (error: CancellationException) {
            failure = "Management operation cancelled."
            cancellation = error
        } catch (error: Failure) {
            failure = error.safeReason
        } catch (error: IllegalArgumentException) {
            failure = "Invalid or unsupported management input."
        } catch (error: Exception) {
            failure = "Could not complete the management operation."
        } finally {
            try {
                if (cancellation == null && accounts.value[networkId]?.let { it.connected && it.generation == generation } == true) refresh(generation)
            } catch (error: CancellationException) {
                refreshFailure = "Refresh cancelled."
                cancellation = error
            } catch (error: Exception) {
                refreshFailure = "Could not refresh current bouncer state."
            }
            val status = if (failure == null && refreshFailure == null) BouncerOperationStatus.SUCCESS else if (applied > 0) BouncerOperationStatus.PARTIAL else BouncerOperationStatus.ERROR
            outcome = BouncerOperationOutcome(status, applied, total, failure, refreshFailure)
            update(networkId, generation) { it.copy(operation = outcome) }
            mutex.unlock()
        }
        cancellation?.let { throw it }
        return outcome
    }

    public suspend fun refresh(networkId: String): BouncerOperationOutcome = operate(networkId, { emptyList() }) { generation ->
        when (state(networkId).mode) {
            NetworkMode.SOJU -> refreshSoju(networkId, generation)
            NetworkMode.ZNC -> refreshZnc(networkId, generation)
            NetworkMode.DIRECT -> throw Failure("This is not a bouncer account.")
        }
    }

    private suspend fun refreshSoju(networkId: String, generation: Long) {
        val account = state(networkId)
        if (IrcBouncerNetworks.CAPABILITY !in account.capabilities) throw Failure("Bouncer network management was not advertised.")
        request(networkId, generation, native("LISTNETWORKS", wire = "BOUNCER LISTNETWORKS"))
        availability(networkId, generation, "BouncerServ", BouncerServiceAvailability.PROBING)
        try {
            request(networkId, generation, service("BouncerServ", "help channel update") { it.any { line -> line.contains("channel update") && line.contains("detach") } })
            val statuses = lines(request(networkId, generation, service("BouncerServ", "network status") { reply ->
                reply.any { line -> '[' in line && ']' in line } || reply.any { line -> line.startsWith("No network configured") }
            }))
            update(networkId, generation) { previous ->
                previous.copy(sojuNetworks = previous.sojuNetworks.mapValues { (_, attrs) ->
                    val name = attrs.name.orEmpty()
                    val reported = statuses.firstOrNull { line -> line.startsWith("$name [") || line.startsWith("$name (") }
                    if (reported == null) attrs else {
                        val address = if (reported.startsWith("$name (")) reported.substring(name.length + 2).substringBeforeLast(") [") else name
                        attrs.copy(unknown = attrs.unknown + mapOf(
                            "enabled" to if ("disabled" in reported.substringAfterLast('[').substringBefore(']').split(',').map(String::trim)) "0" else "1",
                            "addr" to address,
                        ))
                    }
                })
            }
        } catch (error: Failure) {
            availability(networkId, generation, "BouncerServ", BouncerServiceAvailability.UNAVAILABLE)
        }
    }

    private fun requireMode(state: BouncerAccountState, mode: NetworkMode) {
        if (state.mode != mode) throw Failure("The selected account does not support this management operation.")
        if (mode == NetworkMode.SOJU && IrcBouncerNetworks.CAPABILITY !in state.capabilities) throw Failure("Bouncer network management was not advertised.")
    }

    public suspend fun addSojuNetwork(networkId: String, draft: BouncerNetworkDraft): BouncerOperationOutcome = operate(networkId, { account ->
        requireMode(account, NetworkMode.SOJU)
        if (draft.addrValidationError() != null) throw Failure("Invalid upstream address.")
        val attributes = draft.toAttributes().let { if (!draft.enabled && it.name == null) it.copy(name = draft.addressWithoutUserInfo().trim()) else it }
        val steps = mutableListOf(native("ADDNETWORK", wire = IrcBouncerNetworks.addNetworkCommand(attributes)))
        if (!draft.enabled) steps.add(service("BouncerServ", BouncerServCommand.networkUpdate(attributes.name.orEmpty(), false)) { it.any { line -> line.startsWith("updated network ") } })
        steps
    }) { refreshSoju(networkId, it) }

    public suspend fun updateSojuNetwork(networkId: String, netId: String, draft: BouncerNetworkDraft): BouncerOperationOutcome = operate(networkId, { account ->
        requireMode(account, NetworkMode.SOJU)
        val baseline = account.sojuNetworks[netId] ?: throw Failure("Upstream network is no longer available.")
        val unchangedUnknownAddress = draft.addr.isEmpty() && baseline.host.isNullOrEmpty() && baseline.unknown["addr"].isNullOrEmpty()
        if (!unchangedUnknownAddress && draft.addrValidationError() != null) throw Failure("Invalid upstream address.")
        val steps = mutableListOf<Step>()
        val diff = draft.attributesChangedAgainst(baseline)
        if (diff.attributeString().isNotEmpty()) steps.add(native("CHANGENETWORK", netId, IrcBouncerNetworks.changeNetworkCommand(netId, diff)))
        val enabled = baseline.unknown["enabled"] != "0"
        if (draft.enabled != enabled) {
            val name = if (diff.name != null) draft.name.trim().ifEmpty {
                BouncerNetworkDraft(addr = baseline.unknown["addr"] ?: draft.addr).addressWithoutUserInfo().trim()
            } else baseline.name.orEmpty()
            if (name.isEmpty()) throw Failure("Upstream network name is unavailable.")
            steps.add(service("BouncerServ", BouncerServCommand.networkUpdate(name, draft.enabled)) { it.any { line -> line.startsWith("updated network ") } })
        }
        steps
    }) { refreshSoju(networkId, it) }

    public suspend fun removeSojuNetwork(networkId: String, netId: String): BouncerOperationOutcome = operate(networkId, { account ->
        requireMode(account, NetworkMode.SOJU)
        listOf(native("DELNETWORK", netId, IrcBouncerNetworks.delNetworkCommand(netId)))
    }) { refreshSoju(networkId, it) }

    public suspend fun fetchSojuChannel(networkId: String, channel: String): BouncerOperationOutcome = operate(networkId, { account ->
        requireMode(account, NetworkMode.SOJU)
        emptyList()
    }) { refreshSojuChannel(networkId, it, channel) }

    private suspend fun refreshSojuChannel(networkId: String, generation: Long, channel: String) {
        val messages = request(networkId, generation, service("BouncerServ", BouncerServCommand.channelStatus()) { reply -> reply.any { it.startsWith("$channel [") } })
        val line = lines(messages).first { it.startsWith("$channel [") }
        val status = line.substringAfter('[').substringBeforeLast(']')
        val settings = SojuChannelSettings(channel, status, detached = "detached" in status.split(',').map(String::trim))
        update(networkId, generation) { it.copy(sojuChannels = it.sojuChannels + (channel to settings)) }
    }

    public suspend fun updateSojuChannel(networkId: String, baseline: SojuChannelSettings, draft: SojuChannelSettings): BouncerOperationOutcome = operate(networkId, { account ->
        requireMode(account, NetworkMode.SOJU)
        if (account.sojuChannels[baseline.name] !== baseline) throw Failure("Channel settings changed or this connection was replaced. Fetch current settings before applying.")
        require(baseline.name == draft.name)
        val detach = draft.detachAfterSeconds?.takeIf { it != baseline.detachAfterSeconds }
        val relay = draft.relayDetached?.takeIf { it != baseline.relayDetached }
        val reattach = draft.reattachOn?.takeIf { it != baseline.reattachOn }
        val detached = draft.detached?.takeIf { it != baseline.detached }
        if (detach == null && relay == null && reattach == null && detached == null) emptyList() else listOf(service("BouncerServ", BouncerServCommand.channelUpdate(draft.name, detach, relay, reattach, detached)) { it.any { line -> line.startsWith("updated channel ") } })
    }) { refreshSojuChannel(networkId, it, draft.name) }


    private suspend fun refreshZnc(networkId: String, generation: Long) {
        availability(networkId, generation, "*status", BouncerServiceAvailability.PROBING)
        val reply = lines(request(networkId, generation, service("*status", BouncerZncCommands.listNetworks().text) { BouncerZncParser.networks(it) != null }))
        val networks = BouncerZncParser.networks(reply) ?: throw Failure("Unsupported network list response.")
        update(networkId, generation) { it.copy(zncNetworks = networks) }
        availability(networkId, generation, "*controlpanel", BouncerServiceAvailability.PROBING)
        try {
            request(networkId, generation, service("*controlpanel", "Help GetNetwork") { it.any { line -> line.contains("GetNetwork") && !line.contains("Unknown", true) && !line.contains("error", true) } })
        } catch (error: Failure) {
            availability(networkId, generation, "*controlpanel", BouncerServiceAvailability.UNAVAILABLE)
        }
    }

    public suspend fun addZncNetwork(networkId: String, name: String): BouncerOperationOutcome = operate(networkId, { account ->
        requireMode(account, NetworkMode.ZNC)
        listOf(zncStep(BouncerZncCommands.addNetwork(name)))
    }) { refreshZnc(networkId, it) }

    public suspend fun removeZncNetwork(networkId: String, name: String): BouncerOperationOutcome = operate(networkId, { account ->
        requireMode(account, NetworkMode.ZNC)
        listOf(zncStep(BouncerZncCommands.deleteNetwork(name)))
    }) { refreshZnc(networkId, it) }

    public suspend fun setZncConnection(networkId: String, network: String, action: ZncConnectionAction): BouncerOperationOutcome = operate(networkId, { account ->
        requireMode(account, NetworkMode.ZNC)
        if (account.boundNetwork != network) throw Failure("Connect this account to the selected upstream network before controlling its connection.")
        val command = when (action) {
            ZncConnectionAction.CONNECT -> BouncerZncCommands.connect()
            ZncConnectionAction.DISCONNECT -> BouncerZncCommands.disconnect()
            ZncConnectionAction.RECONNECT -> BouncerZncCommands.reconnect()
        }
        listOf(zncStep(command))
    }) { refreshZnc(networkId, it) }

    private fun zncStep(command: ZncCommand): Step = service(if (command.target == ZncCommandTarget.Status) "*status" else "*controlpanel", command.text) { reply -> reply.any(command::matchesAcknowledgement) }

    public suspend fun fetchZncNetwork(networkId: String, network: String): BouncerOperationOutcome = operate(networkId, { account ->
        requireMode(account, NetworkMode.ZNC)
        emptyList()
    }) { refreshZncNetwork(networkId, it, network) }

    private suspend fun refreshZncNetwork(networkId: String, generation: Long, network: String) {
        val account = state(networkId)
        val settings = mutableMapOf<ZncNetworkVariable, String>()
        for (variable in ZncNetworkVariable.entries) {
            val command = BouncerZncCommands.getNetwork(network, variable)
            val target = if (command.target == ZncCommandTarget.Status) "*status" else "*controlpanel"
            val reply = lines(request(networkId, generation, service(target, command.text) { response ->
                response.any(command::matchesAcknowledgement) || response.any { it.equals("Error: Unknown variable", true) }
            }))
            reply.mapNotNull(BouncerZncParser::networkSetting).firstOrNull { it.first == variable }?.let { settings[variable] = it.second }
        }
        val servers = if (account.boundNetwork == network) {
            val reply = lines(request(networkId, generation, service("*status", BouncerZncCommands.listServers().text) { BouncerZncParser.servers(it) != null }))
            BouncerZncParser.servers(reply) ?: throw Failure("Unsupported server list response.")
        } else account.zncNetworkDetails[network]?.servers.orEmpty()
        update(networkId, generation) { it.copy(zncNetworkDetails = it.zncNetworkDetails + (network to ZncNetworkDraft(network, settings, servers))) }
    }

    public suspend fun applyZncNetwork(networkId: String, baseline: ZncNetworkDraft, draft: ZncNetworkDraft): BouncerOperationOutcome = operate(networkId, { account ->
        requireMode(account, NetworkMode.ZNC)
        if (account.zncNetworkDetails[baseline.name] !== baseline) throw Failure("Network settings changed or this connection was replaced. Fetch current settings before applying.")
        val commands = BouncerZncCommands.networkDiff(baseline, draft)
        if (commands.any { it.text.startsWith("AddServer ") || it.text.startsWith("DelServer ") } && account.boundNetwork != draft.name) throw Failure("Connect to this upstream network to fetch and verify its server list before editing servers.")
        if (commands.any { it.target == ZncCommandTarget.Status } && account.zncStatus != BouncerServiceAvailability.AVAILABLE) throw Failure("The status service is unavailable.")
        if (commands.any { it.target == ZncCommandTarget.ControlPanel } && account.zncControlPanel != BouncerServiceAvailability.AVAILABLE) throw Failure("The controlpanel module is unavailable.")
        commands.map(::zncStep)
    }) { refreshZncNetwork(networkId, it, draft.name) }

    public suspend fun fetchZncChannel(networkId: String, channel: String): BouncerOperationOutcome = operate(networkId, { account ->
        requireMode(account, NetworkMode.ZNC)
        if (account.boundNetwork.isNullOrEmpty()) throw Failure("Connect to an upstream network first.")
        if (account.zncStatus != BouncerServiceAvailability.AVAILABLE) throw Failure("The status service is unavailable.")
        emptyList()
    }) { refreshZncChannel(networkId, it, channel) }

    private suspend fun refreshZncChannel(networkId: String, generation: Long, channel: String) {
        val reply = lines(request(networkId, generation, service("*status", BouncerZncCommands.listChans().text) { BouncerZncParser.channels(it) != null }))
        val found = BouncerZncParser.channels(reply)?.firstOrNull { it.name == channel } ?: throw Failure("The channel is not configured on this upstream network.")
        update(networkId, generation) { it.copy(zncChannels = it.zncChannels + (channel to found.toDraft())) }
    }

    public suspend fun applyZncChannel(networkId: String, baseline: ZncChannelDraft, draft: ZncChannelDraft): BouncerOperationOutcome = operate(networkId, { account ->
        requireMode(account, NetworkMode.ZNC)
        if (account.zncChannels[baseline.name] !== baseline) throw Failure("Channel settings changed or this connection was replaced. Fetch current settings before applying.")
        val network = account.boundNetwork ?: throw Failure("Connect to an upstream network first.")
        val commands = BouncerZncCommands.channelDiff(network, baseline, draft, account.boundNetwork)
        if (commands.any { it.target == ZncCommandTarget.ControlPanel } && account.zncControlPanel != BouncerServiceAvailability.AVAILABLE) throw Failure("The controlpanel module is unavailable.")
        commands.map(::zncStep)
    }) { refreshZncChannel(networkId, it, draft.name) }

    private companion object {
        const val LABEL_PREFIX = "yardhal-mgmt-"
    }
}
