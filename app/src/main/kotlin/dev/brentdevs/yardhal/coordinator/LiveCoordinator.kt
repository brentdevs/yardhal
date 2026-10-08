package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.client.AuthenticationRejectedException
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
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.MentionMatcher
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.SaslMode
import dev.brentdevs.yardhal.core.data.SlashCommand
import dev.brentdevs.yardhal.core.data.SlashCommandParser
import dev.brentdevs.yardhal.core.data.StoredMessage
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

    private val _operationError = MutableStateFlow<String?>(null)
    public val operationError: StateFlow<String?> = _operationError.asStateFlow()

    public fun dismissOperationError(expectedError: String) {
        _operationError.compareAndSet(expectedError, null)
    }

    public fun dismissWhois() {
        _whois.value = null
    }

    private var ignoreStore: dev.brentdevs.yardhal.core.data.IgnoreStore? = null

    public fun attachIgnores(store: dev.brentdevs.yardhal.core.data.IgnoreStore) {
        ignoreStore = store
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val sessionLifecycleLock = Any()
    @Volatile private var networkAvailable = true
    private var defaultNetworkHandle: Long? = null
    private val persistenceLock = Any()
    private var persistenceTail: Job? = null

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
    }

    private inner class Session(
        @Volatile var config: NetworkConfig,
        casemapping: CaseMapping,
        manuallyWanted: Boolean = false,
        allowStartup: Boolean = true,
    ) {
        val state = PerNetworkState(config.id, config.nick, config.autojoin).also { it.casemapping = casemapping }
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

        override fun mergeStoredHistory(ref: ConversationRef, messages: List<StoredMessage>) {
            val key = currentRef(ref).storageKey
            updateBufferKey(key) { current ->
                val merged = mergeSearchContext(current.messages, messages, prependEqualTimestamp = true) { idGenerator.getAndIncrement() }
                val marker = maxOf(current.readAtMs, readMarkers.marker(key))
                val boundary = if (mutes.isMuted(key)) null else merged.asSequence()
                    .filter { it.countsAsUnread && it.timestampMs > marker }.minOfOrNull { it.timestampMs }
                current.copy(
                    messages = merged,
                    hasUnread = current.hasUnread || boundary != null,
                    unreadFromTimestampMs = current.unreadFromTimestampMs ?: boundary,
                    readAtMs = marker,
                )
            }
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
        for (config in networkStore.all()) connect(config)
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
            if (!saveDisconnectIntent(networkId, false)) {
                showConnectionError(networkId, "Could not save Connect intent. Check device storage and retry; the saved disconnect preference is unchanged.")
                return
            }
            val config = networkStore.byId(networkId) ?: return
            val previous = sessions[networkId]
            if (previous == null) {
                startSession(config, manuallyWanted = true)
            } else {
                synchronized(previous.state) {
                    val mapping = previous.state.casemapping
                    stopSession(previous, "Connecting with current network settings")
                    startSession(config, mapping, manuallyWanted = true)
                }
            }
        }
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
                val updated = config.copy(certificatePin = CertificatePin(inspection.host, inspection.port, leaf.sha256))
                val persisted = try {
                    networkStore.update(updated)
                } catch (_: java.io.IOException) {
                    false
                }
                if (!persisted) {
                    showConnectionError(networkId, "Certificate trust was not saved. Check device storage and retry.")
                    return@trust false
                }
                val wanted = session.recovery.desiredConnection
                val mapping = session.state.casemapping
                stopSession(session, "Certificate trust changed")
                startSession(updated, mapping, manuallyWanted = wanted)
                true
            }
        }

    public fun removeCertificateTrust(networkId: String): Boolean =
        synchronized(sessionLifecycleLock) remove@{
            val saved = networkStore.byId(networkId) ?: return@remove false
            if (saved.certificatePin == null) return@remove false
            val removed = try {
                updateNetwork(saved.copy(certificatePin = null))
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
            val session = sessions[config.id]
            if (session == null) {
                if (!networkStore.update(updated)) return@update false
                clearNewAutojoins(saved, updated, CaseMapping.RFC1459)
                refreshNetworkStates()
            } else {
                synchronized(session.state) {
                    if (!networkStore.update(updated)) return@update false
                    clearNewAutojoins(saved, updated, session.state.casemapping)
                    if (!credentialsChanged && session.config.connectionSettingsMatch(updated)) {
                        session.config = updated
                        refreshNetworkStates()
                    } else {
                        val wanted = session.recovery.desiredConnection && !updated.userDisconnected
                        val mapping = session.state.casemapping
                        stopSession(session, "Network settings changed")
                        startSession(updated, mapping, manuallyWanted = wanted, allowStartup = false)
                    }
                }
            }
            true
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
        casemapping: CaseMapping = CaseMapping.RFC1459,
        manuallyWanted: Boolean = false,
        allowStartup: Boolean = true,
    ) {
        val session = Session(config, casemapping, manuallyWanted, allowStartup)
        session.recovery.handle(RecoverySignal.START)
        sessions[config.id] = session
        _profiles.update { it - config.id }
        updateAllBuffersForNetwork(config.id) { buffer ->
            buffer.copy(
                topic = null,
                members = emptyList(),
                memberPresence = emptyMap(),
                typingUsers = emptyMap(),
                joinState = if (buffer.ref.kind == ConversationKind.CHANNEL &&
                    !channelOrder.isParted(buffer.ref.storageKey)
                ) {
                    if (session.recovery.desiredConnection) JoinState.JOINING else JoinState.IDLE
                } else buffer.joinState,
            )
        }
        ensureBaseBuffers(session)
        restoreKnownConversations(session)
        refreshNetworkStates()
        if (session.recovery.desiredConnection) launchSession(session)
    }

    private fun restoreKnownConversations(session: Session) {
        val retainedBuffers = _buffers.value
        val retainedMappingKnown = session.storedMappingKnown
        enqueuePersistence {
            val known = runCatching {
                messageStore.knownConversations(session.config.id)
            }.getOrDefault(emptyList())
            synchronized(session.state) {
                if (sessions[session.config.id] !== session) return@enqueuePersistence
                for (storedRef in known) {
                    val ref = currentRef(
                        if (retainedMappingKnown) {
                            retainedBuffers[storedRef.storageKey]?.ref ?: _buffers.value[storedRef.storageKey]?.ref ?: storedRef
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
                _profiles.update { it - networkId }
                refreshNetworkStates()
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
        session.acceptedEpoch = null
        session.state.registered = false
        session.statusFlow.value = ConnectionStatus.DISCONNECTED
        clearIdentification(session)
        session.probeJob?.cancel()
        session.probeJob = null
        history.reset(session.config.id, quitReason)
        session.reconnector?.stop()
        session.connection?.disconnect()
        session.lifecycleJob.cancel()
    }

    public fun removeNetwork(networkId: String) {
        synchronized(sessionLifecycleLock) {
            val saved = networkStore.byId(networkId) ?: return
            if (!networkStore.remove(networkId)) return
            sessions[networkId]?.let { session ->
                synchronized(session.state) { stopSession(session, "Network removed") }
            }
            enqueuePersistence {
                messageStore.deleteNetwork(networkId)
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
            synchronized(selectionLock) {
                if (selectedStorageKey?.substringBefore("|") == networkId) {
                    selectedStorageKey = null
                    pendingRenamedKey = null
                }
                _buffers.update { buffers -> buffers.filterValues { it.ref.networkId != networkId } }
            }
            _profiles.update { it - networkId }
            rawLogs.remove(networkId)
            _rawLogVersion.update { it + 1 }
            refreshNetworkStates()
        }
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

    private fun launchSession(session: Session) {
        if (session.reconnector != null) return
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
                    session.presentationSecrets = buildSet {
                        effective.saslPassword?.takeIf(String::isNotEmpty)?.let { add(it) }
                        effective.serverPassword?.takeIf(String::isNotEmpty)?.let { add(it) }
                        effective.nickServPassword?.takeIf(String::isNotEmpty)?.let { add(it) }
                        effective.proxyPassword?.takeIf(String::isNotEmpty)?.let { add(it) }
                    }
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

    private fun effectiveConfig(config: NetworkConfig, stsUpgradePort: Int? = null): NetworkConfig {
        val saslPassword = resolvePassword(config.saslPasswordRef, config.saslPassword, "SASL")
        if ((config.saslMode == SaslMode.PLAIN || config.saslMode == SaslMode.SCRAM_SHA_256) &&
            saslPassword.isNullOrEmpty()
        ) throw AuthenticationRejectedException("The selected SASL mechanism requires a saved password. Replace it in network settings.")
        val serverPassword = resolvePassword(config.serverPasswordRef, config.serverPassword, "server")
        val nickServPassword = resolvePassword(config.nickServPasswordRef, config.nickServPassword, "NickServ")
        val proxyPassword = resolvePassword(config.proxy?.passwordRef, config.proxyPassword, "proxy")
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
        if (config.saslMode == SaslMode.EXTERNAL && !effectiveTls) {
            throw TlsIdentityUnavailableException("SASL EXTERNAL requires TLS. Enable TLS in network security settings.")
        }
        val identity = config.tlsClientAlias?.let(clientIdentityProvider) ?: config.tlsClientIdentity
        if (config.saslMode == SaslMode.EXTERNAL && identity == null) {
            throw TlsIdentityUnavailableException("SASL EXTERNAL requires TLS and a selected client certificate. Correct the network security settings.")
        }
        val pin = config.certificatePin?.takeIf {
            effectiveTls && it.host.equals(config.host, ignoreCase = true) && it.port == effectivePort
        }
        return config.copy(
            port = effectivePort,
            tls = effectiveTls,
            saslAuthcid = config.saslAuthcid.takeIf { saslPassword != null || config.saslMode == SaslMode.EXTERNAL },
            saslPassword = saslPassword,
            serverPassword = serverPassword,
            nickServPassword = nickServPassword,
            proxyPassword = proxyPassword,
            tlsClientIdentity = identity,
            certificatePin = pin,
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
                session.reconnector?.setNetworkAvailable(false)
            }
            RecoveryAction.NUDGE -> {
                if (session.reconnector == null) launchSession(session) else session.reconnector?.nudge()
            }
            RecoveryAction.PROBE -> probeSession(session)
            RecoveryAction.STOP -> session.reconnector?.stop()
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
            when (event) {
                is IrcEvent.ConnectionOpened -> {
                    session.connectionError = null
                    session.rejectedCertificate = null
                    session.statusFlow.value = ConnectionStatus.CONNECTING
                }
                is IrcEvent.Disconnected -> {
                    session.connection = null
                    session.acceptedEpoch = null
                    connectionFailed(session, event.cause)
                }
                else -> Unit
            }
            refreshNetworkStates()
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
        )
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
                _buffers.value.values
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
            is InboundEffect.ProfilesChanged -> _profiles.update { profiles ->
                if (sessions[session.config.id] === session) {
                    profiles + (session.config.id to effect.profiles)
                } else {
                    profiles
                }
            }
            is InboundEffect.NetworkIconChanged -> refreshNetworkStates()
            is InboundEffect.StatusChanged -> {
                if (effect.status != ConnectionStatus.REGISTERED && session.quitRequested) return
                session.statusFlow.value = effect.status
                refreshNetworkStates()
            }
            is InboundEffect.OwnNickChanged -> refreshNetworkStates()
            is InboundEffect.EnsureBuffer -> buffer(effect.ref)
            is InboundEffect.RemoveBuffer -> {
                removeBuffer(effect.ref.storageKey)
                channelOrder.markParted(effect.ref.storageKey)
                _orderState.value = channelOrder.snapshot()
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
            is InboundEffect.SetTopic -> updateBuffer(effect.ref) { it.copy(topic = effect.topic) }
            is InboundEffect.SetMembers -> updateBuffer(effect.ref) {
                it.copy(members = effect.members, memberPresence = effect.presence)
            }
            is InboundEffect.SetJoinState -> updateBuffer(effect.ref) { it.copy(joinState = effect.state) }
            is InboundEffect.ClearTyping -> updateBufferKey(effect.ref.storageKey) { it.copy(typingUsers = emptyMap()) }
            is InboundEffect.SetTyping -> updateBuffer(effect.ref) { buffer ->
                val now = clock()
                val fresh = buffer.typingUsers.filterValues { it > now }.toMutableMap()
                val expiresAt = effect.expiresAtMs
                if (expiresAt == null) fresh.remove(effect.nick) else fresh[effect.nick] = expiresAt
                buffer.copy(typingUsers = fresh)
            }
            is InboundEffect.ApplyReaction -> updateBuffer(effect.ref) { buffer ->
                buffer.copy(reactions = applyReaction(buffer.reactions, effect))
            }
            is InboundEffect.RedactMessage -> updateBufferKey(effect.ref.storageKey) { buffer ->
                redactInBuffer(buffer, effect.msgid)
            }
            is InboundEffect.ApplyReadMarker -> applyReadMarker(effect.ref, effect.timestampMs)
            is InboundEffect.WhoisCompleted -> _whois.value = effect.info
            is InboundEffect.ChannelListed -> _channelList.update { it + effect.entry }
            is InboundEffect.ChannelListFinished -> _channelList.update { list -> list.sortedByDescending { it.users } }
            is InboundEffect.BouncerNetworksChanged -> bumpBouncerVersion()
            is InboundEffect.NetworkFeaturesChanged -> refreshNetworkStates()
        }
    }

    private fun applyReadMarker(ref: ConversationRef, millis: Long) {
        readMarkers.advance(ref.storageKey, millis)
        _buffers.update { current ->
            val existing = current[ref.storageKey] ?: return@update current
            if (millis <= existing.readAtMs) {
                current
            } else {
                val boundary = if (mutes.isMuted(ref.storageKey)) null else existing.messages
                    .asSequence()
                    .filter { it.timestampMs > millis && it.countsAsUnread }
                    .minOfOrNull { it.timestampMs }
                current + (ref.storageKey to existing.copy(
                    readAtMs = millis,
                    hasUnread = boundary != null,
                    unreadFromTimestampMs = boundary,
                ))
            }
        }
    }

    private val selectionLock = Any()
    private var selectedStorageKey: String? = null
    private var pendingRenamedKey: String? = null

    public fun trackSelection(storageKey: String?) {
        synchronized(selectionLock) {
            if (selectedStorageKey != storageKey) {
                selectedStorageKey = storageKey
                pendingRenamedKey = null
            }
        }
    }

    public fun followRenamedSelection(storageKey: String?, bufferKeys: Set<String>): String? =
        synchronized(selectionLock) {
            if (selectedStorageKey != storageKey) {
                selectedStorageKey = storageKey
                pendingRenamedKey = null
            }
            val renamed = pendingRenamedKey
            if (renamed == null || (storageKey != null && storageKey in bufferKeys) || renamed !in bufferKeys) {
                return@synchronized null
            }
            selectedStorageKey = renamed
            pendingRenamedKey = null
            renamed
        }

    private fun removeBuffer(storageKey: String) = withNetworkState(storageKey.substringBefore("|")) {
        history.remove(storageKey)
        synchronized(selectionLock) {
            if (selectedStorageKey == storageKey || pendingRenamedKey == storageKey) pendingRenamedKey = null
            _buffers.update { it - storageKey }
        }
    }

    private inline fun withNetworkState(networkId: String, operation: () -> Unit) {
        val state = sessions[networkId]?.state
        if (state == null) operation() else synchronized(state) { operation() }
    }

    private fun renameConversation(session: Session, from: ConversationRef, to: ConversationRef) {
        val fromKey = from.storageKey
        val toKey = to.storageKey
        synchronized(selectionLock) {
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
                    moved.copy(
                        messages = messages,
                        hasUnread = boundary != null,
                        unreadFromTimestampMs = boundary,
                        readAtMs = marker,
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
            enqueuePersistence { migrateMessages(from, to) }
        }
        val autojoin = session.state.autojoin
        if (autojoin != session.config.autojoin) {
            session.config = session.config.copy(autojoin = autojoin)
            networkStore.update(session.config)
        }
        history.resume(session.config.id)
    }

    private fun currentRef(ref: ConversationRef): ConversationRef {
        val mapping = sessions[ref.networkId]?.state?.casemapping ?: return ref
        val folded = mapping.fold(ref.rawTarget)
        return if (ref.normalizedTarget == folded) ref else ref.copy(normalizedTarget = folded)
    }

    private fun migrateConversationMetadata(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        readMarkers.rename(fromKey, toKey)
        mutes.rename(fromKey, toKey)
        channelOrder.rename(fromKey, toKey)
        _mutedState.value = mutes.all()
        _orderState.value = channelOrder.snapshot()
    }

    private suspend fun migrateMessages(from: ConversationRef, to: ConversationRef) {
        messageStore.renameConversation(from, to) { removed, retained ->
            synchronized(selectionLock) {
                updateAllBuffersForNetwork(from.networkId) { buffer ->
                    buffer.copy(
                        messages = buffer.messages.map { message ->
                            if (message.storedRowId == removed) message.copy(storedRowId = retained) else message
                        },
                        replyDraft = buffer.replyDraft?.let { draft ->
                            if (draft.storedRowId == removed) draft.copy(storedRowId = retained) else draft
                        },
                    )
                }
            }
        }
    }

    private fun reconcileCaseMapping(session: Session) = synchronized(selectionLock) {
        val mapping = session.state.casemapping
        val existing = _buffers.value.values.filter { it.ref.networkId == session.config.id }
        val refs = existing.associate { it.key to it.ref }
        if (session.storedMappingKnown) {
            val order = channelOrder.snapshot()
            val metadataKeys = readMarkers.all().keys + mutes.all() + order.pinnedKeys +
                order.groups.flatMap { it.memberKeys } + order.partedKeys + refs.keys
            for (key in metadataKeys) {
                if (key.substringBefore("|") != session.config.id) continue
                val raw = refs[key]?.rawTarget ?: key.substringAfter("|")
                migrateConversationMetadata(key, "${session.config.id}|${mapping.fold(raw)}")
            }
            enqueuePersistence {
                val persisted = messageStore.knownConversations(session.config.id)
                for (from in persisted) {
                    val raw = refs[from.storageKey]?.rawTarget ?: from.rawTarget
                    val to = from.copy(rawTarget = raw, normalizedTarget = mapping.fold(raw))
                    migrateMessages(from, to)
                    synchronized(session.state) {
                        if (sessions[session.config.id] === session) {
                            migrateConversationMetadata(from.storageKey, to.storageKey)
                        }
                    }
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
            first.copy(
                ref = ref,
                messages = messages,
                topic = buffers.firstNotNullOfOrNull { it.topic },
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
        val merged = LinkedHashMap<String, MutableMap<String, Set<String>>>()
        for (buffer in buffers) {
            for ((msgid, reactions) in buffer.reactions) {
                val target = merged.getOrPut(msgid) { LinkedHashMap() }
                for ((emoji, senders) in reactions) target[emoji] = target[emoji].orEmpty() + senders
            }
        }
        return merged
    }

    private fun ensureBaseBuffers(session: Session) {
        buffer(ConversationRef.server(session.config.id))
        for (channel in session.config.autojoin) {
            val ref = ConversationRef.channel(session.config.id, channel, session.state.casemapping)
            if (!channelOrder.isParted(ref.storageKey)) {
                updateBuffer(ref) {
                    it.copy(joinState = if (session.recovery.desiredConnection) JoinState.JOINING else JoinState.IDLE)
                }
            }
        }
    }

    public data class BouncerEntry(
        public val networkId: String,
        public val netId: String,
        public val attributes: dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.Attributes,
        public val isBoundToThisConnection: Boolean,
    )

    private val _bouncerVersion = MutableStateFlow(0)
    public val bouncerVersion: StateFlow<Int> = _bouncerVersion.asStateFlow()

    private fun bumpBouncerVersion() {
        _bouncerVersion.value += 1
    }

    public fun hasBouncerSession(): Boolean =
        sessions.values.any { it.state.isBouncerDiscovery }

    public fun bouncerSessionIds(): List<String> =
        sessions.values.filter { it.state.isBouncerDiscovery }.map { it.config.id }

    public fun bouncerEntries(): List<BouncerEntry> =
        sessions.values.filter { it.state.isBouncerDiscovery }.flatMap { session ->
            session.state.bouncerStore.all().map { (netId, attrs) ->
                BouncerEntry(
                    networkId = session.config.id,
                    netId = netId,
                    attributes = attrs,
                    isBoundToThisConnection = session.state.bouncerStore.boundNetId == netId,
                )
            }
        }

    public fun addBouncerNetwork(networkId: String, draft: dev.brentdevs.yardhal.core.data.BouncerNetworkDraft) {
        val session = sessions[networkId] ?: return
        if (!session.state.isBouncerDiscovery) {
            appendSystem(session, ConversationRef.server(networkId), "soju mgmt: not a discovery connection")
            return
        }
        val validationError = draft.addrValidationError()
        if (validationError != null) {
            appendSystem(session, ConversationRef.server(networkId), "soju mgmt: $validationError")
            return
        }
        val line = dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.addNetworkCommand(
            draft.toAttributes(),
        )
        appendSystem(session, ConversationRef.server(networkId), "soju mgmt: → $line")
        sendRaw(session, line)
    }

    public fun deleteBouncerNetwork(networkId: String, netId: String) {
        sessions[networkId]?.let { sendRaw(it, dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.delNetworkCommand(netId)) }
    }

    public fun setBouncerEnabled(networkId: String, networkName: String, enabled: Boolean) {
        sessions[networkId]?.let {
            sendRaw(it, "PRIVMSG BouncerServ :${dev.brentdevs.yardhal.core.data.BouncerServCommand.networkUpdate(networkName, enabled)}")
        }
    }

    public fun connectBouncerNetwork(networkId: String, netId: String, connect: Boolean) {
        sessions[networkId]?.let {
            val verb = if (connect) "CONNECTNETWORK" else "DISCONNECTNETWORK"
            sendRaw(it, "BOUNCER $verb $netId")
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
        val buffer = _buffers.value[storageKey] ?: return
        sendRaw(session, "REDACT ${buffer.ref.rawTarget} $msgid")
        updateBufferKey(storageKey) { current -> redactInBuffer(current, msgid) }
    }

    private fun updateAllBuffersForNetwork(networkId: String, transform: (ConversationBuffer) -> ConversationBuffer) {
        _buffers.update { current ->
            current.mapValues { (_, buffer) ->
                if (buffer.ref.networkId == networkId) transform(buffer) else buffer
            }
        }
    }

    public fun ensureMembers(networkId: String, storageKey: String) {
        val session = sessions[networkId] ?: return
        val buffer = _buffers.value[storageKey] ?: return
        if (buffer.ref.kind != ConversationKind.CHANNEL || buffer.joinState != JoinState.JOINED) return
        val needsMembers = buffer.members.isEmpty()
        val needsPresence = buffer.members.any { buffer.memberPresence[it.nick]?.away == null }
        if (!needsMembers && !needsPresence) return
        val query = synchronized(session.state) {
            if (!session.state.registered) return
            if (session.state.hasWhox) WHOX_QUERY else ""
        }
        sendRaw(session, "WHO ${buffer.ref.rawTarget} $query".trimEnd())
    }

    public fun react(networkId: String, storageKey: String, msgid: String, emoji: String) {
        val session = sessions[networkId] ?: return
        val buffer = _buffers.value[storageKey] ?: return
        val own = session.state.ownNick
        var reactionVerb: String? = null
        updateBufferKey(storageKey) { current ->
            var reactions = current.reactions
            if (!current.messages.any { it.msgid == msgid }) return@updateBufferKey current
            val perMessage = (reactions[msgid] ?: emptyMap()).toMutableMap()
            when {
                perMessage[emoji].orEmpty().contains(own) -> {
                    val remaining = perMessage[emoji].orEmpty().toMutableSet().apply { remove(own) }
                    reactionVerb = "unreact"
                    if (remaining.isEmpty()) perMessage.remove(emoji) else perMessage[emoji] = remaining
                }
                else -> {
                    reactionVerb = "react"
                    perMessage[emoji] = perMessage[emoji].orEmpty() + own
                }
            }
            reactions = if (perMessage.isEmpty()) reactions - msgid else reactions + (msgid to perMessage.toMap())
            current.copy(reactions = reactions)
        }
        reactionVerb?.let { verb ->
            sendRaw(session, "@+draft/$verb=$emoji;+draft/refs=$msgid TAGMSG ${buffer.ref.rawTarget}")
        }
    }

    public fun setReplyDraft(networkId: String, storageKey: String, message: ChatMessage?) {
        updateBufferKey(storageKey) { it.copy(replyDraft = message) }
    }

    public fun directMessageKey(networkId: String, fromKey: String, nick: String): String {
        val session = sessions[networkId]
        val casemapping = session?.state?.casemapping ?: dev.brentdevs.yardhal.core.protocol.CaseMapping.RFC1459
        val ref = ConversationRef.directMessage(networkId, nick, casemapping)
        buffer(ref)
        return ref.storageKey
    }

    public fun ensureConversation(networkId: String, conversation: String): String {
        val session = sessions[networkId]
        val casemapping = session?.state?.casemapping ?: dev.brentdevs.yardhal.core.protocol.CaseMapping.RFC1459
        val ref = when {
            conversation == ConversationRef.SERVER_TARGET -> ConversationRef.server(networkId)
            conversation.firstOrNull()?.let { it in "#&" } == true ->
                ConversationRef.channel(networkId, conversation, casemapping)
            else -> ConversationRef.directMessage(networkId, conversation, casemapping)
        }
        buffer(ref)
        return ref.storageKey
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

    public fun openSearchHit(hit: dev.brentdevs.yardhal.core.data.FtsHit): String {
        val storageKey = ensureConversation(hit.networkId, hit.conversation)
        val ref = _buffers.value[storageKey]?.ref ?: return storageKey
        enqueuePersistence {
            val context = messageStore.around(ref, hit.rowId, hit.timestampMs)
            updateBufferKey(currentRef(ref).storageKey) { current ->
                current.copy(messages = mergeSearchContext(current.messages, context, nextLocalId = idGenerator::getAndIncrement))
            }
        }
        return storageKey
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

    public fun uploadAndShare(
        networkId: String,
        storageKey: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ) {
        val session = sessions[networkId] ?: return
        val endpoint = session.state.filehostEndpoint ?: run {
            appendSystem(session, _buffers.value[storageKey]?.ref ?: ConversationRef.server(networkId), "This network does not advertise a filehost (soju.im/FILEHOST).")
            return
        }
        val config = session.effective ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val uploaded = try {
                dev.brentdevs.yardhal.core.client.FilehostUploader.upload(
                    endpointUrl = endpoint,
                    file = dev.brentdevs.yardhal.core.client.OutgoingFile(fileName, mimeType, bytes),
                    ircConnectionIsTls = config.tls,
                    saslUser = config.saslAuthcid,
                    saslPassword = config.saslPassword,
                )
            } catch (error: dev.brentdevs.yardhal.core.client.FilehostException) {
                appendSystem(session, _buffers.value[storageKey]?.ref ?: ConversationRef.server(networkId), "Upload failed: ${error.message}")
                return@launch
            }
            sendMessage(
                session = session,
                ref = _buffers.value[storageKey]?.ref ?: return@launch,
                wireText = uploaded.url,
                optimisticText = uploaded.url,
                attachmentUrl = uploaded.url,
            )
        }
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
        persist: Boolean = true,
        reconcilePendingEcho: Boolean = false,
        echoLabel: String? = null,
        senderAccount: String? = null,
        channelContext: String? = null,
        historyContext: Boolean = false,
        optimistic: Boolean = false,
    ) {
        val presentedText = redactPresentation(session, text)
        val newLocalId = idGenerator.getAndIncrement()
        val muted = mutes.isMuted(ref.storageKey)
        var appended = false
        var persistedLocalId = newLocalId
        var persistedTimestampMs = timestampMs
        updateBuffer(ref) { buffer ->
            appended = false
            if (reconcilePendingEcho && !playback) {
                reconcileEcho(buffer, kind, presentedText, echoLabel, msgid, timestampMs, attachmentUrl, senderAccount)?.let { return@updateBuffer it }
            }
            val byMsgid = if (msgid == null) -1 else buffer.messages.indexOfFirst { it.msgid == msgid }
            val byContent = if (byMsgid >= 0 || !playback) -1 else {
                buffer.messages.uniqueHistoryIndex { message ->
                    (msgid == null || message.msgid == null) && message.sender == sender &&
                        message.kind == kind && message.text == presentedText && message.timestampMs == timestampMs
                }
            }
            val duplicateIndex = if (byMsgid >= 0) byMsgid else byContent
            if (duplicateIndex >= 0) {
                val existing = buffer.messages[duplicateIndex]
                persistedLocalId = existing.localId
                val updated = existing.copy(
                    msgid = existing.msgid ?: msgid,
                    replyToMsgid = existing.replyToMsgid ?: replyToMsgid,
                    attachmentUrl = existing.attachmentUrl ?: attachmentUrl,
                    channelContext = existing.channelContext ?: channelContext,
                    senderAccount = existing.senderAccount ?: senderAccount,
                    historyContext = existing.historyContext && historyContext,
                )
                if (updated == existing) return@updateBuffer buffer
                val messages = buffer.messages.toMutableList()
                messages[duplicateIndex] = updated
                return@updateBuffer buffer.copy(messages = messages)
            }
            val entry = ChatMessage(
                localId = newLocalId,
                sender = sender,
                kind = kind,
                text = presentedText,
                timestampMs = timestampMs,
                sentByUs = sentByUs,
                highlightsMe = highlightsMe,
                msgid = msgid,
                replyToMsgid = replyToMsgid,
                attachmentUrl = attachmentUrl,
                playback = playback,
                pendingEcho = pendingEcho,
                echoLabel = echoLabel.takeIf { pendingEcho },
                senderAccount = senderAccount,
                channelContext = channelContext,
                historyContext = historyContext,
            )
            appended = true
            val countsAsUnread = entry.countsAsUnread && !muted && timestampMs > buffer.readAtMs
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
                unreadFromTimestampMs = boundary,
            )
        }
        if (persist) {
            val localId = if (msgid == null) {
                persistedLocalId
            } else {
                _buffers.value[ref.storageKey]?.messages?.firstOrNull { it.msgid == msgid }?.localId ?: persistedLocalId
            }
            persistAsync(session, ref, sender, kind, presentedText, msgid, persistedTimestampMs, sentByUs, localId, channelContext, historyContext)
        }
        if (appended && highlightsMe && !sentByUs && !playback && !muted) {
            notifier.onHighlight(session.config.name, sender, ConversationNames.forRef(ref), presentedText)
        }
    }

    private fun persistAsync(
        session: Session,
        ref: ConversationRef,
        sender: String,
        kind: MessageKind,
        text: String,
        msgid: String?,
        timestampMs: Long,
        sentByUs: Boolean,
        localId: Long,
        channelContext: String?,
        historyContext: Boolean,
    ) {
        enqueuePersistence {
            val stored = StoredMessage(
                networkId = session.config.id,
                conversation = ref,
                msgid = msgid,
                senderNick = sender,
                senderUser = null,
                senderHost = null,
                kind = kind,
                text = text,
                sentByUs = sentByUs,
                timestampMs = timestampMs,
                channelContext = channelContext,
                historyContext = historyContext,
            )
            val inserted = messageStore.recordWithRowId(stored)
            val persisted = if (inserted != null) stored else messageStore.findStoredMessage(stored) ?: return@enqueuePersistence
            val rowId = inserted ?: persisted.rowId
            val key = _buffers.value.values.firstOrNull { buffer ->
                buffer.ref.networkId == ref.networkId && buffer.messages.any { it.localId == localId }
            }?.key ?: return@enqueuePersistence
            updateBufferKey(key) { current ->
                val index = current.messages.indexOfFirst { it.localId == localId }
                if (index < 0) current else {
                    val updated = current.messages.toMutableList()
                    val message = updated[index]
                    updated[index] = message.copy(storedRowId = rowId, msgid = message.msgid ?: persisted.msgid,
                        sentByUs = message.sentByUs || persisted.sentByUs,
                        historyContext = message.historyContext && persisted.historyContext)
                    current.copy(messages = updated)
                }
            }
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
            joinState = if (channelOrder.isParted(ref.storageKey)) JoinState.IDLE else JoinState.JOINED,
            history = ConversationHistory(gaps = restoredGaps(ref)),
        )
        _buffers.update { it + (ref.storageKey to created) }
        created
    }

    public fun markRead(storageKey: String) {
        val latest = _buffers.value[storageKey]?.messages?.maxOfOrNull { it.timestampMs } ?: return
        readMarkers.advance(storageKey, latest)
        updateBufferKey(storageKey) { it.copy(hasUnread = false, readAtMs = latest) }
        val networkId = storageKey.substringBefore("|")
        val session = sessions[networkId] ?: return
        if ("draft/read-marker" !in session.state.supportedCaps) return
        val target = session.bufferTargetFor(storageKey) ?: return
        val iso = java.time.Instant.ofEpochMilli(latest).toString()
        sendRaw(session, "MARKREAD $target timestamp=$iso")
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
        val now = clock()
        if (now - lastTypingSentAt < TYPING_SEND_INTERVAL_MS) return
        lastTypingSentAt = now
        sendRaw(session, "@+typing=active TAGMSG ${buffer.ref.rawTarget}")
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
                UiNetwork(
                    id = config.id,
                    name = config.name,
                    host = config.host,
                    status = session?.statusFlow?.value ?: ConnectionStatus.DISCONNECTED,
                    ownNick = live?.state?.ownNick ?: config.nick,
                    hasBotMode = live?.state?.botModeLetter != null,
                    accountBanAvailable = live?.state?.accountExtban != null,
                    iconUrl = live?.state?.networkIconUrl,
                    connectionPhase = session?.recovery?.phase ?: if (config.userDisconnected) {
                        RecoveryPhase.USER_DISCONNECTED
                    } else {
                        RecoveryPhase.DISCONNECTED
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
        if (sessions[session.config.id] === session) session.reconnector?.sendLine(line)
    }

    private fun sendLabeled(session: Session, origin: ConversationRef, command: LabeledCommand, line: String) {
        val label = synchronized(session.state) { session.state.issueLabel(origin, command, clock()) }
        sendRaw(session, if (label == null) line else labelLine(line, label))
    }

    private fun SlashCommand.isLocal(): Boolean =
        this is SlashCommand.Query || this is SlashCommand.IgnoreAdd ||
            this is SlashCommand.IgnoreRemove || this is SlashCommand.Help

    public fun sendText(networkId: String, storageKey: String, input: String): Boolean {
        val session = sessions[networkId] ?: return false
        val activeBuffer = _buffers.value[storageKey] ?: return false
        val command = SlashCommandParser.parse(input, activeBuffer.ref.rawTarget) ?: return false
        if (session.statusFlow.value != ConnectionStatus.REGISTERED && !command.isLocal()) return false
        val replyTo = activeBuffer.replyDraft?.takeIf { command is SlashCommand.PlainMessage }
        if (replyTo != null) {
            updateBufferKey(storageKey) { it.copy(replyDraft = null) }
        }
        dispatchCommand(session, activeBuffer.ref, command, replyToMsgid = replyTo?.msgid)
        return true
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
    ) {
        when (command) {
            is SlashCommand.PlainMessage -> sendComposed(session, active, command.text, replyToMsgid)
            is SlashCommand.EscapedMessage -> sendComposed(session, active, "/" + command.text, replyToMsgid)
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
            is SlashCommand.Query -> buffer(resolveTargetRef(session, command.nick))
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
            is SlashCommand.Whois -> {
                synchronized(session.state) { session.state.whoisExpected = true }
                sendLabeled(session, active, LabeledCommand.WHOIS, "WHOIS ${command.target} ${command.target}")
            }
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

    private fun sendComposed(session: Session, ref: ConversationRef, text: String, replyToMsgid: String?) {
        val normalized = IrcMultiline.normalize(text)
        if ('\n' !in normalized) {
            sendMessage(session, ref, normalized, replyToMsgid = replyToMsgid)
            return
        }
        val limits = session.state.outboundMultilineLimits()
        if (limits == null) {
            normalized.split('\n').filter { it.isNotBlank() }.forEachIndexed { index, line ->
                sendMessage(session, ref, line, replyToMsgid = replyToMsgid.takeIf { index == 0 })
            }
            return
        }
        val echoed = "echo-message" in session.state.supportedCaps
        val budget = IrcMultiline.lineBudget(session.state.ownNick, ref.rawTarget)
        IrcMultiline.split(normalized, limits, budget).forEachIndexed { index, lines ->
            val reply = replyToMsgid.takeIf { index == 0 }
            val label = synchronized(session.state) { session.state.issueLabel(ref, LabeledCommand.PRIVMSG, clock()) }
            appendChat(
                session = session,
                ref = ref,
                sender = session.state.ownNick,
                kind = MessageKind.PRIVMSG,
                text = IrcMultiline.combine(lines),
                msgid = null,
                timestampMs = clock(),
                sentByUs = true,
                highlightsMe = false,
                replyToMsgid = reply,
                pendingEcho = echoed,
                persist = !echoed,
                echoLabel = label,
                optimistic = true,
            )
            val batchTags = buildMap {
                if (label != null) put("label", label)
                if (reply != null) put("+draft/reply", reply)
            }
            val reference = "yml" + idGenerator.getAndIncrement()
            for (frame in IrcMultiline.frame(reference, "PRIVMSG", ref.rawTarget, lines, batchTags)) {
                session.reconnector?.send(frame)
            }
        }
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
    ) {
        val safeWireText = sanitizeOutboundText(wireText)
        val echoExpected = "echo-message" in session.state.supportedCaps
        val label = synchronized(session.state) { session.state.issueLabel(origin, LabeledCommand.PRIVMSG, clock()) }
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
                persist = !echoExpected,
                echoLabel = label,
                optimistic = true,
            )
        }
        val tags = buildMap {
            if (label != null) put("label", label)
            if (replyToMsgid != null) put("+draft/reply", replyToMsgid)
            if (attachmentUrl != null) put("+draft/attachment", attachmentUrl)
        }
        if (tags.isNotEmpty()) {
            session.reconnector?.send(
                IrcMessage(tags = tags, command = "PRIVMSG", parameters = listOf(ref.rawTarget, safeWireText)),
            )
        } else {
            sendRaw(session, "PRIVMSG ${ref.rawTarget} :$safeWireText")
        }
    }
}

internal fun sanitizeOutboundText(raw: String): String =
    raw.replace("\r", " ").replace("\n", " ")
        .replace("\u0000", "")
