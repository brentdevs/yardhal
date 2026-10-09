package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks

public data class BouncerNetworkDraft(
    public val addr: String = "",
    public val name: String = "",
    public val nick: String = "",
    public val username: String = "",
    public val realname: String = "",
    public val password: String = "",
    public val enabled: Boolean = true,
    public val passwordChanged: Boolean = false,
) {
    public enum class Scheme(public val wirePrefix: String) {
        TLS("ircs"),
        PLAIN("irc+insecure"),
        UNIX("irc+unix"),
        ;

        public companion object {
            public fun fromPrefix(prefix: String): Scheme? =
                entries.firstOrNull { it.wirePrefix == prefix.lowercase() }
        }
    }

    public data class ParsedAddr(public val scheme: Scheme, public val host: String, public val port: Int?)

    public fun parseAddr(): ParsedAddr? {
        val trimmed = addr.trim()
        val schemeSeparator = trimmed.indexOf("://")
        if (schemeSeparator <= 0) return null
        val scheme = Scheme.fromPrefix(trimmed.substring(0, schemeSeparator)) ?: return null
        var remainder = trimmed.substring(schemeSeparator + 3)
        if (scheme == Scheme.UNIX) {
            if (remainder.isEmpty()) return null
            return ParsedAddr(scheme, remainder, null)
        }
        remainder = remainder.substringAfterLast('@')
        if (remainder.isEmpty() || remainder.any { it in "/@?#" || it.isWhitespace() }) return null
        return if (remainder.startsWith("[")) {
            val close = remainder.indexOf(']')
            if (close <= 1) return null
            val host = remainder.substring(1, close)
            val rest = remainder.substring(close + 1)
            if (rest.isEmpty()) ParsedAddr(scheme, host, null) else {
                if (!rest.startsWith(":")) return null
                val port = rest.drop(1).toIntOrNull() ?: return null
                ParsedAddr(scheme, host, port)
            }
        } else {
            val colon = remainder.indexOf(':')
            if (colon < 0) ParsedAddr(scheme, remainder, null) else {
                if (remainder.indexOf(':', colon + 1) >= 0) return null
                val port = remainder.substring(colon + 1).toIntOrNull() ?: return null
                ParsedAddr(scheme, remainder.substring(0, colon), port)
            }
        }
    }

    public fun addrValidationError(): String? {
        val trimmed = addr.trim()
        if (trimmed.isEmpty()) return "Address is required."
        val parsed = parseAddr() ?: return "Use ircs://host, irc+insecure://host, or irc+unix:///path."
        if (parsed.scheme != Scheme.UNIX && parsed.host.isEmpty()) return "Address is missing a host."
        if (parsed.port != null && parsed.port !in 1..65535) return "Port must be between 1 and 65535."
        if (trimmed.any { it == '\r' || it == '\n' || it == '\u0000' || it.isWhitespace() }) return "Address cannot contain whitespace."
        return null
    }

    public fun isValid(): Boolean = addrValidationError() == null

    public fun toAttributes(): IrcBouncerNetworks.Attributes {
        var attrs = IrcBouncerNetworks.Attributes()
        val trimmedName = name.trim()
        if (trimmedName.isNotEmpty()) attrs = attrs.copy(name = trimmedName)

        val parsed = parseAddr()
        if (parsed != null) {
            when (parsed.scheme) {
                Scheme.TLS -> {
                    attrs = attrs.copy(host = parsed.host, port = parsed.port ?: 6697, tls = true)
                }
                Scheme.PLAIN -> {
                    attrs = attrs.copy(host = parsed.host, port = parsed.port ?: 6667, tls = false)
                }
                Scheme.UNIX -> {
                    attrs = attrs.copy(host = "${Scheme.UNIX.wirePrefix}://${parsed.host}", unknown = mapOf("port" to "", "tls" to ""))
                }
            }
        }

        nick.trim().takeIf { it.isNotEmpty() }?.let { attrs = attrs.copy(nickname = it) }
        username.trim().takeIf { it.isNotEmpty() }?.let { attrs = attrs.copy(username = it) }
        realname.trim().takeIf { it.isNotEmpty() }?.let { attrs = attrs.copy(realname = it) }
        if (password.isNotEmpty()) attrs = attrs.copy(pass = password)
        return attrs
    }

    public fun attributesChangedAgainst(baseline: IrcBouncerNetworks.Attributes): IrcBouncerNetworks.Attributes {
        val full = toAttributes()
        val previous = fromAttributes(baseline).toAttributes()
        return IrcBouncerNetworks.Attributes(
            name = name.trim().takeIf { it != baseline.name.orEmpty() },
            host = full.host.takeIf { it != previous.host },
            port = full.port.takeIf { it != previous.port },
            tls = full.tls.takeIf { it != previous.tls },
            nickname = nick.trim().takeIf { it != baseline.nickname.orEmpty() },
            username = username.trim().takeIf { it != baseline.username.orEmpty() },
            realname = realname.trim().takeIf { it != baseline.realname.orEmpty() },
            pass = password.takeIf { passwordChanged || it.isNotEmpty() },
            unknown = if (full.host != previous.host) full.unknown else emptyMap(),
        )
    }

    override fun toString(): String =
        "BouncerNetworkDraft(addr=${addressWithoutUserInfo()}, name=$name, nick=$nick, username=$username, realname=$realname, password=<redacted>, enabled=$enabled, passwordChanged=$passwordChanged)"

    public fun addressWithoutUserInfo(): String {
        val schemeSeparator = addr.indexOf("://")
        if (schemeSeparator < 0 || Scheme.fromPrefix(addr.substring(0, schemeSeparator)) == Scheme.UNIX || '@' !in addr) return addr
        return addr.substring(0, schemeSeparator + 3) + addr.substringAfterLast('@')
    }

    public companion object {
        public fun fromAttributes(attrs: IrcBouncerNetworks.Attributes): BouncerNetworkDraft {
            val addr = buildString {
                val host = attrs.host.orEmpty()
                when {
                    host.startsWith("irc+unix://") -> append(host)
                    host.startsWith("/") -> {
                        append("irc+unix://")
                        append(host)
                    }
                    attrs.unknown["addr"]?.startsWith("irc+unix://") == true -> append(attrs.unknown.getValue("addr"))
                    host.isEmpty() -> Unit
                    else -> {
                        append(if (attrs.tls ?: true) "ircs://" else "irc+insecure://")
                        append(if (':' in host) "[$host]" else host)
                        attrs.port?.let { append(":$it") }
                    }
                }
            }
            return BouncerNetworkDraft(
                addr = addr,
                name = attrs.name.orEmpty(),
                nick = attrs.nickname.orEmpty(),
                username = attrs.username.orEmpty(),
                realname = attrs.realname.orEmpty(),
                password = "",
                enabled = (attrs.unknown["enabled"] ?: "1") != "0",
            )
        }
    }
}

