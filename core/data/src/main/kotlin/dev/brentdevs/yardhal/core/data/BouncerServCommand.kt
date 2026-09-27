package dev.brentdevs.yardhal.core.data

public object BouncerServCommand {

    public enum class RelayMode(public val wireName: String) {
        MESSAGE("message"),
        HIGHLIGHT("highlight"),
        NONE("none"),
        DEFAULT("default"),
        ;

        public companion object {
            public val ALL: List<RelayMode> = entries.toList()
        }
    }

    public fun networkUpdate(name: String, enabled: Boolean): String {
        val flag = if (enabled) "-enabled" else "-disabled"
        return "network update ${posixQuote(name)} $flag"
    }

    public fun channelUpdate(
        name: String,
        detachAfterSeconds: Long? = null,
        relayDetached: RelayMode? = null,
        reattachOn: RelayMode? = null,
    ): String {
        val parts = mutableListOf("channel", "update", posixQuote(name))
        detachAfterSeconds?.let { parts.add("-detach-after"); parts.add(formatDuration(it)) }
        relayDetached?.let { parts.add("-relay-detached"); parts.add(it.wireName) }
        reattachOn?.let { parts.add("-reattach-on"); parts.add(it.wireName) }
        return parts.joinToString(" ")
    }

    public fun channelStatus(name: String? = null): String =
        if (name == null) "channel status" else "channel status ${posixQuote(name)}"

    public fun posixQuote(value: String): String {
        if (value.isEmpty()) return "''"
        var needsQuoting = false
        for (ch in value) {
            val safe = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                ch == '_' || ch == '-' || ch == '+' || ch == '.' || ch == '/' ||
                ch == ':' || ch == ',' || ch == '@' || ch == '%'
            if (!safe) {
                needsQuoting = true
                break
            }
        }
        if (!needsQuoting) return value
        return "'" + value.replace("'", "'\\''") + "'"
    }

    public fun formatDuration(secondsTotal: Long): String {
        if (secondsTotal == 0L) return "0"
        val absolute = if (secondsTotal < 0) -secondsTotal else secondsTotal
        val units = listOf(86400L to "d", 3600L to "h", 60L to "m", 1L to "s")
        for ((unit, suffix) in units) {
            if (absolute % unit == 0L) {
                val n = absolute / unit
                val sign = if (secondsTotal < 0) "-" else ""
                return "$sign$n$suffix"
            }
        }
        return "${secondsTotal}s"
    }
}
