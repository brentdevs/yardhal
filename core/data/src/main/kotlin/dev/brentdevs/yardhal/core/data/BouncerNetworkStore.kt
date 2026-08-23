package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks

public class BouncerNetworkStore {

    private val networks = LinkedHashMap<String, IrcBouncerNetworks.Attributes>()

    @Volatile
    public var boundNetId: String? = null
        private set

    public fun bind(netId: String?) {
        boundNetId = netId
    }

    @Synchronized
    public fun apply(update: IrcBouncerNetworks.NetworkUpdate): Boolean {
        val change = update.change
        return when (change) {
            is IrcBouncerNetworks.Change.Upsert -> {
                val existing = networks[update.netId]
                val merged = existing?.merged(change.attributes) ?: change.attributes
                if (existing == merged) false else {
                    networks[update.netId] = merged
                    true
                }
            }
            is IrcBouncerNetworks.Change.Deleted -> networks.remove(update.netId) != null
        }
    }

    @Synchronized
    public fun get(netId: String): IrcBouncerNetworks.Attributes? = networks[netId]

    @Synchronized
    public fun all(): List<Pair<String, IrcBouncerNetworks.Attributes>> =
        networks.entries
            .map { it.key to it.value }
            .sortedBy { (_, attrs) -> attrs.name?.lowercase() ?: "" }

    @Synchronized
    public fun clear() {
        networks.clear()
        boundNetId = null
    }
}
