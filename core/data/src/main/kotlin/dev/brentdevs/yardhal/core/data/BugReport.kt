package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
public data class BugReportBuild(
    public val version: String,
    public val versionCode: Long,
    public val androidApi: Int,
    public val manufacturer: String,
    public val model: String,
)

@Serializable
public data class BugReportConnection(
    public val alias: String,
    public val status: String,
    public val tlsRequested: Boolean?,
    public val proxyConfigured: Boolean?,
    public val openConversations: Int,
    public val joinedChannels: Int,
    public val loadedMessages: Int,
)

@Serializable
public data class BugReportActivity(
    public val networkAlias: String,
    public val outbound: Boolean,
    public val command: String,
    public val parameterCount: Int,
)

@Serializable
public data class BugReport(
    public val schemaVersion: Int = 1,
    public val generatedAtMs: Long,
    public val description: String,
    public val build: BugReportBuild,
    public val connections: List<BugReportConnection>,
    public val activity: List<BugReportActivity>,
) {
    public fun readable(): String = buildString {
        appendLine("Yardhal bug report · schema $schemaVersion")
        appendLine("Generated: ${Instant.ofEpochMilli(generatedAtMs)}")
        appendLine()
        appendLine("Description")
        appendLine(description.ifBlank { "No description supplied" })
        appendLine()
        appendLine("Build and device")
        appendLine("Yardhal ${build.version} (${build.versionCode})")
        appendLine("Android API ${build.androidApi} · ${build.manufacturer} ${build.model}")
        appendLine()
        appendLine("Connection snapshot (up to 32 represented networks; last attempted transport)")
        if (connections.isEmpty()) appendLine("No networks represented in this snapshot")
        for (connection in connections) {
            appendLine("${connection.alias}: ${connection.status}; TLS requested=${connection.tlsRequested ?: "unknown"}; proxy configured=${connection.proxyConfigured ?: "unknown"}")
            appendLine("  Open conversations=${connection.openConversations}; joined channels=${connection.joinedChannels}; loaded messages=${connection.loadedMessages}")
        }
        appendLine()
        appendLine("Recent protocol activity")
        appendLine("Per-network order; at most 20 recent commands per network and 100 total. Not a complete traffic trace.")
        if (activity.isEmpty()) appendLine("No recent protocol commands captured")
        for (event in activity) {
            appendLine("${event.networkAlias} ${if (event.outbound) "out" else "in"} ${event.command} · ${event.parameterCount} parameters")
        }
        appendLine()
        append("Privacy: automatic context excludes network names/hosts, nicknames, conversation names, message bodies, tags, authentication arguments, URLs and vault contents. Description redaction is best-effort and may retain personal context; review it before sharing. Existing traffic inspection remains separate.")
    }
}

public object BugReportRedaction {
    public const val MAX_DESCRIPTION_CHARS: Int = 8192
    public const val MAX_ACTIVITY: Int = 100
    private const val REDACTED = "<redacted>"
    private val urls = Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>]+")
    private val assignments = Regex("(?i)\\b(password|passwd|pass|token|secret|api[_-]?key|authorization|credential)\\s*(?:[:=]|is\\b)?\\s+(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)|\\b(password|passwd|pass|token|secret|api[_-]?key|authorization|credential)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)")
    private val authentication = Regex("(?im)^\\s*(?:@\\S+\\s+)?(?::\\S+\\s+)?(?:PASS|AUTHENTICATE|OPER|IDENTIFY|REGISTER|NS\\s+(?:IDENTIFY|REGISTER)|NICKSERV\\s+(?:IDENTIFY|REGISTER)|(?:PRIVMSG|NOTICE)\\s+(?:NICKSERV(?:@\\S+)?|BOUNCERSERV|\\*\\S+)\\s+:?)\\b[^\\r\\n]*")
    private val authorization = Regex("(?i)\\b(?:Basic|Bearer)\\s+[A-Za-z0-9._~+/=-]+")
    private val commands = setOf(
        "AUTHENTICATE", "AWAY", "BATCH", "BOUNCER", "CAP", "CHATHISTORY", "FAIL", "INVITE", "JOIN",
        "KICK", "KILL", "LIST", "MARKREAD", "METADATA", "MODE", "MONITOR", "NICK", "NOTE", "NOTICE",
        "OPER", "PART", "PASS", "PING", "PONG", "PRIVMSG", "QUIT", "REDACT", "REGISTER", "RENAME",
        "SETNAME", "TAGMSG", "TOPIC", "USER", "WARN", "WHO", "WHOIS", "WHOWAS", "ERROR", "ACK",
    )

    public fun description(text: String, redactKnownSecrets: (String) -> String = { it }): String {
        var result = redactKnownSecrets(text)
        result = authentication.replace(result, REDACTED)
        result = authorization.replace(result, REDACTED)
        result = assignments.replace(result) { match ->
            match.groups[1]?.value.orEmpty().ifEmpty { match.groups[2]?.value.orEmpty() } + "=$REDACTED"
        }
        result = urls.replace(result, "<URL omitted>")
        result = result.filter { it == '\n' || it == '\t' || !it.isISOControl() }
        return bounded(result, MAX_DESCRIPTION_CHARS)
    }

    public fun activity(networkAlias: String, outbound: Boolean, wire: String): BugReportActivity {
        val parsed = IrcMessage.parse(wire)
        val command = parsed?.command?.uppercase(Locale.ROOT).orEmpty()
        val safeCommand = when {
            command.length == 3 && command.all { it in '0'..'9' } -> command
            command in commands -> command
            else -> "UNRECOGNIZED_COMMAND"
        }
        return BugReportActivity(networkAlias, outbound, safeCommand, parsed?.parameters?.size ?: 0)
    }

    public fun deviceField(text: String): String = bounded(text.filterNot(Char::isISOControl), 120)

    private fun bounded(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (text[limit - 1].isHighSurrogate() && text[limit].isLowSurrogate()) limit - 1 else limit
        return text.substring(0, end)
    }
}
