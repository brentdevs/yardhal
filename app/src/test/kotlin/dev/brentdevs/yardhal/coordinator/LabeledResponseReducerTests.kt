package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.client.IrcEvent
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabeledResponseReducerTests {
    private val context = InboundContext(nowMs = 1000L)

    private fun negotiated(vararg caps: String): PerNetworkState {
        val state = PerNetworkState("net", "me")
        state.apply(IrcEvent.CapabilitiesNegotiated(caps.toSet()), context)
        return state
    }

    private fun PerNetworkState.feed(line: String, ctx: InboundContext = context): List<InboundEffect> {
        val message = assertNotNull(IrcMessage.parse(line))
        return apply(IrcEvent.MessageReceived(message), ctx)
    }

    private fun List<InboundEffect>.appended(): List<InboundEffect.AppendMessage> =
        filterIsInstance<InboundEffect.AppendMessage>()

    private fun pendingMessage(text: String, echoLabel: String?): ChatMessage = ChatMessage(
        localId = 1,
        sender = "me",
        kind = MessageKind.PRIVMSG,
        text = text,
        timestampMs = 500,
        sentByUs = true,
        highlightsMe = false,
        msgid = null,
        pendingEcho = true,
        echoLabel = echoLabel,
    )

    @Test
    fun labelsAreIssuedOnlyWhenCapabilityAcked() {
        val plain = negotiated("batch", "echo-message")
        assertNull(plain.issueLabel(plain.server, LabeledCommand.RAW, 1000L))

        val labeled = negotiated("batch", "labeled-response")
        val channel = labeled.channelRef("#c")
        val first = labeled.issueLabel(channel, LabeledCommand.MODE, 1000L)
        val second = labeled.issueLabel(channel, LabeledCommand.MODE, 1000L)
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first != second)
        assertEquals(channel, labeled.pendingLabel(first)?.origin)
        assertEquals(LabeledCommand.MODE, labeled.pendingLabel(second)?.command)
    }

    @Test
    fun unappliedLabelledModeReplyIsRoutedToOriginBuffer() {
        val state = negotiated("batch", "labeled-response")
        val channel = state.channelRef("#c")
        val label = assertNotNull(state.issueLabel(channel, LabeledCommand.MODE, 1000L))
        val originOnly = InboundContext(nowMs = 1000L, hasBuffer = { it == channel.storageKey })

        val effects = state.feed("@label=$label :srv 324 me #untracked +nt", originOnly)

        val line = effects.appended().single()
        assertEquals(channel, line.ref)
        assertTrue(effects.filterIsInstance<InboundEffect.SetModes>().isEmpty())
        assertTrue(state.channelRefs().isEmpty())
        assertNull(state.pendingLabel(label))
    }

    @Test
    fun labelledModeReplyUpdatesTargetModesWithoutTranscript() {
        val state = negotiated("batch", "labeled-response")
        val channel = state.channelRef("#c")
        val label = assertNotNull(state.issueLabel(state.directRef("alice"), LabeledCommand.MODE, 1000L))

        val effects = state.feed("@label=$label :srv 324 me #c +nt")
        val modes = effects.filterIsInstance<InboundEffect.SetModes>().single()

        assertEquals(channel, modes.ref)
        assertEquals(mapOf("n" to emptyList(), "t" to emptyList()), modes.modes)
        assertTrue(modes.complete)
        assertTrue(effects.appended().isEmpty())
        assertNull(state.pendingLabel(label))
    }

    @Test
    fun malformedLabelledModeReplyKeepsOriginRoutingAndPriorSnapshot() {
        val state = negotiated("batch", "labeled-response")
        val channel = state.channelRef("#c")
        val origin = state.directRef("alice")
        val initial = state.feed(":srv 324 me #c +nt").filterIsInstance<InboundEffect.SetModes>().single()
        for (payload in listOf("#c +ik", "alice +i", "#c", "")) {
            val label = assertNotNull(state.issueLabel(origin, LabeledCommand.MODE, 1000L))
            val effects = state.feed("@label=$label :srv 324 me $payload")
            assertEquals(origin, effects.appended().single().ref, payload)
            assertTrue(effects.filterIsInstance<InboundEffect.SetModes>().isEmpty(), payload)
            assertEquals(initial.modes, checkNotNull(state.channel(channel.storageKey)).modeSnapshot(), payload)
            assertNull(state.pendingLabel(label), payload)
        }
    }

    @Test
    fun unappliedModeReplyInALabelledBatchKeepsOriginUntilBatchEnd() {
        val state = negotiated("batch", "labeled-response")
        val origin = state.directRef("alice")
        val label = assertNotNull(state.issueLabel(origin, LabeledCommand.MODE, 1000L))
        val originOnly = InboundContext(nowMs = 1000L, hasBuffer = { it == origin.storageKey })
        state.feed("@label=$label :srv BATCH +m1 labeled-response", originOnly)
        val effects = state.feed("@batch=m1 :srv 324 me #untracked +nt", originOnly)
        assertEquals(origin, effects.appended().single().ref)
        assertTrue(effects.filterIsInstance<InboundEffect.SetModes>().isEmpty())
        assertNotNull(state.pendingLabel(label))
        state.feed(":srv BATCH -m1", originOnly)
        assertNull(state.pendingLabel(label))
    }


    @Test
    fun labelledServerNoticeIsRoutedToOriginBuffer() {
        val state = negotiated("batch", "labeled-response")
        val channel = state.channelRef("#c")
        val label = assertNotNull(state.issueLabel(channel, LabeledCommand.RAW, 1000L))

        val effects = state.feed("@label=$label :srv.example NOTICE me :Server says hi")

        assertEquals(channel, effects.appended().single().ref)
    }

    @Test
    fun unappliedLabelledModeReplyFallsBackToServerWhenOriginBufferClosed() {
        val state = negotiated("batch", "labeled-response")
        val channel = state.channelRef("#c")
        val label = assertNotNull(state.issueLabel(channel, LabeledCommand.MODE, 1000L))

        val effects = state.feed(
            "@label=$label :srv 324 me #c +nt",
            InboundContext(nowMs = 1000L, hasBuffer = { false }),
        )

        assertEquals(state.server, effects.appended().single().ref)
        assertTrue(effects.filterIsInstance<InboundEffect.SetModes>().isEmpty())
        assertTrue(state.channelRefs().isEmpty())
    }

    @Test
    fun labelledBatchIsRoutedToOriginAndClearedAtBatchEnd() {
        val state = negotiated("batch", "labeled-response")
        val query = state.directRef("alice")
        val label = assertNotNull(state.issueLabel(query, LabeledCommand.WHOIS, 1000L))

        assertTrue(state.feed("@label=$label :srv BATCH +w1 labeled-response").isEmpty())
        val error = state.feed("@batch=w1 :srv 401 me ghost :No such nick").appended().single()
        val unknown = state.feed("@batch=w1 :srv 999 me ghost :Odd").appended().single()
        assertEquals(query, error.ref)
        assertEquals(query, unknown.ref)
        assertNotNull(state.pendingLabel(label))

        state.feed(":srv BATCH -w1")

        assertNull(state.pendingLabel(label))
        assertEquals(state.server, state.feed(":srv 999 me ghost :Odd").appended().single().ref)
    }

    @Test
    fun labelledWhoisBatchStillAssemblesWhoisInfo() {
        val state = negotiated("batch", "labeled-response")
        state.whoisExpected = true
        val label = assertNotNull(state.issueLabel(state.channelRef("#c"), LabeledCommand.WHOIS, 1000L))

        state.feed("@label=$label :srv BATCH +w2 labeled-response")
        state.feed("@batch=w2 :srv 311 me alice ident host * :Alice Real")
        state.feed("@batch=w2 :srv 330 me alice alice_acct :is logged in as")
        val completed = state.feed("@batch=w2 :srv 318 me alice :End of /WHOIS list")
            .filterIsInstance<InboundEffect.WhoisCompleted>().single()
        state.feed(":srv BATCH -w2")

        assertEquals("alice", completed.info.nick)
        assertEquals("ident", completed.info.user)
        assertEquals("alice_acct", completed.info.account)
        assertFalse(state.whoisExpected)
        assertNull(state.pendingLabel(label))
    }

    @Test
    fun ackClearsPendingLabelWithoutOutput() {
        val state = negotiated("batch", "labeled-response")
        val label = assertNotNull(state.issueLabel(state.channelRef("#c"), LabeledCommand.MONITOR, 1000L))

        val effects = state.feed("@label=$label :srv ACK")

        assertTrue(effects.isEmpty())
        assertNull(state.pendingLabel(label))
    }

    @Test
    fun labelledEchoReconcilesPendingMessageEvenWhenTextDiffers() {
        val state = negotiated("batch", "labeled-response", "echo-message")
        val channel = state.channelRef("#c")
        val label = assertNotNull(state.issueLabel(channel, LabeledCommand.PRIVMSG, 1000L))

        val echo = state.feed("@label=$label;msgid=m1 :me!u@h PRIVMSG #c :hello (filtered)").appended().single()

        assertEquals(channel, echo.ref)
        assertTrue(echo.reconcilePendingEcho)
        assertEquals(label, echo.echoLabel)
        assertNull(state.pendingLabel(label))

        val buffer = ConversationBuffer(
            ref = channel,
            displayName = "#c",
            messages = listOf(pendingMessage("hello", "other"), pendingMessage("hello", label).copy(localId = 2)),
        )
        val reconciled = assertNotNull(
            reconcileEcho(buffer, echo.kind, echo.text, echo.echoLabel, echo.msgid, echo.timestampMs, null),
        )
        val updated = reconciled.messages.single { it.localId == 2L }
        assertEquals("hello (filtered)", updated.text)
        assertEquals("m1", updated.msgid)
        assertFalse(updated.pendingEcho)
        assertNull(updated.echoLabel)
        assertTrue(reconciled.messages.single { it.localId == 1L }.pendingEcho)
    }

    @Test
    fun labelledEchoInsideBatchCarriesLabel() {
        val state = negotiated("batch", "labeled-response", "echo-message")
        val label = assertNotNull(state.issueLabel(state.directRef("NickServ"), LabeledCommand.PRIVMSG, 1000L))

        state.feed("@label=$label :srv BATCH +e1 labeled-response")
        val echo = state.feed("@batch=e1 :me!u@h PRIVMSG NickServ :help").appended().single()
        val reply = state.feed("@batch=e1 :NickServ!s@srv NOTICE me :Available commands").appended().single()
        state.feed(":srv BATCH -e1")

        assertEquals(label, echo.echoLabel)
        assertNull(reply.echoLabel)
        assertEquals(state.directRef("NickServ"), reply.ref)
        assertNull(state.pendingLabel(label))
    }

    @Test
    fun unlabeledTrafficKeepsServerRoutingAndTextReconciliation() {
        val state = negotiated("batch", "echo-message")
        val channel = state.channelRef("#c")

        val channelOnly = InboundContext(nowMs = 1000L, hasBuffer = { it == channel.storageKey })
        val reply = state.feed(":srv 324 me #untracked +nt", channelOnly).appended().single()
        val stray = state.feed("@label=unknown :srv 324 me #untracked +nt", channelOnly).appended().single()
        val echo = state.feed("@msgid=m2 :me!u@h PRIVMSG #c :hello").appended().single()

        assertEquals(state.server, reply.ref)
        assertEquals(state.server, stray.ref)
        assertNull(echo.echoLabel)
        assertTrue(echo.reconcilePendingEcho)

        val buffer = ConversationBuffer(ref = channel, displayName = "#c", messages = listOf(pendingMessage("hello", null)))
        val reconciled = assertNotNull(reconcileEcho(buffer, echo.kind, echo.text, null, echo.msgid, echo.timestampMs, null))
        assertEquals("m2", reconciled.messages.single().msgid)
        assertNull(reconcileEcho(buffer, echo.kind, "different", null, "m3", echo.timestampMs, null))
    }

    @Test
    fun reconnectResetsPendingLabels() {
        val state = negotiated("batch", "labeled-response")
        val channel = state.channelRef("#c")
        val label = assertNotNull(state.issueLabel(channel, LabeledCommand.TOPIC, 1000L))
        state.feed("@label=$label :srv BATCH +t1 labeled-response")

        state.apply(IrcEvent.ConnectionOpened, context)

        assertNull(state.pendingLabel(label))
        assertFalse(state.labeledResponseEnabled)
        assertEquals(state.server, state.feed("@label=$label :srv 333 me #c alice 1700000000").appended().single().ref)
        state.apply(IrcEvent.CapabilitiesNegotiated(setOf("batch", "labeled-response")), context)
        val fresh = assertNotNull(state.issueLabel(channel, LabeledCommand.TOPIC, 2000L))
        assertTrue(fresh != label)
    }

    @Test
    fun staleLabelsExpire() {
        val state = negotiated("batch", "labeled-response")
        val stale = assertNotNull(state.issueLabel(state.channelRef("#c"), LabeledCommand.RAW, 1000L))

        state.issueLabel(state.channelRef("#c"), LabeledCommand.RAW, 1000L + PerNetworkState.LABEL_TTL_MS + 1)

        assertNull(state.pendingLabel(stale))
    }

    @Test
    fun labelLinePrependsOrMergesTagSection() {
        assertEquals("@label=yh1 WHOIS a a", labelLine("WHOIS a a", "yh1"))
        assertEquals("@label=yh2;+typing=active TAGMSG #c", labelLine("@+typing=active TAGMSG #c", "yh2"))
        assertEquals("yh2", IrcMessage.parse(labelLine("@+typing=active TAGMSG #c", "yh2"))?.tag("label"))
    }

    @Test
    fun serverRefIsUsedForUnlabelledServerReplies() {
        val state = negotiated("batch", "labeled-response")
        assertEquals(ConversationRef.server("net"), state.feed(":srv 999 me :hi").appended().single().ref)
    }
}
