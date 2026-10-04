package dev.brentdevs.yardhal.coordinator

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
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.MentionMatcher
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.SlashCommand
import dev.brentdevs.yardhal.core.data.SlashCommandParser
import dev.brentdevs.yardhal.core.data.StoredMessage
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.IrcCtcp
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcMultiline
import dev.brentdevs.yardhal.core.protocol.IrcMetadata
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
    public val mutes: MuteStore,
    private val vault: CredentialVault,
    private val channelOrder: dev.brentdevs.yardhal.core.data.ChannelOrderStore,
    private val connectionFactory: ConnectionFactory,
    private val stsPolicies: StsPolicyStore,
    private val notifier: HighlightNotifier = HighlightNotifier { _, _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
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

    public fun dismissWhois() {
        _whois.value = null
    }

    private var ignoreStore: dev.brentdevs.yardhal.core.data.IgnoreStore? = null

    public fun attachIgnores(store: dev.brentdevs.yardhal.core.data.IgnoreStore) {
        ignoreStore = store
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val sessionLifecycleLock = Any()
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
        const val HISTORY_PAGE_SIZE = 200
    }

    private data class MessageContentKey(
        val sender: String,
        val kind: MessageKind,
        val text: String,
        val timestampMs: Long,
    )

    private inner class Session(@Volatile var config: NetworkConfig, casemapping: CaseMapping) {
        val state = PerNetworkState(config.id, config.nick, config.autojoin).also { it.casemapping = casemapping }
        var storedMappingKnown = _buffers.value.values.any { it.ref.networkId == config.id }
        val lifecycleJob = SupervisorJob(scope.coroutineContext[Job])
        val lifecycleScope = CoroutineScope(scope.coroutineContext + lifecycleJob)
        val statusFlow = MutableStateFlow(ConnectionStatus.CONNECTING)
        var reconnector: IrcReconnector? = null
        var connection: IrcConnection? = null
        @Volatile var quitRequested: Boolean = false
        @Volatile var stsUpgradePort: Int? = null

        init {
            lifecycleJob.invokeOnCompletion {
                reconnector?.stop()
                connection?.disconnect()
            }
        }
    }

    public fun startAll() {
        for (config in networkStore.all()) connect(config)
    }

    public fun connect(config: NetworkConfig) {
        synchronized(sessionLifecycleLock) {
            if (sessions.containsKey(config.id)) return
            startSession(config)
        }
    }

    public fun updateNetwork(config: NetworkConfig, credentialsChanged: Boolean = false): Boolean =
        synchronized(sessionLifecycleLock) update@{
            val saved = networkStore.byId(config.id) ?: return@update false
            val session = sessions[config.id]
            if (session == null) {
                if (!networkStore.update(config)) return@update false
                clearNewAutojoins(saved, config, CaseMapping.RFC1459)
                if (credentialsChanged || !saved.connectionSettingsMatch(config)) startSession(config)
            } else {
                synchronized(session.state) {
                    if (!networkStore.update(config)) return@update false
                    clearNewAutojoins(saved, config, session.state.casemapping)
                    if (!credentialsChanged && session.config.connectionSettingsMatch(config)) {
                        session.config = config
                        refreshNetworkStates()
                    } else {
                        stopSession(session, "Network settings changed")
                        startSession(config, session.state.casemapping)
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
            saslPassword == other.saslPassword

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

    private fun startSession(config: NetworkConfig, casemapping: CaseMapping = CaseMapping.RFC1459) {
        val session = Session(config, casemapping)
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
                ) JoinState.JOINING else buffer.joinState,
            )
        }
        ensureBaseBuffers(session)
        restoreKnownConversations(session)
        refreshNetworkStates()
        launchSession(session)
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
                        if (ref.kind == ConversationKind.CHANNEL) markJoining(ref) else buffer(ref)
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
            val session = sessions[networkId] ?: return
            synchronized(session.state) {
                stopSession(session, quitReason)
                _profiles.update { it - networkId }
                refreshNetworkStates()
            }
        }
    }

    private fun stopSession(session: Session, quitReason: String) {
        session.quitRequested = true
        sendRaw(session, "QUIT :$quitReason")
        sessions.remove(session.config.id, session)
        session.reconnector?.stop()
        session.connection?.disconnect()
        session.lifecycleJob.cancel()
    }

    public fun removeNetwork(networkId: String) {
        disconnect(networkId)
        networkStore.remove(networkId)
        enqueuePersistence { messageStore.deleteNetwork(networkId) }
        synchronized(selectionLock) {
            if (selectedStorageKey?.substringBefore("|") == networkId) {
                selectedStorageKey = null
                pendingRenamedKey = null
            }
            _buffers.update { buffers -> buffers.filterValues { it.ref.networkId != networkId } }
        }
        _profiles.update { it - networkId }
    }

    private fun launchSession(session: Session) {
        val reconnector = IrcReconnector(
            scope = session.lifecycleScope,
            policy = ReconnectPolicy(initialDelayMillis = 1_000, maxDelayMillis = 30_000),
            connectionFactory = {
                synchronized(session.state) {
                    session.lifecycleJob.ensureActive()
                    connectionFactory.create(effectiveConfig(session.config, session.stsUpgradePort)) { port ->
                        synchronized(session.state) {
                            if (sessions[session.config.id] === session) session.stsUpgradePort = port
                        }
                    }.also { session.connection = it }
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
                    if (state is dev.brentdevs.yardhal.core.client.ReconnectState.Stopped &&
                        sessions[session.config.id] === session &&
                        !session.quitRequested
                    ) {
                        session.statusFlow.value = ConnectionStatus.DISCONNECTED
                        refreshNetworkStates()
                    }
                }
            }
        }
        reconnector.start()
    }

    private fun effectiveConfig(config: NetworkConfig, stsUpgradePort: Int? = null): NetworkConfig {
        val password = config.saslPasswordRef?.let { vault.readPassword(it) }
        val decision = StsResolver.decide(
            stsPolicies,
            config.host,
            config.port,
            config.tls,
            clock() / 1000,
        )
        val policyPort = (decision as? StsUpgradeDecision.UpgradeRequired)?.port
        val securePort = policyPort ?: stsUpgradePort
        return config.copy(
            port = securePort ?: config.port,
            tls = config.tls || securePort != null,
            saslAuthcid = config.saslAuthcid.takeIf { config.saslPasswordRef != null },
            saslPassword = password,
        )
    }

    private fun routeEvent(session: Session, event: IrcEvent) {
        synchronized(session.state) {
            if (sessions[session.config.id] !== session) return
            val previousMapping = session.state.casemapping
            val previousSupport = session.state.isupport
            val effects = session.state.apply(event, inboundContext(session))
            if (previousMapping != session.state.casemapping) reconcileCaseMapping(session)
            if (previousSupport !== session.state.isupport) session.storedMappingKnown = true
            for (effect in effects) processEffect(session, effect)
        }
    }

    private fun inboundContext(session: Session): InboundContext {
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
            latestReadMarkerMs = { readMarkers.all().values.maxOrNull() },
            isParted = { channel -> channelOrder.isParted(session.state.channelRef(channel).storageKey) },
        )
    }

    private fun processEffect(session: Session, effect: InboundEffect) {
        when (effect) {
            is InboundEffect.SendRaw -> sendRaw(session, effect.line)
            is InboundEffect.ScheduleRaw -> session.lifecycleScope.launch {
                delay(effect.delayMs)
                synchronized(session.state) {
                    if (sessions[session.config.id] === session &&
                        session.state.connectionEpoch == effect.connectionEpoch
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
            is InboundEffect.RedactMessage -> updateAllBuffersForNetwork(session.config.id) { buffer ->
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

    private fun removeBuffer(storageKey: String) {
        synchronized(selectionLock) {
            if (selectedStorageKey == storageKey || pendingRenamedKey == storageKey) pendingRenamedKey = null
            _buffers.update { it - storageKey }
        }
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
                val moved = existing.copy(ref = to, displayName = ConversationNames.forRef(to))
                val merged = current[toKey]?.takeIf { fromKey != toKey }?.let { occupant ->
                    moved.copy(
                        messages = (occupant.messages + moved.messages).sortedBy { it.timestampMs },
                        hasUnread = moved.hasUnread || occupant.hasUnread,
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
            if (fromKey in historyLoaded.value) historyLoaded.value = historyLoaded.value - fromKey + toKey
            enqueuePersistence { migrateMessages(from, to) }
        }
        val autojoin = session.state.autojoin
        if (autojoin != session.config.autojoin) {
            session.config = session.config.copy(autojoin = autojoin)
            networkStore.update(session.config)
        }
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
        val loaded = historyLoaded.value
        val groups = existing.groupBy { "${session.config.id}|${mapping.fold(it.ref.rawTarget)}" }
        val replacement = groups.mapValues { (_, buffers) ->
            val first = buffers.first()
            val ref = first.ref.copy(normalizedTarget = mapping.fold(first.ref.rawTarget))
            val messages = buffers.flatMap { it.messages }
                .distinctBy { it.msgid ?: MessageContentKey(it.sender, it.kind, it.text, it.timestampMs) }
                .sortedBy { it.timestampMs }
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
            )
        }
        val selected = pendingRenamedKey ?: selectedStorageKey
        selected?.let { refs[it] }?.let { ref ->
            val key = "${ref.networkId}|${mapping.fold(ref.rawTarget)}"
            if (selected != key) pendingRenamedKey = key
        }
        historyLoaded.value = loaded - refs.keys + groups.filterValues { buffers ->
            buffers.all { it.key in loaded }
        }.keys
        _buffers.update { current -> current - refs.keys + replacement }
        restoreKnownConversations(session)
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
            if (!channelOrder.isParted(ref.storageKey)) markJoining(ref)
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

    private val rawLogs = LinkedHashMap<String, ArrayDeque<RawFrame>>()
    private val _rawLogVersion = MutableStateFlow(0)
    public val rawLogVersion: StateFlow<Int> = _rawLogVersion.asStateFlow()

    public fun ingestRaw(networkId: String, outbound: Boolean, line: String) {
        val deque = rawLogs.getOrPut(networkId) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(RawFrame(outbound, line))
            while (deque.size > 400) deque.removeFirst()
        }
        _rawLogVersion.value += 1
    }

    public fun rawLog(networkId: String): List<RawFrame> {
        val deque = rawLogs.getOrPut(networkId) { ArrayDeque() }
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
                current.copy(messages = mergeSearchContext(current.messages, context, idGenerator::getAndIncrement))
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
        val config = effectiveConfig(session.config, session.stsUpgradePort)
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
    ) {
        val newLocalId = idGenerator.getAndIncrement()
        val muted = mutes.isMuted(ref.storageKey)
        updateBuffer(ref) { buffer ->
            if (reconcilePendingEcho && !playback) {
                reconcileEcho(buffer, kind, text, echoLabel, msgid, timestampMs, attachmentUrl, senderAccount)?.let { return@updateBuffer it }
            }
            if (msgid != null && buffer.messages.any { it.msgid == msgid }) return@updateBuffer buffer
            val entry = ChatMessage(
                localId = newLocalId,
                sender = sender,
                kind = kind,
                text = text,
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
            )
            val countsAsUnread = entry.countsAsUnread && !muted && timestampMs > buffer.readAtMs
            val boundary = if (countsAsUnread) {
                val existing = buffer.unreadFromTimestampMs
                if (existing == null || existing <= buffer.readAtMs) timestampMs else minOf(existing, timestampMs)
            } else {
                buffer.unreadFromTimestampMs
            }
            buffer.copy(
                messages = buffer.messages + entry,
                hasUnread = buffer.hasUnread || countsAsUnread,
                unreadFromTimestampMs = boundary,
            )
        }
        if (persist) {
            val localId = if (msgid == null) {
                newLocalId
            } else {
                _buffers.value[ref.storageKey]?.messages?.firstOrNull { it.msgid == msgid }?.localId ?: newLocalId
            }
            persistAsync(session, ref, sender, kind, text, msgid, timestampMs, sentByUs, localId, channelContext)
        }
        if (highlightsMe && !sentByUs && !muted) {
            notifier.onHighlight(session.config.name, sender, ConversationNames.forRef(ref), text)
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
    ) {
        enqueuePersistence {
            val rowId = messageStore.recordWithRowId(
                StoredMessage(
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
                ),
            ) ?: return@enqueuePersistence
            val key = _buffers.value.values.firstOrNull { buffer ->
                buffer.ref.networkId == ref.networkId && buffer.messages.any { it.localId == localId }
            }?.key ?: return@enqueuePersistence
            updateBufferKey(key) { current ->
                val index = current.messages.indexOfFirst { it.localId == localId }
                if (index < 0) current else {
                    val updated = current.messages.toMutableList()
                    updated[index] = updated[index].copy(storedRowId = rowId)
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
                    ?: ConversationBuffer(ref = ref, displayName = ConversationNames.forRef(ref))
                current + (ref.storageKey to transform(existing))
            }
        }
    }

    private fun buffer(ref: ConversationRef): ConversationBuffer = synchronized(selectionLock) {
        _buffers.value[ref.storageKey]?.let { return@synchronized it }
        if (ref.storageKey == selectedStorageKey) pendingRenamedKey = null
        val created = ConversationBuffer(
            ref = ref,
            displayName = ConversationNames.forRef(ref),
            joinState = if (channelOrder.isParted(ref.storageKey)) JoinState.IDLE else JoinState.JOINED,
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

    private val historyLoaded = MutableStateFlow<Set<String>>(emptySet())

    public fun loadPersistedHistory(storageKey: String): Boolean = synchronized(selectionLock) {
        if (storageKey in historyLoaded.value) return false
        val buffer = _buffers.value[storageKey] ?: return false
        historyLoaded.value = historyLoaded.value + storageKey
        enqueuePersistence {
            val stored = messageStore.recent(buffer.ref, HISTORY_PAGE_SIZE)
            val currentKey = currentRef(buffer.ref).storageKey
            if (stored.isEmpty()) {
                if (currentKey != storageKey) historyLoaded.value = historyLoaded.value - currentKey
                return@enqueuePersistence
            }
            updateBufferKey(currentKey) { current ->
                val existingMsgids = current.messages.mapNotNull { it.msgid }.toSet()
                val existingSignatures = current.messages.map { it.sender to it.timestampMs }.toSet()
                val marker = maxOf(current.readAtMs, readMarkers.marker(currentKey))
                val restored = stored
                    .filter { it.msgid == null || it.msgid !in existingMsgids }
                    .filter { row -> (row.senderNick to row.timestampMs) !in existingSignatures }
                    .map { row ->
                        ChatMessage(
                            localId = idGenerator.getAndIncrement(),
                            sender = row.senderNick,
                            kind = row.kind,
                            text = row.text,
                            timestampMs = row.timestampMs,
                            sentByUs = row.sentByUs,
                            highlightsMe = false,
                            msgid = row.msgid,
                            storedRowId = row.rowId,
                            channelContext = row.channelContext,
                        )
                    }
                val messages = restored + current.messages
                val firstUnread = if (mutes.isMuted(currentKey)) null else messages
                    .asSequence()
                    .filter { it.timestampMs > marker && it.countsAsUnread }
                    .minOfOrNull { it.timestampMs }
                current.copy(
                    messages = messages,
                    hasUnread = current.hasUnread || firstUnread != null,
                    unreadFromTimestampMs = current.unreadFromTimestampMs ?: firstUnread,
                    readAtMs = marker,
                )
            }
        }
        return true
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
            sessions.values.map { session ->
                UiNetwork(
                    id = session.config.id,
                    name = session.config.name,
                    host = session.config.host,
                    status = session.statusFlow.value,
                    ownNick = session.state.ownNick,
                    hasBotMode = session.state.botModeLetter != null,
                    accountBanAvailable = session.state.accountExtban != null,
                    iconUrl = session.state.networkIconUrl,
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
