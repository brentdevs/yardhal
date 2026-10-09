package dev.brentdevs.yardhal.core.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineStoreTests {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun shell(ref: ConversationRef, at: Long, topic: String? = null, topicAt: Long? = null): OfflineConversationShell =
        OfflineConversationShell(ref, ref.rawTarget, at, topic = topic, topicObservedAtMs = topicAt)

    private suspend fun withStore(block: suspend (OfflineStore, OfflineDao) -> Unit) {
        val db = YardhalDatabase.inMemory(context)
        try {
            block(OfflineStore(db.offlineDao()), db.offlineDao())
        } finally {
            db.close()
        }
    }

    @Test
    fun durableReloadRestoresShellRosterWhoisProfileAndSelection() = runBlocking {
        val name = "offline-cache-reload.db"
        val ref = ConversationRef.channel("network", "#Yardhal")
        val presence = CachedPresence(away = true, awayMessage = "lunch", account = "alice", user = "user", host = "host", realName = "Alice")
        val user = CachedUser("Alice", presence, mapOf("display-name" to "Alice A", "avatar" to "https://example.org/alice.png"), 102)
        val info = WhoisInfo("Alice", user = "user", host = "host", realName = "Alice", account = "alice", channels = listOf("@#Yardhal"))
        context.deleteDatabase(name)
        try {
            val db = YardhalDatabase.build(context, name)
            try {
                val store = OfflineStore(db.offlineDao())
                store.saveConversation(shell(ref, 103, "cached topic", 100).copy(modes = mapOf("k" to listOf("secret"), "n" to emptyList()), modesObservedAtMs = 101),
                    OfflineRoster(listOf(ChannelMember("Alice", '@')), mapOf("Alice" to presence), 103, completeAtObservation = true))
                store.saveWhois("network", info, 101)
                store.saveUser("network", user)
                store.select(ref, 104)
            } finally {
                db.close()
            }
            val reopened = YardhalDatabase.build(context, name)
            try {
                val store = OfflineStore(reopened.offlineDao())
                val restored = store.shells().single()
                assertEquals(ref, restored.ref)
                assertEquals("cached topic", restored.topic)
                assertEquals(100L, restored.topicObservedAtMs)
                assertEquals(mapOf("k" to listOf("secret"), "n" to emptyList()), restored.modes)
                assertEquals(101L, restored.modesObservedAtMs)
                val roster = assertNotNull(store.roster(ref))
                assertEquals(listOf(ChannelMember("Alice", '@')), roster.members)
                assertEquals(presence, roster.presence["Alice"])
                assertEquals(103L, roster.memberObservedAtMs["Alice"])
                assertTrue(roster.completeAtObservation)
                assertEquals(CachedWhois(info, 101), store.whois("network", "ALICE"))
                assertEquals(user, store.user("network", "alice"))
                assertEquals(ref, store.selection())
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun newestFieldWinsAndUnknownFieldsDoNotEraseKnownSnapshots() = runBlocking {
        withStore { store, _ ->
            val ref = ConversationRef.channel("n", "#room")
            store.saveConversation(shell(ref, 30, "new", 30).copy(modes = mapOf("n" to emptyList()), modesObservedAtMs = 10),
                OfflineRoster(listOf(ChannelMember("alice")), observedAtMs = 20, completeAtObservation = true))
            store.saveConversation(shell(ref, 40, "old", 10).copy(modes = mapOf("t" to emptyList()), modesObservedAtMs = 20))
            store.saveConversation(shell(ref, 50))
            val restored = store.shells().single()
            assertEquals("new", restored.topic)
            assertEquals(30L, restored.topicObservedAtMs)
            assertEquals(mapOf("t" to emptyList()), restored.modes)
            assertEquals(listOf(ChannelMember("alice")), store.roster(ref)?.members)
            store.saveConversation(shell(ref, 50, null, 30).copy(modesObservedAtMs = 20))
            assertNull(store.shells().single().topic)
            assertTrue(store.shells().single().modes.isEmpty())
            store.saveWhois("n", WhoisInfo("alice", realName = "new"), 30)
            store.saveWhois("n", WhoisInfo("alice", realName = "old"), 20)
            store.saveUser("n", CachedUser("alice", metadata = mapOf("status" to "old"), observedAtMs = 20))
            store.saveUser("n", CachedUser("alice", observedAtMs = 20))
            assertEquals("new", store.whois("n", "alice")?.info?.realName)
            assertEquals(emptyMap(), store.user("n", "alice")?.metadata)
            assertEquals(30L, store.whois("n", "alice")?.observedAtMs)
        }
    }

    @Test
    fun partialRosterMergesButCompleteLiveRosterReplacesIncludingEmpty() = runBlocking {
        withStore { store, _ ->
            val ref = ConversationRef.channel("n", "#room")
            store.saveConversation(shell(ref, 10), OfflineRoster(listOf(ChannelMember("alice", '@')), observedAtMs = 10, completeAtObservation = true))
            store.saveConversation(shell(ref, 20), OfflineRoster(listOf(ChannelMember("bob")), observedAtMs = 20))
            val partial = assertNotNull(store.roster(ref))
            assertEquals(listOf("alice", "bob"), partial.members.map { it.nick })
            assertFalse(partial.completeAtObservation)
            assertEquals(mapOf("alice" to 10L, "bob" to 20L), partial.memberObservedAtMs)
            store.saveConversation(shell(ref, 20), OfflineRoster(listOf(ChannelMember("bob", '+')), observedAtMs = 20, completeAtObservation = true))
            assertEquals(listOf(ChannelMember("bob", '+')), store.roster(ref)?.members)
            store.saveConversation(shell(ref, 30), OfflineRoster(emptyList(), observedAtMs = 30, completeAtObservation = true))
            assertTrue(assertNotNull(store.roster(ref)).members.isEmpty())
            store.saveConversation(shell(ref, 5), OfflineRoster(listOf(ChannelMember("obsolete")), observedAtMs = 5, completeAtObservation = true))
            assertTrue(assertNotNull(store.roster(ref)).members.isEmpty())
        }
    }

    @Test
    fun renameCollisionMergesNewestFieldsRosterAndSelection() = runBlocking {
        withStore { store, _ ->
            val old = ConversationRef.channel("n", "#old")
            val new = ConversationRef.channel("n", "#new")
            store.saveConversation(shell(old, 20, "newer topic", 20),
                OfflineRoster(listOf(ChannelMember("Alice", '@')), observedAtMs = 20, completeAtObservation = true))
            store.saveConversation(shell(new, 10, "older topic", 10).copy(modes = mapOf("n" to emptyList()), modesObservedAtMs = 10),
                OfflineRoster(listOf(ChannelMember("alice", '+'), ChannelMember("Bob")), observedAtMs = 10, completeAtObservation = true))
            store.select(old, 21)
            store.renameConversation(old, new)
            assertEquals(new, store.selection())
            val restored = store.shells().single()
            assertEquals(new, restored.ref)
            assertEquals("newer topic", restored.topic)
            assertEquals(mapOf("n" to emptyList()), restored.modes)
            assertEquals(listOf(ChannelMember("Alice", '@'), ChannelMember("Bob")), store.roster(new)?.members)
            assertNull(store.roster(old))
            assertFalse(assertNotNull(store.roster(new)).completeAtObservation)
        }
    }

    @Test
    fun directRenameFollowsWhoisProfileAndRosterIdentity() = runBlocking {
        withStore { store, _ ->
            val old = ConversationRef.directMessage("n", "old")
            val new = ConversationRef.directMessage("n", "new")
            val channel = ConversationRef.channel("n", "#room")
            store.saveConversation(shell(old, 20))
            store.saveConversation(shell(channel, 10), OfflineRoster(listOf(ChannelMember("old")), mapOf("old" to CachedPresence(account = "a")), 10))
            store.saveWhois("n", WhoisInfo("old", account = "a"), 20)
            store.saveUser("n", CachedUser("old", metadata = mapOf("avatar" to "url"), observedAtMs = 10))
            store.saveWhois("n", WhoisInfo("new", account = "stale"), 5)
            store.select(old, 20)
            store.renameConversation(old, new)
            assertNull(store.whois("n", "old"))
            assertNull(store.user("n", "old"))
            assertEquals("a", store.whois("n", "new")?.info?.account)
            assertEquals("new", store.whois("n", "new")?.info?.nick)
            assertEquals("new", store.user("n", "new")?.nick)
            assertEquals(mapOf("avatar" to "url"), store.user("n", "new")?.metadata)
            assertEquals(listOf(ChannelMember("new")), store.roster(channel)?.members)
            assertEquals(setOf("new"), store.roster(channel)?.presence?.keys)
            assertEquals(new, store.selection())
        }
    }

    @Test
    fun nickCollisionKeepsNewestRosterAndPresenceObservation() = runBlocking {
        withStore { store, _ ->
            val ref = ConversationRef.channel("n", "#room")
            store.saveConversation(shell(ref, 20), OfflineRoster(
                members = listOf(ChannelMember("new", '@'), ChannelMember("old", '+')),
                presence = mapOf("new" to CachedPresence(account = "newer"), "old" to CachedPresence(account = "older")),
                observedAtMs = 20,
                memberObservedAtMs = mapOf("new" to 20L, "old" to 10L),
                presenceObservedAtMs = mapOf("new" to 20L, "old" to 10L),
            ))
            store.renameUser("n", "old", "new")
            val roster = assertNotNull(store.roster(ref))
            assertEquals(listOf(ChannelMember("new", '@')), roster.members)
            assertEquals("newer", roster.presence["new"]?.account)
            assertEquals(20L, roster.memberObservedAtMs["new"])
            assertEquals(20L, roster.presenceObservedAtMs["new"])
        }
    }

    @Test
    fun caseMappingRekeysCollisionsAndSelectionWithoutLosingNewestUserFields() = runBlocking {
        withStore { store, _ ->
            val left = ConversationRef.channel("n", "#[room]", CaseMapping.ASCII)
            val right = ConversationRef.channel("n", "#{room}", CaseMapping.ASCII)
            store.saveConversation(shell(left, 20, "new", 20).copy(caseMapping = CaseMapping.ASCII),
                OfflineRoster(listOf(ChannelMember("[alice]", '@')), observedAtMs = 20, completeAtObservation = true))
            store.saveConversation(shell(right, 10, "old", 10).copy(caseMapping = CaseMapping.ASCII),
                OfflineRoster(listOf(ChannelMember("{alice}", '+'), ChannelMember("bob")), observedAtMs = 10, completeAtObservation = true))
            store.saveWhois("n", WhoisInfo("[alice]", realName = "new"), 20, CaseMapping.ASCII)
            store.saveUser("n", CachedUser("{alice}", metadata = mapOf("status" to "here"), observedAtMs = 10), CaseMapping.ASCII)
            store.select(left, 20)
            store.rekeyNetwork("n", CaseMapping.RFC1459)
            val restored = store.shells().single()
            assertEquals(CaseMapping.RFC1459, restored.caseMapping)
            assertEquals("#{room}", restored.ref.normalizedTarget)
            assertEquals("new", restored.topic)
            assertEquals(restored.ref.normalizedTarget, store.selection()?.normalizedTarget)
            assertEquals(listOf(ChannelMember("bob"), ChannelMember("[alice]", '@')).sortedBy { CaseMapping.RFC1459.fold(it.nick) }, store.roster(restored.ref)?.members)
            assertEquals("new", store.whois("n", "{alice}")?.info?.realName)
            assertEquals(mapOf("status" to "here"), store.user("n", "[alice]")?.metadata)
        }
    }

    @Test
    fun retentionKeepsExactBoundaryAndExpiresFieldsIndependently() = runBlocking {
        withStore { store, _ ->
            val now = OfflineCachePolicy.RETENTION_MS + 100
            val expired = ConversationRef.channel("n", "#expired")
            val boundary = ConversationRef.channel("n", "#boundary")
            val current = ConversationRef.channel("n", "#current")
            store.saveConversation(shell(expired, 99))
            store.saveConversation(shell(boundary, 100, "boundary", 100))
            store.saveConversation(shell(current, 99, "expired", 99).copy(modesObservedAtMs = 99),
                OfflineRoster(listOf(ChannelMember("old")), observedAtMs = 99))
            store.saveConversation(shell(current, now), OfflineRoster(listOf(ChannelMember("new")), observedAtMs = now))
            store.saveWhois("n", WhoisInfo("alice"), 99)
            store.saveUser("n", CachedUser("alice", observedAtMs = now))
            store.saveWhois("n", WhoisInfo("bob"), 100)
            store.select(expired, now)
            store.maintain(now)
            assertEquals(setOf(boundary, current), store.shells().map { it.ref }.toSet())
            assertEquals("boundary", store.shells().first { it.ref == boundary }.topic)
            assertNull(store.shells().first { it.ref == current }.topicObservedAtMs)
            assertNull(store.shells().first { it.ref == current }.modesObservedAtMs)
            assertEquals(listOf(ChannelMember("new"), ChannelMember("old")), store.roster(current)?.members)
            assertNull(store.whois("n", "alice"))
            assertNotNull(store.user("n", "alice"))
            assertNotNull(store.whois("n", "bob"))
            assertNull(store.selection())
            store.maintain(now + 1)
            assertEquals(listOf(current), store.shells().map { it.ref })
            assertNull(store.whois("n", "bob"))
        }
    }

    @Test
    fun freshnessUsesStrictBoundaryAndNeverTreatsFutureObservationAsFresh() {
        val whois = CachedWhois(WhoisInfo("alice"), 100)
        assertFalse(whois.isFresh(99))
        assertTrue(whois.isFresh(100))
        assertTrue(whois.isFresh(100 + OfflineCachePolicy.WHOIS_FRESHNESS_MS - 1))
        assertFalse(whois.isFresh(100 + OfflineCachePolicy.WHOIS_FRESHNESS_MS))
        assertFalse(CachedWhois(WhoisInfo("alice"), Long.MIN_VALUE).isFresh(Long.MAX_VALUE))
    }

    @Test
    fun maintenanceCapsEachNetworkDeterministicallyAndKeepsSelectionWithinBudget() = runBlocking {
        withStore { store, dao ->
            val count = OfflineCachePolicy.MAX_CONVERSATIONS_PER_NETWORK + 2
            for (i in 0 until count) {
                val target = "#${i.toString().padStart(4, '0')}"
                dao.putConversation(OfflineConversationRow("n", target, target, ConversationKind.CHANNEL.name, target,
                    CaseMapping.RFC1459.wireName, 100, null, null, "{}", null, null, null))
                dao.putUser(WhoisCacheRow("n", "user${i.toString().padStart(4, '0')}", "user$i", CaseMapping.RFC1459.wireName,
                    100, "{\"nick\":\"user$i\"}", 100, null, null))
            }
            val other = ConversationRef.channel("other", "#room")
            store.saveConversation(shell(other, 100))
            store.saveWhois("other", WhoisInfo("alice"), 100)
            store.select(ConversationRef.channel("n", "#1001"), 100)
            store.maintain(100)
            assertEquals(OfflineCachePolicy.MAX_CONVERSATIONS_PER_NETWORK, store.shells("n").size)
            assertEquals("#1001", store.shells("n").first().ref.rawTarget)
            assertEquals("#0998", store.shells("n").last().ref.rawTarget)
            assertEquals(OfflineCachePolicy.MAX_USERS_PER_NETWORK, dao.userRows("n").size)
            assertEquals("user0999", dao.userRows("n").last().nick)
            assertEquals(listOf(other), store.shells("other").map { it.ref })
            assertNotNull(store.whois("other", "alice"))
            assertEquals(ConversationRef.channel("n", "#1001"), store.selection())
            store.saveConversation(shell(ConversationRef.channel("n", "#zzzz"), 101))
            store.saveWhois("n", WhoisInfo("znew"), 101)
            assertEquals(OfflineCachePolicy.MAX_CONVERSATIONS_PER_NETWORK, store.shells("n").size)
            assertEquals(OfflineCachePolicy.MAX_CONVERSATIONS_PER_NETWORK + 1, dao.conversationRows("n").size)
            store.maintain(101)
            assertEquals(OfflineCachePolicy.MAX_CONVERSATIONS_PER_NETWORK, store.shells("n").size)
            assertEquals(OfflineCachePolicy.MAX_USERS_PER_NETWORK, dao.userRows("n").size)
            assertTrue(store.shells("n").any { it.ref.rawTarget == "#zzzz" })
            assertNotNull(store.whois("n", "znew"))
            assertEquals(ConversationRef.channel("n", "#1001"), store.selection())
        }
    }

    @Test
    fun oversizedCompleteRosterReplacesOldSnapshotAndReportsTruncation() = runBlocking {
        withStore { store, _ ->
            val ref = ConversationRef.channel("n", "#room")
            store.saveConversation(shell(ref, 1), OfflineRoster(listOf(ChannelMember("old")), observedAtMs = 1))
            val members = (0..OfflineCachePolicy.MAX_ROSTER_ENTRIES).map { ChannelMember("user${it.toString().padStart(5, '0')}") }
            store.saveConversation(shell(ref, 2), OfflineRoster(members, observedAtMs = 2, completeAtObservation = true))
            val restored = assertNotNull(store.roster(ref))
            assertEquals(OfflineCachePolicy.MAX_ROSTER_ENTRIES, restored.members.size)
            assertTrue(restored.truncated)
            assertTrue(restored.completeAtObservation)
            assertFalse(restored.members.any { it.nick == "old" })
        }
    }

    @Test
    fun conversationAndNetworkDeletionFollowSelectionWithoutDeletingOtherNetworks() = runBlocking {
        withStore { store, _ ->
            val first = ConversationRef.channel("n1", "#room")
            val second = ConversationRef.channel("n2", "#room")
            store.saveConversation(shell(first, 1))
            store.saveConversation(shell(second, 1))
            store.saveWhois("n1", WhoisInfo("alice"), 1)
            store.saveWhois("n2", WhoisInfo("alice"), 1)
            store.select(first, 1)
            store.deleteConversation(first)
            assertNull(store.selection())
            assertNotNull(store.whois("n1", "alice"))
            store.select(second, 2)
            store.deleteNetwork("n1")
            assertEquals(second, store.selection())
            assertNull(store.whois("n1", "alice"))
            assertNotNull(store.whois("n2", "alice"))
            store.deleteNetwork("n2")
            assertNull(store.selection())
            assertTrue(store.shells().isEmpty())
            assertNull(store.whois("n2", "alice"))
        }
    }

    @Test
    fun malformedMetadataQuarantinesExactOriginalsAndLiveWritesHealFields() = runBlocking {
        StorageRecovery.clearSessionNotices()
        withStore { store, dao ->
            val network = "metadata-corruption"
            val good = ConversationRef.channel(network, "#good")
            val badModesRef = ConversationRef.channel(network, "#bad-modes")
            val badRosterRef = ConversationRef.channel(network, "#bad-roster")
            val expired = ConversationRef.channel(network, "#expired")
            val badModes = OfflineConversationRow(network, badModesRef.normalizedTarget, badModesRef.rawTarget,
                ConversationKind.CHANNEL.name, badModesRef.rawTarget, CaseMapping.RFC1459.wireName,
                0, "original topic", 0, "{invalid", 0, null, null)
            val badRoster = badModes.copy(conversation = badRosterRef.normalizedTarget, rawTarget = badRosterRef.rawTarget,
                displayName = badRosterRef.rawTarget, modesJson = "{}", rosterJson = "{invalid", rosterObservedAtMs = 0)
            val badUser = WhoisCacheRow(network, "broken", "broken", CaseMapping.RFC1459.wireName,
                0, "{invalid", 0, """{"nick":"broken","metadata":{"status":"retained"},"observedAtMs":0}""", 0)
            val badProfile = WhoisCacheRow(network, "profile", "profile", CaseMapping.RFC1459.wireName,
                0, """{"nick":"profile","realName":"retained"}""", 0, "{invalid", 0)
            dao.putConversation(badModes)
            dao.putConversation(badRoster)
            dao.putUser(badUser)
            dao.putUser(badProfile)
            store.saveConversation(shell(good, 20, "usable", 20))
            store.saveConversation(shell(expired, 10))
            store.saveWhois(network, WhoisInfo("healthy", realName = "usable"), 20)
            assertEquals(setOf(good, badModesRef, badRosterRef, expired), store.shells(network).map { it.ref }.toSet())
            val healedModes = store.shells(network).first { it.ref == badModesRef }
            assertEquals("original topic", healedModes.topic)
            assertNull(healedModes.modesObservedAtMs)
            assertTrue(healedModes.modes.isEmpty())
            assertNull(store.roster(badRosterRef))
            assertNull(store.whois(network, "broken"))
            assertEquals(mapOf("status" to "retained"), store.user(network, "broken")?.metadata)
            assertNull(store.user(network, "profile"))
            assertEquals("retained", store.whois(network, "profile")?.info?.realName)
            val originalEvidence = dao.evidence()
            assertEquals(4, originalEvidence.size)
            assertEquals(badModes, OfflineCodec.json.decodeFromString<OfflineConversationRow>(
                originalEvidence.single { it.identity == badModesRef.normalizedTarget }.payloadJson))
            assertEquals(badRoster, OfflineCodec.json.decodeFromString<OfflineConversationRow>(
                originalEvidence.single { it.identity == badRosterRef.normalizedTarget }.payloadJson))
            assertEquals(badUser, OfflineCodec.json.decodeFromString<WhoisCacheRow>(
                originalEvidence.single { it.identity == "broken" }.payloadJson))
            assertEquals(badProfile, OfflineCodec.json.decodeFromString<WhoisCacheRow>(
                originalEvidence.single { it.identity == "profile" }.payloadJson))
            dao.putConversation(badModes)
            store.shells(network)
            assertEquals(originalEvidence, dao.evidence())
            store.saveConversation(shell(badModesRef, 30, "replacement", 30).copy(
                modes = mapOf("n" to emptyList()), modesObservedAtMs = 30))
            store.saveConversation(shell(badRosterRef, 30), OfflineRoster(listOf(ChannelMember("live")),
                observedAtMs = 30, completeAtObservation = true))
            store.saveWhois(network, WhoisInfo("broken", realName = "live"), 30)
            store.saveUser(network, CachedUser("profile", metadata = mapOf("status" to "live"), observedAtMs = 30))
            store.maintain(OfflineCachePolicy.RETENTION_MS + 20)
            store.rekeyNetwork(network, CaseMapping.ASCII)
            assertEquals("replacement", store.shells(network).first { it.ref == badModesRef }.topic)
            assertEquals(mapOf("n" to emptyList()), store.shells(network).first { it.ref == badModesRef }.modes)
            assertEquals(listOf(ChannelMember("live")), store.roster(badRosterRef)?.members)
            assertEquals("live", store.whois(network, "broken", CaseMapping.ASCII)?.info?.realName)
            assertEquals(mapOf("status" to "live"), store.user(network, "profile", CaseMapping.ASCII)?.metadata)
            assertNull(dao.conversation(network, expired.normalizedTarget))
            assertEquals(originalEvidence, dao.evidence())
            store.maintain(OfflineCachePolicy.RETENTION_MS + 20)
            store.maintain(OfflineCachePolicy.RETENTION_MS + 20)
            assertEquals(originalEvidence, dao.evidence())
            assertEquals(originalEvidence.map { OfflineEvidenceNoticeRow(it.networkId, it.kind, it.identity) }.toSet(),
                dao.evidenceNotices().toSet())
            assertEquals(4, StorageRecovery.notices.value.size)
            assertTrue(StorageRecovery.notices.value.all { it.quarantinePath == null && !it.temporary })
            val brokenSelection = LaunchSelectionRow(networkId = network, conversation = "#missing", rawTarget = "#missing",
                kind = "invalid", observedAtMs = 0)
            dao.putSelection(brokenSelection)
            assertNull(store.selection())
            assertNull(dao.selection())
            assertEquals(brokenSelection, OfflineCodec.json.decodeFromString<LaunchSelectionRow>(
                dao.evidence().single { it.kind == "selection" }.payloadJson))
            store.maintain(OfflineCachePolicy.RETENTION_MS * 2)
            assertTrue(store.shells(network).isEmpty())
            assertEquals(5, dao.evidence().size)
        }
    }

    @Test
    fun quarantineEvidencePersistsAcrossReloadWithoutRetainingBadActiveMetadata() = runBlocking {
        val name = "offline-quarantine-reload.db"
        val ref = ConversationRef.channel("quarantine-reload", "#room")
        val original = OfflineConversationRow(ref.networkId, ref.normalizedTarget, ref.rawTarget,
            ConversationKind.CHANNEL.name, ref.rawTarget, CaseMapping.RFC1459.wireName,
            100, "retained topic", 100, "{invalid", 100, null, null)
        context.deleteDatabase(name)
        try {
            val db = YardhalDatabase.build(context, name)
            val evidence: OfflineQuarantineRow
            try {
                db.offlineDao().putConversation(original)
                val restored = OfflineStore(db.offlineDao()).shells().single()
                assertEquals("retained topic", restored.topic)
                assertTrue(restored.modes.isEmpty())
                evidence = db.offlineDao().evidence().single()
            } finally {
                db.close()
            }
            StorageRecovery.clearSessionNotices()
            assertTrue(StorageRecovery.notices.value.isEmpty())
            val reopened = YardhalDatabase.build(context, name)
            try {
                val store = OfflineStore(reopened.offlineDao())
                assertEquals(evidence, reopened.offlineDao().evidence().single())
                assertEquals(original, OfflineCodec.json.decodeFromString<OfflineConversationRow>(evidence.payloadJson))
                assertEquals(listOf(OfflineEvidenceNoticeRow(ref.networkId, "conversation", ref.normalizedTarget)),
                    reopened.offlineDao().evidenceNotices())
                assertNull(store.shells().single().modesObservedAtMs)
                assertEquals(1, StorageRecovery.notices.value.size)
                assertTrue(StorageRecovery.notices.value.all { it.quarantinePath == null && !it.temporary })
                store.saveConversation(shell(ref, 200).copy(modes = mapOf("n" to emptyList()), modesObservedAtMs = 200))
                assertEquals(mapOf("n" to emptyList()), store.shells().single().modes)
                assertEquals(evidence, reopened.offlineDao().evidence().single())
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun failedEvidenceInsertionCannotClearOriginalCachedFields() = runBlocking {
        val db = YardhalDatabase.inMemory(context)
        try {
            val original = OfflineConversationRow("quarantine-failure", "#room", "#room", ConversationKind.CHANNEL.name,
                "#room", CaseMapping.RFC1459.wireName, 100, "retained topic", 100, "{invalid", 100, null, null)
            db.offlineDao().putConversation(original)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER deny_offline_evidence BEFORE INSERT ON offline_quarantine BEGIN " +
                    "SELECT RAISE(ABORT, 'fixture evidence insertion failed'); END",
            )
            assertFailsWith<SQLiteException> { OfflineStore(db.offlineDao()).shells() }
            assertEquals(original, db.offlineDao().conversation(original.networkId, original.conversation))
            assertTrue(db.offlineDao().evidence().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun shellHydrationIsBoundedNewestFirstWithSelectionPriorityAndLazyRoster() = runBlocking {
        withStore { store, dao ->
            val network = "bounded-shells"
            val count = OfflineCachePolicy.MAX_CONVERSATIONS_PER_NETWORK + 5
            for (i in 0 until count) {
                val target = "#${i.toString().padStart(4, '0')}"
                dao.putConversation(OfflineConversationRow(network, target, target, ConversationKind.CHANNEL.name, target,
                    CaseMapping.RFC1459.wireName, i.toLong(), null, null, "{}", null, "{unread roster", i.toLong()))
            }
            val selected = ConversationRef.channel(network, "#0000")
            store.select(selected, 10000)
            val restored = store.shells(network)
            assertEquals(OfflineCachePolicy.MAX_CONVERSATIONS_PER_NETWORK, restored.size)
            assertEquals(selected, restored.first().ref)
            assertEquals("#1004", restored[1].ref.rawTarget)
            assertEquals(listOf("#0000", "#1004", "#1003"), store.shells(network, 3).map { it.ref.rawTarget })
            assertEquals(OfflineCachePolicy.MAX_CONVERSATIONS_PER_NETWORK, store.shells(network, Int.MAX_VALUE).size)
            assertTrue(store.shells(network, 0).isEmpty())
            val outsideBudget = ConversationRef.channel(network, "#0001")
            assertFalse(restored.any { it.ref == outsideBudget })
            assertEquals(outsideBudget, store.shell(outsideBudget)?.ref)
            assertTrue(dao.evidence().isEmpty())
            store.select(null, 10001)
            assertEquals(listOf("#1004", "#1003", "#1002"), store.shells(network, 3).map { it.ref.rawTarget })
            assertNull(store.roster(selected))
            assertEquals(1, dao.evidence().size)
        }
    }

    @Test
    fun sqlMaintenanceDoesNotDecodeOpaqueRostersBeforeNormalPolicyExpiry() = runBlocking {
        withStore { store, dao ->
            val network = "opaque-maintenance"
            val retained = ConversationRef.channel(network, "#retained")
            val expired = ConversationRef.channel(network, "#expired")
            val retainedRow = OfflineConversationRow(network, retained.normalizedTarget, retained.rawTarget,
                ConversationKind.CHANNEL.name, retained.rawTarget, CaseMapping.RFC1459.wireName,
                100, null, null, "{}", null, "{opaque unread roster", 100)
            dao.putConversation(retainedRow)
            dao.putConversation(retainedRow.copy(conversation = expired.normalizedTarget, rawTarget = expired.rawTarget,
                displayName = expired.rawTarget, observedAtMs = 99, rosterObservedAtMs = 99))
            store.maintain(OfflineCachePolicy.RETENTION_MS + 100)
            assertEquals(retainedRow, dao.conversation(network, retained.normalizedTarget))
            assertNull(dao.conversation(network, expired.normalizedTarget))
            assertTrue(dao.evidence().isEmpty())
            assertEquals(retained, store.shell(retained)?.ref)
            assertTrue(dao.evidence().isEmpty())
            assertNull(store.roster(retained))
            assertEquals(retainedRow, OfflineCodec.json.decodeFromString<OfflineConversationRow>(dao.evidence().single().payloadJson))
            assertNull(dao.conversation(network, retained.normalizedTarget)?.rosterJson)
            store.maintain(OfflineCachePolicy.RETENTION_MS + 101)
            assertNull(dao.conversation(network, retained.normalizedTarget))
            assertEquals(1, dao.evidence().size)
        }
    }
}
