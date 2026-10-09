package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelayParserTests {
    @Test
    fun ordinarySenderCannotMasqueradeAsConfiguredRelay() {
        assertNull(RelayParser.parse("alice", "<admin> trusted", "bridge", RelayFormat.ANGLE))
        assertNull(RelayParser.parse("bridge-extra", "<admin> trusted", "bridge", RelayFormat.ANGLE))
        assertNull(RelayParser.parse("bridge", "<admin> trusted", "*", RelayFormat.ANGLE))
    }

    @Test
    fun formatsRequireExactBoundariesAndRetainWireSource() {
        assertEquals(RelayedMessage("alice", "hello: <bob>", "Bridge"),
            RelayParser.parse("Bridge", "<alice> hello: <bob>", "bridge", RelayFormat.ANGLE))
        assertEquals(RelayedMessage("alice@matrix", "hello", "bridge"),
            RelayParser.parse("bridge", "[alice@matrix] hello", "bridge", RelayFormat.BRACKET))
        assertEquals(RelayedMessage("alice", "hello", "bridge"),
            RelayParser.parse("bridge", "alice: hello", "bridge", RelayFormat.COLON))
        assertNull(RelayParser.parse("bridge", "prefix <alice> hello", "bridge", RelayFormat.ANGLE))
        assertNull(RelayParser.parse("bridge", "<alice>hello", "bridge", RelayFormat.ANGLE))
        assertNull(RelayParser.parse("bridge", "[alice] hello", "bridge", RelayFormat.ANGLE))
    }

    @Test
    fun invalidOrUnboundedIdentityAndControlBodyAreRejected() {
        for (body in listOf("<> hello", "<two words> hello", "<alice> ", "<${"a".repeat(65)}> hello", "<a\u0001b> hello", "<alice> hi\rspoof", "<<admin>> hello")) {
            assertNull(RelayParser.parse("bridge", body, "bridge", RelayFormat.ANGLE))
        }
    }

    @Test
    fun senderMatchingUsesNetworkCasemapping() {
        assertEquals("alice", RelayParser.parse("bridge[", "<alice> hi", "bridge{", RelayFormat.ANGLE)?.sender)
        assertNull(RelayParser.parse("bridge[", "<alice> hi", "bridge{", RelayFormat.ANGLE, CaseMapping.ASCII))
    }
}
