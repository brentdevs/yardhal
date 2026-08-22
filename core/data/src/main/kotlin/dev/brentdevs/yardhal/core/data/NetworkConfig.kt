package dev.brentdevs.yardhal.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
) {
    init {
        require(id.isNotBlank())
        require(host.isNotBlank())
        require(nick.isNotBlank())
        require(port in 1..65535)
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
        name = "Ergo (localhost)",
        host = "127.0.0.1",
        port = 6667,
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
