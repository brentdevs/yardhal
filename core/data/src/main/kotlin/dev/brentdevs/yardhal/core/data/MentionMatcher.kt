package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.CaseMapping

public object MentionMatcher {

    private val DELIMITERS = setOf(
        ' ', '\t', '\n', '\r', ',', ':', ';', '!', '?', '(', ')',
        '<', '>', '"', '\'', '.', '@', '#', '*', '+', '=', '/', '%', '&', '$',
    )

    public fun containsMessage(text: String, nick: String, casemapping: CaseMapping = CaseMapping.RFC1459): Boolean {
        if (nick.isEmpty() || text.isEmpty()) return false
        val foldedNick = casemapping.fold(nick)
        var start = -1
        for (i in text.indices) {
            val ch = text[i]
            if (ch in DELIMITERS) {
                if (start >= 0) {
                    if (casemapping.equal(text.substring(start, i), foldedNick)) return true
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
        }
        return start >= 0 && casemapping.equal(text.substring(start), foldedNick)
    }
}
