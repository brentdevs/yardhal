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
        return "network update ${posixQuote(name)} -enabled ${if (enabled) "true" else "false"}"
    }

    public fun channelUpdate(
        name: String,
        detachAfterSeconds: Long? = null,
        relayDetached: RelayMode? = null,
        reattachOn: RelayMode? = null,
        detached: Boolean? = null,
    ): String {
        val parts = mutableListOf("channel", "update", posixQuote(name))
        detached?.let { parts.add("-detached"); parts.add(it.toString()) }
        detachAfterSeconds?.let { parts.add("-detach-after"); parts.add(formatDuration(it)) }
        relayDetached?.let { parts.add("-relay-detached"); parts.add(it.wireName) }
        reattachOn?.let { parts.add("-reattach-on"); parts.add(it.wireName) }
        return parts.joinToString(" ")
    }

    public fun channelStatus(network: String? = null): String =
        if (network == null) "channel status" else "channel status -network ${posixQuote(network)}"

    public fun posixQuote(value: String): String {
        require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid service argument." }
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
        require(secondsTotal >= 0) { "Detach duration cannot be negative." }
        if (secondsTotal == 0L) return "0"
        for ((unit, suffix) in listOf(3600L to "h", 60L to "m", 1L to "s")) {
            if (secondsTotal % unit == 0L) return "${secondsTotal / unit}$suffix"
        }
        return "${secondsTotal}s"
    }
}
