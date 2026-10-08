package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.client.IrcEvent
import dev.brentdevs.yardhal.core.client.SaslOutcome
import dev.brentdevs.yardhal.core.data.ChannelMember
import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.WhoisInfo
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.MultilineLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerNetworkStateTests {
    private val now = 1_700_000_000_000L

    private fun state(autojoin: List<String> = emptyList()) = PerNetworkState("net", "me", autojoin)

    private fun PerNetworkState.feed(line: String, context: InboundContext = InboundContext(nowMs = now)): List<InboundEffect> {
        val message = IrcMessage.parse(line) ?: error("unparseable $line")
        return apply(IrcEvent.MessageReceived(message), context)
    }

    private fun channel(name: String) = ConversationRef.channel("net", name)

    private inline fun <reified T : InboundEffect> List<InboundEffect>.only(): T = filterIsInstance<T>().single()

    private fun List<InboundEffect>.appended(): List<InboundEffect.AppendMessage> = filterIsInstance<InboundEffect.AppendMessage>()

    private fun List<InboundEffect>.sent(): List<String> = filterIsInstance<InboundEffect.SendRaw>().map { it.line }

    private fun PerNetworkState.joinChannelWith(name: String, vararg nicks: String) {
        feed(":me!u@h JOIN $name")
        feed(":srv 353 me = $name :${nicks.joinToString(" ")}")
        feed(":srv 366 me $name :End of NAMES")
    }

    @Test
    fun capAndKeepaliveTrafficStaysOutOfTheTranscript() {
        val state = state()
        assertTrue(state.feed(":srv CAP * ACK :server-time").isEmpty())
        assertTrue(state.feed("PING :token").isEmpty())
        assertTrue(state.feed(":srv PONG srv :token").isEmpty())
    }

    @Test
    fun unknownCommandsAndNumericsLandInTheServerBuffer() {
        val state = state()
        val unknown = state.feed(":srv WALLOPS :hello opers").appended().single()
        assertEquals(ConversationKind.SERVER, unknown.ref.kind)
        assertEquals("WALLOPS hello opers", unknown.text)
        val motd = state.feed(":srv 372 me :- welcome").appended().single()
        assertEquals("- welcome", motd.text)
        assertEquals(now, motd.timestampMs)
    }

    @Test
    fun errorNumericsAreTaggedAsErrors() {
        assertEquals("[error] nobody No such nick", state().feed(":srv 401 me nobody :No such nick").appended().single().text)
    }

    @Test
    fun inputTooLongSurfacesAsAnErrorLine() {
        val line = state().feed(":srv 417 me :Input line was too long").appended().single()
        assertEquals(ConversationKind.SERVER, line.ref.kind)
        assertEquals("[error] Input line was too long", line.text)
    }

    @Test
    fun standardRepliesAreTaggedByVerb() {
        val state = state()
        assertEquals("[fail] CHATHISTORY INVALID_TARGET bad", state.feed(":srv FAIL CHATHISTORY INVALID_TARGET :bad").appended().single().text)
        assertTrue(state.feed(":srv WARN REHASH CERTS_EXPIRED :soon").appended().single().text.startsWith("[warn] "))
        assertTrue(state.feed(":srv NOTE * OPER_MESSAGE :hi").appended().single().text.startsWith("[note] "))
    }

    @Test
    fun monitorNumericsAreTaggedAsMonitorLines() {
        val state = state()
        assertEquals("[monitor] alice!u@h", state.feed(":srv 730 me :alice!u@h").appended().single().text)
        assertEquals("[monitor] bob", state.feed(":srv 731 me :bob").appended().single().text)
    }

    @Test
    fun channelPrivmsgBecomesAChannelMessageWithServerTimeAndTags() {
        val state = state()
        val effect = state.feed(
            "@time=2024-01-01T00:00:00.000Z;msgid=m1;+draft/reply=m0;+draft/attachment=https://x/y.png :alice!u@h PRIVMSG #room :hi there",
        ).appended().single()
        assertEquals(channel("#room"), effect.ref)
        assertEquals("alice", effect.sender)
        assertEquals(MessageKind.PRIVMSG, effect.kind)
        assertEquals(1_704_067_200_000L, effect.timestampMs)
        assertEquals("m1", effect.msgid)
        assertEquals("m0", effect.replyToMsgid)
        assertEquals("https://x/y.png", effect.attachmentUrl)
        assertFalse(effect.sentByUs)
    }

    @Test
    fun mentionsHighlightButOwnMessagesReconcileEchoes() {
        val state = state()
        val mention = state.feed(":alice!u@h PRIVMSG #room :hey me, look").appended().single()
        assertTrue(mention.highlightsMe)
        val echo = state.feed(":me!u@h PRIVMSG #room :hello me").appended().single()
        assertTrue(echo.sentByUs)
        assertTrue(echo.reconcilePendingEcho)
        assertFalse(echo.highlightsMe)
    }

    @Test
    fun privateMessagesRouteByPeerAndServiceTargetsGoToServerBuffer() {
        val state = state()
        assertEquals(ConversationRef.directMessage("net", "alice"), state.feed(":alice!u@h PRIVMSG me :psst").appended().single().ref)
        assertEquals(ConversationRef.directMessage("net", "bob"), state.feed(":me!u@h PRIVMSG bob :reply").appended().single().ref)
        assertEquals(ConversationKind.SERVER, state.feed(":irc.example NOTICE me :server notice").appended().single().ref.kind)
        assertEquals(ConversationKind.SERVER, state.feed(":*status!znc@znc.in PRIVMSG *status :hello").appended().single().ref.kind)
    }

    @Test
    fun noticesAndCtcpActionsKeepTheirKind() {
        val state = state()
        assertEquals(MessageKind.NOTICE, state.feed(":alice!u@h NOTICE #room :note").appended().single().kind)
        val action = state.feed(":alice!u@h PRIVMSG #room :\u0001ACTION waves\u0001").appended().single()
        assertEquals(MessageKind.ACTION, action.kind)
        assertEquals("waves", action.text)
    }

    @Test
    fun ignoredSendersAreDropped() {
        val context = InboundContext(nowMs = now, isIgnored = { it == "troll" })
        assertTrue(state().feed(":troll!u@h PRIVMSG #room :spam", context).isEmpty())
    }

    @Test
    fun playbackBatchesIncludingNestedOnesSuppressHighlights() {
        val state = state()
        state.feed(":srv BATCH +outer chathistory #room")
        state.feed("@batch=outer :srv BATCH +inner netjoin a b")
        val message = state.feed("@batch=inner :alice!u@h PRIVMSG #room :me are you there").appended().single()
        assertTrue(message.playback)
        assertFalse(message.highlightsMe)
        state.feed(":srv BATCH -inner")
        state.feed(":srv BATCH -outer")
        assertFalse(state.feed(":alice!u@h PRIVMSG #room :live").appended().single().playback)
    }

    @Test
    fun netsplitBatchCollapsesQuitsIntoOneSummaryLine() {
        val state = state()
        state.joinChannelWith("#room", "me", "alice", "bob")
        state.feed(":srv BATCH +split netsplit hub.example leaf.example")
        val quitEffects = state.feed("@batch=split :alice!u@h QUIT :hub.example leaf.example") +
            state.feed("@batch=split :bob!u@h QUIT :hub.example leaf.example")
        assertTrue(quitEffects.appended().isEmpty())
        val summary = state.feed(":srv BATCH -split").appended().single()
        assertEquals("⇅ 2 users quit during netsplit (hub.example ↔ leaf.example)", summary.text)
        assertEquals(listOf("me"), state.channel(channel("#room").storageKey)?.memberList()?.map { it.nick })
    }

    @Test
    fun netjoinBatchSuppressesJoinLinesButTracksMembers() {
        val state = state()
        state.joinChannelWith("#room", "me")
        state.feed(":srv BATCH +j netjoin a b")
        assertTrue(state.feed("@batch=j :alice!u@h JOIN #room").appended().isEmpty())
        assertEquals("⇜ 1 users returned from netsplit (a ↔ b)", state.feed(":srv BATCH -j").appended().single().text)
        assertEquals(listOf("alice", "me"), state.channel(channel("#room").storageKey)?.memberList()?.map { it.nick })
    }

    @Test
    fun tagmsgReactionsAndTypingBecomeEffects() {
        val state = state()
        val react = state.feed("@+draft/react=👍;+draft/reply=m1 :alice!u@h TAGMSG #room").filterIsInstance<InboundEffect.ApplyReaction>()
        assertTrue(react.isEmpty())
        val added = state.feed("@+draft/react=👍;+draft/refs=m1,m2 :alice!u@h TAGMSG #room").only<InboundEffect.ApplyReaction>()
        assertEquals(InboundEffect.ApplyReaction(channel("#room"), "alice", "👍", listOf("m1", "m2"), added = true), added)
        val removed = state.feed("@+draft/unreact=👍;+draft/refs=m1 :alice!u@h TAGMSG #room").only<InboundEffect.ApplyReaction>()
        assertFalse(removed.added)
        val typing = state.feed("@+typing=active :alice!u@h TAGMSG me").only<InboundEffect.SetTyping>()
        assertEquals(InboundEffect.SetTyping(ConversationRef.directMessage("net", "alice"), "alice", now + TYPING_TTL_MS), typing)
        assertNull(state.feed("@+typing=done :alice!u@h TAGMSG #room").only<InboundEffect.SetTyping>().expiresAtMs)
    }

    @Test
    fun reactionFoldAddsAndRemovesPerSender() {
        val ref = channel("#room")
        val once = applyReaction(emptyMap(), InboundEffect.ApplyReaction(ref, "alice", "👍", listOf("m1"), true))
        val twice = applyReaction(once, InboundEffect.ApplyReaction(ref, "bob", "👍", listOf("m1"), true))
        assertEquals(setOf("alice", "bob"), twice["m1"]?.get("👍"))
        val undone = applyReaction(twice, InboundEffect.ApplyReaction(ref, "alice", "👍", listOf("m1"), false))
        assertEquals(setOf("bob"), undone["m1"]?.get("👍"))
        val empty = applyReaction(undone, InboundEffect.ApplyReaction(ref, "bob", "👍", listOf("m1"), false))
        assertEquals(emptyMap(), empty["m1"])
    }

    @Test
    fun redactionReplacesOnlyTheMatchingMessage() {
        val message = ChatMessage(1, "alice", MessageKind.PRIVMSG, "secret", 0, false, false, "m1")
        val other = message.copy(localId = 2, msgid = "m2", text = "keep")
        val buffer = ConversationBuffer(channel("#room"), "#room", messages = listOf(message, other))
        val redacted = redactInBuffer(buffer, "m1")
        assertEquals(listOf("message deleted", "keep"), redacted.messages.map { it.text })
        assertEquals(MessageKind.SYSTEM, redacted.messages[0].kind)
        assertEquals(buffer, redactInBuffer(buffer, "missing"))
    }

    @Test
    fun redactEmitsRedactionForTheWireConversation() {
        val state = state()
        assertEquals(InboundEffect.RedactMessage(channel("#room"), "same-id"),
            state.feed(":alice!u@h REDACT #room same-id").only<InboundEffect.RedactMessage>())
        assertEquals(InboundEffect.RedactMessage(state.directRef("alice"), "dm-id"),
            state.feed(":alice!u@h REDACT me dm-id").only<InboundEffect.RedactMessage>())
        assertEquals(InboundEffect.RedactMessage(state.directRef("bob"), "outgoing-id"),
            state.feed(":me!u@h REDACT bob outgoing-id").only<InboundEffect.RedactMessage>())
        assertTrue(state.feed(":alice!u@h REDACT #room").filterIsInstance<InboundEffect.RedactMessage>().isEmpty())
    }

    @Test
    fun markreadAppliesTimestampToTheTargetAndIgnoresStarTargets() {
        val state = state()
        val marker = state.feed(":srv MARKREAD #room timestamp=2024-01-01T00:00:00.000Z").single()
        assertEquals(InboundEffect.ApplyReadMarker(channel("#room"), 1_704_067_200_000L), marker)
        assertTrue(state.feed(":srv MARKREAD * timestamp=2024-01-01T00:00:00.000Z").isEmpty())
        assertTrue(state.feed(":srv MARKREAD #room *").isEmpty())
    }

    @Test
    fun ownJoinOpensBufferAndRequestsTopicModeAndHistory() {
        val state = state()
        state.feed(":srv 005 me CHATHISTORY=500 :are supported")
        val effects = state.feed(":me!u@h JOIN #room")
        assertTrue(InboundEffect.EnsureBuffer(channel("#room")) in effects)
        assertEquals(listOf("TOPIC #room", "MODE #room"), effects.sent())
        assertEquals(listOf(InboundEffect.RequestHistory(channel("#room"))),
            effects.filterIsInstance<InboundEffect.RequestHistory>())
        assertTrue(effects.appended().isEmpty())
    }

    @Test
    fun othersJoiningAddMemberAndAJoinLine() {
        val state = state()
        state.joinChannelWith("#room", "@me")
        val effects = state.feed(":alice!u@h JOIN #room")
        assertEquals(listOf("alice", "me"), effects.only<InboundEffect.SetMembers>().members.map { it.nick })
        val line = effects.appended().single()
        assertEquals(MessageKind.JOIN, line.kind)
        assertEquals("→ alice joined", line.text)
    }

    @Test
    fun partByOthersRemovesMemberAndOwnPartRemovesBuffer() {
        val state = state()
        state.joinChannelWith("#room", "me", "alice")
        val other = state.feed(":alice!u@h PART #room :bye")
        assertEquals(listOf("me"), other.only<InboundEffect.SetMembers>().members.map { it.nick })
        assertEquals("← alice left (bye)", other.appended().single().text)
        val own = state.feed(":me!u@h PART #room")
        assertEquals(listOf<InboundEffect>(InboundEffect.RemoveBuffer(channel("#room"))), own)
        assertNull(state.channel(channel("#room").storageKey))
    }

    @Test
    fun kickOfAnotherUserRemovesThemAndKickOfUsFailsTheJoin() {
        val state = state()
        state.joinChannelWith("#room", "me", "alice")
        val other = state.feed(":op!u@h KICK #room alice :rude")
        assertEquals("alice was kicked by op (rude)", other.appended().single().text)
        assertEquals(listOf("me"), other.only<InboundEffect.SetMembers>().members.map { it.nick })
        val own = state.feed(":op!u@h KICK #room me")
        assertTrue(own.only<InboundEffect.SetMembers>().members.isEmpty())
        assertTrue(InboundEffect.ClearTyping(channel("#room")) in own)
        assertTrue(InboundEffect.SetJoinState(channel("#room"), JoinState.FAILED) in own)
    }

    @Test
    fun kickForAnUnknownBufferIsIgnored() {
        val context = InboundContext(nowMs = now, hasBuffer = { false })
        assertTrue(state().feed(":op!u@h KICK #elsewhere alice", context).isEmpty())
    }

    @Test
    fun quitRemovesTheUserFromEveryChannel() {
        val state = state()
        state.joinChannelWith("#a", "me", "alice")
        state.joinChannelWith("#b", "me", "Alice")
        val effects = state.feed(":ALICE!u@h QUIT :gone")
        assertEquals(2, effects.filterIsInstance<InboundEffect.SetMembers>().size)
        assertNull(state.user("alice"))
    }

    @Test
    fun nickChangeRenamesMembersAndTracksOwnNick() {
        val state = state()
        state.joinChannelWith("#room", "me", "+alice")
        val rename = state.feed(":alice!u@h NICK :alicia")
        assertEquals(listOf(ChannelMember("alicia", '+'), ChannelMember("me")), rename.only<InboundEffect.SetMembers>().members)
        val own = state.feed(":me!u@h NICK newme")
        assertTrue(InboundEffect.OwnNickChanged("newme") in own)
        assertEquals("newme", state.ownNick)
        assertTrue(state.isOwnNick("NEWME"))
    }

    @Test
    fun topicVerbAndNumericsSetOrClearTopic() {
        val state = state()
        assertEquals(InboundEffect.SetTopic(channel("#room"), "new topic"), state.feed(":alice!u@h TOPIC #room :new topic").single())
        assertEquals(InboundEffect.SetTopic(channel("#room"), "old topic"), state.feed(":srv 332 me #room :old topic").single())
        assertEquals(InboundEffect.SetTopic(channel("#room"), null), state.feed(":srv 331 me #room :No topic is set").single())
    }

    @Test
    fun namesAccumulateUntilEndAndMarkJoined() {
        val state = state()
        state.feed(":me!u@h JOIN #room")
        assertTrue(state.feed(":srv 353 me = #room :@me +bob").isEmpty())
        assertTrue(state.feed(":srv 353 me = #room :alice!a@host").isEmpty())
        val end = state.feed(":srv 366 me #room :End of NAMES")
        assertEquals(
            listOf(ChannelMember("alice"), ChannelMember("bob", '+'), ChannelMember("me", '@')),
            end.only<InboundEffect.SetMembers>().members,
        )
        assertTrue(InboundEffect.SetJoinState(channel("#room"), JoinState.JOINED) in end)
    }

    @Test
    fun namesForAClosedBufferAreDiscarded() {
        val state = state()
        state.feed(":srv 353 me = #gone :alice")
        assertTrue(state.feed(":srv 366 me #gone :End", InboundContext(nowMs = now, hasBuffer = { false })).isEmpty())
    }

    @Test
    fun whoRepliesReplaceMembersAndCaptureAwayFlags() {
        val state = state()
        state.joinChannelWith("#room", "me")
        state.feed(":srv 352 me #room ~a host srv alice G@ :0 Alice")
        state.feed(":srv 352 me #room ~m host srv me H :0 Me")
        val end = state.feed(":srv 315 me #room :End of WHO")
        val members = end.only<InboundEffect.SetMembers>()
        assertEquals(listOf(ChannelMember("alice", '@'), ChannelMember("me")), members.members)
        assertEquals(PresenceState(away = true, user = "~a", host = "host", realName = "Alice"), members.presence["alice"])
    }

    @Test
    fun whoxRepliesAddMembersWithAccountAndAway() {
        val state = state()
        state.joinChannelWith("#room", "me")
        val effects = state.feed(":srv 354 me #room ~a host alice G+ alice_acct :Alice A")
        val snapshot = effects.only<InboundEffect.SetMembers>()
        assertTrue(ChannelMember("alice", '+') in snapshot.members)
        assertEquals(
            PresenceState(away = true, account = "alice_acct", user = "~a", host = "host", realName = "Alice A"),
            snapshot.presence["alice"],
        )
        val noAccount = state.feed(":srv 354 me #room ~m host me H 0 :Me").only<InboundEffect.SetMembers>()
        assertEquals(PresenceState(away = false, account = null, user = "~m", host = "host", realName = "Me"), noAccount.presence["me"])
    }

    @Test
    fun reconnectClearsRostersAndPresenceBeforeWhoxRebuildsJoinedChannel() {
        val state = state()
        val context = InboundContext(nowMs = now, openChannels = { listOf("#room", "#hidden", "#restored") })
        val caps = setOf("away-notify", "no-implicit-names")
        state.apply(IrcEvent.CapabilitiesNegotiated(caps), context)
        state.feed(":me!old@old.host JOIN #room me_account :Old Me")
        state.feed(":srv 354 me #room ~a alice.host Alice G@ alice_account :Alice A")
        state.feed(":srv 354 me #room old old.host me G me_account :Old Me")
        state.feed(":me!old@old.host AWAY :Old away message")
        state.feed(":me!old@old.host JOIN #hidden")
        state.feed(":srv 354 me #hidden ~c carol.host Carol H 0 :Carol C")
        assertEquals(true, state.user("me")?.presence?.away)
        assertEquals("me_account", state.user("me")?.presence?.account)

        val reopened = state.apply(IrcEvent.ConnectionOpened, context)
        val cleared = reopened.filterIsInstance<InboundEffect.SetMembers>()
        assertEquals(setOf(channel("#room"), channel("#hidden"), channel("#restored")), cleared.map { it.ref }.toSet())
        assertEquals(3, cleared.size)
        assertTrue(cleared.all { it.members.isEmpty() && it.presence.isEmpty() })
        assertTrue(state.channel(channel("#room").storageKey)?.memberList()?.isEmpty() == true)
        assertTrue(state.channel(channel("#hidden").storageKey)?.memberList()?.isEmpty() == true)
        assertNull(state.user("Alice"))
        assertNull(state.user("Carol"))
        assertNull(state.user("me"))
        assertEquals("me", state.ownNick)
        assertTrue(InboundEffect.SetJoinState(channel("#room"), JoinState.JOINING) in reopened)
        assertTrue(InboundEffect.SetJoinState(channel("#hidden"), JoinState.JOINING) in reopened)
        assertTrue(InboundEffect.SetJoinState(channel("#restored"), JoinState.JOINING) in reopened)
        assertTrue(reopened.sent().isEmpty())

        state.apply(IrcEvent.CapabilitiesNegotiated(caps), context)
        val registered = state.apply(IrcEvent.Registered("me", "Welcome"), context)
        assertEquals(listOf("JOIN #room", "JOIN #hidden", "JOIN #restored"), registered.sent())
        val joined = state.feed(":me!fresh@fresh.host JOIN #room")
        assertTrue(InboundEffect.SetJoinState(channel("#room"), JoinState.JOINED) in joined)
        assertTrue(joined.sent().none { it.startsWith("WHO ") })
        assertEquals(PresenceState(away = false, user = "fresh", host = "fresh.host"), state.user("me")?.presence)

        val refreshed = state.feed(":srv 354 me #room ~b bob.host Bob H+ bob_account :Bob B")
            .only<InboundEffect.SetMembers>()
        assertEquals(listOf(ChannelMember("Bob", '+')), refreshed.members)
        assertEquals(
            mapOf("Bob" to PresenceState(away = false, account = "bob_account", user = "~b", host = "bob.host", realName = "Bob B")),
            refreshed.presence,
        )
        assertNull(state.user("Alice"))
        assertTrue(state.channel(channel("#hidden").storageKey)?.memberList()?.isEmpty() == true)
    }

    @Test
    fun banListEntriesGoToTheChannel() {
        val line = state().feed(":srv 367 me #room *!*@bad op 1700000000").appended().single()
        assertEquals(channel("#room"), line.ref)
        assertEquals("*!*@bad op 1700000000", line.text)
    }

    @Test
    fun joinFailureNumericsFailTheChannelJoin() {
        for (numeric in listOf(403, 405, 437, 471, 473, 474, 475)) {
            val effects = state().feed(":srv $numeric me #room :Cannot join channel")
            assertTrue(InboundEffect.EnsureBuffer(channel("#room")) in effects, "numeric $numeric")
            assertTrue(InboundEffect.SetJoinState(channel("#room"), JoinState.FAILED) in effects, "numeric $numeric")
            assertEquals("Cannot join channel", effects.appended().single().text)
        }
    }

    @Test
    fun whoisNumericsAssembleOnlyWhenRequested() {
        val state = state()
        assertTrue(state.feed(":srv 311 me alice ~a host * :Alice A").isEmpty())
        state.feed(":srv 318 me alice :End")
        state.whoisExpected = true
        state.feed(":srv 311 me alice ~a host * :Alice A")
        state.feed(":srv 330 me alice alice_acct :is logged in as")
        state.feed(":srv 319 me alice :@#room #other")
        val info = state.feed(":srv 318 me alice :End of WHOIS").only<InboundEffect.WhoisCompleted>().info
        assertEquals("Alice A", info.realName)
        assertEquals("alice_acct", info.account)
        assertEquals(listOf("@#room", "#other"), info.channels)
        assertFalse(state.whoisExpected)
    }

    @Test
    fun whoisAwayServerOperIdleAndBotNumericsReachTheCompletedResult() {
        val state = state()
        state.joinChannelWith("#room", "me", "alice")
        state.whoisExpected = true
        for (line in listOf(
            ":srv 311 me alice ~a alice.host * :Alice A",
            ":srv 301 me alice :gone to lunch",
            ":srv 312 me alice irc.example :Example IRC server",
            ":srv 313 me alice :is an IRC operator",
            ":srv 317 me alice 42 1700000000 :seconds idle, signon time",
        )) {
            assertTrue(state.feed(line).isEmpty(), line)
            assertTrue(state.whoisExpected, line)
        }
        val bot = state.feed(":srv 335 me alice :is a Bot on IRCv3")
        assertEquals(true, bot.only<InboundEffect.SetMembers>().presence["alice"]?.isBot)
        assertTrue(bot.filterIsInstance<InboundEffect.WhoisCompleted>().isEmpty())
        assertTrue(state.whoisExpected)
        val completed = state.feed(":srv 318 me alice :End of WHOIS").only<InboundEffect.WhoisCompleted>()
        assertEquals(
            WhoisInfo(
                nick = "alice",
                user = "~a",
                host = "alice.host",
                realName = "Alice A",
                server = "irc.example",
                serverInfo = "Example IRC server",
                awayMessage = "gone to lunch",
                isOper = true,
                isBot = true,
                idleSeconds = 42L,
                signOnEpochSeconds = 1_700_000_000L,
            ),
            completed.info,
        )
        assertTrue(checkNotNull(state.user("alice")).presence.isBot)
        assertFalse(state.whoisExpected)
    }

    @Test
    fun unsolicitedWhoisFieldNumericsDoNotStartAssemblyOrChangePresence() {
        val state = state()
        state.joinChannelWith("#room", "me", "alice")
        val presence = checkNotNull(state.user("alice")).presence
        for (line in listOf(
            ":srv 301 me alice :away",
            ":srv 312 me alice irc.example :Example server",
            ":srv 313 me alice :is an IRC operator",
            ":srv 317 me alice 42 1700000000 :seconds idle, signon time",
            ":srv 318 me alice :End of WHOIS",
        )) {
            assertTrue(state.feed(line).isEmpty(), line)
            assertEquals(presence, checkNotNull(state.user("alice")).presence, line)
            assertFalse(state.whoisExpected, line)
        }
    }

    @Test
    fun unassembledNumericsRemainVisibleAndDoNotSeedOrCompleteWhois() {
        for (numeric in listOf(302, 303, 304, 307, 308, 309, 310, 314, 316)) {
            for (requested in listOf(false, true)) {
                val state = state()
                state.whoisExpected = requested
                val effects = state.feed(":srv $numeric me bob :numeric payload")
                assertEquals(1, effects.size, "$numeric requested=$requested")
                val line = effects.appended().single()
                assertEquals(state.server, line.ref)
                assertEquals("bob numeric payload", line.text)
                assertEquals(requested, state.whoisExpected)
                assertNull(state.user("bob"))
                assertEquals("me", state.ownNick)

                state.whoisExpected = true
                assertTrue(state.feed(":srv 311 me alice ~a host * :Alice A").isEmpty())
                val info = state.feed(":srv 318 me alice :End of WHOIS").only<InboundEffect.WhoisCompleted>().info
                assertEquals(WhoisInfo(nick = "alice", user = "~a", host = "host", realName = "Alice A"), info)
                assertFalse(state.whoisExpected)
            }
        }
    }

    @Test
    fun listRepliesOnlyFlowWhileListing() {
        val state = state()
        assertTrue(state.feed(":srv 322 me #room 12 :topic").isEmpty())
        state.listingChannels = true
        assertEquals(LiveCoordinator.ChannelListEntry("#room", 12, "topic"), state.feed(":srv 322 me #room 12 :topic").only<InboundEffect.ChannelListed>().entry)
        assertEquals(listOf<InboundEffect>(InboundEffect.ChannelListFinished), state.feed(":srv 323 me :End of LIST"))
    }

    @Test
    fun isupportUpdatesCasemappingPrefixWhoxHistoryAndFilehost() {
        val state = state()
        state.feed(":srv 005 me CASEMAPPING=ascii PREFIX=(qov)~@+ WHOX CHATHISTORY=1000 soju.im/FILEHOST=https://up.example :are supported")
        assertEquals(CaseMapping.ASCII, state.casemapping)
        assertEquals(listOf('~', '@', '+'), state.prefixModes.symbols)
        assertTrue(state.hasWhox)
        assertEquals("https://up.example", state.filehostEndpoint)
    }

    @Test
    fun casemappingChangeRekeysTrackedChannels() {
        val state = state()
        state.joinChannelWith("#Room[1]", "me", "Al[ice]")
        state.feed(":srv 005 me CASEMAPPING=ascii :are supported")
        val key = ConversationRef.channel("net", "#Room[1]", CaseMapping.ASCII).storageKey
        assertEquals(listOf("Al[ice]", "me"), state.channel(key)?.memberList()?.map { it.nick })
        assertTrue(state.user("al[ice]") != null)
    }

    @Test
    fun bouncerNetIdBindsAndNetworkUpdatesBumpVersion() {
        val state = state()
        assertTrue(InboundEffect.BouncerNetworksChanged in state.feed(":srv 005 me BOUNCER_NETID=42 :are supported"))
        assertEquals("42", state.bouncerStore.boundNetId)
        assertEquals(listOf<InboundEffect>(InboundEffect.BouncerNetworksChanged), state.feed(":srv BOUNCER NETWORK 7 name=libera;state=connected"))
        assertEquals("soju mgmt: network created (9)", state.feed(":srv BOUNCER ADDNETWORK 9").appended().single().text)
    }


    @Test
    fun capNewUpdatesAdvertisedMultilineLimitsWithoutAcknowledgingCapabilities() {
        val state = state()
        val context = InboundContext(nowMs = now)
        assertTrue(state.feed(":srv CAP me NEW :draft/multiline=max-bytes=8192,max-lines=12").isEmpty())
        assertEquals(MultilineLimits(8192, 12), state.multilineLimits)
        assertTrue(state.supportedCaps.isEmpty())
        assertNull(state.outboundMultilineLimits())

        val caps = setOf("batch", "draft/multiline")
        assertTrue(state.apply(IrcEvent.CapabilitiesNegotiated(caps), context).isEmpty())
        assertEquals(MultilineLimits(8192, 12), state.outboundMultilineLimits())
        assertTrue(state.feed(":srv CAP me NEW :server-time draft/multiline=max-bytes=4096,max-lines=6").isEmpty())
        assertEquals(MultilineLimits(4096, 6), state.outboundMultilineLimits())
        assertEquals(caps, state.supportedCaps)
    }

    @Test
    fun saslFailurePreventsRegistrationAndJoinsUntilANewConnectionOpens() {
        val state = state(autojoin = listOf("#room"))
        val context = InboundContext(nowMs = now)
        val caps = setOf("batch", "sasl")
        state.apply(IrcEvent.CapabilitiesNegotiated(caps), context)
        assertTrue(state.apply(IrcEvent.SaslResult(SaslOutcome.Success), context).isEmpty())
        val effects = state.apply(IrcEvent.SaslResult(SaslOutcome.Failure(0, "Selected SASL mechanism is not available.")), context)
        assertTrue(effects.only<InboundEffect.AuthenticationFailed>().reason.contains("SASL"))
        assertTrue(effects.only<InboundEffect.AuthenticationFailed>().reason.contains("not available"))
        assertTrue(state.apply(IrcEvent.Registered("me", "Welcome"), context).isEmpty())
        assertFalse(state.registered)
        assertEquals(caps, state.supportedCaps)
        state.apply(IrcEvent.ConnectionOpened, context)
        assertEquals(listOf("JOIN #room"), state.apply(IrcEvent.Registered("me", "Welcome"), context).sent())
        assertTrue(state.registered)
    }

    @Test
    fun registrationJoinsAutojoinAndOpenChannelsOnce() {
        val state = state(autojoin = listOf("#a", "#b"))
        val context = InboundContext(nowMs = now, openChannels = { listOf("#b", "#c") })
        val effects = state.apply(IrcEvent.Registered("me_", "Welcome"), context)
        assertEquals(listOf("JOIN #a", "JOIN #b", "JOIN #c"), effects.sent())
        assertTrue(InboundEffect.StatusChanged(ConnectionStatus.REGISTERED) in effects)
        assertEquals("me_", state.ownNick)
        assertEquals(3, effects.filterIsInstance<InboundEffect.SetJoinState>().count { it.state == JoinState.JOINING })
        assertTrue(state.apply(IrcEvent.Registered("me_", "Duplicate welcome"), context).isEmpty())
    }

    @Test
    fun disconnectReportsDisconnectedAndReconnectResetsConnectionState() {
        val state = state()
        state.apply(IrcEvent.Registered("me", "Welcome"), InboundContext(nowMs = now))
        val epoch = state.connectionEpoch
        assertEquals(listOf<InboundEffect>(InboundEffect.StatusChanged(ConnectionStatus.DISCONNECTED)), state.apply(IrcEvent.Disconnected(null), InboundContext(nowMs = now)))
        assertFalse(state.registered)
        assertEquals(epoch + 1, state.connectionEpoch)
        state.feed(":srv BATCH +h chathistory #room")
        state.whoisExpected = true
        state.apply(IrcEvent.ConnectionOpened, InboundContext(nowMs = now))
        assertNull(state.batchType("h"))
        assertFalse(state.whoisExpected)
        assertFalse(state.feed("@batch=h :alice!u@h PRIVMSG #room :live").appended().single().playback)
    }
}
