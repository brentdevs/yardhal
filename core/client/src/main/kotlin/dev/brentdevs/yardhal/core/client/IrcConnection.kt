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
    public val initialAway: String? = null,
) {
    init {
        require(nick.isNotBlank()) { "nick must not be blank" }
        require(port in 1..65535) { "port out of range" }
        require((saslAuthcid == null) == (saslPassword == null)) {
            "SASL requires both authcid and password"
        }
        require(initialAway == null || (initialAway.isNotEmpty() && initialAway.none { it in "\r\n\u0000" })) {
            "initialAway must be a non-empty single line"
        }
    }

    public companion object {
        public const val PRE_AWAY_CAP: String = "draft/pre-away"
        public const val EXTENDED_ISUPPORT_CAP: String = "draft/extended-isupport"

        public val DEFAULT_CAPABILITIES: Set<String> = setOf(
            "server-time",
            "message-tags",
            "echo-message",
            "batch",
            "draft/chathistory",
            "draft/message-redaction",
            "no-implicit-names",
            "labeled-response",
            "extended-join",
            "away-notify",
            "account-notify",
            "account-tag",
            "multi-prefix",
            "userhost-in-names",
            "chghost",
            "setname",
            "invite-notify",
            "extended-monitor",
            "draft/multiline",
            "draft/channel-rename",
            "cap-notify",
            "sasl",
            "znc.in/playback",
            "draft/read-marker",
            PRE_AWAY_CAP,
            EXTENDED_ISUPPORT_CAP,
            dev.brentdevs.yardhal.core.protocol.AccountRegistrationPolicy.CAPABILITY,
            "draft/metadata-2",
            dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.CAPABILITY,
            dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.NOTIFY_CAPABILITY,
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
    public data class CapabilitiesNegotiated(
        public val capabilities: Set<String>,
        public val values: Map<String, String> = emptyMap(),
    ) : IrcEvent
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
    private val rawTap: ((outbound: Boolean, line: String) -> Unit)? = null,
    private val stsPolicyStore: StsPolicyStore? = null,
    private val onStsUpgrade: ((Int) -> Unit)? = null,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val eventChannel = Channel<IrcEvent>(Channel.UNLIMITED)
    private val outbound = Channel<String>(Channel.UNLIMITED)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    public val events: kotlinx.coroutines.flow.Flow<IrcEvent> = eventChannel.receiveAsFlow()

    @Volatile
    private var socket: Socket? = null

    private var connectedTls: Boolean = config.tls
    private var connectedPort: Int = config.port

    @Volatile
    private var lastInboundMillis: Long = 0

    @Volatile
    private var registeredNickname: String? = null

    private companion object {
        const val MAX_NICK_COLLISION_ATTEMPTS = 4
    }

    private val stateLock = Any()
    private var nickUserSent: Boolean = false
    private var nickCollisionAttempts: Int = 0
    private var negotiator: CapabilityNegotiator? = null
    private var saslAuthenticator: SaslAuthenticator? = null
    private var awaySent: Boolean = false

    public val isRegistered: Boolean
        get() = registeredNickname != null

    public fun reauthenticate(): Boolean = synchronized(stateLock) {
        val current = negotiator
        val inProgress = saslAuthenticator?.isFinished == false
        if (current == null || registeredNickname == null || inProgress || CapabilityNegotiator.SASL_CAP !in current.acknowledged) {
            false
        } else {
            startSasl()
        }
    }

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
            val decision = stsPolicyStore?.let { store ->
                StsResolver.decide(store, config.host, config.port, config.tls, nowMillis() / 1000)
            }
            val port = (decision as? StsUpgradeDecision.UpgradeRequired)?.port ?: config.port
            val tls = config.tls || decision is StsUpgradeDecision.UpgradeRequired
            val raw = (socketFactory ?: SocketFactory.getDefault()).createSocket()
            try {
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(config.host, port), config.connectTimeoutMillis)
                val ready = if (tls) wrapTls(raw, config.host, port) else raw
                connectedTls = tls
                connectedPort = port
                ready
            } catch (error: Throwable) {
                runCatching { raw.close() }
                throw error
            }
        }
        adoptSocket(created)
    }

    private fun wrapTls(raw: Socket, host: String, port: Int): SSLSocket {
        val factory = SSLContext.getDefault().socketFactory
        val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
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
        synchronized(stateLock) {
            if (config.capabilities.isEmpty()) {
                sendNickUser()
                return
            }
            val effectiveWanted =
                if (config.saslAuthcid != null) config.capabilities else config.capabilities - CapabilityNegotiator.SASL_CAP
            val created = CapabilityNegotiator(
                wanted = effectiveWanted,
                sendRaw = ::sendLine,
                onSaslAcknowledged = { if (!startSasl()) negotiator?.saslFinished() },
                onFinished = {
                    publishCapabilities()
                    sendNickUser()
                },
                beforeCapEnd = ::sendPreRegistrationRequests,
                onDeleted = ::handleCapabilitiesDeleted,
            )
            negotiator = created
            created.begin()
        }
    }

    private fun startSasl(): Boolean {
        val authcid = config.saslAuthcid ?: return false
        val password = config.saslPassword ?: return false
        val authenticator = SaslAuthenticator(
            authcid = authcid,
            password = password,
            advertisedMechanisms = SaslAuthenticator.parseMechanismList(
                negotiator?.advertisedValues?.get(CapabilityNegotiator.SASL_CAP),
            ),
            sendRaw = ::sendLine,
            onOutcome = { outcome ->
                emit(IrcEvent.SaslResult(outcome))
                negotiator?.saslFinished()
            },
        )
        saslAuthenticator = authenticator
        authenticator.start()
        return true
    }

    private fun sendPreRegistrationRequests() {
        val acknowledged = negotiator?.acknowledged ?: return
        if (IrcConnectionConfig.EXTENDED_ISUPPORT_CAP in acknowledged) sendLine("ISUPPORT")
        val away = config.initialAway
        if (away != null && IrcConnectionConfig.PRE_AWAY_CAP in acknowledged) sendAway(away)
    }

    private fun sendAway(message: String) {
        awaySent = true
        sendLine("AWAY :$message")
    }

    private fun publishCapabilities() {
        val acknowledged = LinkedHashSet(negotiator?.acknowledged ?: emptySet())
        val values = negotiator?.advertisedValues?.filterKeys { it in acknowledged } ?: emptyMap()
        emit(IrcEvent.CapabilitiesNegotiated(acknowledged, values))
    }

    private fun handleCapabilitiesDeleted(names: Set<String>) {
        if (CapabilityNegotiator.SASL_CAP in names) {
            saslAuthenticator?.takeUnless { it.isFinished }?.cancel("server withdrew the sasl capability")
        }
        publishCapabilities()
    }

    private fun sendNickUser() {
        if (nickUserSent) return
        nickUserSent = true
        config.serverPassword?.let { sendLine("PASS ${it}") }
        sendLine("NICK ${config.nick}")
        sendLine("USER ${config.username} 0 * :${config.realName}")
    }

    private fun handleLine(line: String) {
        lastInboundMillis = nowMillis()
        rawTap?.invoke(false, line)
        val message = IrcMessage.parse(line) ?: return
        if (handleStsAdvertisement(message)) return
        synchronized(stateLock) { applyToSession(message) }
        emit(IrcEvent.MessageReceived(message))
    }

    private fun applyToSession(message: IrcMessage) {
        val numeric = message.numeric
        saslAuthenticator?.let { authenticator ->
            if (numeric != null) authenticator.handleNumeric(numeric, message)
            authenticator.handleMessage(message)
        }
        negotiator?.handle(message)

        when {
            message.command == "PING" && message.parameters.isNotEmpty() ->
                sendLine("PONG :${message.parameters.last()}")
            numeric == 1 && registeredNickname == null -> {
                val nickname = message.parameters.firstOrNull() ?: config.nick
                registeredNickname = nickname
                emit(IrcEvent.Registered(nickname, message.parameters.lastOrNull() ?: ""))
                val away = config.initialAway
                if (away != null && !awaySent) sendAway(away)
            }
            (numeric == 432 || numeric == 433) &&
                registeredNickname == null &&
                nickCollisionAttempts < MAX_NICK_COLLISION_ATTEMPTS -> {
                nickCollisionAttempts += 1
                val retryNick = config.nick + "_".repeat(nickCollisionAttempts)
                sendLine("NICK $retryNick")
            }
        }
    }

    private fun handleStsAdvertisement(message: IrcMessage): Boolean {
        if (!message.command.equals("CAP", ignoreCase = true)) return false
        val verb = message.parameters.getOrNull(1)?.uppercase()
        if (verb != "LS" && verb != "NEW") return false
        val value = message.parameters.lastOrNull()
            ?.split(' ')
            ?.firstOrNull { it.startsWith("sts=") }
            ?.substringAfter('=')
            ?: return false
        if (!connectedTls) {
            val port = StsResolver.parseUpgradePort(value) ?: return false
            onStsUpgrade?.invoke(port)
            disconnect()
            return true
        }
        val store = stsPolicyStore ?: return false
        val policy = StsResolver.parseCapValue(value, nowMillis() / 1000, connectedPort) ?: return false
        if (policy.durationSeconds == 0L) store.delete(config.host) else store.save(config.host, policy)
        return false
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
                        rawTap?.invoke(true, redactSensitiveOutbound(line))
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
        if (connectedTls && socket != null) {
            stsPolicyStore?.let { StsResolver.refreshOnDisconnect(it, config.host, nowMillis() / 1000) }
        }
        job.cancelChildren()
        eventChannel.trySend(IrcEvent.Disconnected(cause))
        eventChannel.close()
        outbound.close()
    }
}

private val VISIBLE_AUTHENTICATE_ARGUMENTS: Set<String> =
    SaslAuthenticator.PREFERRED_MECHANISMS.toSet() +
        SaslAuthenticator.CONTINUATION_MARKER +
        SaslAuthenticator.ABORT_MARKER

internal fun redactSensitiveOutbound(line: String): String {
    val command = line.substringBefore(' ').uppercase()
    val argument = line.substringAfter(' ', "")
    return when (command) {
        "PASS" -> "PASS <redacted>"
        "AUTHENTICATE" -> if (argument in VISIBLE_AUTHENTICATE_ARGUMENTS) line else "AUTHENTICATE <redacted>"
        "REGISTER" -> {
            val fields = argument.split(' ', limit = 3)
            if (fields.size < 3) "REGISTER <redacted>" else "REGISTER ${fields[0]} ${fields[1]} <redacted>"
        }
        else -> line
    }
}
