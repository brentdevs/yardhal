package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.client.TlsClientIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class NetworkConfigValidationException(message: String) : IllegalArgumentException(message)

@Serializable
public data class NetworkConfig(
    public val id: String,
    public val name: String,
    public val host: String,
    public val port: Int = 6697,
    public val tls: Boolean = true,
    public val nick: String,
    public val username: String = "yardhal",
    public val realName: String = "Yardhal",
    public val autojoin: List<String> = emptyList(),
    public val saslAuthcid: String? = null,
    @SerialName("saslPasswordRef") public val saslPasswordRef: String? = null,
    public val serverPasswordRef: String? = null,
    @kotlinx.serialization.Transient public val saslPassword: String? = null,
    public val alternateNicks: List<String> = emptyList(),
    public val autoConnect: Boolean = true,
    public val userDisconnected: Boolean = false,
    public val saslMode: SaslMode = SaslMode.AUTO,
    public val nickServAccount: String? = null,
    public val nickServPasswordRef: String? = null,
    public val nickServService: String = "NickServ",
    public val waitForNickServ: Boolean = true,
    public val proxy: SocksProxyConfig? = null,
    public val tlsClientAlias: String? = null,
    public val certificatePin: CertificatePin? = null,
    @kotlinx.serialization.Transient public val serverPassword: String? = null,
    @kotlinx.serialization.Transient public val nickServPassword: String? = null,
    @kotlinx.serialization.Transient public val proxyPassword: String? = null,
    @kotlinx.serialization.Transient public val tlsClientIdentity: TlsClientIdentity? = null,
) {
    init {
        if (id.isBlank()) throw NetworkConfigValidationException("Network ID must not be blank")
        if (host.isBlank()) throw NetworkConfigValidationException("Network host must not be blank")
        if (nick.isBlank()) throw NetworkConfigValidationException("Network nickname must not be blank")
        if (port !in 1..65535) throw NetworkConfigValidationException("Network port must be between 1 and 65535")
    }

    override fun toString(): String =
        "NetworkConfig(id=$id, name=$name, host=$host, port=$port, tls=$tls, nick=$nick, saslMode=$saslMode)"
}

@Serializable
public enum class SaslMode { AUTO, PLAIN, SCRAM_SHA_256, EXTERNAL }

@Serializable
public data class SocksProxyConfig(
    public val host: String,
    public val port: Int = 1080,
    public val username: String? = null,
    public val passwordRef: String? = null,
) {
    init {
        if (host.isBlank()) throw NetworkConfigValidationException("Proxy host must not be blank")
        if (port !in 1..65535) throw NetworkConfigValidationException("Proxy port must be between 1 and 65535")
    }
}

@Serializable
public data class CertificatePin(
    public val host: String,
    public val port: Int,
    public val sha256: String,
) {
    init {
        if (host.isBlank()) throw NetworkConfigValidationException("Certificate pin host must not be blank")
        if (port !in 1..65535) throw NetworkConfigValidationException("Certificate pin port must be between 1 and 65535")
        if (sha256.length != 64 || sha256.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
            throw NetworkConfigValidationException("Certificate pin must be a SHA-256 hexadecimal digest")
        }
    }
}

public object NetworkPresets {

    public data class Preset(
        public val id: String,
        public val name: String,
        public val host: String,
        public val port: Int,
        public val tls: Boolean,
        public val channels: List<String> = emptyList(),
    )

    public val LIBERA: Preset = Preset(
        id = "libera",
        name = "Libera.Chat",
        host = "irc.libera.chat",
        port = 6697,
        tls = true,
        channels = listOf("#libera"),
    )

    public val OFTC: Preset = Preset(
        id = "oftc",
        name = "OFTC",
        host = "irc.oftc.net",
        port = 6697,
        tls = true,
    )

    public val ERGO_LOCAL: Preset = Preset(
        id = "ergo-local",
        name = "Ergo (emulator host)",
        host = "10.0.2.2",
        port = 16667,
        tls = false,
    )

    public val ALL: List<Preset> = listOf(LIBERA, OFTC, ERGO_LOCAL)

    public fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }

    public fun toNetworkConfig(preset: Preset, id: String, nick: String): NetworkConfig =
        NetworkConfig(
            id = id,
            name = preset.name,
            host = preset.host,
            port = preset.port,
            tls = preset.tls,
            nick = nick,
            autojoin = preset.channels,
        )
}
