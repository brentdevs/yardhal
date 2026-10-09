package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.BouncerNetworkDraft
import dev.brentdevs.yardhal.core.data.NetworkMode
import dev.brentdevs.yardhal.core.data.ZncNetworkVariable
import dev.brentdevs.yardhal.core.data.ZncServerDraft
import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BouncerManagementTests {
    private class Harness(scope: CoroutineScope, labeled: Boolean = false) {
        val sent = mutableListOf<String>()
        var rejectChanges = false
        var rejectEnable = false
        var dropChannelStatus = false
        var paused = false
        var disabled = false
        var noControlPanel = false
        var rejectRealName = false
        var snapshotPayload: List<String>? = null
        var snapshotBeforeEnd: List<String> = emptyList()
        var pauseRefreshAfterChange = false
        var rejectRealNameSend = false
        var channelName = "#room"
        var servers: List<ZncServerDraft> = emptyList()
        var floodRateEcho: String? = null
        private var batchNumber = 0
        private val zncSettings = mutableMapOf<String, String>()
        private var attributes = IrcBouncerNetworks.Attributes(host = "h", port = 6697, tls = true, name = "My Network", realname = "Old Name")
        val manager: BouncerManagement

        init {
            manager = BouncerManagement(scope, send = { _, generation, wire ->
                sent.add(wire)
                if (!paused) respond(generation, wire)
            }, timeoutMs = 100)
            manager.start("account", 1, NetworkMode.SOJU)
            manager.connected("account", 1, NetworkMode.SOJU, setOf(IrcBouncerNetworks.CAPABILITY) + if (labeled) setOf("labeled-response", "message-tags", "batch") else emptySet())
        }

        fun feed(line: String, generation: Long = 1): Boolean = manager.receive("account", generation, assertNotNull(IrcMessage.parse(line)))

        private fun respond(generation: Long, wire: String) {
            val message = assertNotNull(IrcMessage.parse(wire))
            val label = message.tag("label")
            when (message.command) {
                "BOUNCER" -> when (message.parameters.firstOrNull()) {
                    "LISTNETWORKS" -> {
                        val batch = "net${++batchNumber}"
                        feed("${if (label == null) "" else "@label=$label "}BATCH +$batch soju.im/bouncer-networks", generation)
                        (snapshotPayload ?: listOf("BOUNCER NETWORK 42 ${attributes.attributeString()}")).forEach { feed("@batch=$batch $it", generation) }
                        snapshotBeforeEnd.forEach { feed(it, generation) }
                        feed("BATCH -$batch", generation)
                    }
                    "ADDNETWORK" -> {
                        attributes = IrcBouncerNetworks.parseAttributes(message.parameters[1]).copy(pass = null)
                        feed("${if (label == null) "" else "@label=$label "}BOUNCER ADDNETWORK 42", generation)
                    }
                    "CHANGENETWORK" -> if (rejectChanges) {
                        feed("${if (label == null) "" else "@label=$label "}FAIL BOUNCER UNKNOWN_ATTRIBUTE CHANGENETWORK pass :secret from server must not appear", generation)
                    } else {
                        attributes = attributes.merged(IrcBouncerNetworks.parseAttributes(message.parameters[2])).copy(pass = null)
                        feed("${if (label == null) "" else "@label=$label "}BOUNCER CHANGENETWORK 42", generation)
                        if (pauseRefreshAfterChange) paused = true
                    }
                    "DELNETWORK" -> feed("${if (label == null) "" else "@label=$label "}BOUNCER DELNETWORK 42", generation)
                }
                "PRIVMSG" -> {
                    val target = message.parameters[0]
                    val body = message.parameters[1]
                    val reply = when {
                        target == "*controlpanel" && noControlPanel -> listOf("No such module controlpanel")
                        body.startsWith("help channel update") -> listOf("channel update <name> [-detached true|false] [-detach-after <duration>]: update a channel")
                        body == "network status" -> listOf("${attributes.name} (ircs://h) [${if (disabled) "disabled" else "connected"}]")
                        body.startsWith("network update") -> {
                            if (rejectEnable) listOf("error: unknown network: confidential server details") else {
                                disabled = body.endsWith("false")
                                listOf("updated network \"${attributes.name}\"")
                            }
                        }
                        body == "channel status" && dropChannelStatus -> emptyList()
                        body == "channel status" -> listOf("#room [joined, detached]")
                        body.startsWith("channel update") -> listOf("updated channel \"#room\"")
                        body == "ListNetworks" -> listOf("No networks")
                        body.startsWith("Help GetNetwork") -> listOf("GetNetwork <variable> [username] [network]")
                        body == "ListServers" -> if (servers.isEmpty()) listOf("You don't have any servers added.") else table(
                            listOf("Host", "Port", "SSL", "Password"),
                            *servers.map { listOf(it.host, it.port.toString(), if (it.tls) "SSL" else "", if (it.password.isNullOrEmpty()) "" else "******") }.toTypedArray(),
                        )
                        body.startsWith("DelServer ") && target == "*status" -> {
                            val parts = body.split(' ')
                            val port = parts[2].toInt()
                            val index = servers.indexOfFirst { it.host.equals(parts[1], true) && (port == 6667 || it.port == port) }
                            if (index < 0) listOf("No such server") else {
                                servers = servers.filterIndexed { serverIndex, _ -> serverIndex != index }
                                listOf("Server removed")
                            }
                        }
                        body == "ListChans" -> table(
                            listOf("Index", "Name", "Status", "In config", "Buffer", "Clear", "Modes", "Users"),
                            listOf("1", channelName, "Joined", "yes", "*100", "*yes", "+nt", "1"),
                        )
                        body.startsWith("GetNetwork ") -> {
                            val variable = body.split(' ')[1]
                            val value = zncSettings[variable] ?: when (variable) {
                                "FloodRate" -> "1.00"
                                "FloodBurst", "JoinDelay" -> "1"
                                "TrustAllCerts", "TrustPKI" -> "false"
                                else -> "value"
                            }
                            listOf("$variable = $value")
                        }
                        body.startsWith("SetNetwork ") -> {
                            val parts = body.split(' ', limit = 5)
                            val variable = parts[1]
                            if (rejectRealNameSend && variable == "RealName") throw IllegalStateException("sensitive runtime details")
                            if (rejectRealName && variable == "RealName") listOf("Access denied!") else {
                                val observed = if (variable == "FloodRate") floodRateEcho ?: parts[4] else parts[4]
                                zncSettings[variable] = observed
                                listOf("$variable = $observed")
                            }
                        }
                        else -> listOf("error: unsupported command")
                    }
                    if (label != null && reply.isNotEmpty()) {
                        val root = "reply${++batchNumber}"
                        val nested = "child$batchNumber"
                        feed("@label=$label BATCH +$root labeled-response", generation)
                        feed("@batch=$root BATCH +$nested draft/example", generation)
                        reply.forEach { feed("@batch=$nested :$target!service@bouncer NOTICE me :$it", generation) }
                        feed("BATCH -$nested", generation)
                        feed("BATCH -$root", generation)
                    } else reply.forEach { line -> feed(":${if (target == "*controlpanel" && noControlPanel) "*status" else target}!service@bouncer NOTICE me :$line", generation) }
                }
                "PING" -> if (!dropChannelStatus) feed(":bouncer PONG bouncer :${message.parameters.last()}", generation)
            }
        }

        private fun table(headers: List<String>, vararg rows: List<String>): List<String> {
            val widths = headers.indices.map { column ->
                maxOf(headers[column].toByteArray(Charsets.UTF_8).size, rows.maxOfOrNull { it[column].toByteArray(Charsets.UTF_8).size } ?: 0)
            }
            val border = widths.joinToString("+", prefix = "+", postfix = "+") { "-".repeat(it + 2) }
            fun cells(values: List<String>): String = values.indices.joinToString("|", prefix = "|", postfix = "|") { index ->
                " ${values[index]}${" ".repeat(widths[index] - values[index].toByteArray(Charsets.UTF_8).size)} "
            }
            return listOf(border, cells(headers), border) + rows.map(::cells) + border
        }
    }

    @Test
    fun serviceTrafficNeverEscapesToOrdinaryChatAndAbsentSettingsStayUnknown() = runTest {
        val harness = Harness(this)
        runCurrent()
        assertTrue(harness.feed(":BouncerServ!service@bouncer PRIVMSG me :unsolicited control message"))
        assertTrue(harness.feed(":*status!status@znc.in PRIVMSG me :control message"))
        assertTrue(harness.feed(":me!u@bouncer PRIVMSG BouncerServ :network update example -enabled false"))
        assertTrue(harness.feed("@+typing=active :me!u@bouncer TAGMSG BouncerServ"))
        assertFalse(harness.feed(":friend!u@host PRIVMSG me :ordinary chat"))
        val result = harness.manager.fetchSojuChannel("account", "#room")
        assertEquals(BouncerOperationStatus.SUCCESS, result.status)
        val channel = assertNotNull(harness.manager.accounts.value["account"]?.sojuChannels?.get("#room"))
        assertEquals(true, channel.detached)
        assertNull(channel.detachAfterSeconds)
        assertNull(channel.relayDetached)
        assertNull(channel.reattachOn)
        assertTrue(harness.sent.any { it == "PRIVMSG BouncerServ :channel status" })
        assertFalse(harness.sent.any { it.contains("channel status '#room'") })
    }

    @Test
    fun nestedLabeledResponseBatchesCorrelateToTheOperation() = runTest {
        val harness = Harness(this, labeled = true)
        runCurrent()
        val result = harness.manager.fetchSojuChannel("account", "#room")
        assertEquals(BouncerOperationStatus.SUCCESS, result.status)
        assertEquals(true, harness.manager.accounts.value["account"]?.sojuChannels?.get("#room")?.detached)
        assertTrue(harness.sent.any { it.startsWith("@label=yardhal-mgmt-") && it.contains("channel status") })
        assertTrue(harness.feed("@label=yardhal-mgmt-old :BouncerServ NOTICE me :late reply"))
    }

    @Test
    fun changedFieldOnlyCommandsReportRejectedAndPartialApplyWithoutLeakingSecrets() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.rejectEnable = true
        val baseline = assertNotNull(harness.manager.accounts.value["account"]?.sojuNetworks?.get("42"))
        val draft = BouncerNetworkDraft.fromAttributes(baseline).copy(enabled = false, password = "new-secret")
        val result = harness.manager.updateSojuNetwork("account", "42", draft)
        assertEquals(BouncerOperationStatus.PARTIAL, result.status)
        assertEquals(1, result.applied)
        assertEquals(2, result.total)
        assertTrue(harness.sent.contains("BOUNCER CHANGENETWORK 42 pass=new-secret"))
        assertTrue(harness.sent.contains("PRIVMSG BouncerServ :network update 'My Network' -enabled false"))
        assertEquals("1", harness.manager.accounts.value["account"]?.sojuNetworks?.get("42")?.unknown?.get("enabled"))
        assertFalse(result.toString().contains("new-secret"))
        assertFalse(result.toString().contains("secret from server"))
        assertFalse(draft.toString().contains("new-secret"))
    }

    @Test
    fun renameAndDisableUsesTheAcknowledgedNewNameAndEscapesRealnames() = runTest {
        val harness = Harness(this)
        runCurrent()
        val baseline = assertNotNull(harness.manager.accounts.value["account"]?.sojuNetworks?.get("42"))
        val draft = BouncerNetworkDraft.fromAttributes(baseline).copy(name = "New Network", realname = "Real; Name", enabled = false)
        val result = harness.manager.updateSojuNetwork("account", "42", draft)
        assertEquals(BouncerOperationStatus.SUCCESS, result.status)
        assertTrue(harness.sent.contains("BOUNCER CHANGENETWORK 42 name=New\\sNetwork;realname=Real\\:\\sName"))
        assertTrue(harness.sent.contains("PRIVMSG BouncerServ :network update 'New Network' -enabled false"))
        val current = assertNotNull(harness.manager.accounts.value["account"]?.sojuNetworks?.get("42"))
        assertEquals("New Network", current.name)
        assertEquals("Real; Name", current.realname)
        assertEquals("0", current.unknown["enabled"])
    }

    @Test
    fun aNativeRejectedChangeIsNotSuccessfulAndDoesNotExposeTheServerDescription() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.rejectChanges = true
        val baseline = assertNotNull(harness.manager.accounts.value["account"]?.sojuNetworks?.get("42"))
        val result = harness.manager.updateSojuNetwork("account", "42", BouncerNetworkDraft.fromAttributes(baseline).copy(password = "new-secret"))
        assertEquals(BouncerOperationStatus.ERROR, result.status)
        assertEquals(0, result.applied)
        assertFalse(result.toString().contains("secret from server"))
        assertFalse(result.toString().contains("new-secret"))
    }

    @Test
    fun foreignLabelsCannotCompleteTheCurrentRequest() = runTest {
        val harness = Harness(this, labeled = true)
        runCurrent()
        harness.paused = true
        val operation = async { harness.manager.fetchSojuChannel("account", "#room") }
        runCurrent()
        val label = assertNotNull(IrcMessage.parse(harness.sent.last())?.tag("label"))
        assertTrue(harness.feed("@label=$label :me!u@bouncer PRIVMSG BouncerServ :#room [joined]"))
        assertTrue(harness.feed("@label=$label :me!u@bouncer NOTICE BouncerServ :#room [joined]"))
        assertFalse(operation.isCompleted)
        assertTrue(harness.feed("@label=yardhal-mgmt-stale :BouncerServ NOTICE me :#room [joined]"))
        assertFalse(operation.isCompleted)
        harness.manager.capabilitiesChanged("account", 1, setOf(IrcBouncerNetworks.CAPABILITY))
        assertFalse(operation.isCompleted)
        assertTrue(harness.feed("@label=$label :BouncerServ NOTICE me :#room [joined, detached]"))
        assertEquals(BouncerOperationStatus.SUCCESS, operation.await().status)
    }

    @Test
    fun capabilityChangesAreGenerationSafeAndDoNotSendOrInventServiceSupport() = runTest {
        val harness = Harness(this)
        runCurrent()
        val sentBefore = harness.sent.size
        harness.manager.capabilitiesChanged("account", 1, emptySet())
        assertEquals(BouncerServiceAvailability.UNSUPPORTED, harness.manager.accounts.value["account"]?.sojuService)
        harness.manager.capabilitiesChanged("account", 0, setOf(IrcBouncerNetworks.CAPABILITY))
        assertEquals(emptySet(), harness.manager.accounts.value["account"]?.capabilities)
        harness.manager.capabilitiesChanged("account", 1, setOf(IrcBouncerNetworks.CAPABILITY))
        assertEquals(BouncerServiceAvailability.UNKNOWN, harness.manager.accounts.value["account"]?.sojuService)
        assertEquals(sentBefore, harness.sent.size)
    }

    @Test
    fun historyBouncerFramesDoNotOverwriteCurrentDiscovery() = runTest {
        val harness = Harness(this)
        runCurrent()
        assertFalse(harness.feed("BATCH +history chathistory #room"))
        assertTrue(harness.feed("@batch=history BOUNCER NETWORK 42 name=historical"))
        assertFalse(harness.feed("BATCH -history"))
        assertEquals("My Network", harness.manager.accounts.value["account"]?.sojuNetworks?.get("42")?.name)
    }

    @Test
    fun disconnectAndStaleGenerationResolvePendingWorkAndIgnoreLateState() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.paused = true
        val operation = async { harness.manager.fetchSojuChannel("account", "#room") }
        runCurrent()
        harness.manager.disconnected("account", 1)
        val result = operation.await()
        assertEquals(BouncerOperationStatus.ERROR, result.status)
        harness.manager.start("account", 2, NetworkMode.SOJU)
        assertTrue(harness.feed("BOUNCER NETWORK 42 name=stale", generation = 1))
        assertEquals("My Network", harness.manager.accounts.value["account"]?.sojuNetworks?.get("42")?.name)
        assertTrue(harness.feed(":BouncerServ NOTICE me :late control", generation = 1))
        harness.manager.remove("account")
        assertTrue(harness.feed(":BouncerServ NOTICE me :removed-account control", generation = 1))
    }

    @Test
    fun timeoutCannotTurnSendingOrLateUnlabeledRepliesIntoSuccess() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.dropChannelStatus = true
        val operation = async { harness.manager.fetchSojuChannel("account", "#room") }
        advanceUntilIdle()
        assertEquals(BouncerOperationStatus.ERROR, operation.await().status)
        assertTrue(harness.feed(":BouncerServ NOTICE me :#room [joined]"))
        val second = harness.manager.fetchSojuChannel("account", "#room")
        assertEquals(BouncerOperationStatus.ERROR, second.status)
        assertNull(harness.manager.accounts.value["account"]?.sojuChannels?.get("#room"))
    }

    @Test
    fun missingControlpanelIsObservedFromRealStatusReplyNotFromSending() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.noControlPanel = true
        harness.manager.start("account", 2, NetworkMode.ZNC, boundNetwork = "alpha")
        harness.manager.connected("account", 2, NetworkMode.ZNC, emptySet(), boundNetwork = "alpha")
        runCurrent()
        val state = assertNotNull(harness.manager.accounts.value["account"])
        assertEquals(BouncerServiceAvailability.AVAILABLE, state.zncStatus)
        assertEquals(BouncerServiceAvailability.UNAVAILABLE, state.zncControlPanel)
    }

    @Test
    fun historicalAndUnknownNativeRepliesCannotAcknowledgeOrMutateManagement() = runTest {
        val harness = Harness(this)
        runCurrent()
        val before = assertNotNull(harness.manager.accounts.value["account"]?.sojuNetworks?.get("42"))
        harness.paused = true
        val operation = async { harness.manager.updateSojuNetwork("account", "42", BouncerNetworkDraft.fromAttributes(before).copy(realname = "Changed")) }
        runCurrent()
        assertTrue(harness.feed("@draft/chathistory-context=42 BOUNCER NETWORK 42 name=historical"))
        assertTrue(harness.feed("@draft/chathistory-context=42 BOUNCER CHANGENETWORK 42"))
        assertTrue(harness.feed("@batch=missing BOUNCER NETWORK 42 *"))
        assertTrue(harness.feed("@batch=missing BOUNCER CHANGENETWORK 42"))
        assertFalse(harness.feed("BATCH +history znc.in/playback"))
        assertTrue(harness.feed("@batch=history BATCH +nested soju.im/bouncer-networks"))
        assertTrue(harness.feed("@batch=nested BOUNCER NETWORK 42 name=nested-history"))
        assertTrue(harness.feed("@batch=nested BOUNCER CHANGENETWORK 42"))
        assertTrue(harness.feed("BATCH -nested"))
        assertFalse(harness.feed("BATCH -history"))
        assertFalse(operation.isCompleted)
        assertEquals(before, harness.manager.accounts.value["account"]?.sojuNetworks?.get("42"))
        harness.paused = false
        assertTrue(harness.feed("BOUNCER CHANGENETWORK 42"))
        assertEquals(BouncerOperationStatus.SUCCESS, operation.await().status)
    }

    @Test
    fun malformedCompleteSnapshotDoesNotPruneOrPartiallyReplaceCurrentRows() = runTest {
        val harness = Harness(this)
        runCurrent()
        val before = harness.manager.accounts.value["account"]?.sojuNetworks
        harness.snapshotPayload = listOf("BOUNCER NETWORK 42 name=Partial", "BOUNCER NETWORK 43")
        assertEquals(BouncerOperationStatus.ERROR, harness.manager.refresh("account").status)
        assertEquals(before, harness.manager.accounts.value["account"]?.sojuNetworks)
    }

    @Test
    fun bindingChangesClearDetailsAndRejectHeldChannelAndNetworkDrafts() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.manager.start("account", 2, NetworkMode.ZNC, username = "user", boundNetwork = "alpha")
        harness.manager.connected("account", 2, NetworkMode.ZNC, emptySet(), username = "user", boundNetwork = "alpha")
        runCurrent()
        assertEquals(BouncerOperationStatus.SUCCESS, harness.manager.fetchZncChannel("account", "#room").status)
        assertEquals(BouncerOperationStatus.SUCCESS, harness.manager.fetchZncNetwork("account", "alpha").status)
        val oldChannel = assertNotNull(harness.manager.accounts.value["account"]?.zncChannels?.get("#room"))
        val oldNetwork = assertNotNull(harness.manager.accounts.value["account"]?.zncNetworkDetails?.get("alpha"))
        harness.manager.start("account", 3, NetworkMode.ZNC, username = "other", boundNetwork = "beta")
        assertEquals(emptyMap(), harness.manager.accounts.value["account"]?.zncChannels)
        assertEquals(emptyMap(), harness.manager.accounts.value["account"]?.zncNetworkDetails)
        harness.manager.connected("account", 3, NetworkMode.ZNC, emptySet(), username = "other", boundNetwork = "beta")
        runCurrent()
        assertEquals(BouncerOperationStatus.SUCCESS, harness.manager.fetchZncChannel("account", "#room").status)
        assertEquals(oldChannel, harness.manager.accounts.value["account"]?.zncChannels?.get("#room"))
        val start = harness.sent.size
        assertEquals(BouncerOperationStatus.ERROR, harness.manager.applyZncChannel("account", oldChannel, oldChannel.copy(bufferSize = 200)).status)
        assertEquals(BouncerOperationStatus.ERROR, harness.manager.applyZncNetwork("account", oldNetwork, oldNetwork.copy(settings = oldNetwork.settings + (dev.brentdevs.yardhal.core.data.ZncNetworkVariable.Nick to "newNick"))).status)
        assertFalse(harness.sent.drop(start).any { it.contains(":SetChan ") || it.contains(":SetNetwork ") || it.contains(":AddServer ") || it.contains(":DelServer ") })
    }

    @Test
    fun reconnectRequiresFreshSojuChannelBaselineEvenWhenValuesAreEqual() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.manager.fetchSojuChannel("account", "#room")
        val old = assertNotNull(harness.manager.accounts.value["account"]?.sojuChannels?.get("#room"))
        harness.manager.start("account", 2, NetworkMode.SOJU)
        assertEquals(emptyMap(), harness.manager.accounts.value["account"]?.sojuChannels)
        harness.manager.connected("account", 2, NetworkMode.SOJU, setOf(IrcBouncerNetworks.CAPABILITY))
        runCurrent()
        harness.manager.fetchSojuChannel("account", "#room")
        assertEquals(old, harness.manager.accounts.value["account"]?.sojuChannels?.get("#room"))
        val start = harness.sent.size
        assertEquals(BouncerOperationStatus.ERROR, harness.manager.updateSojuChannel("account", old, old.copy(detached = false)).status)
        assertFalse(harness.sent.drop(start).any { it.contains(":channel update ") })
    }

    @Test
    fun accessDeniedPreservesAcknowledgedSettingsAndReportsPartialApply() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.manager.start("account", 2, NetworkMode.ZNC, boundNetwork = "alpha")
        harness.manager.connected("account", 2, NetworkMode.ZNC, emptySet(), boundNetwork = "alpha")
        runCurrent()
        assertEquals(BouncerOperationStatus.SUCCESS, harness.manager.fetchZncNetwork("account", "alpha").status)
        val baseline = assertNotNull(harness.manager.accounts.value["account"]?.zncNetworkDetails?.get("alpha"))
        val nick = dev.brentdevs.yardhal.core.data.ZncNetworkVariable.Nick
        val altnick = dev.brentdevs.yardhal.core.data.ZncNetworkVariable.Altnick
        val realname = dev.brentdevs.yardhal.core.data.ZncNetworkVariable.RealName
        harness.rejectRealName = true
        val draft = baseline.copy(settings = baseline.settings + mapOf(nick to "newNick", altnick to "newAlt", realname to "Restricted"))
        val result = harness.manager.applyZncNetwork("account", baseline, draft)
        assertEquals(BouncerOperationStatus.PARTIAL, result.status)
        assertEquals(2, result.applied)
        assertEquals(3, result.total)
        val refreshed = assertNotNull(harness.manager.accounts.value["account"]?.zncNetworkDetails?.get("alpha"))
        assertEquals("newNick", refreshed.settings[nick])
        assertEquals("newAlt", refreshed.settings[altnick])
        assertEquals(baseline.settings[realname], refreshed.settings[realname])
        assertEquals(BouncerServiceAvailability.AVAILABLE, harness.manager.accounts.value["account"]?.zncControlPanel)
    }

    @Test
    fun timedOutAutomaticRefreshRecoversOnlyAfterAnObservedReplyFence() = runTest {
        val harness = Harness(this)
        harness.paused = true
        advanceUntilIdle()
        assertEquals(BouncerOperationStatus.ERROR, harness.manager.accounts.value["account"]?.operation?.status)
        val operation = async { harness.manager.removeSojuNetwork("account", "42") }
        runCurrent()
        val fence = assertNotNull(IrcMessage.parse(harness.sent.last())).parameters.last()
        assertTrue(harness.sent.last().startsWith("PING :yardhal-mgmt-"))
        assertFalse(harness.sent.any { it == "BOUNCER DELNETWORK 42" })
        assertTrue(harness.feed("BOUNCER DELNETWORK 42"))
        assertTrue(harness.feed("BATCH +late soju.im/bouncer-networks"))
        assertTrue(harness.feed("@batch=late BOUNCER NETWORK 99 name=stale"))
        assertTrue(harness.feed("BATCH -late"))
        assertTrue(harness.feed(":bouncer PONG bouncer :yardhal-mgmt-wrong"))
        assertFalse(operation.isCompleted)
        assertNull(harness.manager.accounts.value["account"]?.sojuNetworks?.get("99"))
        harness.paused = false
        assertTrue(harness.feed(":bouncer PONG bouncer :$fence"))
        assertEquals(BouncerOperationStatus.SUCCESS, operation.await().status)
        assertTrue(harness.sent.contains("BOUNCER DELNETWORK 42"))
    }

    @Test
    fun timedOutLabeledRefreshDoesNotTrustUnlabeledOrOldLabeledMutationReplies() = runTest {
        val harness = Harness(this, labeled = true)
        harness.paused = true
        advanceUntilIdle()
        val oldLabel = assertNotNull(IrcMessage.parse(harness.sent.first())?.tag("label"))
        val operation = async { harness.manager.removeSojuNetwork("account", "42") }
        runCurrent()
        val label = assertNotNull(IrcMessage.parse(harness.sent.last())?.tag("label"))
        assertTrue(harness.feed("BOUNCER DELNETWORK 42"))
        assertTrue(harness.feed("@label=$oldLabel BOUNCER DELNETWORK 42"))
        assertFalse(operation.isCompleted)
        harness.paused = false
        assertTrue(harness.feed("@label=$label BOUNCER DELNETWORK 42"))
        assertEquals(BouncerOperationStatus.SUCCESS, operation.await().status)
        assertFalse(harness.sent.any { it.startsWith("PING ") })
    }

    @Test
    fun snapshotContainsOnlyCorrelatedRowsAndReconcilesConcurrentLiveDeltas() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.feed("BOUNCER NETWORK 43 host=preserved;port=7000;tls=0;nickname=before;name=Existing")
        harness.snapshotPayload = listOf(
            "BOUNCER NETWORK 42 host=snapshot;port=6697;tls=1;name=Old;realname=Snapshot",
            "BOUNCER NETWORK 44 host=deleted;name=Delete",
        )
        harness.snapshotBeforeEnd = listOf(
            "BOUNCER NETWORK 42 name=Renamed",
            "BOUNCER NETWORK 43 nickname=after",
            "BOUNCER NETWORK 44 *",
            "BOUNCER NETWORK 45 host=new;name=New",
        )
        assertEquals(BouncerOperationStatus.SUCCESS, harness.manager.refresh("account").status)
        val networks = assertNotNull(harness.manager.accounts.value["account"]?.sojuNetworks)
        assertEquals(setOf("42", "43", "45"), networks.keys)
        assertEquals("Renamed", networks["42"]?.name)
        assertEquals("snapshot", networks["42"]?.host)
        assertEquals("Snapshot", networks["42"]?.realname)
        assertEquals("preserved", networks["43"]?.host)
        assertEquals(7000, networks["43"]?.port)
        assertEquals("after", networks["43"]?.nickname)
    }

    @Test
    fun liveDeleteThenPartialReaddDoesNotResurrectSnapshotFields() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.snapshotPayload = listOf("BOUNCER NETWORK 42 host=old;port=6697;name=Old")
        harness.snapshotBeforeEnd = listOf("BOUNCER NETWORK 42 *", "BOUNCER NETWORK 42 name=Recreated")
        assertEquals(BouncerOperationStatus.SUCCESS, harness.manager.refresh("account").status)
        val network = assertNotNull(harness.manager.accounts.value["account"]?.sojuNetworks?.get("42"))
        assertEquals("Recreated", network.name)
        assertNull(network.host)
        assertNull(network.port)
    }

    @Test
    fun unexpectedRuntimeFailureDuringBuildPublishesErrorWithoutLeakingDetails() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.manager.start("account", 2, NetworkMode.ZNC, boundNetwork = "alpha")
        harness.manager.connected("account", 2, NetworkMode.ZNC, emptySet(), boundNetwork = "alpha")
        runCurrent()
        harness.manager.fetchZncNetwork("account", "alpha")
        val baseline = assertNotNull(harness.manager.accounts.value["account"]?.zncNetworkDetails?.get("alpha"))
        val settings = object : Map<ZncNetworkVariable, String> by baseline.settings {
            override fun get(key: ZncNetworkVariable): String = throw IllegalStateException("sensitive runtime details")
        }
        val outcome = harness.manager.applyZncNetwork("account", baseline, baseline.copy(settings = settings))
        assertEquals(BouncerOperationStatus.ERROR, outcome.status)
        assertEquals(outcome, harness.manager.accounts.value["account"]?.operation)
        assertFalse(outcome.toString().contains("sensitive runtime details"))
    }

    @Test
    fun runtimeSendFailurePreservesEarlierAcknowledgedChangesAsPartial() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.manager.start("account", 2, NetworkMode.ZNC, boundNetwork = "alpha")
        harness.manager.connected("account", 2, NetworkMode.ZNC, emptySet(), boundNetwork = "alpha")
        runCurrent()
        harness.manager.fetchZncNetwork("account", "alpha")
        val baseline = assertNotNull(harness.manager.accounts.value["account"]?.zncNetworkDetails?.get("alpha"))
        harness.rejectRealNameSend = true
        val outcome = harness.manager.applyZncNetwork("account", baseline, baseline.copy(settings = baseline.settings + mapOf(ZncNetworkVariable.Nick to "newNick", ZncNetworkVariable.RealName to "newName")))
        assertEquals(BouncerOperationStatus.PARTIAL, outcome.status)
        assertEquals(1, outcome.applied)
        assertEquals(2, outcome.total)
        assertEquals("newNick", harness.manager.accounts.value["account"]?.zncNetworkDetails?.get("alpha")?.settings?.get(ZncNetworkVariable.Nick))
        assertFalse(outcome.toString().contains("sensitive runtime details"))
    }

    @Test
    fun callerCancellationAfterSendDoesNotCancelTheOwnedMutationOrPoisonLaterWork() = runTest {
        val harness = Harness(this)
        runCurrent()
        val baseline = assertNotNull(harness.manager.accounts.value["account"]?.sojuNetworks?.get("42"))
        harness.paused = true
        val operation = async { harness.manager.updateSojuNetwork("account", "42", BouncerNetworkDraft.fromAttributes(baseline).copy(realname = "New Name")) }
        runCurrent()
        assertTrue(harness.sent.any { it.contains("CHANGENETWORK 42") })
        operation.cancelAndJoin()
        assertEquals(BouncerOperationStatus.RUNNING, harness.manager.accounts.value["account"]?.operation?.status)
        harness.paused = false
        assertTrue(harness.feed("BOUNCER CHANGENETWORK 42"))
        runCurrent()
        assertEquals(BouncerOperationStatus.SUCCESS, harness.manager.accounts.value["account"]?.operation?.status)
        assertEquals(BouncerOperationStatus.SUCCESS, harness.manager.fetchSojuChannel("account", "#room").status)
    }

    @Test
    fun ownerCancellationDuringRefreshPropagatesAndPublishesPartialInsteadOfSuccess() = runTest {
        val owner = SupervisorJob()
        val harness = Harness(CoroutineScope(coroutineContext + owner))
        runCurrent()
        val baseline = assertNotNull(harness.manager.accounts.value["account"]?.sojuNetworks?.get("42"))
        harness.pauseRefreshAfterChange = true
        val operation = async { harness.manager.updateSojuNetwork("account", "42", BouncerNetworkDraft.fromAttributes(baseline).copy(realname = "New Name")) }
        runCurrent()
        owner.cancelAndJoin()
        assertFailsWith<CancellationException> { operation.await() }
        val outcome = assertNotNull(harness.manager.accounts.value["account"]?.operation)
        assertEquals(BouncerOperationStatus.PARTIAL, outcome.status)
        assertEquals(1, outcome.applied)
        assertNotNull(outcome.refreshError)
    }

    @Test
    fun unicodeChannelNamesReachConsumerCachesWithoutTruncation() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.manager.start("account", 2, NetworkMode.ZNC, boundNetwork = "alpha")
        harness.manager.connected("account", 2, NetworkMode.ZNC, emptySet(), boundNetwork = "alpha")
        runCurrent()
        for (name in listOf("+foo#bar", "+foo&bar", "#日本語😀|café", "&local")) {
            harness.channelName = name
            assertEquals(BouncerOperationStatus.SUCCESS, harness.manager.fetchZncChannel("account", name).status)
            assertEquals(name, harness.manager.accounts.value["account"]?.zncChannels?.get(name)?.name)
        }
    }

    @Test
    fun boundStatusServerDeletionAvoidsBrokenLegacyControlpanelGrammar() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.servers = listOf(ZncServerDraft("irc.例え.test", port = 7000, password = "masked"), ZncServerDraft("irc.例え.test", port = 7001, tls = false, password = "masked"))
        harness.manager.start("account", 2, NetworkMode.ZNC, boundNetwork = "alpha")
        harness.manager.connected("account", 2, NetworkMode.ZNC, emptySet(), boundNetwork = "alpha")
        runCurrent()
        harness.manager.fetchZncNetwork("account", "alpha")
        val baseline = assertNotNull(harness.manager.accounts.value["account"]?.zncNetworkDetails?.get("alpha"))
        assertEquals(BouncerOperationStatus.SUCCESS, harness.manager.applyZncNetwork("account", baseline, baseline.copy(servers = baseline.servers.take(1))).status)
        assertTrue(harness.sent.contains("PRIVMSG *status :DelServer irc.例え.test 7001"))
        assertFalse(harness.sent.any { it.startsWith("PRIVMSG *controlpanel :DelServer ") })
        assertEquals(listOf(7000), harness.manager.accounts.value["account"]?.zncNetworkDetails?.get("alpha")?.servers?.map { it.port })
    }

    @Test
    fun roundedFloodRateEchoEstablishesApplySuccessAndObservedState() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.manager.start("account", 2, NetworkMode.ZNC, boundNetwork = "alpha")
        harness.manager.connected("account", 2, NetworkMode.ZNC, emptySet(), boundNetwork = "alpha")
        runCurrent()
        harness.manager.fetchZncNetwork("account", "alpha")
        val baseline = assertNotNull(harness.manager.accounts.value["account"]?.zncNetworkDetails?.get("alpha"))
        harness.floodRateEcho = "0.12"
        val result = harness.manager.applyZncNetwork("account", baseline, baseline.copy(settings = baseline.settings + (ZncNetworkVariable.FloodRate to "0.125")))
        assertEquals(BouncerOperationStatus.SUCCESS, result.status)
        assertEquals(1, result.applied)
        assertEquals("0.12", harness.manager.accounts.value["account"]?.zncNetworkDetails?.get("alpha")?.settings?.get(ZncNetworkVariable.FloodRate))
    }

    @Test
    fun unknownUnixAddressCanRemainUnchangedWhileOtherNativeSettingsAreEdited() = runTest {
        val harness = Harness(this)
        harness.snapshotPayload = listOf("BOUNCER NETWORK 42 name=Unix;nickname=old")
        runCurrent()
        val baseline = assertNotNull(harness.manager.accounts.value["account"]?.sojuNetworks?.get("42"))
        assertNull(baseline.host)
        assertNull(baseline.unknown["addr"])
        harness.snapshotPayload = null
        val result = harness.manager.updateSojuNetwork("account", "42", BouncerNetworkDraft.fromAttributes(baseline).copy(nick = "new"))
        assertEquals(BouncerOperationStatus.SUCCESS, result.status)
        assertTrue(harness.sent.contains("BOUNCER CHANGENETWORK 42 nickname=new"))
    }

    @Test
    fun clearingAKnownAddressDoesNotSendAnEndpointMutation() = runTest {
        val harness = Harness(this)
        runCurrent()
        val baseline = assertNotNull(harness.manager.accounts.value["account"]?.sojuNetworks?.get("42"))
        val start = harness.sent.size
        val result = harness.manager.updateSojuNetwork("account", "42", BouncerNetworkDraft.fromAttributes(baseline).copy(addr = "", nick = "new"))
        assertEquals(BouncerOperationStatus.ERROR, result.status)
        assertFalse(harness.sent.drop(start).any { it.contains("CHANGENETWORK") })
    }

    @Test
    fun unnamedDisabledNetworkFallbackNameNeverContainsAddressUserinfo() = runTest {
        val harness = Harness(this)
        runCurrent()
        val result = harness.manager.addSojuNetwork("account", BouncerNetworkDraft(addr = "ircs://user:address-secret@irc.example", enabled = false))
        assertEquals(BouncerOperationStatus.SUCCESS, result.status)
        val creation = harness.sent.first { it.startsWith("BOUNCER ADDNETWORK ") }
        val attributes = IrcBouncerNetworks.parseAttributes(assertNotNull(IrcMessage.parse(creation)).parameters[1])
        assertEquals("ircs://irc.example", attributes.name)
        assertFalse(harness.sent.any { "address-secret" in it || "user:" in it })
    }

    @Test
    fun nestedLabeledSnapshotWaitsForItsCorrelatedRootAndReconcilesLaterLiveChanges() = runTest {
        val harness = Harness(this, labeled = true)
        runCurrent()
        harness.paused = true
        val operation = async { harness.manager.refresh("account") }
        runCurrent()
        val label = assertNotNull(IrcMessage.parse(harness.sent.last())?.tag("label"))
        assertTrue(harness.feed("@label=$label BATCH +root labeled-response"))
        assertTrue(harness.feed("@batch=root BATCH +snapshot soju.im/bouncer-networks"))
        assertTrue(harness.feed("@batch=snapshot BOUNCER NETWORK 42 host=current;name=Snapshot"))
        assertTrue(harness.feed("BATCH -snapshot"))
        assertFalse(operation.isCompleted)
        assertTrue(harness.feed("BOUNCER NETWORK 42 nickname=live"))
        assertTrue(harness.feed("@label=yardhal-mgmt-foreign BATCH +foreign soju.im/bouncer-networks"))
        assertTrue(harness.feed("@batch=foreign BOUNCER NETWORK 99 name=Foreign"))
        assertTrue(harness.feed("BATCH -foreign"))
        assertFalse(operation.isCompleted)
        harness.paused = false
        assertTrue(harness.feed("BATCH -root"))
        assertEquals(BouncerOperationStatus.SUCCESS, operation.await().status)
        val networks = assertNotNull(harness.manager.accounts.value["account"]?.sojuNetworks)
        assertEquals(setOf("42"), networks.keys)
        assertEquals("current", networks["42"]?.host)
        assertEquals("live", networks["42"]?.nickname)
    }
}
