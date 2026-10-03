package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.MessageKind

internal fun redactInBuffer(buffer: ConversationBuffer, msgid: String): ConversationBuffer {
    val index = buffer.messages.indexOfFirst { it.msgid == msgid }
    if (index < 0) return buffer
    val messages = buffer.messages.toMutableList()
    messages[index] = messages[index].copy(text = "message deleted", kind = MessageKind.SYSTEM)
    return buffer.copy(messages = messages)
}

internal fun applyReaction(
    reactions: Map<String, Map<String, Set<String>>>,
    effect: InboundEffect.ApplyReaction,
): Map<String, Map<String, Set<String>>> {
    var updated = reactions
    for (msgid in effect.msgids) {
        val perMessage = updated[msgid].orEmpty().toMutableMap()
        val nicks = perMessage[effect.emoji].orEmpty().toMutableSet()
        if (effect.added) nicks.add(effect.sender) else nicks.remove(effect.sender)
        if (nicks.isEmpty()) perMessage.remove(effect.emoji) else perMessage[effect.emoji] = nicks
        updated = updated + (msgid to perMessage.toMap())
    }
    return updated
}
