package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.CaseMapping

public enum class ConversationKind {
    SERVER,
    CHANNEL,
    DIRECT_MESSAGE,
}

public data class ConversationRef(
    public val networkId: String,
    public val kind: ConversationKind,
    public val rawTarget: String,
    public val normalizedTarget: String,
) {
    public val storageKey: String
        get() = "$networkId|$normalizedTarget"

    public companion object {
        public const val SERVER_TARGET: String = "*server*"

        public fun server(networkId: String): ConversationRef =
            ConversationRef(networkId, ConversationKind.SERVER, SERVER_TARGET, SERVER_TARGET)

        public fun channel(networkId: String, name: String, casemapping: CaseMapping = CaseMapping.RFC1459): ConversationRef =
            ConversationRef(networkId, ConversationKind.CHANNEL, name, casemapping.fold(name))

        public fun directMessage(networkId: String, nick: String, casemapping: CaseMapping = CaseMapping.RFC1459): ConversationRef =
            ConversationRef(networkId, ConversationKind.DIRECT_MESSAGE, nick, casemapping.fold(nick))
    }
}

public enum class MessageKind {
    PRIVMSG,
    NOTICE,
    ACTION,
    JOIN,
    PART,
    QUIT,
    NICK_CHANGE,
    MODE,
    TOPIC,
    KICK,
    SYSTEM,
}

public data class StoredMessage(
    public val rowId: Long = 0,
    public val networkId: String,
    public val conversation: ConversationRef,
    public val msgid: String?,
    public val senderNick: String,
    public val senderUser: String?,
    public val senderHost: String?,
    public val kind: MessageKind,
    public val text: String,
    public val sentByUs: Boolean,
    public val timestampMs: Long,
)
