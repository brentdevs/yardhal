package dev.brentdevs.yardhal.core.protocol

public data class IrcPrefix(
    public val nick: String,
    public val user: String? = null,
    public val host: String? = null,
) {
    public val isServer: Boolean
        get() = user == null && host == null

    override fun toString(): String = buildString {
        append(nick)
        if (user != null) append('!').append(user)
        if (host != null) append('@').append(host)
    }

    public companion object {
        public fun parse(raw: String): IrcPrefix? {
            if (raw.isEmpty() || ' ' in raw) return null
            val bang = raw.indexOf('!')
            val at = raw.indexOf('@')
            return when {
                bang >= 0 && at > bang -> complete(raw.substring(0, bang), raw.substring(bang + 1, at), raw.substring(at + 1))
                bang >= 0 -> {
                    val nick = raw.substring(0, bang)
                    val user = raw.substring(bang + 1)
                    if (nick.isEmpty() || user.isEmpty()) null else IrcPrefix(nick, user, null)
                }
                at >= 0 -> {
                    val nick = raw.substring(0, at)
                    val host = raw.substring(at + 1)
                    if (nick.isEmpty() || host.isEmpty()) null else IrcPrefix(nick, null, host)
                }
                else -> IrcPrefix(raw)
            }
        }

        private fun complete(nick: String, user: String, host: String): IrcPrefix? =
            if (nick.isEmpty() || user.isEmpty() || host.isEmpty()) null else IrcPrefix(nick, user, host)
    }
}
