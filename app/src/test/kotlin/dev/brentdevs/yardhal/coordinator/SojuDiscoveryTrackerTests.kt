package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.protocol.IrcBouncerNetworks
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SojuDiscoveryTrackerTests {
    private fun SojuDiscoveryTracker.feed(line: String, generation: Long = 1): List<SojuDiscoveryChange> =
        receive(generation, requireNotNull(IrcMessage.parse(line)))

    @Test fun duplicatePushesAndPartialDeltasMergeWithoutInventingDeletion() {
        val tracker = SojuDiscoveryTracker().also { it.begin(1) }
        val first = assertIs<SojuDiscoveryChange.Upsert>(tracker.feed(":srv BOUNCER NETWORK 42 :name=Libera;nickname=alice").single())
        val duplicate = assertIs<SojuDiscoveryChange.Upsert>(tracker.feed(":srv BOUNCER NETWORK 42 :name=Libera;nickname=alice").single())
        assertEquals(first, duplicate)
        val delta = assertIs<SojuDiscoveryChange.Upsert>(tracker.feed(":srv BOUNCER NETWORK 42 :state=connected").single())
        assertEquals("Libera", delta.attributes.name)
        assertEquals("alice", delta.attributes.nickname)
        assertEquals(IrcBouncerNetworks.State.CONNECTED, delta.attributes.state)
        assertEquals(listOf(SojuDiscoveryChange.Delete("42")), tracker.feed(":srv BOUNCER NETWORK 42 *"))
    }

    @Test fun restoredAbsentChildrenArePrunedOnlyByCompleteListingNotPartialPushes() {
        val tracker = SojuDiscoveryTracker().also {
            it.begin(1, mapOf("old" to IrcBouncerNetworks.Attributes(name = "Offline")))
        }
        assertIs<SojuDiscoveryChange.Upsert>(tracker.feed(":srv BOUNCER NETWORK new :name=New").single())
        assertTrue(tracker.feed(":srv BATCH +listing soju.im/bouncer-networks").isEmpty())
        assertIs<SojuDiscoveryChange.Upsert>(tracker.feed("@batch=listing :srv BOUNCER NETWORK new :name=New").single())
        assertTrue(tracker.feed(":srv BATCH -unknown").isEmpty())
        assertEquals(listOf(SojuDiscoveryChange.Snapshot(setOf("new"))), tracker.feed(":srv BATCH -listing"))
        val delta = assertIs<SojuDiscoveryChange.Upsert>(tracker.feed(":srv BOUNCER NETWORK old :state=connected").single())
        assertEquals(null, delta.attributes.name)
    }

    @Test fun interruptedSnapshotsAndStaleGenerationsCannotPruneRestoredChildren() {
        val tracker = SojuDiscoveryTracker().also { it.begin(1) }
        tracker.feed(":srv BATCH +old-list soju.im/bouncer-networks")
        tracker.feed("@batch=old-list :srv BOUNCER NETWORK 1 :name=Old")
        tracker.begin(2, mapOf("saved" to IrcBouncerNetworks.Attributes(name = "Offline")))
        assertTrue(tracker.feed(":srv BATCH -old-list", 1).isEmpty())
        assertTrue(tracker.feed(":srv BOUNCER NETWORK saved *", 1).isEmpty())
        assertTrue(tracker.feed(":srv BATCH -old-list", 2).isEmpty())
        val retained = assertIs<SojuDiscoveryChange.Upsert>(tracker.feed(":srv BOUNCER NETWORK saved :state=connected", 2).single())
        assertEquals("Offline", retained.attributes.name)
    }

    @Test fun missingAttributesCannotDeleteRestoredNetworkOrPruneItFromMalformedSnapshot() {
        val tracker = SojuDiscoveryTracker().also { it.begin(1, mapOf("saved" to IrcBouncerNetworks.Attributes(name = "Retained"))) }
        assertTrue(tracker.feed(":srv BOUNCER NETWORK saved").isEmpty())
        assertTrue(tracker.feed(":srv BOUNCER NETWORK saved * unexpected").isEmpty())
        tracker.feed(":srv BATCH +listing soju.im/bouncer-networks")
        assertTrue(tracker.feed("@batch=listing :srv BOUNCER NETWORK saved").isEmpty())
        tracker.feed("@batch=listing :srv BOUNCER NETWORK other :name=Other")
        assertTrue(tracker.feed(":srv BATCH -listing").isEmpty())
        val retained = assertIs<SojuDiscoveryChange.Upsert>(tracker.feed(":srv BOUNCER NETWORK saved :state=connected").single())
        assertEquals("Retained", retained.attributes.name)
    }

    @Test fun malformedOrIncompleteSnapshotNeverPrunesAnyChildren() {
        val tracker = SojuDiscoveryTracker().also { it.begin(1) }
        tracker.feed(":srv BATCH +listing soju.im/bouncer-networks")
        tracker.feed("@batch=listing :srv BOUNCER NETWORK")
        assertTrue(tracker.feed(":srv BATCH -listing").isEmpty())
        tracker.feed(":srv BATCH +listing soju.im/bouncer-networks")
        tracker.feed("@batch=listing :srv BATCH +nested labeled-response")
        assertTrue(tracker.feed(":srv BATCH -listing").isEmpty())
        assertTrue(tracker.feed(":srv BATCH -nested").isEmpty())
        tracker.feed(":srv BATCH +good soju.im/bouncer-networks")
        assertEquals(listOf(SojuDiscoveryChange.Snapshot(emptySet())), tracker.feed(":srv BATCH -good"))
    }

    @Test fun concurrentUnbatchedDeltasAreRetainedAndExplicitDeletesStayDeletedAtSnapshotEnd() {
        val tracker = SojuDiscoveryTracker().also { it.begin(1) }
        tracker.feed(":srv BATCH +listing soju.im/bouncer-networks")
        tracker.feed("@batch=listing :srv BOUNCER NETWORK 1 :name=First")
        tracker.feed(":srv BOUNCER NETWORK 2 :name=Concurrent")
        tracker.feed(":srv BOUNCER NETWORK 1 *")
        assertEquals(listOf(SojuDiscoveryChange.Snapshot(setOf("2"))), tracker.feed(":srv BATCH -listing"))
    }

    @Test fun concurrentIndependentLiveBatchDeltasSurviveListingClosureWithoutRevivingDeletes() {
        val tracker = SojuDiscoveryTracker().also { it.begin(1) }
        tracker.feed(":srv BATCH +listing soju.im/bouncer-networks")
        tracker.feed("@batch=listing :srv BOUNCER NETWORK 1 :name=First")
        tracker.feed(":srv BATCH +independent labeled-response")
        tracker.feed("@batch=independent :srv BOUNCER NETWORK 2 :name=Concurrent")
        tracker.feed("@batch=independent :srv BOUNCER NETWORK 1 *")
        tracker.feed(":srv BATCH -independent")
        assertEquals(listOf(SojuDiscoveryChange.Snapshot(setOf("2"))), tracker.feed(":srv BATCH -listing"))
        val retained = assertIs<SojuDiscoveryChange.Upsert>(tracker.feed(":srv BOUNCER NETWORK 2 :state=connected").single())
        assertEquals("Concurrent", retained.attributes.name)
    }

    @Test fun playbackNestedDiscoveryAndUserOriginatedFramesCannotCreateOrRemoveLiveUpstreams() {
        val tracker = SojuDiscoveryTracker().also { it.begin(1) }
        tracker.feed(":srv BATCH +history chathistory #room")
        tracker.feed("@batch=history :srv BATCH +listing soju.im/bouncer-networks")
        assertTrue(tracker.feed("@batch=listing :srv BOUNCER NETWORK 1 :name=Historical").isEmpty())
        assertTrue(tracker.feed("@batch=listing :srv BOUNCER NETWORK live *").isEmpty())
        assertTrue(tracker.feed(":srv BATCH -listing").isEmpty())
        tracker.feed(":srv BATCH -history")
        assertTrue(tracker.feed("@draft/chathistory-context :srv BOUNCER NETWORK 1 :name=Context").isEmpty())
        assertTrue(tracker.feed(":attacker!u@h BOUNCER NETWORK 1 :name=Injected").isEmpty())
        assertTrue(tracker.feed("@batch=unknown :srv BOUNCER NETWORK live *").isEmpty())
    }

    @Test fun overlappingSnapshotsAndUndeclaredBatchFramesCannotClaimGenuineAbsence() {
        val tracker = SojuDiscoveryTracker().also { it.begin(1) }
        tracker.feed(":srv BATCH +first soju.im/bouncer-networks")
        tracker.feed(":srv BATCH +second soju.im/bouncer-networks")
        tracker.feed("@batch=first :srv BOUNCER NETWORK 1 :name=First")
        tracker.feed("@batch=second :srv BOUNCER NETWORK 2 :name=Second")
        assertTrue(tracker.feed(":srv BATCH -first").isEmpty())
        assertTrue(tracker.feed(":srv BATCH -second").isEmpty())
        tracker.feed(":srv BATCH +listing soju.im/bouncer-networks")
        tracker.feed("@batch=undeclared :srv BOUNCER NETWORK 3 :name=Partial")
        assertTrue(tracker.feed(":srv BATCH -listing").isEmpty())
    }
}
