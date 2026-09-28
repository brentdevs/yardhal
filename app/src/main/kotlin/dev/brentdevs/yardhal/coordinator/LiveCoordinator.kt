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
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    private val _whois = MutableStateFlow<dev.brentdevs.yardhal.core.data.WhoisInfo?>(null)
    public val whois: StateFlow<dev.brentdevs.yardhal.core.data.WhoisInfo?> = _whois.asStateFlow()

    public fun dismissWhois() {
        _whois.value = null
    }

    private var ignoreStore: dev.brentdevs.yardhal.core.data.IgnoreStore? = null

    public fun attachIgnores(store: dev.brentdevs.yardhal.core.data.IgnoreStore) {
        ignoreStore = store
    }

    private val sessions = LinkedHashMap<String, Session>()

    private companion object {
        const val TYPING_TTL_MS = 6_000L
        const val TYPING_SEND_INTERVAL_MS = 4_000L
        const val HISTORY_PAGE_SIZE = 200
    }

    private inner class Session(val config: NetworkConfig) {
        var casemapping: CaseMapping = CaseMapping.RFC1459
        var ownNick: String = config.nick
        var statusFlow: MutableStateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.CONNECTING)
        var reconnector: IrcReconnector? = null
        var collectorJob: Job? = null
        var quitRequested: Boolean = false
        var supportedCaps: Set<String> = emptySet()
        var hasWhox: Boolean = false
        var filehostEndpoint: String? = null
        @Volatile var stsUpgradePort: Int? = null
        var isBouncerDiscovery: Boolean = false
        var prefixModes: dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes =
            dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes.DEFAULT
        var chathistoryLimit: Int = 0
        var whoisExpected: Boolean = false
        val whois = dev.brentdevs.yardhal.core.data.WhoisAccumulator()
        val bouncerStore: dev.brentdevs.yardhal.core.data.BouncerNetworkStore =
            dev.brentdevs.yardhal.core.data.BouncerNetworkStore()
        val openBatchTypes: MutableMap<String, String> = LinkedHashMap()

        fun isupportCasemapping(): CaseMapping = casemapping
    }

    public fun startAll() {
        for (config in networkStore.all()) connect(config)
    }

    public fun connect(config: NetworkConfig) {
        if (sessions.containsKey(config.id)) return
        val session = Session(config)
        sessions[config.id] = session
        ensureBaseBuffers(session)
        restoreKnownConversations(session)
        refreshNetworkStates()
        launchSession(session)
    }

    private fun restoreKnownConversations(session: Session) {
        scope.launch {
            val known = runCatching {
                messageStore.knownConversations(session.config.id, session.casemapping)
            }.getOrDefault(emptyList())
            for (ref in known) {
                val isAutojoined = ref.kind == ConversationKind.CHANNEL &&
                    ref.rawTarget in session.config.autojoin
                if (channelOrder.isParted(ref.storageKey)) continue
                if (!isAutojoined) buffer(ref)
                if (ref.kind == ConversationKind.CHANNEL &&
                    ref.rawTarget !in session.config.autojoin &&
                    session.statusFlow.value == ConnectionStatus.REGISTERED
                ) {
                    sendRaw(session, "JOIN ${ref.rawTarget}")
                }
            }
        }
    }

    public fun disconnect(networkId: String, quitReason: String = "Yardhal") {
        val session = sessions[networkId] ?: return
        session.quitRequested = true
        sendRaw(session, "QUIT :$quitReason")
        session.reconnector?.stop()
        session.collectorJob?.cancel()
        sessions.remove(networkId)
        refreshNetworkStates()
    }

    public fun removeNetwork(networkId: String) {
        disconnect(networkId)
        networkStore.remove(networkId)
        scope.launch { messageStore.deleteNetwork(networkId) }
        _buffers.value = _buffers.value.filterValues { it.ref.networkId != networkId }
    }

    private fun launchSession(session: Session) {
        val reconnector = IrcReconnector(
            scope = scope,
            policy = ReconnectPolicy(initialDelayMillis = 1_000, maxDelayMillis = 30_000),
            connectionFactory = {
                connectionFactory.create(effectiveConfig(session.config, session.stsUpgradePort)) { port ->
                    session.stsUpgradePort = port
                }
            },
        )
        session.reconnector = reconnector
        session.collectorJob = scope.launch {
            reconnector.events.collect { event -> routeEvent(session, event) }
        }
        scope.launch {
            reconnector.state.collect { state ->
                if (state is dev.brentdevs.yardhal.core.client.ReconnectState.Stopped &&
                    sessions[session.config.id] === session &&
                    !session.quitRequested
                ) {
                    session.statusFlow.value = ConnectionStatus.DISCONNECTED
                    refreshNetworkStates()
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
            saslPassword = password,
        )
    }

    private suspend fun routeEvent(session: Session, event: IrcEvent) {
        when (event) {
            is IrcEvent.Registered -> {
                session.ownNick = event.nickname
                session.statusFlow.value = ConnectionStatus.REGISTERED
                joinAutojoins(session)
                refreshNetworkStates()
            }
            is IrcEvent.CapabilitiesNegotiated -> {
                session.supportedCaps = event.capabilities
                if ("znc.in/playback" in event.capabilities) {
                    val lastSeen = readMarkers.all().values.maxOrNull()
                        ?: (clock() / 1000 - 7 * 24 * 3600)
                    sendRaw(session, "PRIVMSG *playback :playback * start $lastSeen")
                }
                if (dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.CAPABILITY in event.capabilities) {
                    session.isBouncerDiscovery = true
                    bumpBouncerVersion()
                }
            }
            is IrcEvent.MessageReceived -> handleInbound(session, event.message)
            is IrcEvent.Disconnected ->
                if (!session.quitRequested) {
                    session.statusFlow.value = ConnectionStatus.CONNECTING
                    refreshNetworkStates()
                }
            else -> Unit
        }
    }

    private fun joinAutojoins(session: Session) {
        val channels = LinkedHashSet<String>()
        for (channel in session.config.autojoin) channels.add(channel)
        for ((key, buffer) in _buffers.value) {
            if (buffer.ref.networkId == session.config.id && buffer.ref.kind == ConversationKind.CHANNEL) {
                channels.add(buffer.ref.rawTarget)
            }
        }
        for (channel in channels) {
            markJoining(ConversationRef.channel(session.config.id, channel, session.casemapping))
            sendRaw(session, "JOIN $channel")
        }
    }

    private fun ensureBaseBuffers(session: Session) {
        buffer(ConversationRef.server(session.config.id))
        for (channel in session.config.autojoin) {
            buffer(ConversationRef.channel(session.config.id, channel))
        }
    }

    private fun handleInbound(session: Session, message: IrcMessage) {
        val numeric = message.numeric
        when {
            message.command.equals("CAP", true) -> return
            message.command.equals("PRIVMSG", true) || message.command.equals("NOTICE", true) ->
                handleChatMessage(session, message)
            message.command.equals("BATCH", true) -> handleBatchFrame(session, message)
            message.command.equals("REDACT", true) -> handleRedact(session, message)
            message.command.equals("TAGMSG", true) -> handleTagmsg(session, message)
            message.command.equals("BOUNCER", true) -> handleBouncerMessage(session, message)
            message.command.equals("MARKREAD", true) -> handleInboundMarkRead(session, message)
            message.command.equals("JOIN", true) -> handleJoin(session, message)
            message.command.equals("QUIT", true) -> handleQuit(session, message)
            message.command.equals("PART", true) -> handlePart(session, message)
            message.command.equals("TOPIC", true) -> handleTopicVerb(session, message)
            message.command.equals("NICK", true) -> handleNickChange(session, message)
            message.command.equals("FAIL", true) || message.command.equals("WARN", true) ->
                appendServerLine(session, message, message.command.lowercase())
            message.command.equals("NOTE", true) -> appendServerLine(session, message, "note")
            numeric == 332 -> handleTopicNumeric(session, message)
            numeric == 331 || numeric == 353 || numeric == 366 || numeric == 367 ->
                handleNamesRelatedNumeric(session, message, numeric)
            numeric == 352 -> accumulateWhoLine(session, message)
            numeric == 315 -> finalizeNames(session, message)
            numeric == 403 || numeric == 405 || numeric == 437 || numeric == 471 ||
                numeric == 473 || numeric == 474 || numeric == 475 -> {
                val channel = message.parameters.getOrNull(1).orEmpty()
                if (channel.isEmpty() || channel[0] !in "#&") return
                val ref = ConversationRef.channel(session.config.id, channel, session.casemapping)
                buffer(ref)
                markJoinFailed(session, ref, message.parameters.getOrNull(2) ?: "cannot join $channel")
            }
            numeric == 354 -> handleWhoXLine(session, message)
            numeric == 322 -> accumulateListEntry(session, message)
            numeric == 323 -> finalizeChannelList()
            numeric != null && numeric in 301..319 && numeric != 305 && numeric != 306 ->
                handleWhoisNumeric(session, numeric, message)
            numeric == 730 || numeric == 731 -> appendServerLine(session, message, "monitor")
            numeric == 5 -> applyIsupportTokens(session, message)
            numeric != null && numeric in 400..599 -> appendServerLine(session, message, "error")
            else -> appendServerLine(session, message)
        }
    }

    private val netsplitCollapser = dev.brentdevs.yardhal.core.data.NetsplitCollapser()

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
        sessions.values.any { it.isBouncerDiscovery }

    public fun bouncerEntries(): List<BouncerEntry> =
        sessions.values.filter { it.isBouncerDiscovery }.flatMap { session ->
            session.bouncerStore.all().map { (netId, attrs) ->
                BouncerEntry(
                    networkId = session.config.id,
                    netId = netId,
                    attributes = attrs,
                    isBoundToThisConnection = session.bouncerStore.boundNetId == netId,
                )
            }
        }

    private fun handleBouncerMessage(session: Session, message: IrcMessage) {
        val update = dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.parseNetwork(message.parameters)
        if (update != null) {
            if (session.bouncerStore.apply(update)) bumpBouncerVersion()
            return
        }
        val addedId = dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.parseAddNetworkReply(message.parameters)
        if (addedId != null) {
            appendSystem(
                session,
                ConversationRef.server(session.config.id),
                "soju mgmt: network created ($addedId)",
            )
        }
    }

    public fun addBouncerNetwork(networkId: String, draft: dev.brentdevs.yardhal.core.data.BouncerNetworkDraft) {
        val session = sessions[networkId] ?: return
        if (!session.isBouncerDiscovery) {
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

    private var channelListNetworkId: String? = null

    public fun startChannelList(networkId: String) {
        channelListNetworkId = networkId
        _channelList.value = emptyList()
        sessions[networkId]?.let { sendRaw(it, "LIST") }
    }

    private fun accumulateListEntry(session: Session, message: IrcMessage) {
        if (message.parameters.size < 3) return
        if (session.config.id != channelListNetworkId) return
        _channelList.value = _channelList.value + ChannelListEntry(
            name = message.parameters[1],
            users = message.parameters[2].toIntOrNull() ?: 0,
            topic = message.parameters.getOrNull(3).orEmpty(),
        )
    }

    private fun finalizeChannelList() {
        _channelList.value = _channelList.value.sortedByDescending { it.users }
    }

    public fun deleteMessage(networkId: String, storageKey: String, msgid: String) {
        val session = sessions[networkId] ?: return
        val buffer = _buffers.value[storageKey] ?: return
        sendRaw(session, "REDACT ${buffer.ref.rawTarget} $msgid")
        updateBufferKey(storageKey) { current ->
            val index = current.messages.indexOfFirst { it.msgid == msgid }
            if (index < 0) {
                current
            } else {
                val messages = current.messages.toMutableList()
                messages[index] = messages[index].copy(text = "message deleted", kind = MessageKind.SYSTEM)
                current.copy(messages = messages)
            }
        }
    }

    private fun handleBatchFrame(session: Session, message: IrcMessage) {
        val head = message.parameters.firstOrNull() ?: return
        if (message.command.equals("BATCH", true)) {
            when {
                head.startsWith("+") -> {
                    val reference = head.drop(1)
                    val type = message.parameters.getOrNull(1) ?: return
                    session.openBatchTypes[reference] = type
                    netsplitCollapser.onStart(reference, type, emptyList())
                }
                head.startsWith("-") -> {
                    val reference = head.drop(1)
                    session.openBatchTypes.remove(reference)
                    val summary = netsplitCollapser.onEnd(reference) ?: return
                    appendSystem(
                        session,
                        ConversationRef.server(session.config.id),
                        summary.toString(),
                        MessageKind.SYSTEM,
                    )
                }
            }
        }
    }

    private fun handleRedact(session: Session, message: IrcMessage) {
        if (message.parameters.size < 2) return
        val msgid = message.parameters[1]
        updateAllBuffersForNetwork(session.config.id) { buffer ->
            val index = buffer.messages.indexOfFirst { it.msgid == msgid }
            if (index < 0) {
                buffer
            } else {
                val messages = buffer.messages.toMutableList()
                messages[index] = messages[index].copy(text = "message deleted", kind = MessageKind.SYSTEM)
                buffer.copy(messages = messages)
            }
        }
    }

    private fun updateAllBuffersForNetwork(networkId: String, transform: (ConversationBuffer) -> ConversationBuffer) {
        _buffers.value = _buffers.value.mapValues { (_, buffer) ->
            if (buffer.ref.networkId == networkId) transform(buffer) else buffer
        }
    }

    private fun handleQuit(session: Session, message: IrcMessage) {
        val nick = message.prefix?.nick ?: return
        removeMember(session, nick)
        if (netsplitCollapser.isSuppressing("QUIT")) {
            netsplitCollapser.recordSuppressed("QUIT")
        }
    }

    private val pendingNames = LinkedHashMap<String, MutableSet<dev.brentdevs.yardhal.core.data.ChannelMember>>()

    private fun accumulateNames(session: Session, message: IrcMessage) {
        if (message.parameters.size < 2) return
        val (channelRaw, members) = dev.brentdevs.yardhal.core.data.NamesParser.parseNamesLine(
            message.parameters.drop(1),
            session.prefixModes,
        )
        val channel = channelRaw ?: return
        val key = ConversationRef.channel(session.config.id, channel, session.casemapping).storageKey
        pendingNames.getOrPut(key) { LinkedHashSet() }.addAll(members)
    }

    private fun finalizeNames(session: Session, message: IrcMessage) {
        if (message.parameters.size < 2) return
        val channel = message.parameters[1]
        val key = ConversationRef.channel(session.config.id, channel, session.casemapping).storageKey
        val members = pendingNames.remove(key)?.sortedBy { it.nick.lowercase() } ?: return
        val ref = _buffers.value[key]?.ref
        if (ref != null) {
            updateBufferKey(key) { it.copy(members = members, joinState = JoinState.JOINED) }
        }
    }


    private fun handleInboundMarkRead(session: Session, message: IrcMessage) {
        val target = message.parameters.firstOrNull() ?: return
        if (target.startsWith("*")) return
        val markerParam = message.parameters.getOrNull(1) ?: return
        val iso = markerParam.substringAfter("timestamp=", "")
        if (iso.isEmpty()) return
        val millis = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return
        val ref = ConversationRef.channel(session.config.id, target, session.casemapping)
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

    private fun handleNamesRelatedNumeric(session: Session, message: IrcMessage, numeric: Int) {
        if (numeric == 331) {
            handleTopicNumeric(session, message)
            return
        }
        if (numeric == 353) {
            accumulateNames(session, message)
            return
        }
        if (numeric == 366) {
            finalizeNames(session, message)
            return
        }
        if (message.parameters.size >= 2) {
            val channel = message.parameters[1]
            val ref = ConversationRef.channel(session.config.id, channel, session.casemapping)
            val body = message.parameters.drop(2).joinToString(" ").ifEmpty { message.parameters[1] }
            appendSystem(session, ref, body, MessageKind.SYSTEM)
        }
    }

    private fun accumulateWhoLine(session: Session, message: IrcMessage) {
        if (message.parameters.size < 7) return
        val channel = message.parameters[1]
        val rawNick = message.parameters[5]
        val nick = rawNick.substringBefore('!').substringBefore('@')
        val flags = message.parameters.getOrNull(6).orEmpty()
        val symbol = session.prefixModes.symbols.firstOrNull { it in flags }
        val key = ConversationRef.channel(session.config.id, channel, session.casemapping).storageKey
        pendingNames.getOrPut(key) { LinkedHashSet() }
            .add(dev.brentdevs.yardhal.core.data.ChannelMember(nick, symbol))
    }

    public fun ensureMembers(networkId: String, storageKey: String) {
        val session = sessions[networkId] ?: return
        val buffer = _buffers.value[storageKey] ?: return
        if (buffer.ref.kind != ConversationKind.CHANNEL) return
        val needsMembers = buffer.members.isEmpty()
        val needsPresence = buffer.memberPresence.size < buffer.members.size
        if (!needsMembers && !needsPresence) return
        val query = if (session.hasWhox) "%acfhn" else ""
        sendRaw(session, "WHO ${buffer.ref.rawTarget} $query".trimEnd())
    }

    private fun handleWhoXLine(session: Session, message: IrcMessage) {
        if (message.parameters.size < 6) return
        val fields = message.parameters.drop(1)
        val channel = fields[0]
        val nick = fields[2]
        val flags = fields[3]
        val account = fields[4].takeIf { it != "0" }
        if (channel.isNotEmpty() && channel[0] in "#&") {
            val key = ConversationRef.channel(session.config.id, channel, session.casemapping).storageKey
            val symbol = session.prefixModes.symbols.firstOrNull { it in flags }
            _buffers.update { current ->
                val existing = current[key]
                    ?: ConversationBuffer(
                        ref = ConversationRef.channel(session.config.id, channel, session.casemapping),
                        displayName = channel,
                    )
                if (existing.members.any { it.nick.equals(nick, ignoreCase = true) }) {
                    current
                } else {
                    current + (key to existing.copy(
                        members = (existing.members + dev.brentdevs.yardhal.core.data.ChannelMember(nick, symbol))
                            .sortedBy { it.nick.lowercase() },
                    ))
                }
            }
        }
        updateMemberPresence(session, nick, away = flags.contains('G'), account = account)
    }

    private fun updateMemberPresence(session: Session, nick: String, away: Boolean, account: String?) {
        for ((key, buffer) in _buffers.value) {
            if (buffer.ref.networkId != session.config.id) continue
            if (buffer.members.any { it.nick.equals(nick, ignoreCase = true) }) {
                updateBufferKey(key) { it.copy(memberPresence = it.memberPresence + (nick to PresenceState(away, account))) }
            }
        }
    }

    private fun handleWhoisNumeric(session: Session, numeric: Int, message: IrcMessage) {
        if (!session.whoisExpected) return
        session.whois.handle(numeric, message.parameters)?.let { complete ->
            session.whoisExpected = false
            _whois.value = complete
        }
    }

    private fun handleTagmsg(session: Session, message: IrcMessage) {
        val sender = message.prefix?.nick ?: return
        val targetParam = message.parameters.firstOrNull() ?: return
        val ref =
            if (targetParam.isNotEmpty() && targetParam[0] in "#&") {
                ConversationRef.channel(session.config.id, targetParam, session.casemapping)
            } else {
                ConversationRef.directMessage(session.config.id, sender, session.casemapping)
            }

        val react = message.tag("+draft/react")
        val unreact = message.tag("+draft/unreact")
        if (react != null || unreact != null) {
            val refs = (message.tag("+draft/refs") ?: message.tag("+draft/msgids"))
                ?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
            if (refs.isEmpty()) return
            updateBuffer(ref) { buffer ->
                var reactions = buffer.reactions
                for (msgid in refs) {
                    val perMessage = reactions[msgid]?.toMutableMap() ?: mutableMapOf()
                    when {
                        react != null -> {
                            val nicks = perMessage[react].orEmpty().toMutableSet()
                            nicks.add(sender)
                            perMessage[react] = nicks
                        }
                        unreact != null -> {
                            val nicks = perMessage[unreact].orEmpty().toMutableSet()
                            nicks.remove(sender)
                            if (nicks.isEmpty()) perMessage.remove(unreact) else perMessage[unreact] = nicks
                        }
                    }
                    reactions = reactions + (msgid to perMessage.toMap())
                }
                buffer.copy(reactions = reactions)
            }
            return
        }

        val typingValue = message.tag("+typing") ?: return
        updateBuffer(ref) { buffer ->
            val fresh = buffer.typingUsers.filterValues { it > clock() }.toMutableMap()
            when (typingValue) {
                "active" -> fresh[sender] = clock() + TYPING_TTL_MS
                else -> fresh.remove(sender)
            }
            buffer.copy(typingUsers = fresh)
        }
    }

    public fun react(networkId: String, storageKey: String, msgid: String, emoji: String) {
        val session = sessions[networkId] ?: return
        val buffer = _buffers.value[storageKey] ?: return
        val own = session.ownNick
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
        val casemapping = session?.casemapping ?: dev.brentdevs.yardhal.core.protocol.CaseMapping.RFC1459
        val ref = ConversationRef.directMessage(networkId, nick, casemapping)
        buffer(ref)
        return ref.storageKey
    }

    public fun ensureConversation(networkId: String, conversation: String): String {
        val session = sessions[networkId]
        val casemapping = session?.casemapping ?: dev.brentdevs.yardhal.core.protocol.CaseMapping.RFC1459
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

    public suspend fun searchMessages(raw: String): List<dev.brentdevs.yardhal.core.data.FtsHit> =
        messageStore.search(raw)

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
        updateBufferKey(storageKey) { it.copy(joinState = JoinState.JOINING) }
        sendRaw(session, "JOIN ${buffer.ref.rawTarget}")
    }

    private fun markJoining(ref: ConversationRef) {
        updateBuffer(ref) { it.copy(joinState = JoinState.JOINING) }
    }

    private fun markJoinFailed(session: Session, ref: ConversationRef, reason: String) {
        updateBufferKey(ref.storageKey) { it.copy(joinState = JoinState.FAILED) }
        appendSystem(session, ref, reason, MessageKind.SYSTEM)
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
        _buffers.update { it - storageKey }
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
        val endpoint = session.filehostEndpoint ?: run {
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

    private fun applyIsupportTokens(session: Session, message: IrcMessage) {
        val tokens = message.parameters.drop(1).dropLast(1).filter { it.isNotEmpty() }
        for (token in tokens) {
            when {
                token.startsWith("CASEMAPPING=") ->
                    dev.brentdevs.yardhal.core.protocol.CaseMapping.fromWireName(
                        token.removePrefix("CASEMAPPING="),
                    )?.let { session.casemapping = it }
                token.startsWith("PREFIX=") ->
                    parsePrefixToken(token.removePrefix("PREFIX="))?.let { session.prefixModes = it }
                token.startsWith("CHATHISTORY=") ->
                    session.chathistoryLimit = token.substringAfter('=').toIntOrNull()?.coerceAtMost(200) ?: 0
                token == "WHOX" -> session.hasWhox = true
                token.startsWith("soju.im/FILEHOST=") ->
                    session.filehostEndpoint = token.removePrefix("soju.im/FILEHOST=")
                token.startsWith(dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.ISUPPORT_NET_ID_TOKEN + "=") ->
                    session.bouncerStore.bind(
                        token.substringAfter('='),
                    ).also { bumpBouncerVersion() }
            }
        }
    }

    private fun parsePrefixToken(raw: String): dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes? {
        if (!raw.startsWith('(')) return null
        val close = raw.indexOf(')')
        if (close < 0) return null
        val modes = raw.substring(1, close)
        val symbols = raw.substring(close + 1)
        if (modes.length != symbols.length || modes.isEmpty()) return null
        return dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes(modes.toList(), symbols.toList())
    }

    private fun handleChatMessage(session: Session, message: IrcMessage) {
        if (message.parameters.size < 2) return
        val targetParam = message.parameters[0]
        val rawText = message.parameters[1]
        val senderNick = message.prefix?.nick ?: return
        if (senderNick != session.ownNick && ignoreStore?.isIgnored(senderNick) == true) return
        val fromUs = senderNick == session.ownNick
        val isServiceTarget = targetParam.startsWith("*")
        val ref =
            when {
                targetParam.isNotEmpty() && targetParam[0] in "#&" ->
                    ConversationRef.channel(session.config.id, targetParam, session.casemapping)
                isServiceTarget -> ConversationRef.server(session.config.id)
                message.prefix?.isServer == true || fromUs -> {
                    if (fromUs) {
                        ConversationRef.directMessage(session.config.id, targetParam, session.casemapping)
                    } else {
                        ConversationRef.server(session.config.id)
                    }
                }
                else ->
                    ConversationRef.directMessage(session.config.id, senderNick, session.casemapping)
            }

        val decoded = IrcCtcp.decode(rawText)
        val ctcpAction = decoded.filterIsInstance<dev.brentdevs.yardhal.core.protocol.PrivmsgContent.Ctcp>()
            .firstOrNull { it.message.command == IrcCtcp.ACTION }
        val kind =
            when {
                ctcpAction != null -> MessageKind.ACTION
                message.command.equals("NOTICE", true) -> MessageKind.NOTICE
                else -> MessageKind.PRIVMSG
            }
        val body = ctcpAction?.message?.arguments
            ?: decoded.filterIsInstance<dev.brentdevs.yardhal.core.protocol.PrivmsgContent.Plain>()
                .firstOrNull()?.text
            ?: rawText
        val timestampMs = parseServerTime(message.tag("time")) ?: clock()
        val batchRef = message.tag("batch")
        val isPlayback = when (session.openBatchTypes[batchRef]) {
            "znc.in/playback", "chathistory" -> true
            else -> false
        }
        val highlightsMe = !fromUs && !isPlayback &&
            MentionMatcher.containsMessage(body, session.ownNick, session.casemapping)

        appendChat(
            session = session,
            ref = ref,
            sender = senderNick,
            kind = kind,
            text = body,
            msgid = message.tag("msgid"),
            timestampMs = timestampMs,
            sentByUs = fromUs,
            highlightsMe = highlightsMe,
            replyToMsgid = message.tag("+draft/reply"),
            attachmentUrl = message.tag("+draft/attachment"),
            playback = isPlayback,
        )
    }

    private fun handleJoin(session: Session, message: IrcMessage) {
        val nick = message.prefix?.nick ?: return
        val channel = message.parameters.firstOrNull() ?: return
        val ref = ConversationRef.channel(session.config.id, channel, session.casemapping)
        if (nick != session.ownNick) addMember(session, ref, nick)
        if (nick != session.ownNick && netsplitCollapser.isSuppressing("JOIN")) {
            netsplitCollapser.recordSuppressed("JOIN")
            return
        }
        if (nick == session.ownNick) {
            buffer(ref)
            sendRaw(session, "TOPIC $channel")
            sendRaw(session, "MODE $channel")
            requestChathistory(session, ref)
        } else {
            appendSystem(session, ref, "→ $nick joined", MessageKind.JOIN)
        }
    }

    private fun handlePart(session: Session, message: IrcMessage) {
        val nick = message.prefix?.nick ?: return
        val channel = message.parameters.firstOrNull() ?: return
        val reason = message.parameters.getOrNull(1)
        val ref = ConversationRef.channel(session.config.id, channel, session.casemapping)
        removeMember(session, nick, ref.storageKey)
        if (nick.equals(session.ownNick, ignoreCase = true)) {
            _buffers.update { it - ref.storageKey }
            channelOrder.markParted(ref.storageKey)
            _orderState.value = channelOrder.snapshot()
            return
        }
        appendSystem(session, ref, if (reason == null) "← $nick left" else "← $nick left ($reason)", MessageKind.PART)
    }

    private fun handleNickChange(session: Session, message: IrcMessage) {
        if (message.parameters.isEmpty()) return
        val oldNick = message.prefix?.nick ?: return
        val newNick = message.parameters.last()
        renameMember(session, oldNick, newNick)
        if (oldNick == session.ownNick) {
            session.ownNick = newNick
            refreshNetworkStates()
        }
    }

    private fun addMember(session: Session, ref: ConversationRef, nick: String) {
        val folded = session.casemapping.fold(nick)
        updateBuffer(ref) { buffer ->
            if (buffer.members.any { session.casemapping.fold(it.nick) == folded }) buffer else
                buffer.copy(members = (buffer.members + dev.brentdevs.yardhal.core.data.ChannelMember(nick))
                    .sortedBy { it.nick.lowercase() })
        }
        pendingNames[ref.storageKey]?.add(dev.brentdevs.yardhal.core.data.ChannelMember(nick))
    }

    private fun removeMember(session: Session, nick: String, channelKey: String? = null) {
        val folded = session.casemapping.fold(nick)
        for ((key, buffer) in _buffers.value) {
            if (buffer.ref.networkId != session.config.id || buffer.ref.kind != ConversationKind.CHANNEL) continue
            if (channelKey != null && key != channelKey) continue
            updateBufferKey(key) { current -> current.copy(
                members = current.members.filterNot { session.casemapping.fold(it.nick) == folded },
                memberPresence = current.memberPresence.filterKeys { session.casemapping.fold(it) != folded },
            ) }
            pendingNames[key]?.removeAll { session.casemapping.fold(it.nick) == folded }
        }
    }

    private fun renameMember(session: Session, oldNick: String, newNick: String) {
        val folded = session.casemapping.fold(oldNick)
        for ((key, buffer) in _buffers.value) {
            if (buffer.ref.networkId != session.config.id || buffer.ref.kind != ConversationKind.CHANNEL) continue
            updateBufferKey(key) { current ->
                val member = current.members.firstOrNull { session.casemapping.fold(it.nick) == folded }
                    ?: return@updateBufferKey current
                val presence = current.memberPresence.entries.firstOrNull {
                    session.casemapping.fold(it.key) == folded
                }?.value
                current.copy(
                    members = (current.members.filterNot { session.casemapping.fold(it.nick) == folded } +
                        member.copy(nick = newNick)).sortedBy { it.nick.lowercase() },
                    memberPresence = current.memberPresence.filterKeys { session.casemapping.fold(it) != folded } +
                        if (presence == null) emptyMap() else mapOf(newNick to presence),
                )
            }
            pendingNames[key]?.let { pending ->
                val prior = pending.firstOrNull { session.casemapping.fold(it.nick) == folded }
                if (prior != null) {
                    pending.remove(prior)
                    pending.add(prior.copy(nick = newNick))
                }
            }
        }
    }

    private fun handleTopicVerb(session: Session, message: IrcMessage) {
        if (message.parameters.size < 2) return
        val ref = ConversationRef.channel(session.config.id, message.parameters[0], session.casemapping)
        updateBuffer(ref) { it.copy(topic = message.parameters.last()) }
    }

    private fun handleTopicNumeric(session: Session, message: IrcMessage) {
        if (message.parameters.size < 3) return
        val ref = ConversationRef.channel(session.config.id, message.parameters[1], session.casemapping)
        updateBuffer(ref) { it.copy(topic = message.parameters.last()) }
    }

    private fun appendServerLine(session: Session, message: IrcMessage, tag: String? = null) {
        val payload = message.parameters.drop(1).joinToString(" ").ifEmpty { message.command }
        appendSystem(session, ConversationRef.server(session.config.id), if (tag == null) payload else "[$tag] $payload")
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
    ) {
        if (persist) persistAsync(session, ref, sender, kind, text, msgid, timestampMs, sentByUs)
        val muted = mutes.isMuted(ref.storageKey)
        updateBuffer(ref) { buffer ->
            if (sentByUs && !playback) {
                val index = buffer.messages.indexOfLast {
                    it.pendingEcho && it.kind == kind && it.text == text
                }
                if (index >= 0) {
                    val messages = buffer.messages.toMutableList()
                    messages[index] = messages[index]
                        .copy(msgid = msgid, timestampMs = timestampMs, attachmentUrl = attachmentUrl, pendingEcho = false)
                    return@updateBuffer buffer.copy(messages = messages)
                }
            }
            if (msgid != null && buffer.messages.any { it.msgid == msgid }) return@updateBuffer buffer
            val entry = ChatMessage(
                localId = idGenerator.getAndIncrement(),
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
        if (highlightsMe && !sentByUs) {
            if (highlightsMe && !muted) {
                notifier.onHighlight(session.config.name, sender, ConversationNames.forRef(ref), text)
            }
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
    ) {
        scope.launch {
            messageStore.record(
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
                ),
            )
        }
    }

    private fun updateBuffer(ref: ConversationRef, transform: (ConversationBuffer) -> ConversationBuffer) {
        _buffers.update { current ->
            val existing = current[ref.storageKey]
                ?: ConversationBuffer(ref = ref, displayName = ConversationNames.forRef(ref))
            current + (ref.storageKey to transform(existing))
        }
    }

    private fun buffer(ref: ConversationRef): ConversationBuffer {
        _buffers.value[ref.storageKey]?.let { return it }
        val created = ConversationBuffer(ref = ref, displayName = ConversationNames.forRef(ref))
        _buffers.update { it + (ref.storageKey to created) }
        return created
    }

    public fun markRead(storageKey: String) {
        val latest = _buffers.value[storageKey]?.messages?.maxOfOrNull { it.timestampMs } ?: return
        readMarkers.advance(storageKey, latest)
        updateBufferKey(storageKey) { it.copy(hasUnread = false, readAtMs = latest) }
        val networkId = storageKey.substringBefore("|")
        val session = sessions[networkId] ?: return
        if ("draft/read-marker" !in session.supportedCaps) return
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

    public fun loadPersistedHistory(storageKey: String): Boolean {
        if (storageKey in historyLoaded.value) return false
        historyLoaded.value = historyLoaded.value + storageKey
        val buffer = _buffers.value[storageKey] ?: return false
        scope.launch {
            val stored = messageStore.recent(buffer.ref, HISTORY_PAGE_SIZE)
            if (stored.isEmpty()) return@launch
            updateBufferKey(storageKey) { current ->
                val existingMsgids = current.messages.mapNotNull { it.msgid }.toSet()
                val existingSignatures = current.messages.map { it.sender to it.timestampMs }.toSet()
                val marker = maxOf(current.readAtMs, readMarkers.marker(storageKey))
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
                        )
                    }
                val messages = restored + current.messages
                val firstUnread = if (mutes.isMuted(storageKey)) null else messages
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

    private fun requestChathistory(session: Session, ref: ConversationRef) {
        val limit = session.chathistoryLimit
        val supported = limit > 0 || "draft/chathistory" in session.supportedCaps
        if (!supported || ref.kind != ConversationKind.CHANNEL) return
        val count = if (limit > 0) minOf(limit, 100) else 50
        sendRaw(session, "CHATHISTORY LATEST ${ref.rawTarget} * $count")
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

    private fun updateBufferKey(storageKey: String, transform: (ConversationBuffer) -> ConversationBuffer) {
        _buffers.update { current ->
            val existing = current[storageKey] ?: return@update current
            current + (storageKey to transform(existing))
        }
    }

    private fun refreshNetworkStates() {
        _networkStates.value = sessions.values.map { session ->
            UiNetwork(
                id = session.config.id,
                name = session.config.name,
                host = session.config.host,
                status = session.statusFlow.value,
                ownNick = session.ownNick,
            )
        }.sortedBy { it.name }
    }

    private fun sendRaw(session: Session, line: String) {
        session.reconnector?.sendLine(line)
    }

    public fun sendText(networkId: String, storageKey: String, input: String) {
        val session = sessions[networkId] ?: return
        val activeBuffer = _buffers.value[storageKey] ?: return
        val command = SlashCommandParser.parse(input, activeBuffer.ref.rawTarget) ?: return
        val replyTo = activeBuffer.replyDraft?.takeIf { command is SlashCommand.PlainMessage }
        if (replyTo != null) {
            updateBufferKey(storageKey) { it.copy(replyDraft = null) }
        }
        dispatchCommand(session, activeBuffer.ref, command, replyToMsgid = replyTo?.msgid)
    }

    private fun dispatchCommand(
        session: Session,
        active: ConversationRef,
        command: SlashCommand,
        replyToMsgid: String? = null,
    ) {
        when (command) {
            is SlashCommand.PlainMessage -> sendMessage(session, active, command.text, replyToMsgid = replyToMsgid)
            is SlashCommand.EscapedMessage -> sendMessage(session, active, "/" + command.text, replyToMsgid = replyToMsgid)
            is SlashCommand.Action -> sendMessage(
                session,
                active,
                "\u0001ACTION ${command.description}\u0001",
                optimisticKind = MessageKind.ACTION,
                optimisticText = command.description,
            )
            is SlashCommand.Msg -> sendMessage(session, resolveTargetRef(session, command.target), sanitizeOutboundText(command.text))
            is SlashCommand.Query -> buffer(resolveTargetRef(session, command.nick))
            is SlashCommand.Join -> {
                if (command.channels.isEmpty()) return
                val channels = command.channels.joinToString(",")
                sendRaw(session, if (command.keys.isEmpty()) "JOIN $channels" else "JOIN $channels ${command.keys.joinToString(",")}")
                for (c in command.channels) {
                    val ref = ConversationRef.channel(session.config.id, c, session.casemapping)
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
            }
            is SlashCommand.NickChange -> sendRaw(session, "NICK ${sanitizeOutboundText(command.newNick)}")
            is SlashCommand.TopicSet -> sendRaw(session, "TOPIC ${command.channel} :${sanitizeOutboundText(command.topic)}")
            is SlashCommand.TopicShow -> {
                val target = command.channel ?: active.rawTarget
                sendRaw(session, "TOPIC $target")
            }
            is SlashCommand.Away ->
                sendRaw(session, if (command.message == null) "AWAY" else "AWAY :${command.message}")
            is SlashCommand.Quit -> disconnect(session.config.id, sanitizeOutboundText(command.reason ?: ""))
            is SlashCommand.Whois -> {
                session.whoisExpected = true
                sendRaw(session, "WHOIS ${command.target} ${command.target}")
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
                sendRaw(session, if (command.mask == null) "MODE $channel +b" else "MODE $channel +b ${command.mask}")
            }
            is SlashCommand.Mode -> {
                val target = command.target ?: active.rawTarget
                sendRaw(
                    session,
                    if (command.params.isEmpty()) "MODE $target" else "MODE $target ${command.params.joinToString(" ")}",
                )
            }
            is SlashCommand.CtcpQuery -> sendMessage(
                session,
                resolveTargetRef(session, command.target),
                IrcCtcp.encode(command.command, command.arguments),
                suppressOptimistic = true,
            )
            is SlashCommand.Op -> {
                val channel = command.channel ?: active.rawTarget
                sendRaw(session, "MODE $channel ${if (command.grant) "+o" else "-o"} ${command.nick}")
            }
            is SlashCommand.Voice -> {
                val channel = command.channel ?: active.rawTarget
                sendRaw(session, "MODE $channel ${if (command.grant) "+v" else "-v"} ${command.nick}")
            }
            is SlashCommand.MonitorAdd -> sendRaw(session, "MONITOR + ${command.nick}")
            is SlashCommand.MonitorRemove -> sendRaw(session, "MONITOR - ${command.nick}")
            is SlashCommand.MonitorList -> sendRaw(session, "MONITOR L")
            is SlashCommand.WhoQuery -> sendRaw(
                session,
                if (command.useWhox && session.hasWhox) "WHO ${command.target} %acfhn" else "WHO ${command.target}",
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
                "Commands: /me /msg /query /join /part /nick /topic /away /back /quit /whois /kick /ban /mode /ctcp /raw",
            )
            is SlashCommand.Raw -> sendRaw(session, command.line)
        }
    }

    private fun resolveTargetRef(session: Session, target: String): ConversationRef {
        val leader = target.firstOrNull()
        return if (leader != null && leader in "#&") {
            ConversationRef.channel(session.config.id, target, session.casemapping)
        } else {
            ConversationRef.directMessage(session.config.id, target, session.casemapping)
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
    ) {
        val safeWireText = sanitizeOutboundText(wireText)
        if (!suppressOptimistic) {
            appendChat(
                session = session,
                ref = ref,
                sender = session.ownNick,
                kind = optimisticKind,
                text = optimisticText ?: safeWireText,
                msgid = null,
                timestampMs = clock(),
                sentByUs = true,
                highlightsMe = false,
                replyToMsgid = replyToMsgid,
                attachmentUrl = attachmentUrl,
                pendingEcho = "echo-message" in session.supportedCaps,
                persist = "echo-message" !in session.supportedCaps,
            )
        }
        val tags = buildMap {
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

private fun parseServerTime(value: String?): Long? {
    if (value.isNullOrEmpty()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

internal fun sanitizeOutboundText(raw: String): String =
    raw.replace("\r", " ").replace("\n", " ")
        .replace("\u0000", "")
