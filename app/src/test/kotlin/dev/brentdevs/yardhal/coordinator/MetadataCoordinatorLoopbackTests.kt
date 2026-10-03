package dev.brentdevs.yardhal.coordinator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.core.client.InMemoryStsPolicyStore
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.InMemoryCredentialVault
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.YardhalDatabase
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetadataCoordinatorLoopbackTests {
    private class Server : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile private var socket: Socket? = null
        val received: MutableList<String> = CopyOnWriteArrayList()

        val port: Int get() = listener.localPort

        fun start() {
            executor.submit {
                val accepted = listener.accept()
                socket = accepted
                val reader = BufferedReader(InputStreamReader(accepted.getInputStream(), Charsets.UTF_8))
                while (!accepted.isClosed) {
                    val line = reader.readLine() ?: break
                    received.add(line)
                    respond(line)
                }
            }
        }

        private fun respond(line: String) {
            when {
                line.startsWith("CAP LS") -> send(":srv CAP * LS :batch server-time draft/metadata-2=max-subs=10")
                line.startsWith("CAP REQ :") -> send(":srv CAP * ACK :${line.substringAfter("CAP REQ :")}")
                line.startsWith("USER ") -> {
                    send(":srv 001 tester :Welcome")
                    send(":srv 005 tester NETWORK=Loop draft/ICON=https://example.org/icon.png?size={size} :are supported")
                    send(":srv BATCH +self metadata tester")
                    send(":srv BATCH -self")
                }
                line == "METADATA * SUB avatar display-name" -> send(":srv 770 tester avatar display-name")
                line == "JOIN #room" -> {
                    send(":tester!u@h JOIN #room")
                    send(":srv 353 tester = #room :tester alice")
                    send(":srv 366 tester #room :End of NAMES")
                    send(":srv BATCH +j1 metadata #room")
                    send("@batch=j1 :srv METADATA alice avatar * :https://example.com/alice.png")
                    send("@batch=j1 :srv METADATA alice display-name * :Alice Liddell")
                    send(":srv BATCH -j1")
                    send(":srv 774 tester #room 1")
                }
                line == "METADATA * SET display-name :Tester T" ->
                    send(":srv 761 tester * display-name * :Tester T")
            }
        }

        fun send(line: String) {
            val current = socket ?: return
            synchronized(current) {
                current.getOutputStream().write((line + "\r\n").toByteArray(Charsets.UTF_8))
                current.getOutputStream().flush()
            }
        }

        override fun close() {
            socket?.close()
            listener.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun metadataFlowsFromWireToProfilesAndBack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory("yardhal-metadata").toFile()
        val database = YardhalDatabase.inMemory(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Server().use { server ->
            try {
                server.start()
                val config = NetworkConfig(
                    id = "network",
                    name = "Loop",
                    host = "127.0.0.1",
                    port = server.port,
                    tls = false,
                    nick = "tester",
                    autojoin = listOf("#room"),
                )
                val networks = NetworkStore(directory)
                networks.add(config)
                val coordinator = LiveCoordinator(
                    scope = scope,
                    networkStore = networks,
                    messageStore = MessageStore(database.messageDao()),
                    readMarkers = ReadMarkerStore(directory),
                    mutes = MuteStore(directory),
                    vault = InMemoryCredentialVault(),
                    channelOrder = ChannelOrderStore(directory),
                    connectionFactory = ConnectionFactory { connectionConfig, onStsUpgrade ->
                        IrcConnection(
                            IrcConnectionConfig(
                                host = connectionConfig.host,
                                port = connectionConfig.port,
                                tls = connectionConfig.tls,
                                nick = connectionConfig.nick,
                                capabilities = setOf("batch", "server-time", "draft/metadata-2"),
                            ),
                            onStsUpgrade = onStsUpgrade,
                        )
                    },
                    stsPolicies = InMemoryStsPolicyStore(),
                )
                val room = ConversationRef.channel(config.id, "#room")
                coordinator.startAll()

                await { coordinator.profiles.value[config.id]?.forNick("ALICE")?.displayName == "Alice Liddell" }
                assertEquals("https://example.com/alice.png", coordinator.profiles.value[config.id]?.forNick("alice")?.avatarUrl)
                val subIndex = server.received.indexOf("METADATA * SUB avatar display-name")
                val joinIndex = server.received.indexOf("JOIN #room")
                assertTrue(subIndex in 0 until joinIndex, "SUB must precede JOIN: ${server.received}")

                await { coordinator.networks.value.singleOrNull()?.iconUrl == "https://example.org/icon.png?size={size}" }

                await { "METADATA #room SYNC" in server.received }

                assertTrue(coordinator.sendText(config.id, room.storageKey, "/setdisplayname Tester T"))
                await { coordinator.profiles.value[config.id]?.forNick("tester")?.displayName == "Tester T" }

                assertTrue(coordinator.sendText(config.id, room.storageKey, "/setavatar http://insecure.example/a.png"))
                await {
                    coordinator.buffers.value[room.storageKey]?.messages
                        ?.any { it.text == "Avatar URLs must use https://" } == true
                }
                assertTrue(server.received.none { it.startsWith("METADATA * SET avatar") })
                coordinator.disconnect(config.id)
            } finally {
                scope.cancel()
                database.close()
                directory.deleteRecursively()
            }
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }
}
