package dev.brentdevs.yardhal.core.data

public object FtsQuery {

    public fun build(raw: String): String? {
        val tokens = raw
            .split(' ', '\t', '\n')
            .map { token -> token.filter { it.isLetterOrDigit() || it == '\'' } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { token ->
            "\"" + token.replace("\"", "") + "\""
        }
    }
}
