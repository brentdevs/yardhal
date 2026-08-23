package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcEvent
import dev.brentdevs.yardhal.core.client.IrcReconnector
import dev.brentdevs.yardhal.core.client.ReconnectPolicy
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
import kotlinx.coroutines.launch

public fun interface ConnectionFactory {
    public fun create(config: NetworkConfig): IrcConnection
}

public class LiveCoordinator(
    public val scope: CoroutineScope,
    public val networkStore: NetworkStore,
    public val messageStore: MessageStore,
    public val readMarkers: ReadMarkerStore,
    public val mutes: MuteStore,
    private val vault: CredentialVault,
    private val connectionFactory: ConnectionFactory,
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
        refreshNetworkStates()
        launchSession(session)
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
        val config = effectiveConfig(session.config)
        val reconnector = IrcReconnector(
            scope = scope,
            policy = ReconnectPolicy(initialDelayMillis = 1_000, maxDelayMillis = 30_000),
            connectionFactory = { connectionFactory.create(effectiveConfig(session.config)) },
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

    private fun effectiveConfig(config: NetworkConfig): NetworkConfig {
        val password = config.saslPasswordRef?.let { vault.readPassword(it) }
        return config.copy(saslPassword = password)
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
                if ("soju.im/bouncer" in event.capabilities) {
                    sendRaw(session, "BOUNCER LISTNETWORKS")
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
        for (channel in session.config.autojoin) {
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
            message.command.equals("PRIVMSG", true) || message.command.equals("NOTICE", true) ->
                handleChatMessage(session, message)
            message.command.equals("BATCH", true) -> handleBatchFrame(session, message)
            message.command.equals("REDACT", true) -> handleRedact(session, message)
            message.command.equals("TAGMSG", true) -> handleTagmsg(session, message)
            message.command.equals("JOIN", true) -> handleJoin(session, message)
            message.command.equals("QUIT", true) -> handleQuit(session)
            message.command.equals("PART", true) -> handlePart(session, message)
            message.command.equals("TOPIC", true) -> handleTopicVerb(session, message)
            message.command.equals("NICK", true) -> handleNickChange(session, message)
            message.command.equals("FAIL", true) || message.command.equals("WARN", true) ->
                appendServerLine(session, message, message.command.lowercase())
            message.command.equals("NOTE", true) -> appendServerLine(session, message, "note")
            numeric == 332 -> handleTopicNumeric(session, message)
            numeric == 353 -> accumulateNames(session, message)
            numeric == 366 -> finalizeNames(session, message)
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
        if (message.parameters.size < 2) return
        val reference = message.parameters.last().removePrefix("+").removePrefix("-")
        val action = message.parameters[message.parameters.size - 2]
        when (action) {
            "+" -> {
                val type = message.parameters.getOrNull(message.parameters.size - 1) ?: return
                session.openBatchTypes[reference] = type
                netsplitCollapser.onStart(reference, type, emptyList())
            }
            "-" -> {
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

    private fun handleQuit(session: Session) {
        if (netsplitCollapser.isSuppressing("QUIT")) {
            netsplitCollapser.recordSuppressed("QUIT")
        }
    }

    private val pendingNames = LinkedHashMap<String, MutableSet<String>>()
    private var prefixModes: dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes =
        dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes.DEFAULT
    private var chathistoryLimit: Int = 0

    private fun accumulateNames(session: Session, message: IrcMessage) {
        if (message.parameters.size < 2) return
        val (channelRaw, members) = dev.brentdevs.yardhal.core.data.NamesParser.parseNamesLine(
            message.parameters.drop(1),
            prefixModes,
        )
        val channel = channelRaw ?: return
        val key = ConversationRef.channel(session.config.id, channel, session.casemapping).storageKey
        pendingNames.getOrPut(key) { LinkedHashSet() }.addAll(members)
    }

    private fun finalizeNames(session: Session, message: IrcMessage) {
        if (message.parameters.size < 2) return
        val channel = message.parameters[1]
        val key = ConversationRef.channel(session.config.id, channel, session.casemapping).storageKey
        val members = pendingNames.remove(key)?.sortedBy { it.lowercase() } ?: return
        updateBufferKey(key) { it.copy(members = members) }
    }

    private var whoxQuerySent: Boolean = false

    private fun handleWhoXLine(session: Session, message: IrcMessage) {
        if (message.parameters.size < 5) return
        val fields = message.parameters.drop(1)
        val account = fields.getOrNull(0)?.takeIf { it != "0" }
        val flags = fields.getOrNull(1)
        val nick = fields.getOrNull(3) ?: return
        updateMemberPresence(session, nick, away = flags?.contains('G') == true, account = account)
    }

    private fun updateMemberPresence(session: Session, nick: String, away: Boolean, account: String?) {
        for ((key, buffer) in _buffers.value) {
            if (buffer.ref.networkId != session.config.id) continue
            if (buffer.members.any { it.equals(nick, ignoreCase = true) }) {
                updateBufferKey(key) { it.copy(memberPresence = it.memberPresence + (nick to PresenceState(away, account))) }
            }
        }
    }

    private fun handleWhoisNumeric(session: Session, numeric: Int, message: IrcMessage) {
        if (!whoisExpected) return
        whoisAccumulator.handle(numeric, message.parameters)?.let { complete ->
            whoisExpected = false
            _whois.value = complete
        }
    }

    private val whoisAccumulator = dev.brentdevs.yardhal.core.data.WhoisAccumulator()
    private var whoisExpected: Boolean = false

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
        updateBufferKey(storageKey) { current ->
            var reactions = current.reactions
            if (!current.messages.any { it.msgid == msgid }) return@updateBufferKey current
            val perMessage = (reactions[msgid] ?: emptyMap()).toMutableMap()
            when {
                perMessage[emoji].orEmpty().contains(own) -> {
                    val remaining = perMessage[emoji].orEmpty().toMutableSet().apply { remove(own) }
                    sendRaw(session, "@+draft/unreact=$emoji;+draft/refs=$msgid TAGMSG ${buffer.ref.rawTarget}")
                    if (remaining.isEmpty()) perMessage.remove(emoji) else perMessage[emoji] = remaining
                }
                else -> {
                    sendRaw(session, "@+draft/react=$emoji;+draft/refs=$msgid TAGMSG ${buffer.ref.rawTarget}")
                    perMessage[emoji] = perMessage[emoji].orEmpty() + own
                }
            }
            reactions = if (perMessage.isEmpty()) reactions - msgid else reactions + (msgid to perMessage.toMap())
            current.copy(reactions = reactions)
        }
    }

    public fun setReplyDraft(networkId: String, storageKey: String, message: ChatMessage?) {
        updateBufferKey(storageKey) { it.copy(replyDraft = message) }
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
        val config = effectiveConfig(session.config)
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
                    parsePrefixToken(token.removePrefix("PREFIX="))?.let { prefixModes = it }
                token.startsWith("CHATHISTORY=") ->
                    chathistoryLimit = token.substringAfter('=').toIntOrNull()?.coerceAtMost(200) ?: 0
                token == "WHOX" -> session.hasWhox = true
                token.startsWith("soju.im/FILEHOST=") ->
                    session.filehostEndpoint = token.removePrefix("soju.im/FILEHOST=")
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
        val isZncPlayback = batchRef != null && session.openBatchTypes[batchRef] == "znc.in/playback"
        val highlightsMe = !fromUs && !isZncPlayback &&
            MentionMatcher.containsMessage(body, session.ownNick, session.casemapping)
        val isPlayback = isZncPlayback

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
        if (nick != session.ownNick && netsplitCollapser.isSuppressing("JOIN")) {
            netsplitCollapser.recordSuppressed("JOIN")
            return
        }
        val ref = ConversationRef.channel(session.config.id, channel, session.casemapping)
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
        appendSystem(session, ref, if (reason == null) "← $nick left" else "← $nick left ($reason)", MessageKind.PART)
    }

    private fun handleNickChange(session: Session, message: IrcMessage) {
        if (message.parameters.isEmpty()) return
        val newNick = message.parameters.last()
        if (message.prefix?.nick == session.ownNick) {
            session.ownNick = newNick
            refreshNetworkStates()
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
    ) {
        persistAsync(session, ref, sender, kind, text, msgid, timestampMs, sentByUs)
        updateBuffer(ref) { buffer ->
            if (sentByUs && msgid != null) {
                val index = buffer.messages.indexOfLast {
                    it.sentByUs && it.msgid == null && it.kind == kind && it.text == text
                }
                if (index >= 0) {
                    val messages = buffer.messages.toMutableList()
                    messages[index] = messages[index]
                        .copy(msgid = msgid, timestampMs = timestampMs, attachmentUrl = attachmentUrl)
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
            )
            val countsAsUnread = !sentByUs && !playback &&
                kind in setOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)
            buffer.copy(
                messages = buffer.messages + entry,
                hasUnread = buffer.hasUnread || countsAsUnread,
            )
        }
        if (highlightsMe && !sentByUs) {
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
        val current = _buffers.value
        val existing = current[ref.storageKey]
            ?: ConversationBuffer(ref = ref, displayName = ConversationNames.forRef(ref))
        _buffers.value = current + (ref.storageKey to transform(existing))
    }

    private fun buffer(ref: ConversationRef): ConversationBuffer {
        _buffers.value[ref.storageKey]?.let { return it }
        val created = ConversationBuffer(ref = ref, displayName = ConversationNames.forRef(ref))
        _buffers.value = _buffers.value + (ref.storageKey to created)
        return created
    }

    public fun markRead(storageKey: String) {
        val latest = _buffers.value[storageKey]?.messages?.maxOfOrNull { it.timestampMs } ?: return
        readMarkers.advance(storageKey, latest)
        updateBufferKey(storageKey) { it.copy(hasUnread = false) }
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
                current.copy(messages = restored + current.messages)
            }
        }
        return true
    }

    private fun requestChathistory(session: Session, ref: ConversationRef) {
        if (chathistoryLimit <= 0 || ref.kind != ConversationKind.CHANNEL) return
        val count = minOf(chathistoryLimit, 100)
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
        val current = _buffers.value[storageKey] ?: return
        _buffers.value = _buffers.value + (storageKey to transform(current))
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
            is SlashCommand.Msg -> sendMessage(session, resolveTargetRef(session, command.target), command.text)
            is SlashCommand.Query -> buffer(resolveTargetRef(session, command.nick))
            is SlashCommand.Join -> {
                if (command.channels.isEmpty()) return
                val channels = command.channels.joinToString(",")
                sendRaw(session, if (command.keys.isEmpty()) "JOIN $channels" else "JOIN $channels ${command.keys.joinToString(",")}")
                for (c in command.channels) {
                    buffer(ConversationRef.channel(session.config.id, c, session.casemapping))
                }
            }
            is SlashCommand.Part -> {
                val target = command.channel
                    ?: active.rawTarget.takeIf { active.kind != ConversationKind.SERVER }
                    ?: return
                sendRaw(session, if (command.reason == null) "PART $target" else "PART $target :${command.reason}")
            }
            is SlashCommand.NickChange -> sendRaw(session, "NICK ${command.newNick}")
            is SlashCommand.TopicSet -> sendRaw(session, "TOPIC ${command.channel} :${command.topic}")
            is SlashCommand.TopicShow -> {
                val target = command.channel ?: active.rawTarget
                sendRaw(session, "TOPIC $target")
            }
            is SlashCommand.Away ->
                sendRaw(session, if (command.message == null) "AWAY" else "AWAY :${command.message}")
            is SlashCommand.Quit -> disconnect(session.config.id, command.reason ?: "")
            is SlashCommand.Whois -> {
                whoisExpected = true
                sendRaw(session, "WHOIS ${command.target} ${command.target}")
 }
            is SlashCommand.Kick -> {
                val channel = command.channel ?: active.rawTarget
                sendRaw(
                    session,
                    if (command.reason == null) "KICK $channel ${command.nick}"
                    else "KICK $channel ${command.nick} :${command.reason}",
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
                if (command.useWhox && session.hasWhox) "WHO ${command.target} %afhn" else "WHO ${command.target}",
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
        if (!suppressOptimistic) {
            appendChat(
                session = session,
                ref = ref,
                sender = session.ownNick,
                kind = optimisticKind,
                text = optimisticText ?: wireText,
                msgid = null,
                timestampMs = clock(),
                sentByUs = true,
                highlightsMe = false,
                replyToMsgid = replyToMsgid,
                attachmentUrl = attachmentUrl,
            )
        }
        val tags = buildMap {
            if (replyToMsgid != null) put("+draft/reply", replyToMsgid)
            if (attachmentUrl != null) put("+draft/attachment", attachmentUrl)
        }
        if (tags.isNotEmpty()) {
            session.reconnector?.send(
                IrcMessage(tags = tags, command = "PRIVMSG", parameters = listOf(ref.rawTarget, wireText)),
            )
        } else {
            sendRaw(session, "PRIVMSG ${ref.rawTarget} :$wireText")
        }
    }
}

private fun parseServerTime(value: String?): Long? {
    if (value.isNullOrEmpty()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}
