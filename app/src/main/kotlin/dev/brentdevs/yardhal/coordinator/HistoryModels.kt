package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.protocol.HistoryAnchor

public enum class HistoryLoadStatus {
    IDLE,
    LOADING,
    EXHAUSTED,
    FAILED,
    CANCELLED,
    UNSUPPORTED,
    WINDOW_EMPTY,
}

public data class HistoryLoadState(
    public val status: HistoryLoadStatus = HistoryLoadStatus.IDLE,
    public val error: String? = null,
    public val requiresReconnect: Boolean = false,
    public val windowBeforeMs: Long? = null,
)

public data class HistoryGap(
    public val id: String,
    public val from: HistoryAnchor,
    public val to: HistoryAnchor,
    public val state: HistoryLoadState = HistoryLoadState(),
)

public data class ConversationHistory(
    public val initial: HistoryLoadState = HistoryLoadState(),
    public val older: HistoryLoadState = HistoryLoadState(),
    public val catchUp: HistoryLoadState = HistoryLoadState(),
    public val gaps: List<HistoryGap> = emptyList(),
    public val discovery: HistoryLoadState = HistoryLoadState(),
    public val discoveryTruncated: Boolean = false,
)
