package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.ConversationRef
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

internal fun Reduction.isPlayback(message: IrcMessage): Boolean =
    context.historyPlayback || playbackBatch(message.tag("batch")) != null

internal fun Reduction.playbackBatch(start: String?): OpenBatch? {
    var reference = start
    while (reference != null) {
        val batch = state.openBatches[reference] ?: return null
        if (batch.type in PLAYBACK_BATCH_TYPES) return batch
        reference = batch.parent
    }
    return null
}

internal fun Reduction.handleChatMessage(message: IrcMessage) {
    if (message.parameters.size < 2) return
    if (bufferMultilineLine(message)) return
    val batch = playbackBatch(message.tag("batch"))
    emitChat(
        prefix = message.prefix,
        command = message.command,
        targetParam = message.parameters[0],
        rawText = message.parameters[1],
        tags = message.tags,
        playback = context.historyPlayback || batch != null,
        historyTarget = batch?.parameters?.firstOrNull()?.let(state::targetRef),
    )
}

internal fun Reduction.emitChat(
    prefix: IrcPrefix?,
    command: String,
    targetParam: String,
    rawText: String,
    tags: Map<String, String?>,
    playback: Boolean,
    historyTarget: ConversationRef? = null,
) {
    if (prefix == null) return
    val senderNick = prefix.nick
    val fromUs = state.isOwnNick(senderNick)
    if (!fromUs && context.isIgnored(senderNick)) return
    val ref = messageConversation(prefix, targetParam, historyTarget)
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
            replyToMsgid = tags["+reply"] ?: tags["+draft/reply"],
            attachmentUrl = tags["+attachment"] ?: tags["+draft/attachment"],
            playback = playback,
            reconcilePendingEcho = fromUs,
            echoLabel = correlation?.takeIf { fromUs && it.command == LabeledCommand.PRIVMSG }?.label,
            senderAccount = accountValue(tags["account"]),
            channelContext = channelContext,
            historyContext = "draft/chathistory-context" in tags,
        ),
    )
}

internal fun Reduction.handleTagmsg(message: IrcMessage) {
    val sender = message.prefix?.nick ?: return
    val targetParam = message.parameters.firstOrNull() ?: return
    val historical = playbackBatch(message.tag("batch"))?.parameters?.firstOrNull()?.let(state::targetRef)
    val ref = messageConversation(message.prefix, targetParam, historical)
    val react = message.tag("+react") ?: message.tag("+draft/react")
    val unreact = message.tag("+unreact") ?: message.tag("+draft/unreact")
    if (react != null || unreact != null) {
        val refs = (message.tag("+refs") ?: message.tag("+draft/refs") ?: message.tag("+draft/msgids")
            ?: message.tag("+reply") ?: message.tag("+draft/reply"))
            ?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        if (refs.isEmpty()) return
        val emoji = react ?: unreact ?: return
        emit(InboundEffect.ApplyReaction(ref, sender, emoji, refs, added = react != null))
        return
    }
    if (isPlayback(message)) return
    val typing = message.tag("+typing") ?: message.tag("+draft/typing") ?: return
    val expiresAt = if (typing == "active") context.nowMs + TYPING_TTL_MS else null
    emit(InboundEffect.SetTyping(ref, sender, expiresAt))
}

internal fun Reduction.handleRedact(message: IrcMessage) {
    if (message.parameters.size < 2) return
    val target = message.parameters[0]
    val historical = playbackBatch(message.tag("batch"))?.parameters?.firstOrNull()?.let(state::targetRef)
    emit(InboundEffect.RedactMessage(messageConversation(message.prefix, target, historical), message.parameters[1]))
}

private fun Reduction.messageConversation(
    prefix: IrcPrefix?,
    target: String,
    historical: ConversationRef? = null,
): ConversationRef {
    val history = context.historyTarget ?: historical
    if (state.isChannelName(target)) {
        if (history?.kind == ConversationKind.CHANNEL && history.normalizedTarget == state.casemapping.fold(target)) return history
        return state.channelRef(target)
    }
    return history ?: when {
        target.startsWith("*") -> replyRef
        prefix != null && state.isOwnNick(prefix.nick) -> state.directRef(target)
        prefix == null || prefix.isServer -> replyRef
        else -> state.directRef(prefix.nick)
    }
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
