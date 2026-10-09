package dev.brentdevs.yardhal.coordinator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.core.client.InMemoryStsPolicyStore
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.data.CachedPresence
import dev.brentdevs.yardhal.core.data.CachedUser
import dev.brentdevs.yardhal.core.data.ChannelMember
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.HistoryCoverageStore
import dev.brentdevs.yardhal.core.data.InMemoryCredentialVault
import dev.brentdevs.yardhal.core.data.MessageDao
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.MessageRow
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.OfflineConversationShell
import dev.brentdevs.yardhal.core.data.OfflineRoster
import dev.brentdevs.yardhal.core.data.OfflineStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.StorageRecovery
import dev.brentdevs.yardhal.core.data.StoredMessage
import dev.brentdevs.yardhal.core.data.WhoisInfo
import dev.brentdevs.yardhal.core.data.YardhalDatabase
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcMetadata
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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
class OfflineCoordinatorTests {
    private class Server(private val capabilities: String = "server-time draft/read-marker echo-message") : AutoCloseable {
        private val listener = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        private val executor = Executors.newCachedThreadPool()
        private val clients = CopyOnWriteArrayList<Socket>()
        @Volatile private var current: Socket? = null
        @Volatile var holdRegistration = false
        val received = CopyOnWriteArrayList<String>()
        val port: Int get() = listener.localPort

        init {
            executor.submit {
                while (!listener.isClosed) {
                    val socket = try {
                        listener.accept()
                    } catch (_: java.io.IOException) {
                        break
                    }
                    clients.add(socket)
                    current = socket
                    executor.submit {
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                        while (!socket.isClosed) {
                            val line = try {
                                reader.readLine()
                            } catch (_: java.io.IOException) {
                                null
                            } ?: break
                            received.add(line)
                            when {
                                line.startsWith("CAP LS") -> send(":srv CAP * LS :$capabilities", socket)
                                line.startsWith("CAP REQ :") -> send(":srv CAP * ACK :${line.substringAfter("CAP REQ :")}", socket)
                                line.startsWith("USER ") && !holdRegistration -> register(socket)
                                line.startsWith("PING ") -> send("PONG ${line.substringAfter("PING ")}", socket)
                            }
                        }
                    }
                }
            }
        }

        fun register(socket: Socket? = current) {
            send(":srv 001 tester :Welcome", socket)
            send(":srv 005 tester CASEMAPPING=rfc1459 CHANMODES=b,k,l,imnst WHOX :are supported", socket)
        }

        fun send(line: String, socket: Socket? = current) {
            val destination = socket ?: return
            synchronized(destination) {
                destination.getOutputStream().write((line + "\r\n").toByteArray(Charsets.UTF_8))
                destination.getOutputStream().flush()
            }
        }

        override fun close() {
            for (socket in clients) socket.close()
            listener.close()
            executor.shutdownNow()
        }
    }

    private class Harness(
        server: Server,
        autoConnect: Boolean = false,
        daoFactory: (MessageDao) -> MessageDao = { it },
        serverPassword: String? = null,
        onCreate: (LiveCoordinator) -> Unit = {},
    ) : AutoCloseable {
        private val directory = Files.createTempDirectory("yardhal-offline-coordinator").toFile()
        val database = YardhalDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val now = AtomicLong(1_800_000_000_000L)
        val vault = InMemoryCredentialVault().also { if (serverPassword != null) it.storePassword("offline-password", serverPassword) }
        val config = NetworkConfig("network", "Offline network", "127.0.0.1", server.port, tls = false,
            nick = "tester", autojoin = listOf("#room"), autoConnect = autoConnect,
            serverPasswordRef = serverPassword?.let { "offline-password" })
        val networks = NetworkStore(directory).also { it.add(config) }
        val messages = MessageStore(daoFactory(database.messageDao()))
        val offline = OfflineStore(database.offlineDao())
        val markers = ReadMarkerStore(directory)
        lateinit var coordinator: LiveCoordinator
            private set

        init {
            coordinator = LiveCoordinator(
                scope = scope,
                networkStore = networks,
                messageStore = messages,
                readMarkers = markers,
                historyCoverage = HistoryCoverageStore(directory),
                mutes = MuteStore(directory),
                vault = vault,
                channelOrder = ChannelOrderStore(directory),
                connectionFactory = ConnectionFactory { config, onStsUpgrade ->
                    onCreate(coordinator)
                    IrcConnection(IrcConnectionConfig(host = config.host, port = config.port, tls = false, nick = config.nick,
                        serverPassword = config.serverPassword,
                        capabilities = setOf("server-time", "draft/read-marker", "echo-message", "draft/channel-rename", IrcMetadata.CAPABILITY)),
                        onStsUpgrade = onStsUpgrade)
                },
                stsPolicies = InMemoryStsPolicyStore(),
                clock = now::get,
                offlineStore = offline,
            )
        }
        val room = ConversationRef.channel(config.id, "#room")

        suspend fun record(ref: ConversationRef = room, msgid: String? = "message", timestampMs: Long = now.get(),
            text: String = "hello", highlights: Boolean = false, highlightsKnown: Boolean = true): Long =
            assertNotNull(messages.recordWithRowId(StoredMessage(networkId = config.id, conversation = ref,
                msgid = msgid, senderNick = "alice", senderUser = "user", senderHost = "host", kind = MessageKind.PRIVMSG,
                text = text, sentByUs = false, timestampMs = timestampMs, highlightsMe = highlights,
                highlightsKnown = highlightsKnown)))

        suspend fun seedShell(ref: ConversationRef = room, roster: OfflineRoster? = null) {
            offline.saveConversation(OfflineConversationShell(ref, ref.rawTarget, now.get() - 1000,
                topic = "Saved topic", topicObservedAtMs = now.get() - 1000,
                modes = mapOf("n" to emptyList(), "k" to listOf("saved-key")), modesObservedAtMs = now.get() - 1000), roster)
        }

        override fun close() {
            scope.cancel()
            database.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) delay(10)
        }
    }

    @Test
    fun coldOfflineLaunchRestoresSelectedPageInteractionsMetadataAndFullUnreadCounts() = runBlocking {
        Server().use { server ->
            Harness(server).use { harness ->
                val parent = harness.record(msgid = "parent", timestampMs = harness.now.get() - 1000, text = "quoted parent")
                repeat(250) { index ->
                    harness.record(msgid = "m-$index", timestampMs = harness.now.get() - 900 + index,
                        highlights = index % 10 == 0)
                }
                val reply = StoredMessage(networkId = harness.config.id, conversation = harness.room, msgid = "reply",
                    senderNick = "alice", senderUser = "user", senderHost = "host", kind = MessageKind.PRIVMSG,
                    text = "image reply", sentByUs = false, timestampMs = harness.now.get(), highlightsMe = true,
                    replyToMsgid = "parent", replyParentRowId = parent, attachmentUrl = "https://example.com/object",
                    attachmentName = "photo.png", attachmentMimeType = "image/png", attachmentSizeBytes = 1024,
                    attachmentWidth = 40, attachmentHeight = 20, senderAccount = "alice-account")
                harness.messages.record(reply)
                harness.messages.applyReaction(harness.room, "reply", "bob", "👍", true, harness.now.get())
                val other = ConversationRef.directMessage(harness.config.id, "bob")
                harness.record(other, "dm", highlights = true)
                harness.seedShell(roster = OfflineRoster(listOf(ChannelMember("old", '@')),
                    mapOf("old" to CachedPresence(away = true, account = "old-account")), harness.now.get() - 1000,
                    completeAtObservation = true, truncated = true))
                harness.offline.saveUser(harness.config.id, CachedUser("alice", metadata = mapOf(IrcMetadata.KEY_DISPLAY_NAME to "Alice"),
                    observedAtMs = harness.now.get() - 1000))
                harness.offline.select(harness.room, harness.now.get() - 1000)
                harness.coordinator.updateConnectivity(false)
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                val restored = assertNotNull(harness.coordinator.buffers.value[harness.room.storageKey])
                assertEquals(harness.room.storageKey, harness.coordinator.restoredSelection.value)
                assertEquals(200, restored.messages.size)
                assertFalse(restored.messages.any { it.msgid == "parent" })
                val rendered = restored.messages.first { it.msgid == "reply" }
                assertEquals(ReplyPreview("alice", "quoted parent"), rendered.replyPreview)
                assertEquals(parent, rendered.localReplyParentRowId)
                assertEquals(reply.attachmentUrl, rendered.attachmentUrl)
                assertEquals(reply.attachmentName, rendered.attachmentName)
                assertEquals(reply.attachmentMimeType, rendered.attachmentMimeType)
                assertEquals(reply.attachmentSizeBytes, rendered.attachmentSizeBytes)
                assertEquals(reply.senderAccount, rendered.senderAccount)
                assertEquals(setOf("bob"), restored.reactions["reply"]?.get("👍"))
                assertEquals(252, restored.unreadCount)
                assertEquals(26, restored.mentionCount)
                assertTrue(restored.mentionCountKnown)
                assertEquals(1, harness.coordinator.buffers.value[other.storageKey]?.mentionCount)
                assertTrue(harness.coordinator.buffers.value[other.storageKey]?.messages?.isEmpty() == true)
                assertTrue(restored.cachedTopic && restored.cachedRoster && restored.cachedModes && restored.rosterTruncated)
                assertEquals(true, restored.memberPresence["old"]?.away)
                assertTrue(harness.coordinator.profiles.value[harness.config.id]?.forNick("alice")?.cached == true)
                assertTrue(server.received.isEmpty())
            }
        }
    }

    @Test
    fun savedPageAndRosterAreReadyBeforeFirstSocketAndCompleteLiveRefreshDoesNotEraseHistory() = runBlocking {
        Server().use { server ->
            val firstSocket = CompletableDeferred<Unit>()
            Harness(server, autoConnect = true, onCreate = { coordinator ->
                val restored = assertNotNull(coordinator.buffers.value["network|#room"])
                assertEquals("saved", restored.messages.first().msgid)
                assertEquals(listOf("old"), restored.members.map { it.nick })
                assertTrue(restored.cachedRoster)
                assertTrue(coordinator.restorationReady.value)
                firstSocket.complete(Unit)
            }).use { harness ->
                harness.record(msgid = "saved")
                harness.seedShell(roster = OfflineRoster(listOf(ChannelMember("old")), observedAtMs = harness.now.get() - 1000,
                    completeAtObservation = true))
                harness.offline.select(harness.room, harness.now.get())
                harness.coordinator.startAll()
                withTimeout(10_000) { firstSocket.await() }
                await { harness.coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                assertTrue(harness.coordinator.buffers.value.getValue(harness.room.storageKey).cachedRoster)
                server.send(":tester!u@h JOIN #room")
                server.send(":srv 353 tester = #room :tester new")
                server.send(":new!u@h JOIN #room")
                await { harness.coordinator.buffers.value.getValue(harness.room.storageKey).messages.any { it.kind == MessageKind.JOIN } }
                assertEquals(listOf("old"), harness.coordinator.buffers.value.getValue(harness.room.storageKey).members.map { it.nick })
                server.send(":srv 366 tester #room :End of NAMES")
                server.send(":srv 332 tester #room :Live topic")
                server.send(":srv 324 tester #room +nt")
                await {
                    val current = harness.coordinator.buffers.value.getValue(harness.room.storageKey)
                    !current.cachedRoster && !current.cachedTopic && !current.cachedModes
                }
                val live = harness.coordinator.buffers.value.getValue(harness.room.storageKey)
                assertEquals(setOf("tester", "new"), live.members.map { it.nick }.toSet())
                assertEquals("Live topic", live.topic)
                assertEquals(setOf("n", "t"), live.channelModes.keys)
                assertTrue(live.messages.any { it.msgid == "saved" })
                harness.coordinator.disconnect(harness.config.id)
                await { harness.coordinator.networks.value.single().status == ConnectionStatus.DISCONNECTED }
                val disconnected = harness.coordinator.buffers.value.getValue(harness.room.storageKey)
                assertEquals(live.topic, disconnected.topic)
                assertEquals(live.members, disconnected.members)
                assertEquals(live.channelModes, disconnected.channelModes)
                assertTrue(disconnected.cachedTopic && disconnected.cachedRoster && disconnected.cachedModes)
                assertTrue(disconnected.messages.any { it.msgid == "saved" })
            }
        }
    }

    @Test
    fun equalTimeReadCursorCoversFullHistoryAndLegacyMentionsBecomeKnownOnlyAfterRead() = runBlocking {
        Server().use { server ->
            Harness(server).use { harness ->
                val time = harness.now.get() - 1000
                val first = harness.record(msgid = "first", timestampMs = time, highlightsKnown = false)
                harness.record(msgid = "second", timestampMs = time, highlights = true)
                harness.markers.advance(harness.room.storageKey, time, first)
                harness.coordinator.updateConnectivity(false)
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                assertEquals(1, harness.coordinator.buffers.value.getValue(harness.room.storageKey).unreadCount)
                assertTrue(harness.coordinator.buffers.value.getValue(harness.room.storageKey).mentionCountKnown)
                val legacy = ConversationRef.directMessage(harness.config.id, "legacy")
                harness.record(legacy, "unknown", highlightsKnown = false)
                harness.record(legacy, "known", timestampMs = harness.now.get() + 1, highlights = true)
                val key = harness.coordinator.ensureConversation(harness.config.id, "legacy")
                harness.coordinator.loadPersistedHistory(key)
                await { harness.coordinator.buffers.value.getValue(key).unreadCount == 2 }
                assertEquals(1, harness.coordinator.buffers.value.getValue(key).mentionCount)
                assertFalse(harness.coordinator.buffers.value.getValue(key).mentionCountKnown)
                harness.coordinator.markRead(key)
                await {
                    val current = harness.coordinator.buffers.value.getValue(key)
                    current.unreadCount == 0 && current.mentionCountKnown && harness.markers.pending(harness.config.id).containsKey(key)
                }
                assertEquals(0, harness.coordinator.buffers.value.getValue(key).mentionCount)
            }
        }
    }

    @Test
    fun offlineReadReplaysOnceOnlyAfterRegistrationAndLateCapabilityAndOlderRemoteCannotRegress() = runBlocking {
        Server("server-time").use { server ->
            server.holdRegistration = true
            Harness(server).use { harness ->
                val time = harness.now.get() - 1000
                harness.record(timestampMs = time)
                harness.offline.select(harness.room, harness.now.get())
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                harness.coordinator.markRead(harness.room.storageKey)
                await { harness.markers.pending(harness.config.id).containsKey(harness.room.storageKey) }
                harness.coordinator.connectNetwork(harness.config.id)
                await { server.received.any { it.startsWith("USER ") } }
                assertTrue(server.received.none { it.startsWith("MARKREAD ") })
                server.register()
                await { harness.coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                assertTrue(server.received.none { it.startsWith("MARKREAD ") })
                server.send(":srv CAP tester NEW :draft/read-marker")
                await { server.received.count { it.startsWith("MARKREAD #room ") } == 1 }
                assertEquals(1, server.received.count { it.startsWith("MARKREAD #room ") })
                server.send(":srv MARKREAD #room timestamp=${Instant.ofEpochMilli(time - 1)}")
                server.send(":srv CAP tester ACK :draft/read-marker")
                await { harness.markers.cursor(harness.room.storageKey).timestampMs == time }
                assertEquals(0, harness.coordinator.buffers.value.getValue(harness.room.storageKey).unreadCount)
                assertTrue(harness.markers.pending(harness.config.id).containsKey(harness.room.storageKey))
                assertEquals(1, server.received.count { it.startsWith("MARKREAD #room ") })
                server.send(":srv MARKREAD #room timestamp=${Instant.ofEpochMilli(time)}")
                await { harness.markers.pending(harness.config.id).isEmpty() }
                assertEquals(0, harness.coordinator.buffers.value.getValue(harness.room.storageKey).unreadCount)
            }
        }
    }

    @Test
    fun cachedWhoisIsUsableOfflineRevalidatesOnReconnectAndNavigationRejectsLatePresentation() = runBlocking {
        Server().use { server ->
            Harness(server).use { harness ->
                harness.record()
                harness.offline.select(harness.room, harness.now.get())
                harness.offline.saveWhois(harness.config.id, WhoisInfo("alice", account = "saved-account", realName = "Saved Alice"),
                    harness.now.get() - 600_000)
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                assertTrue(harness.coordinator.canSendOffline(harness.config.id, harness.room.storageKey, "/whois alice"))
                assertTrue(harness.coordinator.sendText(harness.config.id, harness.room.storageKey, "/whois alice"))
                await { harness.coordinator.whoisPresentation.value != null }
                val cached = assertNotNull(harness.coordinator.whoisPresentation.value)
                assertTrue(cached.cached && cached.offline && !cached.refreshing)
                assertEquals("saved-account", cached.info.account)
                harness.coordinator.connectNetwork(harness.config.id)
                await { server.received.any { it == "WHOIS alice alice" } }
                assertTrue(harness.coordinator.whoisPresentation.value?.refreshing == true)
                server.send(":srv 311 tester alice user host * :Live Alice")
                server.send(":srv 330 tester alice live-account :is logged in as")
                server.send(":srv 318 tester alice :End of WHOIS")
                await { harness.coordinator.whoisPresentation.value?.cached == false }
                assertEquals("Live Alice", harness.coordinator.whois.value?.realName)
                assertEquals("live-account", harness.coordinator.whoisPresentation.value?.info?.account)
                assertTrue(harness.coordinator.sendText(harness.config.id, harness.room.storageKey, "/whois alice"))
                await { server.received.count { it == "WHOIS alice alice" } == 2 }
                harness.coordinator.trackSelection(harness.coordinator.ensureConversation(harness.config.id, "bob"))
                server.send(":srv 311 tester alice user host * :Late Alice")
                server.send(":srv 318 tester alice :End of WHOIS")
                await { harness.offline.whois(harness.config.id, "alice")?.info?.realName == "Late Alice" }
                assertNull(harness.coordinator.whoisPresentation.value)
                assertNull(harness.coordinator.whois.value)
            }
        }
    }

    @Test
    fun unchangedReadMarkersDoNotReportStorageFailureAtColdLaunch() = runBlocking {
        Server().use { server ->
            Harness(server).use { harness ->
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                assertNull(harness.coordinator.operationError.value)
                assertTrue(server.received.isEmpty())
            }
        }
    }

    @Test
    fun networkRemovalDuringColdEnumerationCannotRestoreDeletedSelection() = runBlocking {
        Server().use { server ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            Harness(server, autoConnect = true, daoFactory = { delegate ->
                object : MessageDao by delegate {
                    override suspend fun conversations(networkId: String): List<String> {
                        if (!entered.isCompleted) {
                            entered.complete(Unit)
                            release.await()
                        }
                        return delegate.conversations(networkId)
                    }
                }
            }).use { harness ->
                harness.record()
                harness.seedShell()
                harness.offline.select(harness.room, harness.now.get())
                harness.coordinator.startAll()
                withTimeout(10_000) { entered.await() }
                harness.coordinator.removeNetwork(harness.config.id)
                release.complete(Unit)
                await { harness.coordinator.restorationReady.value }
                assertTrue(harness.coordinator.buffers.value.isEmpty())
                assertNull(harness.coordinator.restoredSelection.value)
                assertTrue(harness.messages.knownConversations(harness.config.id).isEmpty())
                assertNull(harness.offline.selection())
                assertTrue(server.received.isEmpty())
            }
        }
    }

    @Test
    fun networkRemovalDuringSelectedReadCannotResurrectBuffersOrDurableCache() = runBlocking {
        Server().use { server ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            Harness(server, autoConnect = true, daoFactory = { delegate ->
                object : MessageDao by delegate {
                    override suspend fun recent(networkId: String, conversation: String, limit: Int): List<MessageRow> {
                        if (conversation == "#room" && !entered.isCompleted) {
                            entered.complete(Unit)
                            release.await()
                        }
                        return delegate.recent(networkId, conversation, limit)
                    }
                }
            }).use { harness ->
                harness.record()
                harness.seedShell()
                harness.offline.select(harness.room, harness.now.get())
                harness.coordinator.startAll()
                withTimeout(10_000) { entered.await() }
                harness.coordinator.removeNetwork(harness.config.id)
                release.complete(Unit)
                await { harness.coordinator.restorationReady.value && harness.messages.knownConversations(harness.config.id).isEmpty() &&
                    harness.offline.shells(harness.config.id).isEmpty() && harness.offline.selection() == null }
                assertTrue(harness.coordinator.buffers.value.isEmpty())
                assertTrue(harness.offline.shells(harness.config.id).isEmpty())
                assertNull(harness.offline.selection())
                assertTrue(server.received.isEmpty())
            }
        }
    }

    @Test
    fun localQuoteAndCanonicalEchoKeepOneDurableIdentity() = runBlocking {
        Server().use { server ->
            Harness(server).use { harness ->
                val parent = harness.record(msgid = null, text = "local quote")
                harness.offline.select(harness.room, harness.now.get())
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                harness.coordinator.connectNetwork(harness.config.id)
                await { harness.coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                val quoted = harness.coordinator.buffers.value.getValue(harness.room.storageKey).messages.first { it.storedRowId == parent }
                harness.coordinator.setReplyDraft(harness.config.id, harness.room.storageKey, quoted)
                assertTrue(harness.coordinator.sendText(harness.config.id, harness.room.storageKey, "quoted response"))
                await { harness.messages.recent(harness.room, 10).any { it.sentByUs && it.text == "quoted response" } }
                val optimistic = harness.messages.recent(harness.room, 10).first { it.sentByUs }
                assertEquals(parent, optimistic.replyParentRowId)
                await { server.received.any { IrcMessage.parse(it)?.command == "PRIVMSG" } }
                val outbound = server.received.mapNotNull(IrcMessage::parse).first { it.command == "PRIVMSG" }
                val label = outbound.tags["label"]?.let { ";label=$it" }.orEmpty()
                server.send("@msgid=echo$label :tester!u@h PRIVMSG #room :quoted response")
                await { harness.messages.recent(harness.room, 10).any { it.msgid == "echo" } }
                val rows = harness.messages.recent(harness.room, 10).filter { it.sentByUs }
                assertEquals(1, rows.size)
                assertEquals(optimistic.rowId, rows.single().rowId)
                assertEquals(parent, rows.single().replyParentRowId)
                val rendered = harness.coordinator.buffers.value.getValue(harness.room.storageKey).messages.first { it.msgid == "echo" }
                assertFalse(rendered.pendingEcho)
                assertEquals(ReplyPreview("alice", "local quote"), rendered.replyPreview)
            }
        }
    }

    @Test
    fun maintenanceIsSerializedBoundedAndThrottledUntilRuntimeDeadline() = runBlocking {
        Server().use { server ->
            data class RetentionCall(val messages: Int, val conversations: Int, val protected: List<String>, val nowMs: Long)
            val calls = CopyOnWriteArrayList<RetentionCall>()
            val orphanMaintenance = CopyOnWriteArrayList<Long>()
            Harness(server, daoFactory = { delegate ->
                object : MessageDao by delegate {
                    override suspend fun maintainNetworkAtomic(networkId: String, keepMessages: Int, keepConversations: Int,
                        protectedConversations: List<String>, nowMs: Long) {
                        calls.add(RetentionCall(keepMessages, keepConversations, protectedConversations, nowMs))
                        delegate.maintainNetworkAtomic(networkId, keepMessages, keepConversations, protectedConversations, nowMs)
                    }
                    override suspend fun pruneOrphanReactions(nowMs: Long) {
                        orphanMaintenance.add(nowMs)
                        delegate.pruneOrphanReactions(nowMs)
                    }
                }
            }).use { harness ->
                harness.record()
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value && orphanMaintenance.size == 1 }
                assertEquals(listOf(RetentionCall(250_000, 1000, listOf(ConversationRef.SERVER_TARGET), harness.now.get())), calls.toList())
                harness.coordinator.onForegroundResume()
                assertEquals(1, orphanMaintenance.size)
                harness.now.addAndGet(300_001)
                harness.coordinator.onForegroundResume()
                await { orphanMaintenance.size == 2 }
                assertEquals(2, calls.size)
                assertEquals(250_000, calls.last().messages)
                assertEquals(1000, calls.last().conversations)
                assertEquals(harness.now.get(), calls.last().nowMs)
            }
        }
    }

    @Test
    fun newerUserSelectionWinsWhileColdConversationEnumerationIsSuspended() = runBlocking {
        Server().use { server ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            Harness(server, daoFactory = { delegate ->
                object : MessageDao by delegate {
                    override suspend fun conversations(networkId: String): List<String> {
                        if (!entered.isCompleted) {
                            entered.complete(Unit)
                            release.await()
                        }
                        return delegate.conversations(networkId)
                    }
                }
            }).use { harness ->
                val other = ConversationRef.directMessage(harness.config.id, "bob")
                harness.record(text = "saved selection")
                harness.record(other, "other", text = "user selection")
                harness.offline.select(harness.room, harness.now.get())
                harness.coordinator.startAll()
                withTimeout(10_000) { entered.await() }
                harness.coordinator.trackSelection(harness.coordinator.ensureConversation(harness.config.id, "bob"))
                release.complete(Unit)
                await { harness.coordinator.restorationReady.value }
                assertNull(harness.coordinator.restoredSelection.value)
                assertEquals(other, harness.offline.selection())
                assertEquals("user selection", harness.coordinator.buffers.value.getValue(other.storageKey).messages.single().text)
                assertTrue(harness.coordinator.buffers.value.getValue(harness.room.storageKey).messages.isEmpty())
                assertTrue(server.received.isEmpty())
            }
        }
    }

    @Test
    fun serverConsoleSelectionRestoresItsDurablePageWithoutRosterOrSocket() = runBlocking {
        Server().use { server ->
            Harness(server).use { harness ->
                val console = ConversationRef.server(harness.config.id)
                harness.messages.record(StoredMessage(networkId = harness.config.id, conversation = console,
                    msgid = null, senderNick = "srv", senderUser = null, senderHost = null, kind = MessageKind.SYSTEM,
                    text = "saved console event", sentByUs = false, timestampMs = harness.now.get()))
                harness.offline.saveConversation(OfflineConversationShell(console, "Server", harness.now.get()))
                harness.offline.select(console, harness.now.get())
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                assertEquals(console.storageKey, harness.coordinator.restoredSelection.value)
                val restored = harness.coordinator.buffers.value.getValue(console.storageKey)
                assertEquals("saved console event", restored.messages.single().text)
                assertFalse(restored.cachedRoster)
                assertTrue(restored.members.isEmpty())
                assertEquals(0, restored.unreadCount)
                assertTrue(server.received.isEmpty())
            }
        }
    }

    @Test
    fun malformedSelectedRosterPreservesHealthyStateAndDurableRecoveryEvidence() = runBlocking {
        Server().use { server ->
            Harness(server).use { harness ->
                val healthy = ConversationRef.channel(harness.config.id, "#healthy")
                harness.record()
                harness.seedShell(roster = OfflineRoster(listOf(ChannelMember("bad", null)), observedAtMs = harness.now.get()))
                harness.seedShell(healthy, OfflineRoster(listOf(ChannelMember("good", '@')),
                    observedAtMs = harness.now.get(), completeAtObservation = true))
                val malformed = "{not-a-roster"
                harness.database.openHelper.writableDatabase.execSQL(
                    "UPDATE offline_conversations SET rosterJson = ? WHERE networkId = ? AND conversation = ?",
                    arrayOf(malformed, harness.config.id, harness.room.normalizedTarget))
                harness.offline.select(harness.room, harness.now.get())
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                val selected = harness.coordinator.buffers.value.getValue(harness.room.storageKey)
                assertEquals("hello", selected.messages.single().text)
                assertEquals("Saved topic", selected.topic)
                assertTrue(selected.cachedTopic && selected.cachedModes)
                assertFalse(selected.cachedRoster)
                assertTrue(selected.members.isEmpty())
                harness.coordinator.ensureMembers(harness.config.id, healthy.storageKey)
                await { harness.coordinator.buffers.value.getValue(healthy.storageKey).cachedRoster }
                assertEquals("good", harness.coordinator.buffers.value.getValue(healthy.storageKey).members.single().nick)
                assertTrue(StorageRecovery.notices.value.any { it.message.contains("not recovered") })
                harness.database.openHelper.readableDatabase.query("SELECT * FROM offline_quarantine").use { cursor ->
                    var retainedOriginal = false
                    while (cursor.moveToNext()) {
                        if ((0 until cursor.columnCount).any { cursor.getString(it)?.contains(malformed) == true }) retainedOriginal = true
                    }
                    assertTrue(retainedOriginal)
                }
                assertTrue(server.received.isEmpty())
            }
        }
    }

    @Test
    fun orphanReactionsAttachWhenParentArrivesAndOffPageRedactionSanitizesLocalQuote() = runBlocking {
        Server().use { server ->
            Harness(server).use { harness ->
                val parent = harness.record(msgid = "off-page", timestampMs = harness.now.get() - 1000, text = "private quote")
                repeat(210) { harness.record(msgid = "older-$it", timestampMs = harness.now.get() - 900 + it) }
                harness.messages.record(StoredMessage(networkId = harness.config.id, conversation = harness.room,
                    msgid = "reply", senderNick = "alice", senderUser = null, senderHost = null, kind = MessageKind.PRIVMSG,
                    text = "reply", sentByUs = false, timestampMs = harness.now.get(), replyParentRowId = parent))
                harness.messages.applyReaction(harness.room, "arriving", "bob", "👍", true, harness.now.get())
                harness.offline.select(harness.room, harness.now.get())
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                assertTrue(harness.coordinator.buffers.value.getValue(harness.room.storageKey).messages.none { it.msgid == "off-page" })
                harness.coordinator.connectNetwork(harness.config.id)
                await { harness.coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                server.send("@msgid=arriving :alice!u@h PRIVMSG #room :parent arrived")
                await { harness.coordinator.buffers.value.getValue(harness.room.storageKey).reactions["arriving"]?.get("👍") == setOf("bob") }
                server.send(":alice!u@h REDACT #room off-page :removed")
                await { harness.coordinator.buffers.value.getValue(harness.room.storageKey).messages.first { it.msgid == "reply" }.replyPreview?.redacted == true }
                val quote = assertNotNull(harness.coordinator.buffers.value.getValue(harness.room.storageKey).messages.first { it.msgid == "reply" }.replyPreview)
                assertEquals("message deleted", quote.text)
                assertNull(quote.attachmentUrl)
                assertTrue(assertNotNull(harness.messages.byRowId(harness.room, parent)).redacted)
            }
        }
    }

    @Test
    fun productionStartupBoundsRetainedConversationUnionAndPreservesOldSelectedPage() = runBlocking {
        Server().use { server ->
            Harness(server).use { harness ->
                val selected = ConversationRef.directMessage(harness.config.id, "old-selected")
                harness.record(selected, "selected", harness.now.get() - 10_000, text = "protected old message", highlights = true)
                repeat(1001) { index ->
                    harness.record(ConversationRef.directMessage(harness.config.id, "dm-$index"), "m-$index",
                        harness.now.get() - 5000 + index)
                }
                harness.offline.saveConversation(OfflineConversationShell(selected, "Old selected", harness.now.get() - 10_000,
                    topic = "Protected cached topic", topicObservedAtMs = harness.now.get() - 10_000))
                repeat(1000) { index ->
                    val ref = ConversationRef.channel(harness.config.id, "#cache-only-$index")
                    harness.offline.saveConversation(OfflineConversationShell(ref, ref.rawTarget, harness.now.get() - 1000 + index))
                }
                harness.offline.select(selected, harness.now.get())
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                val retained = harness.messages.knownConversations(harness.config.id)
                assertEquals(999, retained.size)
                assertTrue(retained.any { it.storageKey == selected.storageKey })
                val buffers = harness.coordinator.buffers.value.values.filter { it.ref.networkId == harness.config.id }
                assertEquals(1000, buffers.size)
                assertEquals(999, buffers.sumOf { it.unreadCount })
                assertTrue(buffers.none { it.ref.rawTarget.startsWith("#cache-only-") })
                assertEquals(selected.storageKey, harness.coordinator.restoredSelection.value)
                val restored = harness.coordinator.buffers.value.getValue(selected.storageKey)
                assertEquals("protected old message", restored.messages.single().text)
                assertEquals("Protected cached topic", restored.topic)
                assertTrue(restored.cachedTopic)
                assertEquals(1, restored.mentionCount)
                assertEquals(1000, harness.offline.shells(harness.config.id).size)
                assertTrue(harness.offline.shells(harness.config.id).any { it.ref.storageKey == selected.storageKey })
                assertTrue(server.received.isEmpty())
            }
        }
    }

    @Test
    fun coldReactionHistoryIsExplicitlyPartialEvenWhenGlobalCapLeavesNoMembership() = runBlocking {
        Server().use { server ->
            Harness(server).use { harness ->
                harness.record()
                harness.messages.applyReaction(harness.room, "message", "bob", "👍", true, harness.now.get())
                harness.messages.applyReaction(harness.room, "message", "carol", "👎", true, harness.now.get())
                harness.messages.maintainReactionMemberships(harness.config.id, perParentLimit = 1, networkLimit = 0)
                harness.offline.select(harness.room, harness.now.get())
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                val restored = harness.coordinator.buffers.value.getValue(harness.room.storageKey)
                assertTrue(restored.messages.single().reactionsTruncated)
                assertTrue(restored.reactions.isEmpty())
                assertTrue(assertNotNull(harness.messages.byRowId(harness.room, restored.messages.single().storedRowId ?: 0)).reactionsTruncated)
            }
        }
    }

    @Test
    fun renameCollisionPreservesCanonicalRowsQuotesReactionsAndLatestCachedMetadata() = runBlocking {
        Server().use { server ->
            Harness(server).use { harness ->
                val destination = ConversationRef.channel(harness.config.id, "#destination")
                val sourceParent = harness.record(msgid = "shared", timestampMs = harness.now.get() - 1000, text = "source body")
                harness.messages.record(StoredMessage(networkId = harness.config.id, conversation = harness.room,
                    msgid = "reply", senderNick = "alice", senderUser = null, senderHost = null, kind = MessageKind.PRIVMSG,
                    text = "local quote", sentByUs = false, timestampMs = harness.now.get() - 900, replyParentRowId = sourceParent))
                val canonical = harness.record(destination, "shared", harness.now.get() - 1000, text = "canonical target body")
                harness.messages.applyReaction(harness.room, "shared", "alice", "👍", true, harness.now.get() - 100)
                harness.messages.applyReaction(destination, "shared", "bob", "👋", true, harness.now.get())
                harness.seedShell(roster = OfflineRoster(listOf(ChannelMember("old", '@')),
                    observedAtMs = harness.now.get() - 1000, completeAtObservation = true))
                harness.offline.saveConversation(OfflineConversationShell(destination, destination.rawTarget, harness.now.get() - 500,
                    topic = "Newest destination topic", topicObservedAtMs = harness.now.get() - 500,
                    modes = mapOf("s" to emptyList()), modesObservedAtMs = harness.now.get() - 500),
                    OfflineRoster(listOf(ChannelMember("new", '+')), observedAtMs = harness.now.get() - 500,
                        completeAtObservation = true))
                harness.offline.select(harness.room, harness.now.get())
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                val sourceLocalId = harness.coordinator.buffers.value.getValue(harness.room.storageKey).messages.first { it.msgid == "shared" }.localId
                harness.coordinator.connectNetwork(harness.config.id)
                await { harness.coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                server.send(":srv RENAME #room #destination :Moved")
                await {
                    val renamed = harness.coordinator.buffers.value[destination.storageKey]
                    renamed?.messages?.firstOrNull { it.msgid == "shared" }?.text == "canonical target body" &&
                        renamed.messages.firstOrNull { it.msgid == "reply" }?.replyPreview?.text == "canonical target body" &&
                        renamed.members.map { it.nick }.toSet() == setOf("old", "new") &&
                        harness.offline.selection()?.storageKey == destination.storageKey
                }
                val renamed = harness.coordinator.buffers.value.getValue(destination.storageKey)
                val parent = renamed.messages.first { it.msgid == "shared" }
                assertEquals(sourceLocalId, parent.localId)
                assertEquals(canonical, parent.storedRowId)
                assertEquals("Newest destination topic", renamed.topic)
                assertEquals(mapOf("s" to emptyList()), renamed.channelModes)
                assertTrue(renamed.cachedTopic && renamed.cachedModes && renamed.cachedRoster)
                assertEquals(listOf(ChannelMember("new", '+'), ChannelMember("old", '@')), renamed.members.sortedBy { it.nick })
                val cachedRoster = assertNotNull(harness.offline.roster(destination))
                assertFalse(cachedRoster.completeAtObservation)
                assertEquals(mapOf("old" to harness.now.get() - 1000, "new" to harness.now.get() - 500),
                    cachedRoster.memberObservedAtMs)
                assertEquals(mapOf("👍" to setOf("alice"), "👋" to setOf("bob")), renamed.reactions["shared"])
                assertFalse(harness.coordinator.buffers.value.containsKey(harness.room.storageKey))
                assertTrue(harness.messages.recent(harness.room, 10).isEmpty())
                val reply = harness.messages.recent(destination, 10).first { it.msgid == "reply" }
                assertEquals(canonical, reply.replyParentRowId)
                server.send(":srv 353 tester = #destination :tester old new fresh")
                server.send(":srv MODE #destination +i")
                server.send("@msgid=refresh-boundary :fresh!u@h PRIVMSG #destination :during refresh")
                await { harness.coordinator.buffers.value[destination.storageKey]?.messages?.any { it.msgid == "refresh-boundary" } == true }
                val partial = harness.coordinator.buffers.value.getValue(destination.storageKey)
                assertEquals(renamed.members, partial.members)
                assertEquals(renamed.channelModes, partial.channelModes)
                assertTrue(partial.cachedRoster && partial.cachedModes)
                server.send(":srv 366 tester #destination :End of NAMES")
                server.send(":srv 324 tester #destination +n")
                await {
                    val live = harness.coordinator.buffers.value.getValue(destination.storageKey)
                    !live.cachedRoster && !live.cachedModes && harness.offline.roster(destination)?.completeAtObservation == true
                }
                val live = harness.coordinator.buffers.value.getValue(destination.storageKey)
                assertEquals(setOf("tester", "old", "new", "fresh"), live.members.map { it.nick }.toSet())
                assertTrue(live.members.all { it.symbol == null })
                assertEquals(mapOf("n" to emptyList()), live.channelModes)
                assertEquals(canonical, live.messages.first { it.msgid == "shared" }.storedRowId)
                assertEquals("canonical target body", live.messages.first { it.msgid == "reply" }.replyPreview?.text)
                assertEquals(renamed.reactions["shared"], live.reactions["shared"])
            }
        }
    }

    @Test
    fun runtimeRetentionDropsPrunedVisibleRowsOffPageReactionsAndLocalQuotePreview() = runBlocking {
        Server().use { server ->
            Harness(server).use { harness ->
                val parent = harness.record(msgid = "off-page", timestampMs = harness.now.get() - 1000, text = "old quote")
                repeat(210) { harness.record(msgid = "older-$it", timestampMs = harness.now.get() - 900 + it) }
                harness.messages.record(StoredMessage(networkId = harness.config.id, conversation = harness.room,
                    msgid = "reply", senderNick = "alice", senderUser = null, senderHost = null, kind = MessageKind.PRIVMSG,
                    text = "new reply", sentByUs = false, timestampMs = harness.now.get(), replyParentRowId = parent))
                harness.messages.applyReaction(harness.room, "off-page", "bob", "👍", true, harness.now.get())
                harness.offline.select(harness.room, harness.now.get())
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                val before = harness.coordinator.buffers.value.getValue(harness.room.storageKey)
                assertEquals("old quote", before.messages.first { it.msgid == "reply" }.replyPreview?.text)
                assertTrue(before.reactions.containsKey("off-page"))
                harness.messages.trimTo(harness.room, 1, harness.now.get())
                harness.now.addAndGet(300_001)
                harness.coordinator.onForegroundResume()
                await { harness.coordinator.buffers.value.getValue(harness.room.storageKey).messages.size == 1 }
                val after = harness.coordinator.buffers.value.getValue(harness.room.storageKey)
                assertEquals("reply", after.messages.single().msgid)
                assertNull(after.messages.single().replyPreview)
                assertNull(after.messages.single().localReplyParentRowId)
                assertTrue(after.reactions.isEmpty())
                assertEquals(1, after.unreadCount)
            }
        }
    }

    @Test
    fun runtimeMaintenanceCannotPruneOrRestoreObsoleteKeysDuringPendingCaseMappingCutover() = runBlocking {
        Server().use { server ->
            val calls = AtomicLong()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            Harness(server, daoFactory = { delegate ->
                object : MessageDao by delegate {
                    override suspend fun maintainNetworkAtomic(networkId: String, keepMessages: Int, keepConversations: Int,
                        protectedConversations: List<String>, nowMs: Long) {
                        if (calls.incrementAndGet() == 2L) {
                            entered.complete(Unit)
                            release.await()
                        }
                        delegate.maintainNetworkAtomic(networkId, keepMessages, keepConversations, protectedConversations, nowMs)
                    }
                }
            }).use { harness ->
                val old = ConversationRef.channel(harness.config.id, "#room[one]")
                val renamed = ConversationRef.channel(harness.config.id, "#room[one]", dev.brentdevs.yardhal.core.protocol.CaseMapping.ASCII)
                harness.record(old, "one", text = "stable message")
                harness.seedShell(old)
                harness.offline.select(old, harness.now.get())
                harness.markers.advance(old.storageKey, harness.now.get() - 1000, 0)
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                harness.coordinator.connectNetwork(harness.config.id)
                await { harness.coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                await { server.received.count { it.startsWith("MARKREAD #room[one] ") } == 1 }
                harness.now.addAndGet(300_001)
                harness.coordinator.onForegroundResume()
                withTimeout(10_000) { entered.await() }
                server.send(":srv 005 tester CASEMAPPING=ascii :are supported")
                await { harness.coordinator.buffers.value.containsKey(renamed.storageKey) && !harness.coordinator.buffers.value.containsKey(old.storageKey) }
                release.complete(Unit)
                await { harness.messages.knownConversations(harness.config.id).any { it.storageKey == renamed.storageKey } &&
                    harness.offline.selection()?.storageKey == renamed.storageKey && calls.get() >= 3L }
                assertTrue(harness.coordinator.sendText(harness.config.id, renamed.storageKey, "case-remap-barrier"))
                await { server.received.any { it == "PRIVMSG #room[one] :case-remap-barrier" } }
                assertEquals(1, server.received.count { it.startsWith("MARKREAD #room[one] ") })
                assertFalse(harness.coordinator.buffers.value.containsKey(old.storageKey))
                val restored = harness.coordinator.buffers.value.getValue(renamed.storageKey)
                assertEquals("stable message", restored.messages.first { it.msgid == "one" }.text)
                assertEquals(1, restored.unreadCount)
                assertTrue(harness.messages.knownConversations(harness.config.id).none { it.storageKey == old.storageKey })
            }
        }
    }

    @Test
    fun cachedMetadataAndAttachmentValuesUseExistingKnownCredentialRedactionPolicy() = runBlocking {
        val secret = "phase3-private-password"
        Server("server-time draft/read-marker echo-message ${IrcMetadata.CAPABILITY}").use { server ->
            Harness(server, serverPassword = secret).use { harness ->
                harness.record()
                harness.offline.select(harness.room, harness.now.get())
                harness.coordinator.startAll()
                await { harness.coordinator.restorationReady.value }
                harness.coordinator.connectNetwork(harness.config.id)
                await { harness.coordinator.networks.value.single().status == ConnectionStatus.REGISTERED }
                server.send(":srv 332 tester #room :Topic $secret")
                server.send(":srv 324 tester #room +k $secret")
                server.send(":srv METADATA alice ${IrcMetadata.KEY_DISPLAY_NAME} * :Name $secret")
                server.send("@msgid=attachment;+draft/attachment=https://files.example/$secret :alice!u@h PRIVMSG #room :file")
                assertTrue(harness.coordinator.sendText(harness.config.id, harness.room.storageKey, "/whois alice"))
                await { server.received.any { it == "WHOIS alice alice" } }
                server.send(":srv 311 tester alice user host * :Real name $secret")
                server.send(":srv 318 tester alice :End of WHOIS")
                await { harness.offline.whois(harness.config.id, "alice") != null &&
                    harness.offline.user(harness.config.id, "alice")?.metadata?.containsKey(IrcMetadata.KEY_DISPLAY_NAME) == true &&
                    harness.messages.recent(harness.room, 20).any { it.msgid == "attachment" } &&
                    harness.offline.shell(harness.room)?.modesObservedAtMs != null }
                val shell = assertNotNull(harness.offline.shell(harness.room))
                assertFalse(shell.topic.orEmpty().contains(secret))
                assertTrue(shell.modes.values.flatten().none { it.contains(secret) })
                assertFalse(assertNotNull(harness.offline.whois(harness.config.id, "alice")).info.realName.orEmpty().contains(secret))
                assertTrue(assertNotNull(harness.offline.user(harness.config.id, "alice")).metadata.values.none { it.contains(secret) })
                val attachment = harness.messages.recent(harness.room, 20).first { it.msgid == "attachment" }
                assertFalse(attachment.attachmentUrl.orEmpty().contains(secret))
                assertTrue(harness.coordinator.buffers.value.getValue(harness.room.storageKey).messages.none { it.text.contains(secret) })
            }
        }
    }
}
