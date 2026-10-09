package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.client.CertificateInspection
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
    public val hasBotMode: Boolean = false,
    public val accountBanAvailable: Boolean = false,
    public val iconUrl: String? = null,
    public val connectionPhase: RecoveryPhase = when (status) {
        ConnectionStatus.DISCONNECTED -> RecoveryPhase.DISCONNECTED
        ConnectionStatus.CONNECTING -> RecoveryPhase.CONNECTING
        ConnectionStatus.REGISTERED -> RecoveryPhase.REGISTERED
    },
    public val connectionError: String? = null,
    public val disconnectSavePending: Boolean = false,
    public val rejectedCertificate: CertificateInspection? = null,
    public val hasCertificatePin: Boolean = false,
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
    public val echoLabel: String? = null,
    public val storedRowId: Long? = null,
    public val senderAccount: String? = null,
    public val channelContext: String? = null,
    public val historyContext: Boolean = false,
    public val localReplyParentRowId: Long? = null,
    public val replyPreview: ReplyPreview? = null,
    public val attachmentName: String? = null,
    public val attachmentMimeType: String? = null,
    public val attachmentSizeBytes: Long? = null,
    public val attachmentWidth: Int? = null,
    public val attachmentHeight: Int? = null,
    public val redacted: Boolean = false,
    public val highlightsKnown: Boolean = true,
    public val reactionsTruncated: Boolean = false,
) {
    public val countsAsUnread: Boolean
        get() = !sentByUs && !playback && !historyContext && !redacted &&
            (kind == MessageKind.PRIVMSG || kind == MessageKind.NOTICE || kind == MessageKind.ACTION)
}

public data class ReplyPreview(
    public val sender: String,
    public val text: String,
    public val attachmentUrl: String? = null,
    public val redacted: Boolean = false,
)

public data class WhoisPresentation(
    public val networkId: String,
    public val info: dev.brentdevs.yardhal.core.data.WhoisInfo,
    public val fetchedAtMs: Long,
    public val cached: Boolean,
    public val refreshing: Boolean,
    public val offline: Boolean,
)

public data class PresenceState(
    public val away: Boolean? = null,
    public val awayMessage: String? = null,
    public val account: String? = null,
    public val user: String? = null,
    public val host: String? = null,
    public val realName: String? = null,
    public val isBot: Boolean = false,
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
    public val history: ConversationHistory = ConversationHistory(),
    public val cachedTopic: Boolean = false,
    public val cachedRoster: Boolean = false,
    public val rosterTruncated: Boolean = false,
    public val cachedModes: Boolean = false,
    public val cachedStateAtMs: Long? = null,
    public val channelModes: Map<String, List<String>> = emptyMap(),
    public val unreadCount: Int = 0,
    public val mentionCount: Int = 0,
    public val mentionCountKnown: Boolean = true,
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
