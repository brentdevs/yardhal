package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes

public object NamesParser {

    public fun stripPrefixes(nick: String, prefixes: ChannelPrefixModes): String {
        var result = nick
        while (result.isNotEmpty() && prefixes.symbols.contains(result.first())) {
            result = result.drop(1)
        }
        return result
    }

    public fun parseNamesLine(
        params: List<String>,
        prefixes: ChannelPrefixModes,
    ): Pair<String?, List<String>> {
        val payloadIndex = params.size - 1
        if (payloadIndex < 1) return null to emptyList()
        val channel = params.getOrNull(payloadIndex - 1)
        val members = params.last()
            .split(' ')
            .filter { it.isNotEmpty() }
            .map { stripPrefixes(it, prefixes) }
        return channel to members
    }
}
