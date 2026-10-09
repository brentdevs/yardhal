package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.BouncerServCommand
import dev.brentdevs.yardhal.core.data.NetworkMode
import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks

public enum class BouncerServiceAvailability { UNKNOWN, PROBING, AVAILABLE, UNAVAILABLE, UNSUPPORTED }

public enum class BouncerOperationStatus { RUNNING, SUCCESS, ERROR, PARTIAL }

public enum class ZncConnectionAction { CONNECT, DISCONNECT, RECONNECT }

public data class BouncerOperationOutcome(
    public val status: BouncerOperationStatus,
    public val applied: Int = 0,
    public val total: Int = 0,
    public val error: String? = null,
    public val refreshError: String? = null,
)

public data class SojuChannelSettings(
    public val name: String,
    public val status: String = "",
    public val detached: Boolean? = null,
    public val detachAfterSeconds: Long? = null,
    public val relayDetached: BouncerServCommand.RelayMode? = null,
    public val reattachOn: BouncerServCommand.RelayMode? = null,
)

public data class BouncerAccountState(
    public val networkId: String,
    public val generation: Long,
    public val mode: NetworkMode,
    public val connected: Boolean = false,
    public val capabilities: Set<String> = emptySet(),
    public val username: String = "",
    public val boundNetwork: String? = null,
    public val sojuService: BouncerServiceAvailability = BouncerServiceAvailability.UNKNOWN,
    public val zncStatus: BouncerServiceAvailability = BouncerServiceAvailability.UNKNOWN,
    public val zncControlPanel: BouncerServiceAvailability = BouncerServiceAvailability.UNKNOWN,
    public val sojuNetworks: Map<String, IrcBouncerNetworks.Attributes> = emptyMap(),
    public val sojuChannels: Map<String, SojuChannelSettings> = emptyMap(),
    public val zncNetworks: List<dev.brentdevs.yardhal.core.data.ZncNetwork> = emptyList(),
    public val zncNetworkDetails: Map<String, dev.brentdevs.yardhal.core.data.ZncNetworkDraft> = emptyMap(),
    public val zncChannels: Map<String, dev.brentdevs.yardhal.core.data.ZncChannelDraft> = emptyMap(),
    public val operation: BouncerOperationOutcome? = null,
)
