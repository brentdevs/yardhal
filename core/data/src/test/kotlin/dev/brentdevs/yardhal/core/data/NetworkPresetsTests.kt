package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkPresetsTests {

    @Test
    fun presetsResolveById() {
        assertEquals(NetworkPresets.LIBERA, NetworkPresets.byId("libera"))
        assertNull(NetworkPresets.byId("nope"))
        assertTrue(NetworkPresets.ALL.size >= 3)
    }

    @Test
    fun presetToConfig() {
        val config = NetworkPresets.toNetworkConfig(
            NetworkPresets.LIBERA,
            id = "abc",
            nick = "tester",
        )
        assertEquals("irc.libera.chat", config.host)
        assertEquals(6697, config.port)
        assertTrue(config.tls)
        assertNotNull(config.id)
        assertNull(config.saslAuthcid)
    }
}
