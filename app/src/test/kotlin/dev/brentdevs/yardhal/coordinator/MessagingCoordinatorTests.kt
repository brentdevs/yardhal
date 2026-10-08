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
import dev.brentdevs.yardhal.core.data.MessageKind
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
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessagingCoordinatorTests {
    private class Server(
        private val advertised: String,
        private val echoText: (String) -> String = { it },
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile private var socket: Socket? = null
        val received: MutableList<String> = CopyOnWriteArrayList()
        val noImplicitNames: Boolean = "no-implicit-names" in advertised.split(' ')
        @Volatile var holdJoins: Boolean = false

        val port: Int get() = listener.localPort

        fun start() {
            executor.submit {
                val accepted = listener.accept()
                socket = accepted
                val reader = BufferedReader(InputStreamReader(accepted.getInputStream(), Charsets.UTF_8))
                val pendingBatch = ArrayList<IrcMessage>()
                var echoedBatches = 0
                while (!accepted.isClosed) {
                    val line = reader.readLine() ?: break
                    received += line
                    val message = IrcMessage.parse(line) ?: continue
                    when {
                        line.startsWith("CAP LS") -> send(":srv CAP * LS :$advertised")
                        line.startsWith("CAP REQ :") -> send(":srv CAP * ACK :${line.substringAfter("CAP REQ :")}")
                        line.startsWith("USER ") -> {
                            send(":srv 001 tester :Welcome")
                            if (noImplicitNames) send(":srv 005 tester WHOX :are supported")
                        }
                        line == "JOIN #room" && !holdJoins -> {
                            send(":tester!u@h JOIN #room")
                            if (!noImplicitNames) {
                                send(":srv 353 tester = #room :@tester alice")
                                send(":srv 366 tester #room :End of NAMES")
                            }
                        }
                        line == "WHO #room %cuhnfar" -> {
                            send(":srv 354 tester #room ~t tester.host tester H@ tester-acct :Tester Real")
                            send(":srv 354 tester #room ~a alice.host alice G alice-acct :Alice Real")
                            send(":srv 315 tester #room :End of WHO")
                        }
                        message.command == "BATCH" && message.parameters.first().startsWith("+") -> {
                            pendingBatch.clear()
                            pendingBatch += message
                        }
                        message.tag("batch") != null -> pendingBatch += message
                        message.command == "BATCH" -> {
                            pendingBatch += message
                            val msgid = "ml-echo-${++echoedBatches}"
                            pendingBatch.forEachIndexed { index, frame ->
                                val tags = if (index == 0) frame.tags + ("msgid" to msgid) else frame.tags
                                val prefix = if (index == pendingBatch.lastIndex) "" else ":tester!u@h "
                                val parameters = if (frame.command == "PRIVMSG") {
                                    listOf(frame.parameters.first(), echoText(frame.parameters.last()))
                                } else {
                                    frame.parameters
                                }
                                val stamped = frame.copy(tags = tags, parameters = parameters).toWire()
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

    private class Harness(directory: java.io.File, server: Server, val scope: CoroutineScope, clock: () -> Long) {
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
        val historyCoverage = dev.brentdevs.yardhal.core.data.HistoryCoverageStore(directory)
        val mutes = MuteStore(directory)
        val channelOrder = ChannelOrderStore(directory)
        val coordinator = LiveCoordinator(
            scope = scope,
            networkStore = networks,
            messageStore = messages,
            readMarkers = readMarkers,
            historyCoverage = historyCoverage,
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
                        capabilities = setOf(
                            "echo-message",
                            "server-time",
                            "batch",
                            "draft/multiline",
                            "draft/channel-rename",
                            "labeled-response",
                            "no-implicit-names",
                        ),
                    ),
                    onStsUpgrade = onStsUpgrade,
                )
            },
            stsPolicies = InMemoryStsPolicyStore(),
            clock = clock,
        )
    }

    private fun withHarness(
        advertised: String,
        echoText: (String) -> String = { it },
        clock: () -> Long = System::currentTimeMillis,
        body: suspend (Server, Harness) -> Unit,
    ) = runBlocking {
        val directory = Files.createTempDirectory("yardhal-messaging").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Server(advertised, echoText).use { server ->
            server.start()
            val harness = Harness(directory, server, scope, clock)
            try {
                harness.coordinator.startAll()
                val ref = ConversationRef.channel(harness.config.id, "#room")
                await {
                    val buffer = harness.coordinator.buffers.value[ref.storageKey]
                    if (server.noImplicitNames) {
                        buffer?.joinState == JoinState.JOINED
                    } else {
                        buffer?.members?.any { it.nick == "alice" } == true
                    }
                }
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
    fun clockSkewedOptimisticSendsStayAtTheBottomAndCanonicalEchoesRestoreChronologicalOrder() {
        for (deviceTime in listOf("2023-01-01T00:00:00Z", "2025-01-01T00:00:00Z")) {
            withHarness(
                "echo-message server-time batch labeled-response",
                clock = { Instant.parse(deviceTime).toEpochMilli() },
            ) { server, harness ->
                val ref = ConversationRef.channel(harness.config.id, "#room")
                val coordinator = harness.coordinator
                server.send("@msgid=alice-first;time=2024-01-01T00:00:00.002Z :alice!u@h PRIVMSG #room :first from alice")
                server.send("@msgid=alice-second;time=2024-01-01T00:00:00.002Z :alice!u@h PRIVMSG #room :second from alice")
                await { coordinator.buffers.value[ref.storageKey]?.messages?.any { it.msgid == "alice-second" } == true }

                assertTrue(coordinator.sendText(harness.config.id, ref.storageKey, "first from us"))
                assertTrue(coordinator.sendText(harness.config.id, ref.storageKey, "second from us"))
                val before = coordinator.buffers.value.getValue(ref.storageKey).messages
                val firstPending = before.single { it.text == "first from us" }
                val secondPending = before.single { it.text == "second from us" }
                assertEquals(listOf(firstPending.localId, secondPending.localId), before.takeLast(2).map { it.localId })
                assertTrue(before.zipWithNext().all { (left, right) -> left.timestampMs <= right.timestampMs })
                assertTrue(firstPending.pendingEcho)
                assertTrue(secondPending.pendingEcho)
                await {
                    server.received.mapNotNull { IrcMessage.parse(it) }
                        .any { it.command == "PRIVMSG" && it.parameters.lastOrNull() == "second from us" }
                }
                val sent = server.received.mapNotNull { IrcMessage.parse(it) }.filter { it.command == "PRIVMSG" }
                val firstLabel = assertNotNull(sent.single { it.parameters.last() == "first from us" }.tag("label"))
                val secondLabel = assertNotNull(sent.single { it.parameters.last() == "second from us" }.tag("label"))
                assertEquals(firstLabel, firstPending.echoLabel)
                assertEquals(secondLabel, secondPending.echoLabel)

                server.send("@msgid=later;time=2024-01-01T00:00:00.003Z :alice!u@h PRIVMSG #room :later from alice")
                server.send("@label=$firstLabel;msgid=own-first;time=2024-01-01T00:00:00.001Z :tester!u@h PRIVMSG #room :first from us")
                await { coordinator.buffers.value[ref.storageKey]?.messages?.any { it.msgid == "own-first" } == true }
                val afterFirstEcho = coordinator.buffers.value.getValue(ref.storageKey).messages
                assertEquals(secondPending, afterFirstEcho.single { it.localId == secondPending.localId })
                assertEquals(firstPending.localId, afterFirstEcho.single { it.msgid == "own-first" }.localId)

                server.send("@label=$secondLabel;msgid=own-second;time=2024-01-01T00:00:00.002Z :tester!u@h PRIVMSG #room :second from us")
                await { coordinator.buffers.value[ref.storageKey]?.messages?.any { it.msgid == "own-second" } == true }
                val messages = coordinator.buffers.value.getValue(ref.storageKey).messages
                val chat = messages.filter { it.kind == MessageKind.PRIVMSG }
                assertEquals(listOf("own-first", "alice-first", "alice-second", "own-second", "later"), chat.map { it.msgid })
                assertEquals(firstPending.localId, chat.single { it.msgid == "own-first" }.localId)
                assertEquals(secondPending.localId, chat.single { it.msgid == "own-second" }.localId)
                assertTrue(chat.filter { it.sentByUs }.all { !it.pendingEcho && it.echoLabel == null })
                assertTrue(messages.zipWithNext().all { (left, right) -> left.timestampMs <= right.timestampMs })
                await { harness.messages.recent(ref, 20).count { it.msgid?.startsWith("own-") == true } == 2 }
                val stored = harness.messages.recent(ref, 20).filter { it.msgid?.startsWith("own-") == true }
                assertEquals(
                    listOf(Instant.parse("2024-01-01T00:00:00.001Z").toEpochMilli(), Instant.parse("2024-01-01T00:00:00.002Z").toEpochMilli()),
                    stored.map { it.timestampMs },
                )
            }
        }
    }

    @Test
    fun sendsWithoutEchoMessageAppendAndPersistAfterNewerServerClockMessages() =
        withHarness("server-time batch", clock = { Instant.parse("2023-01-01T00:00:00Z").toEpochMilli() }) { server, harness ->
            val ref = ConversationRef.channel(harness.config.id, "#room")
            val timestamp = Instant.parse("2024-01-01T00:00:00.002Z").toEpochMilli()
            server.send("@msgid=server-newer;time=2024-01-01T00:00:00.002Z :alice!u@h PRIVMSG #room :from alice")
            await { harness.coordinator.buffers.value[ref.storageKey]?.messages?.any { it.msgid == "server-newer" } == true }
            assertTrue(harness.coordinator.sendText(harness.config.id, ref.storageKey, "without echo"))
            assertTrue(harness.coordinator.sendText(harness.config.id, ref.storageKey, "still without echo"))

            val messages = harness.coordinator.buffers.value.getValue(ref.storageKey).messages
            assertEquals(listOf("without echo", "still without echo"), messages.takeLast(2).map { it.text })
            assertEquals(listOf(timestamp, timestamp), messages.takeLast(2).map { it.timestampMs })
            assertTrue(messages.takeLast(2).all { !it.pendingEcho })
            await { harness.messages.recent(ref, 20).count { it.sentByUs } == 2 }
            val stored = harness.messages.recent(ref, 20).filter { it.sentByUs }
            assertEquals(listOf("without echo", "still without echo"), stored.map { it.text })
            assertEquals(listOf(timestamp, timestamp), stored.map { it.timestampMs })
        }

    @Test
    fun wireRedactionOnlyChangesTheNamedConversationWhenMessageIdsOverlap() =
        withHarness("server-time batch") { server, harness ->
            val room = ConversationRef.channel(harness.config.id, "#room")
            val other = ConversationRef.channel(harness.config.id, "#other")
            server.send("@msgid=shared;time=2024-01-01T00:00:00.001Z :alice!u@h PRIVMSG #room :room original")
            server.send("@msgid=shared;time=2024-01-01T00:00:00.002Z :alice!u@h PRIVMSG #other :other original")
            await { harness.messages.recent(room, 20).any { it.msgid == "shared" } }
            await { harness.messages.recent(other, 20).any { it.msgid == "shared" } }
            val beforeRoom = harness.coordinator.buffers.value.getValue(room.storageKey).messages.single { it.msgid == "shared" }
            val beforeOther = harness.coordinator.buffers.value.getValue(other.storageKey).messages.single { it.msgid == "shared" }

            server.send(":alice!u@h REDACT #room shared :removed")
            await {
                harness.coordinator.buffers.value[room.storageKey]?.messages
                    ?.singleOrNull { it.msgid == "shared" }?.kind == MessageKind.SYSTEM
            }
            val redacted = harness.coordinator.buffers.value.getValue(room.storageKey).messages.single { it.msgid == "shared" }
            assertFalse(redacted.text.contains(beforeRoom.text))
            assertEquals(beforeRoom.localId, redacted.localId)
            val unchanged = harness.coordinator.buffers.value.getValue(other.storageKey).messages.single { it.msgid == "shared" }
            assertEquals(beforeOther.localId, unchanged.localId)
            assertEquals(beforeOther.text, unchanged.text)
            assertEquals(beforeOther.kind, unchanged.kind)
            assertEquals("other original", harness.messages.recent(other, 20).single { it.msgid == "shared" }.text)
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
                    ?.singleOrNull { it.sentByUs }?.msgid == "ml-echo-1"
            }
            val message = harness.coordinator.buffers.value[ref.storageKey]?.messages?.single { it.sentByUs }
            assertEquals("line one\n\nline two", message?.text)
            assertFalse(message?.pendingEcho == true)
            await { harness.messages.recent(ref, 10).any { it.msgid == "ml-echo-1" } }
        }

    @Test
    fun labeledMultilineBatchesReconcileFilteredEchoesWithoutDuplicates() =
        withHarness(
            "echo-message server-time batch labeled-response draft/multiline=max-bytes=4096,max-lines=2",
            echoText = { "$it (filtered)" },
        ) { server, harness ->
            val ref = ConversationRef.channel(harness.config.id, "#room")
            assertTrue(harness.coordinator.sendText(harness.config.id, ref.storageKey, "one\ntwo\nthree\nfour"))
            await {
                harness.coordinator.buffers.value[ref.storageKey]?.messages?.filter { it.sentByUs }
                    ?.let { messages ->
                        messages.size == 2 && messages.all { !it.pendingEcho && it.msgid?.startsWith("ml-echo-") == true }
                    } == true
            }

            val frames = server.received.mapNotNull { IrcMessage.parse(it) }.filter {
                it.command == "BATCH" || it.tag("batch") != null
            }
            val openings = frames.filter { it.command == "BATCH" && it.parameters.first().startsWith("+") }
            assertEquals(2, openings.size)
            val labels = openings.map { assertNotNull(it.tag("label")) }
            assertEquals(2, labels.toSet().size)
            assertTrue(frames.filter { it !in openings }.all { it.tag("label") == null })
            val messages = harness.coordinator.buffers.value.getValue(ref.storageKey).messages.filter { it.sentByUs }
            assertEquals(
                mapOf<String?, String>("ml-echo-1" to "one (filtered)\ntwo (filtered)", "ml-echo-2" to "three (filtered)\nfour (filtered)"),
                messages.associate { it.msgid to it.text },
            )
            assertTrue(messages.all { it.echoLabel == null })
            await { harness.messages.recent(ref, 10).count { it.msgid?.startsWith("ml-echo-") == true } == 2 }
        }

    @Test
    fun noImplicitNamesJoinsBeforeLazyWhoxLoadsMembersOnlyOnce() =
        withHarness("batch no-implicit-names") { server, harness ->
            val coordinator = harness.coordinator
            val ref = ConversationRef.channel(harness.config.id, "#room")
            val joined = coordinator.buffers.value.getValue(ref.storageKey)
            assertEquals(JoinState.JOINED, joined.joinState)
            assertTrue(joined.members.isEmpty())
            assertTrue(server.received.none { IrcMessage.parse(it)?.command == "WHO" })
            server.holdJoins = true
            coordinator.retryJoin(harness.config.id, ref.storageKey)
            coordinator.ensureMembers(harness.config.id, ref.storageKey)
            assertTrue(coordinator.sendText(harness.config.id, ref.storageKey, "/quote PING :joining-guard"))
            await { "PING :joining-guard" in server.received }
            assertEquals(JoinState.JOINING, coordinator.buffers.value.getValue(ref.storageKey).joinState)
            assertTrue(server.received.none { IrcMessage.parse(it)?.command == "WHO" })
            server.send(":tester!u@h JOIN #room")
            await { coordinator.buffers.value[ref.storageKey]?.joinState == JoinState.JOINED }

            coordinator.ensureMembers(harness.config.id, ref.storageKey)
            val queried = withTimeoutOrNull(5_000) {
                while (server.received.none { IrcMessage.parse(it)?.command == "WHO" }) delay(10)
                true
            }
            assertEquals(true, queried, "Wire: ${server.received}; buffer: ${coordinator.buffers.value[ref.storageKey]}")
            assertEquals("WHO #room %cuhnfar", server.received.first { IrcMessage.parse(it)?.command == "WHO" })
            await {
                coordinator.buffers.value[ref.storageKey]?.let { buffer ->
                    buffer.members.size == 2 &&
                        buffer.members.all { buffer.memberPresence[it.nick]?.away != null }
                } == true
            }
            val loaded = coordinator.buffers.value.getValue(ref.storageKey)
            assertEquals(JoinState.JOINED, loaded.joinState)
            assertEquals(listOf("alice", "tester"), loaded.members.map { it.nick })
            assertEquals('@', loaded.members.single { it.nick == "tester" }.symbol)
            assertEquals("alice-acct", loaded.memberPresence["alice"]?.account)
            assertEquals("Alice Real", loaded.memberPresence["alice"]?.realName)
            assertEquals(true, loaded.memberPresence["alice"]?.away)
            assertEquals("~t", loaded.memberPresence["tester"]?.user)
            assertEquals("tester.host", loaded.memberPresence["tester"]?.host)

            coordinator.ensureMembers(harness.config.id, ref.storageKey)
            assertTrue(coordinator.sendText(harness.config.id, ref.storageKey, "/quote PING :members-ready"))
            await { "PING :members-ready" in server.received }
            assertEquals(1, server.received.count { it == "WHO #room %cuhnfar" })
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
            coordinator.trackSelection(old.storageKey)
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
            assertEquals(
                renamed.storageKey,
                coordinator.followRenamedSelection(old.storageKey, coordinator.buffers.value.keys),
            )
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

    @Test
    fun rapidRenamesFollowActiveSelectionOnceAndIgnoreRecreatedSourceAndRemovedDestination() =
        withHarness("echo-message server-time batch draft/channel-rename") { server, harness ->
            val coordinator = harness.coordinator
            val original = ConversationRef.channel(harness.config.id, "#room").storageKey
            val middle = ConversationRef.channel(harness.config.id, "#middle").storageKey
            val final = ConversationRef.channel(harness.config.id, "#final").storageKey
            coordinator.trackSelection(original)
            server.send(":alice!u@h RENAME #room #middle :first")
            server.send(":alice!u@h RENAME #middle #final :second")
            await { final in coordinator.buffers.value && middle !in coordinator.buffers.value }
            assertEquals(null, coordinator.followRenamedSelection(original, setOf(original)))
            assertEquals(final, coordinator.followRenamedSelection(original, coordinator.buffers.value.keys))
            assertEquals(null, coordinator.followRenamedSelection(final, coordinator.buffers.value.keys))
            assertEquals(null, coordinator.followRenamedSelection(original, coordinator.buffers.value.keys))

            server.send(":tester!u@h JOIN #room")
            await { original in coordinator.buffers.value }
            coordinator.trackSelection(original)
            server.send(":alice!u@h RENAME #room #middle :reused")
            await { middle in coordinator.buffers.value && original !in coordinator.buffers.value }
            server.send(":tester!u@h JOIN #room")
            await { original in coordinator.buffers.value }
            assertEquals(null, coordinator.followRenamedSelection(original, coordinator.buffers.value.keys))
            coordinator.leaveConversation(harness.config.id, original)
            assertEquals(null, coordinator.followRenamedSelection(original, coordinator.buffers.value.keys))

            coordinator.trackSelection(middle)
            val removed = ConversationRef.channel(harness.config.id, "#removed").storageKey
            server.send(":alice!u@h RENAME #middle #removed :last")
            await { removed in coordinator.buffers.value && middle !in coordinator.buffers.value }
            coordinator.leaveConversation(harness.config.id, removed)
            assertEquals(null, coordinator.followRenamedSelection(middle, coordinator.buffers.value.keys))

            coordinator.trackSelection(final)
            val discarded = ConversationRef.channel(harness.config.id, "#discarded").storageKey
            server.send(":alice!u@h RENAME #final #discarded :network removal")
            await { discarded in coordinator.buffers.value }
            coordinator.removeNetwork(harness.config.id)
            assertEquals(null, coordinator.followRenamedSelection(final, setOf(discarded)))
        }

    @Test
    fun channelContextSurvivesCoordinatorHistoryReload() =
        withHarness("echo-message server-time batch") { server, harness ->
            val ref = ConversationRef.directMessage(harness.config.id, "alice")
            server.send("@+draft/channel-context=#room;msgid=context-1 :alice!u@h NOTICE tester :contextual help")
            await { harness.messages.recent(ref, 10).any { it.msgid == "context-1" } }
            assertEquals(
                "#room",
                harness.coordinator.buffers.value.getValue(ref.storageKey).messages.single { it.msgid == "context-1" }.channelContext,
            )
            val restored = LiveCoordinator(
                scope = harness.scope,
                networkStore = harness.networks,
                messageStore = harness.messages,
                readMarkers = harness.readMarkers,
                historyCoverage = harness.historyCoverage,
                mutes = harness.mutes,
                vault = InMemoryCredentialVault(),
                channelOrder = harness.channelOrder,
                connectionFactory = ConnectionFactory { config, onStsUpgrade ->
                    IrcConnection(
                        IrcConnectionConfig(host = config.host, port = config.port, tls = config.tls, nick = config.nick),
                        onStsUpgrade = onStsUpgrade,
                    )
                },
                stsPolicies = InMemoryStsPolicyStore(),
            )
            assertEquals(ref.storageKey, restored.ensureConversation(harness.config.id, "alice"))
            assertTrue(restored.loadPersistedHistory(ref.storageKey))
            await { restored.buffers.value[ref.storageKey]?.messages?.any { it.msgid == "context-1" } == true }
            val entry = restored.buffers.value.getValue(ref.storageKey).messages.single { it.msgid == "context-1" }
            assertEquals("#room", entry.channelContext)
            assertEquals("contextual help", entry.text)
            assertEquals(dev.brentdevs.yardhal.core.data.MessageKind.NOTICE, entry.kind)
        }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }
}
