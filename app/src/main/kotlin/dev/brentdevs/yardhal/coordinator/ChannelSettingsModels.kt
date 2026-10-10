package dev.brentdevs.yardhal.coordinator

public enum class ChannelSettingsRequestStatus {
    IDLE,
    PENDING,
    CONFIRMED,
    ERROR,
    TIMEOUT,
    DISCONNECTED,
}

public enum class ChannelAccessListStatus {
    IDLE,
    LOADING,
    COMPLETE,
    EMPTY,
    ERROR,
    TIMEOUT,
    DISCONNECTED,
}

public data class ChannelAccessEntry(
    public val mask: String,
    public val setter: String?,
    public val setAtEpochSeconds: Long?,
)

public data class ChannelAccessList(
    public val mode: Char,
    public val status: ChannelAccessListStatus = ChannelAccessListStatus.IDLE,
    public val entries: List<ChannelAccessEntry> = emptyList(),
    public val error: String? = null,
    public val limit: Int? = null,
)

public data class ChannelModeSetting(
    public val mode: Char,
    public val enabled: Boolean,
    public val parameter: String = "",
    public val parameterOnEnable: Boolean = false,
    public val parameterOnDisable: Boolean = false,
)

public data class ChannelSettingsState(
    public val storageKey: String,
    public val channel: String,
    public val available: Boolean,
    public val canEditModes: Boolean,
    public val canEditTopic: Boolean,
    public val topic: String,
    public val topicLengthLimit: Int?,
    public val modes: List<ChannelModeSetting>,
    public val accessLists: Map<Char, ChannelAccessList>,
    public val requestStatus: ChannelSettingsRequestStatus = ChannelSettingsRequestStatus.IDLE,
    public val requestDescription: String? = null,
    public val error: String? = null,
    public val unavailableReason: String? = null,
) {
    public val busy: Boolean get() = requestStatus == ChannelSettingsRequestStatus.PENDING ||
        accessLists.values.any { it.status == ChannelAccessListStatus.LOADING }
}
