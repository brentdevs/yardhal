package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.client.IrcEvent
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.MetadataCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataReducerTests {
    private val context = InboundContext(nowMs = 1000L)

    private fun PerNetworkState.feed(line: String): List<InboundEffect> =
        apply(IrcEvent.MessageReceived(requireNotNull(IrcMessage.parse(line))), context)

    private fun negotiated(capValue: String? = null): PerNetworkState {
        val state = PerNetworkState("net", "me", autojoin = listOf("#c"))
        state.apply(IrcEvent.ConnectionOpened, context)
        val values = capValue?.let { mapOf("draft/metadata-2" to it) }.orEmpty()
        state.apply(IrcEvent.CapabilitiesNegotiated(setOf("batch", "draft/metadata-2"), values), context)
        return state
    }

    private fun List<InboundEffect>.rawLines(): List<String> = filterIsInstance<InboundEffect.SendRaw>().map { it.line }

    private fun List<InboundEffect>.profiles(): NetworkProfiles? =
        filterIsInstance<InboundEffect.ProfilesChanged>().lastOrNull()?.profiles

    private fun List<InboundEffect>.serverText(): List<String> =
        filterIsInstance<InboundEffect.AppendMessage>().map { it.text }

    @Test
    fun capabilityValueIsParsed() {
        val state = negotiated("before-connect,max-subs=25,max-keys=10,max-value-bytes=300,unknown=1")
        assertEquals(MetadataCapability(maxSubs = 25, maxKeys = 10, maxValueBytes = 300, beforeConnect = true), state.metadataCapability)
    }

    @Test
    fun registrationSubscribesBeforeJoining() {
        val state = negotiated("max-subs=50")
        val lines = state.apply(IrcEvent.Registered("me", "Welcome"), context).rawLines()
        assertEquals(listOf("METADATA * SUB avatar display-name", "JOIN #c"), lines)
    }

    @Test
    fun registrationRespectsMaxSubs() {
        val state = negotiated("max-subs=1")
        val lines = state.apply(IrcEvent.Registered("me", "Welcome"), context).rawLines()
        assertEquals("METADATA * SUB avatar", lines.first())
        val none = negotiated("max-subs=0").apply(IrcEvent.Registered("me", "Welcome"), context).rawLines()
        assertTrue(none.none { it.startsWith("METADATA") })
    }

    @Test
    fun noSubscriptionWithoutCapability() {
        val state = PerNetworkState("net", "me")
        state.apply(IrcEvent.CapabilitiesNegotiated(setOf("batch")), context)
        val lines = state.apply(IrcEvent.Registered("me", "Welcome"), context).rawLines()
        assertTrue(lines.none { it.startsWith("METADATA") })
        assertNull(state.metadataCapability)
    }

    @Test
    fun capNewAfterRegistrationSubscribes() {
        val state = PerNetworkState("net", "me")
        state.apply(IrcEvent.CapabilitiesNegotiated(setOf("batch")), context)
        state.apply(IrcEvent.Registered("me", "Welcome"), context)
        val lines = state.apply(IrcEvent.CapabilitiesNegotiated(setOf("batch", "draft/metadata-2")), context).rawLines()
        assertEquals(listOf("METADATA * SUB avatar display-name"), lines)
    }

    @Test
    fun subscriptionNumericsTrackKeys() {
        val state = negotiated()
        state.feed(":srv 770 me avatar display-name")
        assertEquals(setOf("avatar", "display-name"), state.metadataSubscriptions)
        state.feed(":srv 771 me display-name")
        assertEquals(setOf("avatar"), state.metadataSubscriptions)
        val effects = state.feed(":srv 772 me avatar website")
        assertEquals(setOf("avatar", "website"), state.metadataSubscriptions)
        assertEquals(listOf("[metadata] subscribed: avatar website"), effects.serverText())
    }

    @Test
    fun metadataMessageStoresCasemappedUserProfile() {
        val state = negotiated()
        val effects = state.feed(":srv METADATA Alice[m] avatar * :https://example.com/a.png")
        assertEquals("https://example.com/a.png", state.user("alice{M}")?.metadata?.get("avatar"))
        val profiles = effects.profiles()
        assertEquals(UserProfile(avatarUrl = "https://example.com/a.png"), profiles?.forNick("ALICE[m]"))
        state.feed(":srv METADATA alice[m] display-name * :Alice Liddell")
        assertEquals("Alice Liddell", state.user("Alice[M]")?.profile?.displayName)
    }

    @Test
    fun metadataWithoutValueDeletesKey() {
        val state = negotiated()
        state.feed(":srv METADATA bob display-name * :Bob")
        val effects = state.feed(":srv METADATA bob display-name *")
        assertNull(state.user("bob")?.metadata?.get("display-name"))
        assertNull(effects.profiles()?.forNick("bob"))
    }

    @Test
    fun invalidKeysAreIgnored() {
        val state = negotiated()
        val effects = state.feed(":srv METADATA bob \$url * :https://example.com")
        assertNull(state.user("bob"))
        assertTrue(effects.none { it is InboundEffect.ProfilesChanged })
    }

    @Test
    fun channelMetadataIsStoredOnJoinedChannels() {
        val state = negotiated()
        state.feed(":me!u@h JOIN #c")
        state.feed(":srv METADATA #c avatar * :https://example.com/c.png")
        assertEquals("https://example.com/c.png", state.channel(state.channelRef("#c").storageKey)?.metadataValue("avatar"))
        state.feed(":srv METADATA #unknown avatar * :https://example.com/x.png")
        assertNull(state.channel(state.channelRef("#unknown").storageKey))
    }

    @Test
    fun metadataBatchPublishesProfilesOnceAtBatchEnd() {
        val state = negotiated()
        state.feed(":me!u@h JOIN #c")
        state.feed(":srv 353 me = #c :me alice bob")
        state.feed(":srv 366 me #c :End of /NAMES list.")
        assertTrue(state.feed(":srv BATCH +m1 metadata #c").none { it is InboundEffect.ProfilesChanged })
        val inside = state.feed("@batch=m1 :srv METADATA alice avatar * :https://example.com/alice.png") +
            state.feed("@batch=m1 :srv METADATA bob display-name * :Bobby")
        assertTrue(inside.none { it is InboundEffect.ProfilesChanged })
        val end = state.feed(":srv BATCH -m1")
        val published = end.filterIsInstance<InboundEffect.ProfilesChanged>()
        assertEquals(1, published.size)
        val profiles = published.single().profiles
        assertEquals("https://example.com/alice.png", profiles.forNick("alice")?.avatarUrl)
        assertEquals("Bobby", profiles.forNick("BOB")?.displayName)
    }

    @Test
    fun registrationBatchCarriesOwnMetadata() {
        val state = negotiated()
        state.apply(IrcEvent.Registered("me", "Welcome"), context)
        state.feed(":srv BATCH +1 metadata me")
        state.feed("@batch=1 :srv METADATA me display-name * :Myself")
        val profiles = state.feed(":srv BATCH -1").profiles()
        assertEquals("Myself", profiles?.forNick("ME")?.displayName)
    }

    @Test
    fun quitAndNickChangesRepublishProfiles() {
        val state = negotiated()
        state.feed(":alice!u@h JOIN #c")
        state.feed(":srv METADATA alice avatar * :https://example.com/alice.png")
        val renamed = state.feed(":alice!u@h NICK :alicia").profiles()
        assertNull(renamed?.forNick("alice"))
        assertEquals("https://example.com/alice.png", renamed?.forNick("alicia")?.avatarUrl)
        val quit = state.feed(":alicia!u@h QUIT :bye").profiles()
        assertEquals(emptyMap(), quit?.byFoldedNick)
    }

    @Test
    fun keyValueNumericStoresAndReports() {
        val state = negotiated()
        val effects = state.feed(":srv 761 me * display-name * :Me Myself")
        assertEquals("Me Myself", state.user("me")?.profile?.displayName)
        assertEquals(listOf("[metadata] * display-name = Me Myself"), effects.serverText())
        assertEquals(state.server, effects.filterIsInstance<InboundEffect.AppendMessage>().single().ref)
    }

    @Test
    fun keyNotSetNumericClearsValue() {
        val state = negotiated()
        state.feed(":srv 761 me bob avatar * :https://example.com/b.png")
        val effects = state.feed(":srv 766 me bob avatar :key not set")
        assertNull(state.user("bob")?.profile)
        assertEquals(listOf("[metadata] bob avatar is not set"), effects.serverText())
    }

    @Test
    fun whoisKeyValueIsStored() {
        val state = negotiated()
        state.feed(":srv 760 me carol avatar * :https://example.com/c.png")
        assertEquals("https://example.com/c.png", state.user("carol")?.profile?.avatarUrl)
    }

    @Test
    fun syncLaterSchedulesRetryWithRetryAfter() {
        val state = negotiated()
        val effects = state.feed(":srv 774 me #bigchan 4")
        assertEquals(
            listOf(InboundEffect.ScheduleRaw("METADATA #bigchan SYNC", 4_000L, state.connectionEpoch)),
            effects.filterIsInstance<InboundEffect.ScheduleRaw>(),
        )
        assertTrue(effects.rawLines().isEmpty())
    }

    @Test
    fun syncLaterWithoutRetryAfterUsesDefaultAndClampsHugeValues() {
        val state = negotiated()
        val fallback = state.feed(":srv 774 me #bigchan").filterIsInstance<InboundEffect.ScheduleRaw>().single()
        assertEquals(DEFAULT_METADATA_SYNC_DELAY_MS, fallback.delayMs)
        val clamped = state.feed(":srv 774 me #bigchan 999999999").filterIsInstance<InboundEffect.ScheduleRaw>().single()
        assertEquals(MAX_METADATA_SYNC_DELAY_MS, clamped.delayMs)
    }

    @Test
    fun reconnectAdvancesEpochAndClearsMetadata() {
        val state = negotiated()
        state.feed(":srv METADATA alice avatar * :https://example.com/a.png")
        val epoch = state.connectionEpoch
        val effects = state.apply(IrcEvent.ConnectionOpened, context)
        assertEquals(epoch + 1, state.connectionEpoch)
        assertNull(state.user("alice")?.profile)
        assertEquals(emptyMap(), effects.profiles()?.byFoldedNick)
        assertNull(state.metadataCapability)
    }

    @Test
    fun failMetadataIsReportedInServerBuffer() {
        val state = negotiated()
        val effects = state.feed(":srv FAIL METADATA KEY_NO_PERMISSION bob secret :permission denied") +
            state.feed(":srv FAIL METADATA VALUE_INVALID :value is too long")
        assertEquals(
            listOf(
                "[metadata] KEY_NO_PERMISSION (bob secret): permission denied",
                "[metadata] VALUE_INVALID: value is too long",
            ),
            effects.serverText(),
        )
    }

    @Test
    fun casemappingChangeRekeysProfiles() {
        val state = negotiated()
        state.feed(":srv METADATA Dan^ avatar * :https://example.com/d.png")
        assertEquals("https://example.com/d.png", state.user("dan~")?.profile?.avatarUrl)
        val profiles = state.feed(":srv 005 me CASEMAPPING=ascii :are supported").profiles()
        assertEquals(CaseMapping.ASCII, profiles?.casemapping)
        assertEquals("https://example.com/d.png", profiles?.forNick("DAN^")?.avatarUrl)
        assertNull(profiles?.forNick("dan~"))
    }

    @Test
    fun iconIsupportTokenSetsAndClearsNetworkIcon() {
        val state = PerNetworkState("net", "me")
        val set = state.feed(":srv 005 me NETWORK=Example draft/ICON=https://example.org/icon.png?size={size}\\x20x :are supported")
        assertEquals("https://example.org/icon.png?size={size} x", state.networkIconUrl)
        assertEquals(listOf(InboundEffect.NetworkIconChanged("https://example.org/icon.png?size={size} x")), set.filterIsInstance<InboundEffect.NetworkIconChanged>())
        assertTrue(state.feed(":srv 005 me draft/ICON=https://example.org/icon.png?size={size}\\x20x :are supported").none { it is InboundEffect.NetworkIconChanged })
        val cleared = state.feed(":srv 005 me -draft/ICON :are supported")
        assertNull(state.networkIconUrl)
        assertEquals(listOf(InboundEffect.NetworkIconChanged(null)), cleared.filterIsInstance<InboundEffect.NetworkIconChanged>())
    }

    @Test
    fun displayNameIsSanitizedForProfiles() {
        val profile = UserProfile.from(mapOf("display-name" to "  Eve\u0007\nAdams  ", "avatar" to " "))
        assertEquals(UserProfile(avatarUrl = null, displayName = "EveAdams"), profile)
        assertNull(UserProfile.from(mapOf("website" to "https://example.com")))
    }
}
