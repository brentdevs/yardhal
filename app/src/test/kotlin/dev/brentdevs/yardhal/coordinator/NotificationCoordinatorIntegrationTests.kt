package dev.brentdevs.yardhal.coordinator

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.core.client.InMemoryStsPolicyStore
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.HistoryCoverageStore
import dev.brentdevs.yardhal.core.data.IgnoreStore
import dev.brentdevs.yardhal.core.data.InMemoryCredentialVault
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.NotificationKind
import dev.brentdevs.yardhal.core.data.NotificationPreferences
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.YardhalDatabase
import dev.brentdevs.yardhal.service.ConversationNotifier
import dev.brentdevs.yardhal.service.NotificationDecision
import dev.brentdevs.yardhal.service.NotificationEvent
import dev.brentdevs.yardhal.service.NotificationPolicy
import dev.brentdevs.yardhal.service.NotificationRoutes
import dev.brentdevs.yardhal.service.Notifications
import dev.brentdevs.yardhal.ui.image.ImageCacheCategory
import dev.brentdevs.yardhal.ui.image.RemoteImageLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
class NotificationCoordinatorIntegrationTests {
    private class Server : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile private var socket: Socket? = null
        val received = CopyOnWriteArrayList<String>()
        val port: Int get() = listener.localPort

        fun start() {
            executor.submit {
                runCatching {
                    val peer = listener.accept()
                    socket = peer
                    val reader = peer.getInputStream().bufferedReader(Charsets.UTF_8)
                    while (!peer.isClosed) {
                        val line = reader.readLine() ?: break
                        received += line
                        when {
                            line.startsWith("CAP LS") -> send(":srv CAP * LS :message-tags server-time batch draft/metadata-2=max-subs=10")
                            line.startsWith("CAP REQ :") -> send(":srv CAP * ACK :${line.substringAfter("CAP REQ :")}")
                            line.startsWith("USER ") -> send(":srv 001 tester :Welcome")
                            line == "METADATA * SUB avatar display-name" -> send(":srv 770 tester avatar display-name")
                            line == "JOIN #room" -> {
                                send(":tester!u@h JOIN #room")
                                send(":srv 353 tester = #room :tester alice")
                                send(":srv 366 tester #room :End of NAMES")
                            }
                        }
                    }
                }
            }
        }

        fun send(line: String) {
            val peer = requireNotNull(socket)
            synchronized(peer) {
                peer.getOutputStream().write((line + "\r\n").toByteArray(Charsets.UTF_8))
                peer.getOutputStream().flush()
            }
        }

        override fun close() {
            socket?.close()
            listener.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun liveWireEventsApplyNotificationPolicyAndKeepInvitationRoutesDistinctWithoutAutojoin() = runBlocking {
        val directory = Files.createTempDirectory("yardhal-notifications").toFile()
        val database = YardhalDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val observations = CopyOnWriteArrayList<Pair<NotificationEvent, NotificationDecision>>()
        val foreground = AtomicBoolean(false)
        val read = ReadMarkerStore(directory)
        val ignore = IgnoreStore(directory)
        val cleared = CopyOnWriteArrayList<ConversationRef>()
        Server().use { server ->
            server.start()
            val config = NetworkConfig(id = "stable-upstream", name = "Notification network", host = "127.0.0.1", port = server.port, tls = false, nick = "tester", autojoin = listOf("#room"))
            val coordinator = LiveCoordinator(
                scope = scope,
                networkStore = NetworkStore(directory).also { it.add(config) },
                messageStore = MessageStore(database.messageDao()),
                readMarkers = read,
                historyCoverage = HistoryCoverageStore(directory),
                mutes = MuteStore(directory),
                vault = InMemoryCredentialVault(),
                channelOrder = ChannelOrderStore(directory),
                connectionFactory = ConnectionFactory { connectionConfig, onStsUpgrade ->
                    IrcConnection(IrcConnectionConfig(host = connectionConfig.host, port = connectionConfig.port, tls = false, nick = connectionConfig.nick, capabilities = setOf("message-tags", "server-time", "batch")), onStsUpgrade = onStsUpgrade)
                },
                stsPolicies = InMemoryStsPolicyStore(),
                notifier = ConversationNotifier { event, eligibility -> observations += event to NotificationPolicy.decide(event, eligibility, NotificationPreferences(), true) },
                appIsForeground = foreground::get,
                onNotificationConversationRead = cleared::add,
            )
            coordinator.attachIgnores(ignore)
            val room = ConversationRef.channel(config.id, "#room")
            suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }
            suspend fun barrier(id: String) {
                server.send("@msgid=$id :Barrier!u@h PRIVMSG tester :notification processing barrier")
                awaitCondition { observations.any { it.first.eventId == id } }
            }
            fun send(id: String, target: String = "#room", sender: String = "alice", text: String = "tester hello", tags: String = "") {
                server.send("@msgid=$id$tags :$sender!u@h PRIVMSG $target :$text")
            }
            fun assertSuppressed(id: String) {
                assertFalse(observations.any { it.first.eventId == id && it.second is NotificationDecision.Show })
            }
            try {
                coordinator.startAll()
                awaitCondition { coordinator.buffers.value[room.storageKey]?.members?.any { it.nick == "alice" } == true }
                send("mention")
                send("dm", target = "tester", text = "no nickname mention")
                send("ordinary", text = "ordinary conversation")
                barrier("first-barrier")
                val mention = observations.first { it.first.eventId == "mention" }
                assertIs<NotificationDecision.Show>(mention.second)
                assertEquals(NotificationKind.MENTION, mention.first.kind)
                assertEquals(room, mention.first.ref)
                val dm = observations.first { it.first.eventId == "dm" }
                assertIs<NotificationDecision.Show>(dm.second)
                assertEquals(NotificationKind.DIRECT_MESSAGE, dm.first.kind)
                assertEquals(ConversationRef.directMessage(config.id, "alice"), dm.first.ref)
                assertFalse(observations.any { it.first.eventId == "ordinary" })

                coordinator.toggleMute(room.storageKey)
                send("muted")
                barrier("mute-barrier")
                assertSuppressed("muted")
                assertTrue(room in cleared)
                coordinator.toggleMute(room.storageKey)
                coordinator.trackSelection(room.storageKey)
                foreground.set(true)
                send("viewed")
                barrier("view-barrier")
                assertSuppressed("viewed")
                foreground.set(false)
                send("background-selected")
                barrier("background-barrier")
                assertIs<NotificationDecision.Show>(observations.first { it.first.eventId == "background-selected" }.second)
                coordinator.trackSelection(null)

                coordinator.markReadThrough(room, dev.brentdevs.yardhal.core.data.ReadCursor(Long.MAX_VALUE, Long.MAX_VALUE))
                send("already-read")
                send("own", sender = "tester")
                send("duplicate", target = "tester")
                send("duplicate", target = "tester")
                server.send(":srv BATCH +replay znc.in/playback")
                send("replay", target = "tester", tags = ";batch=replay")
                server.send(":srv BATCH -replay")
                ignore.add("Ignored")
                send("ignored", target = "tester", sender = "Ignored")
                barrier("filter-barrier")
                assertSuppressed("already-read")
                assertSuppressed("own")
                assertEquals(1, observations.count { it.first.eventId == "duplicate" && it.second is NotificationDecision.Show })
                assertSuppressed("replay")
                assertFalse(observations.any { it.first.eventId == "ignored" && it.second is NotificationDecision.Show })

                server.send("@msgid=invite-live :alice!u@h INVITE tester :#new-room")
                server.send("@msgid=invite-other :alice!u@h INVITE someone :#other-room")
                server.send("@msgid=invite-ignored :Ignored!u@h INVITE tester :#ignored-room")
                server.send(":srv BATCH +invite-replay znc.in/playback")
                server.send("@msgid=invite-history;batch=invite-replay :alice!u@h INVITE tester :#history-room")
                server.send(":srv BATCH -invite-replay")
                barrier("invite-barrier")
                val invite = observations.first { it.first.eventId == "invite-live" }
                assertIs<NotificationDecision.Show>(invite.second)
                assertEquals(NotificationKind.INVITE, invite.first.kind)
                val route = NotificationRoutes.fromEvent(invite.first)
                assertTrue(route.invite)
                assertEquals(ConversationRef.channel(config.id, "#new-room"), route.ref)
                assertSuppressed("invite-history")
                assertFalse(observations.any { it.first.eventId in setOf("invite-other", "invite-ignored") && it.second is NotificationDecision.Show })
                assertFalse(server.received.any { it == "JOIN #new-room" || it == "JOIN #history-room" })
            } finally {
                coordinator.disconnect(config.id)
                scope.cancel()
                database.close()
                directory.deleteRecursively()
            }
        }
    }

    @Test
    @Config(sdk = [35], application = Application::class)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun cachedSenderRenditionsReachNativeNotificationsWithoutFetchingUnknownAvatars() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val directory = Files.createTempDirectory("yardhal-notification-avatars").toFile()
        val database = YardhalDatabase.inMemory(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requests = AtomicInteger()
        val bytes = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
        }.toByteArray()
        val loader = RemoteImageLoader(directory, connectionFactory = { url ->
            requests.incrementAndGet()
            ImageConnection(url, bytes)
        })
        val cachedUrl = "https://profiles.test/alice.png?size={size}"
        val uncachedUrl = "https://profiles.test/other-alice.png"
        val mediaOnlyUrl = "https://profiles.test/bob.png"
        assertNotNull(loader.load(cachedUrl, 40, ImageCacheCategory.AVATAR))
        assertNotNull(loader.load(mediaOnlyUrl, 40, ImageCacheCategory.MEDIA))
        assertNull(loader.cached(cachedUrl, 96, ImageCacheCategory.AVATAR))
        assertNull(loader.cachedAvatar(uncachedUrl))
        assertNull(loader.cachedAvatar(mediaOnlyUrl))
        val initialRequests = requests.get()
        assertEquals(2, initialRequests)
        val posted = CopyOnWriteArrayList<NotificationEvent>()
        Server().use { first ->
            Server().use { second ->
                first.start()
                second.start()
                val configs = listOf(first, second).mapIndexed { index, server ->
                    NetworkConfig(id = "upstream-$index", name = "Avatar network $index", host = "127.0.0.1",
                        port = server.port, tls = false, nick = "tester", autojoin = listOf("#room"))
                }
                val coordinator = LiveCoordinator(
                    scope = scope,
                    networkStore = NetworkStore(directory).also { store -> configs.forEach(store::add) },
                    messageStore = MessageStore(database.messageDao()),
                    readMarkers = ReadMarkerStore(directory),
                    historyCoverage = HistoryCoverageStore(directory),
                    mutes = MuteStore(directory),
                    vault = InMemoryCredentialVault(),
                    channelOrder = ChannelOrderStore(directory),
                    connectionFactory = ConnectionFactory { config, onStsUpgrade ->
                        IrcConnection(IrcConnectionConfig(host = config.host, port = config.port, tls = false,
                            nick = config.nick, capabilities = setOf("message-tags", "server-time", "batch", "draft/metadata-2")),
                            onStsUpgrade = onStsUpgrade)
                    },
                    stsPolicies = InMemoryStsPolicyStore(),
                    notifier = ConversationNotifier { event, eligibility ->
                        if (Notifications.post(app, event, eligibility, NotificationPreferences(), loader.cachedAvatar(event.avatarUrl))) posted += event
                    },
                )
                suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }
                suspend fun notification(id: String): Notification {
                    awaitCondition { posted.any { it.eventId == id } }
                    val event = posted.first { it.eventId == id }
                    return manager.activeNotifications.single { it.tag == NotificationRoutes.identity(NotificationRoutes.fromEvent(event)) }.notification
                }
                fun icon(notification: Notification): Bitmap =
                    assertIs<BitmapDrawable>(assertNotNull(notification.getLargeIcon()).loadDrawable(app)).bitmap
                try {
                    coordinator.startAll()
                    awaitCondition { configs.all { config ->
                        coordinator.buffers.value[ConversationRef.channel(config.id, "#room").storageKey]?.members?.any { it.nick == "alice" } == true
                    } }
                    first.send(":srv METADATA alice avatar * :$cachedUrl")
                    first.send(":bob!u@h JOIN #room")
                    first.send(":srv METADATA bob avatar * :$mediaOnlyUrl")
                    first.send(":srv METADATA tester avatar * :$mediaOnlyUrl")
                    second.send(":srv METADATA alice avatar * :$uncachedUrl")
                    awaitCondition {
                        coordinator.profiles.value[configs[0].id]?.forNick("ALICE")?.avatarUrl == cachedUrl &&
                            coordinator.profiles.value[configs[0].id]?.forNick("bob")?.avatarUrl == mediaOnlyUrl &&
                            coordinator.profiles.value[configs[1].id]?.forNick("alice")?.avatarUrl == uncachedUrl
                    }
                    first.send("@msgid=cached-mention :ALICE!u@h PRIVMSG #room :tester cached mention")
                    first.send("@msgid=cached-dm :alice!u@h PRIVMSG tester :cached direct message")
                    first.send("@msgid=cached-invite :ALICE!u@h INVITE tester :#invited")
                    second.send("@msgid=uncached-dm :alice!u@h PRIVMSG tester :other network direct message")
                    first.send("@msgid=media-only :bob!u@h PRIVMSG tester :inline media is not an avatar")
                    first.send("@msgid=unknown-sender :charlie!u@h PRIVMSG tester :no profile")
                    for ((id, sender) in listOf("cached-mention" to "ALICE", "cached-dm" to "alice")) {
                        val native = notification(id)
                        val style = assertNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(native))
                        val person = assertNotNull(style.messages.single().person)
                        assertEquals(sender, person.name.toString())
                        assertEquals(Color.RED, assertIs<BitmapDrawable>(assertNotNull(person.icon).loadDrawable(app)).bitmap.getPixel(0, 0))
                        assertEquals(Color.RED, icon(native).getPixel(0, 0))
                        assertTrue(style.conversationTitle.toString().contains(if (id == "cached-mention") "#room" else "alice"))
                        assertTrue(style.conversationTitle.toString().contains("Avatar network 0"))
                    }
                    val invite = notification("cached-invite")
                    assertEquals(Color.RED, icon(invite).getPixel(0, 0))
                    assertTrue(invite.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("#invited"))
                    assertTrue(invite.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Avatar network 0"))
                    for (id in listOf("uncached-dm", "media-only", "unknown-sender")) {
                        val native = notification(id)
                        val style = assertNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(native))
                        assertNotEquals(Color.RED, icon(native).getPixel(0, 0))
                        assertNotEquals(Color.RED, assertIs<BitmapDrawable>(assertNotNull(style.messages.single().person?.icon).loadDrawable(app)).bitmap.getPixel(0, 0))
                    }
                    assertTrue(notification("uncached-dm").extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Avatar network 1"))
                    assertEquals(initialRequests, requests.get())
                    loader.clear(ImageCacheCategory.AVATAR)
                    assertNull(loader.cachedAvatar(cachedUrl))
                    assertNotNull(loader.cached(mediaOnlyUrl, 40, ImageCacheCategory.MEDIA))
                    first.send("@msgid=cleared-avatar :alice!u@h PRIVMSG tester :avatar was cleared")
                    assertNotEquals(Color.RED, icon(notification("cleared-avatar")).getPixel(0, 0))
                    assertEquals(initialRequests, requests.get())
                } finally {
                    configs.forEach { coordinator.disconnect(it.id) }
                    loader.cancelRequests()
                    scope.cancel()
                    database.close()
                    directory.deleteRecursively()
                }
            }
        }
    }

    private class ImageConnection(url: URL, private val bytes: ByteArray) : HttpURLConnection(url) {
        override fun getResponseCode(): Int = 200
        override fun getContentType(): String = "image/png"
        override fun getContentLengthLong(): Long = bytes.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
    }
}
