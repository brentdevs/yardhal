package dev.brentdevs.yardhal.coordinator

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
)

public data class ConversationBuffer(
    public val ref: ConversationRef,
    public val displayName: String,
    public val topic: String? = null,
    public val messages: List<ChatMessage> = emptyList(),
    public val hasUnread: Boolean = false,
    public val members: List<String> = emptyList(),
) {
    public val key: String get() = ref.storageKey
}

public object ConversationNames {
    public fun forRef(ref: ConversationRef): String = when (ref.kind) {
        ConversationKind.SERVER -> "Server"
        else -> ref.rawTarget
    }
}
