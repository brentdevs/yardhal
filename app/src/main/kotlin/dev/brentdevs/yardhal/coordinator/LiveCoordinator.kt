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

    private val sessions = LinkedHashMap<String, Session>()

    private inner class Session(val config: NetworkConfig) {
        var casemapping: CaseMapping = CaseMapping.RFC1459
        var ownNick: String = config.nick
        var statusFlow: MutableStateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.CONNECTING)
        var reconnector: IrcReconnector? = null
        var collectorJob: Job? = null
        var quitRequested: Boolean = false

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
            message.command.equals("JOIN", true) -> handleJoin(session, message)
            message.command.equals("PART", true) -> handlePart(session, message)
            message.command.equals("TOPIC", true) -> handleTopicVerb(session, message)
            message.command.equals("NICK", true) -> handleNickChange(session, message)
            numeric == 332 -> handleTopicNumeric(session, message)
            numeric == 5 -> applyIsupportTokens(session, message)
            numeric != null && numeric in 400..599 -> appendServerLine(session, message, "error")
            numeric != null && numeric == 433 -> appendServerLine(session, message, "nick taken")
            else -> appendServerLine(session, message)
        }
    }

    private fun applyIsupportTokens(session: Session, message: IrcMessage) {
        val tokens = message.parameters.drop(1).dropLast(1).filter { it.contains('=') || it.isNotEmpty() }
        val casemappingToken = tokens.firstOrNull { it.startsWith("CASEMAPPING=") }
        if (casemappingToken != null) {
            dev.brentdevs.yardhal.core.protocol.CaseMapping.fromWireName(
                casemappingToken.removePrefix("CASEMAPPING="),
            )?.let { session.casemapping = it }
        }
    }

    private fun handleChatMessage(session: Session, message: IrcMessage) {
        if (message.parameters.size < 2) return
        val targetParam = message.parameters[0]
        val rawText = message.parameters[1]
        val senderNick = message.prefix?.nick ?: return
        val fromUs = senderNick == session.ownNick
        val ref =
            if (targetParam.isNotEmpty() && targetParam[0] in "#&") {
                ConversationRef.channel(session.config.id, targetParam, session.casemapping)
            } else if (message.prefix?.isServer == true || fromUs) {
                if (fromUs) {
                    ConversationRef.directMessage(session.config.id, targetParam, session.casemapping)
                } else {
                    ConversationRef.server(session.config.id)
                }
            } else {
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
        val highlightsMe = !fromUs && MentionMatcher.containsMessage(body, session.ownNick, session.casemapping)

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
        )
    }

    private fun handleJoin(session: Session, message: IrcMessage) {
        val nick = message.prefix?.nick ?: return
        val channel = message.parameters.firstOrNull() ?: return
        val ref = ConversationRef.channel(session.config.id, channel, session.casemapping)
        if (nick == session.ownNick) {
            buffer(ref)
            sendRaw(session, "TOPIC $channel")
            sendRaw(session, "MODE $channel")
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
    ) {
        persistAsync(session, ref, sender, kind, text, msgid, timestampMs, sentByUs)
        updateBuffer(ref) { buffer ->
            if (sentByUs && msgid != null) {
                val index = buffer.messages.indexOfLast {
                    it.sentByUs && it.msgid == null && it.kind == kind && it.text == text
                }
                if (index >= 0) {
                    val messages = buffer.messages.toMutableList()
                    messages[index] = messages[index].copy(msgid = msgid, timestampMs = timestampMs)
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
            )
            buffer.copy(messages = buffer.messages + entry)
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
        dispatchCommand(session, activeBuffer.ref, command)
    }

    private fun dispatchCommand(session: Session, active: ConversationRef, command: SlashCommand) {
        when (command) {
            is SlashCommand.PlainMessage -> sendMessage(session, active, command.text)
            is SlashCommand.EscapedMessage -> sendMessage(session, active, "/" + command.text)
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
            is SlashCommand.Whois -> sendRaw(session, "WHOIS ${command.target} ${command.target}")
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
            )
        }
        sendRaw(session, "PRIVMSG ${ref.rawTarget} :$wireText")
    }
}

private fun parseServerTime(value: String?): Long? {
    if (value.isNullOrEmpty()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}
