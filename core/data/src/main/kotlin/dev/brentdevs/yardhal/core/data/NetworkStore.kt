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

    private val mutationLock = Any()

    @Volatile
    private var networks: List<NetworkConfig> = store.loadOrDefault(emptyList()).toList()

    public fun all(): List<NetworkConfig> = networks.toList()

    public fun byId(id: String): NetworkConfig? = networks.firstOrNull { it.id == id }

    public fun add(config: NetworkConfig): Boolean = synchronized(mutationLock) {
        if (networks.any { it.id == config.id }) return@synchronized false
        persist(networks + config)
    }

    public fun update(config: NetworkConfig): Boolean = synchronized(mutationLock) {
        val index = networks.indexOfFirst { it.id == config.id }
        if (index < 0) return@synchronized false
        val next = networks.toMutableList()
        next[index] = config
        persist(next)
    }

    public fun setUserDisconnected(id: String, disconnected: Boolean): Boolean = synchronized(mutationLock) {
        val index = networks.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        if (networks[index].userDisconnected == disconnected) return@synchronized true
        val next = networks.toMutableList()
        next[index] = next[index].copy(userDisconnected = disconnected)
        persist(next)
    }

    public fun remove(id: String): Boolean = synchronized(mutationLock) {
        val next = networks.filterNot { it.id == id }
        if (next.size == networks.size) return@synchronized false
        persist(next)
    }

    public fun deleteUnreferencedPasswords(references: Collection<String>, vault: CredentialVault): Unit =
        synchronized(mutationLock) {
            for (reference in references.distinct()) {
                val retained = networks.any { config ->
                    config.saslPasswordRef == reference || config.serverPasswordRef == reference ||
                        config.nickServPasswordRef == reference || config.proxy?.passwordRef == reference
                }
                if (!retained) vault.deletePassword(reference)
            }
        }

    public fun newId(): String = UUID.randomUUID().toString()

    private fun persist(next: List<NetworkConfig>): Boolean {
        return try {
            store.save(next)
            networks = next
            true
        } catch (_: Exception) {
            false
        }
    }
}
