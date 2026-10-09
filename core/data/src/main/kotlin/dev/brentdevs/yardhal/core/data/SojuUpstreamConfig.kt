package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks

public object SojuUpstreamConfig {
    public fun reconcile(
        parent: NetworkConfig,
        netId: String,
        attributes: IrcBouncerNetworks.Attributes,
        existing: NetworkConfig? = null,
    ): NetworkConfig {
        val previous = existing?.bouncerBinding
        val nickname = (if (attributes.nickname == null) previous?.nickname else attributes.nickname)
            ?.takeIf { it.isNotBlank() && it.none { character -> character.isWhitespace() || character in "\u0000\r\n:" } }
        val realName = (if (attributes.realname == null) previous?.realName else attributes.realname)
            ?.takeIf { it.isNotBlank() && it.none { character -> character in "\u0000\r\n" } }
        val enabled = when (attributes.unknown["enabled"]?.lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> previous?.enabled ?: true
        }
        return inherit(parent, (existing ?: parent.copy(
            id = DerivedNetworkIdentity.sojuNetworkId(parent.id, netId),
            name = "soju network $netId",
            autojoin = emptyList(),
            userDisconnected = false,
            autoConnect = parent.autoConnect,
            mode = NetworkMode.SOJU,
            zncNetwork = null,
            bouncerBinding = BouncerBinding(parent.id, netId),
        )).copy(
            name = attributes.name?.takeIf(String::isNotBlank) ?: existing?.name ?: "soju network $netId",
            bouncerBinding = BouncerBinding(parent.id, netId, enabled, nickname, realName, previous?.rejectionReason),
        ))
    }

    public fun inherit(parent: NetworkConfig, upstream: NetworkConfig): NetworkConfig {
        val binding = requireNotNull(upstream.bouncerBinding)
        require(binding.parentId == parent.id)
        return parent.copy(
            id = upstream.id,
            name = upstream.name,
            nick = binding.nickname ?: parent.nick,
            realName = binding.realName ?: parent.realName,
            autojoin = upstream.autojoin,
            alternateNicks = emptyList(),
            autoConnect = upstream.autoConnect,
            userDisconnected = upstream.userDisconnected,
            nickServAccount = null,
            nickServPasswordRef = null,
            nickServPassword = null,
            waitForNickServ = false,
            mode = NetworkMode.SOJU,
            bouncerBinding = binding,
            zncNetwork = null,
        )
    }
}
