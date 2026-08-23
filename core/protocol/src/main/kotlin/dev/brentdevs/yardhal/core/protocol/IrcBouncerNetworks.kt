package dev.brentdevs.yardhal.core.protocol

public object IrcBouncerNetworks {

    public const val CAPABILITY: String = "soju.im/bouncer-networks"
    public const val NOTIFY_CAPABILITY: String = "soju.im/bouncer-networks-notify"
    public const val BATCH_TYPE: String = "soju.im/bouncer-networks"
    public const val ISUPPORT_NET_ID_TOKEN: String = "BOUNCER_NETID"

    public enum class State(public val wireName: String) {
        CONNECTED("connected"),
        CONNECTING("connecting"),
        DISCONNECTED("disconnected"),
        ;

        public companion object {
            public fun fromWire(value: String): State? =
                entries.firstOrNull { it.wireName == value.lowercase() }
        }
    }

    public data class Attributes(
        public val name: String? = null,
        public val state: State? = null,
        public val host: String? = null,
        public val port: Int? = null,
        public val tls: Boolean? = null,
        public val nickname: String? = null,
        public val username: String? = null,
        public val realname: String? = null,
        public val pass: String? = null,
        public val error: String? = null,
        public val unknown: Map<String, String> = emptyMap(),
    ) {
        public fun merged(delta: Attributes): Attributes = Attributes(
            name = delta.name ?: name,
            state = delta.state ?: state,
            host = delta.host ?: host,
            port = delta.port ?: port,
            tls = delta.tls ?: tls,
            nickname = delta.nickname ?: nickname,
            username = delta.username ?: username,
            realname = delta.realname ?: realname,
            pass = delta.pass ?: pass,
            error = delta.error ?: error,
            unknown = unknown + delta.unknown,
        )

        public fun attributeString(): String {
            val pairs = ArrayList<Pair<String, String>>(8)
            name?.let { pairs.add("name" to it) }
            host?.let { pairs.add("host" to it) }
            port?.let { pairs.add("port" to it.toString()) }
            tls?.let { pairs.add("tls" to if (it) "1" else "0") }
            nickname?.let { pairs.add("nickname" to it) }
            username?.let { pairs.add("username" to it) }
            realname?.let { pairs.add("realname" to it) }
            pass?.let { pairs.add("pass" to it) }
            error?.let { pairs.add("error" to it) }
            unknown.forEach { (key, value) -> pairs.add(key to value) }
            return pairs
                .sortedBy { it.first }
                .joinToString(";") { "${it.first}=${escapeValue(it.second)}" }
        }
    }

    public sealed interface Change {
        public data class Upsert(public val attributes: Attributes) : Change
        public data object Deleted : Change
    }

    public data class NetworkUpdate(public val netId: String, public val change: Change)

    public fun parseNetwork(parameters: List<String>): NetworkUpdate? {
        if (parameters.size < 2) return null
        if (!parameters[0].equals("NETWORK", ignoreCase = true)) return null
        val netId = parameters[1]
        if (netId.isEmpty()) return null
        val attributeField = if (parameters.size >= 3) parameters[2] else "*"
        if (attributeField == "*") return NetworkUpdate(netId, Change.Deleted)
        return NetworkUpdate(netId, Change.Upsert(parseAttributes(attributeField)))
    }

    public fun parseAddNetworkReply(parameters: List<String>): String? {
        if (parameters.size < 2) return null
        if (!parameters[0].equals("ADDNETWORK", ignoreCase = true)) return null
        val netId = parameters[1]
        return netId.ifEmpty { null }
    }

    public fun addNetworkCommand(attributes: Attributes): String =
        "BOUNCER ADDNETWORK ${attributes.attributeString()}"

    public fun delNetworkCommand(netId: String): String =
        "BOUNCER DELNETWORK $netId"

    public fun parseAttributes(source: String): Attributes {
        var attrs = Attributes()
        for (token in splitAttributeTokens(source)) {
            if (token.isEmpty()) continue
            val separator = token.indexOf('=')
            val key: String
            val rawValue: String
            if (separator < 0) {
                key = token
                rawValue = ""
            } else {
                key = token.substring(0, separator)
                rawValue = token.substring(separator + 1)
            }
            if (key.isEmpty()) continue
            val value = unescapeValue(rawValue)
            attrs = when (key.lowercase()) {
                "name" -> attrs.copy(name = value)
                "state" -> attrs.copy(state = State.fromWire(value))
                "host" -> attrs.copy(host = value)
                "port" -> attrs.copy(port = value.toIntOrNull())
                "tls" -> attrs.copy(tls = value == "1")
                "nickname" -> attrs.copy(nickname = value)
                "username" -> attrs.copy(username = value)
                "realname" -> attrs.copy(realname = value)
                "pass" -> attrs.copy(pass = value)
                "error" -> attrs.copy(error = value)
                else -> attrs.copy(unknown = attrs.unknown + (key to value))
            }
        }
        return attrs
    }

    public fun splitAttributeTokens(source: String): List<String> {
        if (source.isEmpty()) return emptyList()
        val tokens = ArrayList<String>(4)
        val current = StringBuilder()
        var index = 0
        while (index < source.length) {
            val ch = source[index]
            when {
                ch == '\\' && index < source.length - 1 -> {
                    current.append(ch).append(source[index + 1])
                    index += 2
                }
                ch == ';' -> {
                    tokens.add(current.toString())
                    current.setLength(0)
                    index++
                }
                else -> {
                    current.append(ch)
                    index++
                }
            }
        }
        tokens.add(current.toString())
        return tokens
    }

    public fun escapeValue(value: String): String =
        buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    ';' -> append("\\;")
                    else -> append(ch)
                }
            }
        }

    public fun unescapeValue(value: String): String {
        if ('\\' !in value) return value
        return buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val ch = value[index]
                if (ch != '\\' || index == value.length - 1) {
                    if (ch == '\\') break
                    append(ch)
                    index++
                } else {
                    append(value[index + 1])
                    index += 2
                }
            }
        }
    }
}
