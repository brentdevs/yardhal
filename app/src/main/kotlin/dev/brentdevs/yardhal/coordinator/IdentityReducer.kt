package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcPrefix

internal const val WHOX_QUERY: String = "%cuhnfar"

private const val ACCOUNT_TAG_CAP = "account-tag"
private const val AWAY_NOTIFY_CAP = "away-notify"

internal fun accountValue(raw: String?): String? = raw?.takeIf { it.isNotEmpty() && it != "*" }

internal fun Reduction.updateTrackedUser(nick: String, transform: (PresenceState) -> PresenceState) {
    val folded = state.fold(nick)
    if (!state.isTracked(folded)) return
    val existing = state.users[folded] ?: UserState(nick)
    val updated = transform(existing.presence)
    if (state.users[folded] != null && updated == existing.presence) return
    state.users[folded] = existing.copy(presence = updated)
    changedUsers.add(folded)
}

internal fun Reduction.writeUser(nick: String, transform: (PresenceState) -> PresenceState) {
    val folded = state.fold(nick)
    val existing = state.users[folded] ?: UserState(nick)
    state.users[folded] = existing.copy(presence = transform(existing.presence))
}

internal fun Reduction.sourceIdentity(presence: PresenceState, prefix: IrcPrefix, message: IrcMessage): PresenceState =
    presence.copy(
        user = prefix.user ?: presence.user,
        host = prefix.host ?: presence.host,
        account = when {
            message.tags.containsKey("account") -> accountValue(message.tag("account"))
            ACCOUNT_TAG_CAP in state.supportedCaps && (
                message.command.equals("PRIVMSG", ignoreCase = true) ||
                    message.command.equals("NOTICE", ignoreCase = true) ||
                    message.command.equals("TAGMSG", ignoreCase = true)
                ) -> null
            else -> presence.account
        },
        isBot = presence.isBot || message.tags.containsKey("bot"),
    )

internal fun Reduction.observeSource(message: IrcMessage) {
    val prefix = message.prefix ?: return
    if (prefix.isServer || isPlayback(message)) return
    updateTrackedUser(prefix.nick) { sourceIdentity(it, prefix, message) }
}

internal fun Reduction.recordJoinIdentity(nick: String, message: IrcMessage) {
    val prefix = message.prefix ?: return
    val extended = message.parameters.size >= 3
    writeUser(nick) { presence ->
        val tagged = sourceIdentity(presence, prefix, message)
        tagged.copy(
            account = if (extended) accountValue(message.parameters[1]) else tagged.account,
            realName = if (extended) message.parameters[2] else tagged.realName,
            away = tagged.away ?: (false.takeIf { AWAY_NOTIFY_CAP in state.supportedCaps }),
        )
    }
}

internal fun Reduction.handleAway(message: IrcMessage) {
    val nick = message.prefix?.nick ?: return
    val awayMessage = message.parameters.firstOrNull()?.takeIf { it.isNotEmpty() }
    updateTrackedUser(nick) { it.copy(away = awayMessage != null, awayMessage = awayMessage) }
}

internal fun Reduction.handleAccount(message: IrcMessage) {
    val nick = message.prefix?.nick ?: return
    val raw = message.parameters.firstOrNull() ?: return
    updateTrackedUser(nick) { it.copy(account = accountValue(raw)) }
}

internal fun Reduction.handleChghost(message: IrcMessage) {
    val nick = message.prefix?.nick ?: return
    if (message.parameters.size < 2) return
    val user = message.parameters[0]
    val host = message.parameters[1]
    updateTrackedUser(nick) { it.copy(user = user, host = host) }
}

internal fun Reduction.handleSetname(message: IrcMessage) {
    val nick = message.prefix?.nick ?: return
    val realName = message.parameters.firstOrNull() ?: return
    updateTrackedUser(nick) { it.copy(realName = realName) }
    if (state.isOwnNick(nick)) system(state.server, "Your realname is now: $realName")
}

internal fun Reduction.handleInvite(message: IrcMessage) {
    if (message.parameters.size < 2) return
    val inviter = message.prefix?.nick ?: "server"
    val invitee = message.parameters[0]
    val channelName = message.parameters[1]
    if (state.isOwnNick(invitee)) {
        if (context.isIgnored(inviter)) return
        system(state.server, "✉ $inviter invited you to $channelName")
        if (state.isChannelName(channelName)) emit(InboundEffect.NotifyInvite(state.channelRef(channelName),
            inviter, context.nowMs, message.tag("msgid"), isPlayback(message) || "draft/chathistory-context" in message.tags))
        return
    }
    val ref = state.channelRef(channelName)
    system(if (context.hasBuffer(ref.storageKey)) ref else state.server, "✉ $inviter invited $invitee to $channelName")
}

internal fun Reduction.handleInviting(message: IrcMessage) {
    if (message.parameters.size < 3) return
    val invitee = message.parameters[1]
    val channelName = message.parameters[2]
    val ref = state.channelRef(channelName)
    system(if (context.hasBuffer(ref.storageKey)) ref else state.server, "✉ Invited $invitee to $channelName")
}

internal fun Reduction.handleMonitorStatus(numeric: Int, message: IrcMessage) {
    val targets = message.parameters.getOrNull(1).orEmpty().split(',').mapNotNull { IrcPrefix.parse(it) }
    for (target in targets) {
        val folded = state.fold(target.nick)
        state.monitored[folded] = target.nick
        when (numeric) {
            730 -> writeUser(target.nick) { it.copy(user = target.user ?: it.user, host = target.host ?: it.host) }
            731 -> if (state.channels.values.none { folded in it.members } && state.pendingNames.values.none { folded in it }) {
                state.users.remove(folded)
            }
        }
    }
    serverLine(message, if (numeric == 732) null else "monitor")
}

internal fun Reduction.handleWhoisBot(message: IrcMessage) {
    val target = message.parameters.getOrNull(1) ?: return
    updateTrackedUser(target) { it.copy(isBot = true) }
}
