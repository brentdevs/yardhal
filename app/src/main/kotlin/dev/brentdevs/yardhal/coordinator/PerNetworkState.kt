package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.client.IrcEvent
import dev.brentdevs.yardhal.core.client.SaslOutcome
import dev.brentdevs.yardhal.core.data.BouncerNetworkStore
import dev.brentdevs.yardhal.core.data.ChannelMember
import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.NetsplitCollapser
import dev.brentdevs.yardhal.core.data.WhoisAccumulator
import dev.brentdevs.yardhal.core.protocol.AccountExtban
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes
import dev.brentdevs.yardhal.core.protocol.ISupport
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcMultiline
import dev.brentdevs.yardhal.core.protocol.MultilineLimits
import dev.brentdevs.yardhal.core.protocol.IrcMetadata
import dev.brentdevs.yardhal.core.protocol.MetadataCapability

public class InboundContext(
    public val nowMs: Long,
    public val isIgnored: (String) -> Boolean = { false },
    public val hasBuffer: (String) -> Boolean = { true },
    public val openChannels: () -> List<String> = { emptyList() },
    public val isParted: (String) -> Boolean = { false },
    public val historyPlayback: Boolean = false,
    public val historyTarget: ConversationRef? = null,
)

public data class OpenBatch(
    public val type: String,
    public val parameters: List<String>,
    public val parent: String?,
    public val label: String? = null,
    public val multiline: MultilineBuffer? = null,
)

public enum class LabeledCommand {
    RAW,
    WHOIS,
    WHO,
    MODE,
    TOPIC,
    MONITOR,
    LIST,
    PRIVMSG,
}

public data class PendingLabel(
    public val label: String,
    public val origin: ConversationRef,
    public val command: LabeledCommand,
    public val issuedAtMs: Long,
)

public class MultilineBuffer(public val opening: IrcMessage) {
    internal val lines: MutableList<IrcMessage> = ArrayList()
    internal var byteCount: Int = 0
    internal var overflowed: Boolean = false
}

public data class UserState(
    public val nick: String,
    public val presence: PresenceState = PresenceState(),
    public val metadata: Map<String, String> = emptyMap(),
    public val metadataObservedAtMs: Long? = null,
    public val metadataKeysObserved: Set<String> = emptySet(),
) {
    public val profile: UserProfile?
        get() = UserProfile.from(metadata)
}

public class ChannelState(public val ref: ConversationRef) {
    internal val members: LinkedHashMap<String, ChannelMember> = LinkedHashMap()
    internal val metadata: LinkedHashMap<String, String> = LinkedHashMap()
    internal val modes: LinkedHashMap<String, List<String>> = LinkedHashMap()
    public var membersComplete: Boolean = false
        internal set
    public var modesComplete: Boolean = false
        internal set

    public fun memberList(): List<ChannelMember> = members.values.sortedBy { it.nick.lowercase() }

    public fun modeSnapshot(): Map<String, List<String>> = LinkedHashMap(modes)

    internal fun mergeFrom(other: ChannelState, casemapping: CaseMapping, listModes: String) {
        if (other.membersComplete) members.clear()
        for (member in other.members.values) members[casemapping.fold(member.nick)] = member
        membersComplete = membersComplete || other.membersComplete
        metadata.putAll(other.metadata)
        if (other.modesComplete) modes.keys.removeAll { it.single() !in listModes }
        for ((mode, parameters) in other.modes) {
            val prior = modes[mode]
            modes[mode] = if (prior != null && mode.single() in listModes) {
                (prior + parameters).distinct()
            } else {
                parameters
            }
        }
        modesComplete = modesComplete || other.modesComplete
    }

    public fun metadataValue(key: String): String? = metadata[key]
}

public class PerNetworkState(
    public val networkId: String,
    configuredNick: String,
    autojoin: List<String> = emptyList(),
    public val bouncerNetId: String? = null,
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
    public var filehostEndpoint: String? = null
        internal set
    public var isBouncerDiscovery: Boolean = false
        internal set
    public var isupport: ISupport = ISupport.EMPTY
        internal set
    public var whoisExpected: Boolean = false
    public var whoisTarget: String? = null
    public var listingChannels: Boolean = false
    public var registered: Boolean = false
        internal set
    internal var authenticationRejected: Boolean = false
    public var connectionEpoch: Int = 0
        internal set
    public var metadataCapability: MetadataCapability? = null
        internal set
    public var metadataSubscriptions: Set<String> = emptySet()
        internal set
    public var networkIconUrl: String? = null
        internal set
    private var publishedProfileVersion: Long = 0

    private var labelCounter: Long = 0L

    public val bouncerStore: BouncerNetworkStore = BouncerNetworkStore()

    internal val whois = WhoisAccumulator()
    internal val netsplit = NetsplitCollapser()
    internal val openBatches: LinkedHashMap<String, OpenBatch> = LinkedHashMap()
    internal val pendingNames: LinkedHashMap<String, LinkedHashMap<String, ChannelMember>> = LinkedHashMap()
    internal val channels: LinkedHashMap<String, ChannelState> = LinkedHashMap()
    internal val users: UserTable = UserTable()
    internal val pendingLabels: LinkedHashMap<String, PendingLabel> = LinkedHashMap()
    internal val monitored: LinkedHashMap<String, String> = LinkedHashMap()

    public val botModeLetter: Char? get() = isupport.botModeLetter

    public val accountExtban: AccountExtban? get() = isupport.accountExtban

    public val clientTagPolicy: dev.brentdevs.yardhal.core.protocol.ClientTagPolicy
        get() = dev.brentdevs.yardhal.core.protocol.ClientTagPolicy(supportedCaps, isupport["CLIENTTAGDENY"])

    public val server: ConversationRef get() = ConversationRef.server(networkId)

    public fun fold(nick: String): String = casemapping.fold(nick)

    public fun isOwnNick(nick: String): Boolean = fold(nick) == fold(ownNick)

    public fun isChannelName(target: String): Boolean = target.isNotEmpty() && target[0] in "#&"

    public fun channelRef(name: String): ConversationRef = ConversationRef.channel(networkId, name, casemapping)

    public fun directRef(nick: String): ConversationRef = ConversationRef.directMessage(networkId, nick, casemapping)

    public fun targetRef(target: String): ConversationRef =
        if (isChannelName(target)) channelRef(target) else directRef(target)

    public fun channel(storageKey: String): ChannelState? = channels[storageKey]

    public fun channelRefs(): List<ConversationRef> = channels.values.map { it.ref }

    public fun user(nick: String): UserState? = users[fold(nick)]

    public fun isMonitored(nick: String): Boolean = fold(nick) in monitored

    public fun accountBanMask(nick: String): String? {
        val account = user(nick)?.presence?.account ?: return null
        return accountExtban?.mask(account)
    }

    public fun forgetMonitored(nicks: Collection<String>) {
        for (nick in nicks) {
            val folded = fold(nick)
            monitored.remove(folded)
            if (!isTracked(folded)) users.remove(folded)
        }
    }

    internal fun clearMonitored(): InboundEffect.ProfilesChanged? {
        val targets = monitored.keys.iterator()
        while (targets.hasNext()) {
            val folded = targets.next()
            targets.remove()
            if (!isTracked(folded)) users.remove(folded)
        }
        return profileUpdate()
    }

    internal fun isTracked(folded: String): Boolean =
        folded in monitored ||
            channels.values.any { folded in it.members } ||
            pendingNames.values.any { folded in it }

    public fun forgetChannel(storageKey: String) {
        channels.remove(storageKey)
        pendingNames.remove(storageKey)
    }

    public fun batchType(reference: String?): String? = reference?.let { openBatches[it]?.type }

    public val labeledResponseEnabled: Boolean get() = LABELED_RESPONSE_CAP in supportedCaps

    public fun pendingLabel(label: String): PendingLabel? = pendingLabels[label]

    public fun issueLabel(origin: ConversationRef, command: LabeledCommand, nowMs: Long): String? {
        if (!labeledResponseEnabled) return null
        pendingLabels.values.removeAll { pending ->
            nowMs - pending.issuedAtMs > LABEL_TTL_MS && openBatches.values.none { it.label == pending.label }
        }
        labelCounter += 1
        val label = "yh$labelCounter"
        pendingLabels[label] = PendingLabel(label, origin, command, nowMs)
        return label
    }

    public fun outboundMultilineLimits(): MultilineLimits? =
        multilineLimits?.takeIf { IrcMultiline.CAPABILITY in supportedCaps && "batch" in supportedCaps }

    internal fun renameChannel(from: ConversationRef, to: ConversationRef) {
        channels.remove(from.storageKey)?.let { existing ->
            val moved = channels.getOrPut(to.storageKey) { ChannelState(to) }
            moved.mergeFrom(existing, casemapping, isupport.chanmodes.listA)
        }
        pendingNames.remove(from.storageKey)?.let { pending ->
            pendingNames.getOrPut(to.storageKey) { LinkedHashMap() }.putAll(pending)
        }
        for ((label, pending) in pendingLabels) {
            if (pending.origin.storageKey == from.storageKey) {
                pendingLabels[label] = pending.copy(origin = to)
            }
        }
        autojoin = autojoin.map { if (fold(it) == from.normalizedTarget) to.rawTarget else it }
    }

    public fun apply(event: IrcEvent, context: InboundContext): List<InboundEffect> {
        val reduction = Reduction(this, context)
        when (event) {
            is IrcEvent.ConnectionOpened -> resetConnectionScopedState(reduction)
            is IrcEvent.CapabilitiesNegotiated -> reduction.handleCapabilities(event.capabilities, event.values)
            is IrcEvent.SaslResult -> {
                val outcome = event.outcome
                if (outcome is SaslOutcome.Failure) {
                    if (registered) {
                        reduction.system(server, "SASL authentication failed: ${outcome.description}")
                    } else {
                        authenticationRejected = true
                        reduction.emit(InboundEffect.AuthenticationFailed(outcome.description))
                    }
                }
            }
            is IrcEvent.Registered -> reduction.handleRegistered(event.nickname)
            is IrcEvent.MessageReceived -> reduction.handleMessage(event.message)
            is IrcEvent.Disconnected -> {
                registered = false
                connectionEpoch += 1
                reduction.emit(InboundEffect.StatusChanged(ConnectionStatus.DISCONNECTED))
            }
        }
        profileUpdate()?.let(reduction::emit)
        return reduction.effects
    }

    private fun profileUpdate(): InboundEffect.ProfilesChanged? {
        if (users.profileVersion == publishedProfileVersion) return null
        if (openBatches.values.any { it.type in PROFILE_DEFERRING_BATCHES }) return null
        publishedProfileVersion = users.profileVersion
        return InboundEffect.ProfilesChanged(NetworkProfiles(casemapping, LinkedHashMap(users.profiles)))
    }

    private fun resetConnectionScopedState(reduction: Reduction) {
        openBatches.clear()
        pendingNames.clear()
        whois.reset()
        whoisExpected = false
        supportedCaps = emptySet()
        pendingLabels.clear()
        isupport = ISupport.EMPTY
        prefixModes = ChannelPrefixModes.DEFAULT
        hasWhox = false
        filehostEndpoint = null
        monitored.clear()
        multilineLimits = null
        registered = false
        authenticationRejected = false
        connectionEpoch += 1
        metadataCapability = null
        metadataSubscriptions = emptySet()
        users.clear()
        for (channelName in reduction.context.openChannels()) reduction.channelOrCreate(channelRef(channelName))
        for (channel in channels.values) {
            channel.members.clear()
            channel.membersComplete = false
            channel.metadata.clear()
            channel.modes.clear()
            channel.modesComplete = false
            reduction.publishMembers(channel)
            reduction.publishModes(channel)
            reduction.emit(InboundEffect.SetJoinState(channel.ref, JoinState.JOINING))
        }
    }

    internal fun memberSnapshot(channel: ChannelState): InboundEffect.SetMembers {
        val members = channel.memberList()
        val presence = LinkedHashMap<String, PresenceState>()
        for (member in members) {
            users[fold(member.nick)]?.let { presence[member.nick] = it.presence }
        }
        return InboundEffect.SetMembers(channel.ref, members, presence, channel.membersComplete)
    }

    internal fun rekey(previous: CaseMapping) {
        if (previous == casemapping) return
        val oldChannels = channels.values.toList()
        channels.clear()
        for (old in oldChannels) {
            val ref = channelRef(old.ref.rawTarget)
            val fresh = channels.getOrPut(ref.storageKey) { ChannelState(ref) }
            fresh.mergeFrom(old, casemapping, isupport.chanmodes.listA)
        }
        val oldUsers = users.values.toList()
        users.clear()
        for (user in oldUsers) users[fold(user.nick)] = user
        val oldMonitored = monitored.values.toList()
        monitored.clear()
        for (nick in oldMonitored) monitored[fold(nick)] = nick
        val oldPending = pendingNames.toMap()
        pendingNames.clear()
        for ((key, members) in oldPending) {
            val rawTarget = oldChannels.firstOrNull { it.ref.storageKey == key }?.ref?.rawTarget ?: continue
            val rebuilt = pendingNames.getOrPut(channelRef(rawTarget).storageKey) { LinkedHashMap() }
            for (member in members.values) rebuilt[fold(member.nick)] = member
        }
        for ((label, pending) in pendingLabels.toMap()) {
            pendingLabels[label] = pending.copy(
                origin = pending.origin.copy(normalizedTarget = fold(pending.origin.rawTarget)),
            )
        }
    }

    public companion object {
        public const val LABELED_RESPONSE_CAP: String = "labeled-response"
        public const val LABEL_TTL_MS: Long = 120_000L
    }
}

internal class Reduction(val state: PerNetworkState, val context: InboundContext) {
    val effects: MutableList<InboundEffect> = ArrayList()
    var correlation: PendingLabel? = null

    val replyRef: ConversationRef
        get() = correlation?.origin?.takeIf { context.hasBuffer(it.storageKey) } ?: state.server
    val changedUsers: LinkedHashSet<String> = LinkedHashSet()

    fun emit(effect: InboundEffect) {
        effects.add(effect)
    }

    fun system(ref: ConversationRef, text: String, kind: MessageKind = MessageKind.SYSTEM) {
        emit(InboundEffect.AppendMessage(ref = ref, sender = "", kind = kind, text = text, timestampMs = context.nowMs))
    }

    fun serverLine(message: IrcMessage, tag: String? = null) {
        val payload = if (message.numeric != null) {
            message.parameters.drop(1).joinToString(" ").ifEmpty { message.command }
        } else {
            (listOf(message.command) + message.parameters).joinToString(" ")
        }
        system(replyRef, if (tag == null) payload else "[$tag] $payload")
    }

    fun standardReply(message: IrcMessage) {
        system(replyRef, "[${message.command.lowercase()}] ${message.parameters.joinToString(" ")}")
    }

    fun publishMembers(channel: ChannelState) {
        emit(state.memberSnapshot(channel))
    }

    fun publishModes(channel: ChannelState) {
        emit(InboundEffect.SetModes(channel.ref, channel.modeSnapshot(), channel.modesComplete))
    }

    fun channelOrCreate(ref: ConversationRef): ChannelState =
        state.channels.getOrPut(ref.storageKey) { ChannelState(ref) }

    fun publishChangedUsers() {
        if (changedUsers.isEmpty()) return
        for (channel in state.channels.values) {
            if (changedUsers.any { it in channel.members }) publishMembers(channel)
        }
        changedUsers.clear()
    }
}

internal fun Reduction.handleMessage(message: IrcMessage) {
    val command = message.command.uppercase()
    val numeric = message.numeric
    correlation = correlateLabel(message, command)
    if (command != "JOIN") observeSource(message)
    when {
        command == "PING" || command == "PONG" || command == "ACK" -> Unit
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
        command == "MODE" -> handleMode(message)
        command == "NICK" -> handleNickChange(message)
        command == "AWAY" -> handleAway(message)
        command == "ACCOUNT" -> handleAccount(message)
        command == "CHGHOST" -> handleChghost(message)
        command == "SETNAME" -> handleSetname(message)
        command == "INVITE" -> handleInvite(message)
        command == IrcMetadata.COMMAND -> handleMetadataMessage(message)
        command == "FAIL" && message.parameters.firstOrNull() == IrcMetadata.COMMAND -> handleMetadataFail(message)
        command == "FAIL" || command == "WARN" || command == "NOTE" -> standardReply(message)
        numeric != null -> handleNumeric(numeric, message)
        else -> serverLine(message)
    }
    publishChangedUsers()
}
