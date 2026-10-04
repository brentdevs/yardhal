package dev.brentdevs.yardhal.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrcMetadataTests {

    @Test
    fun capabilityTokensAreParsedAndUnknownOnesIgnored() {
        assertEquals(
            MetadataCapability(maxSubs = 50, maxKeys = 100, maxValueBytes = 512, beforeConnect = true),
            MetadataCapability.parse("foo,before-connect,max-subs=50,max-keys=100,max-value-bytes=512,bar=baz"),
        )
        assertEquals(MetadataCapability(), MetadataCapability.parse(null))
        assertEquals(MetadataCapability(maxSubs = null), MetadataCapability.parse("max-subs=lots"))
    }

    @Test
    fun valueLimitCountsUtf8Bytes() {
        val capability = MetadataCapability(maxValueBytes = 4)
        assertTrue(capability.allowsValue("abcd"))
        assertFalse(capability.allowsValue("ééé"))
        assertTrue(MetadataCapability().allowsValue("x".repeat(10_000)))
    }

    @Test
    fun keyValidationFollowsSpec() {
        assertTrue(IrcMetadata.isValidKey("display-name"))
        assertTrue(IrcMetadata.isValidKey("im.xmpp_2/x"))
        assertFalse(IrcMetadata.isValidKey(""))
        assertFalse(IrcMetadata.isValidKey("Avatar"))
        assertFalse(IrcMetadata.isValidKey("\$url"))
    }

    @Test
    fun setCommandOmitsValueToDelete() {
        assertEquals("METADATA * SET display-name :Ada L", IrcMetadata.setCommand("display-name", "Ada L"))
        assertEquals("METADATA * SET avatar", IrcMetadata.setCommand("avatar", null))
    }

    @Test
    fun isupportValuesAreUnescaped() {
        assertEquals("a b=c\\", ISupport.unescapeValue("a\\x20b\\x3Dc\\x5C"))
        assertEquals("héllo", ISupport.unescapeValue("h\\xC3\\xA9llo"))
        assertEquals("\\xZZ", ISupport.unescapeValue("\\xZZ"))
        assertEquals("plain", ISupport.unescapeValue("plain"))
    }

    @Test
    fun networkIconTokenIsExposed() {
        val support = ISupport.parse(listOf("NETWORK=Example", "draft/ICON=https://example.org/icon.svg"))
        assertEquals("https://example.org/icon.svg", support.networkIcon)
        assertNull(ISupport.parse(listOf("draft/ICON=")).networkIcon)
        assertNull(ISupport.EMPTY.networkIcon)
    }
}
