package dev.brentdevs.yardhal.core.data

import java.io.File
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer

public class NetworkStore(directory: File) {

    private val file = File(directory, "networks.json")
    private val store = JsonFileStore(
        file = file,
        serializer = ListSerializer(NetworkConfig.serializer()),
    )

    @Volatile
    private var networks: MutableList<NetworkConfig> = store.loadOrDefault(mutableListOf()).toMutableList()

    public fun all(): List<NetworkConfig> = networks.toList()

    public fun byId(id: String): NetworkConfig? = networks.firstOrNull { it.id == id }

    public fun add(config: NetworkConfig): Boolean {
        if (networks.any { it.id == config.id }) return false
        networks.add(config)
        persist()
        return true
    }

    public fun update(config: NetworkConfig): Boolean {
        val index = networks.indexOfFirst { it.id == config.id }
        if (index < 0) return false
        networks[index] = config
        persist()
        return true
    }

    public fun remove(id: String): Boolean {
        val removed = networks.removeIf { it.id == id }
        if (removed) persist()
        return removed
    }

    public fun newId(): String = UUID.randomUUID().toString()

    private fun persist() {
        store.save(networks.toList())
    }
}
