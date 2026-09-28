package dev.brentdevs.yardhal.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StsTests {

    private val now = 1_700_000_000L

    @Test
    fun parsesPortAndDuration() {
        val policy = StsResolver.parseCapValue("port=6697,duration=31536000", now)!!
        assertEquals(6697, policy.port)
        assertEquals(now + 31_536_000, policy.expiresAtEpochSeconds)
    }

    @Test
    fun secureAdvertisementUsesCurrentPort() {
        assertEquals(6697, StsResolver.parseCapValue("duration=3600", now, currentPort = 6697)?.port)
        assertEquals(6697, StsResolver.parseUpgradePort("port=6697"))
    }

    @Test
    fun rejectsMissingOrInvalidRequiredValues() {
        assertNull(StsResolver.parseCapValue("port=6697", now))
        assertNull(StsResolver.parseCapValue("duration=3600", now))
        assertNull(StsResolver.parseCapValue("port=0,duration=3600", now))
        assertNull(StsResolver.parseCapValue("", now))
    }

    @Test
    fun storeRoundTrip() {
        val store = InMemoryStsPolicyStore()
        assertNull(store.load("Example.ORG"))
        val policy = StsPolicy(port = 6697, expiresAtEpochSeconds = now + 100)
        store.save("Example.ORG", policy)
        assertEquals(policy, store.load("example.org"))
        store.delete("EXAMPLE.ORG")
        assertNull(store.load("example.org"))
    }

    @Test
    fun plainConnectionUpgradesUnderActivePolicy() {
        val store = InMemoryStsPolicyStore()
        store.save("host", StsPolicy(6697, now + 1000))
        assertEquals(
            StsUpgradeDecision.UpgradeRequired(6697),
            StsResolver.decide(store, "host", 6667, tlsRequested = false, nowEpochSeconds = now),
        )
    }

    @Test
    fun tlsConnectionUpgradedToPolicyPort() {
        val store = InMemoryStsPolicyStore()
        store.save("host", StsPolicy(6697, now + 1000))
        val decision = StsResolver.decide(store, "host", 6667, tlsRequested = true, nowEpochSeconds = now)
        assertEquals(StsUpgradeDecision.UpgradeRequired(6697), decision)
    }

    @Test
    fun matchingPortProceeds() {
        val store = InMemoryStsPolicyStore()
        store.save("host", StsPolicy(6697, now + 1000))
        assertEquals(
            StsUpgradeDecision.ConnectAsConfigured,
            StsResolver.decide(store, "host", 6697, tlsRequested = true, nowEpochSeconds = now),
        )
    }

    @Test
    fun expiredPolicyDeletedAndIgnored() {
        val store = InMemoryStsPolicyStore()
        store.save("host", StsPolicy(6697, now - 1))
        assertEquals(
            StsUpgradeDecision.ConnectAsConfigured,
            StsResolver.decide(store, "host", 6667, tlsRequested = false, nowEpochSeconds = now),
        )
        assertNull(store.load("host"))
    }

    @Test
    fun unknownHostProceedsAsConfigured() {
        assertEquals(
            StsUpgradeDecision.ConnectAsConfigured,
            StsResolver.decide(InMemoryStsPolicyStore(), "fresh.host", 6667, tlsRequested = false, nowEpochSeconds = now),
        )
    }

    @Test
    fun zeroDurationDisablesPolicy() {
        assertTrue(StsResolver.parseCapValue("duration=0", now, currentPort = 6697)?.isExpired(now) == true)
    }

    @Test
    fun refreshesDurationWhenConnectionCloses() {
        val store = InMemoryStsPolicyStore()
        val policy = StsResolver.parseCapValue("duration=3600", now, currentPort = 6697) ?: error("missing policy")
        store.save("host", policy)
        StsResolver.refreshOnDisconnect(store, "host", now + 100)
        assertEquals(now + 3700, store.load("host")?.expiresAtEpochSeconds)
    }
}
