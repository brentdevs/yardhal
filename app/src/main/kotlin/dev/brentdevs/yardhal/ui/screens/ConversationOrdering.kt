package dev.brentdevs.yardhal.ui.screens

import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.core.data.ChannelMember
import dev.brentdevs.yardhal.core.data.NicknameSuggestionOrder
import java.util.Locale

internal fun nicknameSuggestions(buffer: ConversationBuffer, order: NicknameSuggestionOrder): List<String> {
    val alphabetical = compareBy<ChannelMember> { it.nick.lowercase(Locale.ROOT) }.thenBy { it.nick }
    val comparison = when (order) {
        NicknameSuggestionOrder.ALPHABETICAL -> alphabetical
        NicknameSuggestionOrder.ROLE -> compareBy<ChannelMember> {
            when (it.symbol) { '~' -> 0; '&' -> 1; '@' -> 2; '%' -> 3; '+' -> 4; else -> 5 }
        }.then(alphabetical)
        NicknameSuggestionOrder.RECENT -> {
            val latest = HashMap<String, Long>()
            for (message in buffer.messages) {
                val key = message.sender.lowercase(Locale.ROOT)
                latest[key] = maxOf(latest[key] ?: Long.MIN_VALUE, message.timestampMs)
            }
            compareByDescending<ChannelMember> { latest[it.nick.lowercase(Locale.ROOT)] ?: Long.MIN_VALUE }.then(alphabetical)
        }
    }
    return buffer.members.sortedWith(comparison).map { it.nick }
}

internal fun overviewBadgeLabel(unread: Int, mentions: Int, mentionCountKnown: Boolean, showCounts: Boolean): String {
    if (!showCounts) return if (mentions > 0 || !mentionCountKnown) "@" else "•"
    val unreadLabel = if (unread > 99) "99+" else if (unread > 0) "$unread" else "•"
    return mentionBadgeLabel(mentions, mentionCountKnown)?.let { "$unreadLabel · $it" } ?: unreadLabel
}
