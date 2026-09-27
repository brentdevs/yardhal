package dev.brentdevs.yardhal.core.protocol

public object IrcTags {

    public const val CLIENT_ONLY_PREFIX: Char = '+'

    private val ESCAPES = mapOf(
        '\\' to "\\\\",
        ';' to "\\:",
        ' ' to "\\s",
        '\r' to "\\r",
        '\n' to "\\n",
        '\u0000' to "\\0",
    )

    private val UNESCAPES = mapOf(
        '\\' to '\\',
        ':' to ';',
        's' to ' ',
        'r' to '\r',
        'n' to '\n',
        '0' to '\u0000',
    )

    public fun escape(value: String): String = buildString(value.length) {
        for (ch in value) append(ESCAPES[ch] ?: ch.toString())
    }

    public fun unescape(value: String): String {
        if ('\\' !in value) return value
        return buildString(value.length) {
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch != '\\' || i == value.length - 1) {
                    if (ch == '\\') break
                    append(ch)
                    i++
                } else {
                    append(UNESCAPES[value[i + 1]] ?: value[i + 1])
                    i += 2
                }
            }
        }
    }

    public fun isValidKey(key: String): Boolean {
        if (key.isEmpty()) return false
        val body = key.removePrefix(CLIENT_ONLY_PREFIX.toString())
        return body.isNotEmpty() && body.all { it.isLetterOrDigit() || it in "-./_" }
    }

    internal fun parseSection(section: String): LinkedHashMap<String, String?> {
        val tags = LinkedHashMap<String, String?>()
        if (section.isEmpty()) return tags
        for (entry in section.split(';')) {
            if (entry.isEmpty()) continue
            val eq = entry.indexOf('=')
            if (eq < 0) {
                if (isValidKey(entry)) tags.putIfAbsent(entry, null)
            } else {
                val key = entry.substring(0, eq)
                if (isValidKey(key)) tags.putIfAbsent(key, unescape(entry.substring(eq + 1)))
            }
        }
        return tags
    }

    internal fun serializeSection(tags: Map<String, String?>): String =
        tags.entries.joinToString(";") { entry ->
            val value = entry.value
            if (value == null) entry.key else "${entry.key}=${escape(value)}"
        }
}
