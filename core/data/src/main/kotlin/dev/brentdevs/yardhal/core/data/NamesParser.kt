package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes
import kotlinx.serialization.Serializable

@Serializable
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

public data class NamesEntry(
    public val member: ChannelMember,
    public val user: String? = null,
    public val host: String? = null,
)

public object NamesParser {

    public fun parseEntry(token: String, prefixes: ChannelPrefixModes): NamesEntry {
        var symbol: Char? = null
        var source = token
        while (source.isNotEmpty() && prefixes.symbols.contains(source.first())) {
            if (symbol == null) symbol = source.first()
            source = source.drop(1)
        }
        val nick = source.substringBefore('!').substringBefore('@')
        val user = source.substringAfter('!', "").substringBefore('@').takeIf { it.isNotEmpty() }
        val host = source.substringAfter('@', "").takeIf { it.isNotEmpty() }
        return NamesEntry(ChannelMember(nick = nick, symbol = symbol), user, host)
    }

    public fun parseMember(token: String, prefixes: ChannelPrefixModes): ChannelMember =
        parseEntry(token, prefixes).member

    public fun parseNamesLine(
        params: List<String>,
        prefixes: ChannelPrefixModes,
    ): Pair<String?, List<NamesEntry>> {
        val payloadIndex = params.size - 1
        if (payloadIndex < 1) return null to emptyList()
        val channel = params.getOrNull(payloadIndex - 1)
        val entries = params.last()
            .split(' ')
            .filter { it.isNotEmpty() }
            .map { parseEntry(it, prefixes) }
        return channel to entries
    }
}
