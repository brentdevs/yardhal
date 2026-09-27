package dev.brentdevs.yardhal.core.protocol

public data class CtcpMessage(
    public val command: String,
    public val arguments: String,
) {
    public fun toEncodedPayload(): String = IrcCtcp.encode(command, arguments)
}

public sealed interface PrivmsgContent {
    public data class Plain(public val text: String) : PrivmsgContent
    public data class Ctcp(public val message: CtcpMessage) : PrivmsgContent
}

public object IrcCtcp {

    private const val DELIMITER = '\u0001'
    private const val QUOTE = '\u0010'

    public const val ACTION: String = "ACTION"
    public const val VERSION: String = "VERSION"
    public const val PING: String = "PING"
    public const val CLIENTINFO: String = "CLIENTINFO"
    public const val ERRMSG: String = "ERRMSG"

    public fun encode(command: String, arguments: String): String {
        val quoted = quote(arguments.replace(DELIMITER.toString(), ""))
        val safeCommand = command.replace(DELIMITER.toString(), "")
        return if (quoted.isEmpty()) "$DELIMITER$safeCommand$DELIMITER" else "$DELIMITER$safeCommand $quoted$DELIMITER"
    }

    public fun decode(payload: String): List<PrivmsgContent> {
        val contents = ArrayList<PrivmsgContent>(2)
        var plain = StringBuilder()
        var i = 0
        while (i < payload.length) {
            val ch = payload[i]
            if (ch != DELIMITER) {
                plain.append(ch)
                i++
                continue
            }
            val close = payload.indexOf(DELIMITER, i + 1)
            val body =
                if (close < 0) {
                    payload.substring(i + 1)
                } else {
                    payload.substring(i + 1, close)
                }
            if (plain.isNotEmpty()) {
                contents.add(PrivmsgContent.Plain(plain.toString()))
                plain = StringBuilder()
            }
            parseBody(body)?.let { contents.add(PrivmsgContent.Ctcp(it)) }
            i = if (close < 0) payload.length else close + 1
        }
        if (plain.isNotEmpty()) contents.add(PrivmsgContent.Plain(plain.toString()))
        return contents
    }

    public fun parseBody(body: String): CtcpMessage? {
        val unquoted = unquote(body)
        if (unquoted.isEmpty()) return null
        val space = unquoted.indexOf(' ')
        return if (space < 0) {
            CtcpMessage(unquoted.uppercase(), "")
        } else {
            val command = unquoted.substring(0, space)
            if (command.isEmpty()) null else CtcpMessage(command.uppercase(), unquoted.substring(space + 1))
        }
    }

    public fun quote(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                QUOTE -> append(QUOTE).append(QUOTE)
                '\n' -> append(QUOTE).append('n')
                '\r' -> append(QUOTE).append('r')
                '\u0000' -> append(QUOTE).append('\u0000')
                else -> append(ch)
            }
        }
    }

    public fun unquote(value: String): String {
        if (QUOTE !in value) return value
        return buildString(value.length) {
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch != QUOTE || i == value.length - 1) {
                    if (ch == QUOTE) break
                    append(ch)
                    i++
                } else {
                    val next = value[i + 1]
                    append(
                        when (next) {
                            QUOTE -> QUOTE
                            'n' -> '\n'
                            'r' -> '\r'
                            else -> next
                        },
                    )
                    i += 2
                }
            }
        }
    }
}
