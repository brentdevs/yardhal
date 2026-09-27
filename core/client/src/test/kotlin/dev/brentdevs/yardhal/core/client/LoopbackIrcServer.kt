package dev.brentdevs.yardhal.core.client

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

public class LoopbackIrcServer : AutoCloseable {

    private val serverSocket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val clientLatch = CountDownLatch(1)

    public val port: Int get() = serverSocket.localPort

    public val receivedLines: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    private var clientSocket: Socket? = null

    @Volatile
    private var clientOutput: OutputStream? = null

    @Volatile
    public var lineListener: (String) -> Unit = {}

    public fun awaitClient(timeoutSeconds: Long = 5): Boolean =
        clientLatch.await(timeoutSeconds, TimeUnit.SECONDS)

    public fun start() {
        executor.submit {
            while (!serverSocket.isClosed) {
                val accepted = runCatching { serverSocket.accept() }.getOrNull() ?: return@submit
                clientLatch.countDown()
                clientSocket = accepted
                clientOutput = accepted.getOutputStream()
                executor.submit {
                    try {
                        val reader = BufferedReader(InputStreamReader(accepted.getInputStream(), Charsets.UTF_8))
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) continue
                            receivedLines.add(line)
                            lineListener(line)
                        }
                    } catch (_: Throwable) {
                    } finally {
                        runCatching { accepted.close() }
                    }
                }
            }
        }
    }

    public fun sendLine(line: String) {
        val stream = clientOutput ?: error("no client connected")
        synchronized(stream) {
            stream.write((line + "\r\n").toByteArray(Charsets.UTF_8))
            stream.flush()
        }
    }

    public fun dropClient() {
        runCatching { clientSocket?.close() }
    }

    override fun close() {
        runCatching { clientSocket?.close() }
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }

    public companion object {
        public const val SERVER_PREFIX: String = "irc.loopback.test"
    }
}
