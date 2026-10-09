package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlinx.serialization.Serializable

@Serializable
public data class ChannelGroup(
    public val id: String,
    public val name: String,
    public val memberKeys: List<String> = emptyList(),
)

@Serializable
public data class ChannelOrderState(
    public val pinnedKeys: List<String> = emptyList(),
    public val groups: List<ChannelGroup> = emptyList(),
    public val groupOrder: List<String> = emptyList(),
    public val partedKeys: List<String> = emptyList(),
) {
    public fun groupOf(key: String): ChannelGroup? = groups.firstOrNull { key in it.memberKeys }

    public fun isParted(key: String): Boolean = key in partedKeys
}

public class ChannelOrderStore(directory: File) {

    private val file = File(directory, "channel-order.json")
    private val store = JsonFileStore(
        file = file,
        serializer = ChannelOrderState.serializer(),
    )

    private var state: ChannelOrderState = store.loadOrDefault(ChannelOrderState())

    @Synchronized
    public fun snapshot(): ChannelOrderState = state

    @Synchronized
    public fun togglePin(key: String) {
        state = if (key in state.pinnedKeys) {
            state.copy(pinnedKeys = state.pinnedKeys - key)
        } else {
            state.copy(pinnedKeys = state.pinnedKeys + key)
        }
        persist()
    }

    @Synchronized
    public fun createGroup(id: String, name: String) {
        if (state.groups.any { it.id == id }) return
        state = state.copy(
            groups = state.groups + ChannelGroup(id = id, name = name),
            groupOrder = state.groupOrder + id,
        )
        persist()
    }

    @Synchronized
    public fun renameGroup(id: String, name: String) {
        state = state.copy(
            groups = state.groups.map { if (it.id == id) it.copy(name = name) else it },
        )
        persist()
    }

    @Synchronized
    public fun deleteGroup(id: String) {
        state = state.copy(
            groups = state.groups.filterNot { it.id == id },
            groupOrder = state.groupOrder - id,
        )
        persist()
    }

    @Synchronized
    public fun addToGroup(groupId: String, key: String) {
        state = state.copy(
            groups = state.groups.map { group ->
                when {
                    group.id != groupId -> group
                    key in group.memberKeys -> group
                    else -> group.copy(memberKeys = group.memberKeys + key)
                }
            },
        )
        persist()
    }

    @Synchronized
    public fun removeFromEveryGroup(key: String) {
        state = state.copy(
            groups = state.groups.map { group ->
                if (key in group.memberKeys) group.copy(memberKeys = group.memberKeys - key) else group
            },
        )
        persist()
    }

    @Synchronized
    public fun markParted(key: String) {
        if (key in state.partedKeys) return
        state = state.copy(partedKeys = state.partedKeys + key)
        persist()
    }

    @Synchronized
    public fun clearParted(key: String) {
        if (key !in state.partedKeys) return
        state = state.copy(partedKeys = state.partedKeys - key)
        persist()
    }

    @Synchronized
    public fun isParted(key: String): Boolean = state.isParted(key)

    @Synchronized
    public fun forget(key: String) {
        state = state.copy(
            pinnedKeys = state.pinnedKeys - key,
            groups = state.groups.map { group ->
                if (key in group.memberKeys) group.copy(memberKeys = group.memberKeys - key) else group
            },
            partedKeys = state.partedKeys - key,
        )
        persist()
    }

    @Synchronized
    public fun forgetNetwork(networkId: String) {
        val prefix = "$networkId|"
        val next = state.copy(
            pinnedKeys = state.pinnedKeys.filterNot { it.startsWith(prefix) },
            groups = state.groups.map { it.copy(memberKeys = it.memberKeys.filterNot { key -> key.startsWith(prefix) }) },
            partedKeys = state.partedKeys.filterNot { it.startsWith(prefix) },
        )
        if (next == state) return
        store.save(next)
        state = next
    }

    @Synchronized
    public fun rename(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        fun List<String>.renamed(): List<String> = map { if (it == fromKey) toKey else it }.distinct()
        var assigned = false
        state = state.copy(
            pinnedKeys = state.pinnedKeys.renamed(),
            groups = state.groups.map { group ->
                group.copy(memberKeys = group.memberKeys.renamed().filter { key ->
                    if (key != toKey) {
                        true
                    } else if (assigned) {
                        false
                    } else {
                        assigned = true
                        true
                    }
                })
            },
            partedKeys = state.partedKeys.renamed(),
        )
        persist()
    }

    private fun persist() {
        store.save(state)
    }
}
