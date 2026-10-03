package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ChannelMember
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.WhoisInfo

public sealed interface InboundEffect {
    public data class SendRaw(public val line: String) : InboundEffect

    public data class StatusChanged(public val status: ConnectionStatus) : InboundEffect

    public data class OwnNickChanged(public val nick: String) : InboundEffect

    public data class EnsureBuffer(public val ref: ConversationRef) : InboundEffect

    public data class RemoveBuffer(public val ref: ConversationRef) : InboundEffect

    public data class AppendMessage(
        public val ref: ConversationRef,
        public val sender: String,
        public val kind: MessageKind,
        public val text: String,
        public val timestampMs: Long,
        public val msgid: String? = null,
        public val sentByUs: Boolean = false,
        public val highlightsMe: Boolean = false,
        public val replyToMsgid: String? = null,
        public val attachmentUrl: String? = null,
        public val playback: Boolean = false,
        public val reconcilePendingEcho: Boolean = false,
        public val echoLabel: String? = null,
        public val senderAccount: String? = null,
    ) : InboundEffect

    public data class SetTopic(public val ref: ConversationRef, public val topic: String?) : InboundEffect

    public data class SetMembers(
        public val ref: ConversationRef,
        public val members: List<ChannelMember>,
        public val presence: Map<String, PresenceState>,
    ) : InboundEffect

    public data class SetJoinState(public val ref: ConversationRef, public val state: JoinState) : InboundEffect

    public data class ClearTyping(public val ref: ConversationRef) : InboundEffect

    public data class SetTyping(
        public val ref: ConversationRef,
        public val nick: String,
        public val expiresAtMs: Long?,
    ) : InboundEffect

    public data class ApplyReaction(
        public val ref: ConversationRef,
        public val sender: String,
        public val emoji: String,
        public val msgids: List<String>,
        public val added: Boolean,
    ) : InboundEffect

    public data class RedactMessage(public val msgid: String) : InboundEffect

    public data class ApplyReadMarker(public val ref: ConversationRef, public val timestampMs: Long) : InboundEffect

    public data class WhoisCompleted(public val info: WhoisInfo) : InboundEffect

    public data class ChannelListed(public val entry: LiveCoordinator.ChannelListEntry) : InboundEffect

    public data object ChannelListFinished : InboundEffect

    public data object BouncerNetworksChanged : InboundEffect

    public data object NetworkFeaturesChanged : InboundEffect
}
