package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BouncerZncManagementTests {
    @Test
    fun upstreamConnectionControlsRequireRealServiceAcknowledgements() {
        assertEquals("Connect", BouncerZncCommands.connect().text)
        assertTrue(BouncerZncCommands.connect().boundOnly)
        assertTrue(BouncerZncCommands.connect().matchesAcknowledgement("Connecting to irc.example.org..."))
        assertFalse(BouncerZncCommands.connect().matchesAcknowledgement("You don't have any servers added."))
        assertEquals("Jump", BouncerZncCommands.reconnect().text)
        assertTrue(BouncerZncCommands.reconnect().matchesAcknowledgement("Jumping to the next server in the list..."))
        assertEquals("Disconnect", BouncerZncCommands.disconnect().text)
        assertTrue(BouncerZncCommands.disconnect().matchesAcknowledgement("Disconnected from IRC. Use 'connect' to reconnect."))
        assertFalse(BouncerZncCommands.disconnect().matchesAcknowledgement("You must be connected with a network to use this command"))
    }

    @Test
    fun parsesStatusAndControlpanelNetworkTables() {
        for (onIrcHeader in listOf("On IRC", "OnIRC")) {
            val lines = table(
                listOf("Network", onIrcHeader, "IRC Server", "IRC User", "Channels"),
                listOf("libera", "Yes", "irc.libera.chat", "a|b!ident@host", "12"),
                listOf("offline", "No", "", "", ""),
            )
            assertEquals(
                listOf(
                    ZncNetwork("libera", true, "irc.libera.chat", "a|b!ident@host", 12),
                    ZncNetwork("offline", false),
                ),
                BouncerZncParser.networks(lines),
            )
        }
    }

    @Test
    fun parsesMaskedServerPasswordsAndCurrentMarker() {
        val servers = assertNotNull(
            BouncerZncParser.servers(
                table(
                    listOf("Host", "Port", "SSL", "Password"),
                    listOf("irc.example*", "6697", "SSL", "******"),
                    listOf("backup.example", "6667", "", ""),
                ),
            ),
        )
        assertEquals(listOf("irc.example", "backup.example"), servers.map { it.host })
        assertTrue(servers[0].current)
        assertTrue(servers[0].tls)
        assertNull(servers[0].password)
        assertFalse(servers[0].passwordChanged)
        assertFalse(servers[1].tls)
        assertEquals("", servers[1].password)
    }

    @Test
    fun parsesChannelPermissionsStatusesAndInheritance() {
        val channels = assertNotNull(
            BouncerZncParser.channels(
                table(
                    listOf("Index", "Name", "Status", "In config", "Buffer", "Clear", "Modes", "Users", "@"),
                    listOf("1", "@+#room", "Detached", "yes", "*200", "*yes", "+nt", "42", "3"),
                    listOf("2", "&local", "Disabled", "", "50", "", "", "0", "0"),
                    listOf("3", "+modeless", "Trying", "yes", "*0", "*", "", "0", "0"),
                ),
            ),
        )
        assertEquals(ZncChannelDraft("#room", detached = true, bufferSize = 200, autoClearChanBuffer = true), channels[0].toDraft())
        assertEquals(ZncChannelDraft("&local", disabled = true), channels[1].toDraft())
        assertEquals(ZncChannelDraft("+modeless", bufferSize = 0, autoClearChanBuffer = false), channels[2].toDraft())
        assertEquals(50, channels[1].bufferSize)
    }

    @Test
    fun distinguishesEmptyRepliesFromIncompleteMalformedAndDeniedReplies() {
        assertEquals(emptyList(), BouncerZncParser.networks(listOf("No networks")))
        assertEquals(emptyList(), BouncerZncParser.servers(listOf("You don't have any servers added.")))
        assertEquals(emptyList(), BouncerZncParser.channels(listOf("There are no channels defined.")))
        assertNull(BouncerZncParser.networks(emptyList()))
        assertNull(BouncerZncParser.servers(listOf("Access denied!")))
        assertNull(BouncerZncParser.channels(listOf("You must be connected with a network to use this command")))
        val lines = table(listOf("Host", "Port", "SSL", "Password"), listOf("host", "6697", "SSL", "******"))
        assertNull(BouncerZncParser.servers(lines.dropLast(1)))
        assertNull(BouncerZncParser.servers(table(listOf("Host", "Port", "SSL", "Password"), listOf("host", "bogus", "SSL", ""))))
        assertNull(BouncerZncParser.servers(table(listOf("Host", "Port", "SSL", "Password"), listOf("host", "0", "SSL", ""))))
        assertNull(BouncerZncParser.networks(lines))
    }

    @Test
    fun parsesControlpanelSettingEchoes() {
        assertEquals(ZncNetworkVariable.Altnick to "other", BouncerZncParser.networkSetting("AltNick = other"))
        assertEquals(ZncNetworkVariable.RealName to "Two Words", BouncerZncParser.networkSetting("RealName = Two Words"))
        assertEquals(
            ZncChannelSetting("#room", ZncChannelVariable.BufferSize, "50", true),
            BouncerZncParser.channelSetting("#room: BufferSize = 50 (default)"),
        )
        assertNull(BouncerZncParser.networkSetting("Error: Unknown variable"))
        assertNull(BouncerZncParser.channelSetting("Error: No channels matching [#room] found."))
    }

    @Test
    fun usesRealUnquotedGrammarAndExplicitTargets() {
        assertEquals("GetNetwork Nick \$user libera", BouncerZncCommands.getNetwork("libera", ZncNetworkVariable.Nick).text)
        assertEquals("SetNetwork RealName \$user libera Two Words", BouncerZncCommands.setNetwork("libera", ZncNetworkVariable.RealName, "Two Words").text)
        assertEquals("GetChan Detached \$user libera #room", BouncerZncCommands.getChan("libera", "#room", ZncChannelVariable.Detached).text)
        assertEquals("SetChan BufferSize \$user libera #room 200", BouncerZncCommands.setChan("libera", "#room", ZncChannelVariable.BufferSize, "200").text)
        assertEquals("AddServer \$user libera irc.example +6697 two words", BouncerZncCommands.addServer("libera", ZncServerDraft("irc.example", password = "two words")).text)
        val deletion = BouncerZncCommands.deleteServer("libera", ZncServerDraft("irc.example", password = null))
        assertEquals("DelServer irc.example 6697", deletion.text)
        assertEquals(ZncCommandTarget.Status, deletion.target)
        assertTrue(deletion.boundOnly)
        assertTrue(deletion.matchesAcknowledgement("Server removed"))
        assertFalse(deletion.matchesAcknowledgement("No such server"))
        assertEquals(ZncCommandTarget.ControlPanel, BouncerZncCommands.addServer("libera", ZncServerDraft("host")).target)
        assertTrue(BouncerZncCommands.listServers().boundOnly)
        assertTrue(BouncerZncCommands.listChans().boundOnly)
        assertFalse(BouncerZncCommands.listNetworks().boundOnly)
    }

    @Test
    fun mutationAcknowledgementsRequireTheExpectedCanonicalValue() {
        val nick = BouncerZncCommands.setNetwork("libera", ZncNetworkVariable.Nick, "desired")
        assertTrue(nick.matchesAcknowledgement("Nick = desired"))
        assertFalse(nick.matchesAcknowledgement("Nick = previous"))
        assertFalse(nick.matchesAcknowledgement("Access denied!"))
        val trust = BouncerZncCommands.setNetwork("libera", ZncNetworkVariable.TrustPKI, "true")
        assertTrue(trust.matchesAcknowledgement("TrustPKI = 1"))
        assertFalse(trust.matchesAcknowledgement("TrustPKI = 0"))
        val flood = BouncerZncCommands.setNetwork("libera", ZncNetworkVariable.FloodRate, "2.50")
        assertTrue(flood.matchesAcknowledgement("FloodRate = 2.5"))
        assertFalse(flood.matchesAcknowledgement("FloodRate = 3"))
        val roundedFlood = BouncerZncCommands.setNetwork("libera", ZncNetworkVariable.FloodRate, "2.675")
        assertTrue(roundedFlood.matchesAcknowledgement("FloodRate = 2.67"))
        assertFalse(roundedFlood.matchesAcknowledgement("FloodRate = 2.68"))
        val halfEvenFlood = BouncerZncCommands.setNetwork("libera", ZncNetworkVariable.FloodRate, "0.125")
        assertEquals("SetNetwork FloodRate \$user libera 0.125", halfEvenFlood.text)
        assertTrue(halfEvenFlood.matchesAcknowledgement("FloodRate = 0.12"))
        assertFalse(halfEvenFlood.matchesAcknowledgement("FloodRate = 0.13"))
        val binaryFlood = BouncerZncCommands.setNetwork("libera", ZncNetworkVariable.FloodRate, "0.135")
        assertTrue(binaryFlood.matchesAcknowledgement("FloodRate = 0.14"))
        assertFalse(binaryFlood.matchesAcknowledgement("FloodRate = 0.13"))
        val detached = BouncerZncCommands.setChan("libera", "#room", ZncChannelVariable.Detached, "true")
        assertTrue(detached.matchesAcknowledgement("#room: Detached = 1"))
        assertFalse(detached.matchesAcknowledgement("#other: Detached = 1"))
        assertFalse(detached.matchesAcknowledgement("#room: Detached = 0"))
        val server = BouncerZncCommands.addServer("libera", ZncServerDraft("irc.example"))
        assertTrue(server.matchesAcknowledgement("Added IRC Server irc.example +6697 to network libera for user alice."))
        assertFalse(server.matchesAcknowledgement("Error: Could not add IRC server irc.example +6697 to network libera for user alice."))
        assertFalse(server.matchesAcknowledgement("Added IRC Server other +6697 to network libera for user alice."))
        assertFalse(BouncerZncCommands.listServers().matchesAcknowledgement("+----+"))
    }

    @Test
    fun unchangedNetworksAndPasswordsEmitNoCommands() {
        val baseline = ZncNetworkDraft(
            "libera",
            settings = mapOf(ZncNetworkVariable.Nick to "me", ZncNetworkVariable.RealName to "Me"),
            servers = listOf(ZncServerDraft("irc.example", password = null, current = true)),
        )
        assertEquals(emptyList(), BouncerZncCommands.networkDiff(baseline, baseline))
        val draft = baseline.copy(settings = baseline.settings + (ZncNetworkVariable.RealName to "New Name"))
        assertEquals(listOf("SetNetwork RealName \$user libera New Name"), BouncerZncCommands.networkDiff(baseline, draft).map { it.text })
    }

    @Test
    fun serverDeletionAndAppendPreserveUnknownPasswordsAndOrder() {
        val first = ZncServerDraft("first", password = null)
        val second = ZncServerDraft("second", password = null)
        val third = ZncServerDraft("third", password = "", passwordChanged = true)
        assertEquals(
            listOf("DelServer second 6697", "AddServer \$user libera third +6697"),
            BouncerZncCommands.serverDiff("libera", listOf(first, second), listOf(first, third)).map { it.text },
        )
        assertEquals(
            listOf("DelServer first 6697"),
            BouncerZncCommands.serverDiff("libera", listOf(first, second), listOf(second)).map { it.text },
        )
    }

    @Test
    fun safeReorderRecreatesOnlyKnownPasswordsInDesiredOrder() {
        val first = ZncServerDraft("first", password = "secret")
        val second = ZncServerDraft("second", password = null)
        assertEquals(
            listOf("DelServer first 6697", "AddServer \$user libera first +6697 secret"),
            BouncerZncCommands.serverDiff("libera", listOf(first, second), listOf(second, first)).map { it.text },
        )
    }

    @Test
    fun unknownPasswordsPreventUnsafeReorderAndTlsChanges() {
        val first = ZncServerDraft("first", password = null)
        val second = ZncServerDraft("second", password = null)
        assertFailsWith<IllegalArgumentException> {
            BouncerZncCommands.serverDiff("libera", listOf(first, second), listOf(second, first))
        }
        assertFailsWith<IllegalArgumentException> {
            BouncerZncCommands.serverDiff("libera", listOf(first), listOf(first.copy(tls = false)))
        }
        assertEquals(
            listOf("DelServer first 6697", "AddServer \$user libera first 6697 replacement"),
            BouncerZncCommands.serverDiff("libera", listOf(first), listOf(first.copy(tls = false, passwordChanged = true, password = "replacement"))).map { it.text },
        )
        assertEquals(
            listOf("DelServer first 6697", "AddServer \$user libera first +6697"),
            BouncerZncCommands.serverDiff("libera", listOf(first), listOf(first.copy(passwordChanged = true, password = ""))).map { it.text },
        )
    }

    @Test
    fun unchangedPasswordIntentCannotClearOrReplaceAnExistingSecret() {
        val masked = ZncServerDraft("first", password = null)
        assertEquals(emptyList(), BouncerZncCommands.serverDiff("libera", listOf(masked), listOf(masked.copy(password = "", passwordChanged = false))))
        assertEquals(emptyList(), BouncerZncCommands.serverDiff("libera", listOf(masked), listOf(masked.copy(password = "ignored", passwordChanged = false))))
        assertFailsWith<IllegalArgumentException> {
            BouncerZncCommands.serverDiff("libera", listOf(masked), listOf(masked.copy(host = "other", password = "", passwordChanged = false)))
        }
        assertFailsWith<IllegalArgumentException> {
            BouncerZncCommands.serverDiff("libera", listOf(masked), listOf(masked.copy(tls = false, password = "", passwordChanged = false)))
        }
    }

    @Test
    fun rejectsAmbiguousZncDeletionMatching() {
        val retained = ZncServerDraft("same", port = 7000, password = null)
        val removed = ZncServerDraft("same", port = 6667, password = null)
        assertFailsWith<IllegalArgumentException> {
            BouncerZncCommands.serverDiff("libera", listOf(retained, removed), listOf(retained))
        }
        assertFailsWith<IllegalArgumentException> {
            BouncerZncCommands.serverDiff("libera", listOf(retained, retained.copy(tls = false)), listOf(retained))
        }
    }

    @Test
    fun boundStatusDeletionUsesPortAndRemovesOnlyTheFirstMatchingDuplicate() {
        val first = ZncServerDraft("same", port = 7000, tls = true, password = null)
        val second = ZncServerDraft("same", port = 7001, tls = false, password = null)
        assertEquals(
            listOf("DelServer same 7001"),
            BouncerZncCommands.serverDiff("libera", listOf(first, second), listOf(first)).map { it.text },
        )
        assertEquals(
            listOf("DelServer same 7000"),
            BouncerZncCommands.serverDiff("libera", listOf(first, first.copy(tls = false)), listOf(first.copy(tls = false))).map { it.text },
        )
    }

    @Test
    fun channelDiffChangesOnlyRequestedFieldsAndResetsInheritance() {
        val baseline = ZncChannelDraft("#room", bufferSize = 200, autoClearChanBuffer = true)
        assertEquals(emptyList(), BouncerZncCommands.channelDiff("libera", baseline, baseline))
        assertEquals(
            listOf("SetChan Detached \$user libera #room true", "SetChan BufferSize \$user libera #room -", "SetChan AutoClearChanBuffer \$user libera #room -"),
            BouncerZncCommands.channelDiff("libera", baseline, baseline.copy(detached = true, bufferSize = null, autoClearChanBuffer = null)).map { it.text },
        )
        val reset = BouncerZncCommands.setChan("libera", "#room", ZncChannelVariable.BufferSize, "-")
        assertTrue(reset.matchesAcknowledgement("#room: BufferSize = 50"))
        assertFalse(reset.matchesAcknowledgement("Setting failed, limit for buffer size is 100"))
    }

    @Test
    fun channelEnableDisableRequiresActualBoundNetworkAndAcknowledgement() {
        val baseline = ZncChannelDraft("#room")
        assertFailsWith<IllegalArgumentException> {
            BouncerZncCommands.channelDiff("libera", baseline, baseline.copy(disabled = true), boundNetwork = "other")
        }
        val command = BouncerZncCommands.channelDiff("libera", baseline, baseline.copy(disabled = true), boundNetwork = "libera").single()
        assertEquals("DisableChan #room", command.text)
        assertTrue(command.boundOnly)
        assertTrue(command.matchesAcknowledgement("Disabled 1 channel"))
        assertFalse(command.matchesAcknowledgement("Disabled 0 channels"))
        assertFalse(command.matchesAcknowledgement("There was 1 channel matching [#room]"))
    }

    @Test
    fun rejectsCommandInjectionUnsafeTokensAndUnrepresentableValues() {
        for (bad in listOf("a\r", "a\n", "a\u0000", "two words", "'quoted'", "\$network")) {
            assertFailsWith<IllegalArgumentException> { BouncerZncCommands.getNetwork(bad, ZncNetworkVariable.Nick) }
        }
        for (bad in listOf("#a\nPRIVMSG x :bad", "#*", "#?", "#one,#two", "#two words", "'#quoted'")) {
            assertFailsWith<IllegalArgumentException> { BouncerZncCommands.getChan("libera", bad, ZncChannelVariable.Detached) }
        }
        for (bad in listOf("", " leading", "trailing ", "line\nbreak", "line\rbreak", "nul\u0000value")) {
            assertFailsWith<IllegalArgumentException> { BouncerZncCommands.setNetwork("libera", ZncNetworkVariable.RealName, bad) }
        }
        assertFailsWith<IllegalArgumentException> { BouncerZncCommands.setNetwork("libera", ZncNetworkVariable.FloodRate, "NaN") }
        assertFailsWith<IllegalArgumentException> { BouncerZncCommands.setNetwork("libera", ZncNetworkVariable.FloodBurst, "65536") }
        assertFailsWith<IllegalArgumentException> { BouncerZncCommands.setChan("libera", "#room", ZncChannelVariable.BufferSize, "-1") }
        assertFailsWith<IllegalArgumentException> { BouncerZncCommands.addServer("libera", ZncServerDraft("two hosts")) }
        assertFailsWith<IllegalArgumentException> { BouncerZncCommands.addServer("libera", ZncServerDraft("host", port = 65536)) }
    }

    @Test
    fun secretsAreRedactedFromDraftCommandsAndValidationErrors() {
        val secret = "sensitive-password"
        val server = ZncServerDraft("host", passwordChanged = true, password = secret)
        assertFalse(server.toString().contains(secret))
        assertFalse(ZncNetworkDraft("libera", servers = listOf(server)).toString().contains(secret))
        assertFalse(BouncerZncCommands.addServer("libera", server).toString().contains(secret))
        val error = assertFailsWith<IllegalArgumentException> {
            BouncerZncCommands.addServer("libera", server.copy(password = "$secret\n"))
        }
        assertFalse(error.message.orEmpty().contains(secret))
    }

    @Test
    fun parsesUtf8ByteWidthTables() {
        val lines = table(
            listOf("Index", "Name", "Status", "In config", "Buffer", "Clear", "Modes", "Users"),
            listOf("1", "#café", "Joined", "yes", "50", "yes", "+nt", "2"),
        )
        assertEquals("#café", assertNotNull(BouncerZncParser.channels(lines)).single().name)
    }

    @Test
    fun permissionStrippingNeverSearchesTheChannelBody() {
        val names = listOf("+foo#bar", "+foo&bar", "&local#body", "&+#room", "&+modeless", "@&local", "~&@%+#room", "&&local")
        val channels = assertNotNull(BouncerZncParser.channels(table(
            listOf("Index", "Name", "Status", "In config", "Buffer", "Clear", "Modes", "Users"),
            *names.mapIndexed { index, name -> listOf((index + 1).toString(), name, "Joined", "yes", "50", "yes", "", "1") }.toTypedArray(),
        )))
        assertEquals(listOf("+foo#bar", "+foo&bar", "&local#body", "#room", "+modeless", "&local", "#room", "&local"), channels.map { it.name })
    }

    @Test
    fun utf8ByteWidthTablesPreserveUnicodeAndEmbeddedColumnMarkers() {
        val channels = assertNotNull(BouncerZncParser.channels(table(
            listOf("Index", "Name", "Status", "In config", "Buffer", "Clear", "Modes", "Users"),
            listOf("1", "@#日本語😀|café", "Joined", "yes", "*50", "*yes", "+nt", "2"),
            listOf("2", "+é&日本語", "Detached", "yes", "20", "", "", "0"),
        )))
        assertEquals(listOf("#日本語😀|café", "+é&日本語"), channels.map { it.name })
        val servers = assertNotNull(BouncerZncParser.servers(table(
            listOf("Host", "Port", "SSL", "Password"),
            listOf("irc.例え.test*", "6697", "SSL", "******"),
            listOf("2001:db8::1", "7000", "", ""),
        )))
        assertEquals(listOf("irc.例え.test", "2001:db8::1"), servers.map { it.host })
        assertTrue(servers.first().current)
        val networks = assertNotNull(BouncerZncParser.networks(table(
            listOf("Network", "On IRC", "IRC Server", "IRC User", "Channels"),
            listOf("libera", "Yes", "irc.例え.test", "é😀|nick!ident@例え.test", "1"),
        )))
        assertEquals("é😀|nick!ident@例え.test", networks.single().ircUser)
    }

    private fun table(headers: List<String>, vararg rows: List<String>): List<String> {
        val widths = headers.indices.map { column ->
            maxOf(headers[column].toByteArray(Charsets.UTF_8).size, rows.maxOfOrNull { it[column].toByteArray(Charsets.UTF_8).size } ?: 0)
        }
        val border = widths.joinToString("+", prefix = "+", postfix = "+") { "-".repeat(it + 2) }
        fun line(values: List<String>): String = values.mapIndexed { index, value ->
            " $value${" ".repeat(widths[index] - value.toByteArray(Charsets.UTF_8).size)} "
        }
            .joinToString("|", prefix = "|", postfix = "|")
        return listOf(border, line(headers), border) + rows.map(::line) + border
    }
}
