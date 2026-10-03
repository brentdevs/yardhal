package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class MetadataCapabilityLoopbackTests {

    @org.junit.jupiter.api.Test
    fun negotiatedEventCarriesAdvertisedMetadataLimits() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                when {
                    line.startsWith("CAP LS") ->
                        server.sendLine(":srv CAP * LS :batch draft/metadata-2=before-connect,max-subs=2 server-time=ignored")
                    line.startsWith("CAP REQ :") -> server.sendLine(":srv CAP * ACK :${line.removePrefix("CAP REQ :")}")
                    line.startsWith("USER") -> {
                        server.sendLine(":srv 001 yardhal-test :Welcome")
                        server.sendLine(":srv BATCH +r1 metadata yardhal-test")
                        server.sendLine("@batch=r1 :srv METADATA yardhal-test display-name * :Yardhal Test")
                        server.sendLine(":srv BATCH -r1")
                    }
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val connection = IrcConnection(
                    IrcConnectionConfig(
                        host = "127.0.0.1",
                        port = server.port,
                        tls = false,
                        nick = "yardhal-test",
                        capabilities = IrcConnectionConfig.DEFAULT_CAPABILITIES - CapabilityNegotiator.SASL_CAP,
                        connectTimeoutMillis = 3_000,
                    ),
                )
                val collector = EventCollector(scope, connection.events)
                connection.start()
                server.awaitClient()

                val events = collector.drainUntilRegistered()
                val negotiated = events.filterIsInstance<IrcEvent.CapabilitiesNegotiated>().single()
                assertTrue("draft/metadata-2" in negotiated.capabilities)
                assertEquals(
                    mapOf("draft/metadata-2" to "before-connect,max-subs=2", "server-time" to "ignored"),
                    negotiated.values,
                )
                assertTrue(server.receivedLines.any { it.startsWith("CAP REQ :") && "draft/metadata-2" in it })

                val received = ArrayList<IrcMessage>()
                while (received.none { it.command == "BATCH" && it.parameters.firstOrNull() == "-r1" }) {
                    val event = collector.await()
                    if (event is IrcEvent.MessageReceived) received.add(event.message)
                }
                val metadata = received.single { it.command == "METADATA" }
                assertEquals("r1", metadata.tag("batch"))
                assertEquals(listOf("yardhal-test", "display-name", "*", "Yardhal Test"), metadata.parameters)
                connection.disconnect()
            } finally {
                scope.cancel()
            }
        }
        Unit
    }

    @org.junit.jupiter.api.Test
    fun runtimeCapabilityUpdatesPublishChangedValuesAndWithdrawnFeatures() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                when {
                    line.startsWith("CAP LS") -> server.sendLine(
                        ":srv CAP * LS :batch echo-message labeled-response draft/metadata-2=max-subs=2",
                    )
                    line.startsWith("CAP REQ :") ->
                        server.sendLine(":srv CAP * ACK :${line.removePrefix("CAP REQ :")}")
                    line.startsWith("USER") -> server.sendLine(":srv 001 tester :Welcome")
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val connection = IrcConnection(
                IrcConnectionConfig(host = "127.0.0.1", port = server.port, tls = false, nick = "tester"),
            )
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                val initial = collector.drainUntilRegistered()
                    .filterIsInstance<IrcEvent.CapabilitiesNegotiated>().single()
                assertEquals("max-subs=2", initial.values["draft/metadata-2"])
                server.sendLine(":srv CAP tester NEW :draft/metadata-2=max-subs=1")
                val changed = collector.awaitInstance<IrcEvent.CapabilitiesNegotiated>()
                assertEquals(initial.capabilities, changed.capabilities)
                assertEquals("max-subs=1", changed.values["draft/metadata-2"])
                server.sendLine(":srv CAP tester DEL :echo-message labeled-response draft/metadata-2")
                val removed = collector.awaitInstance<IrcEvent.CapabilitiesNegotiated>()
                assertEquals(setOf("batch"), removed.capabilities)
                assertEquals(emptyMap(), removed.values)
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
        Unit
    }

    @org.junit.jupiter.api.Test
    fun capDelForgetsAdvertisedValues() {
        val negotiator = CapabilityNegotiator(
            wanted = setOf("draft/metadata-2"),
            sendRaw = {},
            onSaslAcknowledged = {},
            onFinished = {},
        )
        negotiator.begin()
        negotiator.handle(IrcMessage(command = "CAP", parameters = listOf("*", "LS", "draft/metadata-2=max-subs=5 batch")))
        assertEquals(mapOf("draft/metadata-2" to "max-subs=5"), negotiator.advertisedValues)
        negotiator.handle(IrcMessage(command = "CAP", parameters = listOf("*", "NEW", "draft/metadata-2")))
        assertEquals(emptyMap(), negotiator.advertisedValues)
        negotiator.handle(IrcMessage(command = "CAP", parameters = listOf("*", "NEW", "draft/metadata-2=max-subs=9")))
        assertEquals(mapOf("draft/metadata-2" to "max-subs=9"), negotiator.advertisedValues)
        negotiator.handle(IrcMessage(command = "CAP", parameters = listOf("*", "DEL", "draft/metadata-2")))
        assertEquals(emptyMap(), negotiator.advertisedValues)
    }
}
