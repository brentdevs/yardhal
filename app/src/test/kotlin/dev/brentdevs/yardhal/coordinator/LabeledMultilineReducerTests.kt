package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.client.IrcEvent
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabeledMultilineReducerTests {
    private val context = InboundContext(nowMs = 1000L)

    private fun negotiated(): PerNetworkState = PerNetworkState("net", "me").also { state ->
        state.apply(
            IrcEvent.CapabilitiesNegotiated(setOf("batch", "draft/multiline", "echo-message", "labeled-response")),
            context,
        )
    }

    private fun PerNetworkState.feed(line: String, ctx: InboundContext = context): List<InboundEffect> =
        apply(IrcEvent.MessageReceived(assertNotNull(IrcMessage.parse(line))), ctx)

    private fun pendingBuffer(state: PerNetworkState, label: String): ConversationBuffer = ConversationBuffer(
        ref = state.channelRef("#room"),
        displayName = "#room",
        messages = listOf(
            ChatMessage(
                localId = 7L,
                sender = "me",
                kind = MessageKind.PRIVMSG,
                text = "one\ntwo",
                timestampMs = 500L,
                sentByUs = true,
                highlightsMe = false,
                msgid = null,
                pendingEcho = true,
                echoLabel = label,
            ),
        ),
    )

    @Test
    fun openingLabelSurvivesPlainMultilineCloseAndReconcilesModifiedEcho() {
        val state = negotiated()
        val label = assertNotNull(state.issueLabel(state.channelRef("#room"), LabeledCommand.PRIVMSG, context.nowMs))
        val pending = pendingBuffer(state, label)

        state.feed("@label=$label;msgid=ml1 :me!u@h BATCH +ml draft/multiline #room")
        assertTrue(state.feed("@batch=ml :me!u@h PRIVMSG #room :one (filtered)").filterIsInstance<InboundEffect.AppendMessage>().isEmpty())
        assertTrue(state.feed("@batch=ml :me!u@h PRIVMSG #room :two (filtered)").filterIsInstance<InboundEffect.AppendMessage>().isEmpty())
        assertNotNull(state.pendingLabel(label))

        val echo = state.feed("BATCH -ml").filterIsInstance<InboundEffect.AppendMessage>().single()

        assertEquals(label, echo.echoLabel)
        assertEquals("one (filtered)\ntwo (filtered)", echo.text)
        assertEquals("ml1", echo.msgid)
        assertTrue(echo.sentByUs)
        assertTrue(echo.reconcilePendingEcho)
        assertNull(state.pendingLabel(label))
        val reconciled = assertNotNull(
            reconcileEcho(pending, echo.kind, echo.text, echo.echoLabel, echo.msgid, echo.timestampMs, echo.attachmentUrl),
        )
        val message = reconciled.messages.single()
        assertEquals(7L, message.localId)
        assertEquals(echo.text, message.text)
        assertEquals("ml1", message.msgid)
        assertFalse(message.pendingEcho)
        assertNull(message.echoLabel)
    }

    @Test
    fun multilineEchoInheritsEnclosingLabelUntilLabeledBatchCloses() {
        val state = negotiated()
        val label = assertNotNull(state.issueLabel(state.channelRef("#room"), LabeledCommand.PRIVMSG, context.nowMs))

        state.feed("@label=$label :srv BATCH +response labeled-response")
        state.feed("@batch=response;msgid=ml2 :me!u@h BATCH +ml draft/multiline #room")
        state.feed("@batch=ml :me!u@h PRIVMSG #room :one (filtered)")
        state.feed("@batch=ml :me!u@h PRIVMSG #room :two (filtered)")

        val echo = state.feed("@batch=response BATCH -ml").filterIsInstance<InboundEffect.AppendMessage>().single()

        assertEquals(label, echo.echoLabel)
        assertEquals("one (filtered)\ntwo (filtered)", echo.text)
        assertEquals("ml2", echo.msgid)
        assertTrue(echo.reconcilePendingEcho)
        assertNotNull(state.pendingLabel(label))
        assertTrue(state.feed(":srv BATCH -response").filterIsInstance<InboundEffect.AppendMessage>().isEmpty())
        assertNull(state.pendingLabel(label))
    }

    @Test
    fun openMultilineLabelDoesNotExpireBeforeFlushWhenAnotherLabelIsIssued() {
        val state = negotiated()
        val origin = state.channelRef("#room")
        val label = assertNotNull(state.issueLabel(origin, LabeledCommand.PRIVMSG, context.nowMs))
        val expired = assertNotNull(state.issueLabel(origin, LabeledCommand.PRIVMSG, context.nowMs))
        state.feed("@label=$label;msgid=ml3 :me!u@h BATCH +ml draft/multiline #room")
        state.feed("@batch=ml :me!u@h PRIVMSG #room :one (filtered)")
        state.feed("@batch=ml :me!u@h PRIVMSG #room :two (filtered)")
        val later = InboundContext(nowMs = context.nowMs + PerNetworkState.LABEL_TTL_MS + 1)
        val fresh = assertNotNull(state.issueLabel(origin, LabeledCommand.PRIVMSG, later.nowMs))

        assertNotNull(state.pendingLabel(label))
        assertNull(state.pendingLabel(expired))
        val echo = state.feed("BATCH -ml", later).filterIsInstance<InboundEffect.AppendMessage>().single()

        assertEquals(label, echo.echoLabel)
        assertEquals("one (filtered)\ntwo (filtered)", echo.text)
        assertNull(state.pendingLabel(label))
        assertNotNull(state.pendingLabel(fresh))
    }
}
