package dev.brentdevs.yardhal.core.protocol

public data class ChannelPrefixModes(
    public val modes: List<Char>,
    public val symbols: List<Char>,
) {
    public fun symbolFor(mode: Char): Char? = symbols.getOrNull(modes.indexOf(mode))

    public fun modeFor(symbol: Char): Char? = modes.getOrNull(symbols.indexOf(symbol))

    public companion object {
        public val DEFAULT: ChannelPrefixModes = ChannelPrefixModes(listOf('o', 'v'), listOf('@', '+'))
    }
}

public data class ChannelModeLists(
    public val listA: String,
    public val listB: String,
    public val alwaysWithParamC: String,
    public val neverWithParamD: String,
) {
    public companion object {
        public const val DEFAULT_A: String = "b"
        public const val DEFAULT_B: String = "k"
        public const val DEFAULT_C: String = "l"
        public const val DEFAULT_D: String = "imnpst"
    }
}

public class ISupport private constructor(
    private val tokens: Map<String, String?>,
) {
    public val rawTokens: Map<String, String?>
        get() = tokens.toMap()

    public val network: String?
        get() = tokens["NETWORK"]

    public val casemapping: CaseMapping
        get() = CaseMapping.fromWireName(tokens["CASEMAPPING"]) ?: CaseMapping.entries.first()

    public val channelTypes: String
        get() = tokens["CHANTYPES"] ?: DEFAULT_CHANTYPES

    public val prefix: ChannelPrefixModes
        get() {
            val raw = tokens["PREFIX"] ?: return ChannelPrefixModes.DEFAULT
            if (!raw.startsWith('(')) return ChannelPrefixModes.DEFAULT
            val close = raw.indexOf(')')
            if (close < 0) return ChannelPrefixModes.DEFAULT
            val modes = raw.substring(1, close)
            val symbols = raw.substring(close + 1)
            if (modes.length != symbols.length) return ChannelPrefixModes.DEFAULT
            return ChannelPrefixModes(modes.toList(), symbols.toList())
        }

    public val chanmodes: ChannelModeLists
        get() {
            val raw = tokens["CHANMODES"]
                ?.split(',')
                ?.takeIf { it.size == 4 }
            return ChannelModeLists(
                listA = raw?.get(0) ?: ChannelModeLists.DEFAULT_A,
                listB = raw?.get(1) ?: ChannelModeLists.DEFAULT_B,
                alwaysWithParamC = raw?.get(2) ?: ChannelModeLists.DEFAULT_C,
                neverWithParamD = raw?.get(3) ?: ChannelModeLists.DEFAULT_D,
            )
        }

    public val modesPerLine: Int?
        get() = tokens["MODES"]?.toIntOrNull()?.takeIf { it > 0 }

    public val maxTargets: Int?
        get() = tokenLimit("MAXTARGETS")

    public val nickLengthLimit: Int?
        get() = tokenLimit("NICKLEN")

    public val topicLengthLimit: Int?
        get() = tokenLimit("TOPICLEN")

    public val channelLengthLimit: Int?
        get() = tokenLimit("CHANNELLEN")

    public val monitorLimit: Int?
        get() = tokenLimit("MONITOR")

    public val utf8Only: Boolean
        get() = tokens.containsKey("UTF8ONLY")

    public val whox: Boolean
        get() = tokens.containsKey("WHOX")

    public val botModeLetter: Char?
        get() = tokens["BOT"]?.firstOrNull { it.isLetter() }

    public val extendedListFlags: String?
        get() = tokens["ELIST"]

    public fun targMax(command: String): Int? =
        tokens["TARGMAX"]?.split(',')?.firstNotNullOfOrNull { entry ->
            val separator = entry.indexOf(':')
            if (separator > 0 && entry.substring(0, separator) == command) {
                entry.substring(separator + 1).toIntOrNull()
            } else {
                null
            }
        }?.takeIf { it > 0 }

    public fun supports(token: String): Boolean = tokens.containsKey(token)

    public operator fun get(key: String): String? = tokens[key]

    public fun mergedWith(later: ISupport): ISupport {
        val combined = LinkedHashMap(tokens)
        combined.putAll(later.tokens)
        return ISupport(combined)
    }

    override fun toString(): String =
        "ISupport(network=$network casemapping=${casemapping.wireName} prefix=${prefix.modes} chanmodes=${chanmodes.listA},${chanmodes.listB},${chanmodes.alwaysWithParamC},${chanmodes.neverWithParamD})"

    private fun tokenLimit(key: String): Int? =
        tokens[key]?.toIntOrNull()?.takeIf { it > 0 }

    public companion object {

        public fun parse(tokens: Iterable<String>): ISupport {
            val parsed = LinkedHashMap<String, String?>()
            for (token in tokens) {
                if (token.isEmpty()) continue
                val separator = token.indexOf('=')
                if (separator < 0) {
                    parsed[token] = null
                } else {
                    parsed[token.substring(0, separator)] = token.substring(separator + 1)
                }
            }
            return ISupport(parsed)
        }

        public val EMPTY: ISupport = parse(emptyList())

        private const val DEFAULT_CHANTYPES = "#&"
    }
}
