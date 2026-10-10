package dev.brentdevs.yardhal.coordinator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.core.client.InMemoryStsPolicyStore
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.HistoryCoverageStore
import dev.brentdevs.yardhal.core.data.InMemoryCredentialVault
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.YardhalDatabase
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChannelSettingsCoordinatorTests {
    private class Peer(private val labelled: Boolean = false) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile private var socket: Socket? = null
        val received = CopyOnWriteArrayList<IrcMessage>()
        val port: Int get() = listener.localPort
        @Volatile var ownRole: String = "@"
        @Volatile var registerAutomatically: Boolean = true
        @Volatile var connections: Int = 0
            private set

        fun start() {
            executor.submit {
                while (!listener.isClosed) {
                    val accepted = runCatching { listener.accept() }.getOrNull() ?: break
                    socket = accepted
                    connections += 1
                    runCatching { serve(accepted) }
                }
            }
        }

        private fun serve(accepted: Socket) {
            val reader = accepted.getInputStream().bufferedReader(Charsets.UTF_8)
            while (!accepted.isClosed) {
                val line = reader.readLine() ?: break
                val message = IrcMessage.parse(line) ?: continue
                received += message
                when {
                    message.command == "CAP" && message.parameters.firstOrNull() == "LS" ->
                        send(":srv CAP * LS :${if (labelled) "message-tags batch labeled-response" else ""}")
                    message.command == "CAP" && message.parameters.firstOrNull() == "REQ" ->
                        send(":srv CAP * ACK :${message.parameters.last()}")
                    message.command == "USER" && registerAutomatically -> register()
                    message.command == "JOIN" -> {
                        send(":tester!u@h JOIN #room")
                        names()
                    }
                    message.command == "NAMES" -> names()
                    message.command == "MODE" && message.parameters == listOf("#room") -> send(":srv 324 tester #room +nt")
                    message.command == "TOPIC" && message.parameters == listOf("#room") -> send(":srv 332 tester #room :Original topic")
                }
            }
        }

        fun register() {
            send(":srv 005 tester CHANMODES=beI,k,l,imnst PREFIX=(ov)@+ TOPICLEN=40 MAXLIST=beI:3 MODES=1 EXCEPTS=e INVEX=I :supported")
            send(":srv 001 tester :Welcome")
        }

        fun disconnect() {
            socket?.close()
        }

        private fun names() {
            send(":srv 353 tester = #room :${ownRole}tester alice")
            send(":srv 366 tester #room :End of NAMES")
        }

        fun send(line: String) {
            val current = socket ?: error("Peer is not connected")
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

    private class Harness(peer: Peer) : AutoCloseable {
        private val directory = Files.createTempDirectory("yardhal-channel-settings").toFile()
        private val database = YardhalDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator: LiveCoordinator
        val key = "network|#room"
        val view: ChannelSettingsState get() = coordinator.channelSettings.value.getValue(key)

        init {
            val networks = NetworkStore(directory)
            networks.add(NetworkConfig(id = "network", name = "Peer", host = "127.0.0.1", port = peer.port,
                tls = false, nick = "tester", autojoin = listOf("#room")))
            coordinator = LiveCoordinator(scope, networks, MessageStore(database.messageDao()), ReadMarkerStore(directory),
                HistoryCoverageStore(directory), MuteStore(directory), InMemoryCredentialVault(), ChannelOrderStore(directory),
                ConnectionFactory { config, onStsUpgrade -> IrcConnection(IrcConnectionConfig(host = config.host,
                    port = config.port, tls = false, nick = config.nick,
                    capabilities = setOf("message-tags", "batch", "labeled-response")), onStsUpgrade = onStsUpgrade) },
                InMemoryStsPolicyStore())
            peer.start()
            coordinator.startAll()
        }

        suspend fun open() {
            await { coordinator.buffers.value[key]?.members?.any { it.nick == "tester" } == true &&
                coordinator.buffers.value[key]?.topic == "Original topic" }
            coordinator.openChannelSettings(key)
            await { view.canEditModes }
        }

        override fun close() {
            scope.cancel()
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun realModeAndTopicConfirmationsDoNotOptimisticallyPublishAndRefusalsPreserveServerValues() = runBlocking {
        Peer().use { peer -> Harness(peer).use { harness ->
            harness.open()
            val coordinator = harness.coordinator
            assertEquals("Original topic", harness.view.topic)
            coordinator.setChannelMode(harness.key, 'm', true)
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+m") } }
            assertEquals(ChannelSettingsRequestStatus.PENDING, harness.view.requestStatus)
            assertFalse(harness.view.modes.single { it.mode == 'm' }.enabled)
            peer.send(":operator!u@h MODE #room +m")
            await { harness.view.modes.single { it.mode == 'm' }.enabled }
            assertEquals(ChannelSettingsRequestStatus.PENDING, harness.view.requestStatus)
            peer.send(":srv 482 tester #room :You're not channel operator")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.ERROR }
            assertTrue(harness.view.error.orEmpty().contains("You're not channel operator"))
            coordinator.setChannelMode(harness.key, 'l', true, "20")
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+l", "20") } }
            peer.send(":tester!u@h MODE #room +l 20")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.CONFIRMED }
            assertEquals("20", harness.view.modes.single { it.mode == 'l' }.parameter)
            coordinator.setChannelTopic(harness.key, "Proposed topic")
            await { peer.received.any { it.command == "TOPIC" && it.parameters == listOf("#room", "Proposed topic") } }
            assertEquals("Original topic", harness.view.topic)
            peer.send(":srv 482 tester #room :Topic change refused")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.ERROR }
            assertEquals("Original topic", harness.view.topic)
            coordinator.setChannelTopic(harness.key, "Confirmed topic")
            await { peer.received.any { it.command == "TOPIC" && it.parameters == listOf("#room", "Confirmed topic") } }
            peer.send(":tester!u@h TOPIC #room :Confirmed topic")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.CONFIRMED && harness.view.topic == "Confirmed topic" }
        } }
    }

    @Test
    fun normalizedAccessMasksConfirmAndRemoveWithoutRelaxingChannelKeyMatching() = runBlocking {
        Peer().use { peer -> Harness(peer).use { harness ->
            harness.open()
            val coordinator = harness.coordinator
            coordinator.editChannelAccessList(harness.key, 'b', "Blocked!*@*", true)
            await { peer.received.any { it.parameters == listOf("#room", "+b", "Blocked!*@*") } }
            peer.send(":tester!u@h MODE #room +b blocked!*@*")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.CONFIRMED }
            assertEquals("blocked!*@*", harness.view.accessLists.getValue('b').entries.single().mask)
            coordinator.editChannelAccessList(harness.key, 'b', "BLOCKED!*@*", false)
            await { peer.received.any { it.parameters == listOf("#room", "-b", "BLOCKED!*@*") } }
            peer.send(":tester!u@h MODE #room -b blocked!*@*")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.CONFIRMED }
            assertTrue(harness.view.accessLists.getValue('b').entries.isEmpty())
            coordinator.setChannelMode(harness.key, 'k', true, "SecretKey")
            await { peer.received.any { it.parameters == listOf("#room", "+k", "SecretKey") } }
            peer.send(":tester!u@h MODE #room +k secretkey")
            await { harness.view.modes.single { it.mode == 'k' }.parameter == "secretkey" }
            assertEquals(ChannelSettingsRequestStatus.PENDING, harness.view.requestStatus)
            peer.send(":tester!u@h MODE #room +k SecretKey")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.CONFIRMED }
        } }
    }

    @Test
    fun negotiatedListsPreserveStructuredMetadataRequireMatchingTerminationAndEndOnDisconnect() = runBlocking {
        Peer(labelled = true).use { peer -> Harness(peer).use { harness ->
            harness.open()
            val coordinator = harness.coordinator
            assertEquals(setOf('b', 'e', 'I'), harness.view.accessLists.keys)
            coordinator.refreshChannelAccessList(harness.key, 'b')
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+b") } }
            val label = assertNotNull(peer.received.last { it.command == "MODE" && it.parameters == listOf("#room", "+b") }.tag("label"))
            peer.send("@label=wrong :srv 368 tester #room :End of ban list")
            peer.send("@label=$label :srv 367 tester #room *!*@blocked setter!u@host 1700000000")
            await { harness.view.accessLists.getValue('b').entries.size == 1 }
            val access = harness.view.accessLists.getValue('b')
            assertEquals(ChannelAccessListStatus.LOADING, access.status)
            assertEquals(ChannelAccessEntry("*!*@blocked", "setter!u@host", 1700000000), access.entries.single())
            peer.send("@label=$label :srv 368 tester #room :End of ban list")
            await { harness.view.accessLists.getValue('b').status == ChannelAccessListStatus.COMPLETE }
            coordinator.refreshChannelAccessList(harness.key, 'e')
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+e") } }
            val exceptLabel = assertNotNull(peer.received.last { it.command == "MODE" && it.parameters == listOf("#room", "+e") }.tag("label"))
            peer.send("@label=$exceptLabel :srv 349 tester #room :End of exceptions")
            await { harness.view.accessLists.getValue('e').status == ChannelAccessListStatus.EMPTY }
            coordinator.refreshChannelAccessList(harness.key, 'I')
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+I") } }
            val inviteLabel = assertNotNull(peer.received.last { it.command == "MODE" && it.parameters == listOf("#room", "+I") }.tag("label"))
            peer.send("@label=$inviteLabel :srv 482 tester #room :Cannot inspect invite exceptions")
            await { harness.view.accessLists.getValue('I').status == ChannelAccessListStatus.ERROR }
            coordinator.refreshChannelAccessList(harness.key, 'I')
            await { peer.received.count { it.command == "MODE" && it.parameters == listOf("#room", "+I") } == 2 }
            val pendingLabel = assertNotNull(peer.received.last { it.command == "MODE" && it.parameters == listOf("#room", "+I") }.tag("label"))
            peer.send("@label=$pendingLabel :srv 346 tester #room *!*@partial setter 1700000001")
            await { harness.view.accessLists.getValue('I').entries.size == 1 }
            coordinator.disconnect("network")
            await { harness.view.accessLists.getValue('I').status == ChannelAccessListStatus.DISCONNECTED }
            assertFalse(harness.view.available)
            assertEquals(ChannelAccessListStatus.IDLE, harness.view.accessLists.getValue('b').status)
            assertEquals(ChannelAccessListStatus.IDLE, harness.view.accessLists.getValue('e').status)
            assertTrue(harness.view.accessLists.values.all { it.entries.isEmpty() })
            assertFalse(harness.view.busy)
            assertTrue(harness.view.accessLists.getValue('I').error.orEmpty().contains("unconfirmed"))
        } }
    }

    @Test
    fun liveOwnRoleAndTopicLockTransitionsGateControllerAndValidationNeverEmitsInvalidCommands() = runBlocking {
        Peer().use { peer -> Harness(peer).use { harness ->
            harness.open()
            val coordinator = harness.coordinator
            coordinator.setChannelMode(harness.key, 'l', true, "-1")
            coordinator.setChannelMode(harness.key, 'k', true, "key\r\nPRIVMSG #room bad")
            coordinator.setChannelMode(harness.key, 'z', true)
            coordinator.setChannelTopic(harness.key, "é".repeat(21))
            assertEquals(ChannelSettingsRequestStatus.ERROR, harness.view.requestStatus)
            peer.ownRole = ""
            peer.send(":operator!u@h MODE #room -o tester")
            await { !harness.view.canEditModes && !harness.view.canEditTopic }
            coordinator.setChannelMode(harness.key, 'm', true)
            coordinator.setChannelTopic(harness.key, "Forbidden topic")
            coordinator.editChannelAccessList(harness.key, 'b', "*!*@forbidden", true)
            peer.send(":srv PING :permission-barrier")
            await { peer.received.any { it.command == "PONG" && it.parameters.lastOrNull() == "permission-barrier" } }
            assertFalse(peer.received.any { it.command == "MODE" && it.parameters.size > 1 })
            assertFalse(peer.received.any { it.command == "TOPIC" && it.parameters.size > 1 })
            peer.send(":operator!u@h MODE #room -t")
            await { harness.view.canEditTopic }
            assertFalse(harness.view.canEditModes)
            coordinator.setChannelTopic(harness.key, "Unlocked topic")
            await { peer.received.any { it.command == "TOPIC" && it.parameters == listOf("#room", "Unlocked topic") } }
            peer.send(":tester!u@h TOPIC #room :Unlocked topic")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.CONFIRMED }
            peer.send(":operator!u@h MODE #room +o tester")
            await { harness.view.canEditModes }
            peer.send(":operator!u@h KICK #room tester :Removed")
            await { !harness.view.available }
            coordinator.setChannelMode(harness.key, 'm', true)
            assertEquals(ChannelSettingsRequestStatus.ERROR, harness.view.requestStatus)
        } }
    }

    @Test
    fun partialUnlabelledListTimesOutAndLateTerminationCannotBecomeCompleteOrContaminateRetry() = runBlocking {
        Peer().use { peer -> Harness(peer).use { harness ->
            harness.open()
            harness.coordinator.refreshChannelAccessList(harness.key, 'b')
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+b") } }
            peer.send(":srv 367 tester #room *!*@partial setter 1700000001")
            await { harness.view.accessLists.getValue('b').entries.size == 1 }
            await { harness.view.accessLists.getValue('b').status == ChannelAccessListStatus.TIMEOUT }
            assertFalse(harness.view.canEditModes)
            peer.send(":srv 368 tester #room :Late end")
            harness.coordinator.refreshChannelAccessList(harness.key, 'b')
            peer.send(":srv PING :timeout-barrier")
            await { peer.received.any { it.command == "PONG" && it.parameters.lastOrNull() == "timeout-barrier" } }
            assertEquals(1, peer.received.count { it.command == "MODE" && it.parameters == listOf("#room", "+b") })
            assertEquals(ChannelAccessListStatus.TIMEOUT, harness.view.accessLists.getValue('b').status)
            assertEquals("*!*@partial", harness.view.accessLists.getValue('b').entries.single().mask)
            assertTrue(harness.view.unavailableReason.orEmpty().contains("Reconnect"))
        } }
    }

    @Test
    fun labelledNumericModeSnapshotConfirmsOnlyItsRequestAndListCaptureIsBounded() = runBlocking {
        Peer(labelled = true).use { peer -> Harness(peer).use { harness ->
            harness.open()
            harness.coordinator.setChannelMode(harness.key, 'l', true, "30")
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+l", "30") } }
            val label = assertNotNull(peer.received.last { it.command == "MODE" && it.parameters == listOf("#room", "+l", "30") }.tag("label"))
            peer.send("@label=other :srv 324 tester #room +ntl 30")
            peer.send(":srv PING :numeric-barrier")
            await { peer.received.any { it.command == "PONG" && it.parameters.lastOrNull() == "numeric-barrier" } }
            assertEquals(ChannelSettingsRequestStatus.PENDING, harness.view.requestStatus)
            peer.send("@label=$label :srv 324 tester #room +ntl 30")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.CONFIRMED }
            harness.coordinator.refreshChannelAccessList(harness.key, 'b')
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+b") } }
            val listLabel = assertNotNull(peer.received.last { it.command == "MODE" && it.parameters == listOf("#room", "+b") }.tag("label"))
            repeat(ChannelSettingsController.MAX_ENTRIES + 1) { index ->
                peer.send("@label=$listLabel :srv 367 tester #room *!*@host$index setter 1700000000")
            }
            await { harness.view.accessLists.getValue('b').status == ChannelAccessListStatus.ERROR }
            assertEquals(ChannelSettingsController.MAX_ENTRIES, harness.view.accessLists.getValue('b').entries.size)
            assertTrue(harness.view.accessLists.getValue('b').error.orEmpty().contains("incomplete"))
            peer.send("@label=$listLabel :srv 368 tester #room :End")
            peer.send(":srv PING :bounded-barrier")
            await { peer.received.any { it.command == "PONG" && it.parameters.lastOrNull() == "bounded-barrier" } }
            assertEquals(ChannelAccessListStatus.ERROR, harness.view.accessLists.getValue('b').status)
        } }
    }

    @Test
    fun sharedMaxListLimitAndCustomPrefixPermissionsUseNegotiatedSupportAndLiveListChanges() = runBlocking {
        Peer(labelled = true).use { peer -> Harness(peer).use { harness ->
            harness.open()
            val coordinator = harness.coordinator
            coordinator.refreshChannelAccessList(harness.key, 'b')
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+b") } }
            val label = assertNotNull(peer.received.last { it.command == "MODE" && it.parameters == listOf("#room", "+b") }.tag("label"))
            repeat(3) { peer.send("@label=$label :srv 367 tester #room *!*@blocked$it setter 1700000000") }
            peer.send("@label=$label :srv 368 tester #room :End")
            await { harness.view.accessLists.getValue('b').status == ChannelAccessListStatus.COMPLETE }
            coordinator.editChannelAccessList(harness.key, 'e', "*!*@trusted", true)
            assertTrue(harness.view.error.orEmpty().contains("limit"))
            peer.send(":operator!u@h MODE #room -b *!*@blocked0")
            await { harness.view.accessLists.getValue('b').entries.size == 2 }
            coordinator.editChannelAccessList(harness.key, 'e', "*!*@trusted", true)
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+e", "*!*@trusted") } }
            assertEquals(ChannelSettingsRequestStatus.PENDING, harness.view.requestStatus)
            peer.send(":tester!u@h MODE #room +e *!*@trusted")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.CONFIRMED }
            coordinator.editChannelAccessList(harness.key, 'b', "*!*@blocked1", false)
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "-b", "*!*@blocked1") } }
            val removalLabel = assertNotNull(peer.received.last { it.command == "MODE" && it.parameters == listOf("#room", "-b", "*!*@blocked1") }.tag("label"))
            peer.send("@label=$removalLabel :srv 696 tester #room b *!*@blocked1 :Removal refused")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.ERROR }
            assertTrue(harness.view.accessLists.getValue('b').entries.any { it.mask == "*!*@blocked1" })
            assertTrue(harness.view.error.orEmpty().contains("Removal refused"))
            peer.send(":srv 005 tester PREFIX=(qov)~!+ CHANMODES=b,,l,mt :updated support")
            peer.send(":srv 353 tester = #room :!tester alice")
            peer.send(":srv 366 tester #room :End")
            await { harness.view.canEditModes && harness.view.accessLists.keys == setOf('b') }
            assertEquals(setOf('l', 'm', 't'), harness.view.modes.map { it.mode }.toSet())
            coordinator.setChannelMode(harness.key, 'k', true, "unsupported")
            assertTrue(harness.view.error.orEmpty().contains("not supported"))
            peer.send(":srv PING :support-barrier")
            await { peer.received.any { it.command == "PONG" && it.parameters.lastOrNull() == "support-barrier" } }
            assertFalse(peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+k", "unsupported") })
        } }
    }

    @Test
    fun automaticReconnectDiscardsCompletedListsAndRequiresFreshRoleBeforeEditsAndRefetch() = runBlocking {
        Peer(labelled = true).use { peer -> Harness(peer).use { harness ->
            harness.open()
            val coordinator = harness.coordinator
            fetchAccessList(peer, harness, 'b', List(3) { "*!*@old$it" })
            fetchAccessList(peer, harness, 'e', emptyList())
            assertEquals(3, harness.view.accessLists.getValue('b').limit)
            coordinator.editChannelAccessList(harness.key, 'e', "*!*@trusted", true)
            assertTrue(harness.view.error.orEmpty().contains("limit"))
            peer.registerAutomatically = false
            peer.ownRole = ""
            peer.disconnect()
            await { !harness.view.available }
            assertEquals(ChannelAccessListStatus.IDLE, harness.view.accessLists.getValue('b').status)
            assertTrue(harness.view.accessLists.values.all { it.entries.isEmpty() })
            await { peer.connections == 2 && peer.received.count { it.command == "USER" } == 2 }
            assertFalse(harness.view.available)
            assertFalse(harness.view.canEditModes)
            assertEquals(ChannelAccessListStatus.IDLE, harness.view.accessLists.getValue('b').status)
            peer.register()
            await { harness.view.available }
            assertFalse(harness.view.canEditModes)
            assertEquals(ChannelAccessListStatus.IDLE, harness.view.accessLists.getValue('b').status)
            assertEquals(ChannelAccessListStatus.IDLE, harness.view.accessLists.getValue('e').status)
            peer.ownRole = "@"
            peer.send(":operator!u@h MODE #room +o tester")
            await { harness.view.canEditModes }
            coordinator.editChannelAccessList(harness.key, 'e', "*!*@trusted", true)
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+e", "*!*@trusted") } }
            assertEquals(ChannelSettingsRequestStatus.PENDING, harness.view.requestStatus)
            peer.send(":tester!u@h MODE #room +e *!*@trusted")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.CONFIRMED }
            fetchAccessList(peer, harness, 'b', listOf("*!*@new-server-mask"))
            assertEquals(listOf("*!*@new-server-mask"), harness.view.accessLists.getValue('b').entries.map { it.mask })
            fetchAccessList(peer, harness, 'e', listOf("*!*@trusted"))
            assertEquals(listOf("*!*@trusted"), harness.view.accessLists.getValue('e').entries.map { it.mask })
        } }
    }

    @Test
    fun partAndKickInvalidateCompletedEmptyAndPartialListsBeforeRejoin() = runBlocking {
        for (loss in listOf(":tester!u@h PART #room :Leaving", ":operator!u@h KICK #room tester :Removed")) {
            Peer(labelled = true).use { peer -> Harness(peer).use { harness ->
                harness.open()
                val coordinator = harness.coordinator
                val oldMasks = List(3) { "*!*@old$it" }
                fetchAccessList(peer, harness, 'b', oldMasks)
                fetchAccessList(peer, harness, 'e', emptyList())
                oldMasks.forEach { peer.send(":operator!u@h MODE #room +b $it") }
                coordinator.refreshChannelAccessList(harness.key, 'I')
                await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+I") } }
                val label = assertNotNull(peer.received.last { it.command == "MODE" && it.parameters == listOf("#room", "+I") }.tag("label"))
                peer.send("@label=$label :srv 346 tester #room *!*@partial setter 1700000001")
                await { harness.view.accessLists.getValue('I').entries.size == 1 }
                peer.send(loss)
                await { !harness.view.available && harness.view.accessLists.getValue('I').status == ChannelAccessListStatus.DISCONNECTED }
                assertEquals(ChannelAccessListStatus.IDLE, harness.view.accessLists.getValue('b').status)
                assertEquals(ChannelAccessListStatus.IDLE, harness.view.accessLists.getValue('e').status)
                assertTrue(harness.view.accessLists.values.all { it.entries.isEmpty() })
                assertFalse(harness.view.busy)
                assertTrue(harness.view.accessLists.getValue('I').error.orEmpty().contains("unavailable"))
                peer.send("@label=$label :srv 347 tester #room :Late end of invite exceptions")
                assertEquals(harness.key, coordinator.openChannel("network", "#room"))
                await { harness.view.canEditModes }
                assertEquals(ChannelAccessListStatus.IDLE, harness.view.accessLists.getValue('b').status)
                assertEquals(ChannelAccessListStatus.DISCONNECTED, harness.view.accessLists.getValue('I').status)
                coordinator.editChannelAccessList(harness.key, 'e', "*!*@trusted", true)
                await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+e", "*!*@trusted") } }
                peer.send(":tester!u@h MODE #room +e *!*@trusted")
                await { harness.view.requestStatus == ChannelSettingsRequestStatus.CONFIRMED }
                fetchAccessList(peer, harness, 'b', listOf("*!*@replacement"))
                assertEquals(listOf("*!*@replacement"), harness.view.accessLists.getValue('b').entries.map { it.mask })
                fetchAccessList(peer, harness, 'I', emptyList())
                assertEquals(ChannelAccessListStatus.EMPTY, harness.view.accessLists.getValue('I').status)
            } }
        }
    }

    @Test
    fun firstOpenAfterKickAndRejoinLeavesUnknownListCountsToServerInsteadOfCachedModes() = runBlocking {
        Peer(labelled = true).use { peer -> Harness(peer).use { harness ->
            val coordinator = harness.coordinator
            await { coordinator.buffers.value[harness.key]?.members?.any { it.nick == "tester" } == true &&
                coordinator.buffers.value[harness.key]?.topic == "Original topic" }
            repeat(3) { peer.send(":operator!u@h MODE #room +b *!*@old$it") }
            await { coordinator.buffers.value[harness.key]?.channelModes?.get("b")?.size == 3 }
            peer.send(":operator!u@h KICK #room tester :Removed")
            await { coordinator.buffers.value[harness.key]?.joinState == JoinState.FAILED }
            assertTrue(coordinator.channelSettings.value.isEmpty())
            assertEquals(harness.key, coordinator.openChannel("network", "#room"))
            harness.open()
            assertEquals(3, coordinator.buffers.value.getValue(harness.key).channelModes.getValue("b").size)
            assertEquals(ChannelAccessListStatus.IDLE, harness.view.accessLists.getValue('b').status)
            assertTrue(harness.view.accessLists.getValue('b').entries.isEmpty())
            coordinator.editChannelAccessList(harness.key, 'e', "*!*@trusted", true)
            await { peer.received.any { it.command == "MODE" && it.parameters == listOf("#room", "+e", "*!*@trusted") } }
            assertEquals(ChannelSettingsRequestStatus.PENDING, harness.view.requestStatus)
            val label = assertNotNull(peer.received.last { it.command == "MODE" && it.parameters == listOf("#room", "+e", "*!*@trusted") }.tag("label"))
            peer.send("@label=$label :srv 478 tester #room *!*@trusted :Current server list limit")
            await { harness.view.requestStatus == ChannelSettingsRequestStatus.ERROR }
            assertTrue(harness.view.error.orEmpty().contains("Current server list limit"))
            fetchAccessList(peer, harness, 'b', listOf("*!*@actual"))
            assertEquals(listOf("*!*@actual"), harness.view.accessLists.getValue('b').entries.map { it.mask })
        } }
    }

    private suspend fun fetchAccessList(peer: Peer, harness: Harness, mode: Char, masks: List<String>) {
        val parameters = listOf("#room", "+$mode")
        val before = peer.received.count { it.command == "MODE" && it.parameters == parameters }
        harness.coordinator.refreshChannelAccessList(harness.key, mode)
        await { peer.received.count { it.command == "MODE" && it.parameters == parameters } == before + 1 }
        val request = peer.received.last { it.command == "MODE" && it.parameters == parameters }
        val tags = request.tag("label")?.let { "@label=$it " }.orEmpty()
        val entryNumeric = when (mode) { 'b' -> 367; 'e' -> 348; 'I' -> 346; else -> error("Unsupported access list") }
        masks.forEach { peer.send("${tags}:srv $entryNumeric tester #room $it setter 1700000000") }
        peer.send("${tags}:srv ${entryNumeric + 1} tester #room :End of list")
        val expected = if (masks.isEmpty()) ChannelAccessListStatus.EMPTY else ChannelAccessListStatus.COMPLETE
        await { harness.view.accessLists.getValue(mode).status == expected }
    }

    companion object {
        private suspend fun await(condition: () -> Boolean) {
            withTimeout(8_000) { while (!condition()) delay(10) }
        }
    }
}
