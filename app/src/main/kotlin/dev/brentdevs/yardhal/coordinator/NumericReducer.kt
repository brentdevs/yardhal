package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcMetadata

private val JOIN_FAILURE_NUMERICS = setOf(403, 405, 437, 471, 473, 474, 475)
private val WHOIS_NUMERICS = (301..319).toSet() - setOf(305, 306) + 330
private val METADATA_NUMERICS = setOf(
    IrcMetadata.RPL_WHOISKEYVALUE,
    IrcMetadata.RPL_KEYVALUE,
    IrcMetadata.RPL_KEYNOTSET,
    IrcMetadata.RPL_METADATASUBOK,
    IrcMetadata.RPL_METADATAUNSUBOK,
    IrcMetadata.RPL_METADATASUBS,
    IrcMetadata.RPL_METADATASYNCLATER,
)

internal fun Reduction.handleNumeric(numeric: Int, message: IrcMessage) {
    when {
        numeric == 5 -> applyIsupportTokens(message)
        numeric == 332 -> handleTopicNumeric(message)
        numeric == 331 -> handleNoTopic(message)
        numeric == 353 -> accumulateNames(message)
        numeric == 366 || numeric == 315 -> finalizeNames(message)
        numeric == 367 -> handleBanListEntry(message)
        numeric == 352 -> accumulateWhoLine(message)
        numeric == 354 -> handleWhoXLine(message)
        numeric == 322 -> handleListEntry(message)
        numeric == 323 -> emit(InboundEffect.ChannelListFinished)
        numeric in JOIN_FAILURE_NUMERICS -> handleJoinFailure(message)
        numeric in WHOIS_NUMERICS -> handleWhoisNumeric(numeric, message)
        numeric == 730 || numeric == 731 -> serverLine(message, "monitor")
        numeric in METADATA_NUMERICS -> handleMetadataNumeric(numeric, message)
        numeric in 400..599 -> serverLine(message, "error")
        else -> serverLine(message)
    }
}

internal fun Reduction.handleBanListEntry(message: IrcMessage) {
    if (message.parameters.size < 2) return
    val ref = state.channelRef(message.parameters[1])
    val body = message.parameters.drop(2).joinToString(" ").ifEmpty { message.parameters[1] }
    system(ref, body, MessageKind.SYSTEM)
}

internal fun Reduction.handleJoinFailure(message: IrcMessage) {
    val channelName = message.parameters.getOrNull(1).orEmpty()
    if (!state.isChannelName(channelName)) {
        serverLine(message, "error")
        return
    }
    val ref = state.channelRef(channelName)
    emit(InboundEffect.EnsureBuffer(ref))
    emit(InboundEffect.SetJoinState(ref, JoinState.FAILED))
    system(ref, message.parameters.getOrNull(2) ?: "cannot join $channelName")
}

internal fun Reduction.handleWhoisNumeric(numeric: Int, message: IrcMessage) {
    if (!state.whoisExpected) return
    val complete = state.whois.handle(numeric, message.parameters) ?: return
    state.whoisExpected = false
    emit(InboundEffect.WhoisCompleted(complete))
}

internal fun Reduction.handleListEntry(message: IrcMessage) {
    if (message.parameters.size < 3 || !state.listingChannels) return
    emit(
        InboundEffect.ChannelListed(
            LiveCoordinator.ChannelListEntry(
                name = message.parameters[1],
                users = message.parameters[2].toIntOrNull() ?: 0,
                topic = message.parameters.getOrNull(3).orEmpty(),
            ),
        ),
    )
}
