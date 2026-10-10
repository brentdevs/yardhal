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
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.HistoryAnchor
import dev.brentdevs.yardhal.core.protocol.ISupport
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
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
        private val referenceTypes: String,
        private val targetsEnd: Boolean,
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
                        if (history || znc) peer.send(":srv 005 tester CHATHISTORY=5 MSGREFTYPES=$referenceTypes :supported")
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
            query.peer.send("${tags(query, targetsEnd)}:srv BATCH +$batch draft/chathistory-targets")
            targets.filter { it.second in from..to }.take(query.message.parameters.last().toInt()).forEach { (nick, time) ->
                query.peer.send("@batch=$batch :srv CHATHISTORY TARGETS $nick ${iso(time)}")
            }
            query.peer.send(":srv BATCH -$batch")
        }

        fun remember(id: String, timestamp: Long) {
            timestamps[id] = timestamp
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

    private class Harness(val directory: java.io.File, private val server: Server, val scope: CoroutineScope) {
        val database = YardhalDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val config = NetworkConfig(id = "network", name = "History", host = "127.0.0.1", port = server.port,
            tls = false, nick = "tester", autojoin = listOf("#room"))
        val messages = MessageStore(database.messageDao())
        val markers = ReadMarkerStore(directory)
        val coverage = HistoryCoverageStore(directory)
        val elapsed = AtomicLong(0)
        val time = AtomicLong(NOW)
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
            notifier = dev.brentdevs.yardhal.service.ConversationNotifier { event, eligibility ->
                if (eligibility.appended && !eligibility.sentByUs && !eligibility.playback && !eligibility.historyContext &&
                    !eligibility.muted && !eligibility.read && !eligibility.ignored && !eligibility.viewed) highlights += event.text
            },
            clock = time::get,
            historyElapsedClock = elapsed::get,
        )
        val channel = ConversationRef.channel(config.id, "#room")

        fun buffer(ref: ConversationRef = channel): ConversationBuffer =
            assertNotNull(coordinator.buffers.value[ref.storageKey])

        suspend fun seed(ref: ConversationRef, id: String, timestamp: Long = BASE): Long {
            server.remember(id, timestamp)
            return assertNotNull(messages.recordWithRowId(StoredMessage(networkId = config.id, conversation = ref,
                msgid = id, senderNick = "alice", senderUser = "u", senderHost = "h", kind = MessageKind.PRIVMSG,
                text = "cached $id", sentByUs = false, timestampMs = timestamp)))
        }
    }

    private class ControlledHost(private val coverage: HistoryCoverageStore) : HistoryHost {
        private val selectionLock = Any()
        private val sessionLock = Any()
        private val localIds = AtomicLong()
        @Volatile
        private var buffers: Map<String, ConversationBuffer> = emptyMap()
        @Volatile
        var beforeUpdateLock: (() -> Unit)? = null
        @Volatile
        var beforeUpdateTransform: (() -> Unit)? = null
        val storage = LinkedBlockingQueue<suspend () -> Unit>()
        val outgoing: MutableList<String> = CopyOnWriteArrayList()
        val replayed: MutableList<IrcMessage> = CopyOnWriteArrayList()

        override val historyBuffers: Map<String, ConversationBuffer>
            get() = buffers

        override fun historyRef(networkId: String, target: String): ConversationRef =
            if (target.startsWith('#')) ConversationRef.channel(networkId, target) else ConversationRef.directMessage(networkId, target)

        override fun ensureHistoryBuffer(ref: ConversationRef) {
            synchronized(selectionLock) {
                val gaps = coverage.gaps(ref.storageKey).map {
                    HistoryGap(it.id, HistoryAnchor(it.fromTimestampMs, it.fromMsgid), HistoryAnchor(it.toTimestampMs, it.toMsgid))
                }
                buffers = buffers + (ref.storageKey to ConversationBuffer(ref, ref.rawTarget, history = ConversationHistory(gaps = gaps)))
            }
        }

        override fun updateHistoryBuffer(key: String, transform: (ConversationBuffer) -> ConversationBuffer) {
            beforeUpdateLock?.invoke()
            synchronized(selectionLock) {
                beforeUpdateTransform?.invoke()
                val buffer = buffers[key] ?: return
                buffers = buffers + (key to transform(buffer))
            }
        }

        override suspend fun mergeStoredHistory(ref: ConversationRef, messages: List<StoredMessage>) {
            synchronized(selectionLock) {
                val buffer = buffers[ref.storageKey] ?: return
                buffers = buffers + (ref.storageKey to buffer.copy(messages =
                    mergeSearchContext(buffer.messages, messages, prependEqualTimestamp = true, nextLocalId = localIds::getAndIncrement)))
            }
        }

        override fun replayHistory(networkId: String, generation: Long, epoch: Long, result: HistoryResult,
                                   excludedKeys: Set<String>, beforeReplay: (List<InboundEffect>) -> Unit) {
            beforeReplay(emptyList())
            replayed.addAll(result.frames)
        }

        override fun sendHistory(networkId: String, generation: Long, epoch: Long, line: String): Boolean {
            outgoing += line
            return true
        }

        override fun historyConnectionActive(networkId: String, generation: Long, epoch: Long): Boolean = true
        override fun reconnectHistory(networkId: String): Unit = error("Unexpected reconnect for $networkId")
        override fun historySessionLock(networkId: String, generation: Long, epoch: Long): Any = sessionLock
        override fun enqueueHistoryStorage(operation: suspend () -> Unit) {
            storage += operation
        }

        fun close(key: String) {
            synchronized(selectionLock) { buffers = buffers - key }
        }

        suspend fun flushStorage() {
            while (true) (storage.poll() ?: return).invoke()
        }
    }

    private fun registerControlled(history: HistoryCoordinator, networkId: String) {
        history.registered(networkId, 1, 1, CaseMapping.RFC1459, "tester",
            ISupport.parse(listOf("CHATHISTORY=5", "MSGREFTYPES=msgid,timestamp")), setOf("draft/chathistory"))
    }

    private fun replyControlled(history: HistoryCoordinator, networkId: String, batch: String, target: String?,
                                rows: List<WireRow>, end: Boolean = false) {
        val type = if (target == null) "draft/chathistory-targets" else "chathistory $target"
        val tags = if (end) "@draft/chathistory-end " else ""
        assertTrue(history.receive(networkId, 1, 1, assertNotNull(IrcMessage.parse("$tags:srv BATCH +$batch $type"))))
        for (row in rows) {
            assertTrue(history.receive(networkId, 1, 1, assertNotNull(IrcMessage.parse(
                "@batch=$batch;time=${iso(row.timestamp)};msgid=${row.id} :alice!u@h PRIVMSG $target :${row.text}"))))
        }
        assertTrue(history.receive(networkId, 1, 1, assertNotNull(IrcMessage.parse(":srv BATCH -$batch"))))
    }

    private fun withHarness(
        history: Boolean = true,
        labeled: Boolean = true,
        targets: List<Pair<String, Long>> = emptyList(),
        registrationNotice: Boolean = false,
        znc: Boolean = false,
        referenceTypes: String = "msgid,timestamp",
        targetsEnd: Boolean = true,
        body: suspend (Server, Harness) -> Unit,
    ) = runBlocking {
        val directory = Files.createTempDirectory("yardhal-history-consumer").toFile()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val server = Server(history, labeled, targets, registrationNotice, znc, referenceTypes, targetsEnd)
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
        await { server.queries.drop(after).any { it.operation == operation && it.target.equals(target, ignoreCase = true) } }
        return server.queries.drop(after).first { it.operation == operation && it.target.equals(target, ignoreCase = true) }
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
        assertTrue(retried.message.parameters[2].startsWith("timestamp="))
        assertEquals(BASE - 10_000, Instant.parse(retried.message.parameters[2].substringAfter('=')).toEpochMilli())
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
        harness.coordinator.connectNetwork(harness.config.id)
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
        await {
            harness.coordinator.buffers.value[carol.storageKey]?.messages?.singleOrNull()?.text == "tester offline DM"
        }
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

    @Test
    fun localPagesTransitionToBeforeAndAdvanceWireReferencesWithoutReorderingEqualTimeRows() = withHarness { server, harness ->
        for (index in 1..205) harness.seed(harness.channel, "local-$index")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.size == 200 && harness.buffer().history.initial.status == HistoryLoadStatus.IDLE }
        val original = harness.buffer().messages.associate { it.msgid to it.localId }
        harness.coordinator.startAll()
        server.reply(query(server, "LATEST", "#room"), emptyList(), end = true)
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE }
        var offset = server.queries.size
        harness.coordinator.loadOlderHistory(key)
        await { harness.buffer().messages.size == 205 && harness.buffer().history.older.status == HistoryLoadStatus.IDLE }
        assertFalse(server.queries.drop(offset).any { it.operation == "BEFORE" })
        harness.coordinator.loadOlderHistory(key)
        val first = query(server, "BEFORE", "#room", offset)
        assertEquals("msgid=local-1", first.message.parameters[2])
        server.reply(first, listOf(WireRow("before-a", BASE), WireRow("before-b", BASE), WireRow("local-1", BASE, "cached local-1")))
        await { harness.buffer().history.older.status == HistoryLoadStatus.IDLE && harness.buffer().messages.size == 207 }
        assertEquals(listOf("before-a", "before-b") + (1..205).map { "local-$it" }, harness.buffer().messages.map { it.msgid })
        offset = server.queries.size
        harness.coordinator.loadOlderHistory(key)
        val second = query(server, "BEFORE", "#room", offset)
        assertEquals("msgid=before-a", second.message.parameters[2])
        server.reply(second, listOf(WireRow("before-oldest", BASE - 1_000)))
        await { harness.buffer().history.older.status == HistoryLoadStatus.IDLE && harness.buffer().messages.size == 208 }
        offset = server.queries.size
        harness.coordinator.loadOlderHistory(key)
        val final = query(server, "BEFORE", "#room", offset)
        assertEquals("msgid=before-oldest", final.message.parameters[2])
        server.reply(final, emptyList(), end = true)
        await { harness.buffer().history.older.status == HistoryLoadStatus.EXHAUSTED &&
            harness.messages.recent(harness.channel, 300).size == 208 }
        val beforeSearch = harness.buffer().messages.map { it.msgid to it.localId }
        val rowId = harness.messages.recent(harness.channel, 300).single { it.msgid == "before-a" }.rowId
        val hit = harness.coordinator.searchMessages("archive", harness.config.id, harness.channel.normalizedTarget)
            .single { it.rowId == rowId }
        harness.seed(harness.channel, "search-only")
        harness.coordinator.openSearchHit(hit)
        await { harness.buffer().messages.any { it.msgid == "search-only" } }
        assertEquals(beforeSearch, harness.buffer().messages.filter { it.msgid != "search-only" }.map { it.msgid to it.localId })
        assertEquals(original, harness.buffer().messages.filter { it.msgid in original }.associate { it.msgid to it.localId })
        assertTrue(harness.highlights.isEmpty())
    }

    @Test
    fun timestampOnlyBeforeOverlapAtArchiveStartHonorsExplicitEnd() = withHarness(referenceTypes = "timestamp") { server, harness ->
        val rowId = harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.singleOrNull()?.storedRowId == rowId }
        val localId = harness.buffer().messages.single().localId
        harness.coordinator.startAll()
        server.reply(query(server, "LATEST", "#room"), emptyList(), end = true)
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE }
        val offset = server.queries.size
        harness.coordinator.loadOlderHistory(key)
        val before = query(server, "BEFORE", "#room", offset)
        assertEquals(BASE + 1, Instant.parse(before.message.parameters[2].substringAfter('=')).toEpochMilli())
        server.reply(before, listOf(WireRow("anchor", BASE, "cached anchor")), end = true)
        await { harness.buffer().history.older.status == HistoryLoadStatus.EXHAUSTED }
        assertEquals(localId, harness.buffer().messages.single().localId)
        assertEquals(rowId, harness.buffer().messages.single().storedRowId)
        assertEquals(1, harness.messages.recent(harness.channel, 10).size)
        assertFalse(harness.buffer().history.older.requiresReconnect)
    }

    @Test
    fun limitedTargetsWithoutEndExposeIncompleteDiscoveryWhileEveryReturnedConversationLoads() = withHarness(
        targets = (1..8).map { "dm-$it" to BASE + it * 1_000L }, targetsEnd = false,
    ) { server, harness ->
        harness.coordinator.startAll()
        val expected = setOf("#room") + (1..5).map { "dm-$it" }
        val answered = mutableSetOf<Query>()
        val loaded = mutableSetOf<String>()
        withTimeout(5_000) {
            while (loaded != expected) {
                await { server.queries.any { it.operation == "LATEST" && it !in answered } }
                val request = server.queries.first { it.operation == "LATEST" && it !in answered }
                answered += request
                loaded += request.target.lowercase()
                server.reply(request, listOf(WireRow("discovered-${request.target}", BASE + 10_000)), end = true)
            }
        }
        val ref = ConversationRef.server(harness.config.id)
        await { harness.buffer(ref).history.discovery.status == HistoryLoadStatus.EXHAUSTED &&
            expected.all { target ->
                val conversation = if (target.startsWith('#')) harness.channel else ConversationRef.directMessage(harness.config.id, target)
                harness.coordinator.buffers.value[conversation.storageKey]?.history?.catchUp?.status == HistoryLoadStatus.IDLE
            } }
        assertTrue(harness.buffer(ref).history.discoveryTruncated)
        assertFalse((6..8).any { ConversationRef.directMessage(harness.config.id, "dm-$it").storageKey in harness.coordinator.buffers.value })
        assertFalse(harness.coordinator.buffers.value.values.flatMap { it.messages }.any { it.text.contains("TARGETS") })
    }

    @Test
    fun repeatedBoundedPlaybackReconnectsMergeDurableGapsWithoutOpeningClosedConversations() = withHarness(
        history = false, labeled = false, znc = true,
    ) { server, harness ->
        val ref = ConversationRef.channel(harness.config.id, "#closed")
        val old = BASE - 20L * 24 * 60 * 60 * 1_000
        harness.seed(ref, "closed-anchor", old)
        val existing = StoredHistoryGap("saved-closed", old - 1_000, null, old + 1_000, null)
        harness.coverage.put(ref.storageKey, listOf(existing))
        val key = harness.coordinator.ensureConversation(harness.config.id, "#closed")
        harness.coordinator.leaveConversation(harness.config.id, key)
        var offset = 0
        repeat(3) { reconnect ->
            harness.time.set(NOW + reconnect * 24L * 60 * 60 * 1_000)
            harness.coordinator.connectNetwork(harness.config.id)
            query(server, "*playback", "PLAY", offset)
            val gaps = HistoryCoverageStore(harness.directory).gaps(ref.storageKey)
            assertEquals(1, gaps.size)
            assertEquals(existing.fromTimestampMs, gaps.single().fromTimestampMs)
            assertEquals(harness.time.get() - 7L * 24 * 60 * 60 * 1_000, gaps.single().toTimestampMs)
            assertFalse(ref.storageKey in harness.coordinator.buffers.value)
            offset = server.queries.size
            harness.coordinator.disconnect(harness.config.id)
        }
        harness.coordinator.ensureConversation(harness.config.id, "#closed")
        assertEquals(1, harness.buffer(ref).history.gaps.size)
        assertEquals(existing.fromTimestampMs, harness.buffer(ref).history.gaps.single().from.timestampMs)
    }

    @Test
    fun inclusivePlaybackUpperBoundaryAdvancesFiniteEmptyWindowsWithoutInventingGapsOrArchiveEnd() = withHarness(
        history = false, labeled = false, znc = true,
    ) { server, harness ->
        harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.singleOrNull()?.msgid == "anchor" }
        harness.coordinator.startAll()
        val discovery = query(server, "*playback", "PLAY")
        server.replyPlayback(discovery, "#room", listOf(WireRow("anchor", BASE, "cached anchor")))
        awaitProcessed(discovery.peer, harness, "boundary-discovery")
        harness.elapsed.addAndGet(2_001)
        discovery.peer.send("PING :finish-boundary-discovery")
        val serverRef = ConversationRef.server(harness.config.id)
        await { harness.buffer(serverRef).history.discovery.status == HistoryLoadStatus.IDLE }
        var offset = server.queries.size
        harness.coordinator.loadOlderHistory(key)
        val first = query(server, "*playback", "PLAY", offset)
        server.replyPlayback(first, "#room", listOf(WireRow("anchor", BASE, "cached anchor")))
        val firstBefore = BASE - 6L * 60 * 60 * 1_000
        await { harness.buffer().history.older.status == HistoryLoadStatus.WINDOW_EMPTY }
        assertEquals(firstBefore, harness.buffer().history.older.windowBeforeMs)
        assertTrue(harness.buffer().history.gaps.isEmpty())
        assertTrue(HistoryCoverageStore(harness.directory).gaps(key).isEmpty())
        offset = server.queries.size
        harness.coordinator.loadOlderHistory(key)
        val second = query(server, "*playback", "PLAY", offset)
        assertEquals(firstBefore, second.message.parameters[4].toBigDecimal().movePointRight(3).toLong())
        server.replyPlayback(second, "#room", emptyList())
        await { harness.buffer().history.older.status == HistoryLoadStatus.WINDOW_EMPTY &&
            harness.buffer().history.older.windowBeforeMs == firstBefore - 6L * 60 * 60 * 1_000 }
        assertTrue(harness.buffer().history.gaps.isEmpty())
        assertEquals(1, harness.messages.recent(harness.channel, 10).size)
    }

    @Test
    fun closingUnlabelledHistoryDrainsOldReplyBeforeNextConversationWithoutReconnect() = withHarness(labeled = false) { server, harness ->
        harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.startAll()
        val old = query(server, "LATEST", "#room")
        harness.coordinator.leaveConversation(harness.config.id, key)
        val bob = ConversationRef.directMessage(harness.config.id, "Bob")
        val bobKey = harness.coordinator.ensureConversation(harness.config.id, "Bob")
        harness.coordinator.loadPersistedHistory(bobKey)
        await { harness.buffer(bob).history.catchUp.status == HistoryLoadStatus.LOADING }
        assertFalse(server.queries.any { it.operation == "LATEST" && it.target.equals("Bob", ignoreCase = true) })
        server.reply(old, listOf(WireRow("retired-closed", BASE + 1_000)), end = true)
        val next = query(server, "LATEST", "bob")
        assertTrue(next.peer === old.peer)
        assertEquals(1, server.peers.size)
        assertFalse(key in harness.coordinator.buffers.value)
        assertFalse(harness.messages.recent(harness.channel, 10).any { it.msgid == "retired-closed" })
        server.reply(next, listOf(WireRow("bob-after-close", BASE + 2_000)), end = true)
        await { harness.buffer(bob).history.catchUp.status == HistoryLoadStatus.IDLE &&
            harness.messages.recent(bob, 10).any { it.msgid == "bob-after-close" } }
        assertFalse(harness.buffer(bob).history.catchUp.requiresReconnect)
    }

    @Test
    fun channelRenameDrainsUnlabelledResponseAndReissuesForNewTargetWithoutNetworkPoisoning() = withHarness(labeled = false) { server, harness ->
        val oldRef = harness.channel
        val newRef = ConversationRef.channel(harness.config.id, "#renamed")
        harness.seed(oldRef, "room-anchor")
        harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.startAll()
        val answered = mutableSetOf<Query>()
        var old: Query? = null
        withTimeout(5_000) {
            while (old == null) {
                await { server.queries.any { it.operation == "LATEST" && it !in answered } }
                val request = server.queries.first { it.operation == "LATEST" && it !in answered }
                answered += request
                if (request.target.equals("#room", ignoreCase = true)) old = request else server.reply(request, emptyList(), end = true)
            }
        }
        val pending = assertNotNull(old)
        pending.peer.send(":srv RENAME #room #renamed :Moved")
        awaitProcessed(pending.peer, harness, "rename-draining")
        assertFalse(oldRef.storageKey in harness.coordinator.buffers.value)
        assertTrue(newRef.storageKey in harness.coordinator.buffers.value)
        assertFalse(server.queries.any { it.operation == "LATEST" && it.target.equals("#renamed", ignoreCase = true) })
        server.reply(pending, listOf(WireRow("retired-room", BASE + 1_000)), end = true)
        val next = query(server, "LATEST", "#renamed")
        assertTrue(next.peer === pending.peer)
        server.reply(next, listOf(WireRow("room-after-rename", BASE + 2_000)), end = true)
        await { harness.buffer(newRef).history.catchUp.status == HistoryLoadStatus.IDLE &&
            harness.messages.recent(newRef, 10).any { it.msgid == "room-after-rename" } }
        assertFalse(harness.buffer(newRef).messages.any { it.msgid == "retired-room" })
        assertFalse(harness.messages.recent(newRef, 10).any { it.msgid == "retired-room" })
        assertEquals(1, server.peers.size)
        assertFalse(harness.buffer(newRef).history.catchUp.requiresReconnect)
    }

    @Test
    fun localOlderCompletionAndConcurrentRemoteBootstrapBothProgressAcrossSelectionLock() = withHarness { _, harness ->
        for (index in 1..201) harness.seed(harness.channel, "concurrent-$index")
        val host = ControlledHost(harness.coverage)
        host.ensureHistoryBuffer(harness.channel)
        val history = HistoryCoordinator(harness.messages, harness.coverage, harness.scope, harness.time::get, harness.elapsed::get, host)
        registerControlled(history, harness.config.id)
        host.flushStorage()
        replyControlled(history, harness.config.id, "discovery", null, emptyList())
        assertTrue(history.open(harness.channel.storageKey))
        host.flushStorage()
        assertEquals(200, host.historyBuffers.getValue(harness.channel.storageKey).messages.size)
        history.older(harness.channel.storageKey)
        val localRead = assertNotNull(host.storage.poll())
        val selectionHeld = CountDownLatch(1)
        val bootstrapUpdating = CountDownLatch(1)
        val releaseSelection = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        host.beforeUpdateTransform = {
            if (pauseOnce.compareAndSet(true, false)) {
                selectionHeld.countDown()
                assertTrue(releaseSelection.await(5, TimeUnit.SECONDS))
            }
        }
        val executor = Executors.newFixedThreadPool(2) { operation -> Thread(operation, "history-lock-regression").also { it.isDaemon = true } }
        try {
            val local = executor.submit { runBlocking { localRead() } }
            assertTrue(selectionHeld.await(5, TimeUnit.SECONDS))
            host.beforeUpdateLock = { bootstrapUpdating.countDown() }
            val remote = executor.submit { history.bootstrap(harness.config.id, harness.channel) }
            assertTrue(bootstrapUpdating.await(5, TimeUnit.SECONDS))
            releaseSelection.countDown()
            local.get(5, TimeUnit.SECONDS)
            remote.get(5, TimeUnit.SECONDS)
            val buffer = host.historyBuffers.getValue(harness.channel.storageKey)
            assertEquals(201, buffer.messages.size)
            assertEquals(HistoryLoadStatus.IDLE, buffer.history.older.status)
            assertEquals(HistoryLoadStatus.LOADING, buffer.history.catchUp.status)
            assertTrue(host.outgoing.any { it.startsWith("CHATHISTORY LATEST #room") })
            history.reset(harness.config.id, "Test complete")
        } finally {
            releaseSelection.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun queuedStorageCannotRestoreCompletedGapOverNewGapOrResurrectRenamedCoverage() = withHarness { _, harness ->
        harness.seed(harness.channel, "anchor")
        val host = ControlledHost(harness.coverage)
        host.ensureHistoryBuffer(harness.channel)
        val history = HistoryCoordinator(harness.messages, harness.coverage, harness.scope, harness.time::get, harness.elapsed::get, host)
        registerControlled(history, harness.config.id)
        host.flushStorage()
        replyControlled(history, harness.config.id, "discovery", null, emptyList())
        history.bootstrap(harness.config.id, harness.channel)
        replyControlled(history, harness.config.id, "first-catchup", "#room", listOf(WireRow("boundary-one", BASE + 6_000)))
        val firstGap = host.historyBuffers.getValue(harness.channel.storageKey).history.gaps.single()
        history.fillGap(harness.channel.storageKey, firstGap.id)
        replyControlled(history, harness.config.id, "finished-gap", "#room", emptyList(), end = true)
        assertTrue(host.historyBuffers.getValue(harness.channel.storageKey).history.gaps.isEmpty())
        history.bootstrap(harness.config.id, harness.channel)
        replyControlled(history, harness.config.id, "new-catchup", "#room", listOf(WireRow("boundary-two", BASE + 12_000)))
        host.flushStorage()
        val newGap = HistoryCoverageStore(harness.directory).gaps(harness.channel.storageKey).single()
        assertEquals("boundary-one", newGap.fromMsgid)
        assertEquals("boundary-two", newGap.toMsgid)
        val renamed = ConversationRef.channel(harness.config.id, "#renamed")
        history.rename(harness.channel, renamed)
        host.close(harness.channel.storageKey)
        host.ensureHistoryBuffer(renamed)
        host.flushStorage()
        assertTrue(HistoryCoverageStore(harness.directory).gaps(harness.channel.storageKey).isEmpty())
        assertEquals(listOf(newGap), HistoryCoverageStore(harness.directory).gaps(renamed.storageKey))
        assertEquals(newGap.id, host.historyBuffers.getValue(renamed.storageKey).history.gaps.single().id)
        assertTrue(host.replayed.any { it.tag("msgid") == "boundary-two" })
        history.removeNetwork(harness.config.id)
        host.flushStorage()
        assertTrue(HistoryCoverageStore(harness.directory).gaps(renamed.storageKey).isEmpty())
    }

    @Test
    fun explicitReconnectRetryResumesFailedGapOnFreshSocketWithoutDiscardingCache() = withHarness(labeled = false) { server, harness ->
        harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.singleOrNull()?.msgid == "anchor" }
        val identity = harness.buffer().messages.single().localId
        harness.coordinator.startAll()
        server.reply(query(server, "LATEST", "#room"), listOf(WireRow("retry-gap-boundary", BASE + 6_000)))
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE && harness.buffer().history.gaps.size == 1 }
        val gap = harness.buffer().history.gaps.single()
        var offset = server.queries.size
        harness.coordinator.fillHistoryGap(key, gap.id)
        val timedOut = query(server, "BETWEEN", "#room", offset)
        harness.elapsed.addAndGet(6_001)
        timedOut.peer.send("PING :expire-gap")
        await { harness.buffer().history.gaps.single().state.requiresReconnect }
        offset = server.queries.size
        harness.coordinator.retryHistory(key)
        val retried = query(server, "BETWEEN", "#room", offset)
        assertTrue(retried.peer !== timedOut.peer)
        assertEquals(timedOut.message.parameters, retried.message.parameters)
        assertEquals(HistoryLoadStatus.LOADING, harness.buffer().history.gaps.single().state.status)
        server.reply(retried, emptyList(), end = true)
        await { harness.buffer().history.gaps.isEmpty() && HistoryCoverageStore(harness.directory).gaps(key).isEmpty() }
        assertEquals(identity, harness.buffer().messages.single { it.msgid == "anchor" }.localId)
        assertEquals(2, server.peers.size)
    }

    @Test
    fun closingConversationDuringWildcardPlaybackDiscardsItsFramesAndDrainsBeforeNextPlay() = withHarness(
        history = false, labeled = false, znc = true,
    ) { server, harness ->
        harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.singleOrNull()?.msgid == "anchor" }
        harness.coordinator.startAll()
        val wildcard = query(server, "*playback", "PLAY")
        wildcard.peer.send(":srv BATCH +closing-playback znc.in/playback #room")
        wildcard.peer.send("@batch=closing-playback;time=${iso(BASE + 1_000)} :alice!u@h PRIVMSG #room :retired partial playback")
        awaitProcessed(wildcard.peer, harness, "closing-playback-started")
        harness.coordinator.leaveConversation(harness.config.id, key)
        val bob = ConversationRef.directMessage(harness.config.id, "Bob")
        val bobKey = harness.coordinator.ensureConversation(harness.config.id, "Bob")
        harness.coordinator.loadPersistedHistory(bobKey)
        await { harness.buffer(bob).history.initial.status == HistoryLoadStatus.IDLE }
        val offset = server.queries.size
        harness.coordinator.loadOlderHistory(bobKey)
        assertEquals(HistoryLoadStatus.LOADING, harness.buffer(bob).history.older.status)
        assertEquals(offset, server.queries.size)
        wildcard.peer.send(":srv BATCH -closing-playback")
        server.replyPlayback(wildcard, "#room", listOf(WireRow("retired-late", BASE + 2_000, "retired late playback")))
        awaitProcessed(wildcard.peer, harness, "closing-playback-drained")
        harness.elapsed.addAndGet(2_001)
        wildcard.peer.send("PING :finish-closed-wildcard")
        val next = query(server, "*playback", "PLAY", offset)
        assertEquals("Bob", next.message.parameters[2])
        assertTrue(next.peer === wildcard.peer)
        assertFalse(key in harness.coordinator.buffers.value)
        assertEquals(listOf("anchor"), harness.messages.recent(harness.channel, 10).map { it.msgid })
        server.replyPlayback(next, "Bob", listOf(WireRow("new-bob-playback", BASE + 3_000, "new Bob playback")))
        await { harness.buffer(bob).history.older.status == HistoryLoadStatus.IDLE &&
            harness.messages.recent(bob, 10).any { it.text == "new Bob playback" } }
        assertEquals(1, server.peers.size)
        assertFalse(harness.buffer(bob).history.older.requiresReconnect)
    }

    @Test
    fun equalTimeMessageIdGapsRemainDistinctWithoutInventingBoundaryCoverage() = withHarness { server, harness ->
        harness.seed(harness.channel, "anchor")
        val key = harness.coordinator.ensureConversation(harness.config.id, "#room")
        harness.coordinator.loadPersistedHistory(key)
        await { harness.buffer().messages.singleOrNull()?.msgid == "anchor" }
        harness.coordinator.startAll()
        server.reply(query(server, "LATEST", "#room"), listOf(WireRow("tied-gap-one", BASE)))
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE &&
            harness.messages.recent(harness.channel, 10).any { it.msgid == "tied-gap-one" } }
        val offset = server.queries.size
        harness.coordinator.disconnect(harness.config.id)
        harness.coordinator.connectNetwork(harness.config.id)
        val next = query(server, "LATEST", "#room", offset)
        assertEquals("msgid=tied-gap-one", next.message.parameters[2])
        server.reply(next, listOf(WireRow("tied-gap-two", BASE)))
        await { harness.buffer().history.catchUp.status == HistoryLoadStatus.IDLE && harness.buffer().history.gaps.size == 2 }
        assertEquals(setOf("anchor" to "tied-gap-one", "tied-gap-one" to "tied-gap-two"),
            harness.buffer().history.gaps.map { it.from.msgid to it.to.msgid }.toSet())
        assertEquals(2, HistoryCoverageStore(harness.directory).gaps(key).size)
    }

    private companion object {
        const val BASE = 1_800_000_000_000L
        const val NOW = BASE + 3_600_000L
        fun iso(time: Long): String = Instant.ofEpochMilli(time).toString()
    }
}
