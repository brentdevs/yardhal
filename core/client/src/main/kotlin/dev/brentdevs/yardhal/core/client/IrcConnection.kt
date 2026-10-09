package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
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
    public val alternateNicks: List<String> = emptyList(),
    public val saslMode: String = "AUTO",
    public val proxy: Socks5Config? = null,
    public val tlsClientIdentity: TlsClientIdentity? = null,
    public val trustedCertificateSha256: String? = null,
    public val knownSecrets: Set<String> = emptySet(),
    public val nickServService: String = "NickServ",
    public val bouncerNetId: String? = null,
) {
    init {
        require(nick.isNotBlank()) { "nick must not be blank" }
        require(port in 1..65535) { "port out of range" }
        require(realName.none { it in "\r\n\u0000" }) { "realName must be a single line without NUL" }
        require(saslMode.uppercase() in setOf("AUTO", "PLAIN", "SCRAM_SHA_256", "EXTERNAL")) { "unsupported SASL mode" }
        require(alternateNicks.all { it.isNotBlank() && it.none { character -> character in " \r\n\u0000" } }) {
            "alternate nicknames must be non-empty single tokens"
        }
        require(initialAway == null || (initialAway.isNotEmpty() && initialAway.none { it in "\r\n\u0000" })) {
            "initialAway must be a non-empty single line"
        }
        require(bouncerNetId == null || (bouncerNetId.isNotBlank() && bouncerNetId.none { it.isWhitespace() || it in "\u0000\r\n:" })) {
            "bouncer network ID must be a single IRC token"
        }
    }

    override fun toString(): String =
        "IrcConnectionConfig(host=$host, port=$port, tls=$tls, nick=$nick, saslMode=$saslMode)"

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

public class AuthenticationRejectedException(message: String) : IOException(message)

public class BouncerBindRejectedException(message: String) : IOException(message)

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
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val trafficRedactor = TrafficRedactor(
        config.knownSecrets + listOfNotNull(config.serverPassword, config.saslPassword, config.proxy?.password),
        config.nickServService,
    )

    public val events: kotlinx.coroutines.flow.Flow<IrcEvent> = eventChannel.receiveAsFlow()

    @Volatile
    private var socket: Socket? = null

    private var connectedTls: Boolean = config.tls
    private var connectedPort: Int = config.port
    @Volatile
    private var trustedTlsTransport = false

    @Volatile
    private var lastInboundMillis: Long = 0

    @Volatile
    private var registeredNickname: String? = null

    private companion object {
        const val MAX_NICK_COLLISION_ATTEMPTS = 4
    }

    private val stateLock = Any()
    private var nickUserSent: Boolean = false
    private var nextCollisionNickname: Int = 0
    private var currentNickname: String = config.nick
    private val collisionNicknames = (config.alternateNicks + (1..MAX_NICK_COLLISION_ATTEMPTS).map { config.nick + "_".repeat(it) })
        .filter { it != config.nick }
        .distinct()
    private var negotiator: CapabilityNegotiator? = null
    private var saslAuthenticator: SaslAuthenticator? = null
    private var awaySent: Boolean = false
    private val saslRequired = config.saslMode.uppercase() != "AUTO" || config.saslAuthcid != null || config.saslPassword != null
    private var saslSucceeded = false
    private var bouncerBindSent = false
    private var serverPasswordSent = false
    private var pendingProbe: Probe? = null

    private data class Probe(
        val token: String,
        val deadlineMillis: Long,
        val result: CompletableDeferred<Boolean>,
        var deadlineJob: Job? = null,
    )

    public val isRegistered: Boolean
        get() = registeredNickname != null && !closed.get()

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
        if (closed.get() || !started.compareAndSet(false, true)) return null
        lastInboundMillis = nowMillis()
        scope.launch {
            try {
                validateAuthentication()
                if (socketOverride != null) adoptSocket(socketOverride) else connect()
                if (closed.get()) return@launch
                trustedTlsTransport = connectedTls
                emit(IrcEvent.ConnectionOpened)
                beginRegistration()
                if (closed.get()) return@launch
                launchReader()
                launchWriter()
                launchKeepalive()
            } catch (error: CancellationException) {
                shutdown(null)
                throw error
            } catch (error: Exception) {
                shutdown(error)
            }
        }
        return job
    }

    private fun validateAuthentication() {
        if (config.serverPassword?.any { it in "\r\n\u0000" } == true) {
            throw AuthenticationRejectedException("The saved server password contains an invalid IRC parameter. Replace it in network settings.")
        }
        if (!saslRequired) return
        if (config.saslMode.equals("EXTERNAL", ignoreCase = true)) {
            if (config.tlsClientIdentity == null) {
                throw AuthenticationRejectedException("SASL EXTERNAL requires a usable TLS client identity; select a client certificate")
            }
            return
        }
        if (config.saslAuthcid.isNullOrBlank() || config.saslPassword == null) {
            throw AuthenticationRejectedException("SASL credentials are missing; configure an account and password")
        }
    }

    private suspend fun connect() {
        withContext(Dispatchers.IO) {
            val decision = stsPolicyStore?.let { store ->
                StsResolver.decide(store, config.host, config.port, config.tls, nowMillis() / 1000)
            }
            val port = (decision as? StsUpgradeDecision.UpgradeRequired)?.port ?: config.port
            val tls = config.tls || decision is StsUpgradeDecision.UpgradeRequired
            if (config.saslMode.equals("EXTERNAL", ignoreCase = true) && !tls) {
                throw AuthenticationRejectedException("SASL EXTERNAL requires TLS; enable TLS for this network")
            }
            val raw = config.proxy?.let {
                Socks5Tunnel.connect(config.host, port, it, config.connectTimeoutMillis)
            } ?: (socketFactory ?: SocketFactory.getDefault()).createSocket()
            try {
                adoptSocket(raw)
                raw.tcpNoDelay = true
                if (config.proxy == null) raw.connect(InetSocketAddress(config.host, port), config.connectTimeoutMillis)
                raw.soTimeout = config.connectTimeoutMillis
                val ready = if (tls) wrapTls(raw, config.host, port) else raw
                ready.soTimeout = 0
                connectedTls = tls
                connectedPort = port
                adoptSocket(ready)
            } catch (error: Throwable) {
                runCatching { raw.close() }
                throw error
            }
        }
    }

    private fun wrapTls(raw: Socket, host: String, port: Int): SSLSocket {
        val pin = config.trustedCertificateSha256.takeIf { port == config.port }
        val factory = createTlsSocketFactory(host, port, config.tlsClientIdentity, pin)
        val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
        try {
            ssl.startHandshake()
            return ssl
        } catch (error: Throwable) {
            runCatching { ssl.close() }
            throw error
        }
    }

    private fun adoptSocket(adopted: Socket) {
        synchronized(stateLock) {
            if (closed.get()) {
                runCatching { adopted.close() }
                throw IOException("Connection was closed")
            }
            socket = adopted
        }
    }

    private fun beginRegistration() {
        synchronized(stateLock) {
            if (config.capabilities.isEmpty() && !saslRequired && config.bouncerNetId == null) {
                sendNickUser()
                return
            }
            val bouncerCaps = if (config.bouncerNetId == null) config.capabilities else
                config.capabilities + dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.CAPABILITY
            val effectiveWanted =
                if (saslRequired) bouncerCaps + CapabilityNegotiator.SASL_CAP else bouncerCaps - CapabilityNegotiator.SASL_CAP
            val created = CapabilityNegotiator(
                wanted = effectiveWanted,
                sendRaw = ::sendLine,
                onSaslAcknowledged = { startSasl() },
                onFinished = {
                    if (!closed.get() && (!saslRequired || saslSucceeded)) {
                        publishCapabilities()
                        sendNickUser()
                    }
                },
                beforeCapEnd = ::sendPreRegistrationRequests,
                onDeleted = ::handleCapabilitiesDeleted,
                required = buildSet {
                    if (saslRequired) add(CapabilityNegotiator.SASL_CAP)
                    if (config.bouncerNetId != null) add(dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.CAPABILITY)
                },
                onRequiredUnavailable = {
                    if (config.bouncerNetId != null &&
                        dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.CAPABILITY !in createdCapabilities()
                    ) shutdown(BouncerBindRejectedException("The bouncer does not support network binding. Check the account settings, then Connect to retry."))
                    else handleAuthenticationFailure(SaslOutcome.Failure(0, "Server did not offer or accept required SASL authentication; check authentication settings"))
                },
            )
            negotiator = created
            created.begin()
        }
    }

    private fun startSasl(): Boolean {
        if (!saslRequired || closed.get()) return false
        sendServerPassword()
        val authcid = config.saslAuthcid ?: ""
        val password = config.saslPassword ?: ""
        val authenticator = SaslAuthenticator(
            authcid = authcid,
            password = password,
            advertisedMechanisms = SaslAuthenticator.parseMechanismList(
                negotiator?.advertisedValues?.get(CapabilityNegotiator.SASL_CAP),
            ),
            sendRaw = ::sendLine,
            onOutcome = { outcome ->
                if (outcome == SaslOutcome.Success) {
                    saslSucceeded = true
                    emit(IrcEvent.SaslResult(outcome))
                    negotiator?.saslFinished()
                } else if (outcome is SaslOutcome.Failure) {
                    handleAuthenticationFailure(outcome)
                }
            },
            mode = config.saslMode,
        )
        saslAuthenticator = authenticator
        authenticator.start()
        return true
    }

    private fun handleAuthenticationFailure(outcome: SaslOutcome.Failure) {
        val safe = outcome.copy(description = trafficRedactor.redact(outcome.description))
        emit(IrcEvent.SaslResult(safe))
        if (registeredNickname == null) {
            shutdown(AuthenticationRejectedException(safe.description))
        } else {
            negotiator?.saslFinished()
        }
    }

    private fun createdCapabilities(): Set<String> = negotiator?.acknowledged.orEmpty()

    private fun sendPreRegistrationRequests() {
        val acknowledged = negotiator?.acknowledged ?: return
        sendServerPassword()
        val netId = config.bouncerNetId
        if (netId != null && !bouncerBindSent && (!saslRequired || saslSucceeded)) {
            if (dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks.CAPABILITY !in acknowledged) {
                shutdown(BouncerBindRejectedException("The bouncer did not accept network binding. Connect to retry after correcting account settings."))
                return
            }
            bouncerBindSent = true
            sendLine("BOUNCER BIND $netId")
        }
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

    private fun sendServerPassword() {
        if (serverPasswordSent || closed.get()) return
        serverPasswordSent = true
        config.serverPassword?.let { sendLine("PASS :$it") }
    }

    private fun sendNickUser() {
        if (nickUserSent || closed.get() || (saslRequired && !saslSucceeded)) return
        nickUserSent = true
        sendServerPassword()
        sendLine("NICK ${config.nick}")
        sendLine("USER ${config.username} 0 * :${config.realName}")
    }

    private fun handleLine(line: String) {
        if (closed.get()) return
        lastInboundMillis = nowMillis()
        rawTap?.invoke(false, trafficRedactor.redact(line))
        val message = IrcMessage.parse(line) ?: return
        synchronized(stateLock) {
            if (closed.get()) return
            if (handleStsAdvertisement(message)) return
            applyToSession(message)
            if (!closed.get()) emit(IrcEvent.MessageReceived(message))
        }
    }

    private fun applyToSession(message: IrcMessage) {
        val numeric = message.numeric
        if (bouncerBindSent && registeredNickname == null &&
            ((message.command.equals("FAIL", true) && message.parameters.firstOrNull().equals("BOUNCER", true)) ||
                ((numeric == 421 || numeric == 461) && message.parameters.getOrNull(1).equals("BOUNCER", true)))
        ) {
            shutdown(BouncerBindRejectedException("The bouncer rejected network ${config.bouncerNetId}. Check that the upstream exists and is enabled, then Connect to retry."))
            return
        }
        if (registeredNickname == null && (numeric == 464 || (config.serverPassword != null &&
                ((numeric == 461 && message.parameters.getOrNull(1).equals("PASS", ignoreCase = true)) ||
                    (message.command.equals("FAIL", ignoreCase = true) && message.parameters.firstOrNull().equals("PASS", ignoreCase = true)))))) {
            shutdown(AuthenticationRejectedException("Server password was rejected; correct the saved server password"))
            return
        }
        saslAuthenticator?.let { authenticator ->
            if (numeric != null) authenticator.handleNumeric(numeric, message)
            authenticator.handleMessage(message)
        }
        if (closed.get()) return
        negotiator?.handle(message)
        if (closed.get()) return

        when {
            message.command == "PING" && message.parameters.isNotEmpty() ->
                sendLine("PONG :${message.parameters.last()}")
            message.command == "PONG" -> {
                val probe = pendingProbe
                if (probe != null && message.parameters.lastOrNull() == probe.token) {
                    pendingProbe = null
                    probe.deadlineJob?.cancel()
                    probe.result.complete(System.nanoTime() / 1_000_000 < probe.deadlineMillis)
                }
            }
            numeric == 1 && registeredNickname == null -> {
                if (saslRequired && !saslSucceeded) {
                    handleAuthenticationFailure(SaslOutcome.Failure(0, "Server completed registration without required SASL authentication"))
                    return
                }
                val nickname = message.parameters.firstOrNull() ?: config.nick
                registeredNickname = nickname
                emit(IrcEvent.Registered(nickname, message.parameters.lastOrNull() ?: ""))
                val away = config.initialAway
                if (away != null && !awaySent) sendAway(away)
            }
            (numeric == 432 || numeric == 433 || numeric == 436 || numeric == 437) && registeredNickname == null -> {
                val rejectedNickname = message.parameters.getOrNull(1)
                if (rejectedNickname != null && CaseMapping.RFC1459.fold(rejectedNickname) != CaseMapping.RFC1459.fold(currentNickname)) return
                val retryNick = collisionNicknames.getOrNull(nextCollisionNickname)
                if (retryNick == null) {
                    shutdown(IOException("All configured and fallback nicknames are unavailable; choose another nickname"))
                } else {
                    nextCollisionNickname += 1
                    currentNickname = retryNick
                    sendLine("NICK $retryNick")
                }
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
            } catch (error: Exception) {
                shutdown(error)
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
                        rawTap?.invoke(true, trafficRedactor.redact(line))
                        stream.write((line + "\r\n").toByteArray(Charsets.UTF_8))
                        stream.flush()
                    }
                }
            } catch (error: Exception) {
                shutdown(error)
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
        if (closed.get()) return
        trafficRedactor.rememberOutbound(line)
        outbound.trySend(line)
    }

    public fun redactPresentation(text: String): String = trafficRedactor.redactPresentation(text)

    public suspend fun probe(timeoutMillis: Long = 5_000): Boolean {
        require(timeoutMillis > 0) { "probe timeout must be positive" }
        val result = synchronized(stateLock) {
            if (closed.get() || socket == null) return false
            val monotonicNow = System.nanoTime() / 1_000_000
            pendingProbe?.takeIf { it.deadlineMillis <= monotonicNow }?.let {
                pendingProbe = null
                it.deadlineJob?.cancel()
                it.result.complete(false)
            }
            val existing = pendingProbe
            if (existing != null) {
                existing.result
            } else {
                val probe = Probe("yardhal-probe-${java.util.UUID.randomUUID()}", monotonicNow + timeoutMillis, CompletableDeferred())
                pendingProbe = probe
                probe.deadlineJob = scope.launch {
                    delay(timeoutMillis)
                    synchronized(stateLock) {
                        if (pendingProbe === probe) {
                            pendingProbe = null
                            probe.result.complete(false)
                        }
                    }
                }
                sendLine("PING :${probe.token}")
                probe.result
            }
        }
        return withTimeoutOrNull(timeoutMillis) { result.await() } ?: false
    }

    public fun send(message: IrcMessage) {
        sendLine(message.toWire())
    }

    public fun disconnect() {
        shutdown(null)
    }

    private fun emit(event: IrcEvent) {
        if (!closed.get()) eventChannel.trySend(event)
    }

    private fun shutdown(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        synchronized(stateLock) {
            pendingProbe?.result?.complete(false)
            pendingProbe = null
            registeredNickname = null
        }
        if (trustedTlsTransport && socket != null) {
            stsPolicyStore?.let { StsResolver.refreshOnDisconnect(it, config.host, nowMillis() / 1000) }
        }
        runCatching { socket?.close() }
        eventChannel.trySend(IrcEvent.Disconnected(cause))
        eventChannel.close()
        outbound.close()
        job.cancel()
    }
}

