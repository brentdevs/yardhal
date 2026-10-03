package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcMultiline
import dev.brentdevs.yardhal.core.protocol.MultilineLimits
import dev.brentdevs.yardhal.core.protocol.MultilineLine

internal const val CHANNEL_CONTEXT_TAG: String = "+channel-context"
internal const val DRAFT_CHANNEL_CONTEXT_TAG: String = "+draft/channel-context"

internal val FALLBACK_INBOUND_MULTILINE_LIMITS: MultilineLimits = MultilineLimits(maxBytes = 65_536, maxLines = 1_000)

internal fun Reduction.handleCapMessage(message: IrcMessage) {
    val verb = message.parameters.getOrNull(1)?.uppercase() ?: return
    val tokens = message.parameters.lastOrNull()?.split(' ')?.filter { it.isNotEmpty() } ?: return
    when (verb) {
        "LS", "NEW" -> tokens
            .firstOrNull { it.substringBefore('=') == IrcMultiline.CAPABILITY }
            ?.let { state.multilineLimits = MultilineLimits.parse(it.substringAfter('=', "")) }
        "DEL" -> if (IrcMultiline.CAPABILITY in tokens) state.multilineLimits = null
    }
}

internal fun Reduction.bufferMultilineLine(message: IrcMessage): Boolean {
    val reference = message.tag("batch") ?: return false
    val batch = state.openBatches[reference] ?: return false
    val buffer = batch.multiline ?: return false
    if (buffer.overflowed) return false
    val batchTarget = batch.parameters.firstOrNull() ?: return false
    if (state.fold(message.parameters[0]) != state.fold(batchTarget)) return false
    val first = buffer.lines.firstOrNull()
    if (first != null && !first.command.equals(message.command, ignoreCase = true)) return false
    val limits = state.multilineLimits ?: FALLBACK_INBOUND_MULTILINE_LIMITS
    val concat = buffer.lines.isNotEmpty() && message.tags.containsKey(IrcMultiline.CONCAT_TAG)
    val separator = if (buffer.lines.isEmpty() || concat) 0 else 1
    val added = message.parameters[1].toByteArray(Charsets.UTF_8).size + separator
    val exceedsLines = limits.maxLines?.let { buffer.lines.size + 1 > it } ?: false
    if (exceedsLines || buffer.byteCount + added > limits.maxBytes) {
        flushMultiline(batch, buffer)
        buffer.overflowed = true
        return false
    }
    buffer.lines += message
    buffer.byteCount += added
    return true
}

internal fun Reduction.flushMultiline(batch: OpenBatch, buffer: MultilineBuffer) {
    val first = buffer.lines.firstOrNull() ?: return
    val text = IrcMultiline.combine(
        buffer.lines.map { MultilineLine(it.parameters[1], it.tags.containsKey(IrcMultiline.CONCAT_TAG)) },
    )
    val tags = LinkedHashMap<String, String?>()
    tags.putAll(first.tags)
    tags.putAll(buffer.opening.tags)
    tags.remove("batch")
    (first.tag("time") ?: buffer.opening.tag("time"))?.let { tags["time"] = it }
    buffer.lines.clear()
    buffer.byteCount = 0
    emitChat(
        prefix = buffer.opening.prefix ?: first.prefix,
        command = first.command,
        targetParam = batch.parameters.firstOrNull() ?: first.parameters[0],
        rawText = text,
        tags = tags,
        playback = isPlaybackBatch(batch.parent),
    )
}
