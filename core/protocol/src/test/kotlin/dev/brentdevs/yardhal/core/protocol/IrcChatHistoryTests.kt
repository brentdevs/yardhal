package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrcChatHistoryTests {

    private val defaultSupport = IrcChatHistory.support(ISupport.EMPTY, setOf("draft/chathistory"))

    @Test
    fun supportsCapabilityOnlyAndIsupportOnlyServers() {
        assertFalse(IrcChatHistory.support(ISupport.EMPTY, emptySet()).enabled)
        assertTrue(defaultSupport.enabled)
        assertEquals(100, defaultSupport.limit)
        assertTrue(IrcChatHistory.support(ISupport.parse(listOf("CHATHISTORY=50")), emptySet()).enabled)
        assertEquals(50, IrcChatHistory.support(ISupport.parse(listOf("CHATHISTORY=50")), emptySet()).limit)
    }

    @Test
    fun forwardedIsupportCannotEnableHistoryWithoutDownstreamCapability() {
        val forwarded = ISupport.parse(listOf("CHATHISTORY=50", "MSGREFTYPES=timestamp"))
        val unsupported = IrcChatHistory.support(forwarded, emptySet(), allowIsupportDiscovery = false)
        assertNull(IrcChatHistory.latest("#room", null, unsupported))
        val negotiated = IrcChatHistory.support(forwarded, setOf("draft/chathistory"), allowIsupportDiscovery = false)
        assertEquals("CHATHISTORY LATEST #room * 50", IrcChatHistory.latest("#room", null, negotiated))
    }

    @Test
    fun requestLimitsStayPositiveAndBoundedIncludingUnlimitedServers() {
        for ((token, expected) in listOf("1" to 1, "20" to 20, "1000" to 100, "0" to 100, "-1" to 100, "invalid" to 100)) {
            val support = IrcChatHistory.support(ISupport.parse(listOf("CHATHISTORY=$token")), emptySet())
            assertEquals(expected, support.limit)
            assertEquals("CHATHISTORY LATEST #room * $expected", IrcChatHistory.latest("#room", null, support))
        }
    }

    @Test
    fun referencesHonorServerPreferenceWithoutNormalizingOpaqueIds() {
        val msgidFirst = IrcChatHistory.support(
            ISupport.parse(listOf("MSGREFTYPES=unknown,msgid,timestamp,msgid")),
            setOf("draft/chathistory"),
        )
        assertEquals(listOf("msgid", "timestamp"), msgidFirst.referenceTypes)
        assertEquals("msgid=AbC-123", IrcChatHistory.reference(HistoryAnchor(1000, "AbC-123"), msgidFirst))
        assertEquals("timestamp=1970-01-01T00:00:01.000Z", IrcChatHistory.reference(HistoryAnchor(1000), msgidFirst))
        assertEquals("timestamp=1970-01-01T00:00:01.000Z", IrcChatHistory.reference(HistoryAnchor(1000, "AbC-123"), defaultSupport))
    }

    @Test
    fun timestampsAlwaysHaveExactlyThreeFractionalDigitsInUtc() {
        val expected = listOf(
            0L to "1970-01-01T00:00:00.000Z",
            1L to "1970-01-01T00:00:00.001Z",
            1200L to "1970-01-01T00:00:01.200Z",
            -1L to "1969-12-31T23:59:59.999Z",
        )
        for ((timestamp, iso) in expected) {
            assertEquals("timestamp=$iso", IrcChatHistory.reference(HistoryAnchor(timestamp), defaultSupport))
        }
        assertNull(IrcChatHistory.reference(HistoryAnchor(Long.MAX_VALUE), defaultSupport))
    }

    @Test
    fun latestMayBeUnanchoredButNeverSilentlyDropsAnUnsupportedAnchor() {
        val unsupportedReferences = IrcChatHistory.support(
            ISupport.parse(listOf("MSGREFTYPES=unknown")),
            setOf("draft/chathistory"),
        )
        assertEquals(emptyList(), unsupportedReferences.referenceTypes)
        assertNull(IrcChatHistory.reference(HistoryAnchor(1000, "m1"), unsupportedReferences))
        assertNull(IrcChatHistory.latest("bob", HistoryAnchor(1000, "m1"), unsupportedReferences))
        assertEquals("CHATHISTORY LATEST bob * 100", IrcChatHistory.latest("bob", null, unsupportedReferences))
    }

    @Test
    fun emptyAndFlagReferenceTokensDoNotUseMissingTokenDefaults() {
        for (token in listOf("MSGREFTYPES", "MSGREFTYPES=")) {
            val support = IrcChatHistory.support(ISupport.parse(listOf(token)), setOf("draft/chathistory"))
            assertEquals(emptyList(), support.referenceTypes)
            assertNull(IrcChatHistory.before("bob", HistoryAnchor(0, "m1"), support))
        }
        assertEquals(listOf("timestamp", "msgid"), defaultSupport.referenceTypes)
    }

    @Test
    fun beforePrefersMsgidToPreserveMessagesWithEqualTimestamps() {
        assertEquals("CHATHISTORY BEFORE #room msgid=m1 100", IrcChatHistory.before("#room", HistoryAnchor(1000, "m1"), defaultSupport))
        val timestampsOnly = defaultSupport.copy(referenceTypes = listOf("timestamp"))
        assertEquals(
            "CHATHISTORY BEFORE #room timestamp=1970-01-01T00:00:01.000Z 100",
            IrcChatHistory.before("#room", HistoryAnchor(1000, "m1"), timestampsOnly),
        )
        assertNull(IrcChatHistory.before("#room", HistoryAnchor(1000), defaultSupport.copy(referenceTypes = listOf("msgid"))))
    }

    @Test
    fun betweenAndTargetsKeepBothExclusiveSelectorsAndPermitReverseWindows() {
        assertEquals(
            "CHATHISTORY BETWEEN #room timestamp=1970-01-01T00:00:02.000Z timestamp=1970-01-01T00:00:01.000Z 100",
            IrcChatHistory.between("#room", HistoryAnchor(2000), HistoryAnchor(1000), defaultSupport),
        )
        assertEquals(
            "CHATHISTORY TARGETS timestamp=1970-01-01T00:00:02.000Z timestamp=1970-01-01T00:00:01.000Z 100",
            IrcChatHistory.targets(2000, 1000, defaultSupport.copy(referenceTypes = listOf("msgid"))),
        )
        assertNull(IrcChatHistory.between("bob", HistoryAnchor(0), HistoryAnchor(1000), defaultSupport.copy(referenceTypes = listOf("msgid"))))
    }

    @Test
    fun catchUpAndGapBoundariesPreserveTimestampTiesWhenMsgidsAreAvailable() {
        assertEquals(
            "CHATHISTORY LATEST bob msgid=m1 100",
            IrcChatHistory.latest("bob", HistoryAnchor(1000, "m1"), defaultSupport),
        )
        assertEquals(
            "CHATHISTORY BETWEEN bob msgid=m1 msgid=m2 100",
            IrcChatHistory.between("bob", HistoryAnchor(1000, "m1"), HistoryAnchor(1000, "m2"), defaultSupport),
        )
    }

    @Test
    fun unsafeTargetsAndMsgidsCannotInjectParametersOrLines() {
        for (target in listOf("", ":bob", "bob carol", "bob,carol", "bob\r\nJOIN #other", "bob\u0000", "bob\tcarol")) {
            assertNull(IrcChatHistory.latest(target, null, defaultSupport))
            assertNull(IrcChatHistory.before(target, HistoryAnchor(0, "m1"), defaultSupport))
            assertNull(IrcChatHistory.between(target, HistoryAnchor(0), HistoryAnchor(1), defaultSupport))
        }
        for (msgid in listOf("", ":m1", "m1 m2", "m1\r\nJOIN #other", "m1\u0000", "m1\tm2")) {
            val anchor = HistoryAnchor(1000, msgid)
            assertNull(IrcChatHistory.reference(anchor, defaultSupport.copy(referenceTypes = listOf("msgid"))))
            assertEquals("timestamp=1970-01-01T00:00:01.000Z", IrcChatHistory.reference(anchor, defaultSupport, preferMsgid = true))
        }
    }

    @Test
    fun disabledSupportAndNonpositiveLimitsNeverProduceCommands() {
        for (support in listOf(defaultSupport.copy(enabled = false), defaultSupport.copy(limit = 0), defaultSupport.copy(limit = -1))) {
            assertNull(IrcChatHistory.latest("bob", null, support))
            assertNull(IrcChatHistory.before("bob", HistoryAnchor(0), support))
            assertNull(IrcChatHistory.between("bob", HistoryAnchor(0), HistoryAnchor(1000), support))
            assertNull(IrcChatHistory.targets(0, 1000, support))
        }
    }
}
