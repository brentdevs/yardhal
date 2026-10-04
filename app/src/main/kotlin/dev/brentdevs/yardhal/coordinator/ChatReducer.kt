package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.MentionMatcher
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.protocol.IrcCtcp
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcMultiline
import dev.brentdevs.yardhal.core.protocol.IrcPrefix
import dev.brentdevs.yardhal.core.protocol.PrivmsgContent
import java.time.Instant

internal const val TYPING_TTL_MS: Long = 6_000L

private val PLAYBACK_BATCH_TYPES = setOf("znc.in/playback", "chathistory")

internal fun parseServerTime(value: String?): Long? {
    if (value.isNullOrEmpty()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

internal fun Reduction.isPlayback(message: IrcMessage): Boolean = isPlaybackBatch(message.tag("batch"))

internal fun Reduction.isPlaybackBatch(start: String?): Boolean {
    var reference = start
    while (reference != null) {
        val batch = state.openBatches[reference] ?: return false
        if (batch.type in PLAYBACK_BATCH_TYPES) return true
        reference = batch.parent
    }
    return false
}

internal fun Reduction.handleChatMessage(message: IrcMessage) {
    if (message.parameters.size < 2) return
    if (bufferMultilineLine(message)) return
    emitChat(
        prefix = message.prefix,
        command = message.command,
        targetParam = message.parameters[0],
        rawText = message.parameters[1],
        tags = message.tags,
        playback = isPlayback(message),
    )
}

internal fun Reduction.emitChat(
    prefix: IrcPrefix?,
    command: String,
    targetParam: String,
    rawText: String,
    tags: Map<String, String?>,
    playback: Boolean,
) {
    if (prefix == null) return
    val senderNick = prefix.nick
    val fromUs = state.isOwnNick(senderNick)
    if (!fromUs && context.isIgnored(senderNick)) return
    val ref = when {
        state.isChannelName(targetParam) -> state.channelRef(targetParam)
        targetParam.startsWith("*") -> replyRef
        fromUs -> state.directRef(targetParam)
        prefix.isServer -> replyRef
        else -> state.directRef(senderNick)
    }
    val decoded = IrcCtcp.decode(rawText)
    val ctcpAction = decoded.filterIsInstance<PrivmsgContent.Ctcp>().firstOrNull { it.message.command == IrcCtcp.ACTION }
    val kind = when {
        ctcpAction != null -> MessageKind.ACTION
        command.equals("NOTICE", true) -> MessageKind.NOTICE
        else -> MessageKind.PRIVMSG
    }
    val body = ctcpAction?.message?.arguments
        ?: decoded.filterIsInstance<PrivmsgContent.Plain>().firstOrNull()?.text
        ?: rawText
    val channelContext = (tags[CHANNEL_CONTEXT_TAG] ?: tags[DRAFT_CHANNEL_CONTEXT_TAG])
        ?.takeIf { ref.kind == ConversationKind.DIRECT_MESSAGE && state.isChannelName(it) && ' ' !in it && ',' !in it }
    emit(
        InboundEffect.AppendMessage(
            ref = ref,
            sender = senderNick,
            kind = kind,
            text = body,
            timestampMs = parseServerTime(tags["time"]) ?: context.nowMs,
            msgid = tags["msgid"],
            sentByUs = fromUs,
            highlightsMe = !fromUs && !playback && MentionMatcher.containsMessage(body, state.ownNick, state.casemapping),
            replyToMsgid = tags["+draft/reply"],
            attachmentUrl = tags["+draft/attachment"],
            playback = playback,
            reconcilePendingEcho = fromUs,
            echoLabel = correlation?.takeIf { fromUs && it.command == LabeledCommand.PRIVMSG }?.label,
            senderAccount = accountValue(tags["account"]),
            channelContext = channelContext,
        ),
    )
}

internal fun Reduction.handleTagmsg(message: IrcMessage) {
    val sender = message.prefix?.nick ?: return
    val targetParam = message.parameters.firstOrNull() ?: return
    val ref = if (state.isChannelName(targetParam)) state.channelRef(targetParam) else state.directRef(sender)
    val react = message.tag("+draft/react")
    val unreact = message.tag("+draft/unreact")
    if (react != null || unreact != null) {
        val refs = (message.tag("+draft/refs") ?: message.tag("+draft/msgids"))
            ?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        if (refs.isEmpty()) return
        val emoji = react ?: unreact ?: return
        emit(InboundEffect.ApplyReaction(ref, sender, emoji, refs, added = react != null))
        return
    }
    val typing = message.tag("+typing") ?: return
    val expiresAt = if (typing == "active") context.nowMs + TYPING_TTL_MS else null
    emit(InboundEffect.SetTyping(ref, sender, expiresAt))
}

internal fun Reduction.handleRedact(message: IrcMessage) {
    if (message.parameters.size < 2) return
    emit(InboundEffect.RedactMessage(message.parameters[1]))
}

internal fun Reduction.handleInboundMarkRead(message: IrcMessage) {
    val target = message.parameters.firstOrNull() ?: return
    if (target.startsWith("*")) return
    val iso = message.parameters.getOrNull(1)?.substringAfter("timestamp=", "").orEmpty()
    if (iso.isEmpty()) return
    val millis = parseServerTime(iso) ?: return
    emit(InboundEffect.ApplyReadMarker(state.targetRef(target), millis))
}

internal fun Reduction.handleBatchFrame(message: IrcMessage) {
    val head = message.parameters.firstOrNull() ?: return
    val reference = head.drop(1)
    when {
        head.startsWith("+") -> {
            val type = message.parameters.getOrNull(1) ?: return
            val parameters = message.parameters.drop(2)
            val multiline = if (type == IrcMultiline.BATCH_TYPE) MultilineBuffer(message) else null
            state.openBatches[reference] = OpenBatch(type, parameters, message.tag("batch"), label = message.tag("label"), multiline = multiline)
            state.netsplit.onStart(reference, type, parameters)
        }
        head.startsWith("-") -> {
            val closing = state.openBatches[reference]
            closing?.multiline?.let { buffer -> if (!buffer.overflowed) flushMultiline(closing, buffer) }
            state.openBatches.remove(reference)?.let { releaseBatchLabel(it) }
            val summary = state.netsplit.onEnd(reference) ?: return
            system(state.server, summary.toString())
        }
    }
}
