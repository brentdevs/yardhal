package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes

public data class ChannelMember(
    public val nick: String,
    public val symbol: Char? = null,
) {
    public val isOperator: Boolean
        get() = symbol == '@' || symbol == '&' || symbol == '~'

    public val isVoice: Boolean
        get() = symbol == '+'

    public val looksLikeBot: Boolean
        get() = nick.endsWith("bot", ignoreCase = true) || nick.endsWith("irobot", ignoreCase = true)
}

public object NamesParser {

    public fun parseMember(token: String, prefixes: ChannelPrefixModes): ChannelMember {
        var symbol: Char? = null
        var nick = token
        while (nick.isNotEmpty() && prefixes.symbols.contains(nick.first())) {
            symbol = nick.first()
            nick = nick.drop(1)
        }
        return ChannelMember(nick = nick, symbol = symbol)
    }

    public fun parseNamesLine(
        params: List<String>,
        prefixes: ChannelPrefixModes,
    ): Pair<String?, List<ChannelMember>> {
        val payloadIndex = params.size - 1
        if (payloadIndex < 1) return null to emptyList()
        val channel = params.getOrNull(payloadIndex - 1)
        val members = params.last()
            .split(' ')
            .filter { it.isNotEmpty() }
            .map { parseMember(it, prefixes) }
        return channel to members
    }
}
