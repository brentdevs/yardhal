package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.IrcMetadata

private const val DISPLAY_NAME_MAX_CHARS = 64

internal val PROFILE_DEFERRING_BATCHES: Set<String> = setOf(IrcMetadata.BATCH_TYPE, "netsplit", "netjoin")

public data class UserProfile(
    public val avatarUrl: String? = null,
    public val displayName: String? = null,
    public val cached: Boolean = false,
    public val observedAtMs: Long? = null,
) {
    public companion object {
        public fun from(metadata: Map<String, String>): UserProfile? {
            if (metadata.isEmpty()) return null
            val avatar = metadata[IrcMetadata.KEY_AVATAR]?.trim()?.takeIf { it.isNotEmpty() }
            val displayName = metadata[IrcMetadata.KEY_DISPLAY_NAME]
                ?.filterNot { it.isISOControl() }
                ?.trim()
                ?.take(DISPLAY_NAME_MAX_CHARS)
                ?.takeIf { it.isNotEmpty() }
            if (avatar == null && displayName == null) return null
            return UserProfile(avatar, displayName)
        }
    }
}

public data class NetworkProfiles(
    public val casemapping: CaseMapping,
    public val byFoldedNick: Map<String, UserProfile>,
) {
    public fun forNick(nick: String): UserProfile? = byFoldedNick[casemapping.fold(nick)]

    public companion object {
        public val EMPTY: NetworkProfiles = NetworkProfiles(CaseMapping.RFC1459, emptyMap())
    }
}

internal class UserTable : LinkedHashMap<String, UserState>() {
    val profiles: LinkedHashMap<String, UserProfile> = LinkedHashMap()
    var profileVersion: Long = 0
        private set

    override fun put(key: String, value: UserState): UserState? {
        val previous = super.put(key, value)
        syncProfile(key, value.profile)
        if (value.metadataObservedAtMs != null &&
            (previous?.metadataObservedAtMs != value.metadataObservedAtMs || previous?.metadataKeysObserved != value.metadataKeysObserved)
        ) profileVersion += 1
        return previous
    }

    override fun putIfAbsent(key: String, value: UserState): UserState? {
        val existing = get(key)
        if (existing == null) put(key, value)
        return existing
    }

    override fun remove(key: String): UserState? {
        val previous = super.remove(key)
        syncProfile(key, null)
        return previous
    }

    override fun clear() {
        super.clear()
        if (profiles.isNotEmpty()) {
            profiles.clear()
            profileVersion += 1
        }
    }

    private fun syncProfile(key: String, profile: UserProfile?) {
        val changed = if (profile == null) profiles.remove(key) != null else profiles.put(key, profile) != profile
        if (changed) profileVersion += 1
    }
}
