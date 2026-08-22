package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

public data class IrcConnectionConfig(
    public val host: String,
    public val port: Int,
    public val tls: Boolean = true,
    public val nick: String,
    public val username: String = "yardhal",
    public val realName: String = "Yardhal",
    public val serverPassword: String? = null,
    public val saslAuthcid: String? = null,
    public val saslPassword: String? = null,
    public val capabilities: Set<String> = DEFAULT_CAPABILITIES,
    public val connectTimeoutMillis: Int = 10_000,
) {
    init {
        require(nick.isNotBlank()) { "nick must not be blank" }
        require(port in 1..65535) { "port out of range" }
        require((saslAuthcid == null) == (saslPassword == null)) {
            "SASL requires both authcid and password"
        }
    }

    public companion object {
        public val DEFAULT_CAPABILITIES: Set<String> = setOf(
            "server-time",
            "message-tags",
            "echo-message",
            "batch",
            "extended-join",
            "away-notify",
            "account-notify",
            "account-tag",
            "multi-prefix",
            "userhost-in-names",
            "chghost",
            "setname",
            "cap-notify",
            "sasl",
        )
    }
}

public data class KeepAliveConfig(
    public val pingIntervalMillis: Long = 120_000,
    public val dropAfterMillis: Long = 300_000,
    public val registrationTimeoutMillis: Long = 45_000,
)

public sealed interface IrcEvent {
    public data object ConnectionOpened : IrcEvent
    public data class CapabilitiesNegotiated(public val capabilities: Set<String>) : IrcEvent
    public data class SaslResult(public val outcome: SaslOutcome) : IrcEvent
    public data class Registered(public val nickname: String, public val welcomeText: String) : IrcEvent
    public data class MessageReceived(public val message: IrcMessage) : IrcEvent
    public data class Disconnected(public val cause: Throwable?) : IrcEvent
}

public class IrcConnection(
    private val config: IrcConnectionConfig,
    private val keepAlive: KeepAliveConfig = KeepAliveConfig(),
    private val socketFactory: SocketFactory? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val eventChannel = Channel<IrcEvent>(Channel.UNLIMITED)
    private val outbound = Channel<String>(Channel.UNLIMITED)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    public val events: kotlinx.coroutines.flow.Flow<IrcEvent> = eventChannel.receiveAsFlow()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var lastInboundMillis: Long = 0

    @Volatile
    private var registeredNickname: String? = null

    private var nickUserSent: Boolean = false
    private var negotiator: CapabilityNegotiator? = null
    private var saslHandler: SaslPlainHandler? = null

    public val isRegistered: Boolean
        get() = registeredNickname != null

    internal fun start(socketOverride: Socket? = null): Job? {
        lastInboundMillis = nowMillis()
        scope.launch {
            val connected =
                if (socketOverride != null) {
                    runCatching { adoptSocket(socketOverride) }.isSuccess
                } else {
                    runCatching { connect() }.isSuccess
                }
            if (!connected) {
                emit(IrcEvent.Disconnected(IOException("connect failed")))
                return@launch
            }
            emit(IrcEvent.ConnectionOpened)
            beginRegistration()
            launchReader()
            launchWriter()
            launchKeepalive()
        }
        return job
    }

    private suspend fun connect() {
        val created = withContext(Dispatchers.IO) {
            val raw = (socketFactory ?: SocketFactory.getDefault()).createSocket()
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMillis)
            if (config.tls) wrapTls(raw, config.host) else raw
        }
        adoptSocket(created)
    }

    private fun wrapTls(raw: Socket, host: String): SSLSocket {
        val factory = SSLContext.getDefault().socketFactory
        val ssl = factory.createSocket(raw, host, config.port, true) as SSLSocket
        ssl.startHandshake()
        val verified = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
            .verify(host, ssl.session)
        if (!verified) {
            runCatching { ssl.close() }
            throw IOException("TLS hostname verification failed for $host")
        }
        return ssl
    }

    private fun adoptSocket(adopted: Socket) {
        socket = adopted
    }

    private fun beginRegistration() {
        config.serverPassword?.let { sendLine("PASS ${it}") }
        val wantsCaps = config.capabilities.isNotEmpty()
        if (wantsCaps) {
            val effectiveWanted =
                if (config.saslAuthcid != null) config.capabilities else config.capabilities - CapabilityNegotiator.SASL_CAP
            negotiator = CapabilityNegotiator(
                wanted = effectiveWanted,
                sendRaw = ::sendLine,
                onSaslAcknowledged = {
                    val authcid = config.saslAuthcid
                    val password = config.saslPassword
                    if (authcid == null || password == null) {
                        negotiator?.saslAbandonedContinueWithout()
                    } else {
                        val handler = SaslPlainHandler(
                            authcid = authcid,
                            password = password,
                            sendRaw = ::sendLine,
                            onOutcome = { outcome ->
                                emit(IrcEvent.SaslResult(outcome))
                                when (outcome) {
                                    is SaslOutcome.Success -> negotiator?.saslCompleted()
                                    is SaslOutcome.Failure -> negotiator?.saslAbandonedContinueWithout()
                                }
                            },
                        )
                        saslHandler = handler
                        handler.start()
                    }
                },
                onFinished = {
                    emit(IrcEvent.CapabilitiesNegotiated(LinkedHashSet(negotiator?.acknowledged ?: emptySet())))
                    sendNickUser()
                },
            )
            negotiator?.begin()
        } else {
            sendNickUser()
        }
    }

    private fun sendNickUser() {
        if (nickUserSent) return
        nickUserSent = true
        sendLine("NICK ${config.nick}")
        sendLine("USER ${config.username} 0 * :${config.realName}")
    }

    private fun handleLine(line: String) {
        lastInboundMillis = nowMillis()
        val message = IrcMessage.parse(line) ?: return

        val numeric = message.numeric
        if (numeric != null) saslHandler?.handleNumeric(numeric, message)
        saslHandler?.handleMessage(message)
        negotiator?.handle(message)

        when {
            message.command == "PING" && message.parameters.isNotEmpty() ->
                sendLine("PONG :${message.parameters.last()}")
            numeric == 1 && registeredNickname == null -> {
                registeredNickname = message.parameters.firstOrNull() ?: config.nick
                emit(IrcEvent.Registered(registeredNickname!!, message.parameters.lastOrNull() ?: ""))
            }
        }
        emit(IrcEvent.MessageReceived(message))
    }

    private fun launchReader() {
        val current = socket ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream: InputStream = current.getInputStream()
                    val chunk = ByteArray(8192)
                    val framer = LineFramer(sink = ::handleLine)
                    while (isActive) {
                        val read = stream.read(chunk)
                        if (read < 0) break
                        framer.feed(chunk, read)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                shutdown(null)
            }
        }
    }

    private fun launchWriter() {
        val current = socket ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream: OutputStream = current.getOutputStream()
                    while (isActive) {
                        val line = outbound.receive()
                        stream.write((line + "\r\n").toByteArray(Charsets.UTF_8))
                        stream.flush()
                    }
                }
            } catch (_: Throwable) {
            } finally {
                shutdown(null)
            }
        }
    }

    private fun launchKeepalive() {
        scope.launch {
            while (isActive) {
                delay(keepAlive.pingIntervalMillis)
                val idle = nowMillis() - lastInboundMillis
                val current = socket ?: break
                if (registeredNickname != null && idle > keepAlive.dropAfterMillis) {
                    shutdown(IOException("keepalive timeout"))
                    current.close()
                    break
                }
                if (idle > keepAlive.pingIntervalMillis) {
                    sendLine("PING :yardhal-${nowMillis()}")
                }
            }
        }
        scope.launch {
            delay(keepAlive.registrationTimeoutMillis)
            if (registeredNickname == null && socket != null) {
                shutdown(IOException("registration timed out"))
                runCatching { socket?.close() }
            }
        }
    }

    public fun sendLine(line: String) {
        outbound.trySend(line)
    }

    public fun send(message: IrcMessage) {
        sendLine(message.toWire())
    }

    public fun disconnect() {
        shutdown(null)
        runCatching { socket?.close() }
    }

    private fun emit(event: IrcEvent) {
        eventChannel.trySend(event)
    }

    private fun shutdown(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        job.cancelChildren()
        eventChannel.trySend(IrcEvent.Disconnected(cause))
        eventChannel.close()
        outbound.close()
    }
}
