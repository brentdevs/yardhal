package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcMultiline
import dev.brentdevs.yardhal.core.protocol.MultilineLimits
import dev.brentdevs.yardhal.core.protocol.MultilineLine
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue

class ErgoRoundTripTest {

    private lateinit var ergoDir: File
    private lateinit var workDir: File
    private var process: Process? = null
    private var port: Int = 0

    @BeforeTest
    fun assumeErgoProvisioned() {
        ergoDir = File(System.getProperty("user.dir"), "../../.tools/ergo").canonicalFile
        val binary = File(ergoDir, "ergo")
        val provisioned = binary.exists() && binary.canExecute()
        if (System.getenv("CI") == "true") {
            assertTrue(provisioned, "Ergo not provisioned; run scripts/ensure-ergo.sh")
        } else {
            assumeTrue(provisioned, "Ergo not provisioned; run scripts/ensure-ergo.sh")
        }
    }

    @AfterTest
    fun stopServer() {
        process?.destroyForcibly()
        if (::workDir.isInitialized) workDir.deleteRecursively()
    }

    private fun startErgo(): Unit {
        port = findFreePort()
        workDir = File.createTempFile("yardhal-ergo", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        val configText = generateConfig(port, workDir)
        File(workDir, "ergo.yaml").writeText(configText)
        val logFile = File(workDir, "ergo.log")
        val builder = ProcessBuilder(
            File(ergoDir, "ergo").absolutePath,
            "run", "--conf", File(workDir, "ergo.yaml").absolutePath, "--quiet",
        )
        builder.directory(ergoDir)
        builder.redirectOutput(logFile)
        builder.redirectErrorStream(true)
        val proc = builder.start()
        process = proc

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (proc.waitFor(50, java.util.concurrent.TimeUnit.MILLISECONDS)) break
            if (canConnect(port)) return
        }
        val log = if (logFile.exists()) logFile.readText() else "<no log>"
        throw AssertionError("Ergo did not open port $port\n$log\n--- generated config ---\n$configText")
    }

    private fun generateConfig(port: Int, dataDir: File): String {
        val default = ProcessBuilder(
            File(ergoDir, "ergo").absolutePath,
            "defaultconfig",
        )
            .directory(ergoDir)
            .start()
            .inputStream
            .bufferedReader()
            .readText()

        val lines = default.lines().toMutableList()
        replaceFirst(lines, "\"127.0.0.1:6667\":", "\"127.0.0.1:$port\":")
        removeLineContaining(lines, "\"[::1]:6667\":")
        removeBlock(lines, "\":6697\":", "min-tls-version")
        replaceFirst(lines, "path: ircd.db", "path: ${File(dataDir, "datastore").absolutePath}")
        replaceFirst(lines, "lock-file:", "lock-file: ${File(dataDir, "ircd.lock").absolutePath}")
        return lines.joinToString("\n")
    }

    private fun replaceFirst(lines: MutableList<String>, needle: String, replacement: String) {
        val index = lines.indexOfFirst { it.contains(needle) }
        assertTrue(index >= 0, "config template missing line: $needle")
        lines[index] = lines[index].replace(needle, replacement)
    }

    private fun removeLineContaining(lines: MutableList<String>, needle: String) {
        lines.removeAll { it.contains(needle) && !it.trimStart().startsWith("#") }
    }

    private fun removeBlock(lines: MutableList<String>, startNeedle: String, endNeedle: String) {
        val start = withIndexUncommented(lines, startNeedle) ?: return
        val end = (start until lines.size).firstOrNull { lines[it].contains(endNeedle) } ?: return
        for (i in end downTo start) lines.removeAt(i)
    }

    private fun withIndexUncommented(lines: List<String>, needle: String): Int? =
        lines.withIndex().firstOrNull { (_, line) ->
            line.contains(needle) && !line.trimStart().startsWith("#")
        }?.index

    private fun findFreePort(): Int =
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun canConnect(port: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        }.isSuccess

    @Test
    fun fullRoundTripAgainstRealServer() = runBlocking {
        startErgo()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val caps = IrcConnectionConfig.DEFAULT_CAPABILITIES - CapabilityNegotiator.SASL_CAP
            val config = IrcConnectionConfig(
                host = "127.0.0.1",
                port = port,
                tls = false,
                nick = "yardhal-it",
                username = "roundtrip",
                realName = "Yardhal Ergo Round Trip",
                capabilities = caps,
            )
            val connection = IrcConnection(config)
            val registered = kotlinx.coroutines.CompletableDeferred<IrcEvent.Registered>()
            val isupport = kotlinx.coroutines.CompletableDeferred<Unit>()
            val joinEcho = kotlinx.coroutines.CompletableDeferred<IrcMessage>()
            val echoBack = kotlinx.coroutines.CompletableDeferred<IrcMessage>()
            scope.launch {
                connection.events.collect { event ->
                    if (event !is IrcEvent.MessageReceived) return@collect
                    when (event.message.command.uppercase()) {
                        "001" -> registered.complete(
                            event.message.let {
                                IrcEvent.Registered(it.parameters.firstOrNull() ?: "", it.parameters.lastOrNull() ?: "")
                            },
                        )
                        "005" -> isupport.complete(Unit)
                        "JOIN" -> if (!joinEcho.isCompleted) joinEcho.complete(event.message)
                        "PRIVMSG" -> if (event.message.parameters.lastOrNull()?.contains("roundtrip-payload") == true) {
                            echoBack.complete(event.message)
                        }
                    }
                }
            }
            connection.start()

            val welcome = withTimeout(15_000) { registered.await() }
            assertEquals("yardhal-it", welcome.nickname)

            withTimeout(10_000) { isupport.await() }

            connection.send(IrcMessage(command = "JOIN", parameters = listOf("#yardhal-roundtrip")))
            val joined = withTimeout(10_000) { joinEcho.await() }
            assertEquals("#yardhal-roundtrip", joined.parameters.firstOrNull())

            connection.send(
                IrcMessage(
                    command = "PRIVMSG",
                    parameters = listOf("#yardhal-roundtrip", "hello roundtrip-payload one"),
                ),
            )
            val echoed = withTimeout(10_000) { echoBack.await() }
            assertTrue(echoed.tags.containsKey("msgid"), "echo-message must carry msgid")
            assertTrue(echoed.tags.containsKey("time"), "server-time tag expected on echo")
            assertEquals("yardhal-it", echoed.prefix?.nick)

            connection.disconnect()
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun multilineEchoAndChannelRenameAgainstRealServer() = runBlocking {
        startErgo()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val config = IrcConnectionConfig(
                host = "127.0.0.1",
                port = port,
                tls = false,
                nick = "yardhal-ml",
                capabilities = IrcConnectionConfig.DEFAULT_CAPABILITIES - CapabilityNegotiator.SASL_CAP,
            )
            val connection = IrcConnection(config)
            val negotiated = kotlinx.coroutines.CompletableDeferred<Set<String>>()
            val registered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val joined = kotlinx.coroutines.CompletableDeferred<Unit>()
            val echoedBatch = kotlinx.coroutines.CompletableDeferred<List<IrcMessage>>()
            val renamed = kotlinx.coroutines.CompletableDeferred<IrcMessage>()
            val batchFrames = ArrayList<IrcMessage>()
            scope.launch {
                connection.events.collect { event ->
                    if (event is IrcEvent.CapabilitiesNegotiated) negotiated.complete(event.capabilities)
                    if (event !is IrcEvent.MessageReceived) return@collect
                    val message = event.message
                    when (message.command.uppercase()) {
                        "005" -> registered.complete(Unit)
                        "JOIN" -> joined.complete(Unit)
                        "RENAME" -> renamed.complete(message)
                        "BATCH" -> {
                            val head = message.parameters.firstOrNull().orEmpty()
                            if (head.startsWith("+") && message.parameters.getOrNull(1) == IrcMultiline.BATCH_TYPE) {
                                batchFrames.clear()
                                batchFrames += message
                            } else if (head.startsWith("-") && batchFrames.isNotEmpty()) {
                                batchFrames += message
                                echoedBatch.complete(batchFrames.toList())
                            }
                        }
                        "PRIVMSG" -> if (batchFrames.isNotEmpty()) batchFrames += message
                    }
                }
            }
            connection.start()

            val caps = withTimeout(15_000) { negotiated.await() }
            assertTrue(IrcMultiline.CAPABILITY in caps, "Ergo should ack draft/multiline: $caps")
            assertTrue("draft/channel-rename" in caps, "Ergo should ack draft/channel-rename: $caps")
            withTimeout(15_000) { registered.await() }
            connection.send(IrcMessage(command = "JOIN", parameters = listOf("#yardhal-ml")))
            withTimeout(10_000) { joined.await() }

            val original = "first line\n\n" + (1..60).joinToString(" ") { "word$it" }
            val batches = IrcMultiline.split(original, MultilineLimits(4096, 100), 120)
            assertEquals(1, batches.size)
            IrcMultiline.frame("yml1", "PRIVMSG", "#yardhal-ml", batches.single()).forEach(connection::send)

            val echoed = withTimeout(10_000) { echoedBatch.await() }
            val opening = echoed.first()
            assertTrue(opening.tags.containsKey("msgid"), "multiline echo must carry msgid on BATCH: $opening")
            assertEquals("#yardhal-ml", opening.parameters.getOrNull(2))
            val reference = opening.parameters.first().drop(1)
            val lines = echoed.filter { it.command.equals("PRIVMSG", true) }
            assertTrue(lines.all { it.tag("batch") == reference })
            assertTrue(lines.any { it.tags.containsKey(IrcMultiline.CONCAT_TAG) })
            val combined = IrcMultiline.combine(
                lines.map { MultilineLine(it.parameters[1], it.tags.containsKey(IrcMultiline.CONCAT_TAG)) },
            )
            assertEquals(original, combined)

            connection.send(IrcMessage(command = "RENAME", parameters = listOf("#yardhal-ml", "#yardhal-renamed", "round trip")))
            val rename = withTimeout(10_000) { renamed.await() }
            assertEquals(listOf("#yardhal-ml", "#yardhal-renamed", "round trip"), rename.parameters)
            assertEquals("yardhal-ml", rename.prefix?.nick)

            connection.disconnect()
        } finally {
            scope.cancel()
        }
        Unit
    }
}
