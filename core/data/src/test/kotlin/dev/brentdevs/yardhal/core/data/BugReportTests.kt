package dev.brentdevs.yardhal.core.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class BugReportTests {
    @Test
    fun descriptionRetainsReproductionContextButRemovesCredentialsAndPrivateUrls() {
        val description = """
            Reconnect fails after changing settings.
            PASSWORD IS "upper secret"
            password=compact-secret
            token: bearer-secret
            secret 'quoted secret'
            Authorization: Bearer eyJprivate.payload
            https://user:url-secret@private.example/upload?token=query-secret
            yardhal://theme/v1/private-theme-payload
            ftp://user:ftp-secret@private-files.example/path
            PRIVMSG NickServ :IDENTIFY manual-secret
            @label=auth :tester!u@host AUTHENTICATE cHJpdmF0ZQ==
            Last error contained remembered-secret.
            Expected: reconnect successfully.
        """.trimIndent()
        val safe = BugReportRedaction.description(description) { it.replace("remembered-secret", "<redacted>") }
        for (secret in listOf("upper secret", "compact-secret", "bearer-secret", "quoted secret", "eyJprivate", "private.example", "url-secret", "query-secret", "manual-secret", "cHJpdmF0ZQ", "remembered-secret", "private-theme-payload", "private-files.example", "ftp-secret")) {
            assertFalse(secret in safe, secret)
        }
        assertTrue("Reconnect fails after changing settings." in safe)
        assertTrue("Expected: reconnect successfully." in safe)
    }

    @Test
    fun reportActivityNeverIncludesTagsNamesArgumentsOrUnknownCommands() {
        val wire = "@account=private-account;+reply=private-id :secret-nick!private-user@private-host PRIVMSG #private :https://private/upload?token=private-token"
        val event = BugReportRedaction.activity("network-1", false, wire)
        assertEquals(BugReportActivity("network-1", false, "PRIVMSG", 2), event)
        val refused = BugReportRedaction.activity("network-1", false, ":private-server 482 private-nick #private :operator password private-pass")
        assertEquals("482", refused.command)
        assertEquals(3, refused.parameterCount)
        val unknown = BugReportRedaction.activity("network-1", true, "private-secret-command private-secret-argument")
        assertEquals("UNRECOGNIZED_COMMAND", unknown.command)
        val serialized = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<List<BugReportActivity>>(), listOf(event, refused, unknown))
        assertFalse("private" in serialized)
        assertFalse("secret" in serialized)
    }


    @Test
    fun descriptionLimitDoesNotSplitSupplementaryCharactersOrPreserveControlInjection() {
        val prefix = "a".repeat(BugReportRedaction.MAX_DESCRIPTION_CHARS - 1)
        val limited = BugReportRedaction.description(prefix + "🧑" + "tail")
        assertEquals(prefix, limited)
        assertFalse(limited.last().isHighSurrogate())
        assertEquals("before\nnext\tend", BugReportRedaction.description("before\u0000\nnext\u001b\tend"))
        assertEquals("Pixelinjected", BugReportRedaction.deviceField("Pixel\ninjected"))
    }
}
