package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.RelayFormat
import dev.brentdevs.yardhal.core.protocol.RelayParser
import dev.brentdevs.yardhal.core.protocol.RelayedMessage
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
public data class RelayConfiguration(public val wireSender: String, public val format: String)

@Serializable
private data class RelayConfigurations(val networks: Map<String, List<RelayConfiguration>> = emptyMap())

public class RelayConfigurationStore(directory: File) {
    private val store = JsonFileStore(File(directory, "relay-configurations.json"), RelayConfigurations.serializer())
    private var state = store.loadOrDefault(RelayConfigurations())

    @Synchronized
    public fun forNetwork(networkId: String): List<RelayConfiguration> = state.networks[networkId].orEmpty()

    @Synchronized
    public fun update(networkId: String, configurations: List<RelayConfiguration>) {
        require(configurations.size <= 64)
        require(configurations.all { item ->
            item.wireSender.isNotBlank() && item.wireSender.length <= 64 &&
                item.wireSender.none { it.isWhitespace() || it.isISOControl() || it in "*!@?,:" } &&
                RelayFormat.entries.any { it.name == item.format }
        })
        val next = state.copy(networks = state.networks + (networkId to configurations.distinctBy { it.wireSender }))
        store.save(next)
        state = next
    }

    @Synchronized
    public fun upsert(networkId: String, configuration: RelayConfiguration, mapping: CaseMapping) {
        update(networkId, forNetwork(networkId).filterNot {
            mapping.equal(it.wireSender, configuration.wireSender)
        } + configuration)
    }

    public fun parse(networkId: String, sender: String, text: String, mapping: CaseMapping): RelayedMessage? =
        forNetwork(networkId).firstNotNullOfOrNull { configuration ->
            RelayFormat.entries.firstOrNull { it.name == configuration.format }?.let { format ->
                RelayParser.parse(sender, text, configuration.wireSender, format, mapping)
            }
        }
}
