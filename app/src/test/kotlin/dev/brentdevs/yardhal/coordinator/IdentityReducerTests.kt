package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.client.IrcEvent
import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityReducerTests {

    private val context = InboundContext(nowMs = 1000L)

    private val allCaps = setOf(
        "away-notify",
        "account-notify",
        "account-tag",
        "extended-join",
        "setname",
        "chghost",
        "userhost-in-names",
        "invite-notify",
        "extended-monitor",
        "message-tags",
    )

    private fun network(caps: Set<String> = allCaps): PerNetworkState {
        val state = PerNetworkState("net", "me")
        state.apply(IrcEvent.CapabilitiesNegotiated(caps), context)
        return state
    }

    private fun PerNetworkState.feed(line: String): List<InboundEffect> =
        apply(IrcEvent.MessageReceived(checkNotNull(IrcMessage.parse(line))), context)

    private fun PerNetworkState.joinRoom(names: String = "me alice bob"): PerNetworkState {
        feed(":me!u@h JOIN #c")
        feed(":srv 353 me = #c :$names")
        feed(":srv 366 me #c :End of NAMES")
        return this
    }

    private fun PerNetworkState.presenceOf(nick: String): PresenceState = checkNotNull(user(nick)).presence

    private fun List<InboundEffect>.memberSnapshots(): List<InboundEffect.SetMembers> =
        filterIsInstance<InboundEffect.SetMembers>()

    private fun List<InboundEffect>.lines(): List<InboundEffect.AppendMessage> =
        filterIsInstance<InboundEffect.AppendMessage>()

    @Test
    fun awayNotifyTracksAwayAndBackAndRepublishesMembers() {
        val state = network().joinRoom()
        val away = state.feed(":alice!a@host AWAY :gone to lunch")
        val snapshot = away.memberSnapshots().single()
        assertEquals("#c", snapshot.ref.rawTarget)
        assertEquals(true, snapshot.presence["alice"]?.away)
        assertEquals("gone to lunch", snapshot.presence["alice"]?.awayMessage)
        assertTrue(away.lines().isEmpty())

        val back = state.feed(":alice!a@host AWAY")
        assertEquals(false, back.memberSnapshots().single().presence["alice"]?.away)
        assertNull(state.presenceOf("alice").awayMessage)
    }

    @Test
    fun presenceEventsFromUntrackedUsersAreIgnored() {
        val state = network().joinRoom()
        assertTrue(state.feed(":stranger!s@h AWAY :brb").isEmpty())
        assertTrue(state.feed(":stranger!s@h ACCOUNT acct").isEmpty())
        assertNull(state.user("stranger"))
    }

    @Test
    fun accountNotifyUpdatesAccount() {
        val state = network().joinRoom()
        val login = state.feed(":alice!a@host ACCOUNT alice-acct")
        assertEquals("alice-acct", login.memberSnapshots().single().presence["alice"]?.account)
        state.feed(":alice!a@host ACCOUNT *")
        assertNull(state.presenceOf("alice").account)
    }

    @Test
    fun accountTagFeedsSenderAccountAndPresence() {
        val state = network().joinRoom()
        val tagged = state.feed("@account=alice-acct :alice!a@host PRIVMSG #c :hi")
        assertEquals("alice-acct", tagged.lines().single().senderAccount)
        assertEquals("alice-acct", state.presenceOf("alice").account)
        assertEquals("alice-acct", tagged.memberSnapshots().single().presence["alice"]?.account)

        val untagged = state.feed(":alice!a@host PRIVMSG #c :logged out")
        assertNull(untagged.lines().single().senderAccount)
        assertNull(state.presenceOf("alice").account)
    }

    @Test
    fun accountTagAbsenceKeepsAccountWhenCapNotAcked() {
        val state = network(allCaps - "account-tag").joinRoom()
        for (command in listOf("PRIVMSG #c :hi", "NOTICE #c :hi", "TAGMSG #c")) {
            state.feed(":alice!a@host ACCOUNT alice-acct")
            state.feed(":alice!a@host $command")
            assertEquals("alice-acct", state.presenceOf("alice").account, command)
        }
    }

    @Test
    fun knownAccountsSurviveUntaggedIdentityNotificationsAndRemainAvailableForAccountBans() {
        for (establishAccount in listOf(
            ":alice!a@host ACCOUNT alice-acct",
            ":alice!a@host JOIN #c alice-acct :Alice Real",
        )) {
            val state = network().joinRoom()
            state.feed(":srv 005 me EXTBAN=~,a ACCOUNTEXTBAN=a :are supported")
            state.feed(establishAccount)
            for (command in listOf(
                "AWAY :gone to lunch",
                "CHGHOST changed changed.host",
                "SETNAME :Alice New Name",
                "BATCH +identity vendor.example/identity",
                "BATCH -identity",
            )) {
                val effects = state.feed(":alice!a@host $command")
                assertEquals("alice-acct", state.presenceOf("alice").account, "$establishAccount then $command")
                assertEquals("~a:alice-acct", state.accountBanMask("alice"), command)
                for (snapshot in effects.memberSnapshots()) {
                    assertEquals("alice-acct", snapshot.presence["alice"]?.account, command)
                }
            }
            val renamed = state.feed(":alice!a@host NICK :alicia")
            assertNull(state.user("alice"))
            assertEquals("alice-acct", state.presenceOf("alicia").account)
            assertEquals("alice-acct", renamed.memberSnapshots().single().presence["alicia"]?.account)
            assertEquals("~a:alice-acct", state.accountBanMask("alicia"))
        }
    }

    @Test
    fun untaggedChatClearsKnownAccountOnlyWhenAccountTagIsAcknowledged() {
        val state = network().joinRoom()
        for (command in listOf("PRIVMSG #c :logged out", "NOTICE #c :logged out", "TAGMSG #c")) {
            state.feed(":alice!a@host ACCOUNT alice-acct")
            val effects = state.feed(":alice!a@host $command")
            assertNull(state.presenceOf("alice").account, command)
            assertNull(effects.memberSnapshots().single().presence["alice"]?.account, command)
            for (line in effects.lines()) assertNull(line.senderAccount, command)
        }
    }

    @Test
    fun explicitAccountTagsUpdateNonChatIdentityEvenWithoutAccountTagCapability() {
        val state = network(allCaps - "account-tag").joinRoom()
        for ((index, command) in listOf(
            "AWAY :away",
            "CHGHOST changed changed.host",
            "SETNAME :Alice Real",
            "BATCH +identity vendor.example/identity",
            "BATCH -identity",
        ).withIndex()) {
            val account = "account-$index"
            val effects = state.feed("@account=$account :alice!a@host $command")
            assertEquals(account, state.presenceOf("alice").account, command)
            assertEquals(account, effects.memberSnapshots().single().presence["alice"]?.account, command)
        }
        val renamed = state.feed("@account=renamed-account :alice!a@host NICK :alicia")
        assertEquals("renamed-account", state.presenceOf("alicia").account)
        assertTrue(renamed.memberSnapshots().all { it.presence["alicia"]?.account == "renamed-account" })
        state.feed("@account=* :alicia!a@host AWAY")
        assertNull(state.presenceOf("alicia").account)
        state.feed("@account=restored :alicia!a@host SETNAME :Alice")
        state.feed("@account :alicia!a@host AWAY :away")
        assertNull(state.presenceOf("alicia").account)
    }

    @Test
    fun extendedJoinCapturesAccountAndRealname() {
        val state = network().joinRoom()
        val effects = state.feed(":carol!~c@carol.host JOIN #c carol-acct :Carol Q. Real")
        val presence = state.presenceOf("carol")
        assertEquals("carol-acct", presence.account)
        assertEquals("Carol Q. Real", presence.realName)
        assertEquals("~c", presence.user)
        assertEquals("carol.host", presence.host)
        assertEquals(false, presence.away)
        assertEquals("carol-acct", effects.memberSnapshots().single().presence["carol"]?.account)

        state.feed(":dave!d@h JOIN #c * :Dave")
        assertNull(state.presenceOf("dave").account)
        assertEquals("Dave", state.presenceOf("dave").realName)
    }

    @Test
    fun plainJoinWithoutAwayNotifyLeavesAwayUnknown() {
        val state = network(emptySet()).joinRoom()
        state.feed(":erin!e@h JOIN #c")
        assertNull(state.presenceOf("erin").away)
    }

    @Test
    fun setnameUpdatesRealnameSilentlyForOthers() {
        val state = network().joinRoom()
        val effects = state.feed(":alice!a@host SETNAME :Alice Liddell")
        assertEquals("Alice Liddell", state.presenceOf("alice").realName)
        assertTrue(effects.lines().isEmpty())
        assertEquals("Alice Liddell", effects.memberSnapshots().single().presence["alice"]?.realName)
    }

    @Test
    fun ownSetnameIsConfirmedInServerBuffer() {
        val state = network().joinRoom()
        val line = state.feed(":me!u@h SETNAME :My New Name").lines().single()
        assertEquals(ConversationKind.SERVER, line.ref.kind)
        assertTrue(line.text.contains("My New Name"))
        assertEquals("My New Name", state.presenceOf("me").realName)
    }

    @Test
    fun chghostUpdatesUserAndHostWithoutTranscriptLine() {
        val state = network().joinRoom()
        val effects = state.feed(":alice!a@old.host CHGHOST newuser new.host")
        assertTrue(effects.lines().isEmpty())
        assertEquals("newuser", state.presenceOf("alice").user)
        assertEquals("new.host", state.presenceOf("alice").host)
        assertEquals("new.host", effects.memberSnapshots().single().presence["alice"]?.host)
    }

    @Test
    fun userhostInNamesCapturesUserAndHost() {
        val state = network().joinRoom("@me!u@h alice!~al@alice.example +bob!b@bob.example")
        assertEquals("~al", state.presenceOf("alice").user)
        assertEquals("alice.example", state.presenceOf("alice").host)
        assertEquals("bob.example", state.presenceOf("bob").host)
        assertEquals(listOf("alice", "bob", "me"), state.channel(state.channelRef("#c").storageKey)?.memberList()?.map { it.nick })
        assertNull(state.presenceOf("alice").away)
    }

    @Test
    fun inviteToUsLandsInServerBuffer() {
        val state = network().joinRoom()
        val line = state.feed(":op!o@h INVITE me #secret").lines().single()
        assertEquals(ConversationKind.SERVER, line.ref.kind)
        assertEquals("✉ op invited you to #secret", line.text)
    }

    @Test
    fun inviteNotifyForOthersLandsInChannelBuffer() {
        val state = network().joinRoom()
        val line = state.feed(":op!o@h INVITE alice #c").lines().single()
        assertEquals("#c", line.ref.rawTarget)
        assertEquals("✉ op invited alice to #c", line.text)
    }

    @Test
    fun invitingNumericConfirmsInChannel() {
        val state = network().joinRoom()
        val line = state.feed(":srv 341 me zed #c").lines().single()
        assertEquals("#c", line.ref.rawTarget)
        assertEquals("✉ Invited zed to #c", line.text)
    }

    @Test
    fun botModeTokenAndWhoFlagsMarkBots() {
        val state = network().joinRoom()
        val features = state.feed(":srv 005 me BOT=B WHOX :are supported")
        assertTrue(InboundEffect.NetworkFeaturesChanged in features)
        assertEquals('B', state.botModeLetter)

        state.feed(":srv 352 me #c ~a alice.host srv alice HB :0 Alice Bot")
        state.feed(":srv 352 me #c ~b bob.host srv bob G :0 Bob Human")
        state.feed(":srv 315 me #c :End of WHO")
        assertTrue(state.presenceOf("alice").isBot)
        assertEquals("Alice Bot", state.presenceOf("alice").realName)
        assertFalse(state.presenceOf("bob").isBot)
        assertEquals(true, state.presenceOf("bob").away)

        state.feed(":srv 354 me #c ~b bob.host bob HB bob-acct :Bob Now Bot")
        assertTrue(state.presenceOf("bob").isBot)
        assertEquals("bob-acct", state.presenceOf("bob").account)
        assertEquals(false, state.presenceOf("bob").away)
    }

    @Test
    fun whoFlagsIgnoredForBotWithoutBotToken() {
        val state = network().joinRoom()
        state.feed(":srv 352 me #c ~a alice.host srv alice HB :0 Alice")
        assertFalse(state.presenceOf("alice").isBot)
    }

    @Test
    fun whoisBotNumericAndBotTagMarkBots() {
        val state = network().joinRoom()
        state.feed(":srv 335 me alice :is a Bot on IRCv3")
        assertTrue(state.presenceOf("alice").isBot)
        state.feed("@bot :bob!b@h PRIVMSG #c :beep")
        assertTrue(state.presenceOf("bob").isBot)
    }

    @Test
    fun whoxRepliesCaptureFullIdentity() {
        val state = network().joinRoom()
        val effects = state.feed(":srv 354 me #c ~al alice.host alice H@ alice-acct :Alice Real")
        val presence = state.presenceOf("alice")
        assertEquals("~al", presence.user)
        assertEquals("alice.host", presence.host)
        assertEquals("alice-acct", presence.account)
        assertEquals("Alice Real", presence.realName)
        assertEquals(false, presence.away)
        assertEquals("alice-acct", effects.memberSnapshots().first().presence["alice"]?.account)
        state.feed(":srv 354 me #c ~al alice.host alice H 0 :Alice Real")
        assertNull(state.presenceOf("alice").account)
    }

    @Test
    fun extendedMonitorTracksMonitoredNicksOutsideChannels() {
        val state = network().joinRoom()
        val online = state.feed(":srv 730 me :zed!z@zed.host")
        assertEquals("[monitor] zed!z@zed.host", online.lines().single().text)
        assertTrue(state.isMonitored("zed"))
        assertEquals("zed.host", state.presenceOf("zed").host)

        val away = state.feed(":zed!z@zed.host AWAY :afk")
        assertTrue(away.memberSnapshots().isEmpty())
        assertEquals(true, state.presenceOf("zed").away)
        state.feed("@account=zed-acct :zed!z@zed.host ACCOUNT zed-acct")
        state.feed("@account=zed-acct :zed!z@zed.host CHGHOST z2 other.host")
        state.feed("@account=zed-acct :zed!z2@other.host SETNAME :Zed Real")
        val presence = state.presenceOf("zed")
        assertEquals("zed-acct", presence.account)
        assertEquals("other.host", presence.host)
        assertEquals("Zed Real", presence.realName)

        val offline = state.feed(":srv 731 me :zed")
        assertEquals("[monitor] zed", offline.lines().single().text)
        assertNull(state.user("zed"))
        assertTrue(state.isMonitored("zed"))

        state.forgetMonitored(listOf("zed"))
        assertFalse(state.isMonitored("zed"))
        assertTrue(state.feed(":zed!z@zed.host AWAY :afk").isEmpty())
    }

    @Test
    fun monitorListNumericEstablishesTargetsForSubsequentIdentityNotifications() {
        val state = network()
        val listed = state.feed(":srv 732 me :zed,alice")
        assertEquals("zed,alice", listed.lines().single().text)
        assertTrue(state.isMonitored("ZED"))
        assertTrue(state.isMonitored("ALICE"))
        assertNull(state.user("zed"))
        assertNull(state.user("alice"))

        assertTrue(state.feed(":zed!z@zed.host ACCOUNT zed-acct").isEmpty())
        assertTrue(state.feed(":zed!z@zed.host AWAY :afk").isEmpty())
        assertTrue(state.feed(":zed!z@zed.host CHGHOST z2 other.host").isEmpty())
        assertTrue(state.feed(":zed!z2@other.host SETNAME :Zed Real").isEmpty())
        assertEquals(
            PresenceState(
                away = true,
                awayMessage = "afk",
                account = "zed-acct",
                user = "z2",
                host = "other.host",
                realName = "Zed Real",
            ),
            state.presenceOf("zed"),
        )
        assertTrue(state.feed(":alice!a@alice.host AWAY :brb").isEmpty())
        assertEquals(true, state.presenceOf("alice").away)
        assertNull(state.user("stranger"))
    }

    @Test
    fun partingKeepsMonitoredUserState() {
        val state = network().joinRoom()
        state.feed(":srv 730 me :alice!a@host")
        state.feed(":alice!a@host PART #c")
        assertTrue(state.user("alice") != null)
        state.feed(":bob!b@host PART #c")
        assertNull(state.user("bob"))
    }

    @Test
    fun accountExtbanBuildsBanMaskFromKnownAccount() {
        val state = network().joinRoom()
        val features = state.feed(":srv 005 me EXTBAN=~,a ACCOUNTEXTBAN=a :are supported")
        assertTrue(InboundEffect.NetworkFeaturesChanged in features)
        assertNull(state.accountBanMask("alice"))
        state.feed(":alice!a@host ACCOUNT alice-acct")
        assertEquals("~a:alice-acct", state.accountBanMask("alice"))
        assertNull(state.accountBanMask("bob"))
    }

    @Test
    fun accountExtbanUnavailableWithoutToken() {
        val state = network().joinRoom()
        state.feed(":alice!a@host ACCOUNT alice-acct")
        assertNull(state.accountExtban)
        assertNull(state.accountBanMask("alice"))
    }

    @Test
    fun reconnectClearsMonitorAndIsupportFeatures() {
        val state = network().joinRoom()
        state.feed(":srv 005 me BOT=B EXTBAN=~,a ACCOUNTEXTBAN=a :are supported")
        state.feed(":srv 730 me :zed!z@zed.host")
        state.apply(IrcEvent.ConnectionOpened, context)
        assertNull(state.botModeLetter)
        assertNull(state.accountExtban)
        assertFalse(state.isMonitored("zed"))
    }
}
