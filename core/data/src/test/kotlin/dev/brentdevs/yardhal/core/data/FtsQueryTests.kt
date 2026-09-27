package dev.brentdevs.yardhal.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FtsQueryTests {

    @Test
    fun wrapsTermsInQuotesAndJoinsWithAnd() {
        assertEquals("\"deploy\" \"today\"", FtsQuery.build("deploy today"))
    }

    @Test
    fun stripsPunctuationThatWouldBreakMatchSyntax() {
        assertEquals("\"foo\" \"OR\" \"bar\"", FtsQuery.build("foo; OR bar*"))
        assertEquals("\"it's\"", FtsQuery.build("it's"))
    }

    @Test
    fun emptyOrPunctuationOnlyQueriesRejected() {
        assertNull(FtsQuery.build(""))
        assertNull(FtsQuery.build("   "))
        assertNull(FtsQuery.build("*!;?"))
    }
}
