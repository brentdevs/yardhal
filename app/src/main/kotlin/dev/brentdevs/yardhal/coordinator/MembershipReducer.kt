package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ChannelMember
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.NamesParser
import dev.brentdevs.yardhal.core.protocol.IrcMessage

internal fun Reduction.handleJoin(message: IrcMessage) {
    val nick = message.prefix?.nick ?: return
    val channelName = message.parameters.firstOrNull() ?: return
    val ref = state.channelRef(channelName)
    val fromUs = state.isOwnNick(nick)
    if (!fromUs) addMember(ref, ChannelMember(nick))
    if (!fromUs && state.netsplit.isSuppressing("JOIN")) {
        state.netsplit.recordSuppressed("JOIN")
        return
    }
    if (fromUs) {
        channelOrCreate(ref)
        emit(InboundEffect.EnsureBuffer(ref))
        emit(InboundEffect.SendRaw("TOPIC $channelName"))
        emit(InboundEffect.SendRaw("MODE $channelName"))
        requestChathistory(ref)
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
        publishMembers(channel)
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

internal fun Reduction.accumulateNames(message: IrcMessage) {
    if (message.parameters.size < 2) return
    val (channelName, members) = NamesParser.parseNamesLine(message.parameters.drop(1), state.prefixModes)
    val ref = state.channelRef(channelName ?: return)
    val pending = state.pendingNames.getOrPut(ref.storageKey) { LinkedHashMap() }
    for (member in members) pending[state.fold(member.nick)] = member
}

internal fun Reduction.accumulateWhoLine(message: IrcMessage) {
    if (message.parameters.size < 7) return
    val ref = state.channelRef(message.parameters[1])
    val nick = message.parameters[5].substringBefore('!').substringBefore('@')
    val flags = message.parameters[6]
    val symbol = state.prefixModes.symbols.firstOrNull { it in flags }
    state.pendingNames.getOrPut(ref.storageKey) { LinkedHashMap() }[state.fold(nick)] = ChannelMember(nick, symbol)
    updatePresence(nick, away = flags.contains('G'), account = null, keepAccount = true)
}

internal fun Reduction.finalizeNames(message: IrcMessage) {
    if (message.parameters.size < 2) return
    val ref = state.channelRef(message.parameters[1])
    val pending = state.pendingNames.remove(ref.storageKey) ?: return
    if (!context.hasBuffer(ref.storageKey)) return
    val channel = channelOrCreate(ref)
    channel.members.clear()
    channel.members.putAll(pending)
    publishMembers(channel)
    emit(InboundEffect.SetJoinState(ref, JoinState.JOINED))
}

internal fun Reduction.handleWhoXLine(message: IrcMessage) {
    if (message.parameters.size < 6) return
    val fields = message.parameters.drop(1)
    val channelName = fields[0]
    val nick = fields[2]
    val flags = fields[3]
    val account = fields[4].takeIf { it != "0" }
    updatePresence(nick, away = flags.contains('G'), account = account, keepAccount = false)
    if (state.isChannelName(channelName)) {
        val ref = state.channelRef(channelName)
        val channel = channelOrCreate(ref)
        val folded = state.fold(nick)
        if (folded !in channel.members) {
            channel.members[folded] = ChannelMember(nick, state.prefixModes.symbols.firstOrNull { it in flags })
        }
        publishMembers(channel)
    }
    publishChannelsContaining(nick, exceptRawTarget = channelName)
}

internal fun Reduction.updatePresence(nick: String, away: Boolean, account: String?, keepAccount: Boolean) {
    val folded = state.fold(nick)
    val existing = state.users[folded] ?: UserState(nick)
    val resolvedAccount = if (keepAccount) existing.presence?.account else account
    state.users[folded] = existing.copy(presence = PresenceState(away = away, account = resolvedAccount))
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
    if (state.channels.values.none { folded in it.members }) state.users.remove(folded)
}
