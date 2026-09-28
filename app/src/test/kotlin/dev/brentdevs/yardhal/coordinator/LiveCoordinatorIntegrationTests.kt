package dev.brentdevs.yardhal.coordinator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.core.client.InMemoryStsPolicyStore
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.InMemoryCredentialVault
import dev.brentdevs.yardhal.core.data.IgnoreStore
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
class LiveCoordinatorIntegrationTests {
    private class Server : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile private var socket: Socket? = null
        private var repeatedMessages = 0

        val port: Int get() = listener.localPort

        fun start() {
            executor.submit {
                val accepted = listener.accept()
                socket = accepted
                val reader = BufferedReader(InputStreamReader(accepted.getInputStream(), Charsets.UTF_8))
                while (!accepted.isClosed) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("CAP LS") -> send(":srv CAP * LS :echo-message server-time")
                        line.startsWith("CAP REQ :") -> send(":srv CAP * ACK :${line.substringAfter("CAP REQ :")}")
                        line.startsWith("USER ") -> send(":srv 001 tester :Welcome")
                        line == "JOIN #room" -> {
                            send(":tester!u@h JOIN #room")
                            send(":srv 353 tester = #room :@tester alice")
                            send(":srv 366 tester #room :End of NAMES")
                        }
                        line == "PRIVMSG #room :hello" ->
                            send("@msgid=echo-1;time=2024-01-01T00:00:00.000Z :tester!u@h PRIVMSG #room :hello")
                        line == "PRIVMSG #room :ok" -> {
                            repeatedMessages += 1
                            if (repeatedMessages == 2) {
                                send("@msgid=echo-first;time=2024-01-01T00:00:00.001Z :tester!u@h PRIVMSG #room :ok")
                                send("@msgid=echo-second;time=2024-01-01T00:00:00.002Z :tester!u@h PRIVMSG #room :ok")
                            }
                        }
                        line == "PRIVMSG #room :after nick" ->
                            send("@msgid=echo-after;time=2024-01-01T00:00:00.003Z :TESTERCASE!u@h PRIVMSG #room :after nick")
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

    @Test
    fun echoesKeepMessageIdsOrderedAndMembershipTracksKickAndNickChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory("yardhal-coordinator").toFile()
        val database = YardhalDatabase.inMemory(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Server().use { server ->
            try {
                server.start()
                val config = NetworkConfig(
                    id = "network",
                    name = "Test network",
                    host = "127.0.0.1",
                    port = server.port,
                    tls = false,
                    nick = "tester",
                    autojoin = listOf("#room"),
                )
                val networks = NetworkStore(directory)
                networks.add(config)
                val messages = MessageStore(database.messageDao())
                val coordinator = LiveCoordinator(
                    scope = scope,
                    networkStore = networks,
                    messageStore = messages,
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
                                capabilities = setOf("echo-message", "server-time"),
                            ),
                            onStsUpgrade = onStsUpgrade,
                        )
                    },
                    stsPolicies = InMemoryStsPolicyStore(),
                )
                val ignores = IgnoreStore(directory)
                coordinator.attachIgnores(ignores)
                val ref = ConversationRef.channel(config.id, "#room")
                coordinator.startAll()
                await { coordinator.buffers.value[ref.storageKey]?.members?.any { it.nick == "alice" } == true }

                coordinator.sendText(config.id, ref.storageKey, "hello")
                await { coordinator.buffers.value[ref.storageKey]?.messages?.singleOrNull()?.msgid == "echo-1" }
                await { messages.recent(ref, 10).size == 1 }
                assertEquals("echo-1", messages.recent(ref, 10).single().msgid)

                coordinator.sendText(config.id, ref.storageKey, "ok")
                coordinator.sendText(config.id, ref.storageKey, "ok")
                await {
                    coordinator.buffers.value[ref.storageKey]?.messages
                        ?.filter { it.text == "ok" }
                        ?.let { it.size == 2 && it.all { message -> message.msgid != null } } == true
                }
                assertEquals(
                    listOf("echo-first", "echo-second"),
                    coordinator.buffers.value[ref.storageKey]?.messages
                        ?.filter { it.text == "ok" }?.map { it.msgid },
                )
                await {
                    coordinator.buffers.value[ref.storageKey]?.messages
                        ?.filter { it.text == "ok" }
                        ?.let { it.size == 2 && it.all { message -> message.storedRowId != null } } == true
                }
                assertEquals(
                    2,
                    coordinator.buffers.value[ref.storageKey]?.messages
                        ?.filter { it.text == "ok" }?.mapNotNull { it.storedRowId }?.distinct()?.size,
                )
                val repeatedRowId = coordinator.buffers.value[ref.storageKey]?.messages
                    ?.firstOrNull { it.msgid == "echo-second" }?.storedRowId
                val repeatedHit = coordinator.searchMessages("ok").first { it.rowId == repeatedRowId }
                coordinator.openSearchHit(repeatedHit)
                await {
                    coordinator.buffers.value[ref.storageKey]?.messages
                        ?.any { it.storedRowId == repeatedHit.rowId } == true
                }
                assertEquals(2, coordinator.buffers.value[ref.storageKey]?.messages?.count { it.text == "ok" })

                server.send(":bob!u@h JOIN #room")
                await { coordinator.buffers.value[ref.storageKey]?.members?.any { it.nick == "bob" } == true }
                server.send(":alice!u@h PART #room")
                await { coordinator.buffers.value[ref.storageKey]?.members?.none { it.nick == "alice" } == true }
                server.send(":bob!u@h NICK :bobby")
                await { coordinator.buffers.value[ref.storageKey]?.members?.any { it.nick == "bobby" } == true }
                server.send(":bobby!u@h QUIT :bye")
                await { coordinator.buffers.value[ref.storageKey]?.members?.none { it.nick == "bobby" } == true }
                assertFalse(coordinator.buffers.value[ref.storageKey]?.members?.any { it.nick == "alice" } == true)
                server.send(":charlie!u@h JOIN #room")
                await { coordinator.buffers.value[ref.storageKey]?.members?.any { it.nick == "charlie" } == true }
                server.send(":operator!u@h KICK #room charlie :cleanup")
                await { coordinator.buffers.value[ref.storageKey]?.members?.none { it.nick == "charlie" } == true }
                server.send(":operator!u@h KICK #room tester :leave")
                await {
                    coordinator.buffers.value[ref.storageKey]?.let {
                        it.joinState == JoinState.FAILED && it.members.isEmpty() &&
                            it.messages.any { message -> message.text.contains("tester was kicked") }
                    } == true
                }
                coordinator.retryJoin(config.id, ref.storageKey)
                await { coordinator.buffers.value[ref.storageKey]?.joinState == JoinState.JOINED }

                server.send(":TESTER!u@h NICK :TesterCase")
                await { coordinator.networks.value.singleOrNull()?.ownNick == "TesterCase" }
                assertTrue(coordinator.buffers.value[ref.storageKey]?.members?.any { it.nick == "TesterCase" } == true)
                coordinator.sendText(config.id, ref.storageKey, "after nick")
                await {
                    coordinator.buffers.value[ref.storageKey]?.messages
                        ?.singleOrNull { it.text == "after nick" }?.msgid == "echo-after"
                }
                assertTrue(
                    coordinator.buffers.value[ref.storageKey]?.messages
                        ?.singleOrNull { it.text == "after nick" }?.sentByUs == true,
                )
                assertTrue(coordinator.buffers.value[ref.storageKey]?.members?.any { it.nick == "TesterCase" } == true)
                server.close()
                await { coordinator.networks.value.singleOrNull()?.status != ConnectionStatus.REGISTERED }
                assertTrue(coordinator.canSendOffline(config.id, ref.storageKey, "/query alice"))
                assertTrue(coordinator.sendText(config.id, ref.storageKey, "/query alice"))
                assertTrue(coordinator.buffers.value.containsKey(ConversationRef.directMessage(config.id, "alice").storageKey))
                assertTrue(coordinator.sendText(config.id, ref.storageKey, "/help"))
                assertTrue(coordinator.sendText(config.id, ref.storageKey, "/ignore alice"))
                assertTrue(ignores.isIgnored("alice"))
                assertFalse(coordinator.sendText(config.id, ref.storageKey, "offline message"))
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
