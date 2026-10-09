package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ChannelMember
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.NamesParser
import dev.brentdevs.yardhal.core.protocol.ChannelModeLists
import dev.brentdevs.yardhal.core.protocol.IrcMessage

internal fun Reduction.handleJoin(message: IrcMessage) {
    val nick = message.prefix?.nick ?: return
    val channelName = message.parameters.firstOrNull() ?: return
    val ref = state.channelRef(channelName)
    val fromUs = state.isOwnNick(nick)
    recordJoinIdentity(nick, message)
    if (!fromUs) addMember(ref, ChannelMember(nick))
    if (!fromUs && state.netsplit.isSuppressing("JOIN")) {
        state.netsplit.recordSuppressed("JOIN")
        return
    }
    if (fromUs) {
        channelOrCreate(ref)
        emit(InboundEffect.EnsureBuffer(ref))
        if (!isPlayback(message)) emit(InboundEffect.SetJoinState(ref, JoinState.JOINED))
        emit(InboundEffect.SendRaw("TOPIC $channelName"))
        emit(InboundEffect.SendRaw("MODE $channelName"))
        requestHistory(ref)
    } else {
        system(ref, "→ $nick joined", MessageKind.JOIN)
    }
}

internal fun Reduction.handlePart(message: IrcMessage) {
    val nick = message.prefix?.nick ?: return
    val channelName = message.parameters.firstOrNull() ?: return
    val reason = message.parameters.getOrNull(1)
    val ref = state.channelRef(channelName)
    if (state.isOwnNick(nick)) {
        state.forgetChannel(ref.storageKey)
        emit(InboundEffect.RemoveBuffer(ref))
        return
    }
    removeMember(nick, ref.storageKey)
    system(ref, if (reason == null) "← $nick left" else "← $nick left ($reason)", MessageKind.PART)
}

internal fun Reduction.handleKick(message: IrcMessage) {
    if (message.parameters.size < 2) return
    val channelName = message.parameters[0]
    if (!state.isChannelName(channelName)) return
    val kickedNick = message.parameters[1]
    if (kickedNick.isEmpty()) return
    val ref = state.channelRef(channelName)
    if (!context.hasBuffer(ref.storageKey)) return
    if (state.isOwnNick(kickedNick)) {
        state.pendingNames.remove(ref.storageKey)
        val channel = channelOrCreate(ref)
        channel.members.clear()
        channel.membersComplete = false
        channel.modesComplete = false
        publishMembers(channel)
        publishModes(channel)
        emit(InboundEffect.ClearTyping(ref))
        emit(InboundEffect.SetJoinState(ref, JoinState.FAILED))
    } else {
        removeMember(kickedNick, ref.storageKey)
    }
    val actor = message.prefix?.nick ?: "server"
    val reason = message.parameters.getOrNull(2)
    system(ref, "$kickedNick was kicked by $actor${reason?.let { " ($it)" }.orEmpty()}", MessageKind.PART)
}

internal fun Reduction.handleQuit(message: IrcMessage) {
    val nick = message.prefix?.nick ?: return
    removeMember(nick, null)
    state.users.remove(state.fold(nick))
    if (state.netsplit.isSuppressing("QUIT")) state.netsplit.recordSuppressed("QUIT")
}

internal fun Reduction.handleNickChange(message: IrcMessage) {
    if (message.parameters.isEmpty()) return
    val oldNick = message.prefix?.nick ?: return
    val newNick = message.parameters.last()
    val oldFolded = state.fold(oldNick)
    val newFolded = state.fold(newNick)
    state.users.remove(oldFolded)?.let { state.users[newFolded] = it.copy(nick = newNick) }
    for (channel in state.channels.values) {
        val member = channel.members.remove(oldFolded) ?: continue
        channel.members[newFolded] = member.copy(nick = newNick)
        publishMembers(channel)
    }
    for (pending in state.pendingNames.values) {
        val prior = pending.remove(oldFolded) ?: continue
        pending[newFolded] = prior.copy(nick = newNick)
    }
    if (oldFolded == state.fold(state.ownNick)) {
        state.ownNick = newNick
        emit(InboundEffect.OwnNickChanged(newNick))
    }
}

internal fun Reduction.handleRename(message: IrcMessage) {
    if (message.parameters.size < 2) return
    val oldName = message.parameters[0]
    val newName = message.parameters[1]
    if (!state.isChannelName(oldName) || !state.isChannelName(newName)) return
    val from = state.channelRef(oldName)
    val to = state.channelRef(newName)
    state.renameChannel(from, to)
    emit(InboundEffect.RenameBuffer(from, to))
    val actor = message.prefix?.nick?.let { " by $it" }.orEmpty()
    val reason = message.parameters.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
    system(to, "Channel renamed from $oldName to $newName$actor$reason")
}

internal fun Reduction.handleTopicVerb(message: IrcMessage) {
    if (message.parameters.size < 2) return
    emit(InboundEffect.SetTopic(state.channelRef(message.parameters[0]), message.parameters.last()))
}

internal fun Reduction.handleTopicNumeric(message: IrcMessage) {
    if (message.parameters.size < 3) return
    emit(InboundEffect.SetTopic(state.channelRef(message.parameters[1]), message.parameters.last()))
}

internal fun Reduction.handleNoTopic(message: IrcMessage) {
    val channelName = message.parameters.getOrNull(1) ?: return
    emit(InboundEffect.SetTopic(state.channelRef(channelName), null))
}

internal fun Reduction.handleMode(message: IrcMessage) {
    val channelName = message.parameters.firstOrNull() ?: return
    if (state.isChannelName(channelName) && !isPlayback(message)) {
        val encoded = message.parameters.getOrNull(1) ?: return
        val ref = state.channelRef(channelName)
        if (context.hasBuffer(ref.storageKey)) {
            val channel = channelOrCreate(ref)
            if (applyChannelModes(channel.modes, encoded, message.parameters, 2)) publishModes(channel)
        }
    }
    serverLine(message)
}

internal fun Reduction.handleModeNumeric(message: IrcMessage) {
    val channelName = message.parameters.getOrNull(1)
    val encoded = message.parameters.getOrNull(2)
    if (isPlayback(message) || channelName == null || !state.isChannelName(channelName) || encoded.isNullOrEmpty()) {
        serverLine(message)
        return
    }
    val ref = state.channelRef(channelName)
    if (!context.hasBuffer(ref.storageKey)) {
        serverLine(message)
        return
    }
    val modes = LinkedHashMap<String, List<String>>()
    if (!applyChannelModes(modes, encoded, message.parameters, 3)) {
        serverLine(message)
        return
    }
    val channel = channelOrCreate(ref)
    val listModes = state.isupport.chanmodes.listA
    channel.modes.keys.removeAll { it.single() !in listModes }
    channel.modes.putAll(modes)
    channel.modesComplete = true
    publishModes(channel)
}

private fun Reduction.applyChannelModes(
    modes: LinkedHashMap<String, List<String>>,
    encoded: String,
    parameters: List<String>,
    parameterStart: Int,
): Boolean {
    val classes = state.isupport.chanmodes
    var adding = true
    var requiredParameters = 0
    for (mode in encoded) {
        when (mode) {
            '+' -> adding = true
            '-' -> adding = false
            else -> {
                val prefix = mode in state.prefixModes.modes
                if (!prefix && mode !in classes.listA && mode !in classes.listB &&
                    mode !in classes.alwaysWithParamC && mode !in classes.neverWithParamD
                ) return false
                if (prefix || requiresModeParameter(mode, adding, classes)) requiredParameters += 1
            }
        }
    }
    if (requiredParameters > parameters.size - parameterStart) return false
    adding = true
    var parameterIndex = parameterStart
    for (mode in encoded) {
        when (mode) {
            '+' -> adding = true
            '-' -> adding = false
            else -> {
                val prefix = mode in state.prefixModes.modes
                val parameter = if (prefix || requiresModeParameter(mode, adding, classes)) parameters[parameterIndex++] else null
                if (prefix) continue
                val key = mode.toString()
                when {
                    mode in classes.listA -> {
                        if (parameter == null) continue
                        val prior = modes[key].orEmpty()
                        if (adding) {
                            if (parameter !in prior) modes[key] = prior + parameter
                        } else {
                            val remaining = prior.filterNot { it == parameter }
                            if (remaining.isEmpty()) modes.remove(key) else modes[key] = remaining
                        }
                    }
                    mode in classes.listB || mode in classes.alwaysWithParamC -> {
                        if (adding && parameter != null) modes[key] = listOf(parameter) else modes.remove(key)
                    }
                    mode in classes.neverWithParamD -> {
                        if (adding) modes[key] = emptyList() else modes.remove(key)
                    }
                }
            }
        }
    }
    return true
}

private fun requiresModeParameter(mode: Char, adding: Boolean, classes: ChannelModeLists): Boolean =
    mode in classes.listA || mode in classes.listB || (adding && mode in classes.alwaysWithParamC)

internal fun Reduction.accumulateNames(message: IrcMessage) {
    if (isPlayback(message)) return
    if (message.parameters.size < 2) return
    val (channelName, entries) = NamesParser.parseNamesLine(message.parameters.drop(1), state.prefixModes)
    val name = channelName ?: return
    if (!state.isChannelName(name)) return
    val ref = state.channelRef(name)
    if (!context.hasBuffer(ref.storageKey)) return
    channelOrCreate(ref)
    val pending = state.pendingNames.getOrPut(ref.storageKey) { LinkedHashMap() }
    for (entry in entries) {
        pending[state.fold(entry.member.nick)] = entry.member
        writeUser(entry.member.nick) { it.copy(user = entry.user ?: it.user, host = entry.host ?: it.host) }
    }
}

internal fun Reduction.accumulateWhoLine(message: IrcMessage) {
    if (isPlayback(message)) return
    if (message.parameters.size < 7) return
    val channelName = message.parameters[1]
    if (!state.isChannelName(channelName)) return
    val ref = state.channelRef(channelName)
    if (!context.hasBuffer(ref.storageKey)) return
    channelOrCreate(ref)
    val nick = message.parameters[5].substringBefore('!').substringBefore('@')
    val flags = message.parameters[6]
    val symbol = state.prefixModes.symbols.firstOrNull { it in flags }
    state.pendingNames.getOrPut(ref.storageKey) { LinkedHashMap() }[state.fold(nick)] = ChannelMember(nick, symbol)
    val realName = message.parameters.getOrNull(7)?.substringAfter(' ', "")?.takeIf { it.isNotEmpty() }
    writeUser(nick) { presence ->
        whoFlags(presence, flags).copy(
            user = message.parameters[2],
            host = message.parameters[3],
            realName = realName ?: presence.realName,
        )
    }
}

internal fun Reduction.finalizeNames(message: IrcMessage) {
    if (isPlayback(message) || message.parameters.size < 2) return
    val channelName = message.parameters[1]
    if (!state.isChannelName(channelName)) return
    val ref = state.channelRef(channelName)
    val pending = state.pendingNames.remove(ref.storageKey).orEmpty()
    if (!context.hasBuffer(ref.storageKey)) return
    val channel = channelOrCreate(ref)
    channel.members.clear()
    channel.members.putAll(pending)
    channel.membersComplete = true
    for ((folded, member) in pending) state.users.getOrPut(folded) { UserState(member.nick) }
    publishMembers(channel)
}

internal fun Reduction.handleWhoXLine(message: IrcMessage) {
    if (isPlayback(message)) return
    if (message.parameters.size < 8) return
    val fields = message.parameters.drop(1)
    val channelName = fields[0]
    val nick = fields[3]
    val flags = fields[4]
    writeUser(nick) { presence ->
        whoFlags(presence, flags).copy(
            user = fields[1],
            host = fields[2],
            account = fields[5].takeIf { it != "0" },
            realName = fields[6],
        )
    }
    val channelRef = channelName.takeIf(state::isChannelName)?.let(state::channelRef)
    if (channelRef != null && context.hasBuffer(channelRef.storageKey)) {
        val ref = channelRef
        val channel = channelOrCreate(ref)
        state.pendingNames.getOrPut(ref.storageKey) { LinkedHashMap() }[state.fold(nick)] =
            ChannelMember(nick, state.prefixModes.symbols.firstOrNull { it in flags })
        val folded = state.fold(nick)
        if (folded !in channel.members) {
            channel.members[folded] = ChannelMember(nick, state.prefixModes.symbols.firstOrNull { it in flags })
        }
        publishMembers(channel)
    }
    publishChannelsContaining(nick, exceptRawTarget = channelName)
}

internal fun Reduction.whoFlags(presence: PresenceState, flags: String): PresenceState {
    val away = flags.contains('G')
    return presence.copy(
        away = away,
        awayMessage = if (away) presence.awayMessage else null,
        isBot = state.botModeLetter?.let { it in flags } ?: presence.isBot,
    )
}

internal fun Reduction.publishChannelsContaining(nick: String, exceptRawTarget: String? = null) {
    val folded = state.fold(nick)
    val skipKey = exceptRawTarget?.takeIf { state.isChannelName(it) }?.let { state.channelRef(it).storageKey }
    for (channel in state.channels.values) {
        if (channel.ref.storageKey == skipKey) continue
        if (folded in channel.members) publishMembers(channel)
    }
}

internal fun Reduction.addMember(ref: ConversationRef, member: ChannelMember) {
    val folded = state.fold(member.nick)
    val channel = channelOrCreate(ref)
    if (folded !in channel.members) {
        channel.members[folded] = member
        publishMembers(channel)
    }
    state.pendingNames[ref.storageKey]?.put(folded, member)
    if (folded !in state.users) state.users[folded] = UserState(member.nick)
}

internal fun Reduction.removeMember(nick: String, storageKey: String?) {
    val folded = state.fold(nick)
    for (channel in state.channels.values) {
        if (storageKey != null && channel.ref.storageKey != storageKey) continue
        if (channel.members.remove(folded) != null) publishMembers(channel)
    }
    for ((key, pending) in state.pendingNames) {
        if (storageKey != null && key != storageKey) continue
        pending.remove(folded)
    }
    if (!state.isTracked(folded)) state.users.remove(folded)
}
