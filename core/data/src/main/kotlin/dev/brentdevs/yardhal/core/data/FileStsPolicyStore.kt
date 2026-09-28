package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.client.StsPolicy
import dev.brentdevs.yardhal.core.client.StsPolicyStore
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
private data class StoredStsPolicy(
    val port: Int,
    val expiresAtEpochSeconds: Long,
    val durationSeconds: Long,
) {
    fun toPolicy(): StsPolicy = StsPolicy(port, expiresAtEpochSeconds, durationSeconds)
}

public class FileStsPolicyStore(directory: File) : StsPolicyStore {
    private val store = JsonFileStore(
        file = File(directory, "sts-policies.json"),
        serializer = MapSerializer(String.serializer(), StoredStsPolicy.serializer()),
    )
    private val policies = store.loadOrDefault(emptyMap()).toMutableMap()

    @Synchronized
    override fun load(host: String): StsPolicy? = policies[host.lowercase()]?.toPolicy()

    @Synchronized
    override fun save(host: String, policy: StsPolicy) {
        policies[host.lowercase()] = StoredStsPolicy(
            policy.port,
            policy.expiresAtEpochSeconds,
            policy.durationSeconds,
        )
        store.save(policies.toMap())
    }

    @Synchronized
    override fun delete(host: String) {
        if (policies.remove(host.lowercase()) != null) store.save(policies.toMap())
    }
}
