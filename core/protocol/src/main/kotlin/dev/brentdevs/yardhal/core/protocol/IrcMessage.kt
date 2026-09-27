package dev.brentdevs.yardhal.core.protocol

public data class IrcMessage(
    public val tags: Map<String, String?> = emptyMap(),
    public val prefix: IrcPrefix? = null,
    public val command: String,
    public val parameters: List<String> = emptyList(),
) {
    public val numeric: Int?
        get() = command.takeIf { it.length == NUMERIC_LENGTH && it.all(Char::isDigit) }?.toInt()

    public fun tag(name: String): String? = tags[name]

    override fun toString(): String = toWire()

    public fun toWire(): String = buildString {
        if (tags.isNotEmpty()) {
            append('@')
            append(IrcTags.serializeSection(tags))
            append(' ')
        }
        prefix?.let {
            append(':')
            append(it)
            append(' ')
        }
        append(command)
        parameters.forEachIndexed { index, param ->
            append(' ')
            val isLast = index == parameters.size - 1
            if (isLast && (param.isEmpty() || param.startsWith(":") || ' ' in param)) {
                append(':')
                append(param)
            } else {
                require(!param.contains(' ') && !param.startsWith(":")) {
                    "middle parameter $index contains space or leading colon: $param"
                }
                append(param)
            }
        }
    }

    public fun wireByteLength(): Int = toWire().toByteArray(Charsets.UTF_8).size

    public companion object {

        public const val CLASSIC_LIMIT_BYTES: Int = 510
        public const val TAGGED_LIMIT_BYTES: Int = 8191
        private const val NUMERIC_LENGTH = 3

        public fun parse(line: String): IrcMessage? {
            var rest = line.trimEnd('\r', '\n')
            if (rest.isEmpty()) return null

            var tags: LinkedHashMap<String, String?> = LinkedHashMap()
            if (rest.startsWith("@")) {
                val separator = rest.indexOf(' ')
                if (separator < 0) return null
                tags = IrcTags.parseSection(rest.substring(1, separator))
                rest = rest.substring(separator + 1).trimStart(' ')
                if (rest.isEmpty()) return null
            }

            var prefix: IrcPrefix? = null
            if (rest.startsWith(":")) {
                val separator = rest.indexOf(' ')
                if (separator < 0) return null
                prefix = IrcPrefix.parse(rest.substring(1, separator)) ?: return null
                rest = rest.substring(separator + 1).trimStart(' ')
                if (rest.isEmpty()) return null
            }

            val commandBreak = rest.indexOf(' ')
            val command = if (commandBreak < 0) rest else rest.substring(0, commandBreak)
            if (!isValidCommand(command)) return null

            val parameters =
                if (commandBreak < 0) emptyList() else parseParameters(rest.substring(commandBreak + 1))

            return IrcMessage(
                tags = if (tags.isEmpty()) emptyMap() else tags.toMap(),
                prefix = prefix,
                command = command,
                parameters = parameters,
            )
        }

        public fun isValidCommand(token: String): Boolean =
            token.isNotEmpty() && (
                token.all { it in 'a'..'z' || it in 'A'..'Z' } ||
                    (token.length == NUMERIC_LENGTH && token.all(Char::isDigit))
                )

        private fun parseParameters(source: String): List<String> {
            val params = ArrayList<String>(4)
            var i = 0
            while (i <= source.length) {
                while (i < source.length && source[i] == ' ') i++
                if (i >= source.length) break
                if (source[i] == ':') {
                    params.add(source.substring(i + 1))
                    break
                }
                val next = source.indexOf(' ', i)
                if (next < 0) {
                    params.add(source.substring(i))
                    break
                }
                params.add(source.substring(i, next))
                i = next
            }
            return params
        }
    }
}
