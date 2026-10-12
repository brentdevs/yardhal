package dev.brentdevs.yardhal.sync

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import dev.brentdevs.yardhal.coordinator.ConnectionFactory
import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.core.client.InMemoryStsPolicyStore
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ChatAppearanceStore
import dev.brentdevs.yardhal.core.data.HistoryCoverageStore
import dev.brentdevs.yardhal.core.data.InMemoryCredentialVault
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.ThemeLibraryStore
import dev.brentdevs.yardhal.core.data.YardhalDatabase
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncManagerTests {
    @get:Rule val temporary = TemporaryFolder()

    private class Peer : AutoCloseable {
        val epoch = UUID.randomUUID().toString()
        private val listener = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        @Volatile var avatarUploads = 0
            private set
        @Volatile var avatarDownloads = 0
            private set
        @Volatile var remoteAvatar = false

        val endpoint: String get() = "http://127.0.0.1:${listener.localPort}"

        private val thread = Thread {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                socket.use {
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val headers = ByteArrayOutputStream()
                    var marker = 0
                    while (marker != 0x0d0a0d0a) {
                        val byte = input.read()
                        if (byte < 0 || headers.size() >= 8192) throw EOFException("Invalid HTTP request")
                        headers.write(byte)
                        marker = (marker shl 8) or byte
                    }
                    val lines = headers.toString(Charsets.US_ASCII.name()).lineSequence().toList()
                    val path = lines.first().substringAfter(' ').substringBefore(' ')
                    val length = lines.first { it.startsWith("Content-Length:", ignoreCase = true) }
                        .substringAfter(':').trim().toInt()
                    val request = input.readNBytes(length).toString(Charsets.UTF_8)
                    val response = reply(path, request).toString().toByteArray(Charsets.UTF_8)
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(response)
                    output.flush()
                }
            }
        }.apply { isDaemon = true; start() }

        private fun reply(path: String, request: String): JsonObject = when (path) {
                    "/v1/enroll" -> JsonObject(mapOf(
                        "token" to JsonPrimitive("a".repeat(64)), "epoch" to JsonPrimitive(epoch),
                    ))
                    "/v1/sync" -> {
                        val body = kotlinx.serialization.json.Json.parseToJsonElement(request).jsonObject
                        val changes = body.getValue("changes").jsonArray
                        avatarUploads += changes.count { it.jsonObject["kind"] == JsonPrimitive("avatar") }
                        val since = body.getValue("since").jsonPrimitive.content.toLong()
                        val remote = if (remoteAvatar && since < 1L) {
                            avatarDownloads++
                            JsonArray(listOf(JsonObject(mapOf(
                                "seq" to JsonPrimitive(1), "kind" to JsonPrimitive("avatar"),
                                "key" to JsonPrimitive("global"),
                                "value" to JsonObject(mapOf("show" to JsonPrimitive(false))),
                                "origin" to JsonPrimitive("another-device"),
                            ))))
                        } else JsonArray(emptyList())
                        JsonObject(mapOf(
                            "epoch" to JsonPrimitive(epoch),
                            "cursor" to JsonPrimitive(if (remoteAvatar) 1 else 0),
                            "changes" to remote,
                            "accepted" to JsonArray(changes.map { it.jsonObject.getValue("id") }),
                            "rejected" to JsonArray(emptyList()),
                            "hasMore" to JsonPrimitive(false),
                        ))
                    }
                    else -> error("Unexpected sync route: $path")
                }

        override fun close() {
            listener.close()
            thread.join(5_000)
        }
    }

    @Test
    fun establishedAccountAcceptsRemoteAvatarChoiceWithoutEchoingLocalDefault(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oldFlags = context.applicationInfo.flags
        context.applicationInfo.flags = oldFlags or ApplicationInfo.FLAG_DEBUGGABLE
        val preferences = context.getSharedPreferences("sync-account", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        val directory = temporary.newFolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val database = YardhalDatabase.inMemory(context)
        try {
            Peer().use { peer ->
                val networks = NetworkStore(directory)
                val order = ChannelOrderStore(directory)
                val mutes = MuteStore(directory)
                val readMarkers = ReadMarkerStore(directory)
                val themeLibrary = ThemeLibraryStore(directory)
                val appearance = ChatAppearanceStore(directory)
                val vault = InMemoryCredentialVault()
                val coordinator = LiveCoordinator(
                    scope = scope, networkStore = networks, messageStore = MessageStore(database.messageDao()),
                    readMarkers = readMarkers, historyCoverage = HistoryCoverageStore(directory), mutes = mutes,
                    vault = vault, channelOrder = order,
                    connectionFactory = ConnectionFactory { config, _ ->
                        IrcConnection(IrcConnectionConfig(host = config.host, port = config.port,
                            tls = config.tls, nick = config.nick))
                    },
                    stsPolicies = InMemoryStsPolicyStore(),
                )
                coordinator.startAll()
                val manager = SyncManager(context, scope, database, networks, order, mutes, readMarkers,
                    themeLibrary, appearance, coordinator, vault)
                assertTrue(manager.enable(peer.endpoint, UUID.randomUUID().toString(), "b".repeat(64)))
                withTimeout(10_000) {
                    while (manager.state.value.phase != "Up to date") delay(10)
                }
                assertTrue(appearance.snapshot().showAvatars)
                val before = manager.state.value.lastSuccessMs
                delay(30)
                peer.remoteAvatar = true
                manager.retry()
                withTimeout(10_000) {
                    while (peer.avatarDownloads == 0 || manager.state.value.lastSuccessMs == before) delay(10)
                }
                assertFalse(appearance.snapshot().showAvatars)
                assertEquals(0, peer.avatarUploads)
                assertEquals(0, manager.state.value.pending)
            }
        } finally {
            scope.cancel()
            database.close()
            preferences.edit().clear().commit()
            context.applicationInfo.flags = oldFlags
        }
    }
}
