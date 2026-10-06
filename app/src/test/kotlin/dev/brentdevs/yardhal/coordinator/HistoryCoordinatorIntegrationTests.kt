package dev.brentdevs.yardhal.coordinator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.core.client.InMemoryStsPolicyStore
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.HistoryCoverageStore
import dev.brentdevs.yardhal.core.data.InMemoryCredentialVault
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.StoredMessage
import dev.brentdevs.yardhal.core.data.StoredHistoryGap
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryCoordinatorIntegrationTests {
    private data class WireRow(
        val id: String,
        val timestamp: Long,
        val text: String = "tester archive $id",
        val historyContext: Boolean = false,
        val replyTo: String? = null,
    )

    private data class Query(val peer: Peer, val message: IrcMessage) {
        val operation: String get() = message.parameters.first()
        val target: String get() = message.parameters[1]
        val label: String? get() = message.tag("label")
    }

    private class Peer(val socket: Socket) {
        val received: MutableList<String> = CopyOnWriteArrayList()

        fun send(line: String) {
            synchronized(socket) {
                socket.getOutputStream().write((line + "\r\n").toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }
        }
    }

    private class Server(
        private val history: Boolean,
        private val labeled: Boolean,
        private val targets: List<Pair<String, Long>>,
        private val registrationNotice: Boolean,
        private val znc: Boolean,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val executor = Executors.newCachedThreadPool()
        private val batches = AtomicLong()
        private val timestamps = mutableMapOf("anchor" to BASE, "bob-anchor" to BASE)
        val peers: MutableList<Peer> = CopyOnWriteArrayList()
        val queries: MutableList<Query> = CopyOnWriteArrayList()
        val port: Int get() = listener.localPort

        fun start() {
            executor.submit {
                runCatching {
                    while (!listener.isClosed) {
                        val peer = Peer(listener.accept())
                        peers += peer
                        executor.submit { runCatching { serve(peer) } }
                    }
                }
            }
        }

        private fun serve(peer: Peer) {
            val reader = BufferedReader(InputStreamReader(peer.socket.getInputStream(), Charsets.UTF_8))
            while (!peer.socket.isClosed) {
                val line = reader.readLine() ?: break
                peer.received += line
                val message = IrcMessage.parse(line) ?: continue
                when (message.command) {
                    "CAP" -> when (message.parameters.firstOrNull()) {
                        "LS" -> peer.send(":srv CAP * LS :message-tags server-time batch draft/multiline=max-bytes=8192,max-lines=1000" +
                            (if (history) " draft/chathistory" else "") +
                            (if (labeled) " labeled-response" else "") +
                            (if (znc) " znc.in/playback" else ""))
                        "REQ" -> peer.send(":srv CAP * ACK :${message.parameters.last()}")
                    }
                    "USER" -> {
                        if (registrationNotice) peer.send("@time=${iso(NOW)} :srv NOTICE tester :registration notice")
                        if (history) peer.send(":srv 005 tester CHATHISTORY=5 MSGREFTYPES=msgid,timestamp :supported")
                        peer.send(":srv 001 tester :Welcome")
                    }
                    "JOIN" -> {
                        peer.send(":tester!u@h JOIN ${message.parameters.first()}")
                        peer.send(":srv 353 tester = ${message.parameters.first()} :tester alice")
                        peer.send(":srv 366 tester ${message.parameters.first()} :End of NAMES")
                    }
                    "CHATHISTORY" -> {
                        val query = Query(peer, message)
                        queries += query
                        if (query.operation == "TARGETS") replyTargets(query)
                    }
                    "ZNC" -> if (message.parameters.firstOrNull() == "*playback") queries += Query(peer, message)
                }
            }
        }

        private fun tags(query: Query, end: Boolean): String {
            val tags = buildList {
                query.label?.let { add("label=$it") }
                if (end) add("draft/chathistory-end")
            }
            return if (tags.isEmpty()) "" else "@${tags.joinToString(";")} "
        }

        private fun replyTargets(query: Query) {
            val from = Instant.parse(query.message.parameters[1].substringAfter('=')).toEpochMilli()
            val to = Instant.parse(query.message.parameters[2].substringAfter('=')).toEpochMilli()
            val batch = "targets-${batches.incrementAndGet()}"
            query.peer.send("${tags(query, true)}:srv BATCH +$batch draft/chathistory-targets")
            targets.filter { it.second in from..to }.take(query.message.parameters.last().toInt()).forEach { (nick, time) ->
                query.peer.send("@batch=$batch :srv CHATHISTORY TARGETS $nick ${iso(time)}")
            }
            query.peer.send(":srv BATCH -$batch")
        }

        fun reply(query: Query, rows: List<WireRow>, end: Boolean = false) {
            assertTrue(rows.count { !it.historyContext } <= query.message.parameters.last().toInt())
            fun reference(value: String): Long? = when {
                value == "*" -> null
                value.startsWith("timestamp=") -> Instant.parse(value.substringAfter('=')).toEpochMilli()
                else -> assertNotNull(timestamps[value.substringAfter('=')])
            }
            val from = reference(query.message.parameters[2])
            val to = if (query.operation == "BETWEEN") reference(query.message.parameters[3]) else null
            assertTrue(rows.all { row ->
                row.historyContext || when (query.operation) {
                    "LATEST" -> from == null || row.timestamp >= from
                    "BETWEEN" -> row.timestamp >= assertNotNull(from) && row.timestamp <= assertNotNull(to)
                    "BEFORE" -> row.timestamp <= assertNotNull(from)
                    else -> false
                }
            })
            rows.forEach { timestamps[it.id] = it.timestamp }
            val batch = "history-${batches.incrementAndGet()}"
            query.peer.send("${tags(query, end)}:srv BATCH +$batch chathistory ${query.target}")
            rows.forEach { row ->
                val destination = if (query.target.startsWith('#')) query.target else "tester"
                val sender = if (query.target.startsWith('#')) "alice" else query.target
                val context = if (row.historyContext) ";draft/chathistory-context" else ""
                val reply = row.replyTo?.let { ";+draft/reply=$it" }.orEmpty()
                query.peer.send("@batch=$batch;time=${iso(row.timestamp)};msgid=${row.id}$context$reply :$sender!u@h PRIVMSG $destination :${row.text}")
            }
            query.peer.send(":srv BATCH -$batch")
        }

        fun replyMultiline(query: Query, id: String, lines: List<String>) {
            timestamps[id] = BASE + 1_000
            val batch = "history-${batches.incrementAndGet()}"
            val multiline = "multiline-${batches.incrementAndGet()}"
            query.peer.send("${tags(query, true)}:srv BATCH +$batch chathistory ${query.target}")
            query.peer.send("@batch=$batch;time=${iso(BASE + 1_000)};msgid=$id :alice!u@h BATCH +$multiline draft/multiline ${query.target}")
            for (line in lines) query.peer.send("@batch=$multiline :alice!u@h PRIVMSG ${query.target} :$line")
            query.peer.send(":alice!u@h BATCH -$multiline")
            query.peer.send(":srv BATCH -$batch")
        }

        fun replyPlayback(query: Query, target: String, rows: List<WireRow>) {
            val from = query.message.parameters[3].toBigDecimal().movePointRight(3).toLong()
            val to = query.message.parameters[4].toBigDecimal().movePointRight(3).toLong()
            assertTrue(rows.all { it.timestamp > from && it.timestamp <= to })
            val batch = "playback-${batches.incrementAndGet()}"
            query.peer.send(":srv BATCH +$batch znc.in/playback $target")
            for (row in rows) {
                val destination = if (target.startsWith('#')) target else "tester"
                val sender = if (target.startsWith('#')) "alice" else target
                query.peer.send("@batch=$batch;time=${iso(row.timestamp)} :$sender!u@h PRIVMSG $destination :${row.text}")
            }
            query.peer.send(":srv BATCH -$batch")
        }

        fun fail(query: Query) {
            query.peer.send("${tags(query, false)}:srv FAIL CHATHISTORY MESSAGE_ERROR ${query.operation} ${query.target} :Denied")
        }

        override fun close() {
            listener.close()
            peers.forEach { it.socket.close() }
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private class Harness(directory: java.io.File, server: Server, scope: CoroutineScope) {
        val database = YardhalDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val config = NetworkConfig(id = "network", name = "History", host = "127.0.0.1", port = server.port,
            tls = false, nick = "tester", autojoin = listOf("#room"))
        val messages = MessageStore(database.messageDao())
        val markers = ReadMarkerStore(directory)
        val coverage = HistoryCoverageStore(directory)
        val elapsed = AtomicLong(0)
        val highlights: MutableList<String> = CopyOnWriteArrayList()
        val coordinator = LiveCoordinator(
            scope = scope,
            networkStore = NetworkStore(directory).also { it.add(config) },
            messageStore = messages,
            readMarkers = markers,
            historyCoverage = coverage,
            mutes = MuteStore(directory),
            vault = InMemoryCredentialVault(),
            channelOrder = ChannelOrderStore(directory),
            connectionFactory = ConnectionFactory { connectionConfig, onStsUpgrade ->
                IrcConnection(IrcConnectionConfig(host = connectionConfig.host, port = connectionConfig.port,
                    tls = false, nick = connectionConfig.nick,
                    capabilities = setOf("message-tags", "server-time", "batch", "draft/chathistory", "labeled-response", "draft/multiline", "znc.in/playback")),
                    onStsUpgrade = onStsUpgrade)
            },
            stsPolicies = InMemoryStsPolicyStore(),
            notifier = LiveCoordinator.HighlightNotifier { _, _, _, text -> highlights += text },
            clock = { NOW },
            historyElapsedClock = elapsed::get,
        )
        val channel = ConversationRef.channel(config.id, "#room")

        fun buffer(ref: ConversationRef = channel): ConversationBuffer =
            assertNotNull(coordinator.buffers.value[ref.storageKey])

        suspend fun seed(ref: ConversationRef, id: String, timestamp: Long = BASE): Long =
            assertNotNull(messages.recordWithRowId(StoredMessage(networkId = config.id, conversation = ref,
                msgid = id, senderNick = "alice", senderUser = "u", senderHost = "h", kind = MessageKind.PRIVMSG,
                text = "cached $id", sentByUs = false, timestampMs = timestamp)))
    }

    private fun withHarness(
        history: Boolean = true,
        labeled: Boolean = true,
        targets: List<Pair<String, Long>> = emptyList(),
        registrationNotice: Boolean = false,
        znc: Boolean = false,
        body: suspend (Server, Harness) -> Unit,
    ) = runBlocking {
        val directory = Files.createTempDirectory("yardhal-history-consumer").toFile()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val server = Server(history, labeled, targets, registrationNotice, znc)
        server.start()
        val harness = Harness(directory, server, scope)
        try {
            body(server, harness)
        } finally {
            harness.coordinator.disconnect(harness.config.id)
            server.close()
            job.cancelAndJoin()
            harness.database.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun await(predicate: suspend () -> Boolean) {
        withTimeout(5_000) { while (!predicate()) delay(10) }
    }

    private suspend fun awaitProcessed(peer: Peer, harness: Harness, marker: String) {
        val ref = ConversationRef.server(harness.config.id)
        peer.send(":srv 372 tester :$marker")
        await { harness.coordinator.buffers.value[ref.storageKey]?.messages?.any { it.text == marker } == true }
    }

    private suspend fun query(server: Server, operation: String, target: String, after: Int = 0): Query {
        await { server.queries.drop(after).any { it.operation == operation && it.target == target } }
        return server.queries.drop(after).first { it.operation == operation && it.target == target }
    }

    @Test
    fun offlineTimestampTiedPagesPreserveEveryRowAndExistingLocalIdentity() = withHarness { _, harness ->
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        val rowIds = (1..451).associate { index ->
            val id = "tied-$index"
            id to harness.seed(harness.channel, id)
        }
        assertTrue(harness.coordinator.loadPersistedHistory(key))
        await { harness.buffer().messages.size == 200 && harness.buffer().history.initial.status == HistoryLoadStatus.IDLE }
        val original = harness.buffer().messages.associate { it.msgid to it.localId }
        harness.coordinator.loadOlderHistory(key)
        await { harness.buffer().messages.size == 400 && harness.buffer().history.older.status == HistoryLoadStatus.IDLE }
        harness.coordinator.loadOlderHistory(key)
        await { harness.buffer().messages.size == 451 && harness.buffer().history.older.status == HistoryLoadStatus.EXHAUSTED }
        val all = harness.buffer().messages
        assertEquals(rowIds.keys, all.map { it.msgid }.toSet())
        assertEquals(451, all.map { it.localId }.toSet().size)
        assertEquals(rowIds.values.toList(), all.map { it.storedRowId })
        assertEquals(original, all.filter { it.msgid in original }.associate { it.msgid to it.localId })
    }

    @Test
    fun anchoredCatchupAndShortBetweenPagesRetainGapUntilExplicitCompletion() = withHarness { server, harness ->
        val cachedRow = harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.markers.advance(key, BASE)
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.singleOrNull()?.storedRowId == cachedRow }
        val localId = harness.buffer().messages.single().localId
        harness.coordinator.startAll()
        val latest = query(server, "LATEST", "#room")
        assertEquals(listOf("LATEST", "#room", "msgid=anchor", "5"), latest.message.parameters)
        server.reply(latest, (6..10).map { WireRow("remote-$it", BASE + it * 1_000L) })
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE && harness.buffer().history.gaps.size == 1 }
        val gapId = harness.buffer().history.gaps.single().id
        assertEquals("anchor", harness.buffer().history.gaps.single().from.msgid)
        assertEquals("remote-6", harness.buffer().history.gaps.single().to.msgid)
        for (ids in listOf(1..2, 3..4)) {
            val offset = server.queries.size
            harness.coordinator.fillHistoryGap(key, gapId)
            val between = query(server, "BETWEEN", "#room", offset)
            val previous = if (ids.first == 1) "anchor" else "remote-2"
            assertEquals(listOf("BETWEEN", "#room", "msgid=$previous", "msgid=remote-6", "5"), between.message.parameters)
            server.reply(between, ids.map { WireRow("remote-$it", BASE + it * 1_000L) })
            await { harness.buffer().history.gaps.singleOrNull()?.let {
                it.state.status == HistoryLoadStatus.IDLE && it.from.msgid == "remote-${ids.last}"
            } == true }
        }
        val offset = server.queries.size
        harness.coordinator.fillHistoryGap(key, gapId)
        val final = query(server, "BETWEEN", "#room", offset)
        assertEquals("msgid=remote-4", final.message.parameters[2])
        server.reply(final, listOf(WireRow("remote-5", BASE + 5_000, "tester needlefive archive remote-5"),
            WireRow("remote-6", BASE + 6_000)), end = true)
        await { harness.buffer().history.gaps.isEmpty() && harness.messages.recent(harness.channel, 30).size == 11 }
        val archived = harness.buffer().messages.filter { it.msgid?.startsWith("remote-") == true }
        assertEquals((1..10).map { "remote-$it" }.toSet(), archived.map { it.msgid }.toSet())
        assertEquals(10, archived.size)
        assertTrue(archived.all { it.playback && !it.countsAsUnread })
        assertEquals(localId, harness.buffer().messages.single { it.msgid == "anchor" }.localId)
        assertEquals(BASE, harness.markers.marker(key))
        assertEquals(BASE, harness.buffer().readAtMs)
        assertFalse(harness.buffer().hasUnread)
        assertTrue(harness.highlights.isEmpty())
        val hit = harness.coordinator.searchMessages("needlefive", harness.config.id, harness.channel.normalizedTarget).single()
        assertEquals(harness.messages.recent(harness.channel, 30).single { it.msgid == "remote-5" }.rowId, hit.rowId)
        val identity = archived.single { it.msgid == "remote-5" }.localId
        harness.coordinator.openSearchHit(hit)
        await { harness.buffer().messages.single { it.msgid == "remote-5" }.storedRowId == hit.rowId }
        assertEquals(identity, harness.buffer().messages.single { it.msgid == "remote-5" }.localId)
    }

    @Test
    fun targetsDiscoverKnownAndNewOfflineDirectMessagesWithoutProtocolTranscript() = withHarness(
        targets = listOf("Bob" to BASE + 2_000, "Carol" to BASE + 3_000), registrationNotice = true,
    ) { server, harness ->
        val bob = ConversationRef.directMessage(harness.config.id, "Bob")
        val carol = ConversationRef.directMessage(harness.config.id, "Carol")
        harness.seed(bob, "bob-anchor")
        harness.seed(ConversationRef.server(harness.config.id), "server-cache", NOW + 60_000)
        harness.coordinator.startAll()
        val answered = mutableSetOf<Query>()
        val targetsSeen = mutableSetOf<String>()
        withTimeout(5_000) {
            while (targetsSeen != setOf("#room", "bob", "carol")) {
                await { server.queries.any { it.operation == "LATEST" && it !in answered } }
                val request = server.queries.first { it.operation == "LATEST" && it !in answered }
                answered += request
                val target = request.target.lowercase()
                targetsSeen += target
                when (target) {
                    "#room" -> server.reply(request, emptyList())
                    "bob" -> {
                        assertTrue(request.message.parameters[2] in setOf("msgid=bob-anchor", "msgid=bob-offline"))
                        server.reply(request, listOf(WireRow("bob-offline", BASE + 2_000)), end = true)
                    }
                    "carol" -> {
                        assertEquals("*", request.message.parameters[2])
                        server.reply(request, listOf(WireRow("carol-offline", BASE + 3_000)), end = true)
                    }
                    else -> error("Unexpected history target ${request.target}")
                }
            }
        }
        val targetsQuery = query(server, "TARGETS", "timestamp=${iso(BASE - 1)}")
        assertEquals(BASE - 1, Instant.parse(targetsQuery.message.parameters[1].substringAfter('=')).toEpochMilli())
        await { harness.coordinator.buffers.value[bob.storageKey]?.messages?.any { it.msgid == "bob-offline" } == true &&
            harness.coordinator.buffers.value[carol.storageKey]?.messages?.any { it.msgid == "carol-offline" } == true }
        await { harness.messages.recent(carol, 10).singleOrNull()?.msgid == "carol-offline" }
        assertTrue(harness.buffer(bob).messages.filter { it.msgid == "bob-offline" }.all { it.playback })
        assertTrue(harness.buffer(carol).messages.single { it.msgid == "carol-offline" }.playback)
        val transcript = harness.coordinator.buffers.value.values.flatMap { it.messages }
        assertTrue(transcript.any { it.text == "registration notice" })
        assertFalse(transcript.any { it.text.contains("TARGETS") || it.text.contains("CAP ") })
        assertTrue(harness.highlights.isEmpty())
    }

    @Test
    fun labeledFailureCanRetryWithoutReplacingCachedIdentity() = withHarness { server, harness ->
        harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.singleOrNull()?.msgid == "anchor" }
        val identity = harness.buffer().messages.single().localId
        harness.coordinator.startAll()
        val failed = query(server, "LATEST", "#room")
        server.fail(failed)
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.FAILED }
        assertFalse(harness.buffer().history.catchUp.requiresReconnect)
        val offset = server.queries.size
        harness.coordinator.retryHistory(key)
        val retried = query(server, "LATEST", "#room", offset)
        assertTrue(retried.label != failed.label)
        server.reply(retried, listOf(WireRow("retry-success", BASE + 1_000)), end = true)
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE &&
            harness.buffer().messages.any { it.msgid == "retry-success" } }
        assertEquals(identity, harness.buffer().messages.single { it.msgid == "anchor" }.localId)
    }

    @Test
    fun unlabelledTimeoutRequiresReconnectAndRetiresOldSocketResponses() = withHarness(labeled = false) { server, harness ->
        harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.startAll()
        val timedOut = query(server, "LATEST", "#room")
        harness.elapsed.addAndGet(6_001)
        timedOut.peer.send("PING :expire-history")
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.FAILED }
        assertTrue(harness.buffer().history.catchUp.requiresReconnect)
        server.reply(timedOut, listOf(WireRow("stale-old-socket", BASE + 1_000)), end = true)
        awaitProcessed(timedOut.peer, harness, "old-response-barrier")
        assertFalse(harness.buffer().messages.any { it.msgid == "stale-old-socket" })
        val offset = server.queries.size
        harness.coordinator.retryHistory(key)
        val fresh = query(server, "LATEST", "#room", offset)
        assertTrue(fresh.peer !== timedOut.peer)
        assertEquals(2, server.peers.size)
        assertEquals(HistoryLoadStatus.LOADING, harness.buffer().history.catchUp.status)
        assertFalse(harness.buffer().messages.any { it.msgid == "stale-old-socket" })
        server.reply(fresh, listOf(WireRow("fresh-socket", BASE + 2_000)), end = true)
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE &&
            harness.messages.recent(harness.channel, 10).any { it.msgid == "fresh-socket" } }
        assertFalse(harness.messages.recent(harness.channel, 10).any { it.msgid == "stale-old-socket" })
    }

    @Test
    fun localOnlyServerRetainsCachedTranscriptWhenOlderHistoryIsUnavailable() = withHarness(history = false) { server, harness ->
        harness.seed(harness.channel, "cached-only")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.singleOrNull()?.msgid == "cached-only" }
        val identity = harness.buffer().messages.single().localId
        harness.coordinator.startAll()
        await { harness.buffer().members.any { it.nick == "alice" } }
        harness.coordinator.loadOlderHistory(key)
        await { harness.buffer().history.older.status == HistoryLoadStatus.UNSUPPORTED }
        assertEquals(identity, harness.buffer().messages.single { it.msgid == "cached-only" }.localId)
        assertTrue(server.queries.isEmpty())
        assertEquals("cached-only", harness.messages.recent(harness.channel, 10).single().msgid)
    }

    @Test
    fun emptySuccessfulBetweenClosesResidualGapWithoutDiscardingCachedRows() = withHarness { server, harness ->
        harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.singleOrNull()?.msgid == "anchor" }
        harness.coordinator.startAll()
        val latest = query(server, "LATEST", "#room")
        server.reply(latest, listOf(WireRow("remote-6", BASE + 6_000)))
        await { harness.buffer().history.gaps.size == 1 && harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE }
        val gap = harness.buffer().history.gaps.single()
        val offset = server.queries.size
        harness.coordinator.fillHistoryGap(key, gap.id)
        val between = query(server, "BETWEEN", "#room", offset)
        assertEquals(listOf("BETWEEN", "#room", "msgid=anchor", "msgid=remote-6", "5"), between.message.parameters)
        server.reply(between, emptyList())
        await { harness.buffer().history.gaps.isEmpty() }
        assertEquals(setOf("anchor", "remote-6"), harness.buffer().messages.map { it.msgid }.toSet())
        await { harness.messages.recent(harness.channel, 10).size == 2 }
    }

    @Test
    fun multilineHistoryPreservesCompleteBodyIdentityAndSearchAcrossManyWireFragments() = withHarness { server, harness ->
        harness.coordinator.startAll()
        val request = query(server, "LATEST", "#room")
        val lines = (0 until 700).map { if (it == 521) "needlelargepage" else "line${it.toString().padStart(4, '0')}" }
        server.replyMultiline(request, "large-page", lines)
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE &&
            harness.messages.recent(harness.channel, 10).any { it.msgid == "large-page" } }
        val message = harness.buffer().messages.single { it.msgid == "large-page" }
        val stored = harness.messages.recent(harness.channel, 10).single { it.msgid == "large-page" }
        assertEquals(lines.joinToString("\n"), message.text)
        assertEquals(message.text, stored.text)
        assertEquals("alice", message.sender)
        assertEquals(BASE + 1_000, message.timestampMs)
        assertTrue(message.playback)
        assertFalse(message.countsAsUnread)
        assertEquals(stored.rowId, harness.coordinator.searchMessages("needlelargepage").single().rowId)
    }

    @Test
    fun relatedFutureContextCannotAdvancePersistedReconnectAnchorOrLoseReplyIdentity() = withHarness { server, harness ->
        harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.startAll()
        val request = query(server, "LATEST", "#room")
        server.reply(request, listOf(
            WireRow("primary", BASE + 1_000),
            WireRow("related", NOW + 60_000, "related quote", historyContext = true, replyTo = "primary"),
        ), end = true)
        await { harness.messages.recent(harness.channel, 10).any { it.msgid == "related" } }
        val related = harness.buffer().messages.single { it.msgid == "related" }
        assertTrue(related.historyContext)
        assertFalse(related.countsAsUnread)
        assertEquals("primary", related.replyToMsgid)
        assertTrue(harness.messages.recent(harness.channel, 10).single { it.msgid == "related" }.historyContext)
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().history.initial.status == HistoryLoadStatus.IDLE &&
            harness.buffer().messages.single { it.msgid == "related" }.storedRowId != null }
        assertEquals(related.localId, harness.buffer().messages.single { it.msgid == "related" }.localId)
        assertEquals("primary", harness.buffer().messages.single { it.msgid == "related" }.replyToMsgid)
        val offset = server.queries.size
        harness.coordinator.disconnect(harness.config.id)
        harness.coordinator.connect(harness.config)
        val reconnected = query(server, "LATEST", "#room", offset)
        assertEquals("msgid=primary", reconnected.message.parameters[2])
        server.reply(reconnected, emptyList())
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE }
    }

    @Test
    fun timedOutWildcardPlaybackCannotInjectLateBatchesBareMessagesOrNotifications() = withHarness(
        history = false, labeled = false, znc = true,
    ) { server, harness ->
        harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.any { it.msgid == "anchor" } }
        harness.coordinator.startAll()
        await { server.queries.any { it.operation == "*playback" } }
        val peer = server.peers.single()
        peer.send(":srv BATCH +held znc.in/playback #room")
        peer.send("@batch=held;time=${iso(BASE + 1_000)};msgid=partial :alice!u@h PRIVMSG #room :tester partial replay")
        awaitProcessed(peer, harness, "playback-started")
        harness.elapsed.addAndGet(6_001)
        peer.send("PING :expire-playback")
        val serverRef = ConversationRef.server(harness.config.id)
        await { harness.buffer(serverRef).history.discovery.status == HistoryLoadStatus.FAILED }
        assertTrue(harness.buffer(serverRef).history.discovery.requiresReconnect)
        peer.send(":srv BATCH +late znc.in/playback #room")
        peer.send("@batch=late;time=${iso(BASE + 2_000)};msgid=late-batch :alice!u@h PRIVMSG #room :tester late batch")
        peer.send(":srv BATCH -late")
        peer.send("@time=${iso(BASE + 3_000)};msgid=late-bare :alice!u@h PRIVMSG #room :tester late bare")
        awaitProcessed(peer, harness, "late-playback-barrier")
        assertFalse(harness.buffer().messages.any { it.msgid in setOf("partial", "late-batch", "late-bare") })
        assertFalse(harness.messages.recent(harness.channel, 20).any { it.msgid in setOf("partial", "late-batch", "late-bare") })
        assertTrue(harness.highlights.isEmpty())
    }

    @Test
    fun disconnectCancelsPendingCatchupAndExplicitRetryStartsFreshConnection() = withHarness { server, harness ->
        harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.any { it.msgid == "anchor" } }
        harness.coordinator.startAll()
        val pending = query(server, "LATEST", "#room")
        harness.coordinator.disconnect(harness.config.id)
        assertEquals(HistoryLoadStatus.CANCELLED, harness.buffer().history.catchUp.status)
        assertEquals(listOf("anchor"), harness.buffer().messages.mapNotNull { it.msgid })
        assertEquals(ConnectionStatus.DISCONNECTED, harness.coordinator.networks.value.single().status)
        assertEquals(harness.config.id, harness.coordinator.networks.value.single().id)
        val offset = server.queries.size
        harness.coordinator.retryHistory(key)
        val fresh = query(server, "LATEST", "#room", offset)
        assertTrue(fresh.peer !== pending.peer)
        server.reply(fresh, listOf(WireRow("after-cancel", BASE + 1_000)), end = true)
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE &&
            harness.buffer().messages.any { it.msgid == "after-cancel" } }
    }

    @Test
    fun clippedNativePlaybackRetainsGapUntilClosedRangeWitnessesCachedLowerBoundary() = withHarness(
        history = false, labeled = false, znc = true,
    ) { server, harness ->
        val rowId = harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.singleOrNull()?.msgid == "anchor" }
        val localId = harness.buffer().messages.single().localId
        harness.markers.advance(key, BASE + 500)
        harness.coordinator.startAll()
        val discovery = query(server, "*playback", "PLAY")
        assertEquals("*", discovery.message.parameters[2])
        server.replyPlayback(discovery, "#room", (6..10).map { WireRow("native-$it", BASE + it * 1_000) })
        server.replyPlayback(discovery, "carol", listOf(WireRow("new-dm", BASE + 5_000, "tester offline DM")))
        awaitProcessed(discovery.peer, harness, "discovery-complete")
        harness.elapsed.addAndGet(2_001)
        discovery.peer.send("PING :finish-discovery")
        await { harness.buffer().history.gaps.size == 1 }
        val carol = ConversationRef.directMessage(harness.config.id, "carol")
        assertEquals("tester offline DM", harness.buffer(carol).messages.single().text)
        val gap = harness.buffer().history.gaps.single()
        assertEquals(BASE, gap.from.timestampMs)
        assertEquals(BASE + 6_000, gap.to.timestampMs)
        var offset = server.queries.size
        harness.coordinator.fillHistoryGap(key, gap.id)
        val partial = query(server, "*playback", "PLAY", offset)
        assertEquals("#room", partial.message.parameters[2])
        server.replyPlayback(partial, "#room", (3..6).map { WireRow("native-$it", BASE + it * 1_000) })
        await { harness.buffer().history.gaps.single().to.timestampMs == BASE + 3_000 }
        assertEquals(gap.from, harness.buffer().history.gaps.single().from)
        offset = server.queries.size
        harness.coordinator.fillHistoryGap(key, gap.id)
        val complete = query(server, "*playback", "PLAY", offset)
        server.replyPlayback(complete, "#room", listOf(WireRow("anchor", BASE, "cached anchor")) +
            (1..3).map { WireRow("native-$it", BASE + it * 1_000) })
        await { harness.buffer().history.gaps.isEmpty() && harness.messages.recent(harness.channel, 30).size == 11 }
        assertEquals((1..10).map { "tester archive native-$it" },
            harness.buffer().messages.filter { it.text != "cached anchor" }.map { it.text })
        val anchor = harness.buffer().messages.single { it.msgid == "anchor" }
        assertEquals(localId, anchor.localId)
        assertEquals(rowId, anchor.storedRowId)
        assertEquals(BASE + 500, harness.markers.marker(key))
        assertTrue(harness.highlights.isEmpty())
        assertEquals(rowId, harness.coordinator.searchMessages("cached").single().rowId)
    }

    @Test
    fun failedCatchupCanReconnectAfterUserDisconnectWithoutDiscardingCache() = withHarness { server, harness ->
        val rowId = harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.singleOrNull()?.storedRowId == rowId }
        val localId = harness.buffer().messages.single().localId
        harness.coordinator.startAll()
        val failed = query(server, "LATEST", "#room")
        server.fail(failed)
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.FAILED }
        harness.coordinator.disconnect(harness.config.id)
        val offset = server.queries.size
        harness.coordinator.retryHistory(key)
        val fresh = query(server, "LATEST", "#room", offset)
        assertTrue(fresh.peer !== failed.peer)
        server.reply(fresh, listOf(WireRow("after-offline-retry", BASE + 1_000)), end = true)
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE &&
            harness.buffer().messages.any { it.msgid == "after-offline-retry" } }
        assertEquals(localId, harness.buffer().messages.single { it.msgid == "anchor" }.localId)
        assertEquals(rowId, harness.buffer().messages.single { it.msgid == "anchor" }.storedRowId)
    }

    @Test
    fun invalidNativeMetadataCannotInventGapOrPersistFallbackClockMessage() = withHarness(
        history = false, labeled = false, znc = true,
    ) { server, harness ->
        val rowId = harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.singleOrNull()?.storedRowId == rowId }
        harness.coordinator.startAll()
        val request = query(server, "*playback", "PLAY")
        request.peer.send(":srv BATCH +invalid znc.in/playback #room")
        request.peer.send("@batch=invalid :alice!u@h PRIVMSG #room :invalid missing timestamp")
        request.peer.send(":srv BATCH -invalid")
        awaitProcessed(request.peer, harness, "invalid-complete")
        harness.elapsed.addAndGet(2_001)
        request.peer.send("PING :finish-invalid")
        val serverRef = ConversationRef.server(harness.config.id)
        await { harness.buffer(serverRef).history.discovery.status == HistoryLoadStatus.FAILED }
        assertTrue(harness.buffer().history.gaps.isEmpty())
        assertEquals(listOf("anchor"), harness.buffer().messages.map { it.msgid })
        assertEquals(listOf(rowId), harness.messages.recent(harness.channel, 20).map { it.rowId })
        assertTrue(harness.highlights.isEmpty())
    }

    @Test
    fun backgroundCatchupPreservesPreviouslyPersistedGapBeforeTranscriptIsOpened() = withHarness { server, harness ->
        harness.seed(harness.channel, "anchor")
        val existing = StoredHistoryGap("unfilled", BASE - 2_000, null, BASE - 1_000, null)
        harness.coverage.put(harness.channel.storageKey, listOf(existing))
        harness.coordinator.startAll()
        val request = query(server, "LATEST", "#room")
        server.reply(request, listOf(WireRow("newer-boundary", BASE + 6_000)))
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE &&
            harness.messages.recent(harness.channel, 10).any { it.msgid == "newer-boundary" } }
        val gaps = harness.buffer().history.gaps
        assertEquals(existing.fromTimestampMs, gaps.single { it.id == "unfilled" }.from.timestampMs)
        assertEquals(BASE + 6_000, gaps.single { it.id != "unfilled" }.to.timestampMs)
        assertTrue(existing in harness.coverage.gaps(harness.channel.storageKey))
    }

    private companion object {
        const val BASE = 1_800_000_000_000L
        const val NOW = BASE + 3_600_000L
        fun iso(time: Long): String = Instant.ofEpochMilli(time).toString()
    }
}
