package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcTags

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
            "PASS" -> parameters.firstOrNull()?.let(::rememberCredential)
            "AUTHENTICATE" -> parameters.firstOrNull()?.takeUnless { it in VISIBLE_AUTHENTICATE_ARGUMENTS }?.let(::remember)
            "BOUNCER" -> {
                val subcommand = parameters.firstOrNull()
                val attributes = when {
                    subcommand.equals("ADDNETWORK", true) -> parameters.getOrNull(1)
                    subcommand.equals("CHANGENETWORK", true) -> parameters.getOrNull(2)
                    else -> null
                }
                attributes?.let { source ->
                    for ((key, value) in IrcTags.parseSection(source)) {
                        if (key.equals("pass", true) && !value.isNullOrEmpty()) {
                            rememberCredential(value)
                            remember(IrcTags.escape(value))
                        }
                    }
                }
            }
            "REGISTER" -> parameters.getOrNull(2)?.let(::rememberCredential)
            "OPER" -> passwordAfterFirstArgument(parameters.joinToString(" "))?.let(::rememberCredential)
            else -> serviceAuthentication(message, nickServService)?.let { authentication ->
                authentication.credentialEcho?.let(::remember)
                authentication.password?.let(::rememberCredential)
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
            val secret = orderedSecrets.firstOrNull { text.startsWith(it, index) }
            if (secret == null) {
                index += if (text.startsWith(REDACTION_MARKER, index)) REDACTION_MARKER.length else 1
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

    private fun rememberCredential(credential: String) {
        remember(credential)
        var start = 0
        while (start < credential.length) {
            while (start < credential.length && credential[start].isCredentialSeparator()) start += 1
            var end = start
            while (end < credential.length && !credential[end].isCredentialSeparator()) end += 1
            if (end > start && (start != 0 || end != credential.length)) remember(credential.substring(start, end))
            start = end
        }
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
        "BOUNCER" -> {
            if (parameters.firstOrNull().equals("BIND", true)) return line
            listOfNotNull(parameters.firstOrNull(), REDACTION_MARKER)
        }
        "FAIL", "WARN", "NOTE" -> {
            if (!parameters.firstOrNull().equals("BOUNCER", true)) return line
            parameters.take(2) + REDACTION_MARKER
        }
        "PRIVMSG", "NOTICE" -> {
            val target = parameters.firstOrNull().orEmpty()
            val sender = message.prefix?.nick.orEmpty()
            if (target.equals("BouncerServ", true) || sender.equals("BouncerServ", true) ||
                (target.length > 1 && target.startsWith('*')) || (sender.length > 1 && sender.startsWith('*'))
            ) listOf(target, REDACTION_MARKER) else {
                val authentication = serviceAuthentication(message, nickServService) ?: return line
                listOf(target, "${authentication.command} $REDACTION_MARKER")
            }
        }
        "PASS" -> listOf("<redacted>")
        "AUTHENTICATE" -> {
            if (parameters.size == 1 && parameters.first() in VISIBLE_AUTHENTICATE_ARGUMENTS) return line
            listOf("<redacted>")
        }
        "REGISTER" -> if (parameters.size < 3) listOf("<redacted>") else parameters.take(2) + "<redacted>"
        "OPER" -> listOfNotNull(parameters.firstOrNull()?.substringBefore('\t'), REDACTION_MARKER)
        "IDENTIFY" -> listOf(REDACTION_MARKER)
        else -> {
            val authentication = serviceAuthentication(message, nickServService) ?: return line
            if (message.command.equals("PRIVMSG", ignoreCase = true) || message.command.equals("NOTICE", ignoreCase = true)) {
                listOf(parameters.first(), "${authentication.command} $REDACTION_MARKER")
            } else {
                authentication.command.split(' ') + REDACTION_MARKER
            }
        }
    }
    return message.copy(parameters = redactedParameters).toWire()
}

private data class ServiceAuthentication(val command: String, val password: String?, val credentialEcho: String? = null)

private fun serviceAuthentication(message: IrcMessage, nickServService: String): ServiceAuthentication? {
    val command = message.command.uppercase()
    val parameters = message.parameters
    val payload = when {
        command == "IDENTIFY" -> "IDENTIFY ${parameters.joinToString(" ")}"
        command == "PRIVMSG" || command == "NOTICE" -> {
            if (!matchesService(parameters.firstOrNull() ?: return null, nickServService)) return null
            parameters.getOrNull(1) ?: return null
        }
        matchesService(command, nickServService) ||
            (nickServService.substringBefore('@').equals("NickServ", ignoreCase = true) && command == "NS") ->
            parameters.joinToString(" ")
        else -> return null
    }
    val trimmed = payload.trimStart(' ', '\t')
    val commandEnd = trimmed.indexOfFirst(Char::isCredentialSeparator)
    val serviceCommand = (if (commandEnd < 0) trimmed else trimmed.substring(0, commandEnd)).uppercase()
    val arguments = if (commandEnd < 0) "" else trimmed.substring(commandEnd).trimStart(' ', '\t')
    return when (serviceCommand) {
        "IDENTIFY" -> ServiceAuthentication("IDENTIFY", passwordAfterFirstArgument(arguments) ?: arguments, arguments)
        "REGISTER" -> ServiceAuthentication("REGISTER", arguments.takeWhile { !it.isCredentialSeparator() })
        "GHOST", "RECOVER" -> ServiceAuthentication(serviceCommand, passwordAfterFirstArgument(arguments))
        "SET" -> {
            val optionEnd = arguments.indexOfFirst(Char::isCredentialSeparator)
            val option = if (optionEnd < 0) arguments else arguments.substring(0, optionEnd)
            if (!option.equals("PASSWORD", ignoreCase = true)) return null
            ServiceAuthentication("SET PASSWORD", if (optionEnd < 0) "" else arguments.substring(optionEnd).trimStart(' ', '\t'))
        }
        else -> null
    }
}

private fun passwordAfterFirstArgument(arguments: String): String? {
    val firstEnd = arguments.indexOfFirst(Char::isCredentialSeparator)
    return if (firstEnd < 0) null else arguments.substring(firstEnd).trimStart(' ', '\t').takeIf(String::isNotEmpty)
}

private fun matchesService(target: String, configured: String): Boolean {
    if (!target.substringBefore('@').equals(configured.substringBefore('@'), ignoreCase = true)) return false
    val targetHost = target.substringAfter('@', "")
    val configuredHost = configured.substringAfter('@', "")
    return targetHost.isEmpty() || configuredHost.isEmpty() || targetHost.equals(configuredHost, ignoreCase = true)
}

private fun Char.isCredentialSeparator(): Boolean = this == ' ' || this == '\t'
