package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class NetworkStoreTests {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): NetworkStore = NetworkStore(tmp.root)

    @Test
    fun addAndRetrieve() {
        val store = store()
        val config = NetworkConfig(id = "n1", name = "Libera", host = "irc.libera.chat", nick = "tester")
        assertTrue(store.add(config))
        assertEquals(config, store.byId("n1"))
        assertFalse(store.add(config.copy(name = "dup")))
        assertEquals(1, store.all().size)
    }

    @Test
    fun persistsAcrossInstances() {
        val first = store()
        first.add(NetworkConfig(id = "n1", name = "Libera", host = "irc.libera.chat", nick = "tester"))
        first.update(NetworkConfig(id = "n1", name = "Renamed", host = "irc.libera.chat", nick = "tester"))

        val second = NetworkStore(tmp.root)
        assertEquals("Renamed", second.byId("n1")!!.name)
    }

    @Test
    fun removePersists() {
        val first = store()
        first.add(NetworkConfig(id = "n1", name = "x", host = "h", nick = "n"))
        assertTrue(first.remove("n1"))

        val second = NetworkStore(tmp.root)
        assertNull(second.byId("n1"))
        assertTrue(second.all().isEmpty())
    }

    @Test
    fun saslPasswordStoredByReferenceOnly() {
        val config = NetworkConfig(
            id = "n1",
            name = "x",
            host = "h",
            nick = "n",
            saslAuthcid = "alice",
            saslPasswordRef = "cred-n1",
        )
        assertTrue(store().add(config))
        val text = File(tmp.root, "networks.json").readText()
        assertFalse(text.contains("secret"), "plaintext secrets must never hit disk")
        assertEquals("cred-n1", NetworkStore(tmp.root).byId("n1")!!.saslPasswordRef)
    }
}
