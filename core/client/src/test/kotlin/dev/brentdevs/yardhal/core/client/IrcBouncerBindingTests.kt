package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class IrcBouncerBindingTests {
    private fun config(port: Int, sasl: Boolean = true): IrcConnectionConfig = IrcConnectionConfig(
        host = "127.0.0.1", port = port, tls = false, nick = "tester", bouncerNetId = "42",
        saslAuthcid = "account".takeIf { sasl }, saslPassword = "account-secret".takeIf { sasl },
        capabilities = setOf(IrcBouncerNetworks.CAPABILITY, "sasl"),
    )

    private fun LoopbackIrcServer.negotiate(line: String, advertised: String = "sasl=PLAIN soju.im/bouncer-networks"): Boolean = when {
        line.startsWith("CAP LS") -> { sendLine(":srv CAP * LS :$advertised"); true }
        line.startsWith("CAP REQ :") -> { sendLine(":srv CAP * ACK :${line.substringAfter("CAP REQ :")}"); true }
        line == "AUTHENTICATE PLAIN" -> { sendLine("AUTHENTICATE +"); true }
        line.startsWith("AUTHENTICATE ") -> { sendLine(":srv 903 * :Authenticated"); true }
        else -> false
    }

    @Test fun bindingFollowsSuccessfulSaslAndPrecedesCapEndNickAndUserExactlyOnce() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                when {
                    server.negotiate(line) -> Unit
                    line.startsWith("USER ") -> server.sendLine(":srv 001 tester :Welcome")
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val connection = IrcConnection(config(server.port))
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                val events = collector.drainUntilRegistered()
                assertTrue(events.filterIsInstance<IrcEvent.SaslResult>().any { it.outcome == SaslOutcome.Success })
                val lines = server.receivedLines.toList()
                val bind = lines.indexOf("BOUNCER BIND 42")
                assertTrue(bind > lines.indexOfLast { it.startsWith("AUTHENTICATE ") })
                assertTrue(bind < lines.indexOf("CAP END"))
                assertTrue(bind < lines.indexOfFirst { it.startsWith("NICK ") })
                assertTrue(bind < lines.indexOfFirst { it.startsWith("USER ") })
                assertEquals(1, lines.count { it == "BOUNCER BIND 42" })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test fun passAuthenticationIsSentBeforeBindingAndDoesNotInventSasl() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                when {
                    server.negotiate(line) -> Unit
                    line.startsWith("USER ") -> server.sendLine(":srv 001 tester :Welcome")
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val connection = IrcConnection(config(server.port, sasl = false).copy(serverPassword = "account:secret"))
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                collector.awaitRegistered()
                val lines = server.receivedLines.toList()
                assertTrue(lines.indexOf("PASS :account:secret") < lines.indexOf("BOUNCER BIND 42"))
                assertTrue(lines.indexOf("BOUNCER BIND 42") < lines.indexOf("CAP END"))
                assertFalse(lines.any { it.startsWith("AUTHENTICATE ") })
                assertEquals(1, lines.count { it.startsWith("PASS ") })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test fun absentBindingCapabilityFailsBeforeNickUserEvenWithEmptyConfiguredCapabilities() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line -> server.negotiate(line, "server-time") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val connection = IrcConnection(config(server.port, sasl = false).copy(capabilities = emptySet()))
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                val events = collector.drainUntilDisconnected()
                assertIs<BouncerBindRejectedException>((events.last() as IrcEvent.Disconnected).cause)
                assertFalse(events.any { it is IrcEvent.Registered })
                assertFalse(server.receivedLines.any { it.startsWith("NICK ") || it.startsWith("USER ") || it.startsWith("BOUNCER BIND ") })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test fun actualSojuInvalidNetidWithoutBindContextHardBlocksRecoveryNudges() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                when {
                    server.negotiate(line) -> Unit
                    line == "BOUNCER BIND 42" -> server.sendLine(":srv FAIL BOUNCER INVALID_NETID 42 :Unknown network ID")
                }
            }
            val attempts = AtomicInteger()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reconnector = IrcReconnector(scope, ReconnectPolicy(initialDelayMillis = 1, maxDelayMillis = 1), {
                attempts.incrementAndGet()
                IrcConnection(config(server.port))
            })
            val collector = EventCollector(scope, kotlinx.coroutines.flow.flow {
                reconnector.events.collect { emit(it.event) }
            })
            try {
                reconnector.start()
                val events = collector.drainUntilDisconnected()
                assertIs<BouncerBindRejectedException>((events.last() as IrcEvent.Disconnected).cause)
                assertFalse(events.any { it is IrcEvent.Registered })
                repeat(10) { reconnector.nudge() }
                delay(100)
                assertEquals(1, attempts.get())
                assertEquals(1, server.receivedLines.count { it == "BOUNCER BIND 42" })
                assertIs<ReconnectState.Stopped>(reconnector.state.value)
            } finally {
                reconnector.stop()
                scope.cancel()
            }
        }
    }

    @Test fun plaintextStsUpgradeDoesNotExposePassSaslOrBindingBeforeSecureReconnect() = runBlocking {
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                if (line.startsWith("CAP LS")) server.sendLine(":srv CAP * LS :sts=port=6697,duration=3600 sasl=PLAIN soju.im/bouncer-networks")
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val upgradePort = AtomicInteger()
            val connection = IrcConnection(config(server.port).copy(serverPassword = "server-secret"),
                onStsUpgrade = upgradePort::set)
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                collector.drainUntilDisconnected()
                assertEquals(6697, upgradePort.get())
                assertTrue(server.receivedLines.any { it.startsWith("CAP LS") })
                assertFalse(server.receivedLines.any {
                    it.startsWith("PASS ") || it.startsWith("AUTHENTICATE ") || it.startsWith("BOUNCER BIND ") ||
                        it.startsWith("NICK ") || it.startsWith("USER ")
                })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test fun managementPasswordsUnknownToTheConnectionNeverReachTrafficPresentation() {
        val redactor = TrafficRedactor(emptySet(), "NickServ")
        val commands = listOf(
            "BOUNCER ADDNETWORK :name=New;pass=unseen-secret",
            "BOUNCER CHANGENETWORK 42 :pass=unseen-secret",
            "PRIVMSG BouncerServ :network create -name new -pass unseen-secret ircs://irc.example",
            "PRIVMSG BouncerServ :network update new -pass unseen-secret",
            "PRIVMSG *controlpanel :AddServer account new irc.example +6697 unseen-secret",
            ":BouncerServ!service@bouncer NOTICE tester :Failed: unseen-secret",
            ":*controlpanel!service@znc PRIVMSG tester :Server password unseen-secret",
            ":srv BOUNCER NETWORK 42 :name=New;pass=unseen-secret",
            ":srv FAIL BOUNCER UNKNOWN_ATTRIBUTE CHANGENETWORK pass=unseen-secret",
        )
        for (line in commands) assertFalse(redactor.redact(line).contains("unseen-secret"))
        assertEquals("BOUNCER BIND 42", redactor.redact("BOUNCER BIND 42"))
        assertEquals(":alice!u@h PRIVMSG tester :Hello", redactor.redact(":alice!u@h PRIVMSG tester :Hello"))
    }
}
