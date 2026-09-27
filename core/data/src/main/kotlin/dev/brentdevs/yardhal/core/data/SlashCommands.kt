package dev.brentdevs.yardhal.core.data

public sealed interface SlashCommand {
    public data class PlainMessage(public val text: String) : SlashCommand
    public data class EscapedMessage(public val text: String) : SlashCommand
    public data class Action(public val description: String) : SlashCommand
    public data class Msg(public val target: String, public val text: String) : SlashCommand
    public data class Query(public val nick: String) : SlashCommand
    public data class Join(public val channels: List<String>, public val keys: List<String>) : SlashCommand
    public data class Part(public val channel: String?, public val reason: String?) : SlashCommand
    public data class NickChange(public val newNick: String) : SlashCommand
    public data class TopicSet(public val channel: String, public val topic: String) : SlashCommand
    public data class TopicShow(public val channel: String?) : SlashCommand
    public data class Away(public val message: String?) : SlashCommand
    public data class Quit(public val reason: String?) : SlashCommand
    public data class Whois(public val target: String) : SlashCommand
    public data class Kick(public val channel: String?, public val nick: String, public val reason: String?) : SlashCommand
    public data class Ban(public val channel: String?, public val mask: String?) : SlashCommand
    public data class Mode(public val target: String?, public val params: List<String>) : SlashCommand
    public data class CtcpQuery(public val target: String, public val command: String, public val arguments: String) : SlashCommand
    public data class Op(public val channel: String?, public val nick: String, public val grant: Boolean) : SlashCommand
    public data class Voice(public val channel: String?, public val nick: String, public val grant: Boolean) : SlashCommand
    public data class MonitorAdd(public val nick: String) : SlashCommand
    public data class MonitorRemove(public val nick: String) : SlashCommand
    public data object MonitorList : SlashCommand
    public data class WhoQuery(public val target: String, public val useWhox: Boolean) : SlashCommand
    public data class IgnoreAdd(public val mask: String) : SlashCommand
    public data class IgnoreRemove(public val mask: String) : SlashCommand
    public data class Raw(public val line: String) : SlashCommand
    public data object Help : SlashCommand
}

public object SlashCommandParser {

    private const val CHANNEL_LEADERS = "#&"

    public fun parse(input: String, currentChannel: String?): SlashCommand? {
        if (!input.startsWith("/")) return SlashCommand.PlainMessage(input)

        val body = input.removePrefix("/")
        if (body.isEmpty()) return SlashCommand.Help

        if (body.startsWith("/")) return SlashCommand.EscapedMessage(body)

        val spaceIndex = body.indexOf(' ')
        val verb = (if (spaceIndex < 0) body else body.substring(0, spaceIndex)).lowercase()
        val rest = if (spaceIndex < 0) "" else body.substring(spaceIndex + 1).trimStart()

        return when (verb) {
            "help" -> SlashCommand.Help
            "me" -> rest.takeIf { it.isNotEmpty() }?.let { SlashCommand.Action(it) }
            "msg" -> parseMsg(rest)
            "query" -> rest.split(' ').firstOrNull { it.isNotEmpty() }?.let { SlashCommand.Query(it) }
            "j", "join" -> parseJoin(rest)
            "part" -> parsePart(rest, currentChannel)
            "nick" -> rest.split(' ').firstOrNull { it.isNotEmpty() }?.let { SlashCommand.NickChange(it) }
            "topic" -> parseTopic(rest, currentChannel)
            "away" -> SlashCommand.Away(rest.ifBlank { null })
            "back" -> SlashCommand.Away(null)
            "quit" -> SlashCommand.Quit(rest.ifBlank { null })
            "whois" -> rest.split(' ').firstOrNull { it.isNotEmpty() }?.let { SlashCommand.Whois(it) }
            "kick" -> parseKick(rest, currentChannel)
            "ban" -> parseBan(rest, currentChannel)
            "m", "mode" -> parseMode(rest, currentChannel)
            "ctcp" -> parseCtcp(rest)
            "op" -> parsePrivChange(rest, currentChannel, mode = "+o") { c, n -> SlashCommand.Op(c, n, grant = true) }
            "deop" -> parsePrivChange(rest, currentChannel, mode = "-o") { c, n -> SlashCommand.Op(c, n, grant = false) }
            "voice", "v" -> parsePrivChange(rest, currentChannel, mode = "+v") { c, n -> SlashCommand.Voice(c, n, grant = true) }
            "devoice" -> parsePrivChange(rest, currentChannel, mode = "-v") { c, n -> SlashCommand.Voice(c, n, grant = false) }
            "monitor" -> parseMonitor(rest)
            "who" -> {
                val target = tokensOf(rest).firstOrNull() ?: currentChannel ?: return null
                SlashCommand.WhoQuery(target, useWhox = true)
            }
            "ignore" -> tokensOf(rest).firstOrNull()?.let { SlashCommand.IgnoreAdd(it) }
            "unignore" -> tokensOf(rest).firstOrNull()?.let { SlashCommand.IgnoreRemove(it) }
            "quote", "raw" -> rawLine(rest)
            else -> rawLine(body)
        }
    }

    private fun rawLine(body: String): SlashCommand.Raw {
        val spaceIndex = body.indexOf(' ')
        if (spaceIndex < 0) return SlashCommand.Raw(body.uppercase())
        return SlashCommand.Raw(
            body.substring(0, spaceIndex).uppercase() + " " + body.substring(spaceIndex + 1),
        )
    }

    private fun parsePrivChange(
        rest: String,
        currentChannel: String?,
        mode: String,
        build: (String?, String) -> SlashCommand,
    ): SlashCommand? {
        val tokens = tokensOf(rest)
        if (tokens.isEmpty()) return null
        val first = tokens.first()
        return if (firstIsChannelLeader(first)) {
            val nick = tokens.getOrNull(1) ?: return null
            build(first, nick)
        } else {
            currentChannel ?: return null
            build(currentChannel, first)
        }
    }

    private fun parseMonitor(rest: String): SlashCommand {
        val trimmed = rest.trim()
        val parts = tokensOf(trimmed)
        val sub = parts.firstOrNull()?.lowercase()
        val target = parts.getOrNull(1)
        return when {
            sub == "+" && target != null -> SlashCommand.MonitorAdd(target)
            sub == "-" && target != null -> SlashCommand.MonitorRemove(target)
            sub == "l" || sub == "ls" || trimmed.isEmpty() -> SlashCommand.MonitorList
            target == null -> SlashCommand.MonitorAdd(sub ?: return SlashCommand.MonitorList)
            else -> SlashCommand.MonitorAdd(trimmed)
        }
    }

    private fun String.upperFirst(): String =
        if (isEmpty()) this else substring(0, 1).uppercase() + substring(1)

    private fun firstIsChannelLeader(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        return token[0] in CHANNEL_LEADERS
    }

    private fun tokensOf(input: String): List<String> = input.split(' ').filter { it.isNotEmpty() }

    private fun parseMsg(rest: String): SlashCommand? {
        val target = tokensOf(rest).firstOrNull() ?: return null
        val textStart = rest.indexOf(target) + target.length
        val text = rest.substring(textStart).trimStart()
        return SlashCommand.Msg(target, text)
    }

    private fun parseJoin(rest: String): SlashCommand {
        val tokens = tokensOf(rest)
        if (tokens.isEmpty()) return SlashCommand.Join(emptyList(), emptyList())
        val keys = tokens.drop(1)
        return SlashCommand.Join(
            channels = tokens.first().split(',').filter { it.isNotEmpty() },
            keys = keys,
        )
    }

    private fun parsePart(rest: String, currentChannel: String?): SlashCommand {
        val tokens = tokensOf(rest)
        val first = tokens.firstOrNull()
        return when {
            first != null && firstIsChannelLeader(first) -> SlashCommand.Part(
                channel = first,
                reason = tokens.drop(1).joinToString(" ").ifEmpty { null },
            )
            currentChannel != null -> SlashCommand.Part(currentChannel, tokens.joinToString(" ").ifEmpty { null })
            else -> SlashCommand.Part(null, tokens.joinToString(" ").ifEmpty { null })
        }
    }

    private fun parseTopic(rest: String, currentChannel: String?): SlashCommand? {
        val tokens = tokensOf(rest)
        val first = tokens.firstOrNull()
        return when {
            tokens.isEmpty() -> SlashCommand.TopicShow(currentChannel)
            firstIsChannelLeader(first) -> {
                val channel = first ?: return null
                val topic = tokens.drop(1).joinToString(" ")
                if (topic.isEmpty()) SlashCommand.TopicShow(channel) else SlashCommand.TopicSet(channel, topic)
            }
            currentChannel != null -> SlashCommand.TopicSet(currentChannel, tokens.joinToString(" "))
            else -> null
        }
    }

    private fun parseKick(rest: String, currentChannel: String?): SlashCommand? {
        val tokens = tokensOf(rest)
        if (tokens.isEmpty()) return null
        val first = tokens.first()
        return if (firstIsChannelLeader(first)) {
            val nick = tokens.getOrNull(1) ?: return null
            SlashCommand.Kick(first, nick, tokens.drop(2).joinToString(" ").ifEmpty { null })
        } else {
            currentChannel ?: return null
            SlashCommand.Kick(currentChannel, first, tokens.drop(1).joinToString(" ").ifEmpty { null })
        }
    }

    private fun parseBan(rest: String, currentChannel: String?): SlashCommand {
        val tokens = tokensOf(rest)
        val first = tokens.firstOrNull()
        return when {
            first != null && firstIsChannelLeader(first) ->
                SlashCommand.Ban(first, tokens.getOrNull(1))
            first != null && currentChannel == null -> SlashCommand.Ban(first, null)
            else -> SlashCommand.Ban(currentChannel, first)
        }
    }

    private fun parseMode(rest: String, currentChannel: String?): SlashCommand? {
        val tokens = tokensOf(rest)
        if (tokens.isEmpty()) return SlashCommand.Mode(currentChannel, emptyList())
        val first = tokens.first()
        return if (firstIsChannelLeader(first) || !first.startsWith("+") && !first.startsWith("-")) {
            SlashCommand.Mode(first, tokens.drop(1))
        } else {
            currentChannel ?: return null
            SlashCommand.Mode(currentChannel, tokens)
        }
    }

    private fun parseCtcp(rest: String): SlashCommand? {
        val tokens = tokensOf(rest)
        val target = tokens.firstOrNull() ?: return null
        val command = tokens.getOrNull(1)?.uppercase() ?: return null
        val remainder = rest.substringAfter(command, "").trimStart()
        val arguments = rest.substringAfter(' ', "").substringAfter(' ', "").trimStart()
        return SlashCommand.CtcpQuery(target, command, arguments.ifEmpty { remainder })
    }
}
