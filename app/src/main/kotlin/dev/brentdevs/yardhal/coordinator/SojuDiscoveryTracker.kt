package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks
import dev.brentdevs.yardhal.core.protocol.IrcMessage

internal sealed interface SojuDiscoveryChange {
    data class Upsert(val netId: String, val attributes: IrcBouncerNetworks.Attributes) : SojuDiscoveryChange
    data class Delete(val netId: String) : SojuDiscoveryChange
    data class Snapshot(val netIds: Set<String>) : SojuDiscoveryChange
}

internal class SojuDiscoveryTracker {
    private data class Batch(val type: String, val parent: String?, val seen: MutableSet<String> = linkedSetOf(), var valid: Boolean = true)
    private val batches = linkedMapOf<String, Batch>()
    private val known = linkedMapOf<String, IrcBouncerNetworks.Attributes>()
    private var generation = Long.MIN_VALUE

    fun begin(generation: Long, restored: Map<String, IrcBouncerNetworks.Attributes> = emptyMap()) {
        this.generation = generation
        batches.clear()
        known.clear()
        known.putAll(restored)
    }

    fun receive(generation: Long, message: IrcMessage): List<SojuDiscoveryChange> {
        if (this.generation != generation || message.prefix?.user != null || "draft/chathistory-context" in message.tags) return emptyList()
        if (message.command == "BATCH") {
            val head = message.parameters.firstOrNull() ?: return emptyList()
            val reference = head.drop(1)
            if (reference.isEmpty()) return emptyList()
            if (head.startsWith('+')) {
                val type = message.parameters.getOrNull(1) ?: return emptyList()
                if (reference in batches) {
                    batches.values.forEach { it.valid = false }
                    return emptyList()
                }
                val parent = message.tag("batch")
                val overlap = type == IrcBouncerNetworks.BATCH_TYPE && !playback(parent) &&
                    batches.values.any { it.type == IrcBouncerNetworks.BATCH_TYPE && !playback(it.parent) }
                if (overlap) batches.values.forEach { if (it.type == IrcBouncerNetworks.BATCH_TYPE) it.valid = false }
                batches[reference] = Batch(type, parent, valid = !overlap)
                return emptyList()
            }
            if (head.startsWith('-')) {
                val batch = batches.remove(reference) ?: return emptyList()
                val incomplete = batches.values.any { it.parent == reference }
                if (incomplete) batches.values.forEach { it.valid = false }
                if (batch.type != IrcBouncerNetworks.BATCH_TYPE || !batch.valid || incomplete || playback(batch.parent)) return emptyList()
                known.keys.retainAll(batch.seen)
                return listOf(SojuDiscoveryChange.Snapshot(batch.seen.toSet()))
            }
            return emptyList()
        }
        if (message.command != "BOUNCER") return emptyList()
        val reference = message.tag("batch")
        if (reference != null && reference !in batches) {
            if (message.parameters.firstOrNull().equals("NETWORK", true)) {
                batches.values.forEach { if (it.type == IrcBouncerNetworks.BATCH_TYPE) it.valid = false }
            }
            return emptyList()
        }
        if (playback(reference)) return emptyList()
        val update = IrcBouncerNetworks.parseNetwork(message.parameters)
        if (update == null || update.netId.any { it.isWhitespace() || it in "\u0000\r\n:" }) {
            if (message.parameters.firstOrNull().equals("NETWORK", true)) {
                snapshots(reference).forEach { it.valid = false }
            }
            return emptyList()
        }
        return when (val change = update.change) {
            is IrcBouncerNetworks.Change.Upsert -> {
                val merged = (known[update.netId] ?: IrcBouncerNetworks.Attributes()).merged(change.attributes)
                known[update.netId] = merged
                batches.values.forEach {
                    if (it.type == IrcBouncerNetworks.BATCH_TYPE && !playback(it.parent)) it.seen.add(update.netId)
                }
                listOf(SojuDiscoveryChange.Upsert(update.netId, merged))
            }
            IrcBouncerNetworks.Change.Deleted -> {
                known.remove(update.netId)
                batches.values.forEach { it.seen.remove(update.netId) }
                listOf(SojuDiscoveryChange.Delete(update.netId))
            }
        }
    }

    private fun snapshots(reference: String?): List<Batch> {
        val result = mutableListOf<Batch>()
        var current = reference
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current)) {
            val batch = batches[current] ?: break
            if (batch.type == IrcBouncerNetworks.BATCH_TYPE) result.add(batch)
            current = batch.parent
        }
        return result
    }

    private fun playback(reference: String?): Boolean {
        var current = reference
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current)) {
            val batch = batches[current] ?: return true
            if (batch.type == "chathistory" || batch.type == "znc.in/playback") return true
            current = batch.parent
        }
        return false
    }
}
