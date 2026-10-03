package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.client.IrcEvent
import dev.brentdevs.yardhal.core.data.ChannelMember
import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.MultilineLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagingReducerTests {

    private val context = InboundContext(nowMs = 1000L)

    private fun PerNetworkState.feed(vararg lines: String): List<InboundEffect> =
        lines.flatMap { line ->
            val message = IrcMessage.parse(line) ?: error("unparseable: $line")
            apply(IrcEvent.MessageReceived(message), context)
        }

    private fun List<InboundEffect>.appended(): List<InboundEffect.AppendMessage> =
        filterIsInstance<InboundEffect.AppendMessage>()

    private fun negotiated(limits: String = "max-bytes=4096,max-lines=24"): PerNetworkState {
        val state = PerNetworkState("net", "me")
        state.feed(":srv CAP * LS :batch echo-message draft/multiline=$limits")
        state.apply(IrcEvent.CapabilitiesNegotiated(setOf("batch", "echo-message", "draft/multiline")), context)
        return state
    }

    @Test
    fun capValueParsedFromLsAndClearedByDel() {
        val state = negotiated()
        assertEquals(MultilineLimits(4096, 24), state.multilineLimits)
        assertEquals(MultilineLimits(4096, 24), state.outboundMultilineLimits())
        state.feed(":srv CAP me DEL :draft/multiline")
        assertNull(state.multilineLimits)
        assertNull(state.outboundMultilineLimits())
    }

    @Test
    fun outboundLimitsRequireAcknowledgedCapsNotJustAdvertisedValue() {
        val state = PerNetworkState("net", "me")
        state.feed(":srv CAP * LS :draft/multiline=max-bytes=4096")
        state.apply(IrcEvent.CapabilitiesNegotiated(setOf("batch")), context)
        assertNull(state.outboundMultilineLimits())
    }

    @Test
    fun multilineBatchReassemblesIntoOneMessageWithConcatAndBatchMsgid() {
        val state = negotiated()
        val buffered = state.feed(
            "@msgid=batch-id;time=2024-01-01T00:00:00.000Z;+draft/reply=parent :alice!u@h BATCH +ml draft/multiline #chan",
            "@batch=ml;time=2024-01-01T00:00:01.000Z :alice!u@h PRIVMSG #chan hello",
            "@batch=ml :alice!u@h PRIVMSG #chan :",
            "@batch=ml :alice!u@h PRIVMSG #chan :how is ",
            "@batch=ml;draft/multiline-concat :alice!u@h PRIVMSG #chan :everyone?",
        )
        assertTrue(buffered.appended().isEmpty(), buffered.toString())
        val closed = state.feed("BATCH -ml").appended().single()
        assertEquals("hello\n\nhow is everyone?", closed.text)
        assertEquals("batch-id", closed.msgid)
        assertEquals("alice", closed.sender)
        assertEquals(state.channelRef("#chan"), closed.ref)
        assertEquals(MessageKind.PRIVMSG, closed.kind)
        assertEquals(1_704_067_201_000L, closed.timestampMs)
        assertEquals("parent", closed.replyToMsgid)
        assertFalse(closed.playback)
        assertTrue(state.openBatches.isEmpty())
    }

    @Test
    fun ownMultilineEchoReconcilesPendingMessage() {
        val state = negotiated()
        val echo = state.feed(
            "@msgid=e1 :me!u@h BATCH +b draft/multiline #chan",
            "@batch=b :me!u@h PRIVMSG #chan first",
            "@batch=b :me!u@h PRIVMSG #chan second",
            "BATCH -b",
        ).appended().single()
        assertTrue(echo.sentByUs)
        assertTrue(echo.reconcilePendingEcho)
        assertEquals("first\nsecond", echo.text)
    }

    @Test
    fun multilineNestedInChathistoryIsPlayback() {
        val state = negotiated()
        val effects = state.feed(
            ":srv BATCH +hist chathistory #chan",
            "@batch=hist;msgid=old :bob!u@h BATCH +inner draft/multiline #chan",
            "@batch=inner :bob!u@h NOTICE #chan :line me one",
            "@batch=inner :bob!u@h NOTICE #chan :line two",
            "BATCH -inner",
            "BATCH -hist",
        ).appended()
        val message = effects.single()
        assertTrue(message.playback)
        assertFalse(message.highlightsMe)
        assertEquals(MessageKind.NOTICE, message.kind)
        assertEquals("old", message.msgid)
        assertEquals("line me one\nline two", message.text)
    }

    @Test
    fun multilineExceedingMaxLinesFlushesAndFallsBackToSeparateLines() {
        val state = negotiated("max-bytes=4096,max-lines=2")
        val effects = state.feed(
            "@msgid=big :alice!u@h BATCH +ml draft/multiline #chan",
            "@batch=ml :alice!u@h PRIVMSG #chan one",
            "@batch=ml :alice!u@h PRIVMSG #chan two",
            "@batch=ml :alice!u@h PRIVMSG #chan three",
            "@batch=ml :alice!u@h PRIVMSG #chan four",
            "BATCH -ml",
        ).appended()
        assertEquals(listOf("one\ntwo", "three", "four"), effects.map { it.text })
        assertEquals(listOf("big", null, null), effects.map { it.msgid })
    }

    @Test
    fun multilineExceedingMaxBytesFlushesBufferedContent() {
        val state = negotiated("max-bytes=10")
        val effects = state.feed(
            ":alice!u@h BATCH +ml draft/multiline #chan",
            "@batch=ml :alice!u@h PRIVMSG #chan abcd",
            "@batch=ml :alice!u@h PRIVMSG #chan efgh",
            "@batch=ml :alice!u@h PRIVMSG #chan ijkl",
            "BATCH -ml",
        ).appended()
        assertEquals(listOf("abcd\nefgh", "ijkl"), effects.map { it.text })
    }

    @Test
    fun multilineLineWithMismatchedTargetIsDeliveredStandalone() {
        val state = negotiated()
        val effects = state.feed(
            ":alice!u@h BATCH +ml draft/multiline #chan",
            "@batch=ml :alice!u@h PRIVMSG #other stray",
            "@batch=ml :alice!u@h PRIVMSG #chan kept",
            "BATCH -ml",
        ).appended()
        assertEquals(listOf("stray" to "#other", "kept" to "#chan"), effects.map { it.text to it.ref.rawTarget })
    }

    @Test
    fun multilineFromIgnoredSenderIsDropped() {
        val state = negotiated()
        val ignoring = InboundContext(nowMs = 1000L, isIgnored = { it == "troll" })
        val effects = listOf(
            ":troll!u@h BATCH +ml draft/multiline #chan",
            "@batch=ml :troll!u@h PRIVMSG #chan a",
            "BATCH -ml",
        ).flatMap { state.apply(IrcEvent.MessageReceived(IrcMessage.parse(it) ?: error(it)), ignoring) }
        assertTrue(effects.appended().isEmpty())
    }

    @Test
    fun channelContextTagStoredOnDirectMessages() {
        val state = PerNetworkState("net", "me")
        val draft = state.feed("@+draft/channel-context=#chan :bot!u@h NOTICE me :I'm helping.").appended().single()
        assertEquals(ConversationKind.DIRECT_MESSAGE, draft.ref.kind)
        assertEquals("#chan", draft.channelContext)
        val ratified = state.feed("@+channel-context=#help :bot!u@h PRIVMSG me :hi").appended().single()
        assertEquals("#help", ratified.channelContext)
    }

    @Test
    fun channelContextIgnoredOnChannelMessagesAndInvalidValues() {
        val state = PerNetworkState("net", "me")
        val inChannel = state.feed("@+draft/channel-context=#chan :bot!u@h PRIVMSG #other :x").appended().single()
        assertNull(inChannel.channelContext)
        val invalid = state.feed("@+draft/channel-context=notachannel :bot!u@h PRIVMSG me :x").appended().single()
        assertNull(invalid.channelContext)
    }

    @Test
    fun channelContextOnMultilineBatchOpeningApplies() {
        val state = negotiated()
        val message = state.feed(
            "@+draft/channel-context=#chan :bot!u@h BATCH +ml draft/multiline me",
            "@batch=ml :bot!u@h PRIVMSG me a",
            "@batch=ml :bot!u@h PRIVMSG me b",
            "BATCH -ml",
        ).appended().single()
        assertEquals("#chan", message.channelContext)
        assertEquals(state.directRef("bot"), message.ref)
    }

    @Test
    fun renameMovesChannelStateAndEmitsBufferMoveWithSystemLine() {
        val state = PerNetworkState("net", "me", autojoin = listOf("#Old", "#keep"))
        state.feed(":me!u@h JOIN #old", ":alice!u@h JOIN #old")
        val effects = state.feed(":op!u@h RENAME #old #new :Typo fix")
        val from = state.channelRef("#old")
        val to = state.channelRef("#new")
        assertEquals(InboundEffect.RenameBuffer(from, to), effects.filterIsInstance<InboundEffect.RenameBuffer>().single())
        val line = effects.appended().single()
        assertEquals(to, line.ref)
        assertEquals("Channel renamed from #old to #new by op (Typo fix)", line.text)
        assertNull(state.channel(from.storageKey))
        val moved = state.channel(to.storageKey)
        assertEquals(to, moved?.ref)
        assertEquals(listOf(ChannelMember("alice")), moved?.memberList())
        assertEquals(listOf("#new", "#keep"), state.autojoin)
    }

    @Test
    fun renameWithEmptyReasonAndCaseOnlyChange() {
        val state = PerNetworkState("net", "me")
        state.feed(":me!u@h JOIN #chan")
        val effects = state.feed(":op!u@h RENAME #chan #Chan :")
        val rename = effects.filterIsInstance<InboundEffect.RenameBuffer>().single()
        assertEquals(rename.from.storageKey, rename.to.storageKey)
        assertEquals("#Chan", state.channel(rename.to.storageKey)?.ref?.rawTarget)
        assertEquals("Channel renamed from #chan to #Chan by op", effects.appended().single().text)
    }

    @Test
    fun renameIgnoresNonChannelTargets() {
        val state = PerNetworkState("net", "me")
        assertTrue(state.feed(":op!u@h RENAME alice bob :x").isEmpty())
        assertTrue(state.feed(":op!u@h RENAME #a").isEmpty())
    }
}
