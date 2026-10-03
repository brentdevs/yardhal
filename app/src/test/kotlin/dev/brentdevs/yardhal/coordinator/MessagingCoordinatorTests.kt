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
import dev.brentdevs.yardhal.core.protocol.IrcMessage
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
import kotlin.test.assertFalse
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
class MessagingCoordinatorTests {
    private class Server(private val advertised: String) : AutoCloseable {
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
                val pendingBatch = ArrayList<IrcMessage>()
                while (!accepted.isClosed) {
                    val line = reader.readLine() ?: break
                    received += line
                    val message = IrcMessage.parse(line) ?: continue
                    when {
                        line.startsWith("CAP LS") -> send(":srv CAP * LS :$advertised")
                        line.startsWith("CAP REQ :") -> send(":srv CAP * ACK :${line.substringAfter("CAP REQ :")}")
                        line.startsWith("USER ") -> send(":srv 001 tester :Welcome")
                        line == "JOIN #room" -> {
                            send(":tester!u@h JOIN #room")
                            send(":srv 353 tester = #room :@tester alice")
                            send(":srv 366 tester #room :End of NAMES")
                        }
                        message.command == "BATCH" && message.parameters.first().startsWith("+") -> {
                            pendingBatch.clear()
                            pendingBatch += message
                        }
                        message.tag("batch") != null -> pendingBatch += message
                        message.command == "BATCH" -> {
                            pendingBatch += message
                            pendingBatch.forEachIndexed { index, frame ->
                                val tags = if (index == 0) frame.tags + ("msgid" to "ml-echo") else frame.tags
                                val prefix = if (index == pendingBatch.lastIndex) "" else ":tester!u@h "
                                val stamped = frame.copy(tags = tags).toWire()
                                send(
                                    if (stamped.startsWith("@")) {
                                        stamped.substringBefore(' ') + " " + prefix + stamped.substringAfter(' ')
                                    } else {
                                        prefix + stamped
                                    },
                                )
                            }
                        }
                    }
                }
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

    private class Harness(directory: java.io.File, server: Server, val scope: CoroutineScope) {
        val database: YardhalDatabase = YardhalDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val config = NetworkConfig(
            id = "network",
            name = "Test network",
            host = "127.0.0.1",
            port = server.port,
            tls = false,
            nick = "tester",
            autojoin = listOf("#room"),
        )
        val networks = NetworkStore(directory).also { it.add(config) }
        val messages = MessageStore(database.messageDao())
        val readMarkers = ReadMarkerStore(directory)
        val mutes = MuteStore(directory)
        val channelOrder = ChannelOrderStore(directory)
        val coordinator = LiveCoordinator(
            scope = scope,
            networkStore = networks,
            messageStore = messages,
            readMarkers = readMarkers,
            mutes = mutes,
            vault = InMemoryCredentialVault(),
            channelOrder = channelOrder,
            connectionFactory = ConnectionFactory { connectionConfig, onStsUpgrade ->
                IrcConnection(
                    IrcConnectionConfig(
                        host = connectionConfig.host,
                        port = connectionConfig.port,
                        tls = connectionConfig.tls,
                        nick = connectionConfig.nick,
                        capabilities = setOf("echo-message", "server-time", "batch", "draft/multiline", "draft/channel-rename"),
                    ),
                    onStsUpgrade = onStsUpgrade,
                )
            },
            stsPolicies = InMemoryStsPolicyStore(),
        )
    }

    private fun withHarness(advertised: String, body: suspend (Server, Harness) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("yardhal-messaging").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Server(advertised).use { server ->
            server.start()
            val harness = Harness(directory, server, scope)
            try {
                harness.coordinator.startAll()
                val ref = ConversationRef.channel(harness.config.id, "#room")
                await { harness.coordinator.buffers.value[ref.storageKey]?.members?.any { it.nick == "alice" } == true }
                body(server, harness)
                harness.coordinator.disconnect(harness.config.id)
            } finally {
                scope.cancel()
                harness.database.close()
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun multilineComposerTextIsBatchedAndReconciledAgainstTheEchoedBatch() =
        withHarness("echo-message server-time batch draft/multiline=max-bytes=4096,max-lines=24") { server, harness ->
            val ref = ConversationRef.channel(harness.config.id, "#room")
            harness.coordinator.sendText(harness.config.id, ref.storageKey, "line one\n\nline two")
            await { server.received.any { it.startsWith("BATCH -") } }
            val batch = server.received.dropWhile { !it.startsWith("BATCH +") }
            val reference = batch.first().split(' ')[1].drop(1)
            assertEquals(
                listOf(
                    "BATCH +$reference draft/multiline #room",
                    "@batch=$reference PRIVMSG #room :line one",
                    "@batch=$reference PRIVMSG #room :",
                    "@batch=$reference PRIVMSG #room :line two",
                    "BATCH -$reference",
                ),
                batch.take(5),
            )
            await {
                harness.coordinator.buffers.value[ref.storageKey]?.messages
                    ?.singleOrNull { it.sentByUs }?.msgid == "ml-echo"
            }
            val message = harness.coordinator.buffers.value[ref.storageKey]?.messages?.single { it.sentByUs }
            assertEquals("line one\n\nline two", message?.text)
            assertFalse(message?.pendingEcho == true)
            await { harness.messages.recent(ref, 10).any { it.msgid == "ml-echo" } }
        }

    @Test
    fun multilineComposerTextFallsBackToSeparatePrivmsgsWithoutTheCap() =
        withHarness("echo-message server-time batch") { server, harness ->
            val ref = ConversationRef.channel(harness.config.id, "#room")
            harness.coordinator.sendText(harness.config.id, ref.storageKey, "alpha\n\nbeta")
            await { server.received.contains("PRIVMSG #room :beta") }
            assertTrue(server.received.contains("PRIVMSG #room :alpha"))
            assertTrue(server.received.none { it.startsWith("BATCH") })
        }

    @Test
    fun renameMovesBufferTranscriptMarkersMutesPinsAndAutojoin() =
        withHarness("echo-message server-time batch draft/channel-rename") { server, harness ->
            val coordinator = harness.coordinator
            val old = ConversationRef.channel(harness.config.id, "#room")
            val renamed = ConversationRef.channel(harness.config.id, "#lounge")
            server.send("@msgid=a1;time=2024-01-01T00:00:00.000Z :alice!u@h PRIVMSG #room :before rename")
            await { harness.messages.recent(old, 10).any { it.msgid == "a1" } }
            coordinator.markRead(old.storageKey)
            coordinator.toggleMute(old.storageKey)
            coordinator.togglePin(old.storageKey)
            val marker = harness.readMarkers.marker(old.storageKey)
            assertTrue(marker > 0)

            server.send(":alice!u@h RENAME #room #lounge :moved")
            await {
                coordinator.buffers.value[renamed.storageKey]?.messages
                    ?.any { it.text == "Channel renamed from #room to #lounge by alice (moved)" } == true
            }

            assertFalse(coordinator.buffers.value.containsKey(old.storageKey))
            val buffer = coordinator.buffers.value.getValue(renamed.storageKey)
            assertEquals(renamed, buffer.ref)
            assertEquals("#lounge", buffer.displayName)
            assertTrue(buffer.messages.any { it.msgid == "a1" })
            assertTrue(buffer.members.any { it.nick == "alice" })
            assertEquals(renamed.storageKey, coordinator.renamedKey(old.storageKey))
            assertTrue(harness.mutes.isMuted(renamed.storageKey))
            assertFalse(harness.mutes.isMuted(old.storageKey))
            assertTrue(renamed.storageKey in coordinator.mutedState.value)
            assertEquals(listOf(renamed.storageKey), coordinator.orderState.value.pinnedKeys)
            assertEquals(marker, harness.readMarkers.marker(renamed.storageKey))
            assertEquals(0L, harness.readMarkers.marker(old.storageKey))
            assertEquals(listOf("#lounge"), harness.networks.byId(harness.config.id)?.autojoin)
            await { harness.messages.recent(renamed, 10).any { it.msgid == "a1" } }
            assertTrue(harness.messages.recent(old, 10).isEmpty())
        }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }
}
