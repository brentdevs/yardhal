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
        val atSign = remainder.lastIndexOf('@')
        if (atSign >= 0) remainder = remainder.substring(atSign + 1)
        if (remainder.isEmpty()) return null
        return if (remainder.startsWith("[")) {
            val close = remainder.indexOf(']')
            if (close < 0) {
                ParsedAddr(scheme, remainder.removePrefix("["), null)
            } else {
                val host = remainder.substring(1, close)
                val rest = remainder.substring(close + 1)
                val port = rest.takeIf { it.startsWith(":") }?.drop(1)?.toIntOrNull()
                ParsedAddr(scheme, host, port)
            }
        } else {
            val colon = remainder.indexOf(':')
            if (colon < 0) {
                ParsedAddr(scheme, remainder, null)
            } else {
                ParsedAddr(scheme, remainder.substring(0, colon), remainder.substring(colon + 1).toIntOrNull())
            }
        }
    }

    public fun addrValidationError(): String? {
        val trimmed = addr.trim()
        if (trimmed.isEmpty()) return "Address is required."
        if (parseAddr() == null) return "Use ircs://host, irc+insecure://host, or irc+unix:///path."
        val parsed = parseAddr()!!
        if (parsed.scheme != Scheme.UNIX && parsed.host.isEmpty()) return "Address is missing a host."
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
                    attrs = attrs.copy(host = parsed.host)
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
        var diff = IrcBouncerNetworks.Attributes()
        full.name.takeIf { it != baseline.name }?.let { diff = diff.copy(name = it) }
        full.host.takeIf { it != baseline.host }?.let { diff = diff.copy(host = it) }
        full.port.takeIf { it != baseline.port }?.let { diff = diff.copy(port = it) }
        full.tls.takeIf { it != baseline.tls }?.let { diff = diff.copy(tls = it) }
        full.nickname.takeIf { it != baseline.nickname }?.let { diff = diff.copy(nickname = it) }
        full.username.takeIf { it != baseline.username }?.let { diff = diff.copy(username = it) }
        full.realname.takeIf { it != baseline.realname }?.let { diff = diff.copy(realname = it) }
        if (password.isNotEmpty()) diff = diff.copy(pass = password)
        return diff
    }

    public companion object {
        public fun fromAttributes(attrs: IrcBouncerNetworks.Attributes): BouncerNetworkDraft {
            val addr = buildString {
                val host = attrs.host.orEmpty()
                when {
                    host.startsWith("/") -> {
                        append("irc+unix://")
                        append(host)
                    }
                    else -> {
                        append(if (attrs.tls ?: true) "ircs://" else "irc+insecure://")
                        append(host)
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

