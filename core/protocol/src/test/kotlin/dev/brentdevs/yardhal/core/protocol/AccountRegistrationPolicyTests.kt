package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountRegistrationPolicyTests {

    @Test
    fun missingValueMeansNoOptionalFeatures() {
        assertEquals(AccountRegistrationPolicy(), AccountRegistrationPolicy.parse(null))
        assertEquals(AccountRegistrationPolicy(), AccountRegistrationPolicy.parse(""))
    }

    @Test
    fun parsesKnownFlags() {
        val policy = AccountRegistrationPolicy.parse("before-connect,email-required,custom-account-name")
        assertTrue(policy.beforeConnect)
        assertTrue(policy.emailRequired)
        assertTrue(policy.customAccountName)
    }

    @Test
    fun parsesPasswordLengthBounds() {
        val policy = AccountRegistrationPolicy.parse("min-password-length=8,max-password-length=300")
        assertEquals(8, policy.minPasswordLength)
        assertEquals(300, policy.maxPasswordLength)
        assertFalse(policy.beforeConnect)
    }

    @Test
    fun ignoresUnknownKeysAndInvalidLengths() {
        val policy = AccountRegistrationPolicy.parse("future-thing=1,email-required=ignored,min-password-length=0,max-password-length=x")
        assertTrue(policy.emailRequired)
        assertNull(policy.minPasswordLength)
        assertNull(policy.maxPasswordLength)
    }
}
