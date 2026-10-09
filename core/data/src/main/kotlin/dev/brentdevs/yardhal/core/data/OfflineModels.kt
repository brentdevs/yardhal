package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.CaseMapping
import kotlinx.serialization.Serializable

public object OfflineCachePolicy {
    public const val WHOIS_FRESHNESS_MS: Long = 5L * 60 * 1000
    public const val RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
    public const val MAX_CONVERSATIONS_PER_NETWORK: Int = 1000
    public const val MAX_USERS_PER_NETWORK: Int = 1000
    public const val MAX_ROSTER_ENTRIES: Int = 10000
}

public data class OfflineConversationShell(
    public val ref: ConversationRef,
    public val displayName: String,
    public val observedAtMs: Long,
    public val caseMapping: CaseMapping = CaseMapping.RFC1459,
    public val topic: String? = null,
    public val topicObservedAtMs: Long? = null,
    public val modes: Map<String, List<String>> = emptyMap(),
    public val modesObservedAtMs: Long? = null,
)

@Serializable
public data class CachedPresence(
    public val away: Boolean? = null,
    public val awayMessage: String? = null,
    public val account: String? = null,
    public val user: String? = null,
    public val host: String? = null,
    public val realName: String? = null,
    public val isBot: Boolean = false,
)

@Serializable
public data class OfflineRoster(
    public val members: List<ChannelMember>,
    public val presence: Map<String, CachedPresence> = emptyMap(),
    public val observedAtMs: Long,
    public val completeAtObservation: Boolean = false,
    public val truncated: Boolean = false,
    public val memberObservedAtMs: Map<String, Long> = emptyMap(),
    public val presenceObservedAtMs: Map<String, Long> = emptyMap(),
)

@Serializable
public data class CachedUser(
    public val nick: String,
    public val presence: CachedPresence = CachedPresence(),
    public val metadata: Map<String, String> = emptyMap(),
    public val observedAtMs: Long,
)

public data class CachedWhois(
    public val info: WhoisInfo,
    public val observedAtMs: Long,
) {
    public fun isFresh(nowMs: Long): Boolean =
        nowMs >= observedAtMs && nowMs - observedAtMs in 0 until OfflineCachePolicy.WHOIS_FRESHNESS_MS
}
