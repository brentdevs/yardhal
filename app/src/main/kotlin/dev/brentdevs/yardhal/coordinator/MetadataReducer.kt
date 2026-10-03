package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcMetadata
import dev.brentdevs.yardhal.core.protocol.MetadataCapability

internal val SUBSCRIBED_METADATA_KEYS: List<String> = listOf(IrcMetadata.KEY_AVATAR, IrcMetadata.KEY_DISPLAY_NAME)
internal const val DEFAULT_METADATA_SYNC_DELAY_MS: Long = 5_000L
internal const val MAX_METADATA_SYNC_DELAY_MS: Long = 600_000L
private const val MILLIS_PER_SECOND = 1_000L

internal fun Reduction.applyMetadataCapability(capabilities: Set<String>, values: Map<String, String>) {
    val wasEnabled = state.metadataCapability != null
    state.metadataCapability = if (IrcMetadata.CAPABILITY in capabilities) {
        MetadataCapability.parse(values[IrcMetadata.CAPABILITY])
    } else {
        null
    }
    if (!wasEnabled && state.registered) subscribeMetadata()
}

internal fun Reduction.subscribeMetadata() {
    val capability = state.metadataCapability ?: return
    val pending = SUBSCRIBED_METADATA_KEYS.filter { it !in state.metadataSubscriptions }
    val room = capability.maxSubs?.let { (it - state.metadataSubscriptions.size).coerceAtLeast(0) } ?: pending.size
    val keys = pending.take(room)
    if (keys.isEmpty()) return
    emit(InboundEffect.SendRaw("${IrcMetadata.COMMAND} * SUB ${keys.joinToString(" ")}"))
}

internal fun Reduction.handleMetadataMessage(message: IrcMessage) {
    val parameters = message.parameters
    if (parameters.size < 3) return
    storeMetadata(target = parameters[0], key = parameters[1], value = parameters.getOrNull(3))
}

internal fun Reduction.handleMetadataNumeric(numeric: Int, message: IrcMessage) {
    val parameters = message.parameters
    when (numeric) {
        IrcMetadata.RPL_KEYVALUE, IrcMetadata.RPL_WHOISKEYVALUE -> {
            if (parameters.size < 5) return
            val target = parameters[1]
            val key = parameters[2]
            val value = parameters[4]
            storeMetadata(target, key, value)
            system(state.server, "[metadata] $target $key = $value")
        }
        IrcMetadata.RPL_KEYNOTSET -> {
            if (parameters.size < 3) return
            val target = parameters[1]
            val key = parameters[2]
            storeMetadata(target, key, null)
            system(state.server, "[metadata] $target $key is not set")
        }
        IrcMetadata.RPL_METADATASUBOK -> state.metadataSubscriptions += parameters.drop(1)
        IrcMetadata.RPL_METADATAUNSUBOK -> state.metadataSubscriptions -= parameters.drop(1).toSet()
        IrcMetadata.RPL_METADATASUBS -> {
            val keys = parameters.drop(1)
            state.metadataSubscriptions += keys
            system(state.server, "[metadata] subscribed: ${keys.joinToString(" ")}")
        }
        IrcMetadata.RPL_METADATASYNCLATER -> scheduleMetadataSync(parameters)
    }
}

internal fun Reduction.handleMetadataFail(message: IrcMessage) {
    val details = message.parameters.drop(1)
    val code = details.firstOrNull() ?: return
    val context = details.drop(1).dropLast(1)
    val description = details.drop(1).lastOrNull().orEmpty()
    val subject = if (context.isEmpty()) "" else " (${context.joinToString(" ")})"
    system(state.server, "[metadata] $code$subject: $description")
}

internal fun Reduction.setNetworkIcon(url: String?) {
    if (state.networkIconUrl == url) return
    state.networkIconUrl = url
    emit(InboundEffect.NetworkIconChanged(url))
}

private fun Reduction.scheduleMetadataSync(parameters: List<String>) {
    val target = parameters.getOrNull(1)?.takeIf { it.isNotEmpty() && ' ' !in it } ?: return
    val retryAfterSeconds = parameters.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }
    val delayMs = retryAfterSeconds
        ?.let { minOf(it, MAX_METADATA_SYNC_DELAY_MS / MILLIS_PER_SECOND) * MILLIS_PER_SECOND }
        ?: DEFAULT_METADATA_SYNC_DELAY_MS
    emit(InboundEffect.ScheduleRaw("${IrcMetadata.COMMAND} $target SYNC", delayMs, state.connectionEpoch))
}

private fun Reduction.storeMetadata(target: String, key: String, value: String?) {
    if (!IrcMetadata.isValidKey(key)) return
    when {
        target == "*" -> storeUserMetadata(state.ownNick, key, value)
        state.isChannelName(target) -> {
            val channel = state.channels[state.channelRef(target).storageKey] ?: return
            if (value == null) channel.metadata.remove(key) else channel.metadata[key] = value
        }
        else -> storeUserMetadata(target, key, value)
    }
}

private fun Reduction.storeUserMetadata(nick: String, key: String, value: String?) {
    val folded = state.fold(nick)
    val existing = state.users[folded]
    if (existing == null && value == null) return
    val base = existing ?: UserState(nick)
    val metadata = if (value == null) base.metadata - key else base.metadata + (key to value)
    state.users[folded] = base.copy(metadata = metadata)
}
