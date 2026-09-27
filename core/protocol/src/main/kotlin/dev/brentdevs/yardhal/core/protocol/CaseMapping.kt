package dev.brentdevs.yardhal.core.protocol

public enum class CaseMapping(public val wireName: String) {
    RFC1459("rfc1459"),
    STRICT_RFC1459("strict-rfc1459"),
    ASCII("ascii"),
    ;

    public fun fold(input: String): String = buildString(input.length) {
        for (ch in input) append(foldChar(ch))
    }

    public fun equal(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        for (i in a.indices) {
            if (foldChar(a[i]) != foldChar(b[i])) return false
        }
        return true
    }

    private fun foldChar(ch: Char): Char = when (this) {
        ASCII -> if (ch in 'A'..'Z') ch + LOWERCASE_OFFSET else ch
        STRICT_RFC1459 -> when (ch) {
            in 'A'..'Z' -> ch + LOWERCASE_OFFSET
            '[' -> '{'
            ']' -> '}'
            '\\' -> '|'
            else -> ch
        }
        RFC1459 -> when (ch) {
            in 'A'..'Z' -> ch + LOWERCASE_OFFSET
            '[' -> '{'
            ']' -> '}'
            '\\' -> '|'
            '^' -> '~'
            else -> ch
        }
    }

    public companion object {
        public const val DEFAULT_WIRE_NAME: String = "rfc1459"

        public fun fromWireName(name: String?): CaseMapping? {
            val folded = name?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == folded }
        }

        private const val LOWERCASE_OFFSET = 32
    }
}
