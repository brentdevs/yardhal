package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes
import dev.brentdevs.yardhal.core.protocol.ISupport
import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks
import dev.brentdevs.yardhal.core.protocol.IrcMessage

private const val PLAYBACK_FALLBACK_SECONDS = 7L * 24 * 3600

internal fun Reduction.handleCapabilities(capabilities: Set<String>, values: Map<String, String> = emptyMap()) {
    state.supportedCaps = capabilities
    applyMetadataCapability(capabilities, values)
    if ("znc.in/playback" in capabilities) {
        val since = context.latestReadMarkerMs()?.let { it / 1000 }
            ?: (context.nowMs / 1000 - PLAYBACK_FALLBACK_SECONDS)
        emit(InboundEffect.SendRaw("PRIVMSG *playback :playback * start $since"))
    }
    if (IrcBouncerNetworks.CAPABILITY in capabilities) {
        state.isBouncerDiscovery = true
        emit(InboundEffect.BouncerNetworksChanged)
    }
}

internal fun Reduction.handleRegistered(nickname: String) {
    state.ownNick = nickname
    state.registered = true
    emit(InboundEffect.StatusChanged(ConnectionStatus.REGISTERED))
    emit(InboundEffect.OwnNickChanged(nickname))
    subscribeMetadata()
    val channels = LinkedHashSet<String>()
    channels.addAll(state.autojoin)
    channels.addAll(context.openChannels())
    for (channel in channels) {
        emit(InboundEffect.SetJoinState(state.channelRef(channel), JoinState.JOINING))
        emit(InboundEffect.SendRaw("JOIN $channel"))
    }
}

internal fun Reduction.applyIsupportTokens(message: IrcMessage) {
    val tokens = message.parameters.drop(1).dropLast(1).filter { it.isNotEmpty() }
    val previousBot = state.botModeLetter
    val previousExtban = state.accountExtban
    state.isupport = state.isupport.mergedWith(ISupport.parse(tokens))
    if (state.botModeLetter != previousBot || state.accountExtban != previousExtban) {
        emit(InboundEffect.NetworkFeaturesChanged)
    }
    for (token in tokens) {
        when {
            token.startsWith("CASEMAPPING=") ->
                CaseMapping.fromWireName(token.removePrefix("CASEMAPPING="))?.let { mapping ->
                    val previous = state.casemapping
                    state.casemapping = mapping
                    state.rekey(previous)
                }
            token.startsWith("PREFIX=") ->
                parsePrefixToken(token.removePrefix("PREFIX="))?.let { state.prefixModes = it }
            token.startsWith("CHATHISTORY=") ->
                state.chathistoryLimit = token.substringAfter('=').toIntOrNull()?.coerceAtMost(200) ?: 0
            token == "WHOX" -> state.hasWhox = true
            token.startsWith("soju.im/FILEHOST=") ->
                state.filehostEndpoint = token.removePrefix("soju.im/FILEHOST=")
            token.startsWith(ISupport.NETWORK_ICON_TOKEN + "=") ->
                setNetworkIcon(ISupport.unescapeValue(token.substringAfter('=')).takeIf { it.isNotEmpty() })
            token == "-" + ISupport.NETWORK_ICON_TOKEN -> setNetworkIcon(null)
            token.startsWith(IrcBouncerNetworks.ISUPPORT_NET_ID_TOKEN + "=") -> {
                state.bouncerStore.bind(token.substringAfter('='))
                emit(InboundEffect.BouncerNetworksChanged)
            }
        }
    }
}

internal fun parsePrefixToken(raw: String): ChannelPrefixModes? {
    if (!raw.startsWith('(')) return null
    val close = raw.indexOf(')')
    if (close < 0) return null
    val modes = raw.substring(1, close)
    val symbols = raw.substring(close + 1)
    if (modes.length != symbols.length || modes.isEmpty()) return null
    return ChannelPrefixModes(modes.toList(), symbols.toList())
}

internal fun Reduction.handleBouncerMessage(message: IrcMessage) {
    val update = IrcBouncerNetworks.parseNetwork(message.parameters)
    if (update != null) {
        if (state.bouncerStore.apply(update)) emit(InboundEffect.BouncerNetworksChanged)
        return
    }
    val addedId = IrcBouncerNetworks.parseAddNetworkReply(message.parameters) ?: return
    system(state.server, "soju mgmt: network created ($addedId)")
}

internal fun Reduction.requestChathistory(ref: ConversationRef) {
    val limit = state.chathistoryLimit
    val supported = limit > 0 || "draft/chathistory" in state.supportedCaps
    if (!supported || ref.kind != ConversationKind.CHANNEL) return
    val count = if (limit > 0) minOf(limit, 100) else 50
    emit(InboundEffect.SendRaw("CHATHISTORY LATEST ${ref.rawTarget} * $count"))
}
