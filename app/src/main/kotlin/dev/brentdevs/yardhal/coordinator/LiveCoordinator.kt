package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.client.AuthenticationRejectedException
import dev.brentdevs.yardhal.core.client.BouncerBindRejectedException
import dev.brentdevs.yardhal.core.client.CertificateInspection
import dev.brentdevs.yardhal.core.client.CertificateRejectedException
import dev.brentdevs.yardhal.core.client.ReconnectState
import dev.brentdevs.yardhal.core.client.ReconnectionEvent
import dev.brentdevs.yardhal.core.client.Socks5Exception
import dev.brentdevs.yardhal.core.client.TlsClientIdentity
import dev.brentdevs.yardhal.core.client.TlsIdentityUnavailableException
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcEvent
import dev.brentdevs.yardhal.core.client.IrcReconnector
import dev.brentdevs.yardhal.core.client.ReconnectPolicy
import dev.brentdevs.yardhal.core.client.StsPolicyStore
import dev.brentdevs.yardhal.core.client.StsResolver
import dev.brentdevs.yardhal.core.client.StsUpgradeDecision
import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.CredentialVault
import dev.brentdevs.yardhal.core.data.CertificatePin
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MESSAGE_CONVERSATION_LIMIT
import dev.brentdevs.yardhal.core.data.MESSAGE_NETWORK_LIMIT
import dev.brentdevs.yardhal.core.data.MESSAGE_PER_CONVERSATION_LIMIT
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.MentionMatcher
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.NetworkMode
import dev.brentdevs.yardhal.core.data.SojuUpstreamConfig
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.SaslMode
import dev.brentdevs.yardhal.core.data.SlashCommand
import dev.brentdevs.yardhal.core.data.SlashCommandParser
import dev.brentdevs.yardhal.core.data.StoredMessage
import dev.brentdevs.yardhal.core.data.CachedUser
import dev.brentdevs.yardhal.core.data.OfflineConversationShell
import dev.brentdevs.yardhal.core.data.OfflineRoster
import dev.brentdevs.yardhal.core.data.OfflineStore
import dev.brentdevs.yardhal.core.data.ReadCursor
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.HistoryAnchor
import dev.brentdevs.yardhal.core.protocol.IrcCtcp
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcMultiline
import dev.brentdevs.yardhal.core.protocol.IrcMetadata
import dev.brentdevs.yardhal.core.protocol.IrcPrefix
import dev.brentdevs.yardhal.core.client.SaslOutcome
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import dev.brentdevs.yardhal.ui.image.ImageUrlPolicy
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public fun interface ConnectionFactory {
    public fun create(config: NetworkConfig, onStsUpgrade: (Int) -> Unit): IrcConnection
}

public class LiveCoordinator(
    public val scope: CoroutineScope,
    public val networkStore: NetworkStore,
    public val messageStore: MessageStore,
    public val readMarkers: ReadMarkerStore,
    private val historyCoverage: dev.brentdevs.yardhal.core.data.HistoryCoverageStore,
    public val mutes: MuteStore,
    private val vault: CredentialVault,
    private val channelOrder: dev.brentdevs.yardhal.core.data.ChannelOrderStore,
    private val connectionFactory: ConnectionFactory,
    private val stsPolicies: StsPolicyStore,
    private val notifier: HighlightNotifier = HighlightNotifier { _, _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val historyElapsedClock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val clientIdentityProvider: (String) -> TlsClientIdentity = {
        throw TlsIdentityUnavailableException("The selected TLS client identity is unavailable. Select it again in network settings.")
    },
    private val offlineStore: OfflineStore? = null,
    public val relayStore: dev.brentdevs.yardhal.core.data.RelayConfigurationStore? = null,
) {
    public fun interface HighlightNotifier {
        public fun onHighlight(networkName: String, sender: String, conversationName: String, text: String)
    }

    private val idGenerator = AtomicLong(1)

    private val _networkStates = MutableStateFlow<List<UiNetwork>>(emptyList())
    public val networks: StateFlow<List<UiNetwork>> = _networkStates.asStateFlow()

    private val _buffers = MutableStateFlow<Map<String, ConversationBuffer>>(emptyMap())
    public val buffers: StateFlow<Map<String, ConversationBuffer>> = _buffers.asStateFlow()

    private val _profiles = MutableStateFlow<Map<String, NetworkProfiles>>(emptyMap())
    public val profiles: StateFlow<Map<String, NetworkProfiles>> = _profiles.asStateFlow()

    private val _whois = MutableStateFlow<dev.brentdevs.yardhal.core.data.WhoisInfo?>(null)
    public val whois: StateFlow<dev.brentdevs.yardhal.core.data.WhoisInfo?> = _whois.asStateFlow()

    private val _whoisPresentation = MutableStateFlow<WhoisPresentation?>(null)
    public val whoisPresentation: StateFlow<WhoisPresentation?> = _whoisPresentation.asStateFlow()

    private val _restoredSelection = MutableStateFlow<String?>(null)
    public val restoredSelection: StateFlow<String?> = _restoredSelection.asStateFlow()
    private val _restorationReady = MutableStateFlow(false)
    public val restorationReady: StateFlow<Boolean> = _restorationReady.asStateFlow()
    @Volatile private var startupRequested = false
    private var maintenanceJob: Job? = null
    private var lastMaintenanceAtMs: Long? = null
    private val writesSinceTrim = ConcurrentHashMap<String, Int>()
    private val loadedRosters = ConcurrentHashMap.newKeySet<String>()
    private val metadataRequests = ConcurrentHashMap<String, String>()
    private val restoredCasemapping = ConcurrentHashMap<String, CaseMapping>()
    private val loadedUsers = ConcurrentHashMap.newKeySet<String>()
    private val persistedRowsByLocalId = ConcurrentHashMap<String, Long>()
    private val reactionRevisions = ConcurrentHashMap<String, Long>()
    private val topicObservedAt = ConcurrentHashMap<String, Long>()
    private val rosterObservedAt = ConcurrentHashMap<String, Long>()
    private val modesObservedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var whoisRequestToken = 0L
    @Volatile private var requestedWhois: Pair<String, String>? = null
    @Volatile private var requestedWhoisOrigin: String? = null

    private val _operationError = MutableStateFlow<String?>(null)
    public val operationError: StateFlow<String?> = _operationError.asStateFlow()
    public val readMarkerPersistence: StateFlow<dev.brentdevs.yardhal.core.data.ReadMarkerPersistence>
        get() = readMarkers.persistence

    public fun dismissOperationError(expectedError: String) {
        _operationError.compareAndSet(expectedError, null)
    }

    public fun dismissWhois() {
        synchronized(selectionLock) {
            whoisRequestToken += 1
            requestedWhois = null
            requestedWhoisOrigin = null
        }
        _whois.value = null
        _whoisPresentation.value = null
    }

    private var ignoreStore: dev.brentdevs.yardhal.core.data.IgnoreStore? = null

    public fun attachIgnores(store: dev.brentdevs.yardhal.core.data.IgnoreStore) {
        ignoreStore = store
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    public val bouncerManagement: BouncerManagement = BouncerManagement(scope, send = { networkId, generation, line ->
        val session = sessions[networkId] ?: throw java.io.IOException("The bouncer connection is no longer available")
        synchronized(session.state) {
            if (sessions[networkId] !== session || session.managementGeneration != generation ||
                !session.state.registered || session.acceptedEpoch != session.attemptToken ||
                session.statusFlow.value != ConnectionStatus.REGISTERED
            ) throw java.io.IOException("The bouncer connection changed before the command could be sent")
            val connection = session.connection ?: throw java.io.IOException("The bouncer is disconnected")
            connection.sendLine(line)
        }
    })
    private val pendingStorageMigrations = ConcurrentHashMap<String, Int>()
    private val deferredStorageReads = mutableMapOf<String, MutableList<suspend () -> Unit>>()
    private val renamedConversations = ConcurrentHashMap<String, ConversationRef>()
    private val deferredMaintenanceNetworks = ConcurrentHashMap.newKeySet<String>()
    private val sessionLifecycleLock = Any()
    @Volatile private var networkAvailable = true
    private var defaultNetworkHandle: Long? = null
    private val persistenceLock = Any()
    private var persistenceTail: Job? = null
    private val coalescedPersistence = mutableMapOf<String, suspend () -> Unit>()
    private var persistenceBatch = 0L

    init {
        scope.launch {
            bouncerManagement.accounts.collect { accounts ->
                synchronized(sessionLifecycleLock) {
                    for ((parentId, account) in accounts) {
                        val session = sessions[parentId] ?: continue
                        if (!account.connected || account.generation != session.managementGeneration ||
                            managementMode(session) != NetworkMode.SOJU || session.config.bouncerBinding != null
                        ) continue
                        for ((netId, attributes) in account.sojuNetworks) {
                            val enabled = when (attributes.unknown["enabled"]) {
                                "0" -> false
                                "1" -> true
                                else -> continue
                            }
                            setDiscoveredUpstreamEnabled(parentId, netId, enabled)
                        }
                    }
                }
            }
        }
    }

    private fun enqueueCoalescedPersistence(key: String, operation: suspend () -> Unit) {
        synchronized(persistenceLock) {
            val batchKey = "$persistenceBatch|$key"
            val pending = coalescedPersistence.put(batchKey, operation) != null
            if (pending) return
            enqueuePersistence {
                val latest = synchronized(persistenceLock) { coalescedPersistence.remove(batchKey) }
                latest?.invoke()
            }
        }
    }

    private fun enqueuePersistence(operation: suspend () -> Unit) {
        synchronized(persistenceLock) {
            val previous = persistenceTail
            persistenceTail = scope.launch {
                previous?.join()
                operation()
            }
        }
    }

    private companion object {
        const val TYPING_SEND_INTERVAL_MS = 4_000L
        const val MAINTENANCE_INTERVAL_MS = 300_000L
    }

    private inner class Session(
        @Volatile var config: NetworkConfig,
        casemapping: CaseMapping,
        manuallyWanted: Boolean = false,
        allowStartup: Boolean = true,
    ) {
        val state = PerNetworkState(config.id, config.nick,
            if (config.mode == NetworkMode.SOJU && config.bouncerBinding == null) emptyList() else config.autojoin,
            config.bouncerBinding?.netId,
        ).also {
            it.casemapping = casemapping
        }
        val historyGeneration = idGenerator.getAndIncrement()
        var storedMappingKnown = _buffers.value.values.any { it.ref.networkId == config.id }
        val lifecycleJob = SupervisorJob(scope.coroutineContext[Job])
        val lifecycleScope = CoroutineScope(scope.coroutineContext + lifecycleJob)
        val recovery = ConnectionRecoveryPolicy(config.autoConnect && allowStartup, config.userDisconnected, networkAvailable).also {
            if (manuallyWanted) it.handle(RecoverySignal.MANUAL_CONNECT)
        }
        val statusFlow = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        var reconnector: IrcReconnector? = null
        var connection: IrcConnection? = null
        var redactionConnection: IrcConnection? = null
        @Volatile var quitRequested: Boolean = false
        @Volatile var stsUpgradePort: Int? = null
        var effective: NetworkConfig? = null
        var presentationSecrets: Set<String> = emptySet()
        var attemptToken = 0L
        var acceptedEpoch: Long? = null
        var connectionError: String? = null
        var disconnectSavePending: Boolean = false
        var authenticatedNick: String? = null
        var authenticatedAccount: String? = null
        var saslAuthenticated: Boolean = false
        var rejectedCertificate: CertificateInspection? = null
        var probeJob: Job? = null
        var identificationGate: NickServIdentificationGate? = null
        var identificationDeadline: Job? = null
        var pendingRegistration: IrcEvent.Registered? = null
        val replayedReadCursors = ConcurrentHashMap<String, ReadCursor>()
        var whoisRefreshToken: Long? = null
        var publishedProfileKeys: Set<String> = emptySet()
        var whoisRefreshEpoch: Int? = null
        var managementGeneration = idGenerator.getAndIncrement()
        val sojuDiscovery = SojuDiscoveryTracker()

        init {
            lifecycleJob.invokeOnCompletion {
                reconnector?.stop()
                connection?.disconnect()
            }
        }
    }

    private val history = HistoryCoordinator(messageStore, historyCoverage, scope, clock, historyElapsedClock, object : HistoryHost {
        override val historyBuffers: Map<String, ConversationBuffer>
            get() = _buffers.value

        override fun historyRef(networkId: String, target: String): ConversationRef =
            sessions[networkId]?.state?.targetRef(target)
                ?: _buffers.value.values.firstOrNull { it.ref.networkId == networkId && it.ref.rawTarget == target }?.ref
                ?: ConversationRef.directMessage(networkId, target)

        override fun ensureHistoryBuffer(ref: ConversationRef) {
            buffer(ref)
        }

        override fun updateHistoryBuffer(key: String, transform: (ConversationBuffer) -> ConversationBuffer) {
            updateBufferKey(key, transform)
        }

        override suspend fun mergeStoredHistory(ref: ConversationRef, messages: List<StoredMessage>) {
            mergeDurableContext(ref, messages, prependEqualTimestamp = true)
        }

        override fun replayHistory(
            networkId: String,
            generation: Long,
            epoch: Long,
            result: HistoryResult,
            excludedKeys: Set<String>,
            beforeReplay: (List<InboundEffect>) -> Unit,
        ) {
            val session = sessions[networkId] ?: return
            synchronized(session.state) {
                if (!historyConnectionActive(networkId, generation, epoch)) return
                val firstNewId = idGenerator.get()
                val target = result.request.target?.takeUnless { it == "*" }?.let(session.state::targetRef)
                val effects = mutableListOf<InboundEffect>()
                val context = inboundContext(session, historyPlayback = true, historyTarget = target)
                val opened = mutableSetOf<String>()
                try {
                    for (frame in result.frames) {
                        if (isManagementChat(session, frame)) continue
                        val supported = when (frame.command) {
                            "PRIVMSG", "NOTICE", "TAGMSG", "REDACT" -> true
                            "BATCH" -> !frame.parameters.firstOrNull().orEmpty().startsWith("+") ||
                                when (frame.parameters.getOrNull(1)) {
                                    "chathistory", "znc.in/playback", "labeled-response", "draft/multiline" -> true
                                    else -> false
                                }
                            else -> false
                        }
                        if (!supported) continue
                        if (frame.command == "BATCH") {
                            val head = frame.parameters.firstOrNull().orEmpty()
                            val ref = head.drop(1)
                            if (head.startsWith("+") && ref !in session.state.openBatches) opened.add(ref)
                            if (head.startsWith("-")) {
                                check(session.state.openBatches[ref]?.multiline?.overflowed != true) {
                                    "History multiline exceeded negotiated limits"
                                }
                            }
                        }
                        for (effect in session.state.apply(IrcEvent.MessageReceived(frame), context)) {
                            val ref = when (effect) {
                                is InboundEffect.AppendMessage -> effect.ref
                                is InboundEffect.ApplyReaction -> effect.ref
                                is InboundEffect.RedactMessage -> effect.ref
                                else -> null
                            }
                            if (ref != null && ref.storageKey !in excludedKeys) effects.add(redactEffect(session, effect))
                        }
                    }
                    beforeReplay(effects)
                } finally {
                    for (ref in opened) session.state.openBatches.remove(ref)
                }
                for (effect in effects) processEffect(session, effect)
                if (result.request.operation == HistoryOperation.BEFORE && target != null) {
                    updateBufferKey(target.storageKey) { current ->
                        current.copy(messages = orderBeforeReplay(current.messages, firstNewId))
                    }
                }
            }
        }

        override fun historyConnectionActive(networkId: String, generation: Long, epoch: Long): Boolean {
            val session = sessions[networkId] ?: return false
            return session.historyGeneration == generation && session.state.connectionEpoch.toLong() == epoch &&
                session.statusFlow.value == ConnectionStatus.REGISTERED
        }

        override fun sendHistory(networkId: String, generation: Long, epoch: Long, line: String): Boolean {
            val session = sessions[networkId] ?: return false
            synchronized(session.state) {
                if (!historyConnectionActive(networkId, generation, epoch)) return false
                sendRaw(session, line)
                return true
            }
        }

        override fun reconnectHistory(networkId: String) {
            connectNetwork(networkId)
        }

        override fun historySessionLock(networkId: String, generation: Long, epoch: Long): Any? =
            sessions[networkId]?.takeIf {
                it.historyGeneration == generation && it.state.connectionEpoch.toLong() == epoch
            }?.state

        override fun enqueueHistoryStorage(operation: suspend () -> Unit) {
            enqueuePersistence(operation)
        }
    })

    public fun startAll() {
        synchronized(sessionLifecycleLock) {
            if (startupRequested) return
            startupRequested = true
        }
        val initialSelectionRevision = synchronized(selectionLock) { selectionRevision }
        var startupSelection: ConversationRef? = null
        val startupShellKeys = mutableMapOf<String, Set<String>>()
        enqueuePersistence {
            try {
                val selected = offlineStore?.selection()?.takeIf {
                    networkStore.byId(it.networkId) != null
                }
                startupSelection = selected
                val now = clock()
                for (config in networkStore.all()) {
                    messageStore.maintainNetwork(config.id, MESSAGE_NETWORK_LIMIT, MESSAGE_CONVERSATION_LIMIT,
                        nowMs = now, protectedConversations = retentionPriorities(config.id, selected))
                }
                offlineStore?.maintain(now)
                messageStore.maintain(now)
                val shells = offlineStore?.shells().orEmpty()
                for (shell in shells) restoredCasemapping[shell.ref.networkId] = shell.caseMapping
                for (config in networkStore.all()) {
                    val known = messageStore.knownConversations(config.id)
                    val cached = shells.filter { it.ref.networkId == config.id }
                    val retained = retainConversationShells(config.id, known, cached, selected, restoreExisting = true)
                    startupShellKeys[config.id] = retained
                    readMarkers.retainNetwork(config.id, retained + known.map { it.storageKey })
                }
                for (buffer in _buffers.value.values) refreshUnread(buffer.ref)
                synchronized(persistenceLock) { lastMaintenanceAtMs = now }
                val restored = synchronized(selectionLock) {
                    if (selectionRevision != initialSelectionRevision) selectedStorageKey?.let { _buffers.value[it]?.ref }
                    else selected?.takeIf { networkStore.byId(it.networkId) != null }?.let {
                        buffer(it)
                        selectedStorageKey = it.storageKey
                        _restoredSelection.value = it.storageKey
                        it
                    }
                }
                if (restored != null) {
                    loadCachedRoster(restored)
                    persistOpenedShell(restored)
                    enqueuePersistence {
                        val current = _buffers.value[restored.storageKey]?.ref
                        if (current != null && selectedStorageKey == restored.storageKey && networkStore.byId(current.networkId) != null) {
                            offlineStore?.select(current, clock())
                        }
                    }
                    history.open(restored.storageKey)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _operationError.value = "Saved conversation state could not be loaded: ${error.message ?: "storage unavailable"}"
            }
            enqueuePersistence {
                for (config in networkStore.all().sortedBy { it.bouncerBinding != null }) connect(config)
                for (config in networkStore.all()) {
                    val retained = startupShellKeys[config.id].orEmpty().mapNotNull { _buffers.value[it]?.ref }
                    retainConversationShells(config.id, retained, emptyList(), startupSelection)
                }
                _restorationReady.value = true
                for (session in sessions.values) {
                    if (session.recovery.desiredConnection) launchSession(session)
                }
                scheduleMaintenance()
                if (maintenanceJob == null) maintenanceJob = scope.launch {
                    while (true) {
                        delay(MAINTENANCE_INTERVAL_MS)
                        scheduleMaintenance()
                    }
                }
            }
        }
    }

    private fun retentionPriorities(networkId: String, lastSelected: ConversationRef?): Set<String> = linkedSetOf<String>().apply {
        synchronized(selectionLock) {
            selectedStorageKey?.takeIf { it.startsWith("$networkId|") }?.let { add(it.substringAfter('|')) }
        }
        if (lastSelected?.networkId == networkId) add(lastSelected.normalizedTarget)
        add(ConversationRef.SERVER_TARGET)
    }

    private fun retainConversationShells(
        networkId: String,
        known: List<ConversationRef>,
        cached: List<OfflineConversationShell>,
        lastSelected: ConversationRef?,
        restoreExisting: Boolean = false,
    ): Set<String> = synchronized(selectionLock) {
        if (networkStore.byId(networkId) == null) return@synchronized emptySet()
        if (pendingStorageMigrations.containsKey(networkId)) return@synchronized _buffers.value.values
            .filter { it.ref.networkId == networkId }.map { it.key }.toSet()
        val refs = LinkedHashMap<String, ConversationRef>()
        val candidates = known + cached.map { it.ref }
        val selected = selectedStorageKey?.let { key -> _buffers.value[key]?.ref ?: candidates.firstOrNull { it.storageKey == key } }
        fun retain(ref: ConversationRef?) {
            if (ref != null && ref.networkId == networkId &&
                (!channelOrder.isParted(ref.storageKey) || ref.storageKey in _buffers.value ||
                    ref.storageKey == lastSelected?.storageKey) &&
                refs.size < MESSAGE_CONVERSATION_LIMIT) refs.putIfAbsent(ref.storageKey, ref)
        }
        retain(selected)
        retain(pendingRenamedKey?.let { _buffers.value[it]?.ref })
        retain(lastSelected)
        retain(ConversationRef.server(networkId))
        for (ref in known) retain(ref)
        for (shell in cached) retain(shell.ref)
        for (current in _buffers.value.values.filter { it.ref.networkId == networkId }
            .sortedByDescending { it.messages.lastOrNull()?.timestampMs ?: it.cachedStateAtMs ?: Long.MIN_VALUE }) retain(current.ref)
        val shells = cached.associateBy { it.ref.storageKey }
        for (ref in refs.values) {
            val absent = ref.storageKey !in _buffers.value
            buffer(ref)
            if (absent || restoreExisting) shells[ref.storageKey]?.let(::restoreShell)
        }
        val droppedBuffers = _buffers.value.values.filter { it.ref.networkId == networkId && it.key !in refs }
        val dropped = droppedBuffers.map { it.key }
        if (dropped.isNotEmpty()) {
            val droppedKeys = dropped.toSet()
            _buffers.update { buffers -> buffers - droppedKeys }
            renamedConversations.entries.removeAll { it.key in droppedKeys || it.value.storageKey in droppedKeys }
        }
        for (key in dropped) {
            history.remove(key)
            loadedRosters.remove(key)
            topicObservedAt.remove(key)
            rosterObservedAt.remove(key)
            modesObservedAt.remove(key)
            metadataRequests.remove(key)
            reactionRevisions.keys.removeAll { it.startsWith("$key|") }
        }
        for (droppedBuffer in droppedBuffers) {
            for (message in droppedBuffer.messages) persistedRowsByLocalId.remove("$networkId|${message.localId}")
        }
        refs.keys.toSet()
    }

    private fun restoreShell(shell: OfflineConversationShell) {
        restoredCasemapping[shell.ref.networkId] = shell.caseMapping
        shell.topicObservedAtMs?.let { topicObservedAt[shell.ref.storageKey] = it }
        shell.modesObservedAtMs?.let { modesObservedAt[shell.ref.storageKey] = it }
        updateBuffer(shell.ref) { current ->
            current.copy(
                ref = shell.ref,
                displayName = shell.displayName,
                topic = shell.topic,
                channelModes = shell.modes,
                cachedTopic = shell.topicObservedAtMs != null,
                cachedModes = shell.modesObservedAtMs != null,
                cachedStateAtMs = shell.observedAtMs,
                joinState = if (shell.ref.kind == ConversationKind.CHANNEL) JoinState.IDLE else current.joinState,
            )
        }
    }

    private suspend fun loadCachedShell(ref: ConversationRef, shellSnapshot: OfflineConversationShell? = null) {
        if (pendingStorageMigrations.containsKey(ref.networkId)) return
        val session = sessions[ref.networkId]
        val shell = shellSnapshot ?: offlineStore?.shell(ref) ?: return
        if (networkStore.byId(ref.networkId) == null || sessions[ref.networkId] !== session || pendingStorageMigrations.containsKey(ref.networkId)) return
        updateBufferKey(ref.storageKey) { current ->
            if (pendingStorageMigrations.containsKey(ref.networkId) || sessions[ref.networkId] !== session) return@updateBufferKey current
            val topicAvailable = current.cachedTopic || !topicObservedAt.containsKey(ref.storageKey)
            val modesAvailable = current.cachedModes || !modesObservedAt.containsKey(ref.storageKey)
            if (topicAvailable) shell.topicObservedAtMs?.let { topicObservedAt[ref.storageKey] = it }
            if (modesAvailable) shell.modesObservedAtMs?.let { modesObservedAt[ref.storageKey] = it }
            current.copy(
                topic = if (topicAvailable) shell.topic else current.topic,
                cachedTopic = if (topicAvailable) shell.topicObservedAtMs != null else current.cachedTopic,
                channelModes = if (modesAvailable) shell.modes else current.channelModes,
                cachedModes = if (modesAvailable) shell.modesObservedAtMs != null else current.cachedModes,
                cachedStateAtMs = maxOf(current.cachedStateAtMs ?: Long.MIN_VALUE, shell.observedAtMs),
            )
        }
    }

    private suspend fun loadCachedRoster(ref: ConversationRef) {
        if (pendingStorageMigrations.containsKey(ref.networkId) || ref.kind != ConversationKind.CHANNEL) return
        val store = offlineStore ?: return
        val session = sessions[ref.networkId]
        val epoch = session?.state?.connectionEpoch
        if (ref.storageKey in loadedRosters) return
        val registered = session?.state?.registered
        val roster = store.roster(ref) ?: return
        var applied = false
        withNetworkState(ref.networkId) {
            if (networkStore.byId(ref.networkId) == null || sessions[ref.networkId] !== session ||
                session?.state?.connectionEpoch != epoch || session?.state?.registered != registered ||
                pendingStorageMigrations.containsKey(ref.networkId)
            ) return@withNetworkState
            updateBufferKey(ref.storageKey) { current ->
                if (current.ref != ref || rosterObservedAt.containsKey(ref.storageKey) && !current.cachedRoster) current else {
                    rosterObservedAt[ref.storageKey] = roster.observedAtMs
                    current.copy(
                        members = roster.members,
                        memberPresence = roster.presence.mapValues { (_, value) -> value.toPresenceState() },
                        cachedRoster = true,
                        rosterTruncated = roster.truncated,
                        cachedStateAtMs = maxOf(current.cachedStateAtMs ?: 0L, roster.observedAtMs),
                    )
                }
            }
            if (ref.storageKey in _buffers.value) {
                loadedRosters.add(ref.storageKey)
                applied = true
            }
        }
        if (applied) loadCachedUsers(ref, roster.members.map { it.nick })
    }

    private suspend fun loadCachedUsers(ref: ConversationRef, nicks: List<String>) {
        val store = offlineStore ?: return
        if (pendingStorageMigrations.containsKey(ref.networkId)) return
        val session = sessions[ref.networkId]
        val epoch = session?.state?.connectionEpoch
        val registered = session?.state?.registered
        val mapping = session?.state?.casemapping ?: restoredCasemapping[ref.networkId] ?: CaseMapping.RFC1459
        for (nick in nicks.distinctBy(mapping::fold)) {
            val loadedKey = "${ref.networkId}|${mapping.fold(nick)}"
            if (nick.isBlank() || loadedKey in loadedUsers) continue
            val cached = store.user(ref.networkId, nick, mapping) ?: continue
            withNetworkState(ref.networkId) {
                if (networkStore.byId(ref.networkId) == null || sessions[ref.networkId] !== session ||
                    session?.state?.connectionEpoch != epoch || session?.state?.registered != registered ||
                    session != null && session.state.casemapping != mapping ||
                    pendingStorageMigrations.containsKey(ref.networkId)
                ) return@withNetworkState
                val cachedProfile = UserProfile.from(cached.metadata)?.copy(cached = true, observedAtMs = cached.observedAtMs)
                val live = session?.takeIf { it.state.registered }?.state?.users?.get(mapping.fold(nick))
                val profile = if (live?.metadataObservedAtMs != null) mergeObservedProfile(cachedProfile, live) { value ->
                    session?.let { redactPresentation(it, value) } ?: value
                } else cachedProfile
                if (profile != null) _profiles.update { profiles ->
                    val current = profiles[ref.networkId] ?: NetworkProfiles(mapping, emptyMap())
                    val key = mapping.fold(cached.nick)
                    profiles + (ref.networkId to current.copy(byFoldedNick = current.byFoldedNick + (key to profile)))
                }
                loadedUsers.add(loadedKey)
            }
        }
    }

    private suspend fun refreshUnread(ref: ConversationRef) {
        if (networkStore.byId(ref.networkId) == null) return
        if (pendingStorageMigrations.containsKey(ref.networkId)) return
        var cursor = readMarkers.cursor(ref.storageKey)
        var counts = messageStore.unreadCounts(ref, cursor)
        if (cursor != readMarkers.cursor(ref.storageKey)) {
            cursor = readMarkers.cursor(ref.storageKey)
            counts = messageStore.unreadCounts(ref, cursor)
        }
        if (networkStore.byId(ref.networkId) == null) return
        updateBufferKey(ref.storageKey) { current ->
            if (pendingStorageMigrations.containsKey(ref.networkId) || readMarkers.cursor(ref.storageKey) != cursor) return@updateBufferKey current
            val pending = current.messages.filter {
                it.storedRowId == null && it.countsAsUnread && it.timestampMs > cursor.timestampMs
            }
            val unread = counts.unreadCount + pending.size
            val mentions = counts.mentionCount + pending.count { it.highlightsMe }
            val muted = mutes.isMuted(ref.storageKey)
            val boundary = current.messages.asSequence().filter {
                it.countsAsUnread && (it.timestampMs > cursor.timestampMs ||
                    it.timestampMs == cursor.timestampMs && (it.storedRowId ?: Long.MAX_VALUE) > cursor.rowId)
            }.minOfOrNull { it.timestampMs }
            current.copy(
                unreadCount = unread,
                mentionCount = mentions,
                mentionCountKnown = counts.mentionCountKnown,
                hasUnread = unread > 0 && !muted,
                readAtMs = cursor.timestampMs,
                unreadFromTimestampMs = boundary.takeUnless { muted || unread == 0 },
            )
        }
    }

    private suspend fun refreshVisibleDurableState(ref: ConversationRef, onlyMsgids: Set<String>? = null) {
        if (pendingStorageMigrations.containsKey(ref.networkId)) return
        val current = _buffers.value[ref.storageKey] ?: return
        val session = sessions[ref.networkId]
        val inspected = current.messages.asSequence().filter { onlyMsgids == null || it.msgid in onlyMsgids }
            .mapNotNull { it.storedRowId }.toMutableSet()
        if (onlyMsgids == null) current.replyDraft?.storedRowId?.let(inspected::add)
        if (inspected.isEmpty()) {
            if (onlyMsgids == null && current.reactions.isNotEmpty()) {
                updateBufferKey(ref.storageKey) { buffer ->
                    buffer.copy(reactions = buffer.reactions.filterKeys { reactionRevisions["${ref.storageKey}|$it"] != null })
                }
            }
            return
        }
        val rows = messageStore.byRowIds(ref, inspected)
        val authoritative = rows.associateBy { it.rowId }
        val parents = messageStore.replyParents(ref, rows)
        val msgids = rows.mapNotNull { it.msgid }.toSet() + current.messages.filter { it.storedRowId in inspected }.mapNotNull { it.msgid } +
            if (onlyMsgids == null) current.reactions.keys else onlyMsgids
        val restoredReactions = messageStore.reactions(ref, msgids)
        if (networkStore.byId(ref.networkId) == null || sessions[ref.networkId] !== session || pendingStorageMigrations.containsKey(ref.networkId)) return
        updateBufferKey(ref.storageKey) { buffer ->
            if (pendingStorageMigrations.containsKey(ref.networkId) || sessions[ref.networkId] !== session) return@updateBufferKey buffer
            fun reconcile(message: ChatMessage): ChatMessage? {
                val rowId = message.storedRowId
                val stored = authoritative[rowId]
                if (rowId in inspected && stored == null) return null
                if (stored == null) return message
                if (stored.pendingEcho && !message.pendingEcho) return message.copy(storedRowId = stored.rowId)
                return mergeChatMessageMetadata(message, stored.toChatMessage(message.localId), incomingCanonical = true).copy(
                    replyToMsgid = stored.replyToMsgid,
                    localReplyParentRowId = stored.replyParentRowId,
                    pendingEcho = stored.pendingEcho,
                    echoLabel = message.echoLabel.takeIf { stored.pendingEcho },
                    replyPreview = message.replyPreview.takeIf { rowId in parents },
                )
            }
            val refreshed = buffer.messages.mapNotNull(::reconcile)
            val ordered = if (refreshed.indices.any { it > 0 && refreshed[it - 1].timestampMs > refreshed[it].timestampMs }) {
                refreshed.sortedBy { it.timestampMs }
            } else refreshed
            val hydrated = hydrateReplyPreviews(ordered, parents)
            var reactions = buffer.reactions
            for (msgid in msgids) {
                if (reactionRevisions["${ref.storageKey}|$msgid"] != null) continue
                val memberships = restoredReactions[msgid]
                reactions = if (memberships.isNullOrEmpty()) reactions - msgid else reactions + (msgid to memberships)
            }
            val redacted = hydrated.asSequence().filter { it.redacted }.mapNotNull { it.msgid }.toSet()
            val draft = buffer.replyDraft?.let(::reconcile)?.takeUnless { it.redacted }
            buffer.copy(messages = hydrated, reactions = reactions - redacted, replyDraft = draft)
        }
    }

    private fun persistSnapshot(session: Session, ref: ConversationRef, saveRoster: Boolean = false) {
        val store = offlineStore ?: return
        val current = _buffers.value[ref.storageKey] ?: return
        val now = clock()
        val mapping = session.state.casemapping
        val topicTime = topicObservedAt[ref.storageKey]
        val modesTime = modesObservedAt[ref.storageKey]
        val rosterTime = rosterObservedAt[ref.storageKey]
        enqueueCoalescedPersistence("snapshot:${session.historyGeneration}|${ref.storageKey}") {
            if (networkStore.byId(ref.networkId) == null) return@enqueueCoalescedPersistence
            val shell = OfflineConversationShell(ref, current.displayName, now, mapping,
                current.topic, topicTime, current.channelModes, modesTime)
            val roster = if ((saveRoster || rosterTime != null) && !current.cachedRoster) {
                OfflineRoster(current.members, current.memberPresence.mapValues { (_, value) ->
                    value.toCachedPresence { redactPresentation(session, it) }
                }, rosterTime ?: now, completeAtObservation = true)
            } else null
            store.saveConversation(shell, roster)
        }
    }

    private fun persistOpenedShell(ref: ConversationRef) {
        val current = _buffers.value[ref.storageKey] ?: return
        val mapping = sessions[ref.networkId]?.state?.casemapping ?: restoredCasemapping[ref.networkId] ?: CaseMapping.RFC1459
        val shell = OfflineConversationShell(current.ref, current.displayName, clock(), mapping)
        enqueueCoalescedPersistence("shell:${ref.storageKey}") {
            if (networkStore.byId(ref.networkId) != null) offlineStore?.saveConversation(shell)
        }
    }

    private fun markNetworkCached(networkId: String) {
        sessions[networkId]?.publishedProfileKeys = emptySet()
        loadedRosters.removeAll { it.substringBefore("|") == networkId }
        loadedUsers.removeAll { it.substringBefore("|") == networkId }
        updateAllBuffersForNetwork(networkId) { current ->
            current.copy(
                cachedTopic = current.cachedTopic || topicObservedAt.containsKey(current.key),
                cachedRoster = current.cachedRoster || rosterObservedAt.containsKey(current.key),
                cachedModes = current.cachedModes || modesObservedAt.containsKey(current.key),
                typingUsers = emptyMap(),
            )
        }
        _profiles.update { profiles ->
            val current = profiles[networkId] ?: return@update profiles
            profiles + (networkId to current.copy(byFoldedNick = current.byFoldedNick.mapValues { (_, profile) ->
                profile.copy(cached = true)
            }))
        }
        _whoisPresentation.update { current ->
            current?.takeIf { it.networkId == networkId }?.copy(cached = true, refreshing = false, offline = true) ?: current
        }
        metadataRequests.keys.removeAll { it.substringBefore("|") == networkId }
    }

    private fun replayPendingReadMarkers(session: Session) {
        synchronized(session.state) {
            if (sessions[session.config.id] !== session || !session.state.registered ||
                session.statusFlow.value != ConnectionStatus.REGISTERED ||
                "draft/read-marker" !in session.state.supportedCaps || !networkAvailable ||
                !session.recovery.desiredConnection || session.recovery.phase != RecoveryPhase.REGISTERED ||
                pendingStorageMigrations.containsKey(session.config.id)
            ) return
            for ((key, cursor) in readMarkers.pending(session.config.id)) {
                if (session.replayedReadCursors[key] == cursor) continue
                val target = session.bufferTargetFor(key) ?: continue
                if (target == ConversationRef.SERVER_TARGET) continue
                sendRaw(session, "MARKREAD $target timestamp=${Instant.ofEpochMilli(cursor.timestampMs)}")
                session.replayedReadCursors[key] = cursor
            }
        }
    }

    private fun scheduleMaintenance() {
        synchronized(persistenceLock) {
            val now = clock()
            val previous = lastMaintenanceAtMs
            if (previous != null && now >= previous && now - previous < MAINTENANCE_INTERVAL_MS) return
            lastMaintenanceAtMs = now
            enqueuePersistence {
                val lastSelected = offlineStore?.selection()
                offlineStore?.maintain(now)
                for (config in networkStore.all()) {
                    if (networkStore.byId(config.id) == null) continue
                    val priorities = synchronized(selectionLock) {
                        if (pendingStorageMigrations.containsKey(config.id)) null else retentionPriorities(config.id, lastSelected)
                    }
                    if (priorities == null) {
                        deferredMaintenanceNetworks.add(config.id)
                        continue
                    }
                    messageStore.maintainNetwork(config.id, MESSAGE_NETWORK_LIMIT, MESSAGE_CONVERSATION_LIMIT,
                        nowMs = now, protectedConversations = priorities)
                    val known = messageStore.knownConversations(config.id)
                    val cached = offlineStore?.shells(config.id).orEmpty()
                    if (pendingStorageMigrations.containsKey(config.id)) {
                        deferredMaintenanceNetworks.add(config.id)
                        continue
                    }
                    val retained = retainConversationShells(config.id, known, cached, lastSelected)
                    readMarkers.retainNetwork(config.id, retained + known.map { it.storageKey })
                    for (ref in _buffers.value.values.filter { it.ref.networkId == config.id }.map { it.ref }) {
                        refreshVisibleDurableState(ref)
                        refreshUnread(ref)
                    }
                }
                messageStore.maintain(now)
            }
        }
    }

    private fun deferStorageRead(networkId: String, operation: suspend () -> Unit): Boolean = synchronized(selectionLock) {
        if (!pendingStorageMigrations.containsKey(networkId)) return@synchronized false
        deferredStorageReads.getOrPut(networkId) { mutableListOf() }.add(operation)
        true
    }

    private fun clearReactionRevision(ref: ConversationRef, msgid: String, revision: Long) {
        reactionRevisions.remove("${ref.storageKey}|$msgid", revision)
        val current = currentRef(ref)
        if (current.storageKey != ref.storageKey) reactionRevisions.remove("${current.storageKey}|$msgid", revision)
        renamedConversations[ref.storageKey]?.let { renamed ->
            val migrated = currentRef(renamed)
            if (migrated.storageKey != current.storageKey) reactionRevisions.remove("${migrated.storageKey}|$msgid", revision)
        }
    }

    private fun durableReactions(
        key: String,
        visible: Map<String, Map<String, Set<String>>>,
        stored: Map<String, Map<String, Set<String>>>,
        redacted: Set<String>,
    ): Map<String, Map<String, Set<String>>> {
        var merged = stored
        for (msgid in stored.keys + visible.keys) {
            if (reactionRevisions["$key|$msgid"] != null) {
                val optimistic = visible[msgid]
                merged = if (optimistic == null) merged - msgid else merged + (msgid to optimistic)
            }
        }
        return merged - redacted
    }

    private suspend fun mergeDurableContext(
        ref: ConversationRef,
        messages: List<StoredMessage>,
        prependEqualTimestamp: Boolean = false,
    ) {
        if (deferStorageRead(ref.networkId) { mergeDurableContext(ref, messages, prependEqualTimestamp) }) return
        val target = currentRef(ref)
        if (networkStore.byId(target.networkId) == null) return
        val session = sessions[target.networkId]
        val epoch = session?.state?.connectionEpoch
        val context = if (messages.any { it.conversation.storageKey != target.storageKey }) {
            messageStore.byRowIds(target, messages.map { it.rowId }.toSet())
        } else messages
        val parents = messageStore.replyParents(target, context)
        val reactions = messageStore.reactions(target)
        if (deferStorageRead(ref.networkId) { mergeDurableContext(ref, messages, prependEqualTimestamp) }) return
        var reload = false
        withNetworkState(target.networkId) {
            if (networkStore.byId(target.networkId) == null) return@withNetworkState
            if (sessions[target.networkId] !== session || session?.state?.connectionEpoch != epoch || currentRef(ref) != target) {
                reload = true
                return@withNetworkState
            }
            updateBufferKey(target.storageKey) { current ->
                val merged = mergeSearchContext(current.messages, context, prependEqualTimestamp) { idGenerator.getAndIncrement() }
                val hydrated = hydrateReplyPreviews(merged, parents)
                val redacted = hydrated.asSequence().filter { it.redacted }.mapNotNull { it.msgid }.toSet()
                current.copy(messages = hydrated, reactions = durableReactions(current.key, current.reactions, reactions, redacted))
            }
        }
        if (networkStore.byId(target.networkId) == null) return
        if (reload) {
            enqueuePersistence {
                val current = currentRef(ref)
                mergeDurableContext(ref, messageStore.byRowIds(current, messages.map { it.rowId }.toSet()), prependEqualTimestamp)
            }
            return
        }
        refreshUnread(target)
        loadCachedUsers(target, context.map { it.senderNick })
    }

    private suspend fun refreshAfterWrite(ref: ConversationRef) {
        if (deferStorageRead(ref.networkId) { refreshAfterWrite(ref) }) return
        val target = currentRef(ref)
        refreshVisibleDurableState(target)
        refreshUnread(target)
        if (deferStorageRead(ref.networkId) { refreshAfterWrite(ref) }) return
        persistOpenedShell(currentRef(ref))
    }

    private fun beginStorageMigration(networkId: String) {
        pendingStorageMigrations.merge(networkId, 1, Int::plus)
        synchronized(persistenceLock) { persistenceBatch += 1 }
    }

    private suspend fun finishStorageMigration(networkId: String, completed: Boolean) {
        val deferred = synchronized(selectionLock) {
            if (pendingStorageMigrations.computeIfPresent(networkId) { _, count -> (count - 1).takeIf { it > 0 } } != null) return
            deferredStorageReads.remove(networkId).orEmpty()
        }
        if (!completed || networkStore.byId(networkId) == null) return
        for (operation in deferred) enqueuePersistence(operation)
        val shells = offlineStore?.shells(networkId).orEmpty().associateBy { it.ref.storageKey }
        for (buffer in _buffers.value.values.filter { it.ref.networkId == networkId }) {
            shells[buffer.key]?.let { loadCachedShell(buffer.ref, it) }
            if (buffer.cachedRoster) {
                loadedRosters.remove(buffer.key)
                loadCachedRoster(buffer.ref)
            }
            refreshVisibleDurableState(buffer.ref)
            loadCachedUsers(buffer.ref, _buffers.value[buffer.key]?.messages.orEmpty().map { it.sender })
            refreshUnread(buffer.ref)
        }
        sessions[networkId]?.let(::restoreKnownConversations)
        sessions[networkId]?.let(::replayPendingReadMarkers)
        if (deferredMaintenanceNetworks.remove(networkId)) {
            synchronized(persistenceLock) { lastMaintenanceAtMs = null }
            scheduleMaintenance()
        }
    }

    private suspend fun refreshReactionRetention(networkId: String, affectedRows: Set<Long>, requestedRef: ConversationRef, requestedMsgids: Set<String>) {
        if (pendingStorageMigrations.containsKey(networkId)) return
        for (buffer in _buffers.value.values.filter { it.ref.networkId == networkId }) {
            val msgids = buffer.messages.asSequence().filter { it.storedRowId in affectedRows }.mapNotNull { it.msgid }.toMutableSet()
            if (buffer.key == requestedRef.storageKey) msgids.addAll(requestedMsgids)
            if (msgids.isNotEmpty()) refreshVisibleDurableState(buffer.ref, msgids)
        }
    }

    private fun requestWhois(session: Session, origin: ConversationRef, nick: String) {
        val explicitRefresh = requestedWhois?.let { it.first == session.config.id && session.state.casemapping.equal(it.second, nick) } == true
        val token = synchronized(selectionLock) {
            whoisRequestToken += 1
            requestedWhois = session.config.id to nick
            requestedWhoisOrigin = selectedStorageKey
            whoisRequestToken
        }
        val existing = _whoisPresentation.value?.takeIf {
            explicitRefresh && it.networkId == session.config.id && session.state.casemapping.equal(it.info.nick, nick)
        }
        if (existing == null) {
            _whois.value = null
            _whoisPresentation.value = null
        } else {
            _whoisPresentation.value = existing.copy(refreshing = true)
        }
        enqueuePersistence {
            loadCachedUsers(origin, listOf(nick))
            val cached = offlineStore?.whois(session.config.id, nick, session.state.casemapping)
            synchronized(session.state) {
                if (!whoisRequestCurrent(session, nick, token)) return@enqueuePersistence
                if (_whoisPresentation.value?.cached == false) {
                    if (explicitRefresh) sendWhoisRefresh(session, origin, nick)
                    return@enqueuePersistence
                }
                val online = session.state.registered && session.statusFlow.value == ConnectionStatus.REGISTERED &&
                    session.recovery.phase == RecoveryPhase.REGISTERED && networkAvailable
                val refresh = online && (explicitRefresh || cached?.isFresh(clock()) != true)
                if (cached != null) {
                    _whois.value = cached.info
                    _whoisPresentation.value = WhoisPresentation(session.config.id, cached.info, cached.observedAtMs,
                        cached = true, refreshing = refresh, offline = !online)
                } else if (!online) {
                    _operationError.value = "No saved WHOIS information is available for $nick on this network."
                }
                if (refresh) sendWhoisRefresh(session, origin, nick)
            }
        }
    }

    private fun whoisRequestCurrent(session: Session, nick: String, token: Long = whoisRequestToken): Boolean =
        synchronized(selectionLock) {
            sessions[session.config.id] === session && networkStore.byId(session.config.id) != null &&
                whoisRequestToken == token && requestedWhois?.first == session.config.id &&
                requestedWhois?.second?.let { session.state.casemapping.equal(it, nick) } == true &&
                requestedWhoisOrigin == selectedStorageKey
        }

    private fun sendWhoisRefresh(session: Session, origin: ConversationRef, nick: String) {
        if (!whoisRequestCurrent(session, nick) || !session.state.registered ||
            session.statusFlow.value != ConnectionStatus.REGISTERED || !networkAvailable ||
            !session.recovery.desiredConnection || session.recovery.phase != RecoveryPhase.REGISTERED) return
        val token = whoisRequestToken
        if (session.whoisRefreshToken == token && session.whoisRefreshEpoch == session.state.connectionEpoch) return
        session.whoisRefreshToken = token
        session.whoisRefreshEpoch = session.state.connectionEpoch
        session.state.whoisExpected = true
        session.state.whoisTarget = nick
        session.state.whois.reset()
        _whoisPresentation.update { it?.copy(refreshing = true, offline = false) }
        sendLabeled(session, origin, LabeledCommand.WHOIS, "WHOIS $nick $nick")
    }

    private fun refreshRequestedWhois(session: Session) {
        val requested = requestedWhois ?: return
        if (requested.first != session.config.id || !whoisRequestCurrent(session, requested.second)) return
        val presentation = _whoisPresentation.value
        if (presentation != null && !presentation.offline && presentation.refreshing) return
        val origin = requestedWhoisOrigin?.let { _buffers.value[it]?.ref } ?: session.state.server
        sendWhoisRefresh(session, origin, requested.second)
    }

    private fun completeWhois(session: Session, info: dev.brentdevs.yardhal.core.data.WhoisInfo) {
        val now = clock()
        val mapping = session.state.casemapping
        val safeInfo = info.copy(
            user = info.user?.let { redactPresentation(session, it) },
            host = info.host?.let { redactPresentation(session, it) },
            realName = info.realName?.let { redactPresentation(session, it) },
            server = info.server?.let { redactPresentation(session, it) },
            serverInfo = info.serverInfo?.let { redactPresentation(session, it) },
            account = info.account?.let { redactPresentation(session, it) },
            awayMessage = info.awayMessage?.let { redactPresentation(session, it) },
        )
        enqueuePersistence {
            if (sessions[session.config.id] !== session || networkStore.byId(session.config.id) == null) return@enqueuePersistence
            offlineStore?.saveWhois(session.config.id, safeInfo, now, mapping)
        }
        if (!whoisRequestCurrent(session, info.nick)) return
        _whois.value = safeInfo
        _whoisPresentation.value = WhoisPresentation(session.config.id, safeInfo, now,
            cached = false, refreshing = false, offline = false)
    }

    public fun connect(config: NetworkConfig) {
        synchronized(sessionLifecycleLock) {
            if (sessions.containsKey(config.id)) return
            val saved = networkStore.byId(config.id)
            startSession(config.copy(
                autoConnect = saved?.autoConnect ?: config.autoConnect,
                userDisconnected = saved?.userDisconnected ?: config.userDisconnected,
            ))
        }
    }

    public fun connectNetwork(networkId: String) {
        synchronized(sessionLifecycleLock) {
            val saved = networkStore.byId(networkId) ?: return
            val binding = saved.bouncerBinding
            if (binding != null) {
                if (!binding.enabled) {
                    showConnectionError(networkId, "This upstream is disabled on the bouncer. Enable it in bouncer management before connecting.")
                    return
                }
                val parent = networkStore.byId(binding.parentId) ?: return
                if (sessions[parent.id]?.recovery?.desiredConnection != true || parent.userDisconnected) connectNetwork(parent.id)
                if (sessions[parent.id]?.recovery?.desiredConnection != true) return
            }
            val config = saved.copy(userDisconnected = false, bouncerBinding = binding?.copy(rejectionReason = null))
            if (config != saved && !networkStore.update(config)) {
                showConnectionError(networkId, "Could not save Connect intent. Check device storage and retry; the saved disconnect preference is unchanged.")
                return
            }
            restartSession(config, manuallyWanted = true)
            if (binding == null) resumeDependents(networkId)
        }
    }

    public fun dependentNetworks(parentId: String): List<NetworkConfig> = networkStore.dependents(parentId)

    private fun restartSession(config: NetworkConfig, manuallyWanted: Boolean, allowStartup: Boolean = false) {
        val previous = sessions[config.id]
        val mapping = previous?.state?.casemapping ?: restoredCasemapping[config.id] ?: CaseMapping.RFC1459
        if (previous != null) synchronized(previous.state) { stopSession(previous, "Connecting with current network settings") }
        startSession(config, mapping, manuallyWanted, allowStartup)
    }

    private fun upstreamEligible(config: NetworkConfig): Boolean {
        val binding = config.bouncerBinding ?: return true
        if (!binding.enabled || binding.rejectionReason != null) return false
        val parent = networkStore.byId(binding.parentId) ?: return false
        return !parent.userDisconnected && sessions[parent.id]?.recovery?.desiredConnection == true
    }

    private fun resumeDependents(parentId: String) {
        for (config in networkStore.dependents(parentId)) {
            val current = sessions[config.id]
            if (current == null) startSession(config)
            else if (upstreamEligible(current.config) && current.recovery.desiredConnection) launchSession(current)
        }
    }

    private fun managementMode(session: Session): NetworkMode =
        if (session.config.mode == NetworkMode.DIRECT &&
            dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.CAPABILITY in session.state.supportedCaps
        ) NetworkMode.SOJU else session.config.mode

    private fun isBouncerOwnedSession(session: Session): Boolean =
        session.config.bouncerBinding != null || session.config.mode == NetworkMode.ZNC ||
            dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.CAPABILITY in session.state.supportedCaps ||
            session.state.supportedCaps.any { it.startsWith("znc.in/") }

    private fun isManagementChat(session: Session, message: IrcMessage): Boolean {
        if (managementMode(session) == NetworkMode.DIRECT ||
            (message.command != "PRIVMSG" && message.command != "NOTICE" && message.command != "TAGMSG")
        ) return false
        val sender = message.prefix?.nick.orEmpty()
        val target = message.parameters.firstOrNull().orEmpty()
        return sender.equals("BouncerServ", true) || target.equals("BouncerServ", true) ||
            sender.equals("*status", true) || target.equals("*status", true) ||
            sender.equals("*controlpanel", true) || target.equals("*controlpanel", true)
    }

    private fun restoredSojuAttributes(parentId: String): Map<String, dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.Attributes> =
        networkStore.dependents(parentId).mapNotNull { config ->
            config.bouncerBinding?.let { binding ->
                binding.netId to dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.Attributes(
                    name = config.name, nickname = binding.nickname, realname = binding.realName,
                )
            }
        }.toMap()

    private fun reconcileSojuDiscovery(session: Session, message: IrcMessage) {
        for (change in session.sojuDiscovery.receive(session.managementGeneration, message)) {
            when (change) {
                is SojuDiscoveryChange.Upsert -> reconcileSojuUpstream(session.config, change.netId, change.attributes)
                is SojuDiscoveryChange.Delete -> networkStore.dependents(session.config.id)
                    .firstOrNull { it.bouncerBinding?.netId == change.netId }?.let { config ->
                        if (!removeNetwork(config.id)) stopDeletedUpstream(config)
                    }
                is SojuDiscoveryChange.Snapshot -> {
                    val absent = networkStore.dependents(session.config.id).filter { it.bouncerBinding?.netId !in change.netIds }
                    if (absent.isNotEmpty()) {
                        if (networkStore.removeAll(absent.map { it.id }.toSet())) {
                            for (config in absent) removePersistedNetwork(config)
                            refreshNetworkStates()
                        } else {
                            for (config in absent) stopDeletedUpstream(config)
                            _operationError.value = "The bouncer's removed upstreams are stopped, but their saved rows could not be deleted. Existing conversations were retained."
                        }
                    }
                }
            }
        }
    }

    private fun reconcileSojuUpstream(
        parent: NetworkConfig,
        netId: String,
        attributes: dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.Attributes,
    ) {
        val saved = networkStore.dependents(parent.id).firstOrNull { it.bouncerBinding?.netId == netId }
        val previous = saved?.let { sessions[it.id]?.config ?: it }
        val updated = SojuUpstreamConfig.reconcile(parent, netId, attributes, previous)
        if (previous == null && networkStore.byId(updated.id) != null) {
            _operationError.value = "A saved network conflicts with this upstream's stable identity. Existing conversations were retained."
            return
        }
        if (!networkStore.upsertAll(listOf(updated))) {
            _operationError.value = "The discovered upstream could not be saved. Check device storage and refresh bouncer management."
            return
        }
        if (previous == null || sessions[updated.id] == null) {
            startSession(
                updated,
                manuallyWanted = previous == null && sessions[parent.id]?.recovery?.desiredConnection == true && !updated.userDisconnected,
            )
        } else applyUpdatedConfig(previous, updated, credentialsChanged = false)
        val child = sessions[updated.id]
        if (child != null && attributes.error != null && child.config.bouncerBinding?.rejectionReason == null) {
            child.connectionError = attributes.error?.takeIf(String::isNotEmpty)?.let {
                "Upstream reported: ${redactPresentation(child, it)}"
            }
        }
        refreshNetworkStates()
    }

    public fun setDiscoveredUpstreamEnabled(parentId: String, netId: String, enabled: Boolean): Boolean =
        synchronized(sessionLifecycleLock) {
            val previous = networkStore.dependents(parentId).firstOrNull { it.bouncerBinding?.netId == netId }
                ?: return@synchronized false
            val binding = previous.bouncerBinding ?: return@synchronized false
            val runtimeBinding = sessions[previous.id]?.config?.bouncerBinding
            if (binding.enabled == enabled && (runtimeBinding == null || runtimeBinding.enabled == enabled)) return@synchronized true
            val updated = previous.copy(bouncerBinding = binding.copy(enabled = enabled, rejectionReason = binding.rejectionReason.takeUnless { enabled }))
            val persisted = updated == previous || networkStore.update(updated)
            if (!persisted) {
                _operationError.value = "The upstream's enabled state was applied for this session, but could not be saved. Check device storage and refresh bouncer management."
            }
            applyUpdatedConfig(previous, updated, credentialsChanged = false)
            if (sessions[updated.id] == null) startSession(updated)
            refreshNetworkStates()
            persisted
        }

    private fun persistUpstreamRejection(session: Session, reason: String) {
        val saved = networkStore.byId(session.config.id) ?: return
        val binding = saved.bouncerBinding ?: return
        val updated = saved.copy(bouncerBinding = binding.copy(rejectionReason = redactPresentation(session, reason)))
        session.config = updated
        if (!networkStore.update(updated)) {
            _operationError.value = "The rejected upstream is stopped for this session, but its restart state could not be saved."
        }
    }

    private fun stopDeletedUpstream(config: NetworkConfig) {
        val previous = sessions[config.id]?.config ?: config
        val binding = previous.bouncerBinding ?: return
        val updated = previous.copy(bouncerBinding = binding.copy(
            rejectionReason = "The bouncer removed this upstream, but its saved row could not be deleted.",
        ))
        if (sessions[config.id] == null) startSession(updated, allowStartup = false)
        else applyUpdatedConfig(previous, updated, credentialsChanged = false)
        refreshNetworkStates()
    }

    public fun updateConnectivity(available: Boolean, networkHandle: Long? = null) {
        synchronized(sessionLifecycleLock) {
            val previouslyAvailable = networkAvailable
            val changedRoute = defaultNetworkHandle != networkHandle
            networkAvailable = available
            defaultNetworkHandle = networkHandle
            val signal = when {
                !available -> RecoverySignal.NETWORK_LOST
                !previouslyAvailable -> RecoverySignal.NETWORK_AVAILABLE
                changedRoute || previouslyAvailable -> RecoverySignal.TRANSPORT_CHANGED
                else -> RecoverySignal.NETWORK_AVAILABLE
            }
            for (session in sessions.values) {
                synchronized(session.state) {
                    session.reconnector?.setNetworkAvailable(available)
                    recover(session, signal)
                }
            }
            refreshNetworkStates()
        }
    }

    public fun onForegroundResume() {
        synchronized(sessionLifecycleLock) {
            for (session in sessions.values) {
                synchronized(session.state) { recover(session, RecoverySignal.RESUME) }
            }
            refreshNetworkStates()
            scheduleMaintenance()
        }
    }

    public fun trustCertificate(networkId: String, expectedInspection: CertificateInspection? = null): Boolean =
        synchronized(sessionLifecycleLock) trust@{
            val session = sessions[networkId] ?: return@trust false
            synchronized(session.state) {
                val inspection = session.rejectedCertificate ?: return@trust false
                if (expectedInspection != null && inspection != expectedInspection) return@trust false
                val leaf = inspection.certificates.firstOrNull() ?: return@trust false
                val effective = session.effective ?: return@trust false
                if (clock() !in leaf.notBeforeMs..leaf.notAfterMs) {
                    showConnectionError(networkId, "Certificate trust could not be applied because the certificate is expired or not yet valid. Check the server certificate and device clock.")
                    return@trust false
                }
                if (!effective.tls || !effective.host.equals(inspection.host, ignoreCase = true) ||
                    effective.port != inspection.port
                ) return@trust false
                val config = networkStore.byId(networkId) ?: return@trust false
                val account = config.bouncerBinding?.parentId?.let(networkStore::byId) ?: config
                val updated = account.copy(certificatePin = CertificatePin(inspection.host, inspection.port, leaf.sha256))
                val persisted = try {
                    updateNetwork(updated)
                } catch (_: java.io.IOException) {
                    false
                }
                if (!persisted) {
                    showConnectionError(networkId, "Certificate trust was not saved. Check device storage and retry.")
                    return@trust false
                }
                true
            }
        }

    public fun removeCertificateTrust(networkId: String): Boolean =
        synchronized(sessionLifecycleLock) remove@{
            val saved = networkStore.byId(networkId) ?: return@remove false
            val account = saved.bouncerBinding?.parentId?.let(networkStore::byId) ?: saved
            if (account.certificatePin == null) return@remove false
            val removed = try {
                updateNetwork(account.copy(certificatePin = null))
            } catch (_: java.io.IOException) {
                false
            }
            if (!removed) showConnectionError(networkId, "Certificate trust removal was not saved. Check device storage and retry.")
            removed
        }

    public fun updateNetwork(config: NetworkConfig, credentialsChanged: Boolean = false): Boolean =
        synchronized(sessionLifecycleLock) update@{
            val saved = networkStore.byId(config.id) ?: return@update false
            val updated = config.copy(userDisconnected = saved.userDisconnected)
            if (saved.bouncerBinding == null && networkStore.dependents(saved.id).isNotEmpty() && updated.mode != NetworkMode.SOJU) {
                _operationError.value = "Remove the discovered upstreams before changing this bouncer account to another connection mode."
                return@update false
            }
            val resetRejectedUpstreams = credentialsChanged || !saved.connectionSettingsMatch(updated)
            val children = networkStore.dependents(config.id).map { savedChild ->
                val child = sessions[savedChild.id]?.config ?: savedChild
                val binding = child.bouncerBinding
                SojuUpstreamConfig.inherit(updated, if (resetRejectedUpstreams) child.copy(
                    bouncerBinding = binding?.copy(rejectionReason = null),
                ) else child)
            }
            if (!networkStore.upsertAll(listOf(updated) + children)) return@update false
            applyUpdatedConfig(saved, updated, credentialsChanged)
            for (child in children) {
                val previous = sessions[child.id]?.config ?: continue
                applyUpdatedConfig(previous, child, credentialsChanged)
            }
            refreshNetworkStates()
            true
        }

    private fun applyUpdatedConfig(previous: NetworkConfig, updated: NetworkConfig, credentialsChanged: Boolean) {
        val session = sessions[updated.id]
        clearNewAutojoins(previous, updated, session?.state?.casemapping ?: CaseMapping.RFC1459)
        if (session == null) return
        synchronized(session.state) {
            if (!credentialsChanged && session.config.connectionSettingsMatch(updated)) {
                session.config = updated
            } else {
                val wanted = session.recovery.desiredConnection && !updated.userDisconnected
                val mapping = session.state.casemapping
                stopSession(session, "Network settings changed")
                startSession(updated, mapping, manuallyWanted = wanted, allowStartup = false)
            }
        }
    }

    private fun NetworkConfig.connectionSettingsMatch(other: NetworkConfig): Boolean =
        id == other.id &&
            host == other.host &&
            port == other.port &&
            tls == other.tls &&
            nick == other.nick &&
            username == other.username &&
            realName == other.realName &&
            autojoin == other.autojoin &&
            saslAuthcid == other.saslAuthcid &&
            saslPasswordRef == other.saslPasswordRef &&
            serverPasswordRef == other.serverPasswordRef &&
            alternateNicks == other.alternateNicks &&
            saslMode == other.saslMode &&
            nickServAccount == other.nickServAccount &&
            nickServPasswordRef == other.nickServPasswordRef &&
            nickServService == other.nickServService &&
            waitForNickServ == other.waitForNickServ &&
            proxy == other.proxy &&
            tlsClientAlias == other.tlsClientAlias &&
            certificatePin == other.certificatePin &&
            mode == other.mode &&
            bouncerBinding == other.bouncerBinding &&
            zncNetwork == other.zncNetwork &&
            saslPassword == other.saslPassword &&
            serverPassword == other.serverPassword &&
            nickServPassword == other.nickServPassword &&
            proxyPassword == other.proxyPassword &&
            tlsClientIdentity === other.tlsClientIdentity

    private fun clearNewAutojoins(previous: NetworkConfig, updated: NetworkConfig, mapping: CaseMapping) {
        if (previous.autojoin == updated.autojoin) return
        val previousChannels = previous.autojoin.map(mapping::fold).toSet()
        for (channel in updated.autojoin) {
            if (mapping.fold(channel) !in previousChannels) {
                channelOrder.clearParted(ConversationRef.channel(updated.id, channel, mapping).storageKey)
            }
        }
        _orderState.value = channelOrder.snapshot()
    }

    private fun startSession(
        config: NetworkConfig,
        casemapping: CaseMapping = restoredCasemapping[config.id] ?: CaseMapping.RFC1459,
        manuallyWanted: Boolean = false,
        allowStartup: Boolean = true,
    ) {
        val session = Session(config, casemapping, manuallyWanted, allowStartup)
        session.recovery.handle(RecoverySignal.START)
        sessions[config.id] = session
        bouncerManagement.start(config.id, session.managementGeneration, managementMode(session),
            config.saslAuthcid.orEmpty(), config.bouncerBinding?.netId ?: config.zncNetwork)
        _profiles.update { profiles ->
            val retained = profiles[config.id] ?: return@update profiles
            profiles + (config.id to retained.copy(byFoldedNick = retained.byFoldedNick.mapValues { (_, profile) ->
                profile.copy(cached = true)
            }))
        }
        updateAllBuffersForNetwork(config.id) { buffer ->
            buffer.copy(
                cachedTopic = buffer.cachedTopic || topicObservedAt.containsKey(buffer.key) || buffer.topic != null,
                cachedRoster = buffer.cachedRoster || rosterObservedAt.containsKey(buffer.key) || buffer.members.isNotEmpty(),
                cachedModes = buffer.cachedModes || modesObservedAt.containsKey(buffer.key) || buffer.channelModes.isNotEmpty(),
                typingUsers = emptyMap(),
                joinState = if (buffer.ref.kind == ConversationKind.CHANNEL &&
                    !channelOrder.isParted(buffer.ref.storageKey)
                ) {
                    if (session.recovery.desiredConnection && upstreamEligible(config)) JoinState.JOINING else JoinState.IDLE
                } else buffer.joinState,
            )
        }
        ensureBaseBuffers(session)
        restoreKnownConversations(session)
        session.connectionError = config.bouncerBinding?.let { binding ->
            binding.rejectionReason ?: if (!binding.enabled) "This upstream is disabled on the bouncer." else null
        }
        refreshNetworkStates()
        if (session.recovery.desiredConnection) launchSession(session)
    }

    private fun restoreKnownConversations(session: Session) {
        val retainedBuffers = _buffers.value
        val retainedMappingKnown = session.storedMappingKnown
        enqueuePersistence {
            if (pendingStorageMigrations.containsKey(session.config.id)) return@enqueuePersistence
            val known = try {
                messageStore.knownConversations(session.config.id)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _operationError.value = "Saved conversations could not be loaded: ${error.message ?: "storage unavailable"}"
                return@enqueuePersistence
            }
            val cachedRefs = offlineStore?.shells(session.config.id).orEmpty().associateBy { it.ref.storageKey }
            synchronized(session.state) {
                if (sessions[session.config.id] !== session || pendingStorageMigrations.containsKey(session.config.id)) return@enqueuePersistence
                for (storedRef in known) {
                    val ref = currentRef(
                        if (retainedMappingKnown) {
                            retainedBuffers[storedRef.storageKey]?.ref ?: _buffers.value[storedRef.storageKey]?.ref ?: cachedRefs[storedRef.storageKey]?.ref ?: storedRef
                        } else {
                            storedRef
                        },
                    )
                    val isAutojoined = ref.kind == ConversationKind.CHANNEL &&
                        ref.rawTarget in session.config.autojoin
                    if (channelOrder.isParted(ref.storageKey)) continue
                    val alreadyOpen = ref.storageKey in _buffers.value
                    if (!isAutojoined && !alreadyOpen) {
                        if (ref.kind == ConversationKind.CHANNEL) {
                            updateBuffer(ref) {
                                it.copy(joinState = if (session.recovery.desiredConnection) JoinState.JOINING else JoinState.IDLE)
                            }
                        } else buffer(ref)
                    }
                    if (ref.kind == ConversationKind.CHANNEL &&
                        !isAutojoined && !alreadyOpen &&
                        session.statusFlow.value == ConnectionStatus.REGISTERED
                    ) {
                        sendRaw(session, "JOIN ${ref.rawTarget}")
                    }
                }
            }
        }
    }

    public fun disconnect(networkId: String, quitReason: String = "Yardhal") {
        synchronized(sessionLifecycleLock) {
            val saved = networkStore.byId(networkId) ?: return
            val session = sessions[networkId] ?: run {
                startSession(saved, allowStartup = false)
                sessions[networkId] ?: return
            }
            synchronized(session.state) {
                val persisted = saveDisconnectIntent(networkId, true)
                session.disconnectSavePending = !persisted
                session.config = networkStore.byId(networkId) ?: saved
                session.recovery.handle(RecoverySignal.USER_DISCONNECT)
                retireTransport(session, quitReason)
                session.connectionError = if (persisted) null else {
                    "Disconnected for this session, but the restart disconnect preference was not saved. Check device storage and retry Disconnect."
                }
                session.connectionError?.let { ingestRaw(networkId, false, it) }
                refreshNetworkStates()
            }
            if (saved.bouncerBinding == null) {
                for (child in networkStore.dependents(networkId)) {
                    val dependent = sessions[child.id] ?: continue
                    val wanted = dependent.recovery.desiredConnection
                    restartSession(dependent.config, manuallyWanted = wanted)
                }
            }
        }
    }

    private fun saveDisconnectIntent(networkId: String, disconnected: Boolean): Boolean = try {
        networkStore.setUserDisconnected(networkId, disconnected)
    } catch (_: java.io.IOException) {
        false
    }

    private fun showConnectionError(networkId: String, reason: String) {
        val session = sessions[networkId] ?: networkStore.byId(networkId)?.let { config ->
            startSession(config, allowStartup = false)
            sessions[networkId]
        } ?: return
        synchronized(session.state) {
            session.connectionError = redactPresentation(session, reason)
            ingestRaw(networkId, false, reason)
            refreshNetworkStates()
        }
    }

    private fun stopSession(session: Session, quitReason: String) {
        retireTransport(session, quitReason)
        sessions.remove(session.config.id, session)
    }

    private fun retireTransport(session: Session, quitReason: String) {
        session.quitRequested = true
        sendRaw(session, "QUIT :$quitReason")
        bouncerManagement.disconnected(session.config.id, session.managementGeneration)
        session.acceptedEpoch = null
        session.state.registered = false
        session.statusFlow.value = ConnectionStatus.DISCONNECTED
        markNetworkCached(session.config.id)
        clearIdentification(session)
        session.probeJob?.cancel()
        session.probeJob = null
        history.reset(session.config.id, quitReason)
        session.reconnector?.stop()
        session.connection?.disconnect()
        session.lifecycleJob.cancel()
    }

    public fun removeNetwork(networkId: String): Boolean = synchronized(sessionLifecycleLock) {
        val saved = networkStore.byId(networkId) ?: return@synchronized false
        val removed = listOf(saved) + networkStore.dependents(networkId)
        if (!networkStore.removeAll(removed.map { it.id }.toSet())) {
            _operationError.value = "The network and its upstreams were not removed because the configuration could not be saved."
            return@synchronized false
        }
        synchronized(persistenceLock) { persistenceBatch += 1 }
        for (config in removed) removePersistedNetwork(config)
        refreshNetworkStates()
        true
    }

    private fun removePersistedNetwork(saved: NetworkConfig) {
            val networkId = saved.id
            sessions[networkId]?.let { session ->
                synchronized(session.state) { stopSession(session, "Network removed") }
            }
            enqueuePersistence {
                messageStore.deleteNetwork(networkId)
                offlineStore?.deleteNetwork(networkId)
                readMarkers.deleteNetwork(networkId)
                channelOrder.forgetNetwork(networkId)
                mutes.deleteNetwork(networkId)
                _orderState.value = channelOrder.snapshot()
                _mutedState.value = mutes.all()
                try {
                    networkStore.deleteUnreferencedPasswords(
                        listOfNotNull(saved.saslPasswordRef, saved.serverPasswordRef, saved.nickServPasswordRef, saved.proxy?.passwordRef),
                        vault,
                    )
                } catch (_: java.io.IOException) {
                    _operationError.value = "Network removed, but unused saved credentials could not be deleted. Check device storage and credential permissions."
                }
            }
            history.removeNetwork(networkId)
            bouncerManagement.remove(networkId)
            synchronized(selectionLock) {
                if (selectedStorageKey?.substringBefore("|") == networkId) {
                    selectedStorageKey = null
                    pendingRenamedKey = null
                }
                _buffers.update { buffers -> buffers.filterValues { it.ref.networkId != networkId } }
            }
            _profiles.update { it - networkId }
            loadedRosters.removeAll { it.substringBefore("|") == networkId }
            loadedUsers.removeAll { it.substringBefore("|") == networkId }
            metadataRequests.keys.removeAll { it.substringBefore("|") == networkId }
            topicObservedAt.keys.removeAll { it.substringBefore("|") == networkId }
            rosterObservedAt.keys.removeAll { it.substringBefore("|") == networkId }
            modesObservedAt.keys.removeAll { it.substringBefore("|") == networkId }
            restoredCasemapping.remove(networkId)
            pendingStorageMigrations.remove(networkId)
            renamedConversations.keys.removeAll { it.startsWith("$networkId|") }
            synchronized(selectionLock) { deferredStorageReads.remove(networkId) }
            deferredMaintenanceNetworks.remove(networkId)
            writesSinceTrim.keys.removeAll { it.substringBefore("|") == networkId }
            persistedRowsByLocalId.keys.removeAll { it.substringBefore("|") == networkId }
            reactionRevisions.keys.removeAll { it.substringBefore("|") == networkId }
            if (_restoredSelection.value?.substringBefore("|") == networkId) _restoredSelection.value = null
            if (_whoisPresentation.value?.networkId == networkId) dismissWhois()
            rawLogs.remove(networkId)
            _rawLogVersion.update { it + 1 }
    }

    private fun redactPresentation(session: Session, rawText: String): String {
        session.redactionConnection?.let { return it.redactPresentation(rawText) }
        val text = rawText
        if (session.presentationSecrets.isEmpty()) return text
        var output: StringBuilder? = null
        var offset = 0
        var unchangedFrom = 0
        while (offset < text.length) {
            var match: String? = null
            for (secret in session.presentationSecrets) {
                if (text.startsWith(secret, offset) && secret.length > (match?.length ?: 0)) match = secret
            }
            if (match == null) {
                offset += if (text.startsWith("<redacted>", offset)) "<redacted>".length else 1
                continue
            }
            val builder = output ?: StringBuilder(text.length).also { output = it }
            builder.append(text, unchangedFrom, offset).append("<redacted>")
            offset += match.length
            unchangedFrom = offset
        }
        return output?.append(text, unchangedFrom, text.length)?.toString() ?: text
    }

    private fun redactEffect(session: Session, effect: InboundEffect): InboundEffect {
        if (effect !is InboundEffect.AppendMessage) return effect
        val text = redactPresentation(session, effect.text)
        return if (text == effect.text) effect else effect.copy(text = text)
    }

    private fun launchSession(session: Session) = synchronized(session.state) {
        if (sessions[session.config.id] !== session || session.quitRequested || !session.lifecycleJob.isActive ||
            !session.recovery.desiredConnection || session.reconnector != null || !upstreamEligible(session.config)
        ) return@synchronized
        if (startupRequested && !_restorationReady.value) return@synchronized
        val reconnector = IrcReconnector(
            scope = session.lifecycleScope,
            policy = ReconnectPolicy(initialDelayMillis = 1_000, maxDelayMillis = 30_000),
            connectionFactory = {
                val config: NetworkConfig
                val upgradePort: Int?
                val token: Long
                synchronized(session.state) {
                    session.lifecycleJob.ensureActive()
                    if (sessions[session.config.id] !== session) throw kotlinx.coroutines.CancellationException()
                    session.redactionConnection = null
                    token = session.reconnector?.currentEpoch ?: throw kotlinx.coroutines.CancellationException()
                    session.attemptToken = token
                    session.managementGeneration = idGenerator.getAndIncrement()
                    session.recovery.handle(RecoverySignal.CONNECTING, token)
                    session.acceptedEpoch = token.takeIf { networkAvailable && session.recovery.phase == RecoveryPhase.CONNECTING }
                    session.statusFlow.value = ConnectionStatus.CONNECTING
                    config = session.config
                    upgradePort = session.stsUpgradePort
                    refreshNetworkStates()
                }
                val effective = effectiveConfig(config, upgradePort)
                synchronized(session.state) {
                    session.lifecycleJob.ensureActive()
                    if (sessions[config.id] !== session || session.reconnector?.currentEpoch != token) {
                        throw kotlinx.coroutines.CancellationException()
                    }
                    session.effective = effective
                    session.presentationSecrets = effective.knownSecrets
                }
                connectionFactory.create(effective) { port ->
                    synchronized(session.state) {
                        if (sessions[config.id] === session && session.reconnector?.currentEpoch == token &&
                            session.acceptedEpoch == token
                        ) {
                            session.stsUpgradePort = port
                        }
                    }
                }.also { connection ->
                    synchronized(session.state) {
                        if (sessions[config.id] !== session || session.reconnector?.currentEpoch != token) {
                            connection.disconnect()
                            throw kotlinx.coroutines.CancellationException()
                        }
                        session.connection = connection
                        session.redactionConnection = connection
                    }
                }
            },
        )
        session.reconnector = reconnector
        session.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            reconnector.events.collect { event -> routeEvent(session, event) }
        }
        session.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            reconnector.state.collect { state ->
                synchronized(session.state) {
                    if (sessions[session.config.id] !== session || session.quitRequested) return@collect
                    if (state is ReconnectState.Stopped) session.statusFlow.value = ConnectionStatus.DISCONNECTED
                    refreshNetworkStates()
                }
            }
        }
        reconnector.setNetworkAvailable(networkAvailable)
        reconnector.start()
    }

    private fun effectiveConfig(savedConfig: NetworkConfig, stsUpgradePort: Int? = null): NetworkConfig {
        val config = savedConfig.bouncerBinding?.let { binding ->
            val parent = networkStore.byId(binding.parentId)
                ?: throw BouncerBindRejectedException("The parent bouncer account no longer exists.")
            SojuUpstreamConfig.inherit(parent, savedConfig)
        } ?: savedConfig
        val znc = config.mode == NetworkMode.ZNC
        val saslPassword = if (znc) null else resolvePassword(config.saslPasswordRef, config.saslPassword, "SASL")
        if (!znc && (config.saslMode == SaslMode.PLAIN || config.saslMode == SaslMode.SCRAM_SHA_256) &&
            saslPassword.isNullOrEmpty()
        ) throw AuthenticationRejectedException("The selected SASL mechanism requires a saved password. Replace it in network settings.")
        if (config.mode == NetworkMode.SOJU &&
            (config.saslAuthcid?.any { it.isWhitespace() || it == '\u0000' } == true ||
                saslPassword?.contains('\u0000') == true)
        ) throw AuthenticationRejectedException("The soju account or password contains an invalid authentication value. Correct account settings.")
        val savedServerPassword = resolvePassword(config.serverPasswordRef, config.serverPassword, if (znc) "ZNC account" else "server")
        val nickServPassword = resolvePassword(config.nickServPasswordRef, config.nickServPassword, "NickServ")
        val proxyPassword = resolvePassword(config.proxy?.passwordRef, config.proxyPassword, "proxy")
        val knownSecrets = buildSet {
            addAll(config.knownSecrets)
            saslPassword?.takeIf(String::isNotEmpty)?.let { add(it) }
            savedServerPassword?.takeIf(String::isNotEmpty)?.let { add(it) }
            nickServPassword?.takeIf(String::isNotEmpty)?.let { add(it) }
            proxyPassword?.takeIf(String::isNotEmpty)?.let { add(it) }
        }
        val serverPassword = if (znc) {
            val account = config.saslAuthcid?.takeIf { it.isNotBlank() && it.none { character -> character.isWhitespace() || character in "\u0000\r\n:/@" } }
                ?: throw AuthenticationRejectedException("The ZNC account username is missing or invalid. Correct account settings.")
            val network = config.zncNetwork?.takeIf { it.isNotBlank() }
            if (network?.any { it.isWhitespace() || it in "\u0000\r\n:/@" } == true) {
                throw AuthenticationRejectedException("The ZNC network name is invalid. Correct account settings.")
            }
            val password = savedServerPassword ?: throw AuthenticationRejectedException("The ZNC account password is missing. Replace it in account settings.")
            if (network == null) "$account:$password" else "$account/$network:$password"
        } else savedServerPassword
        if (nickServPassword != null && (config.nickServService.isBlank() ||
                config.nickServService.any { it.isWhitespace() || it in "\u0000\r\n:,#&*" || it == '$' } ||
                config.nickServAccount?.let { it.isBlank() || it.any { character -> character.isWhitespace() || character in "\u0000\r\n" } } == true ||
                nickServPassword.any { it in "\u0000\r\n" }
            )
        ) throw AuthenticationRejectedException("The NickServ service, account or password contains an invalid IRC command value. Correct network settings.")
        if (config.proxy?.username != null && proxyPassword == null) {
            throw AuthenticationRejectedException("The proxy password is missing. Replace it in network settings.")
        }
        if (config.proxy?.username == null && proxyPassword != null) {
            throw AuthenticationRejectedException("The proxy username is missing. Correct it in network settings.")
        }
        if (saslPassword != null && config.saslAuthcid.isNullOrBlank() && config.saslMode != SaslMode.EXTERNAL) {
            throw AuthenticationRejectedException("The SASL account is missing. Correct it in network settings.")
        }
        val decision = StsResolver.decide(
            stsPolicies, config.host, config.port, config.tls, clock() / 1000,
        )
        val securePort = (decision as? StsUpgradeDecision.UpgradeRequired)?.port ?: stsUpgradePort
        val effectiveTls = config.tls || securePort != null
        val effectivePort = securePort ?: config.port
        if (!znc && config.saslMode == SaslMode.EXTERNAL && !effectiveTls) {
            throw TlsIdentityUnavailableException("SASL EXTERNAL requires TLS. Enable TLS in network security settings.")
        }
        val identity = config.tlsClientAlias?.let(clientIdentityProvider) ?: config.tlsClientIdentity
        if (!znc && config.saslMode == SaslMode.EXTERNAL && identity == null) {
            throw TlsIdentityUnavailableException("SASL EXTERNAL requires TLS and a selected client certificate. Correct the network security settings.")
        }
        val pin = config.certificatePin?.takeIf {
            effectiveTls && it.host.equals(config.host, ignoreCase = true) && it.port == effectivePort
        }
        return config.copy(
            port = effectivePort,
            tls = effectiveTls,
            saslAuthcid = config.saslAuthcid.takeIf { !znc && (saslPassword != null || config.saslMode == SaslMode.EXTERNAL) },
            saslPassword = saslPassword,
            saslMode = if (znc) SaslMode.AUTO else config.saslMode,
            serverPassword = serverPassword,
            nickServPassword = nickServPassword,
            proxyPassword = proxyPassword,
            tlsClientIdentity = identity,
            certificatePin = pin,
            knownSecrets = knownSecrets,
        )
    }

    private fun resolvePassword(reference: String?, transient: String?, role: String): String? {
        if (reference == null) return transient
        val password = try {
            vault.readPassword(reference)
        } catch (_: Exception) {
            throw AuthenticationRejectedException("The saved $role password is unavailable. Replace it in network settings.")
        }
        return password ?: throw AuthenticationRejectedException(
            "The saved $role password is missing. Replace it in network settings.",
        )
    }

    private fun recover(session: Session, signal: RecoverySignal) {
        if (!upstreamEligible(session.config)) return
        when (session.recovery.handle(signal)) {
            RecoveryAction.NONE -> Unit
            RecoveryAction.START -> launchSession(session)
            RecoveryAction.PAUSE -> {
                session.probeJob = null
                clearIdentification(session)
                history.reset(session.config.id, "Network unavailable")
                session.acceptedEpoch = null
                session.state.connectionEpoch += 1
                session.state.registered = false
                session.statusFlow.value = ConnectionStatus.DISCONNECTED
                markNetworkCached(session.config.id)
                session.reconnector?.setNetworkAvailable(false)
            }
            RecoveryAction.NUDGE -> {
                if (session.reconnector == null) launchSession(session) else session.reconnector?.nudge()
            }
            RecoveryAction.PROBE -> probeSession(session)
            RecoveryAction.STOP -> {
                markNetworkCached(session.config.id)
                session.reconnector?.stop()
            }
        }
    }

    private fun probeSession(session: Session) {
        if (session.probeJob?.isActive == true) return
        val connection = session.connection ?: return
        val epoch = session.state.connectionEpoch
        val token = session.attemptToken
        session.probeJob = session.lifecycleScope.launch {
            val alive = connection.probe()
            synchronized(session.state) {
                if (sessions[session.config.id] !== session || session.connection !== connection ||
                    session.state.connectionEpoch != epoch || session.attemptToken != token ||
                    session.reconnector?.currentEpoch != token ||
                    !session.state.registered || session.recovery.phase != RecoveryPhase.REGISTERED
                ) return@launch
                session.probeJob = null
                if (alive) {
                    session.recovery.handle(RecoverySignal.REGISTERED, token)
                } else {
                    session.connectionError = "The server did not respond to a connection check. Reconnecting."
                    session.recovery.handle(RecoverySignal.SERVER_FAILED, token)
                    session.state.registered = false
                    session.acceptedEpoch = null
                    session.state.connectionEpoch += 1
                    session.statusFlow.value = ConnectionStatus.DISCONNECTED
                    markNetworkCached(session.config.id)
                    history.reset(session.config.id, "Connection check failed")
                    connection.disconnect()
                    session.reconnector?.nudge()
                }
                refreshNetworkStates()
            }
        }
    }

    private fun routeEvent(session: Session, envelope: ReconnectionEvent) {
        val event = envelope.event
        synchronized(sessionLifecycleLock) {
        synchronized(session.state) {
            if (session.reconnector?.currentEpoch != envelope.epoch || session.attemptToken != envelope.epoch ||
                envelope.connection != null && session.connection !== envelope.connection
            ) return
            if (event !is IrcEvent.Disconnected && session.acceptedEpoch != envelope.epoch) return
            if (sessions[session.config.id] !== session || session.quitRequested) return
            if (!networkAvailable && event !is IrcEvent.Disconnected) return
            if (session.recovery.phase == RecoveryPhase.AUTHENTICATION_REJECTED ||
                session.recovery.phase == RecoveryPhase.CERTIFICATE_REJECTED
            ) return
            if (event is IrcEvent.ConnectionOpened) {
                session.sojuDiscovery.begin(session.managementGeneration, restoredSojuAttributes(session.config.id))
                bouncerManagement.start(session.config.id, session.managementGeneration, managementMode(session),
                    session.config.saslAuthcid.orEmpty(), session.config.bouncerBinding?.netId ?: session.config.zncNetwork)
            }
            if (event is IrcEvent.CapabilitiesNegotiated &&
                session.config.mode == NetworkMode.DIRECT &&
                dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.CAPABILITY in event.capabilities
            ) {
                val updated = session.config.copy(mode = NetworkMode.SOJU)
                if (networkStore.update(updated)) session.config = updated
                else _operationError.value = "The discovered soju account mode could not be saved. Check device storage."
                bouncerManagement.start(session.config.id, session.managementGeneration, NetworkMode.SOJU,
                    session.config.saslAuthcid.orEmpty())
            }
            if (event is IrcEvent.Registered) {
                if (session.state.registered || session.pendingRegistration != null) return
                if (session.authenticatedNick == "*") {
                    if (session.saslAuthenticated) session.authenticatedNick = event.nickname
                    else {
                        session.authenticatedNick = null
                        session.authenticatedAccount = null
                    }
                }
                if (session.effective?.nickServPassword != null) beginIdentification(session, event)
                else releaseRegistration(session, event)
                return
            }
            if (event is IrcEvent.MessageReceived) {
                if (managementMode(session) == NetworkMode.SOJU && session.config.bouncerBinding == null) {
                    reconcileSojuDiscovery(session, event.message)
                }
                if (bouncerManagement.receive(session.config.id, session.managementGeneration, event.message)) return
                updateAuthenticatedIdentity(session, event.message)
                val gate = session.identificationGate
                if (gate != null && event.message.tags["batch"] == null) {
                    val expired = gate.expire(historyElapsedClock())
                    val outcome = if (expired == NickServOutcome.WAITING) gate.receive(event.message) else expired
                    finishIdentification(session, outcome)
                    if (session.recovery.phase == RecoveryPhase.AUTHENTICATION_REJECTED) return
                }
                if (history.receive(
                        session.config.id, session.historyGeneration, session.state.connectionEpoch.toLong(), event.message,
                    )
                ) return
            }
            if (event is IrcEvent.ConnectionOpened || event is IrcEvent.Disconnected) {
                session.authenticatedNick = null
                session.authenticatedAccount = null
                session.saslAuthenticated = false
                clearIdentification(session)
                session.probeJob = null
                history.reset(session.config.id, "Connection closed")
            }
            if (event is IrcEvent.SaslResult && event.outcome == SaslOutcome.Success) {
                session.saslAuthenticated = true
            }
            val saslFailure = (event as? IrcEvent.SaslResult)?.outcome as? SaslOutcome.Failure
            if (saslFailure != null && session.pendingRegistration != null) {
                appendSystem(session, session.state.server, "SASL authentication failed: ${saslFailure.description}")
                return
            }
            val previousMapping = session.state.casemapping
            val previousSupport = session.state.isupport
            val previousCapabilities = session.state.supportedCaps
            val previousNick = session.state.ownNick
            val effects = session.state.apply(event, inboundContext(session))
            if (event is IrcEvent.MessageReceived && event.message.tags["batch"] == null) {
                val command = event.message.command
                if (command == "366" || command == "315") {
                    event.message.parameters.getOrNull(1)?.let { target ->
                        metadataRequests.remove(session.state.channelRef(target).storageKey, if (command == "366") "NAMES" else "WHO")
                    }
                }
                if (command == "NICK" && "draft/chathistory-context" !in event.message.tags) {
                    val oldNick = event.message.prefix?.nick
                    val newNick = event.message.parameters.firstOrNull()
                    if (oldNick != null && newNick != null) {
                        val mapping = session.state.casemapping
                        _profiles.update { profiles ->
                            val current = profiles[session.config.id] ?: return@update profiles
                            val profile = current.forNick(oldNick) ?: return@update profiles
                            profiles + (session.config.id to current.copy(byFoldedNick =
                                current.byFoldedNick - mapping.fold(oldNick) + (mapping.fold(newNick) to profile)))
                        }
                        enqueuePersistence {
                            if (sessions[session.config.id] === session && networkStore.byId(session.config.id) != null) {
                                offlineStore?.renameUser(session.config.id, oldNick, newNick, mapping)
                            }
                        }
                    }
                }
            }
            if (previousMapping != session.state.casemapping) reconcileCaseMapping(session)
            if (previousSupport !== session.state.isupport) session.storedMappingKnown = true
            if (previousSupport !== session.state.isupport || previousCapabilities != session.state.supportedCaps ||
                previousNick != session.state.ownNick
            ) {
                history.support(
                    session.config.id, session.historyGeneration, session.state.connectionEpoch.toLong(),
                    session.state.casemapping, session.state.ownNick, session.state.isupport, session.state.supportedCaps,
                )
            }
            for (effect in effects) processEffect(session, effect)
            if (event is IrcEvent.CapabilitiesNegotiated) {
                if (session.state.registered) bouncerManagement.capabilitiesChanged(
                    session.config.id, session.managementGeneration, session.state.supportedCaps,
                )
                replayPendingReadMarkers(session)
            }
            when (event) {
                is IrcEvent.ConnectionOpened -> {
                    session.replayedReadCursors.clear()
                    markNetworkCached(session.config.id)
                    session.connectionError = null
                    session.rejectedCertificate = null
                    session.statusFlow.value = ConnectionStatus.CONNECTING
                }
                is IrcEvent.Disconnected -> {
                    bouncerManagement.disconnected(session.config.id, session.managementGeneration)
                    markNetworkCached(session.config.id)
                    session.connection = null
                    session.acceptedEpoch = null
                    connectionFailed(session, event.cause)
                }
                else -> Unit
            }
            refreshNetworkStates()
        }
        }
    }

    private fun updateAuthenticatedIdentity(session: Session, message: IrcMessage) {
        if ("batch" in message.tags || "draft/chathistory-context" in message.tags) return
        val prefix = message.prefix
        fun sameName(actual: String?, expected: String?): Boolean =
            actual != null && expected != null && CaseMapping.ASCII.equal(actual, expected)
        when {
            message.command.equals("NICK", true) && prefix?.user != null && prefix.host != null &&
                sameName(prefix.nick, session.state.ownNick) && sameName(prefix.nick, session.authenticatedNick) -> {
                message.parameters.singleOrNull()?.let { session.authenticatedNick = it }
            }
            message.command.equals("ACCOUNT", true) && prefix?.user != null && prefix.host != null &&
                (sameName(prefix.nick, session.state.ownNick) ||
                    session.authenticatedNick == "*" && prefix.nick == "*" &&
                    !session.state.registered && session.pendingRegistration == null) &&
                message.parameters.size == 1 -> {
                val account = message.parameters[0].takeIf { it.isNotEmpty() && it != "*" && it.none(Char::isWhitespace) }
                session.authenticatedNick = prefix.nick.takeIf { account != null }
                session.authenticatedAccount = account
            }
            (message.numeric == 900 || message.numeric == 901) && prefix?.isServer == true && message.parameters.size >= 3 -> {
                val target = message.parameters[0]
                val usermask = message.parameters[1]
                val beforeRegistration = !session.state.registered && session.pendingRegistration == null
                val loggedInNick = if (target == "*" && usermask == "*" && beforeRegistration) "*" else {
                    val loggedInUser = IrcPrefix.parse(usermask)
                    if (loggedInUser?.user == null || loggedInUser.host == null ||
                        !sameName(target, loggedInUser.nick) ||
                        loggedInUser.nick == "*" && !beforeRegistration ||
                        session.state.registered && !sameName(loggedInUser.nick, session.state.ownNick)
                    ) return
                    loggedInUser.nick
                }
                val account = message.parameters[2].takeIf {
                    message.numeric == 900 && it.isNotEmpty() && it != "*" && it.none(Char::isWhitespace)
                }
                session.authenticatedNick = loggedInNick.takeIf { account != null }
                session.authenticatedAccount = account
            }
        }
    }

    private fun releaseRegistration(session: Session, event: IrcEvent.Registered) {
        if (session.state.registered || session.state.authenticationRejected ||
            !session.recovery.desiredConnection || !networkAvailable
        ) return
        session.pendingRegistration = null
        session.connectionError = null
        session.recovery.handle(RecoverySignal.REGISTERED, session.attemptToken)
        if (session.recovery.phase != RecoveryPhase.REGISTERED) return
        for (effect in session.state.apply(event, inboundContext(session))) processEffect(session, effect)
        history.registered(
            session.config.id, session.historyGeneration, session.state.connectionEpoch.toLong(),
            session.state.casemapping, session.state.ownNick, session.state.isupport, session.state.supportedCaps,
            allowIsupportDiscovery = session.config.mode != NetworkMode.ZNC,
        )
        bouncerManagement.connected(session.config.id, session.managementGeneration, managementMode(session),
            session.state.supportedCaps, session.config.saslAuthcid.orEmpty(),
            session.config.bouncerBinding?.netId ?: session.config.zncNetwork)
        replayPendingReadMarkers(session)
        refreshRequestedWhois(session)
        refreshNetworkStates()
    }

    private fun beginIdentification(session: Session, event: IrcEvent.Registered) {
        val effective = session.effective ?: return
        val password = effective.nickServPassword ?: return
        val intendedAccount = effective.nickServAccount ?: event.nickname
        if (session.authenticatedNick?.let { CaseMapping.ASCII.equal(it, event.nickname) } == true &&
            session.authenticatedAccount?.let { CaseMapping.ASCII.equal(it, intendedAccount) } == true
        ) {
            releaseRegistration(session, event)
            return
        }
        session.recovery.handle(RecoverySignal.IDENTIFYING, session.attemptToken)
        if (session.recovery.phase != RecoveryPhase.IDENTIFYING) return
        session.state.ownNick = event.nickname
        session.pendingRegistration = event
        val gate = NickServIdentificationGate(
            service = effective.nickServService,
            ourNick = event.nickname,
            account = effective.nickServAccount,
            nowMillis = historyElapsedClock(),
        )
        session.identificationGate = gate
        sendRaw(session, "PRIVMSG ${effective.nickServService} :IDENTIFY${effective.nickServAccount?.let { " $it" }.orEmpty()} $password")
        val epoch = session.state.connectionEpoch
        val token = session.attemptToken
        session.identificationDeadline = session.lifecycleScope.launch {
            delay((gate.deadlineMs - historyElapsedClock()).coerceAtLeast(0))
            synchronized(session.state) {
                if (sessions[session.config.id] !== session || session.identificationGate !== gate ||
                    session.state.connectionEpoch != epoch || session.attemptToken != token ||
                    session.reconnector?.currentEpoch != token
                ) return@launch
                finishIdentification(session, gate.expire(maxOf(historyElapsedClock(), gate.deadlineMs)))
            }
        }
        if (!effective.waitForNickServ) releaseRegistration(session, event)
        refreshNetworkStates()
    }

    private fun finishIdentification(session: Session, outcome: NickServOutcome) {
        when (outcome) {
            NickServOutcome.WAITING -> Unit
            NickServOutcome.IDENTIFIED -> {
                val gate = session.identificationGate ?: return
                val registration = session.pendingRegistration?.copy(nickname = gate.currentNick)
                clearIdentification(session)
                if (registration != null) releaseRegistration(session, registration)
            }
            NickServOutcome.REJECTED -> {
                clearIdentification(session)
                authenticationFailed(session, "NickServ rejected identification. Check the service, account and password, then Connect to retry.")
            }
            NickServOutcome.TIMED_OUT -> {
                clearIdentification(session)
                val reason = "NickServ did not confirm identification within seven seconds. Check the service, account and password."
                if (session.config.waitForNickServ) authenticationFailed(session, reason)
                else {
                    session.connectionError = reason
                    appendSystem(session, session.state.server, reason)
                    refreshNetworkStates()
                }
            }
        }
    }

    private fun clearIdentification(session: Session) {
        session.identificationDeadline?.cancel()
        session.identificationDeadline = null
        session.identificationGate = null
        session.pendingRegistration = null
    }

    private fun authenticationFailed(session: Session, reason: String) {
        session.state.authenticationRejected = true
        session.state.registered = false
        session.acceptedEpoch = null
        session.connectionError = redactPresentation(session, reason)
        session.statusFlow.value = ConnectionStatus.DISCONNECTED
        clearIdentification(session)
        session.probeJob?.cancel()
        session.probeJob = null
        history.reset(session.config.id, "Authentication failed")
        session.recovery.handle(RecoverySignal.AUTH_REJECTED, session.attemptToken)
        session.reconnector?.stop()
        appendSystem(session, session.state.server, reason)
        refreshNetworkStates()
    }

    private fun connectionFailed(session: Session, cause: Throwable?) {
        var error = cause
        var depth = 0
        var certificateFailure: CertificateException? = null
        while (error != null && depth < 32) {
            when (error) {
                is BouncerBindRejectedException -> {
                    persistUpstreamRejection(session, error.message ?: "The bouncer rejected this upstream.")
                    authenticationFailed(session, error.message ?: "The bouncer rejected this upstream.")
                    return
                }
                is CertificateRejectedException -> {
                    session.rejectedCertificate = error.inspection
                    session.connectionError = "The server certificate was rejected. Inspect its details before deciding whether to trust it."
                    session.recovery.handle(RecoverySignal.CERT_REJECTED, session.attemptToken)
                    session.reconnector?.stop()
                    return
                }
                is CertificateExpiredException -> {
                    certificateFailed(session, "The server certificate has expired. Check the server certificate and device clock before reconnecting.")
                    return
                }
                is CertificateNotYetValidException -> {
                    certificateFailed(session, "The server certificate is not yet valid. Check the server certificate and device clock before reconnecting.")
                    return
                }
                is CertificateException -> certificateFailure = error
                is AuthenticationRejectedException -> {
                    authenticationFailed(session, error.message ?: "Authentication was rejected. Correct the network credentials.")
                    return
                }
                is TlsIdentityUnavailableException -> {
                    authenticationFailed(session, error.message ?: "The TLS client identity is unavailable. Select it again in network settings.")
                    return
                }
                is Socks5Exception -> {
                    if (error.authenticationRejected) {
                        authenticationFailed(session, "The proxy rejected authentication. Correct the proxy username and password.")
                    } else {
                        session.connectionError = redactPresentation(session, error.message ?: "The SOCKS proxy connection failed. Check the proxy endpoint.")
                        session.recovery.handle(RecoverySignal.SERVER_FAILED, session.attemptToken)
                    }
                    return
                }
            }
            error = error.cause
            depth += 1
        }
        if (certificateFailure != null) {
            certificateFailed(session, "The server certificate is not valid for this endpoint or could not be verified. Check the network hostname, server certificate and device clock before reconnecting.")
            return
        }
        if (session.recovery.phase == RecoveryPhase.OFFLINE || !networkAvailable) return
        session.connectionError = "Unable to reach ${session.config.host}:${session.effective?.port ?: session.config.port}. Check the endpoint and connectivity."
        session.recovery.handle(RecoverySignal.SERVER_FAILED, session.attemptToken)
    }

    private fun certificateFailed(session: Session, reason: String) {
        session.rejectedCertificate = null
        session.connectionError = reason
        session.recovery.handle(RecoverySignal.CERT_REJECTED, session.attemptToken)
        session.reconnector?.stop()
    }

    private fun inboundContext(
        session: Session,
        historyPlayback: Boolean = false,
        historyTarget: ConversationRef? = null,
    ): InboundContext {
        val networkId = session.config.id
        return InboundContext(
            nowMs = clock(),
            isIgnored = { nick -> ignoreStore?.isIgnored(nick) == true },
            hasBuffer = { key -> key in _buffers.value },
            openChannels = {
                if (managementMode(session) == NetworkMode.SOJU && session.config.bouncerBinding == null) emptyList() else _buffers.value.values
                    .filter {
                        it.ref.networkId == networkId && it.ref.kind == ConversationKind.CHANNEL &&
                            !channelOrder.isParted(it.ref.storageKey)
                    }
                    .map { it.ref.rawTarget }
            },
            isParted = { channel -> channelOrder.isParted(session.state.channelRef(channel).storageKey) },
            historyPlayback = historyPlayback,
            historyTarget = historyTarget,
        )
    }

    private fun processEffect(session: Session, effect: InboundEffect) {
        when (effect) {
            is InboundEffect.SendRaw -> sendRaw(session, effect.line)
            is InboundEffect.AuthenticationFailed -> authenticationFailed(session, effect.reason)
            is InboundEffect.RequestHistory -> history.bootstrap(session.config.id, effect.ref)
            is InboundEffect.ScheduleRaw -> session.lifecycleScope.launch {
                delay(effect.delayMs)
                synchronized(session.state) {
                    if (sessions[session.config.id] === session &&
                        session.state.connectionEpoch == effect.connectionEpoch &&
                        session.state.registered && session.statusFlow.value == ConnectionStatus.REGISTERED
                    ) {
                        sendRaw(session, effect.line)
                    }
                }
            }
            is InboundEffect.ProfilesChanged -> {
                if (sessions[session.config.id] !== session || !session.state.registered) return
                val observed = session.state.users.values.filter { it.metadataObservedAtMs != null }
                val redact: (String) -> String = { redactPresentation(session, it) }
                val publishedKeys = effect.profiles.byFoldedNick.keys
                val removedKeys = session.publishedProfileKeys - publishedKeys
                _profiles.update { profiles ->
                    val current = profiles[session.config.id] ?: NetworkProfiles(session.state.casemapping, emptyMap())
                    val merged = current.byFoldedNick.toMutableMap()
                    for (key in removedKeys) merged.remove(key)
                    for (user in observed) {
                        val key = session.state.fold(user.nick)
                        val profile = mergeObservedProfile(merged[key], user, redact)
                        if (profile == null) merged.remove(key) else merged[key] = profile
                    }
                    profiles + (session.config.id to current.copy(casemapping = session.state.casemapping, byFoldedNick = merged))
                }
                session.publishedProfileKeys = publishedKeys
                val mapping = session.state.casemapping
                enqueuePersistence {
                    if (sessions[session.config.id] !== session || networkStore.byId(session.config.id) == null) return@enqueuePersistence
                    for (user in observed) {
                        val previous = offlineStore?.user(session.config.id, user.nick, mapping)
                        if (sessions[session.config.id] !== session || networkStore.byId(session.config.id) == null) return@enqueuePersistence
                        offlineStore?.saveUser(session.config.id, CachedUser(user.nick, user.presence.toCachedPresence(redact),
                            mergeObservedMetadata(previous?.metadata.orEmpty(), user, redact), user.metadataObservedAtMs ?: clock()), mapping)
                    }
                }
            }
            is InboundEffect.NetworkIconChanged -> refreshNetworkStates()
            is InboundEffect.StatusChanged -> {
                if (effect.status != ConnectionStatus.REGISTERED && session.quitRequested) return
                session.statusFlow.value = effect.status
                refreshNetworkStates()
            }
            is InboundEffect.OwnNickChanged -> refreshNetworkStates()
            is InboundEffect.EnsureBuffer -> {
                buffer(effect.ref)
                persistOpenedShell(effect.ref)
            }
            is InboundEffect.RemoveBuffer -> {
                if (isBouncerOwnedSession(session) && !channelOrder.isParted(effect.ref.storageKey)) {
                    loadedRosters.remove(effect.ref.storageKey)
                    rosterObservedAt.remove(effect.ref.storageKey)
                    updateBufferKey(effect.ref.storageKey) { current ->
                        current.copy(
                            joinState = JoinState.IDLE,
                            members = emptyList(),
                            memberPresence = emptyMap(),
                            typingUsers = emptyMap(),
                            cachedRoster = false,
                            rosterTruncated = false,
                            cachedTopic = current.cachedTopic || topicObservedAt.containsKey(current.key),
                            cachedModes = current.cachedModes || modesObservedAt.containsKey(current.key),
                        )
                    }
                } else {
                    removeBuffer(effect.ref.storageKey)
                    channelOrder.markParted(effect.ref.storageKey)
                    _orderState.value = channelOrder.snapshot()
                }
            }
            is InboundEffect.RenameBuffer -> renameConversation(session, effect.from, effect.to)
            is InboundEffect.AppendMessage -> appendChat(
                session = session,
                ref = effect.ref,
                sender = effect.sender,
                kind = effect.kind,
                text = effect.text,
                msgid = effect.msgid,
                timestampMs = effect.timestampMs,
                sentByUs = effect.sentByUs,
                highlightsMe = effect.highlightsMe,
                replyToMsgid = effect.replyToMsgid,
                playback = effect.playback,
                attachmentUrl = effect.attachmentUrl,
                reconcilePendingEcho = effect.reconcilePendingEcho,
                echoLabel = effect.echoLabel,
                senderAccount = effect.senderAccount,
                channelContext = effect.channelContext,
                historyContext = effect.historyContext,
            )
            is InboundEffect.SetTopic -> {
                val now = clock()
                topicObservedAt[effect.ref.storageKey] = now
                updateBuffer(effect.ref) { it.copy(topic = effect.topic?.let { topic -> redactPresentation(session, topic) }, cachedTopic = false, cachedStateAtMs = now) }
                persistSnapshot(session, effect.ref)
            }
            is InboundEffect.SetMembers -> {
                val now = clock()
                if (effect.complete) rosterObservedAt[effect.ref.storageKey] = now
                updateBuffer(effect.ref) { current ->
                    if (!effect.complete && current.cachedRoster) current else
                        current.copy(members = effect.members, memberPresence = effect.presence,
                            cachedRoster = !effect.complete && current.cachedRoster, cachedStateAtMs = now,
                            rosterTruncated = !effect.complete && current.rosterTruncated)
                }
                if (effect.complete) {
                    loadedRosters.add(effect.ref.storageKey)
                    persistSnapshot(session, effect.ref, saveRoster = true)
                }
            }
            is InboundEffect.SetModes -> {
                val now = clock()
                if (effect.complete) modesObservedAt[effect.ref.storageKey] = now
                updateBuffer(effect.ref) { current ->
                    if (!effect.complete && current.cachedModes) current else
                        current.copy(channelModes = effect.modes.mapValues { (_, parameters) -> parameters.map { redactPresentation(session, it) } },
                            cachedModes = !effect.complete && current.cachedModes,
                            cachedStateAtMs = now)
                }
                if (effect.complete) persistSnapshot(session, effect.ref)
            }
            is InboundEffect.SetJoinState -> {
                if (effect.state == JoinState.JOINED && isBouncerOwnedSession(session) && channelOrder.isParted(effect.ref.storageKey)) {
                    channelOrder.clearParted(effect.ref.storageKey)
                    _orderState.value = channelOrder.snapshot()
                }
                updateBuffer(effect.ref) { it.copy(joinState = effect.state) }
                if (effect.state == JoinState.JOINED && synchronized(selectionLock) { selectedStorageKey == effect.ref.storageKey }) {
                    ensureMembers(session.config.id, effect.ref.storageKey)
                }
            }
            is InboundEffect.ClearTyping -> updateBufferKey(effect.ref.storageKey) { it.copy(typingUsers = emptyMap()) }
            is InboundEffect.SetTyping -> updateBuffer(effect.ref) { buffer ->
                val now = clock()
                val fresh = buffer.typingUsers.filterValues { it > now }.toMutableMap()
                val expiresAt = effect.expiresAtMs
                if (expiresAt == null) fresh.remove(effect.nick) else fresh[effect.nick] = expiresAt
                buffer.copy(typingUsers = fresh)
            }
            is InboundEffect.ApplyReaction -> {
                val revisions = effect.msgids.associateWith { msgid ->
                    idGenerator.getAndIncrement().also { reactionRevisions["${effect.ref.storageKey}|$msgid"] = it }
                }
                updateBuffer(effect.ref) { current ->
                    val accepted = effect.msgids.filter { msgid -> current.messages.none { it.msgid == msgid && it.redacted } }
                    current.copy(reactions = applyReaction(current.reactions, effect.copy(msgids = accepted)))
                }
                enqueuePersistence {
                    val affectedRows = mutableSetOf<Long>()
                    try {
                        if (sessions[session.config.id] !== session || networkStore.byId(session.config.id) == null) return@enqueuePersistence
                        for (msgid in effect.msgids) {
                            messageStore.applyReaction(effect.ref, msgid, effect.sender, effect.emoji, effect.added, clock(),
                                onRetentionChanged = { affectedRows.addAll(it) })
                        }
                    } finally {
                        for ((msgid, revision) in revisions) clearReactionRevision(effect.ref, msgid, revision)
                        refreshReactionRetention(session.config.id, affectedRows, currentRef(effect.ref), effect.msgids.toSet())
                    }
                }
            }
            is InboundEffect.RedactMessage -> {
                updateBufferKey(effect.ref.storageKey) { current -> redactInBuffer(current, effect.msgid) }
                enqueuePersistence {
                    if (networkStore.byId(session.config.id) == null) return@enqueuePersistence
                    messageStore.redact(effect.ref, effect.msgid, clock())
                    refreshVisibleDurableState(effect.ref)
                    refreshUnread(effect.ref)
                }
            }
            is InboundEffect.ApplyReadMarker -> applyReadMarker(effect.ref, effect.timestampMs)
            is InboundEffect.WhoisCompleted -> completeWhois(session, effect.info)
            is InboundEffect.ChannelListed -> _channelList.update { it + effect.entry }
            is InboundEffect.ChannelListFinished -> _channelList.update { list -> list.sortedByDescending { it.users } }
            is InboundEffect.BouncerNetworksChanged -> refreshNetworkStates()
            is InboundEffect.NetworkFeaturesChanged -> refreshNetworkStates()
        }
    }

    private fun applyReadMarker(ref: ConversationRef, millis: Long) {
        readMarkers.reconcileRemote(ref.storageKey, millis)
        enqueuePersistence { refreshUnread(currentRef(ref)) }
    }

    private val selectionLock = Any()
    private var selectedStorageKey: String? = null
    private var pendingRenamedKey: String? = null
    private var selectionRevision = 0L

    public fun trackSelection(storageKey: String?) {
        val changed = synchronized(selectionLock) {
            if (selectedStorageKey == storageKey) false else {
                selectedStorageKey = storageKey
                selectionRevision += 1
                pendingRenamedKey = null
                true
            }
        }
        if (!changed) return
        if (requestedWhois != null && requestedWhoisOrigin != storageKey) dismissWhois()
        val token = synchronized(selectionLock) { selectionRevision }
        enqueuePersistence {
            if (synchronized(selectionLock) { selectionRevision != token }) return@enqueuePersistence
            val ref = storageKey?.let { _buffers.value[it]?.ref }
            if (storageKey != null && ref == null || storageKey == null && !_restorationReady.value) return@enqueuePersistence
            if (ref != null && networkStore.byId(ref.networkId) == null) return@enqueuePersistence
            offlineStore?.select(ref, clock())
            if (ref != null) loadCachedShell(ref)
            if (ref != null) loadCachedRoster(ref)
        }
    }

    public fun followRenamedSelection(storageKey: String?, bufferKeys: Set<String>): String? =
        synchronized(selectionLock) {
            if (pendingRenamedKey == null && selectedStorageKey != null && storageKey != null &&
                renamedConversations[storageKey]?.storageKey == selectedStorageKey
            ) return@synchronized null
            if (selectedStorageKey != storageKey) {
                selectedStorageKey = storageKey
                pendingRenamedKey = null
            }
            val renamed = pendingRenamedKey ?: storageKey?.let { renamedConversations[it]?.storageKey }
            if (renamed == null || (storageKey != null && storageKey in bufferKeys) || renamed !in bufferKeys) {
                return@synchronized null
            }
            selectedStorageKey = renamed
            pendingRenamedKey = null
            val ref = _buffers.value[renamed]?.ref
            enqueuePersistence {
                if (synchronized(selectionLock) { selectedStorageKey != renamed }) return@enqueuePersistence
                if (ref != null && networkStore.byId(ref.networkId) != null) offlineStore?.select(ref, clock())
            }
            renamed
        }

    private fun removeBuffer(storageKey: String) = withNetworkState(storageKey.substringBefore("|")) {
        history.remove(storageKey)
        synchronized(persistenceLock) { persistenceBatch += 1 }
        loadedRosters.remove(storageKey)
        reactionRevisions.keys.removeAll { it.startsWith("$storageKey|") }
        renamedConversations.entries.removeAll { it.key == storageKey || it.value.storageKey == storageKey }
        val ref = _buffers.value[storageKey]?.ref
        if (ref != null) enqueuePersistence { offlineStore?.deleteConversation(ref) }
        synchronized(selectionLock) {
            if (selectedStorageKey == storageKey || pendingRenamedKey == storageKey) pendingRenamedKey = null
            _buffers.update { it - storageKey }
        }
    }

    private inline fun withNetworkState(networkId: String, operation: () -> Unit) {
        val state = sessions[networkId]?.state
        if (state == null) operation() else synchronized(state) { operation() }
    }

    private fun rememberConversationRename(from: ConversationRef, to: ConversationRef) {
        if (from.storageKey == to.storageKey) return
        for ((key, destination) in renamedConversations) {
            if (destination.storageKey == from.storageKey) renamedConversations[key] = to
        }
        renamedConversations.remove(to.storageKey)
        renamedConversations[from.storageKey] = to
    }

    private fun renameConversation(session: Session, from: ConversationRef, to: ConversationRef) {
        val fromKey = from.storageKey
        val toKey = to.storageKey
        synchronized(selectionLock) {
            if (fromKey != toKey) beginStorageMigration(from.networkId)
            rememberConversationRename(from, to)
            if (fromKey != toKey && fromKey in _buffers.value &&
                (selectedStorageKey == fromKey || pendingRenamedKey == fromKey)
            ) {
                pendingRenamedKey = toKey
            }
            _buffers.update { current ->
                val existing = current[fromKey] ?: return@update current
                val destinationGaps = if (fromKey == toKey) emptyList()
                    else current[toKey]?.history?.gaps ?: restoredGaps(to)
                val movedHistory = if (destinationGaps.isEmpty()) existing.history
                    else existing.history.copy(gaps = (destinationGaps + existing.history.gaps).distinctBy { it.id })
                val moved = existing.copy(ref = to, displayName = ConversationNames.forRef(to), history = movedHistory)
                val merged = current[toKey]?.takeIf { fromKey != toKey }?.let { occupant ->
                    val preferredMoved = selectedStorageKey == fromKey
                    val messages = if (preferredMoved) mergeConversationMessages(moved.messages, occupant.messages, incomingCanonical = true)
                        else mergeConversationMessages(occupant.messages, moved.messages)
                    val marker = maxOf(moved.readAtMs, occupant.readAtMs, readMarkers.marker(toKey))
                    val boundary = if (mutes.isMuted(fromKey) || mutes.isMuted(toKey)) null else messages.asSequence()
                        .filter { it.countsAsUnread && it.timestampMs > marker }.minOfOrNull { it.timestampMs }
                    val sources = listOf(moved, occupant)
                    val topicSource = if ((topicObservedAt[fromKey] ?: Long.MIN_VALUE) >=
                        (topicObservedAt[toKey] ?: Long.MIN_VALUE)) moved else occupant
                    val modesSource = if ((modesObservedAt[fromKey] ?: Long.MIN_VALUE) >=
                        (modesObservedAt[toKey] ?: Long.MIN_VALUE)) moved else occupant
                    moved.copy(
                        messages = messages,
                        hasUnread = boundary != null,
                        unreadFromTimestampMs = boundary,
                        readAtMs = marker,
                        topic = topicSource.topic,
                        cachedTopic = topicSource.cachedTopic,
                        channelModes = modesSource.channelModes,
                        cachedModes = modesSource.cachedModes,
                        members = sources.flatMap { it.members }.distinctBy { session.state.fold(it.nick) },
                        memberPresence = occupant.memberPresence + moved.memberPresence,
                        cachedRoster = moved.cachedRoster || occupant.cachedRoster,
                        rosterTruncated = moved.rosterTruncated || occupant.rosterTruncated,
                        cachedStateAtMs = sources.mapNotNull { it.cachedStateAtMs }.maxOrNull(),
                        reactions = mergeReactions(sources),
                        unreadCount = maxOf(moved.unreadCount, occupant.unreadCount),
                        mentionCount = maxOf(moved.mentionCount, occupant.mentionCount),
                        mentionCountKnown = moved.mentionCountKnown && occupant.mentionCountKnown,
                        replyDraft = moved.replyDraft ?: occupant.replyDraft,
                    )
                } ?: moved
                current - fromKey + (toKey to merged)
            }
        }
        if (fromKey != toKey) {
            readMarkers.rename(fromKey, toKey)
            if (mutes.rename(fromKey, toKey)) _mutedState.value = mutes.all()
            channelOrder.rename(fromKey, toKey)
            _orderState.value = channelOrder.snapshot()
            history.rename(from, to)
            enqueuePersistence {
                var completed = false
                try {
                    migrateMessages(from, to)
                    completed = true
                } finally {
                    finishStorageMigration(from.networkId, completed)
                }
            }
            migrateCachedKeys(fromKey, toKey)
        }
        val autojoin = session.state.autojoin
        if (autojoin != session.config.autojoin) {
            session.config = session.config.copy(autojoin = autojoin)
            networkStore.update(session.config)
        }
        history.resume(session.config.id)
        persistSnapshot(session, to)
    }

    private fun currentRef(ref: ConversationRef): ConversationRef {
        _buffers.value[ref.storageKey]?.ref?.let { return it }
        val renamed = renamedConversations[ref.storageKey] ?: ref
        val mapping = sessions[ref.networkId]?.state?.casemapping ?: restoredCasemapping[ref.networkId] ?: return renamed
        val folded = mapping.fold(renamed.rawTarget)
        val current = if (renamed.normalizedTarget == folded) renamed else renamed.copy(normalizedTarget = folded)
        return _buffers.value[current.storageKey]?.ref ?: current
    }

    private fun migrateConversationMetadata(fromKey: String, toKey: String, preserveReadReplay: Boolean = false) {
        if (fromKey == toKey) return
        migrateCachedKeys(fromKey, toKey, preserveReadReplay)
        readMarkers.rename(fromKey, toKey)
        mutes.rename(fromKey, toKey)
        channelOrder.rename(fromKey, toKey)
        _mutedState.value = mutes.all()
        _orderState.value = channelOrder.snapshot()
    }

    private fun migrateCachedKeys(fromKey: String, toKey: String, preserveReadReplay: Boolean = false) {
        for (observations in listOf(topicObservedAt, rosterObservedAt, modesObservedAt)) {
            observations.remove(fromKey)?.let { from ->
                observations.merge(toKey, from, ::maxOf)
            }
        }
        if (loadedRosters.remove(fromKey)) loadedRosters.add(toKey)
        for ((key, revision) in reactionRevisions) {
            if (!key.startsWith("$fromKey|")) continue
            reactionRevisions.remove(key, revision)
            reactionRevisions.merge("$toKey|${key.removePrefix("$fromKey|")}", revision, ::maxOf)
        }
        metadataRequests.remove(fromKey)
        sessions[toKey.substringBefore("|")]?.replayedReadCursors?.let { cursors ->
            val previous = cursors.remove(fromKey)
            if (preserveReadReplay && previous != null) cursors.merge(toKey, previous, ::maxOf)
        }
        if (_restoredSelection.value == fromKey) _restoredSelection.value = toKey
    }

    private suspend fun migrateMessages(from: ConversationRef, to: ConversationRef) {
        if (networkStore.byId(from.networkId) == null) return
        messageStore.renameConversation(from, to) { removed, retained ->
            synchronized(selectionLock) {
                for ((key, rowId) in persistedRowsByLocalId) if (rowId == removed) persistedRowsByLocalId[key] = retained
                updateAllBuffersForNetwork(from.networkId) { buffer ->
                    buffer.copy(
                        messages = buffer.messages.map { message ->
                            if (message.storedRowId == removed || message.localReplyParentRowId == removed) {
                                message.copy(
                                    storedRowId = if (message.storedRowId == removed) retained else message.storedRowId,
                                    localReplyParentRowId = if (message.localReplyParentRowId == removed) retained else message.localReplyParentRowId,
                                )
                            } else message
                        }.let { mergeConversationMessages(emptyList(), it) },
                        replyDraft = buffer.replyDraft?.let { draft ->
                            if (draft.storedRowId == removed || draft.localReplyParentRowId == removed) draft.copy(
                                storedRowId = if (draft.storedRowId == removed) retained else draft.storedRowId,
                                localReplyParentRowId = if (draft.localReplyParentRowId == removed) retained else draft.localReplyParentRowId,
                            ) else draft
                        },
                    )
                }
            }
        }
        offlineStore?.renameConversation(from, to, sessions[to.networkId]?.state?.casemapping ?: CaseMapping.RFC1459)
        loadCachedShell(to)
        if (_buffers.value[to.storageKey]?.cachedRoster == true) {
            loadedRosters.remove(to.storageKey)
            loadCachedRoster(to)
        }
        refreshVisibleDurableState(to)
        refreshUnread(to)
    }

    private fun reconcileCaseMapping(session: Session) = synchronized(selectionLock) {
        val mapping = session.state.casemapping
        restoredCasemapping[session.config.id] = mapping
        _profiles.update { profiles ->
            val current = profiles[session.config.id] ?: return@update profiles
            val rekeyed = session.state.users.values.mapNotNull { user ->
                val previous = current.byFoldedNick[current.casemapping.fold(user.nick)]
                mergeObservedProfile(previous, user) { redactPresentation(session, it) }?.let { mapping.fold(user.nick) to it }
            }.toMap()
            profiles + (session.config.id to current.copy(casemapping = mapping, byFoldedNick = rekeyed))
        }
        session.publishedProfileKeys = session.state.users.profiles.keys.toSet()
        loadedUsers.removeAll { it.substringBefore("|") == session.config.id }
        val existing = _buffers.value.values.filter { it.ref.networkId == session.config.id }
        val refs = existing.associate { it.key to it.ref }
        val observedTopics = existing.associate { it.key to topicObservedAt[it.key] }
        val observedModes = existing.associate { it.key to modesObservedAt[it.key] }
        if (session.storedMappingKnown) {
            beginStorageMigration(session.config.id)
            val order = channelOrder.snapshot()
            val metadataKeys = readMarkers.all().keys + mutes.all() + order.pinnedKeys +
                order.groups.flatMap { it.memberKeys } + order.partedKeys + refs.keys
            for (key in metadataKeys) {
                if (key.substringBefore("|") != session.config.id) continue
                val raw = refs[key]?.rawTarget ?: key.substringAfter("|")
                migrateConversationMetadata(key, "${session.config.id}|${mapping.fold(raw)}", preserveReadReplay = true)
            }
            enqueuePersistence {
                var completed = false
                try {
                    if (networkStore.byId(session.config.id) == null) return@enqueuePersistence
                    val persisted = messageStore.knownConversations(session.config.id)
                    val cachedRefs = offlineStore?.shells(session.config.id).orEmpty().associateBy { it.ref.storageKey }
                    for (from in persisted) {
                        val raw = refs[from.storageKey]?.rawTarget ?: cachedRefs[from.storageKey]?.ref?.rawTarget ?: from.rawTarget
                        val to = from.copy(rawTarget = raw, normalizedTarget = mapping.fold(raw))
                        migrateMessages(from, to)
                        synchronized(session.state) {
                            if (sessions[session.config.id] === session) migrateConversationMetadata(from.storageKey, to.storageKey, preserveReadReplay = true)
                        }
                    }
                    offlineStore?.rekeyNetwork(session.config.id, mapping)
                    completed = true
                } finally {
                    finishStorageMigration(session.config.id, completed)
                }
            }
        }
        val groups = existing.groupBy { "${session.config.id}|${mapping.fold(it.ref.rawTarget)}" }
        val replacement = groups.mapValues { (_, buffers) ->
            val first = buffers.firstOrNull { it.ref.normalizedTarget == mapping.fold(it.ref.rawTarget) } ?: buffers.first()
            val preferred = buffers.firstOrNull { it.key == selectedStorageKey } ?: first
            val ref = first.ref.copy(normalizedTarget = mapping.fold(first.ref.rawTarget))
            var messages = preferred.messages
            for (buffer in buffers) if (buffer !== preferred) {
                messages = mergeConversationMessages(messages, buffer.messages, incomingCanonical = buffer === first)
            }
            val marker = maxOf(buffers.maxOf { it.readAtMs }, readMarkers.marker(ref.storageKey))
            val boundary = if (mutes.isMuted(ref.storageKey)) null else messages
                .asSequence().filter { it.countsAsUnread && it.timestampMs > marker }.minOfOrNull { it.timestampMs }
            val topicSource = buffers.maxBy { observedTopics[it.key] ?: Long.MIN_VALUE }
            val modesSource = buffers.maxBy { observedModes[it.key] ?: Long.MIN_VALUE }
            first.copy(
                ref = ref,
                messages = messages,
                topic = topicSource.topic,
                cachedTopic = topicSource.cachedTopic,
                channelModes = modesSource.channelModes,
                cachedModes = modesSource.cachedModes,
                cachedRoster = buffers.any { it.cachedRoster },
                rosterTruncated = buffers.any { it.rosterTruncated },
                cachedStateAtMs = buffers.mapNotNull { it.cachedStateAtMs }.maxOrNull(),
                unreadCount = buffers.maxOf { it.unreadCount },
                mentionCount = buffers.maxOf { it.mentionCount },
                mentionCountKnown = buffers.all { it.mentionCountKnown },
                readAtMs = marker,
                hasUnread = boundary != null,
                unreadFromTimestampMs = boundary,
                members = buffers.flatMap { it.members }.distinctBy { mapping.fold(it.nick) },
                memberPresence = buffers.fold(emptyMap()) { acc, buffer -> acc + buffer.memberPresence },
                typingUsers = buffers.fold(emptyMap()) { acc, buffer -> acc + buffer.typingUsers },
                reactions = mergeReactions(buffers),
                replyDraft = buffers.firstNotNullOfOrNull { it.replyDraft },
                history = first.history.copy(gaps = buffers.flatMap { it.history.gaps }.distinctBy { it.id }),
            )
        }
        val selected = pendingRenamedKey ?: selectedStorageKey
        selected?.let { refs[it] }?.let { ref ->
            val key = "${ref.networkId}|${mapping.fold(ref.rawTarget)}"
            if (selected != key) pendingRenamedKey = key
        }
        for (previous in existing) {
            val destination = replacement["${session.config.id}|${mapping.fold(previous.ref.rawTarget)}"]?.ref ?: continue
            rememberConversationRename(previous.ref, destination)
        }
        _buffers.update { current -> current - refs.keys + replacement }
        restoreKnownConversations(session)
        existing
    }.also { previous ->
        for (buffer in previous) {
            val ref = currentRef(buffer.ref)
            if (ref.storageKey != buffer.ref.storageKey) history.rename(buffer.ref, ref)
        }
        history.resume(session.config.id)
    }

    private fun mergeReactions(buffers: List<ConversationBuffer>): Map<String, Map<String, Set<String>>> {
        val redacted = buffers.asSequence().flatMap { it.messages.asSequence() }
            .filter { it.redacted }.mapNotNull { it.msgid }.toSet()
        val merged = LinkedHashMap<String, MutableMap<String, Set<String>>>()
        for (buffer in buffers) {
            for ((msgid, reactions) in buffer.reactions) {
                if (msgid in redacted) continue
                val target = merged.getOrPut(msgid) { LinkedHashMap() }
                for ((emoji, senders) in reactions) target[emoji] = target[emoji].orEmpty() + senders
            }
        }
        return merged
    }

    private fun ensureBaseBuffers(session: Session) {
        buffer(ConversationRef.server(session.config.id))
        if (managementMode(session) == NetworkMode.SOJU && session.config.bouncerBinding == null) return
        for (channel in session.config.autojoin) {
            val ref = ConversationRef.channel(session.config.id, channel, session.state.casemapping)
            if (!channelOrder.isParted(ref.storageKey)) {
                updateBuffer(ref) {
                    it.copy(joinState = if (session.recovery.desiredConnection && upstreamEligible(session.config)) JoinState.JOINING else JoinState.IDLE)
                }
            }
        }
    }


    public data class RawFrame(public val outbound: Boolean, public val line: String)

    private val rawLogs = ConcurrentHashMap<String, ArrayDeque<RawFrame>>()
    private val _rawLogVersion = MutableStateFlow(0)
    public val rawLogVersion: StateFlow<Int> = _rawLogVersion.asStateFlow()

    public fun ingestRaw(networkId: String, outbound: Boolean, line: String) {
        if (networkStore.byId(networkId) == null) return
        val safeLine = sessions[networkId]?.let { redactPresentation(it, line) } ?: line
        val deque = rawLogs.getOrPut(networkId) { ArrayDeque() }
        if (networkStore.byId(networkId) == null) {
            rawLogs.remove(networkId, deque)
            return
        }
        synchronized(deque) {
            deque.addLast(RawFrame(outbound, safeLine))
            while (deque.size > 400) deque.removeFirst()
        }
        _rawLogVersion.update { it + 1 }
    }

    public fun rawLog(networkId: String): List<RawFrame> {
        val deque = rawLogs[networkId] ?: return emptyList()
        return synchronized(deque) { deque.toList() }
    }

    public data class ChannelListEntry(public val name: String, public val users: Int, public val topic: String)

    private val _channelList = MutableStateFlow<List<ChannelListEntry>>(emptyList())
    public val channelList: StateFlow<List<ChannelListEntry>> = _channelList.asStateFlow()

    public fun startChannelList(networkId: String) {
        _channelList.value = emptyList()
        for (session in sessions.values) {
            synchronized(session.state) { session.state.listingChannels = session.config.id == networkId }
        }
        sessions[networkId]?.let { sendLabeled(it, ConversationRef.server(networkId), LabeledCommand.LIST, "LIST") }
    }

    public fun deleteMessage(networkId: String, storageKey: String, msgid: String) {
        val session = sessions[networkId] ?: return
        synchronized(session.state) {
            if (sessions[networkId] !== session || !session.state.registered ||
                session.statusFlow.value != ConnectionStatus.REGISTERED
            ) return
            val buffer = _buffers.value[storageKey]?.takeIf { it.ref.networkId == networkId } ?: return
            sendLabeled(session, buffer.ref, LabeledCommand.RAW, "REDACT ${buffer.ref.rawTarget} $msgid")
        }
    }

    private fun updateAllBuffersForNetwork(networkId: String, transform: (ConversationBuffer) -> ConversationBuffer) {
        _buffers.update { current ->
            current.mapValues { (_, buffer) ->
                if (buffer.ref.networkId == networkId) transform(buffer) else buffer
            }
        }
    }

    public fun ensureMembers(networkId: String, storageKey: String) {
        val current = _buffers.value[storageKey] ?: return
        if (current.ref.networkId != networkId || current.ref.kind != ConversationKind.CHANNEL) return
        enqueuePersistence { loadCachedRoster(current.ref) }
        val session = sessions[networkId] ?: return
        synchronized(session.state) {
            if (!session.state.registered || session.statusFlow.value != ConnectionStatus.REGISTERED ||
                current.joinState != JoinState.JOINED
            ) return
            val channel = session.state.channel(storageKey)
            val needsMembers = current.cachedRoster || channel?.membersComplete != true
            val needsPresence = current.members.any { current.memberPresence[it.nick]?.away == null }
            if (needsMembers || needsPresence) {
                val request = if (current.cachedRoster) "NAMES" else "WHO"
                if (metadataRequests.putIfAbsent(storageKey, request) == null) {
                    if (request == "NAMES") sendRaw(session, "NAMES ${current.ref.rawTarget}") else {
                        val query = if (session.state.hasWhox) WHOX_QUERY else ""
                        sendRaw(session, "WHO ${current.ref.rawTarget} $query".trimEnd())
                    }
                }
            }
            if (current.cachedTopic) sendRaw(session, "TOPIC ${current.ref.rawTarget}")
            if (current.cachedModes || channel?.modesComplete != true) sendRaw(session, "MODE ${current.ref.rawTarget}")
        }
    }

    public fun react(networkId: String, storageKey: String, msgid: String, emoji: String): Unit = withNetworkState(networkId) {
        val session = sessions[networkId] ?: return@withNetworkState
        if (!session.state.registered || session.statusFlow.value != ConnectionStatus.REGISTERED) return@withNetworkState
        val buffer = _buffers.value[storageKey]?.takeIf { it.ref.networkId == networkId } ?: return@withNetworkState
        if (!buffer.messages.any { it.msgid == msgid && !it.redacted } || emoji.isBlank() ||
            emoji.length > 64 || emoji.any { it.isISOControl() }) return@withNetworkState
        val policy = session.state.clientTagPolicy
        val removing = ownReaction(buffer, msgid, emoji, session.state.ownNick)
        val reactionTag = (if (removing) policy.unreact else policy.react)
        val refsTag = policy.reactionReference
        if (!policy.reactionsAvailable || reactionTag == null || refsTag == null) {
            _operationError.value = "Reactions are unavailable: this server has not enabled the required client tags."
            return@withNetworkState
        }
        val frame = IrcMessage(tags = buildMap {
            put(reactionTag, emoji)
            put(refsTag, msgid)
            policy.refs?.let { put(it, msgid) }
        }, command = "TAGMSG", parameters = listOf(buffer.ref.rawTarget))
        if (session.reconnector?.send(frame) != true) {
            _operationError.value = "Reaction was not sent. Reconnect and try again."
            return@withNetworkState
        }
        val own = session.state.ownNick
        var reactionVerb: String? = null
        var reactionRevision: Long? = null
        updateBufferKey(storageKey) { current ->
            var reactions = current.reactions
            if (!current.messages.any { it.msgid == msgid && !it.redacted }) return@updateBufferKey current
            val perMessage = (reactions[msgid] ?: emptyMap()).toMutableMap()
            when {
                removing -> {
                    val remaining = perMessage[emoji].orEmpty().toMutableSet().apply { remove(own) }
                    reactionVerb = "unreact"
                    if (remaining.isEmpty()) perMessage.remove(emoji) else perMessage[emoji] = remaining
                }
                else -> {
                    reactionVerb = "react"
                    perMessage[emoji] = perMessage[emoji].orEmpty() + own
                }
            }
            reactionRevision = idGenerator.getAndIncrement()
            reactionRevision?.let { reactionRevisions["${buffer.ref.storageKey}|$msgid"] = it }
            reactions = if (perMessage.isEmpty()) reactions - msgid else reactions + (msgid to perMessage.toMap())
            current.copy(reactions = reactions)
        }
        reactionVerb?.let { verb ->
            enqueuePersistence {
                var affectedRows: Set<Long> = emptySet()
                try {
                    if (sessions[networkId] !== session || networkStore.byId(networkId) == null) return@enqueuePersistence
                    messageStore.applyReaction(buffer.ref, msgid, own, emoji, verb == "react", clock(),
                        onRetentionChanged = { affectedRows = it })
                } finally {
                    reactionRevision?.let { clearReactionRevision(buffer.ref, msgid, it) }
                    refreshReactionRetention(networkId, affectedRows, currentRef(buffer.ref), setOf(msgid))
                }
            }
        }
    }

    public fun setReplyDraft(networkId: String, storageKey: String, message: ChatMessage?) {
        updateBufferKey(storageKey) { buffer ->
            if (buffer.ref.networkId != networkId) buffer else buffer.copy(
                replyDraft = message?.let { requested ->
                    buffer.messages.firstOrNull { it.localId == requested.localId }?.takeUnless { it.redacted }
                },
            )
        }
    }

    public fun relayPresentation(networkId: String, message: ChatMessage): ChatMessage {
        if (message.redacted || message.sentByUs || message.kind != MessageKind.PRIVMSG) return message
        val mapping = sessions[networkId]?.state?.casemapping ?: restoredCasemapping[networkId] ?: CaseMapping.RFC1459
        val relay = relayStore?.parse(networkId, message.sender, message.text, mapping) ?: return message
        return message.copy(relayedSender = relay.sender, relaySource = relay.source, relayedBody = relay.body)
    }

    public fun directMessageKey(networkId: String, fromKey: String, nick: String): String = synchronized(sessionLifecycleLock) {
        val session = sessions[networkId]
        synchronized(session?.state ?: selectionLock) {
            val casemapping = session?.state?.casemapping ?: restoredCasemapping[networkId] ?: CaseMapping.RFC1459
            val ref = ConversationRef.directMessage(networkId, nick, casemapping)
            if (networkStore.byId(networkId) != null) {
                channelOrder.clearParted(ref.storageKey)
                _orderState.value = channelOrder.snapshot()
                buffer(ref)
            }
            persistOpenedShell(ref)
            ref.storageKey
        }
    }

    public fun ensureConversation(networkId: String, conversation: String): String = synchronized(sessionLifecycleLock) {
        val session = sessions[networkId]
        synchronized(session?.state ?: selectionLock) {
            val casemapping = session?.state?.casemapping ?: restoredCasemapping[networkId] ?: CaseMapping.RFC1459
            val ref = when {
                conversation == ConversationRef.SERVER_TARGET -> ConversationRef.server(networkId)
                conversation.firstOrNull()?.let { it in "#&" } == true ->
                    ConversationRef.channel(networkId, conversation, casemapping)
                else -> ConversationRef.directMessage(networkId, conversation, casemapping)
            }
            if (networkStore.byId(networkId) != null) {
                if (ref.kind == ConversationKind.DIRECT_MESSAGE) {
                    channelOrder.clearParted(ref.storageKey)
                    _orderState.value = channelOrder.snapshot()
                }
                buffer(ref)
            }
            persistOpenedShell(ref)
            ref.storageKey
        }
    }

    public fun openChannel(networkId: String, channel: String): String? {
        val session = sessions[networkId] ?: return null
        val ref = ConversationRef.channel(networkId, channel, session.state.casemapping)
        val joinState = _buffers.value[ref.storageKey]?.joinState
        if (joinState != JoinState.JOINED && joinState != JoinState.JOINING) {
            val serverKey = ConversationRef.server(networkId).storageKey
            if (!sendText(networkId, serverKey, "/join $channel")) return null
        }
        return ref.storageKey
    }

    public fun memberAction(networkId: String, storageKey: String, action: dev.brentdevs.yardhal.ui.screens.MemberAction, nick: String) {
        when (action) {
            dev.brentdevs.yardhal.ui.screens.MemberAction.WHOIS -> sendText(networkId, storageKey, "/whois $nick")
            dev.brentdevs.yardhal.ui.screens.MemberAction.KICK -> sendText(networkId, storageKey, "/kick $nick")
            dev.brentdevs.yardhal.ui.screens.MemberAction.BAN -> sendText(networkId, storageKey, "/ban ${nick}!*@*")
            dev.brentdevs.yardhal.ui.screens.MemberAction.BAN_ACCOUNT -> {
                val session = sessions[networkId] ?: return
                val buffer = _buffers.value[storageKey] ?: return
                if (buffer.ref.kind != ConversationKind.CHANNEL) return
                val mask = synchronized(session.state) { session.state.accountBanMask(nick) } ?: return
                sendRaw(session, "MODE ${buffer.ref.rawTarget} +b $mask")
            }
            dev.brentdevs.yardhal.ui.screens.MemberAction.IGNORE -> {
                ignoreStore?.add(nick)
                sessions[networkId]?.let { session ->
                    appendSystem(
                        session,
                        _buffers.value[storageKey]?.ref ?: ConversationRef.server(networkId),
                        "Ignoring $nick",
                    )
                }
            }
            dev.brentdevs.yardhal.ui.screens.MemberAction.MESSAGE -> Unit
        }
    }

    private val _mutedState = MutableStateFlow(mutes.all())
    public val mutedState: StateFlow<Set<String>> = _mutedState.asStateFlow()

    public suspend fun searchMessages(
        raw: String,
        networkId: String? = null,
        conversation: String? = null,
    ): List<dev.brentdevs.yardhal.core.data.FtsHit> =
        messageStore.search(raw, networkId = networkId, conversation = conversation)

    public fun openSearchHit(hit: dev.brentdevs.yardhal.core.data.FtsHit): String = synchronized(sessionLifecycleLock) {
        synchronized(sessions[hit.networkId]?.state ?: selectionLock) {
            val storageKey = ensureConversation(hit.networkId, hit.conversation)
            val ref = _buffers.value[storageKey]?.ref
            if (ref != null) enqueuePersistence { loadSearchContext(ref, hit) }
            storageKey
        }
    }

    private suspend fun loadSearchContext(ref: ConversationRef, hit: dev.brentdevs.yardhal.core.data.FtsHit) {
        if (deferStorageRead(ref.networkId) { loadSearchContext(ref, hit) }) return
        val target = currentRef(ref)
        if (networkStore.byId(target.networkId) == null) return
        val session = sessions[target.networkId]
        val context = messageStore.around(target, hit.rowId, hit.timestampMs)
        if (deferStorageRead(ref.networkId) { loadSearchContext(ref, hit) }) return
        if (networkStore.byId(target.networkId) == null) return
        if (sessions[target.networkId] !== session) {
            enqueuePersistence { loadSearchContext(ref, hit) }
            return
        }
        mergeDurableContext(target, context)
    }

    private val _orderState = MutableStateFlow(channelOrder.snapshot())
    public val orderState: StateFlow<dev.brentdevs.yardhal.core.data.ChannelOrderState> = _orderState.asStateFlow()

    public fun togglePin(storageKey: String) {
        channelOrder.togglePin(storageKey)
        _orderState.value = channelOrder.snapshot()
    }

    public fun createGroup(name: String): String {
        val id = "g-" + java.util.UUID.randomUUID()
        channelOrder.createGroup(id, name)
        _orderState.value = channelOrder.snapshot()
        return id
    }

    public fun renameGroup(id: String, name: String) {
        channelOrder.renameGroup(id, name)
        _orderState.value = channelOrder.snapshot()
    }

    public fun deleteGroup(id: String) {
        channelOrder.deleteGroup(id)
        _orderState.value = channelOrder.snapshot()
    }

    public fun addToGroup(groupId: String, storageKey: String) {
        channelOrder.removeFromEveryGroup(storageKey)
        channelOrder.addToGroup(groupId, storageKey)
        _orderState.value = channelOrder.snapshot()
    }

    public fun removeFromGroup(storageKey: String) {
        channelOrder.removeFromEveryGroup(storageKey)
        _orderState.value = channelOrder.snapshot()
    }

    public fun retryJoin(networkId: String, storageKey: String) {
        val session = sessions[networkId] ?: return
        if (session.statusFlow.value != ConnectionStatus.REGISTERED || !session.state.registered) return
        val buffer = _buffers.value[storageKey] ?: return
        if (buffer.ref.kind != ConversationKind.CHANNEL) return
        channelOrder.clearParted(storageKey)
        _orderState.value = channelOrder.snapshot()
        updateBufferKey(storageKey) { it.copy(joinState = JoinState.JOINING) }
        sendRaw(session, "JOIN ${buffer.ref.rawTarget}")
    }

    private fun markJoining(ref: ConversationRef) {
        updateBuffer(ref) { it.copy(joinState = JoinState.JOINING) }
    }

    public fun toggleMute(storageKey: String) {
        if (!mutes.unmute(storageKey)) mutes.mute(storageKey)
        _mutedState.value = mutes.all()
    }

    public fun leaveConversation(networkId: String, storageKey: String) {
        val buffer = _buffers.value[storageKey] ?: return
        if (buffer.ref.kind == ConversationKind.CHANNEL) {
            sendText(networkId, storageKey, "/part ${buffer.ref.rawTarget}")
        }
        removeBuffer(storageKey)
        sessions[networkId]?.let { session -> synchronized(session.state) { session.state.forgetChannel(storageKey) } }
        channelOrder.markParted(storageKey)
        _orderState.value = channelOrder.snapshot()
    }

    public fun secureUploadTransport(networkId: String): Boolean {
        val config = networkStore.byId(networkId) ?: return true
        val transport = config.bouncerBinding?.let { networkStore.byId(it.parentId) ?: return true } ?: config
        val session = sessions[networkId]
        val nowSeconds = clock() / 1_000
        return config.tls || transport.tls || session?.effective?.tls == true || session?.stsUpgradePort != null ||
            stsPolicies.load(config.host)?.let { !it.isExpired(nowSeconds) } == true ||
            stsPolicies.load(transport.host)?.let { !it.isExpired(nowSeconds) } == true
    }

    public fun negotiatedFilehost(networkId: String): dev.brentdevs.yardhal.core.data.NegotiatedFilehost? {
        val session = sessions[networkId] ?: return null
        val endpoint = session.state.filehostEndpoint ?: return null
        val effective = session.effective ?: return null
        return dev.brentdevs.yardhal.core.data.NegotiatedFilehost(
            endpointUrl = endpoint,
            ircConnectionIsTls = secureUploadTransport(networkId),
            saslUser = effective.saslAuthcid,
            saslPassword = effective.saslPassword,
        )
    }

    private fun appendSystem(session: Session, ref: ConversationRef, text: String, kind: MessageKind = MessageKind.SYSTEM) {
        appendChat(
            session = session,
            ref = ref,
            sender = "",
            kind = kind,
            text = text,
            msgid = null,
            timestampMs = clock(),
            sentByUs = false,
            highlightsMe = false,
        )
    }

    private fun appendChat(
        session: Session,
        ref: ConversationRef,
        sender: String,
        kind: MessageKind,
        text: String,
        msgid: String?,
        timestampMs: Long,
        sentByUs: Boolean,
        highlightsMe: Boolean,
        replyToMsgid: String? = null,
        playback: Boolean = false,
        attachmentUrl: String? = null,
        pendingEcho: Boolean = false,
        reconcilePendingEcho: Boolean = false,
        echoLabel: String? = null,
        senderAccount: String? = null,
        channelContext: String? = null,
        historyContext: Boolean = false,
        optimistic: Boolean = false,
        quoteParent: ChatMessage? = null,
        attachmentName: String? = null,
        attachmentMimeType: String? = null,
        attachmentSizeBytes: Long? = null,
        attachmentWidth: Int? = null,
        attachmentHeight: Int? = null,
    ) {
        if (sessions[ref.networkId] !== session || networkStore.byId(ref.networkId) == null) return
        val presentedText = redactPresentation(session, text)
        val newLocalId = idGenerator.getAndIncrement()
        val muted = mutes.isMuted(ref.storageKey)
        var appended = false
        var persistedLocalId = newLocalId
        var persistedTimestampMs = timestampMs
        val entry = ChatMessage(
            localId = newLocalId,
            sender = sender,
            kind = kind,
            text = presentedText,
            timestampMs = timestampMs,
            sentByUs = sentByUs,
            highlightsMe = highlightsMe,
            highlightsKnown = !playback && !historyContext,
            msgid = msgid,
            replyToMsgid = replyToMsgid,
            attachmentUrl = attachmentUrl?.let { redactPresentation(session, it) },
            playback = playback,
            pendingEcho = pendingEcho,
            echoLabel = echoLabel.takeIf { pendingEcho },
            senderAccount = senderAccount?.let { redactPresentation(session, it) },
            channelContext = channelContext,
            historyContext = historyContext,
            localReplyParentRowId = quoteParent?.storedRowId,
            replyPreview = quoteParent?.let { ReplyPreview(it.sender, it.text, it.attachmentUrl, it.redacted) },
            attachmentName = attachmentName?.let { redactPresentation(session, it) },
            attachmentMimeType = attachmentMimeType,
            attachmentSizeBytes = attachmentSizeBytes,
            attachmentWidth = attachmentWidth,
            attachmentHeight = attachmentHeight,
        )
        updateBuffer(ref) { buffer ->
            appended = false
            if (reconcilePendingEcho && !playback) {
                val optimisticMessage = buffer.messages.firstOrNull {
                    it.pendingEcho && if (echoLabel != null) it.echoLabel == echoLabel else it.kind == kind && it.text == presentedText
                }
                reconcileEcho(buffer, kind, presentedText, echoLabel, msgid, timestampMs, attachmentUrl, senderAccount)?.let {
                    persistedLocalId = optimisticMessage?.localId ?: persistedLocalId
                    return@updateBuffer it
                }
            }
            val byMsgid = if (msgid == null) -1 else buffer.messages.indexOfFirst { it.msgid == msgid }
            val byContent = if (byMsgid >= 0 || !playback) -1 else {
                buffer.messages.uniqueHistoryIndex { message ->
                    !message.pendingEcho && (msgid == null || message.msgid == null) && message.sender == sender &&
                        message.kind == kind && message.text == presentedText && message.timestampMs == timestampMs
                }
            }
            val duplicateIndex = if (byMsgid >= 0) byMsgid else byContent
            if (duplicateIndex >= 0) {
                val existing = buffer.messages[duplicateIndex]
                persistedLocalId = existing.localId
                val updated = mergeChatMessageMetadata(existing, entry)
                if (updated == existing) return@updateBuffer buffer
                val messages = buffer.messages.toMutableList()
                messages[duplicateIndex] = updated
                return@updateBuffer buffer.copy(messages = messages)
            }
            appended = true
            val cursor = readMarkers.cursor(ref.storageKey)
            val countsAsUnread = entry.countsAsUnread && !muted &&
                (timestampMs > cursor.timestampMs || timestampMs == cursor.timestampMs && cursor.rowId < Long.MAX_VALUE)
            val boundary = if (countsAsUnread) {
                val existing = buffer.unreadFromTimestampMs
                if (existing == null || existing <= buffer.readAtMs) timestampMs else minOf(existing, timestampMs)
            } else {
                buffer.unreadFromTimestampMs
            }
            val messages = appendChronologically(buffer.messages, entry, optimistic)
            if (optimistic) persistedTimestampMs = messages.last().timestampMs
            buffer.copy(
                messages = messages,
                hasUnread = buffer.hasUnread || countsAsUnread,
                unreadCount = buffer.unreadCount + if (countsAsUnread) 1 else 0,
                mentionCount = buffer.mentionCount + if (countsAsUnread && entry.highlightsMe) 1 else 0,
                unreadFromTimestampMs = boundary,
            )
        }
        val localId = if (msgid == null) {
            persistedLocalId
        } else {
            _buffers.value[ref.storageKey]?.messages?.firstOrNull { it.msgid == msgid }?.localId ?: persistedLocalId
        }
        val snapshot = _buffers.value[ref.storageKey]?.messages?.firstOrNull { it.localId == localId }
        if (snapshot != null) persistAsync(session, ref, snapshot.copy(timestampMs = persistedTimestampMs),
            reconcilePendingEcho || sentByUs && msgid != null, quoteParent?.localId, optimistic)
        if (appended && highlightsMe && !sentByUs && !playback && !historyContext && !muted) {
            notifier.onHighlight(session.config.name, sender, ConversationNames.forRef(ref), presentedText)
        }
    }

    private fun persistAsync(
        session: Session,
        ref: ConversationRef,
        message: ChatMessage,
        healPendingEcho: Boolean,
        quoteParentLocalId: Long?,
        freshLocal: Boolean,
    ) {
        val target = ref
        enqueuePersistence {
            if (networkStore.byId(ref.networkId) == null) return@enqueuePersistence
            val active = _buffers.value.values.firstOrNull { current ->
                current.ref.networkId == ref.networkId && current.messages.any { it.localId == message.localId }
            }
            if (sessions[ref.networkId] !== session && active == null) return@enqueuePersistence
            val visibleMessage = active?.messages?.firstOrNull { it.localId == message.localId }
            val quoteRowId = quoteParentLocalId?.let { parentId ->
                active?.messages?.firstOrNull { it.localId == parentId }?.storedRowId
                    ?: persistedRowsByLocalId["${ref.networkId}|$parentId"]
            } ?: visibleMessage?.localReplyParentRowId ?: message.localReplyParentRowId
            val stored = StoredMessage(
                networkId = target.networkId,
                conversation = target,
                msgid = message.msgid,
                senderNick = message.sender,
                senderUser = null,
                senderHost = null,
                kind = message.kind,
                text = message.text,
                sentByUs = message.sentByUs,
                timestampMs = message.timestampMs,
                channelContext = message.channelContext,
                historyContext = message.historyContext,
                highlightsMe = message.highlightsMe,
                highlightsKnown = message.highlightsKnown,
                playback = message.playback,
                pendingEcho = message.pendingEcho,
                replyToMsgid = message.replyToMsgid,
                replyParentRowId = quoteRowId,
                attachmentUrl = message.attachmentUrl,
                attachmentName = message.attachmentName,
                attachmentMimeType = message.attachmentMimeType,
                attachmentSizeBytes = message.attachmentSizeBytes,
                attachmentWidth = message.attachmentWidth,
                attachmentHeight = message.attachmentHeight,
                senderAccount = message.senderAccount,
                redacted = message.redacted,
                reactionsTruncated = message.reactionsTruncated,
            )
            val existingRowId = persistedRowsByLocalId["${ref.networkId}|${message.localId}"] ?: message.storedRowId
                ?: active?.messages?.firstOrNull { it.localId == message.localId }?.storedRowId
            val recordedRowId = when {
                healPendingEcho && existingRowId != null -> messageStore.healEcho(target, existingRowId, stored)
                freshLocal -> messageStore.recordLocalEcho(stored)
                else -> messageStore.recordWithRowId(stored)
            }
            val persisted = if (recordedRowId == null && !freshLocal) messageStore.findStoredMessage(stored) else null
            val rowId = recordedRowId ?: persisted?.rowId
            if (rowId == null) {
                if ((stored.historyContext || stored.playback) && sessions[ref.networkId] === session &&
                    networkStore.byId(ref.networkId) != null) {
                    updateBufferKey(target.storageKey) { current ->
                        current.copy(
                            messages = current.messages.filterNot { it.localId == message.localId },
                            reactions = if (message.msgid == null) current.reactions else current.reactions - message.msgid,
                        )
                    }
                }
                return@enqueuePersistence
            }
            if (message.pendingEcho) persistedRowsByLocalId["${ref.networkId}|${message.localId}"] = rowId
            else persistedRowsByLocalId.remove("${ref.networkId}|${message.localId}")
            if (networkStore.byId(ref.networkId) == null) return@enqueuePersistence
            val visible = _buffers.value.values.firstOrNull { current ->
                current.ref.networkId == ref.networkId && current.messages.any { it.localId == message.localId }
            }
            updateBufferKey(visible?.key ?: target.storageKey) { current ->
                val index = current.messages.indexOfFirst { it.localId == message.localId }
                if (index < 0) current else {
                    val updated = current.messages.toMutableList()
                    val previous = updated[index]
                    updated[index] = (persisted?.let { mergeChatMessageMetadata(previous, it.toChatMessage(message.localId)) } ?: previous)
                        .copy(storedRowId = rowId, pendingEcho = previous.pendingEcho, echoLabel = previous.echoLabel)
                    val redacted = updated.asSequence().filter { it.redacted }.mapNotNull { it.msgid }.toSet()
                    val messages = if (persisted != null && updated.any { it.localId != message.localId && it.storedRowId == rowId }) {
                        mergeConversationMessages(emptyList(), updated, incomingCanonical = true)
                    } else updated
                    current.copy(messages = messages, reactions = current.reactions - redacted)
                }
            }
            enqueueCoalescedPersistence("message-state:${target.storageKey}") {
                refreshAfterWrite(target)
            }
            val writes = (writesSinceTrim[target.storageKey] ?: 0) + 1
            if (writes >= 100) {
                messageStore.trimTo(target, MESSAGE_PER_CONVERSATION_LIMIT, clock())
                writesSinceTrim[target.storageKey] = 0
            } else writesSinceTrim[target.storageKey] = writes
            scheduleMaintenance()
        }
    }

    private fun updateBuffer(ref: ConversationRef, transform: (ConversationBuffer) -> ConversationBuffer) {
        synchronized(selectionLock) {
            if (ref.storageKey !in _buffers.value && ref.storageKey == selectedStorageKey) pendingRenamedKey = null
            _buffers.update { current ->
                val existing = current[ref.storageKey]
                    ?: ConversationBuffer(ref = ref, displayName = ConversationNames.forRef(ref),
                        history = ConversationHistory(gaps = restoredGaps(ref)))
                current + (ref.storageKey to transform(existing))
            }
        }
    }

    private fun restoredGaps(ref: ConversationRef): List<HistoryGap> {
        val stored = historyCoverage.gaps(ref.storageKey)
        if (stored.isEmpty()) return emptyList()
        return stored.map { gap ->
            HistoryGap(gap.id, HistoryAnchor(gap.fromTimestampMs, gap.fromMsgid), HistoryAnchor(gap.toTimestampMs, gap.toMsgid))
        }
    }

    private fun buffer(ref: ConversationRef): ConversationBuffer = synchronized(selectionLock) {
        _buffers.value[ref.storageKey]?.let { return@synchronized it }
        if (ref.storageKey == selectedStorageKey) pendingRenamedKey = null
        val created = ConversationBuffer(
            ref = ref,
            displayName = ConversationNames.forRef(ref),
            joinState = if (ref.kind == ConversationKind.CHANNEL || channelOrder.isParted(ref.storageKey)) JoinState.IDLE else JoinState.JOINED,
            history = ConversationHistory(gaps = restoredGaps(ref)),
        )
        _buffers.update { it + (ref.storageKey to created) }
        created
    }

    public fun markRead(storageKey: String) {
        val selected = _buffers.value[storageKey] ?: return
        val latest = selected.messages.maxWithOrNull(compareBy<ChatMessage> { it.timestampMs }
            .thenBy { it.storedRowId ?: Long.MAX_VALUE })
        if (latest != null) {
            readMarkers.advance(storageKey, latest.timestampMs, latest.storedRowId ?: 0L)
            updateBufferKey(storageKey) { it.copy(hasUnread = false, unreadCount = 0, mentionCount = 0,
                unreadFromTimestampMs = null, readAtMs = readMarkers.marker(storageKey)) }
        }
        enqueuePersistence {
            if (networkStore.byId(selected.ref.networkId) == null || storageKey !in _buffers.value) return@enqueuePersistence
            val newest = messageStore.newestCursor(selected.ref)
            if (newest != null) readMarkers.advance(storageKey, newest.timestampMs, newest.rowId)
            refreshUnread(selected.ref)
            sessions[selected.ref.networkId]?.let(::replayPendingReadMarkers)
        }
    }

    private fun Session.bufferTargetFor(storageKey: String): String? {
        val buffer = _buffers.value[storageKey] ?: return null
        return when (buffer.ref.kind) {
            dev.brentdevs.yardhal.core.data.ConversationKind.SERVER -> null
            else -> buffer.ref.rawTarget
        }
    }

    public fun loadPersistedHistory(storageKey: String): Boolean = history.open(storageKey)

    public fun loadOlderHistory(storageKey: String) {
        history.older(storageKey)
    }

    public fun retryHistory(storageKey: String) {
        history.retry(storageKey)
    }

    public fun fillHistoryGap(storageKey: String, gapId: String) {
        history.fillGap(storageKey, gapId)
    }

    private var lastTypingSentAt: Long = 0

    public fun sendTyping(networkId: String, storageKey: String) {
        val session = sessions[networkId] ?: return
        val buffer = _buffers.value[storageKey] ?: return
        if (buffer.ref.kind == ConversationKind.SERVER) return
        if (!session.state.registered || session.statusFlow.value != ConnectionStatus.REGISTERED) return
        val tag = session.state.clientTagPolicy.typing ?: return
        val now = clock()
        if (now - lastTypingSentAt < TYPING_SEND_INTERVAL_MS) return
        lastTypingSentAt = now
        sendRaw(session, IrcMessage(tags = mapOf(tag to "active"), command = "TAGMSG", parameters = listOf(buffer.ref.rawTarget)).toWire())
    }

    private fun updateBufferKey(storageKey: String, transform: (ConversationBuffer) -> ConversationBuffer) =
        synchronized(selectionLock) {
            _buffers.update { current ->
                val existing = current[storageKey] ?: return@update current
                current + (storageKey to transform(existing))
            }
        }

    private fun refreshNetworkStates() {
        _networkStates.update {
            networkStore.all().map { config ->
                val session = sessions[config.id]
                val live = session?.takeUnless { it.quitRequested }
                val binding = session?.config?.bouncerBinding ?: config.bouncerBinding
                UiNetwork(
                    id = config.id,
                    name = config.name,
                    host = config.host,
                    status = session?.statusFlow?.value ?: ConnectionStatus.DISCONNECTED,
                    ownNick = live?.state?.ownNick ?: config.nick,
                    hasBotMode = live?.state?.botModeLetter != null,
                    accountBanAvailable = live?.state?.accountExtban != null,
                    iconUrl = live?.state?.networkIconUrl,
                    taggedRepliesAvailable = live?.state?.clientTagPolicy?.reply != null,
                    reactionsAvailable = live?.state?.clientTagPolicy?.reactionsAvailable == true,
                    typingAvailable = live?.state?.clientTagPolicy?.typing != null,
                    attachmentTagsAvailable = live?.state?.clientTagPolicy?.attachment != null,
                    connectionPhase = when {
                        binding?.rejectionReason != null -> RecoveryPhase.AUTHENTICATION_REJECTED
                        binding?.enabled == false -> RecoveryPhase.DISCONNECTED
                        binding != null && !upstreamEligible(session?.config ?: config) -> RecoveryPhase.USER_DISCONNECTED
                        else -> session?.recovery?.phase ?: if (config.userDisconnected) {
                            RecoveryPhase.USER_DISCONNECTED
                        } else RecoveryPhase.DISCONNECTED
                    },
                    connectionError = session?.connectionError,
                    rejectedCertificate = session?.rejectedCertificate,
                    hasCertificatePin = config.certificatePin != null,
                    disconnectSavePending = session?.disconnectSavePending == true,
                )
            }.sortedBy { it.name }
        }
    }

    private fun sendRaw(session: Session, line: String) {
        if (sessions[session.config.id] !== session) return
        val message = IrcMessage.parse(line)
        val filtered = message?.takeIf { it.tags.keys.any { tag -> !session.state.clientTagPolicy.allows(tag) } }
            ?.let { it.copy(tags = it.tags.filterKeys(session.state.clientTagPolicy::allows)).toWire() }
        session.reconnector?.sendLine(filtered ?: line)
    }

    private fun sendLabeled(session: Session, origin: ConversationRef, command: LabeledCommand, line: String) {
        val label = synchronized(session.state) { session.state.issueLabel(origin, command, clock()) }
        sendRaw(session, if (label == null) line else labelLine(line, label))
    }

    private fun SlashCommand.isLocal(): Boolean =
        this is SlashCommand.Query || this is SlashCommand.IgnoreAdd || this is SlashCommand.Whois ||
            this is SlashCommand.IgnoreRemove || this is SlashCommand.Help

    public fun sendText(
        networkId: String,
        storageKey: String,
        input: String,
        attachments: List<dev.brentdevs.yardhal.core.data.UploadedAttachment> = emptyList(),
    ): Boolean = synchronized(sessionLifecycleLock) send@{
        val session = sessions[networkId] ?: return@send false
        synchronized(session.state) compose@{
            val activeBuffer = _buffers.value[storageKey]?.takeIf { it.ref.networkId == networkId } ?: return@compose false
            val command = SlashCommandParser.parse(input, activeBuffer.ref.rawTarget) ?: return@compose false
            if ((!session.state.registered || session.statusFlow.value != ConnectionStatus.REGISTERED || session.quitRequested) && !command.isLocal()) return@compose false
            if (command is SlashCommand.PlainMessage || command is SlashCommand.EscapedMessage) {
                val parent = activeBuffer.replyDraft?.let { draft ->
                    relayPresentation(networkId, activeBuffer.messages.firstOrNull { it.localId == draft.localId } ?: draft)
                }
                val text = when (command) {
                    is SlashCommand.PlainMessage -> command.text
                    is SlashCommand.EscapedMessage -> "/" + command.text
                    else -> return@compose false
                }
                val replyId = parent?.msgid?.takeIf { session.state.clientTagPolicy.reply != null && !parent.redacted }
                val portable = if (parent != null && replyId == null) portableQuote(parent, text) else text
                val sent = sendComposed(session, activeBuffer.ref, portable, replyId, parent, attachments)
                if (sent && parent != null) updateBufferKey(storageKey) { it.copy(replyDraft = null) }
                if (!sent) _operationError.value = "Message was not fully queued. Your draft and quote were retained; reconnect before retrying."
                return@compose sent
            }
            if (command is SlashCommand.Action) {
                val sent = sendMessage(
                    session,
                    activeBuffer.ref,
                    "\u0001ACTION ${command.description}\u0001",
                    optimisticKind = MessageKind.ACTION,
                    optimisticText = command.description,
                )
                if (!sent) _operationError.value = "Message was not fully queued. Your draft was retained; reconnect before retrying."
                return@compose sent
            }
            dispatchCommand(session, activeBuffer.ref, command)
            true
        }
    }

    public fun canSendOffline(networkId: String, storageKey: String, input: String): Boolean {
        if (!sessions.containsKey(networkId)) return false
        val activeBuffer = _buffers.value[storageKey] ?: return false
        return SlashCommandParser.parse(input, activeBuffer.ref.rawTarget)?.isLocal() == true
    }

    private fun dispatchCommand(
        session: Session,
        active: ConversationRef,
        command: SlashCommand,
        replyToMsgid: String? = null,
        quoteParent: ChatMessage? = null,
    ) {
        when (command) {
            is SlashCommand.PlainMessage -> sendComposed(session, active, command.text, replyToMsgid, quoteParent)
            is SlashCommand.EscapedMessage -> sendComposed(session, active, "/" + command.text, replyToMsgid, quoteParent)
            is SlashCommand.Action -> sendMessage(
                session,
                active,
                "\u0001ACTION ${command.description}\u0001",
                optimisticKind = MessageKind.ACTION,
                optimisticText = command.description,
            )
            is SlashCommand.Msg -> sendMessage(
                session,
                resolveTargetRef(session, command.target),
                sanitizeOutboundText(command.text),
                origin = active,
            )
            is SlashCommand.Query -> {
                val ref = resolveTargetRef(session, command.nick)
                if (ref.kind == ConversationKind.DIRECT_MESSAGE) {
                    channelOrder.clearParted(ref.storageKey)
                    _orderState.value = channelOrder.snapshot()
                }
                buffer(ref)
                persistOpenedShell(ref)
            }
            is SlashCommand.Join -> {
                if (command.channels.isEmpty()) return
                val channels = command.channels.joinToString(",")
                sendRaw(session, if (command.keys.isEmpty()) "JOIN $channels" else "JOIN $channels ${command.keys.joinToString(",")}")
                for (c in command.channels) {
                    val ref = ConversationRef.channel(session.config.id, c, session.state.casemapping)
                    channelOrder.clearParted(ref.storageKey)
                    _orderState.value = channelOrder.snapshot()
                    buffer(ref)
                    markJoining(ref)
                }
            }
            is SlashCommand.Part -> {
                val target = command.channel
                    ?: active.rawTarget.takeIf { active.kind != ConversationKind.SERVER }
                    ?: return
                val reason = command.reason
                sendRaw(session, if (reason == null) "PART $target" else "PART $target :${sanitizeOutboundText(reason)}")
                val ref = session.state.channelRef(target)
                channelOrder.markParted(ref.storageKey)
                _orderState.value = channelOrder.snapshot()
                updateBufferKey(ref.storageKey) { it.copy(joinState = JoinState.IDLE) }
            }
            is SlashCommand.NickChange -> sendRaw(session, "NICK ${sanitizeOutboundText(command.newNick)}")
            is SlashCommand.TopicSet -> sendRaw(session, "TOPIC ${command.channel} :${sanitizeOutboundText(command.topic)}")
            is SlashCommand.TopicShow -> {
                val target = command.channel ?: active.rawTarget
                sendLabeled(session, active, LabeledCommand.TOPIC, "TOPIC $target")
            }
            is SlashCommand.SetName ->
                if ("setname" in session.state.supportedCaps) {
                    sendRaw(session, "SETNAME :${sanitizeOutboundText(command.realName)}")
                } else {
                    appendSystem(session, active, "This server does not support changing your realname (setname)")
                }
            is SlashCommand.Away ->
                sendRaw(session, if (command.message == null) "AWAY" else "AWAY :${command.message}")
            is SlashCommand.Quit -> disconnect(session.config.id, sanitizeOutboundText(command.reason ?: ""))
            is SlashCommand.Whois -> requestWhois(session, active, command.target)
            is SlashCommand.Kick -> {
                val channel = command.channel ?: active.rawTarget
                val reason = command.reason
                sendRaw(
                    session,
                    if (reason == null) "KICK $channel ${command.nick}"
                    else "KICK $channel ${command.nick} :${sanitizeOutboundText(reason)}",
                )
            }
            is SlashCommand.Ban -> {
                val channel = command.channel ?: active.rawTarget
                sendLabeled(
                    session,
                    active,
                    LabeledCommand.MODE,
                    if (command.mask == null) "MODE $channel +b" else "MODE $channel +b ${command.mask}",
                )
            }
            is SlashCommand.Mode -> {
                val target = command.target ?: active.rawTarget
                sendLabeled(
                    session,
                    active,
                    LabeledCommand.MODE,
                    if (command.params.isEmpty()) "MODE $target" else "MODE $target ${command.params.joinToString(" ")}",
                )
            }
            is SlashCommand.CtcpQuery -> sendMessage(
                session,
                resolveTargetRef(session, command.target),
                IrcCtcp.encode(command.command, command.arguments),
                suppressOptimistic = true,
                origin = active,
            )
            is SlashCommand.Op -> {
                val channel = command.channel ?: active.rawTarget
                sendLabeled(session, active, LabeledCommand.MODE, "MODE $channel ${if (command.grant) "+o" else "-o"} ${command.nick}")
            }
            is SlashCommand.Voice -> {
                val channel = command.channel ?: active.rawTarget
                sendLabeled(session, active, LabeledCommand.MODE, "MODE $channel ${if (command.grant) "+v" else "-v"} ${command.nick}")
            }
            is SlashCommand.MonitorAdd -> sendLabeled(session, active, LabeledCommand.MONITOR, "MONITOR + ${command.nick}")
            is SlashCommand.MonitorRemove -> {
                synchronized(session.state) { session.state.forgetMonitored(command.nick.split(',')) }
                sendLabeled(session, active, LabeledCommand.MONITOR, "MONITOR - ${command.nick}")
            }
            is SlashCommand.MonitorList -> sendLabeled(session, active, LabeledCommand.MONITOR, "MONITOR L")
            is SlashCommand.MonitorClear -> synchronized(session.state) {
                session.state.clearMonitored()?.let { processEffect(session, it) }
                sendLabeled(session, active, LabeledCommand.MONITOR, "MONITOR C")
            }
            is SlashCommand.MonitorStatus -> sendLabeled(session, active, LabeledCommand.MONITOR, "MONITOR S")
            is SlashCommand.WhoQuery -> sendLabeled(
                session,
                active,
                LabeledCommand.WHO,
                if (command.useWhox && session.state.hasWhox) "WHO ${command.target} $WHOX_QUERY" else "WHO ${command.target}",
            )
            is SlashCommand.IgnoreAdd -> {
                ignoreStore?.add(command.mask)
                appendSystem(session, active, "Ignoring ${command.mask}")
            }
            is SlashCommand.IgnoreRemove -> {
                ignoreStore?.remove(command.mask)
                appendSystem(session, active, "No longer ignoring ${command.mask}")
            }
            is SlashCommand.Help -> appendSystem(
                session,
                active,
                "Commands: /me /msg /query /join /part /nick /topic /away /back /setname /quit /whois /kick /ban /mode /ctcp " +
                    "/setavatar /setdisplayname /raw",
            )
            is SlashCommand.Raw -> sendRawCommand(session, active, command.line)
            is SlashCommand.SetAvatar -> setOwnMetadata(session, active, IrcMetadata.KEY_AVATAR, command.url)
            is SlashCommand.SetDisplayName ->
                setOwnMetadata(session, active, IrcMetadata.KEY_DISPLAY_NAME, command.name)
        }
    }

    private fun setOwnMetadata(session: Session, active: ConversationRef, key: String, rawValue: String?) {
        val capability = session.state.metadataCapability
        if (capability == null) {
            appendSystem(session, active, "This network does not support ${IrcMetadata.CAPABILITY}.")
            return
        }
        val value = rawValue?.let(::sanitizeOutboundText)
        if (key == IrcMetadata.KEY_AVATAR && value != null && !ImageUrlPolicy.isAllowed(value)) {
            appendSystem(session, active, "Avatar URLs must use https://")
            return
        }
        if (value != null && !capability.allowsValue(value)) {
            appendSystem(session, active, "Value exceeds the server limit of ${capability.maxValueBytes} bytes.")
            return
        }
        sendRaw(session, IrcMetadata.setCommand(key, value))
    }

    private fun resolveTargetRef(session: Session, target: String): ConversationRef = session.state.targetRef(target)

    private fun sendRawCommand(session: Session, origin: ConversationRef, line: String) {
        val parsed = IrcMessage.parse(line)
        if (parsed == null || parsed.tags.containsKey("label")) {
            sendRaw(session, line)
        } else {
            sendLabeled(session, origin, LabeledCommand.RAW, line)
        }
    }

    private fun sendComposed(
        session: Session,
        ref: ConversationRef,
        text: String,
        replyToMsgid: String?,
        quoteParent: ChatMessage? = null,
        attachments: List<dev.brentdevs.yardhal.core.data.UploadedAttachment> = emptyList(),
    ): Boolean {
        val normalized = IrcMultiline.normalize(text)
        fun attachmentFor(body: String): dev.brentdevs.yardhal.core.data.UploadedAttachment? =
            attachments.firstOrNull { it.url in body.split(Regex("\\s+")) }
        if ('\n' !in normalized) {
            val attachment = attachmentFor(normalized)
            return sendMessage(session, ref, normalized, replyToMsgid = replyToMsgid, quoteParent = quoteParent,
                attachmentUrl = attachment?.url, attachmentName = attachment?.name,
                attachmentMimeType = attachment?.mimeType, attachmentSizeBytes = attachment?.sizeBytes)
        }
        val limits = session.state.outboundMultilineLimits()
        if (limits == null) {
            for ((index, line) in normalized.split('\n').filter { it.isNotBlank() }.withIndex()) {
                val attachment = attachmentFor(line)
                if (!sendMessage(session, ref, line, replyToMsgid = replyToMsgid.takeIf { index == 0 },
                        quoteParent = quoteParent.takeIf { index == 0 }, attachmentUrl = attachment?.url,
                        attachmentName = attachment?.name, attachmentMimeType = attachment?.mimeType,
                        attachmentSizeBytes = attachment?.sizeBytes)) return false
            }
            return true
        }
        val echoed = "echo-message" in session.state.supportedCaps
        val budget = IrcMultiline.lineBudget(session.state.ownNick, ref.rawTarget)
        for ((index, lines) in IrcMultiline.split(normalized, limits, budget).withIndex()) {
            val reply = replyToMsgid.takeIf { index == 0 }
            val label = session.state.issueLabel(ref, LabeledCommand.PRIVMSG, clock())
            val body = IrcMultiline.combine(lines)
            val attachment = attachmentFor(body)
            val policy = session.state.clientTagPolicy
            val batchTags = buildMap {
                if (label != null) put("label", label)
                if (reply != null) policy.reply?.let { put(it, reply) }
                if (attachment != null) policy.attachment?.let { put(it, attachment.url) }
            }
            val reference = "yml" + idGenerator.getAndIncrement()
            for (frame in IrcMultiline.frame(reference, "PRIVMSG", ref.rawTarget, lines, batchTags)) {
                if (session.reconnector?.send(frame) != true) return false
            }
            appendChat(
                session = session, ref = ref, sender = session.state.ownNick, kind = MessageKind.PRIVMSG,
                text = body, msgid = null, timestampMs = clock(), sentByUs = true, highlightsMe = false,
                replyToMsgid = reply, pendingEcho = echoed, quoteParent = quoteParent.takeIf { index == 0 },
                echoLabel = label, optimistic = true, attachmentUrl = attachment?.url,
                attachmentName = attachment?.name, attachmentMimeType = attachment?.mimeType,
                attachmentSizeBytes = attachment?.sizeBytes,
            )
        }
        return true
    }

    private fun sendMessage(
        session: Session,
        ref: ConversationRef,
        wireText: String,
        optimisticKind: MessageKind = MessageKind.PRIVMSG,
        optimisticText: String? = null,
        suppressOptimistic: Boolean = false,
        replyToMsgid: String? = null,
        attachmentUrl: String? = null,
        origin: ConversationRef = ref,
        quoteParent: ChatMessage? = null,
        attachmentName: String? = null,
        attachmentMimeType: String? = null,
        attachmentSizeBytes: Long? = null,
    ): Boolean {
        val safeWireText = sanitizeOutboundText(wireText)
        val echoExpected = "echo-message" in session.state.supportedCaps
        val label = synchronized(session.state) { session.state.issueLabel(origin, LabeledCommand.PRIVMSG, clock()) }
        val policy = session.state.clientTagPolicy
        val tags = buildMap {
            if (label != null) put("label", label)
            if (replyToMsgid != null) policy.reply?.let { put(it, replyToMsgid) }
            if (attachmentUrl != null) policy.attachment?.let { put(it, attachmentUrl) }
        }
        if (session.reconnector?.send(IrcMessage(tags = tags, command = "PRIVMSG",
                parameters = listOf(ref.rawTarget, safeWireText))) != true) return false
        if (!suppressOptimistic) {
            appendChat(
                session = session,
                ref = ref,
                sender = session.state.ownNick,
                kind = optimisticKind,
                text = optimisticText ?: safeWireText,
                msgid = null,
                timestampMs = clock(),
                sentByUs = true,
                highlightsMe = false,
                replyToMsgid = replyToMsgid,
                attachmentUrl = attachmentUrl,
                pendingEcho = echoExpected,
                quoteParent = quoteParent,
                attachmentName = attachmentName,
                attachmentMimeType = attachmentMimeType,
                attachmentSizeBytes = attachmentSizeBytes,
                echoLabel = label,
                optimistic = true,
            )
        }
        return true
    }
}

internal fun sanitizeOutboundText(raw: String): String =
    raw.replace("\r", " ").replace("\n", " ")
        .replace("\u0000", "")
