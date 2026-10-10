package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlinx.serialization.Serializable

@Serializable
public data class ChannelGroup(
    public val id: String,
    public val name: String,
    public val memberKeys: List<String> = emptyList(),
)

public enum class OrderKind { NETWORK, CHANNEL, GROUP }

public data class OrderMove(
    public val kind: OrderKind,
    public val key: String,
    public val peers: List<String>,
    public val destinationIndex: Int,
)

@Serializable
public data class ChannelOrderState(
    public val pinnedKeys: List<String> = emptyList(),
    public val groups: List<ChannelGroup> = emptyList(),
    public val groupOrder: List<String> = emptyList(),
    public val partedKeys: List<String> = emptyList(),
    public val networkOrder: List<String> = emptyList(),
    public val channelOrder: List<String> = emptyList(),
    public val sortUnreadFirst: Boolean = false,
) {
    public fun groupOf(key: String): ChannelGroup? = groups.firstOrNull { key in it.memberKeys }

    public fun isParted(key: String): Boolean = key in partedKeys
}

public class ChannelOrderStore(directory: File) {
    private val store = JsonFileStore(File(directory, "channel-order.json"), ChannelOrderState.serializer())
    private var state: ChannelOrderState = store.loadOrDefault(ChannelOrderState())

    @Synchronized
    public fun snapshot(): ChannelOrderState = state

    @Synchronized
    public fun togglePin(key: String) {
        publish(state.copy(pinnedKeys = if (key in state.pinnedKeys) state.pinnedKeys - key else state.pinnedKeys + key))
    }

    @Synchronized
    public fun createGroup(id: String, name: String) {
        if (state.groups.any { it.id == id }) return
        publish(state.copy(groups = state.groups + ChannelGroup(id, name), groupOrder = state.groupOrder + id))
    }

    @Synchronized
    public fun renameGroup(id: String, name: String) {
        publish(state.copy(groups = state.groups.map { if (it.id == id) it.copy(name = name) else it }))
    }

    @Synchronized
    public fun deleteGroup(id: String) {
        publish(state.copy(groups = state.groups.filterNot { it.id == id }, groupOrder = state.groupOrder - id))
    }

    @Synchronized
    public fun addToGroup(groupId: String, key: String) {
        publish(state.copy(groups = state.groups.map { group ->
            if (group.id == groupId && key !in group.memberKeys) group.copy(memberKeys = group.memberKeys + key) else group
        }))
    }

    @Synchronized
    public fun moveToGroup(key: String, groupId: String?) {
        require(groupId == null || state.groups.any { it.id == groupId }) { "The destination group no longer exists." }
        publish(state.copy(groups = state.groups.map { group ->
            val members = if (group.id == groupId) {
                if (key in group.memberKeys) group.memberKeys else group.memberKeys + key
            } else {
                group.memberKeys - key
            }
            group.copy(memberKeys = members)
        }))
    }

    @Synchronized
    public fun removeFromEveryGroup(key: String) {
        moveToGroup(key, null)
    }

    @Synchronized
    public fun markParted(key: String) {
        if (key !in state.partedKeys) publish(state.copy(partedKeys = state.partedKeys + key))
    }

    @Synchronized
    public fun clearParted(key: String) {
        if (key in state.partedKeys) publish(state.copy(partedKeys = state.partedKeys - key))
    }

    @Synchronized
    public fun isParted(key: String): Boolean = state.isParted(key)

    @Synchronized
    public fun setSortUnreadFirst(enabled: Boolean) {
        publish(state.copy(sortUnreadFirst = enabled))
    }

    @Synchronized
    public fun move(move: OrderMove) {
        val peerSet = move.peers.toSet()
        require(peerSet.size == move.peers.size && move.key in peerSet) { "The reorder list has changed. Reopen ordering and try again." }
        require(move.destinationIndex in move.peers.indices) { "The reorder destination is no longer available." }
        val peers = move.peers.toMutableList()
        peers.remove(move.key)
        peers.add(move.destinationIndex, move.key)
        fun reordered(existing: List<String>): List<String> {
            val complete = (existing + move.peers).distinct()
            var index = 0
            return complete.map { if (it in peerSet) peers[index++] else it }
        }
        val next = when (move.kind) {
            OrderKind.NETWORK -> state.copy(networkOrder = reordered(state.networkOrder))
            OrderKind.GROUP -> {
                require(move.peers.all { id -> state.groups.any { it.id == id } }) { "A reordered group no longer exists." }
                state.copy(groupOrder = reordered(state.groupOrder))
            }
            OrderKind.CHANNEL -> {
                val network = move.key.substringBefore('|')
                val pinned = move.key in state.pinnedKeys
                val groupId = state.groupOf(move.key)?.id
                require(move.peers.all { key ->
                    key.contains('|') && key.substringBefore('|') == network &&
                        (key in state.pinnedKeys) == pinned && (pinned || state.groupOf(key)?.id == groupId)
                }) { "Reorder within the same network and pinned or group section." }
                when {
                    pinned -> state.copy(pinnedKeys = reordered(state.pinnedKeys), sortUnreadFirst = false)
                    groupId != null -> state.copy(groups = state.groups.map { group ->
                        if (group.id == groupId) group.copy(memberKeys = reordered(group.memberKeys)) else group
                    }, sortUnreadFirst = false)
                    else -> state.copy(channelOrder = reordered(state.channelOrder), sortUnreadFirst = false)
                }
            }
        }
        publish(next)
    }

    @Synchronized
    public fun forget(key: String) {
        publish(state.copy(
            pinnedKeys = state.pinnedKeys - key,
            groups = state.groups.map { it.copy(memberKeys = it.memberKeys - key) },
            partedKeys = state.partedKeys - key,
            channelOrder = state.channelOrder - key,
        ))
    }

    @Synchronized
    public fun forgetNetwork(networkId: String) {
        val prefix = "$networkId|"
        publish(state.copy(
            pinnedKeys = state.pinnedKeys.filterNot { it.startsWith(prefix) },
            groups = state.groups.map { it.copy(memberKeys = it.memberKeys.filterNot { key -> key.startsWith(prefix) }) },
            partedKeys = state.partedKeys.filterNot { it.startsWith(prefix) },
            channelOrder = state.channelOrder.filterNot { it.startsWith(prefix) },
            networkOrder = state.networkOrder - networkId,
        ))
    }

    @Synchronized
    public fun rename(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        fun List<String>.renamed(): List<String> = map { if (it == fromKey) toKey else it }.distinct()
        var assigned = false
        publish(state.copy(
            pinnedKeys = state.pinnedKeys.renamed(),
            groups = state.groups.map { group ->
                group.copy(memberKeys = group.memberKeys.renamed().filter { key ->
                    if (key != toKey) true else if (assigned) false else { assigned = true; true }
                })
            },
            partedKeys = state.partedKeys.renamed(),
            channelOrder = state.channelOrder.renamed(),
        ))
    }

    private fun publish(next: ChannelOrderState) {
        if (next == state) return
        store.save(next)
        state = next
    }
}
