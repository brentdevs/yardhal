package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ChannelMember
import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.protocol.CaseMapping

public data class UiNetwork(
    public val id: String,
    public val name: String,
    public val host: String,
    public val status: ConnectionStatus,
    public val ownNick: String,
) {
    public val storagePrefix: String get() = id
}

public enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    REGISTERED,
}

public data class ChatMessage(
    public val localId: Long,
    public val sender: String,
    public val kind: MessageKind,
    public val text: String,
    public val timestampMs: Long,
    public val sentByUs: Boolean,
    public val highlightsMe: Boolean,
    public val msgid: String?,
    public val replyToMsgid: String? = null,
    public val attachmentUrl: String? = null,
    public val playback: Boolean = false,
    public val pendingEcho: Boolean = false,
    public val storedRowId: Long? = null,
    public val channelContext: String? = null,
) {
    public val countsAsUnread: Boolean
        get() = !sentByUs && !playback &&
            kind in setOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)
}

public data class PresenceState(
    public val away: Boolean,
    public val account: String? = null,
)

public enum class JoinState {
    IDLE,
    JOINING,
    JOINED,
    FAILED,
}

public data class ConversationBuffer(
    public val ref: ConversationRef,
    public val displayName: String,
    public val topic: String? = null,
    public val messages: List<ChatMessage> = emptyList(),
    public val hasUnread: Boolean = false,
    public val members: List<ChannelMember> = emptyList(),
    public val memberPresence: Map<String, PresenceState> = emptyMap(),
    public val typingUsers: Map<String, Long> = emptyMap(),
    public val reactions: Map<String, Map<String, Set<String>>> = emptyMap(),
    public val replyDraft: ChatMessage? = null,
    public val joinState: JoinState = JoinState.JOINED,
    public val unreadFromTimestampMs: Long? = null,
    public val readAtMs: Long = 0L,
) {
    public val key: String get() = ref.storageKey

    public fun activeTypers(nowMs: Long): List<String> =
        typingUsers.filterValues { it > nowMs }.keys.sorted()
}

public object ConversationNames {
    public fun forRef(ref: ConversationRef): String = when (ref.kind) {
        ConversationKind.SERVER -> "Server"
        else -> ref.rawTarget
    }
}
