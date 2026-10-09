package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientTagPolicyTests {
    @Test
    fun messageTagsMustBeNegotiatedEvenWithAllowExceptions() {
        val policy = ClientTagPolicy(setOf("server-time", "account-tag"), "*,-reply")
        assertFalse(policy.allows("+reply"))
        assertNull(policy.reply)
        assertFalse(policy.reactionsAvailable)
        assertTrue(policy.allows("label"))
    }

    @Test
    fun absentAndEmptyDenyAllowAllClientTags() {
        for (deny in listOf(null, "")) {
            val policy = ClientTagPolicy(setOf("message-tags"), deny)
            assertEquals("+reply", policy.reply)
            assertTrue(policy.reactionsAvailable)
        }
    }

    @Test
    fun catchAllAllowsOnlyExactNegatedExceptions() {
        val policy = ClientTagPolicy(setOf("message-tags"), "*,-draft/reply,-draft/react,-draft/unreact,-draft/refs,-typing")
        assertEquals("+draft/reply", policy.reply)
        assertEquals("+draft/react", policy.react)
        assertEquals("+draft/unreact", policy.unreact)
        assertEquals("+draft/refs", policy.refs)
        assertTrue(policy.reactionsAvailable)
        assertEquals("+typing", policy.typing)
        assertNull(policy.attachment)
        assertFalse(policy.allows("+draft/reply-extra"))
        assertFalse(policy.allows("+DRAFT/reply"))
    }

    @Test
    fun explicitDenialFallsBackWithoutTreatingTagNamesAsGlobs() {
        val policy = ClientTagPolicy(setOf("message-tags"), "reply,react,unreact,refs,typing,attachment,example/*")
        assertEquals("+draft/reply", policy.reply)
        assertEquals("+draft/react", policy.react)
        assertEquals("+draft/unreact", policy.unreact)
        assertEquals("+draft/refs", policy.refs)
        assertEquals("+draft/typing", policy.typing)
        assertEquals("+draft/attachment", policy.attachment)
        assertTrue(policy.allows("+example/foo"))
    }

    @Test
    fun missingReactionDependencyMakesFeatureUnavailable() {
        for (deny in listOf("draft/react", "draft/unreact", "reply,draft/reply,draft/refs,draft/msgids", "*")) {
            assertFalse(ClientTagPolicy(setOf("message-tags"), deny).reactionsAvailable)
        }
    }

    @Test
    fun workInProgressReactionsNeverInventForbiddenStableNames() {
        val policy = ClientTagPolicy(setOf("message-tags"), "*,-react,-unreact,-reply")
        assertNull(policy.react)
        assertNull(policy.unreact)
        assertFalse(policy.reactionsAvailable)
        val replyOnly = ClientTagPolicy(setOf("message-tags"), "*,-draft/react,-draft/unreact,-reply")
        assertTrue(replyOnly.reactionsAvailable)
        assertEquals("+reply", replyOnly.reactionReference)
        assertNull(replyOnly.refs)
    }
}
