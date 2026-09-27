package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.CaseMapping
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MentionMatcherTests {

    @Test
    fun matchesWholeTokenOnly() {
        assertTrue(MentionMatcher.containsMessage("hey alice!", "alice"))
        assertFalse(MentionMatcher.containsMessage("hey malice", "alice"))
        assertFalse(MentionMatcher.containsMessage("alicewhere are you", "alice"))
    }

    @Test
    fun matchesAcrossPunctuation() {
        assertTrue(MentionMatcher.containsMessage("alice: ping", "alice"))
        assertTrue(MentionMatcher.containsMessage("(alice)", "alice"))
        assertTrue(MentionMatcher.containsMessage("thanks,alice", "alice"))
    }

    @Test
    fun caseInsensitiveViaCasemapping() {
        assertTrue(MentionMatcher.containsMessage("ALICE hello", "alice", CaseMapping.ASCII))
        assertTrue(MentionMatcher.containsMessage("hey alice{}", "ALICE[]", CaseMapping.RFC1459))
        assertFalse(MentionMatcher.containsMessage("hey malice", "alice", CaseMapping.ASCII))
    }

    @Test
    fun nicksWithSymbolsMatch() {
        assertTrue(MentionMatcher.containsMessage("over to you alice_away", "alice_away"))
        assertTrue(MentionMatcher.containsMessage("|Alice| step up", "|Alice|"))
    }

    @Test
    fun emptyInputsNeverMatch() {
        assertFalse(MentionMatcher.containsMessage("", "alice"))
        assertFalse(MentionMatcher.containsMessage("hello alice", ""))
    }
}
