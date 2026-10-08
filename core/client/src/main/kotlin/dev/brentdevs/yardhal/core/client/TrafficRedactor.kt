package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage

private val VISIBLE_AUTHENTICATE_ARGUMENTS = SaslAuthenticator.PREFERRED_MECHANISMS.toSet() +
    SaslAuthenticator.EXTERNAL + SaslAuthenticator.CONTINUATION_MARKER + SaslAuthenticator.ABORT_MARKER

internal class TrafficRedactor(initialSecrets: Collection<String>, private val nickServService: String) {
    private val secrets = initialSecrets.filterTo(LinkedHashSet()) { it.isNotEmpty() }
    private var orderedSecrets = secrets.sortedByDescending(String::length)

    @Synchronized
    fun rememberOutbound(line: String) {
        val message = IrcMessage.parse(line.trimStart(' ')) ?: return
        val parameters = message.parameters
        when (message.command.uppercase()) {
            "PASS" -> parameters.firstOrNull()?.let(::remember)
            "AUTHENTICATE" -> parameters.firstOrNull()?.takeUnless { it in VISIBLE_AUTHENTICATE_ARGUMENTS }?.let(::remember)
            "REGISTER" -> parameters.getOrNull(2)?.let(::remember)
            else -> identifyPayload(message, nickServService)?.let { payload ->
                val credentials = payload.substringAfter(' ', "")
                remember(credentials)
                remember(credentials.substringAfterLast(' '))
            }
        }
    }

    fun redact(line: String): String = redactPresentation(redactSensitiveOutbound(line, nickServService))

    @Synchronized
    fun redactPresentation(text: String): String {
        if (orderedSecrets.isEmpty()) return text
        var result: StringBuilder? = null
        var index = 0
        var unchangedStart = 0
        while (index < text.length) {
            if (text.startsWith(REDACTION_MARKER, index)) {
                index += REDACTION_MARKER.length
                continue
            }
            val secret = orderedSecrets.firstOrNull { text.startsWith(it, index) }
            if (secret == null) {
                index += 1
            } else {
                val output = result ?: StringBuilder(text.length).also { result = it }
                output.append(text, unchangedStart, index)
                output.append(REDACTION_MARKER)
                index += secret.length
                unchangedStart = index
            }
        }
        return result?.append(text, unchangedStart, text.length)?.toString() ?: text
    }

    private fun remember(secret: String) {
        if (secret.isNotEmpty() && secrets.add(secret)) orderedSecrets = secrets.sortedByDescending(String::length)
    }
}

private const val REDACTION_MARKER = "<redacted>"

internal fun redactSensitiveOutbound(line: String, nickServService: String = "NickServ"): String {
    val message = IrcMessage.parse(line.trimStart(' ')) ?: return line
    val parameters = message.parameters
    val redactedParameters = when (message.command.uppercase()) {
        "PASS" -> listOf("<redacted>")
        "AUTHENTICATE" -> {
            if (parameters.size == 1 && parameters.first() in VISIBLE_AUTHENTICATE_ARGUMENTS) return line
            listOf("<redacted>")
        }
        "REGISTER" -> if (parameters.size < 3) listOf("<redacted>") else parameters.take(2) + "<redacted>"
        else -> {
            if (identifyPayload(message, nickServService) == null) return line
            if (message.command.equals("PRIVMSG", ignoreCase = true) || message.command.equals("NOTICE", ignoreCase = true)) {
                listOf(parameters.first(), "IDENTIFY <redacted>")
            } else {
                listOf("IDENTIFY", "<redacted>")
            }
        }
    }
    return message.copy(parameters = redactedParameters).toWire()
}

private fun identifyPayload(message: IrcMessage, nickServService: String): String? {
    val command = message.command.uppercase()
    val parameters = message.parameters
    val payload = when {
        command == "PRIVMSG" || command == "NOTICE" -> {
            val target = parameters.firstOrNull()?.substringBefore('@') ?: return null
            if (!target.equals(nickServService, ignoreCase = true)) return null
            parameters.getOrNull(1) ?: return null
        }
        command.equals(nickServService, ignoreCase = true) || (nickServService.equals("NickServ", ignoreCase = true) && command == "NS") ->
            parameters.joinToString(" ")
        else -> return null
    }
    val trimmed = payload.trimStart(' ', '\t')
    val commandEnd = trimmed.indexOfFirst { it == ' ' || it == '\t' }
    val identify = if (commandEnd < 0) trimmed else trimmed.substring(0, commandEnd)
    if (!identify.equals("IDENTIFY", ignoreCase = true)) return null
    return if (commandEnd < 0) "IDENTIFY" else "IDENTIFY ${trimmed.substring(commandEnd).trimStart(' ', '\t')}"
}
