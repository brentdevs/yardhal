package dev.brentdevs.yardhal.core.data

import java.math.BigDecimal
import java.math.RoundingMode

public enum class ZncNetworkVariable {
    Nick,
    Altnick,
    Ident,
    RealName,
    BindHost,
    FloodRate,
    FloodBurst,
    JoinDelay,
    Encoding,
    QuitMsg,
    TrustAllCerts,
    TrustPKI,
}

public enum class ZncChannelVariable {
    Detached,
    BufferSize,
    AutoClearChanBuffer,
}

public enum class ZncCommandTarget(public val wireName: String) {
    Status("status"),
    ControlPanel("controlpanel"),
}

public data class ZncNetwork(
    public val name: String,
    public val onIrc: Boolean,
    public val ircServer: String = "",
    public val ircUser: String = "",
    public val channelCount: Int? = null,
)

public data class ZncNetworkDraft(
    public val name: String,
    public val settings: Map<ZncNetworkVariable, String> = emptyMap(),
    public val servers: List<ZncServerDraft> = emptyList(),
)

public data class ZncServerDraft(
    public val host: String,
    public val port: Int = 6697,
    public val tls: Boolean = true,
    public val passwordChanged: Boolean = false,
    public val password: String? = "",
    public val current: Boolean = false,
) {
    public override fun toString(): String =
        "ZncServerDraft(host=$host, port=$port, tls=$tls, passwordChanged=$passwordChanged, password=<redacted>, current=$current)"
}

public data class ZncChannel(
    public val name: String,
    public val status: String,
    public val inConfig: Boolean,
    public val bufferSize: Int,
    public val bufferSizeExplicit: Boolean,
    public val autoClearChanBuffer: Boolean,
    public val autoClearChanBufferExplicit: Boolean,
) {
    public fun toDraft(): ZncChannelDraft = ZncChannelDraft(
        name = name,
        detached = status == "Detached",
        disabled = status == "Disabled",
        bufferSize = bufferSize.takeIf { bufferSizeExplicit },
        autoClearChanBuffer = autoClearChanBuffer.takeIf { autoClearChanBufferExplicit },
    )
}

public data class ZncChannelDraft(
    public val name: String,
    public val detached: Boolean = false,
    public val disabled: Boolean = false,
    public val bufferSize: Int? = null,
    public val autoClearChanBuffer: Boolean? = null,
)

public data class ZncChannelSetting(
    public val channel: String,
    public val variable: ZncChannelVariable,
    public val value: String,
    public val inherited: Boolean,
)

public class ZncCommand internal constructor(
    public val text: String,
    public val target: ZncCommandTarget,
    public val boundOnly: Boolean = false,
    private val acknowledgement: (String) -> Boolean,
) {
    public fun matchesAcknowledgement(line: String): Boolean = acknowledgement(line)

    public override fun toString(): String =
        "ZncCommand(target=$target, boundOnly=$boundOnly, command=${text.substringBefore(' ')}, arguments=<redacted>)"
}

public object BouncerZncParser {
    private val networkColumns = setOf("network", "onirc", "ircserver", "ircuser", "channels")
    private val serverColumns = setOf("host", "port", "ssl", "password")
    private val channelColumns = setOf("index", "name", "status", "inconfig", "buffer", "clear", "modes", "users")

    public fun networks(lines: List<String>): List<ZncNetwork>? {
        if (lines.any { it.trim() == "No networks" }) return emptyList()
        val rows = table(lines, networkColumns) ?: return null
        return rows.map { row ->
            val name = row.getValue("network").takeIf { it.isNotEmpty() } ?: return null
            val connected = boolean(row.getValue("onirc")) ?: return null
            val count = row.getValue("channels")
            ZncNetwork(
                name = name,
                onIrc = connected,
                ircServer = row.getValue("ircserver"),
                ircUser = row.getValue("ircuser"),
                channelCount = if (count.isEmpty()) null else count.toIntOrNull()?.takeIf { it >= 0 } ?: return null,
            )
        }
    }

    public fun servers(lines: List<String>): List<ZncServerDraft>? {
        if (lines.any { it.trim() == "You don't have any servers added." }) return emptyList()
        val rows = table(lines, serverColumns) ?: return null
        return rows.map { row ->
            val rawHost = row.getValue("host")
            val host = rawHost.removeSuffix("*").takeIf { it.isNotEmpty() } ?: return null
            val port = row.getValue("port").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val tls = when (row.getValue("ssl")) {
                "SSL" -> true
                "" -> false
                else -> return null
            }
            val password = when (row.getValue("password")) {
                "" -> ""
                "******" -> null
                else -> return null
            }
            ZncServerDraft(host = host, port = port, tls = tls, password = password, current = rawHost.endsWith("*"))
        }
    }

    public fun channels(lines: List<String>): List<ZncChannel>? {
        if (lines.any { it.trim() == "There are no channels defined." }) return emptyList()
        val rows = table(lines, channelColumns) ?: return null
        return rows.map { row ->
            val rawName = row.getValue("name")
            var prefix = 0
            while (prefix + 1 < rawName.length && rawName[prefix] in "~&@%+" && rawName[prefix + 1] in "~&@%+#!") prefix++
            if (rawName.getOrNull(prefix)?.let { it in "#&+!" } != true) return null
            val name = rawName.substring(prefix)
            val status = row.getValue("status").takeIf { it == "Joined" || it == "Detached" || it == "Disabled" || it == "Trying" } ?: return null
            val buffer = row.getValue("buffer")
            val clear = row.getValue("clear")
            ZncChannel(
                name = name,
                status = status,
                inConfig = boolean(row.getValue("inconfig"), emptyIsFalse = true) ?: return null,
                bufferSize = buffer.removePrefix("*").toIntOrNull()?.takeIf { it >= 0 } ?: return null,
                bufferSizeExplicit = buffer.startsWith("*"),
                autoClearChanBuffer = boolean(clear.removePrefix("*"), emptyIsFalse = true) ?: return null,
                autoClearChanBufferExplicit = clear.startsWith("*"),
            )
        }
    }

    public fun networkSetting(line: String): Pair<ZncNetworkVariable, String>? {
        val separator = line.indexOf(" = ")
        if (separator < 0) return null
        val variable = ZncNetworkVariable.entries.firstOrNull { it.name.equals(line.substring(0, separator), ignoreCase = true) }
            ?: return null
        return variable to line.substring(separator + 3)
    }

    public fun channelSetting(line: String): ZncChannelSetting? {
        val channelEnd = line.indexOf(": ")
        val separator = line.indexOf(" = ", startIndex = channelEnd + 2)
        if (channelEnd <= 0 || separator < 0) return null
        val variable = ZncChannelVariable.entries.firstOrNull {
            it.name.equals(line.substring(channelEnd + 2, separator), ignoreCase = true)
        } ?: return null
        val value = line.substring(separator + 3)
        return ZncChannelSetting(line.substring(0, channelEnd), variable, value.removeSuffix(" (default)"), value.endsWith(" (default)"))
    }

    internal fun boolean(value: String, emptyIsFalse: Boolean = false): Boolean? = when (value.lowercase()) {
        "yes", "true", "1" -> true
        "no", "false", "0" -> false
        "" -> false.takeIf { emptyIsFalse }
        else -> null
    }

    private fun table(lines: List<String>, required: Set<String>): List<Map<String, String>>? {
        val relevant = lines.map(String::trim).filter { it.startsWith('|') || it.startsWith('+') }
        if (relevant.size < 4 || !border(relevant.first()) || relevant.last() != relevant.first()) return null
        val separators = relevant.first().indices.filter { relevant.first()[it] == '+' }
        val header = cells(relevant[1], separators)?.map { it.lowercase().filterNot(Char::isWhitespace) } ?: return null
        if (!header.containsAll(required) || header.distinct().size != header.size || relevant[2] != relevant.first()) return null
        return relevant.subList(3, relevant.lastIndex).map { line ->
            val values = cells(line, separators) ?: return null
            header.zip(values).toMap()
        }
    }

    private fun cells(line: String, separators: List<Int>): List<String>? {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.lastIndex != separators.last() || separators.any { bytes[it] != '|'.code.toByte() }) return null
        return separators.zipWithNext { start, end -> String(bytes, start + 1, end - start - 1, Charsets.UTF_8).trim() }
    }

    private fun border(line: String): Boolean =
        line.startsWith('+') && line.endsWith('+') && '-' in line && line.all { it == '+' || it == '-' }
}

public object BouncerZncCommands {
    public fun listNetworks(): ZncCommand = ZncCommand("ListNetworks", ZncCommandTarget.Status) { it == "No networks" }

    public fun listServers(): ZncCommand = ZncCommand("ListServers", ZncCommandTarget.Status, boundOnly = true) {
        it == "You don't have any servers added."
    }

    public fun listChans(): ZncCommand = ZncCommand("ListChans", ZncCommandTarget.Status, boundOnly = true) {
        it == "There are no channels defined."
    }

    public fun connect(): ZncCommand = ZncCommand("Connect", ZncCommandTarget.Status, boundOnly = true) {
        it == "Connecting..." || it.startsWith("Connecting to ") && it.endsWith("...") || it == "Jumping to the next server in the list..."
    }

    public fun disconnect(): ZncCommand = ZncCommand("Disconnect", ZncCommandTarget.Status, boundOnly = true) {
        it == "Disconnected from IRC. Use 'connect' to reconnect."
    }

    public fun reconnect(): ZncCommand = ZncCommand("Jump", ZncCommandTarget.Status, boundOnly = true) {
        it == "Connecting..." || it.startsWith("Connecting to ") && it.endsWith("...") || it == "Jumping to the next server in the list..."
    }

    public fun addNetwork(name: String): ZncCommand {
        validateNetwork(name)
        return ZncCommand("AddNetwork $name", ZncCommandTarget.Status) {
            it.startsWith("Network added. Use /znc JumpNetwork $name, ")
        }
    }

    public fun deleteNetwork(name: String): ZncCommand {
        validateNetwork(name)
        return ZncCommand("DelNetwork $name", ZncCommandTarget.Status) { it == "Network deleted" }
    }

    public fun getNetwork(network: String, variable: ZncNetworkVariable): ZncCommand {
        validateNetwork(network)
        return ZncCommand("GetNetwork ${variable.name} \$user $network", ZncCommandTarget.ControlPanel) {
            BouncerZncParser.networkSetting(it)?.first == variable
        }
    }

    public fun setNetwork(network: String, variable: ZncNetworkVariable, value: String): ZncCommand {
        validateNetwork(network)
        val canonical = networkValue(variable, value)
        return ZncCommand("SetNetwork ${variable.name} \$user $network $value", ZncCommandTarget.ControlPanel) {
            val setting = BouncerZncParser.networkSetting(it)
            setting != null && setting.first == variable && equivalentNetworkValue(variable, setting.second, canonical)
        }
    }

    public fun getChan(network: String, channel: String, variable: ZncChannelVariable): ZncCommand {
        validateNetwork(network)
        validateChannel(channel)
        return ZncCommand("GetChan ${variable.name} \$user $network $channel", ZncCommandTarget.ControlPanel) {
            val setting = BouncerZncParser.channelSetting(it)
            setting?.channel.equals(channel, ignoreCase = true) && setting?.variable == variable
        }
    }

    public fun setChan(network: String, channel: String, variable: ZncChannelVariable, value: String): ZncCommand {
        validateNetwork(network)
        validateChannel(channel)
        val canonical = channelValue(variable, value)
        return ZncCommand("SetChan ${variable.name} \$user $network $channel $value", ZncCommandTarget.ControlPanel) {
            val setting = BouncerZncParser.channelSetting(it)
            setting != null && setting.channel.equals(channel, ignoreCase = true) && setting.variable == variable &&
                if (canonical == "-") {
                    when (variable) {
                        ZncChannelVariable.BufferSize -> setting.value.toIntOrNull()?.let { size -> size >= 0 } == true
                        ZncChannelVariable.AutoClearChanBuffer -> BouncerZncParser.boolean(setting.value) != null
                        ZncChannelVariable.Detached -> false
                    }
                } else {
                    equivalentChannelValue(variable, setting.value, canonical)
                }
        }
    }

    public fun addServer(network: String, server: ZncServerDraft): ZncCommand {
        validateNetwork(network)
        val arguments = serverArguments(server, includePassword = true)
        return ZncCommand("AddServer \$user $network $arguments", ZncCommandTarget.ControlPanel) {
            it.startsWith("Added IRC Server $arguments to network $network for user ") && it.endsWith('.')
        }
    }

    public fun deleteServer(network: String, server: ZncServerDraft): ZncCommand {
        validateNetwork(network)
        validateServer(server)
        return ZncCommand("DelServer ${server.host} ${server.port}", ZncCommandTarget.Status, boundOnly = true) {
            it == "Server removed"
        }
    }

    public fun networkDiff(baseline: ZncNetworkDraft, draft: ZncNetworkDraft): List<ZncCommand> {
        require(baseline.name == draft.name) { "ZNC network renaming is not supported." }
        validateNetwork(draft.name)
        val changes = ZncNetworkVariable.entries.mapNotNull { variable ->
            draft.settings[variable]?.takeIf { it != baseline.settings[variable] }?.let { setNetwork(draft.name, variable, it) }
        }
        return changes + serverDiff(draft.name, baseline.servers, draft.servers)
    }

    public fun serverDiff(network: String, baseline: List<ZncServerDraft>, draft: List<ZncServerDraft>): List<ZncCommand> {
        validateNetwork(network)
        baseline.forEach(::validateServer)
        draft.forEach(::validateServer)
        val retained = mutableSetOf<Int>()
        var searchFrom = 0
        var prefixSize = 0
        for (desired in draft) {
            val match = (searchFrom until baseline.size).firstOrNull { unchangedServer(baseline[it], desired) } ?: break
            retained.add(match)
            searchFrom = match + 1
            prefixSize++
        }
        val additions = draft.drop(prefixSize).mapIndexed { index, desired ->
            val password = if (desired.passwordChanged) {
                requireNotNull(desired.password) { "A changed server password must be supplied explicitly." }
            } else {
                val existing = baseline.firstOrNull { sameEndpoint(it, desired) }
                    ?: baseline.getOrNull(prefixSize + index)?.takeIf { prefixSize + index !in retained }
                if (existing != null) existing.password else desired.password
            }
            require(password != null) { "Reordering or replacing this server would lose an unknown password. Supply its password explicitly." }
            desired.copy(password = password)
        }
        val commands = mutableListOf<ZncCommand>()
        val remaining = baseline.indices.toMutableList()
        for (index in baseline.indices) {
            if (index in retained) continue
            val removed = baseline[index]
            val firstMatching = remaining.firstOrNull { candidate ->
                baseline[candidate].host.equals(removed.host, ignoreCase = true) &&
                    (removed.port == 6667 || baseline[candidate].port == removed.port)
            }
            require(firstMatching == index) { "ZNC cannot safely distinguish this server from an earlier retained server." }
            commands.add(deleteServer(network, removed))
            remaining.remove(index)
        }
        additions.forEach { commands.add(addServer(network, it)) }
        return commands
    }

    public fun channelDiff(
        network: String,
        baseline: ZncChannelDraft,
        draft: ZncChannelDraft,
        boundNetwork: String? = null,
    ): List<ZncCommand> {
        require(baseline.name == draft.name) { "ZNC channel renaming is not supported." }
        validateNetwork(network)
        validateChannel(draft.name)
        val commands = mutableListOf<ZncCommand>()
        if (baseline.detached != draft.detached) {
            commands.add(setChan(network, draft.name, ZncChannelVariable.Detached, draft.detached.toString()))
        }
        if (baseline.bufferSize != draft.bufferSize) {
            commands.add(setChan(network, draft.name, ZncChannelVariable.BufferSize, draft.bufferSize?.toString() ?: "-"))
        }
        if (baseline.autoClearChanBuffer != draft.autoClearChanBuffer) {
            commands.add(setChan(network, draft.name, ZncChannelVariable.AutoClearChanBuffer, draft.autoClearChanBuffer?.toString() ?: "-"))
        }
        if (baseline.disabled != draft.disabled) {
            require(network == boundNetwork) { "Channel enable/disable is only available on the bound ZNC network." }
            val verb = if (draft.disabled) "DisableChan" else "EnableChan"
            val acknowledgement = if (draft.disabled) "Disabled 1 channel" else "Enabled 1 channel"
            commands.add(ZncCommand("$verb ${draft.name}", ZncCommandTarget.Status, boundOnly = true) { it == acknowledgement })
        }
        return commands
    }

    private fun unchangedServer(baseline: ZncServerDraft, draft: ZncServerDraft): Boolean =
        sameEndpoint(baseline, draft) && !draft.passwordChanged

    private fun sameEndpoint(first: ZncServerDraft, second: ZncServerDraft): Boolean =
        first.host == second.host && first.port == second.port && first.tls == second.tls

    private fun serverArguments(server: ZncServerDraft, includePassword: Boolean): String {
        validateServer(server)
        val port = if (server.tls) "+${server.port}" else server.port.toString()
        val password = if (includePassword) requireNotNull(server.password) { "An unknown server password cannot be recreated." } else ""
        return "${server.host} $port" + if (password.isEmpty()) "" else " $password"
    }

    private fun validateServer(server: ZncServerDraft) {
        token(server.host)
        require(!server.host.startsWith("unix:") && !server.host.endsWith('*')) { "Only TCP server hosts are supported." }
        require(server.port in 1..65535) { "Server port must be between 1 and 65535." }
        server.password?.let { value ->
            safe(value)
            require(value == value.trim() && '\t' !in value) { "Server password contains unsupported whitespace." }
        }
        require(!server.passwordChanged || server.password != null) { "A changed server password must be supplied explicitly." }
    }

    private fun validateNetwork(value: String) {
        require(value.isNotEmpty() && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) {
            "ZNC network names must contain only letters, digits, underscores, or hyphens."
        }
    }

    private fun validateChannel(value: String) {
        token(value)
        require(value.first() in "#&+!" && value.none { it == '*' || it == '?' || it == ',' }) {
            "An exact channel name without wildcards is required."
        }
    }

    private fun token(value: String) {
        safe(value)
        require(value.isNotEmpty() && value.none { it.isWhitespace() || it == '\'' || it == '"' }) { "An unquoted token without whitespace is required." }
    }

    private fun safe(value: String) {
        require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "ZNC arguments must not contain CR, LF, or NUL." }
    }

    private fun networkValue(variable: ZncNetworkVariable, value: String): String {
        safe(value)
        require(value.isNotEmpty() && value == value.trim() && '\t' !in value) { "ZNC cannot set an empty or whitespace-delimited network value." }
        return when (variable) {
            ZncNetworkVariable.TrustAllCerts, ZncNetworkVariable.TrustPKI ->
                requireNotNull(BouncerZncParser.boolean(value)) { "A boolean value is required." }.toString()
            ZncNetworkVariable.FloodBurst, ZncNetworkVariable.JoinDelay -> {
                val number = value.toIntOrNull()
                require(number != null && number in 0..65535) { "A number between 0 and 65535 is required." }
                number.toString()
            }
            ZncNetworkVariable.FloodRate -> {
                val number = value.toDoubleOrNull()
                require(number != null && number.isFinite() && number >= 0) { "A finite nonnegative flood rate is required." }
                BigDecimal(number).setScale(2, RoundingMode.HALF_EVEN).toPlainString()
            }
            ZncNetworkVariable.Nick, ZncNetworkVariable.Altnick, ZncNetworkVariable.Ident,
            ZncNetworkVariable.BindHost, ZncNetworkVariable.Encoding -> {
                token(value)
                value
            }
            ZncNetworkVariable.RealName, ZncNetworkVariable.QuitMsg -> value
        }
    }

    private fun channelValue(variable: ZncChannelVariable, value: String): String {
        safe(value)
        if (value == "-" && variable != ZncChannelVariable.Detached) return value
        return when (variable) {
            ZncChannelVariable.BufferSize -> {
                val number = value.toIntOrNull()
                require(number != null && number >= 0) { "A nonnegative buffer size is required." }
                number.toString()
            }
            ZncChannelVariable.Detached, ZncChannelVariable.AutoClearChanBuffer ->
                requireNotNull(BouncerZncParser.boolean(value)) { "A boolean value is required." }.toString()
        }
    }

    private fun equivalentNetworkValue(variable: ZncNetworkVariable, actual: String, canonical: String): Boolean = when (variable) {
        ZncNetworkVariable.TrustAllCerts, ZncNetworkVariable.TrustPKI -> BouncerZncParser.boolean(actual)?.toString() == canonical
        ZncNetworkVariable.FloodBurst, ZncNetworkVariable.JoinDelay -> actual.toIntOrNull()?.toString() == canonical
        ZncNetworkVariable.FloodRate -> actual.toDoubleOrNull() == canonical.toDoubleOrNull()
        else -> actual == canonical
    }

    private fun equivalentChannelValue(variable: ZncChannelVariable, actual: String, canonical: String): Boolean = when (variable) {
        ZncChannelVariable.BufferSize -> actual.toIntOrNull()?.toString() == canonical
        ZncChannelVariable.Detached, ZncChannelVariable.AutoClearChanBuffer -> BouncerZncParser.boolean(actual)?.toString() == canonical
    }
}
