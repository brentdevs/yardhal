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
    fun channelMetadataSurvivesRenameAndCasemappingRekey() {
        val state = PerNetworkState("net", "me")
        state.feed(
            ":me!u@h JOIN #old[room]",
            ":srv METADATA #old[room] avatar * :https://example.org/channel.png",
            ":op!u@h RENAME #old[room] #new[room] :rename",
            ":srv 005 me CASEMAPPING=ascii :are supported",
        )
        val channel = state.channel(state.channelRef("#new[room]").storageKey)
        assertEquals("https://example.org/channel.png", channel?.metadataValue("avatar"))
        assertNull(state.channel(state.channelRef("#old[room]").storageKey))
    }

    @Test
    fun outstandingLabeledReplyFollowsRenamedConversation() {
        val state = PerNetworkState("net", "me")
        state.apply(IrcEvent.CapabilitiesNegotiated(setOf("labeled-response")), context)
        state.feed(":me!u@h JOIN #old")
        val label = checkNotNull(state.issueLabel(state.channelRef("#old"), LabeledCommand.RAW, context.nowMs))
        state.feed(":op!u@h RENAME #old #new :rename")
        val replyContext = InboundContext(
            nowMs = context.nowMs,
            hasBuffer = { it == state.channelRef("#new").storageKey },
        )
        val reply = state.apply(
            IrcEvent.MessageReceived(checkNotNull(IrcMessage.parse("@label=$label :srv 999 me :response"))),
            replyContext,
        ).appended().single()
        assertEquals(state.channelRef("#new"), reply.ref)
        assertNull(state.pendingLabel(label))
    }

    @Test
    fun noImplicitNamesJoinCompletesWithoutWaitingForNames() {
        val state = PerNetworkState("net", "me")
        state.apply(IrcEvent.CapabilitiesNegotiated(setOf("no-implicit-names", "draft/chathistory")), context)
        val effects = state.feed(":me!u@h JOIN #room")
        assertTrue(InboundEffect.SetJoinState(state.channelRef("#room"), JoinState.JOINED) in effects)
        assertTrue(state.channel(state.channelRef("#room").storageKey)?.memberList().orEmpty().isEmpty())
        assertTrue(effects.filterIsInstance<InboundEffect.SendRaw>().any { it.line == "CHATHISTORY LATEST #room * 50" })
    }

    @Test
    fun withdrawingCapabilitiesDisablesFeaturesAndResubscribesAfterReenable() {
        val state = PerNetworkState("net", "me")
        val caps = setOf("batch", "echo-message", "labeled-response", "draft/metadata-2", "znc.in/playback")
        val values = mapOf("draft/metadata-2" to "max-subs=2")
        state.apply(IrcEvent.CapabilitiesNegotiated(caps, values), context)
        state.apply(IrcEvent.Registered("me", "welcome"), context)
        state.feed(":srv 770 me avatar display-name")
        assertTrue(state.metadataSubscriptions.isNotEmpty())
        val updated = state.apply(IrcEvent.CapabilitiesNegotiated(caps, values), context)
        assertFalse(updated.filterIsInstance<InboundEffect.SendRaw>().any { "*playback" in it.line })
        state.apply(IrcEvent.CapabilitiesNegotiated(setOf("batch")), context)
        assertNull(state.issueLabel(state.server, LabeledCommand.RAW, context.nowMs))
        assertNull(state.metadataCapability)
        assertTrue(state.metadataSubscriptions.isEmpty())
        assertFalse("echo-message" in state.supportedCaps)
        val reenabled = state.apply(IrcEvent.CapabilitiesNegotiated(caps, values), context)
        assertTrue(reenabled.filterIsInstance<InboundEffect.SendRaw>().any { it.line == "METADATA * SUB avatar display-name" })
    }

    @Test
    fun preregistrationIsupportBatchAndTokenRemovalUpdateDerivedState() {
        val state = PerNetworkState("net", "me")
        state.feed(
            ":srv BATCH +support draft/isupport",
            "@batch=support :srv 005 me CASEMAPPING=ascii PREFIX=(qov)~@+ WHOX CHATHISTORY=500 soju.im/FILEHOST=https://upload.example :are supported",
            ":srv BATCH -support",
        )
        assertFalse(state.registered)
        assertTrue(state.hasWhox)
        assertEquals(listOf('~', '@', '+'), state.prefixModes.symbols)
        assertEquals(200, state.chathistoryLimit)
        state.feed(":srv 005 me -CASEMAPPING -PREFIX -WHOX -CHATHISTORY -soju.im/FILEHOST :are supported")
        assertEquals(dev.brentdevs.yardhal.core.protocol.CaseMapping.RFC1459, state.casemapping)
        assertEquals(dev.brentdevs.yardhal.core.protocol.ChannelPrefixModes.DEFAULT, state.prefixModes)
        assertFalse(state.hasWhox)
        assertEquals(0, state.chathistoryLimit)
        assertNull(state.filehostEndpoint)
        val joined = state.feed(":me!u@h JOIN #room")
        assertFalse(joined.filterIsInstance<InboundEffect.SendRaw>().any { it.line.startsWith("CHATHISTORY") })
    }

    @Test
    fun renameIgnoresNonChannelTargets() {
        val state = PerNetworkState("net", "me")
        assertTrue(state.feed(":op!u@h RENAME alice bob :x").isEmpty())
        assertTrue(state.feed(":op!u@h RENAME #a").isEmpty())
    }
}
