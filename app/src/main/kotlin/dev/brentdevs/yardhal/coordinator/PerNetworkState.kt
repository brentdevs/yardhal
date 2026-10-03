package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.client.IrcEvent
import dev.brentdevs.yardhal.core.data.BouncerNetworkStore
import dev.brentdevs.yardhal.core.data.ChannelMember
import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.NetsplitCollapser
import dev.brentdevs.yardhal.core.data.WhoisAccumulator
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcMultiline
import dev.brentdevs.yardhal.core.protocol.MultilineLimits

public class InboundContext(
    public val nowMs: Long,
    public val isIgnored: (String) -> Boolean = { false },
    public val hasBuffer: (String) -> Boolean = { true },
    public val openChannels: () -> List<String> = { emptyList() },
    public val latestReadMarkerMs: () -> Long? = { null },
)

public data class OpenBatch(
    public val type: String,
    public val parameters: List<String>,
    public val parent: String?,
    public val multiline: MultilineBuffer? = null,
)

public class MultilineBuffer(public val opening: IrcMessage) {
    internal val lines: MutableList<IrcMessage> = ArrayList()
    internal var byteCount: Int = 0
    internal var overflowed: Boolean = false
}

public data class UserState(
    public val nick: String,
    public val presence: PresenceState? = null,
)

public class ChannelState(public val ref: ConversationRef) {
    internal val members: LinkedHashMap<String, ChannelMember> = LinkedHashMap()

    public fun memberList(): List<ChannelMember> = members.values.sortedBy { it.nick.lowercase() }
}

public class PerNetworkState(
    public val networkId: String,
    configuredNick: String,
    autojoin: List<String> = emptyList(),
) {
    public var autojoin: List<String> = autojoin
        internal set
    public var multilineLimits: MultilineLimits? = null
        internal set
    public var casemapping: CaseMapping = CaseMapping.RFC1459
        internal set
    public var ownNick: String = configuredNick
        internal set
    public var supportedCaps: Set<String> = emptySet()
        internal set
    public var prefixModes: ChannelPrefixModes = ChannelPrefixModes.DEFAULT
        internal set
    public var hasWhox: Boolean = false
        internal set
    public var chathistoryLimit: Int = 0
        internal set
    public var filehostEndpoint: String? = null
        internal set
    public var isBouncerDiscovery: Boolean = false
        internal set
    public var whoisExpected: Boolean = false
    public var listingChannels: Boolean = false

    public val bouncerStore: BouncerNetworkStore = BouncerNetworkStore()

    internal val whois = WhoisAccumulator()
    internal val netsplit = NetsplitCollapser()
    internal val openBatches: LinkedHashMap<String, OpenBatch> = LinkedHashMap()
    internal val pendingNames: LinkedHashMap<String, LinkedHashMap<String, ChannelMember>> = LinkedHashMap()
    internal val channels: LinkedHashMap<String, ChannelState> = LinkedHashMap()
    internal val users: LinkedHashMap<String, UserState> = LinkedHashMap()

    public val server: ConversationRef get() = ConversationRef.server(networkId)

    public fun fold(nick: String): String = casemapping.fold(nick)

    public fun isOwnNick(nick: String): Boolean = fold(nick) == fold(ownNick)

    public fun isChannelName(target: String): Boolean = target.isNotEmpty() && target[0] in "#&"

    public fun channelRef(name: String): ConversationRef = ConversationRef.channel(networkId, name, casemapping)

    public fun directRef(nick: String): ConversationRef = ConversationRef.directMessage(networkId, nick, casemapping)

    public fun targetRef(target: String): ConversationRef =
        if (isChannelName(target)) channelRef(target) else directRef(target)

    public fun channel(storageKey: String): ChannelState? = channels[storageKey]

    public fun user(nick: String): UserState? = users[fold(nick)]

    public fun forgetChannel(storageKey: String) {
        channels.remove(storageKey)
        pendingNames.remove(storageKey)
    }

    public fun batchType(reference: String?): String? = reference?.let { openBatches[it]?.type }

    public fun outboundMultilineLimits(): MultilineLimits? =
        multilineLimits?.takeIf { IrcMultiline.CAPABILITY in supportedCaps && "batch" in supportedCaps }

    internal fun renameChannel(from: ConversationRef, to: ConversationRef) {
        channels.remove(from.storageKey)?.let { existing ->
            val moved = ChannelState(to)
            moved.members.putAll(existing.members)
            channels[to.storageKey] = moved
        }
        pendingNames.remove(from.storageKey)?.let { pendingNames[to.storageKey] = it }
        autojoin = autojoin.map { if (fold(it) == from.normalizedTarget) to.rawTarget else it }
    }

    public fun apply(event: IrcEvent, context: InboundContext): List<InboundEffect> {
        val reduction = Reduction(this, context)
        when (event) {
            is IrcEvent.ConnectionOpened -> resetConnectionScopedState()
            is IrcEvent.CapabilitiesNegotiated -> reduction.handleCapabilities(event.capabilities)
            is IrcEvent.SaslResult -> Unit
            is IrcEvent.Registered -> reduction.handleRegistered(event.nickname)
            is IrcEvent.MessageReceived -> reduction.handleMessage(event.message)
            is IrcEvent.Disconnected -> reduction.emit(InboundEffect.StatusChanged(ConnectionStatus.CONNECTING))
        }
        return reduction.effects
    }

    private fun resetConnectionScopedState() {
        openBatches.clear()
        pendingNames.clear()
        whois.reset()
        whoisExpected = false
        supportedCaps = emptySet()
        multilineLimits = null
    }

    internal fun memberSnapshot(channel: ChannelState): InboundEffect.SetMembers {
        val members = channel.memberList()
        val presence = LinkedHashMap<String, PresenceState>()
        for (member in members) {
            users[fold(member.nick)]?.presence?.let { presence[member.nick] = it }
        }
        return InboundEffect.SetMembers(channel.ref, members, presence)
    }

    internal fun rekey(previous: CaseMapping) {
        if (previous == casemapping) return
        val oldChannels = channels.values.toList()
        channels.clear()
        for (old in oldChannels) {
            val ref = channelRef(old.ref.rawTarget)
            val fresh = ChannelState(ref)
            for (member in old.members.values) fresh.members[fold(member.nick)] = member
            channels[ref.storageKey] = fresh
        }
        val oldUsers = users.values.toList()
        users.clear()
        for (user in oldUsers) users[fold(user.nick)] = user
        val oldPending = pendingNames.toMap()
        pendingNames.clear()
        for ((key, members) in oldPending) {
            val rawTarget = oldChannels.firstOrNull { it.ref.storageKey == key }?.ref?.rawTarget ?: continue
            val rebuilt = LinkedHashMap<String, ChannelMember>()
            for (member in members.values) rebuilt[fold(member.nick)] = member
            pendingNames[channelRef(rawTarget).storageKey] = rebuilt
        }
    }
}

internal class Reduction(val state: PerNetworkState, val context: InboundContext) {
    val effects: MutableList<InboundEffect> = ArrayList()

    fun emit(effect: InboundEffect) {
        effects.add(effect)
    }

    fun system(ref: ConversationRef, text: String, kind: MessageKind = MessageKind.SYSTEM) {
        emit(InboundEffect.AppendMessage(ref = ref, sender = "", kind = kind, text = text, timestampMs = context.nowMs))
    }

    fun serverLine(message: IrcMessage, tag: String? = null) {
        val payload = message.parameters.drop(1).joinToString(" ").ifEmpty { message.command }
        system(state.server, if (tag == null) payload else "[$tag] $payload")
    }

    fun publishMembers(channel: ChannelState) {
        emit(state.memberSnapshot(channel))
    }

    fun channelOrCreate(ref: ConversationRef): ChannelState =
        state.channels.getOrPut(ref.storageKey) { ChannelState(ref) }
}

internal fun Reduction.handleMessage(message: IrcMessage) {
    val command = message.command.uppercase()
    val numeric = message.numeric
    when {
        command == "PING" || command == "PONG" -> Unit
        command == "CAP" -> handleCapMessage(message)
        command == "PRIVMSG" || command == "NOTICE" -> handleChatMessage(message)
        command == "RENAME" -> handleRename(message)
        command == "BATCH" -> handleBatchFrame(message)
        command == "REDACT" -> handleRedact(message)
        command == "TAGMSG" -> handleTagmsg(message)
        command == "BOUNCER" -> handleBouncerMessage(message)
        command == "MARKREAD" -> handleInboundMarkRead(message)
        command == "JOIN" -> handleJoin(message)
        command == "QUIT" -> handleQuit(message)
        command == "PART" -> handlePart(message)
        command == "KICK" -> handleKick(message)
        command == "TOPIC" -> handleTopicVerb(message)
        command == "NICK" -> handleNickChange(message)
        command == "FAIL" || command == "WARN" -> serverLine(message, command.lowercase())
        command == "NOTE" -> serverLine(message, "note")
        numeric != null -> handleNumeric(numeric, message)
        else -> serverLine(message)
    }
}
