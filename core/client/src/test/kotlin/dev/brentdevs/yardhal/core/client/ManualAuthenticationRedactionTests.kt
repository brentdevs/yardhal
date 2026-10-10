package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks
import dev.brentdevs.yardhal.core.protocol.IrcTags
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class ManualAuthenticationRedactionTests {
    @Test
    fun manualAuthenticationWireEchoesAndRetiredExportUseSafePresentation() = runBlocking {
        val cases = listOf(
            "REGISTER * me@example.org modern-register-password" to listOf("modern-register-password"),
            "PRIVMSG NickServ@services.example.org :REGISTER service-register-password me@example.org" to listOf("service-register-password"),
            "NOTICE NickServ@services.example.org :SET PASSWORD replacement-password" to listOf("replacement-password"),
            "PRIVMSG NickServ :GHOST nickname ghost-password" to listOf("ghost-password"),
            "PRIVMSG NickServ :RECOVER nickname recover-password" to listOf("recover-password"),
            "NS register manual-ns-password me@example.org" to listOf("manual-ns-password"),
            "IDENTIFY top-level-password" to listOf("top-level-password"),
            "OPER operator oper-password" to listOf("oper-password"),
            "PRIVMSG NickServ :IDENTIFY\taccount\ttab-identify-password" to listOf("tab-identify-password"),
            "NS GHOST nickname\ttab-ghost-password" to listOf("tab-ghost-password"),
            "PRIVMSG NickServ :SET\tPASSWORD\tfirst-component second-component\tthird-component" to
                listOf("first-component", "second-component", "third-component"),
            "IDENTIFY <redacted>manual-password" to listOf("<redacted>manual-password"),
        )
        val configuredSecret = "<redacted>configured-password"
        val chat = "PRIVMSG #chat :IDENTIFY ordinary-chat"
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                when {
                    line.startsWith("USER ") -> server.sendLine(":srv 001 primary :Welcome")
                    line == chat -> server.sendLine(":primary!u@h $line")
                    else -> {
                        val index = cases.indexOfFirst { it.first == line }
                        if (index >= 0) {
                            server.sendLine(":primary!u@h $line")
                            server.sendLine(":srv NOTICE primary :Echo ${cases[index].second.joinToString("|")} done-$index")
                        }
                    }
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val tapped = CopyOnWriteArrayList<String>()
            val connection = IrcConnection(
                IrcConnectionConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    tls = false,
                    nick = "primary",
                    capabilities = emptySet(),
                    nickServService = "NickServ@services.example.org",
                    knownSecrets = setOf(configuredSecret),
                    connectTimeoutMillis = 1_000,
                ),
                rawTap = { _, line -> tapped += line },
            )
            val redactReport = connection.presentationRedactor()
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                collector.awaitRegistered()
                for ((index, case) in cases.withIndex()) {
                    val (wire, passwords) = case
                    connection.sendLine(wire)
                    val events = ArrayList<IrcEvent.MessageReceived>()
                    while (true) {
                        val event = collector.awaitInstance<IrcEvent.MessageReceived>()
                        events += event
                        if (event.message.parameters.lastOrNull()?.endsWith("done-$index") == true) break
                    }
                    assertTrue(server.receivedLines.contains(wire), wire)
                    val echo = events.single { it.message.prefix?.toString() == "primary!u@h" }
                    assertEquals(IrcMessage.parse(":primary!u@h $wire"), echo.message, wire)
                    val visibleEcho = connection.redactPresentation(echo.message.toWire())
                    assertFalse(passwords.any { it in visibleEcho }, visibleEcho)
                    val individualEcho = events.last().message.parameters.last()
                    assertEquals(
                        "Echo ${passwords.joinToString("|") { "<redacted>" }} done-$index",
                        connection.redactPresentation(individualEcho),
                        wire,
                    )
                }
                server.sendLine(":srv NOTICE primary :Saved $configuredSecret")
                while (true) {
                    val event = collector.awaitInstance<IrcEvent.MessageReceived>()
                    if (event.message.parameters.lastOrNull() == "Saved $configuredSecret") {
                        assertEquals("Saved <redacted>", connection.redactPresentation(event.message.parameters.last()))
                        break
                    }
                }
                connection.sendLine(chat)
                while (true) {
                    val event = collector.awaitInstance<IrcEvent.MessageReceived>()
                    if (event.message.parameters.lastOrNull() == "IDENTIFY ordinary-chat") {
                        assertEquals(":primary!u@h $chat", connection.redactPresentation(event.message.toWire()))
                        break
                    }
                }
                val allPasswords = cases.flatMap { it.second } + configuredSecret
                assertFalse(tapped.any { line -> allPasswords.any { it in line } })
                assertTrue(tapped.any { "REGISTER <redacted>" in it })
                assertTrue(tapped.any { "SET PASSWORD <redacted>" in it })
                assertEquals("account nickname operator me@example.org", connection.redactPresentation("account nickname operator me@example.org"))
                connection.disconnect()
                assertEquals(
                    "Recovered: ${allPasswords.joinToString("|") { "<redacted>" }}",
                    redactReport("Recovered: ${allPasswords.joinToString("|")}"),
                )
                assertEquals("account nickname operator me@example.org", redactReport("account nickname operator me@example.org"))
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test
    fun bouncerManagementRawTapMasksUnknownPasswordsAndRetainsWildcardDiagnostics() = runBlocking {
        val draftPassword = "draft;secret\\ value"
        val changedPassword = "changed-secret"
        val cases = listOf(
            IrcBouncerNetworks.addNetworkCommand(IrcBouncerNetworks.Attributes(name = "new", pass = draftPassword)) to
                ":srv NOTICE * :Rejected $draftPassword and ${IrcTags.escape(draftPassword)} done-0",
            IrcBouncerNetworks.changeNetworkCommand("42", IrcBouncerNetworks.Attributes(pass = changedPassword)) to
                ":srv NOTICE * :Rejected $changedPassword done-1",
            "PRIVMSG BouncerServ :network update new -pass service-secret" to
                ":BouncerServ!service@bouncer NOTICE tester :Rejected service-secret done-2",
            "PRIVMSG *controlpanel :AddServer account new irc.example +6697 module-secret" to
                ":*controlpanel!service@znc PRIVMSG tester :Rejected module-secret done-3",
        )
        val passwords = listOf(draftPassword, IrcTags.escape(draftPassword), "draft;secret\\", "value", changedPassword, "service-secret", "module-secret")
        val diagnostic = ":srv NOTICE * :Upstream connection refused"
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                if (line.startsWith("USER ")) {
                    server.sendLine(":srv 001 tester :Welcome")
                } else {
                    val index = cases.indexOfFirst { it.first == line }
                    if (index >= 0) {
                        server.sendLine(":tester!u@h $line")
                        server.sendLine(cases[index].second)
                    }
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val tapped = CopyOnWriteArrayList<Pair<Boolean, String>>()
            val connection = IrcConnection(
                IrcConnectionConfig(host = "127.0.0.1", port = server.port, tls = false, nick = "tester", capabilities = emptySet()),
                rawTap = { outbound, line -> tapped += outbound to line },
            )
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                collector.awaitRegistered()
                for ((index, case) in cases.withIndex()) {
                    connection.sendLine(case.first)
                    while (true) {
                        val event = collector.awaitInstance<IrcEvent.MessageReceived>()
                        if (event.message.parameters.lastOrNull()?.endsWith("done-$index") == true) break
                    }
                    assertTrue(server.receivedLines.contains(case.first))
                }
                server.sendLine(diagnostic)
                while (true) {
                    val event = collector.awaitInstance<IrcEvent.MessageReceived>()
                    if (event.message.parameters.lastOrNull() == "Upstream connection refused") break
                }
                assertTrue(tapped.any { !it.first && it.second == diagnostic })
                assertTrue(tapped.any { !it.first && it.second == ":srv NOTICE * :Rejected <redacted> and <redacted> done-0" })
                assertTrue(tapped.any { !it.first && it.second == ":srv NOTICE * :Rejected <redacted> done-1" })
                assertTrue(tapped.any { it.first && it.second == "BOUNCER ADDNETWORK <redacted>" })
                assertTrue(tapped.any { it.first && it.second == "BOUNCER CHANGENETWORK <redacted>" })
                assertFalse(tapped.any { (_, line) -> passwords.any { it in line } })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }

    @Test
    fun zncComposedPassAndRawVaultPasswordAreMaskedAcrossSocketTraffic() = runBlocking {
        val password = "vault:password\\suffix"
        val composedPass = "account/network:$password"
        val diagnostic = ":srv NOTICE * :Rejected $password and $composedPass"
        LoopbackIrcServer().use { server ->
            server.start()
            server.lineListener = { line ->
                when {
                    line.startsWith("PASS ") -> server.sendLine(":srv $line")
                    line.startsWith("USER ") -> {
                        server.sendLine(":srv 001 tester :Welcome")
                        server.sendLine(diagnostic)
                    }
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val tapped = CopyOnWriteArrayList<Pair<Boolean, String>>()
            val connection = IrcConnection(
                IrcConnectionConfig(
                    host = "127.0.0.1", port = server.port, tls = false, nick = "tester", capabilities = emptySet(),
                    serverPassword = composedPass, knownSecrets = setOf(password),
                ),
                rawTap = { outbound, line -> tapped += outbound to line },
            )
            try {
                val collector = EventCollector(scope, connection.events)
                connection.start()
                collector.awaitRegistered()
                while (true) {
                    val event = collector.awaitInstance<IrcEvent.MessageReceived>()
                    if (event.message.parameters.lastOrNull() == diagnostic.substringAfter(" :")) break
                }
                assertTrue(server.receivedLines.contains("PASS :$composedPass"))
                assertTrue(tapped.any { it.first && it.second == "PASS <redacted>" })
                assertTrue(tapped.any { !it.first && it.second == ":srv PASS <redacted>" })
                assertTrue(tapped.any { !it.first && it.second == ":srv NOTICE * :Rejected <redacted> and <redacted>" })
                assertFalse(tapped.any { (_, line) -> password in line || composedPass in line })
            } finally {
                connection.disconnect()
                scope.cancel()
            }
        }
    }
}
