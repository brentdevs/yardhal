package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.CachedPresence

internal fun CachedPresence.toPresenceState(): PresenceState = PresenceState(
    away = away,
    awayMessage = awayMessage,
    account = account,
    user = user,
    host = host,
    realName = realName,
    isBot = isBot,
)

internal fun PresenceState.toCachedPresence(redact: (String) -> String = { it }): CachedPresence = CachedPresence(
    away = away,
    awayMessage = awayMessage?.let(redact),
    account = account?.let(redact),
    user = user?.let(redact),
    host = host?.let(redact),
    realName = realName?.let(redact),
    isBot = isBot,
)

internal fun mergeObservedProfile(previous: UserProfile?, user: UserState, redact: (String) -> String = { it }): UserProfile? {
    val fresh = UserProfile.from(user.metadata)
    val avatarObserved = dev.brentdevs.yardhal.core.protocol.IrcMetadata.KEY_AVATAR in user.metadataKeysObserved
    val displayObserved = dev.brentdevs.yardhal.core.protocol.IrcMetadata.KEY_DISPLAY_NAME in user.metadataKeysObserved
    val avatar = if (avatarObserved) fresh?.avatarUrl?.let(redact) else previous?.avatarUrl
    val display = if (displayObserved) fresh?.displayName?.let(redact) else previous?.displayName
    if (avatar == null && display == null) return null
    return UserProfile(avatar, display,
        cached = !avatarObserved && previous?.avatarUrl != null || !displayObserved && previous?.displayName != null,
        observedAtMs = user.metadataObservedAtMs ?: previous?.observedAtMs)
}

internal fun mergeObservedMetadata(previous: Map<String, String>, user: UserState, redact: (String) -> String = { it }): Map<String, String> {
    val merged = previous.toMutableMap()
    for (key in user.metadataKeysObserved) {
        val value = user.metadata[key]
        if (value == null) merged.remove(key) else merged[key] = redact(value)
    }
    return merged
}
