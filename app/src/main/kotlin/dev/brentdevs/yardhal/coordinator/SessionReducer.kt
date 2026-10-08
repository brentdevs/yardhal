package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.protocol.ISupport
import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks
import dev.brentdevs.yardhal.core.protocol.IrcMessage

internal fun Reduction.handleCapabilities(capabilities: Set<String>, values: Map<String, String> = emptyMap()) {
    state.supportedCaps = capabilities
    applyMetadataCapability(capabilities, values)
    val discovery = IrcBouncerNetworks.CAPABILITY in capabilities
    if (state.isBouncerDiscovery != discovery) {
        state.isBouncerDiscovery = discovery
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
        if (context.isParted(channel)) continue
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
    val previousMapping = state.casemapping
    state.casemapping = state.isupport.casemapping
    state.rekey(previousMapping)
    state.prefixModes = state.isupport.prefix
    state.hasWhox = state.isupport.whox
    state.filehostEndpoint = state.isupport["soju.im/FILEHOST"]
    setNetworkIcon(state.isupport.networkIcon)
    for (token in tokens) {
        when {
            token.startsWith(IrcBouncerNetworks.ISUPPORT_NET_ID_TOKEN + "=") -> {
                state.bouncerStore.bind(token.substringAfter('='))
                emit(InboundEffect.BouncerNetworksChanged)
            }
        }
    }
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

internal fun Reduction.requestHistory(ref: ConversationRef) {
    emit(InboundEffect.RequestHistory(ref))
}
