package dev.brentdevs.yardhal.core.client

import kotlin.test.Test
import kotlin.test.assertEquals

class RedactSensitiveOutboundTests {

    @Test
    fun mechanismNamesAndMarkersStayVisible() {
        for (line in listOf("AUTHENTICATE PLAIN", "AUTHENTICATE SCRAM-SHA-256", "AUTHENTICATE EXTERNAL", "AUTHENTICATE +", "AUTHENTICATE *")) {
            assertEquals(line, redactSensitiveOutbound(line))
        }
    }

    @Test
    fun authenticatePayloadsAreRedacted() {
        assertEquals("AUTHENTICATE <redacted>", redactSensitiveOutbound("AUTHENTICATE biwsbj11c2VyLHI9ck9wck5HZndFYmVSV2diTkVrcU8="))
        assertEquals("PASS <redacted>", redactSensitiveOutbound("PASS hunter2"))
    }

    @Test
    fun registerPasswordIsRedacted() {
        assertEquals("REGISTER * me@example.org <redacted>", redactSensitiveOutbound("REGISTER * me@example.org hunter2"))
        assertEquals("REGISTER acct * <redacted>", redactSensitiveOutbound("REGISTER acct * :pass with spaces"))
        assertEquals("REGISTER <redacted>", redactSensitiveOutbound("REGISTER hunter2"))
    }

    @Test
    fun nickServIdentifyRedactsPasswordsAndAccountWithPrefixesAndTags() {
        assertEquals("PRIVMSG NickServ :IDENTIFY <redacted>", redactSensitiveOutbound("PRIVMSG NickServ :IDENTIFY hunter2"))
        assertEquals("@label=identify :me!u@h PRIVMSG AccountService :IDENTIFY <redacted>", redactSensitiveOutbound(
            "@label=identify :me!u@h PRIVMSG AccountService :IDENTIFY account hunter2", "AccountService",
        ))
        assertEquals("NS IDENTIFY <redacted>", redactSensitiveOutbound("NS IDENTIFY account hunter2"))
        assertEquals("PRIVMSG NickServ :IDENTIFY <redacted>", redactSensitiveOutbound("PRIVMSG NickServ : \tidentify\taccount hunter2"))
        assertEquals("PASS <redacted>", redactSensitiveOutbound("  PASS hunter2"))
        assertEquals(":srv AUTHENTICATE <redacted>", redactSensitiveOutbound(":srv AUTHENTICATE c2VjcmV0"))
        assertEquals("PRIVMSG #a :IDENTIFY hunter2", redactSensitiveOutbound("PRIVMSG #a :IDENTIFY hunter2"))
    }

    @Test
    fun manualServiceCredentialFormatsAreRedacted() {
        val cases = mapOf(
            "PRIVMSG NickServ :REGISTER registration-password me@example.org" to "PRIVMSG NickServ :REGISTER <redacted>",
            "NOTICE NickServ :SET PASSWORD replacement-password" to "NOTICE NickServ :SET PASSWORD <redacted>",
            "PRIVMSG NickServ :GHOST nickname ghost-password" to "PRIVMSG NickServ :GHOST <redacted>",
            "PRIVMSG NickServ :RECOVER nickname recover-password" to "PRIVMSG NickServ :RECOVER <redacted>",
            "NS register registration-password me@example.org" to "NS REGISTER <redacted>",
            "NickServ SET PASSWORD replacement-password" to "NickServ SET PASSWORD <redacted>",
            "IDENTIFY identify-password" to "IDENTIFY <redacted>",
            "IDENTIFY account identify-password" to "IDENTIFY <redacted>",
            "OPER operator oper-password" to "OPER operator <redacted>",
            "PRIVMSG NickServ :SET\tPASSWORD\treplacement-password" to "PRIVMSG NickServ :SET PASSWORD <redacted>",
            "NS GHOST nickname\tghost-password" to "NS GHOST <redacted>",
            "OPER operator\toper-password" to "OPER operator <redacted>",
        )
        for ((wire, expected) in cases) assertEquals(expected, redactSensitiveOutbound(wire), wire)
    }

    @Test
    fun configuredQualifiedServiceChecksNicknameAndExplicitHost() {
        val service = "NickServ@services.example.org"
        for (target in listOf("NickServ", "nickserv@SERVICES.EXAMPLE.ORG")) {
            assertEquals("PRIVMSG $target :IDENTIFY <redacted>", redactSensitiveOutbound("PRIVMSG $target :IDENTIFY password", service))
        }
        assertEquals("NS REGISTER <redacted>", redactSensitiveOutbound("NS REGISTER password email@example.org", service))
        for (target in listOf("NickServ@other.example.org", "OtherServ@services.example.org", "#NickServ", "NickServ,friend")) {
            val wire = "PRIVMSG $target :IDENTIFY ordinary-conversation"
            assertEquals(wire, redactSensitiveOutbound(wire, service))
            val redactor = TrafficRedactor(emptyList(), service)
            redactor.rememberOutbound(wire)
            assertEquals("ordinary-conversation", redactor.redactPresentation("ordinary-conversation"))
        }
    }

    @Test
    fun identifyCompleteCredentialEchoAndIndividualPasswordAreMasked() {
        for (separator in listOf(" ", "\t")) {
            val redactor = TrafficRedactor(emptyList(), "NickServ")
            redactor.rememberOutbound("PRIVMSG NickServ :IDENTIFY account${separator}password")
            assertEquals("Echo <redacted>", redactor.redactPresentation("Echo account${separator}password"))
            assertEquals("Echo <redacted>", redactor.redactPresentation("Echo password"))
            assertEquals("account", redactor.redactPresentation("account"))
        }
    }

    @Test
    fun manualPasswordsAreRememberedWithoutMaskingAccountsOrOtherChat() {
        val cases = listOf(
            "PRIVMSG NickServ :REGISTER registration-password email@example.org" to "registration-password",
            "NS REGISTER ns-password email@example.org" to "ns-password",
            "PRIVMSG NickServ :SET PASSWORD replacement-password" to "replacement-password",
            "PRIVMSG NickServ :GHOST nickname ghost-password" to "ghost-password",
            "PRIVMSG NickServ :RECOVER nickname recover-password" to "recover-password",
            "IDENTIFY account identify-password" to "identify-password",
            "IDENTIFY trailing-password \t" to "trailing-password",
            "OPER operator oper-password" to "oper-password",
            "PRIVMSG NickServ :IDENTIFY\taccount\ttab-password" to "tab-password",
            "NS RECOVER nickname\ttab-recover-password" to "tab-recover-password",
        )
        for ((wire, password) in cases) {
            val redactor = TrafficRedactor(emptyList(), "NickServ")
            redactor.rememberOutbound(wire)
            assertEquals("Echo <redacted>", redactor.redactPresentation("Echo $password"), wire)
            val chat = "account nickname operator email@example.org REGISTER and SET PASSWORD are useful commands"
            assertEquals(chat, redactor.redactPresentation(chat), wire)
        }
        val redactor = TrafficRedactor(emptyList(), "NickServ")
        redactor.rememberOutbound("PRIVMSG #chat :IDENTIFY ordinary-conversation")
        redactor.rememberOutbound("PRIVMSG NickServ :SET EMAIL email@example.org")
        assertEquals("ordinary-conversation email@example.org", redactor.redactPresentation("ordinary-conversation email@example.org"))
    }

    @Test
    fun spaceAndTabDelimitedPasswordComponentsAreRemembered() {
        val redactor = TrafficRedactor(emptyList(), "NickServ")
        redactor.rememberOutbound("PRIVMSG NickServ :SET PASSWORD first-part second-part\tthird-part")
        assertEquals("Echo <redacted> <redacted> <redacted>", redactor.redactPresentation("Echo third-part second-part first-part"))
        assertEquals("Echo <redacted>", redactor.redactPresentation("Echo first-part second-part\tthird-part"))
    }

    @Test
    fun markerPrefixedConfiguredAndManualPasswordsAreFullyMasked() {
        val redactor = TrafficRedactor(listOf("<redacted>configured-secret", "red"), "NickServ")
        redactor.rememberOutbound("IDENTIFY <redacted>manual-secret")
        val masked = redactor.redactPresentation("Echo <redacted>configured-secret <redacted>manual-secret <redacted>")
        assertEquals("Echo <redacted> <redacted> <redacted>", masked)
        assertEquals(masked, redactor.redactPresentation(masked))
    }

    @Test
    fun literalPresentationMaskingIsIdempotentAndPrefersLongestOverlappingSecret() {
        val redactor = TrafficRedactor(listOf("red", "secret", "secret-password"), "NickServ")
        val masked = redactor.redactPresentation("Echo secret-password red <redacted>")
        assertEquals("Echo <redacted> <redacted> <redacted>", masked)
        assertEquals(masked, redactor.redactPresentation(masked))
        assertEquals("payload <redacted>", redactor.redact("payload <redacted>"))
    }

    @Test
    fun otherCommandsPassThrough() {
        assertEquals("VERIFY acct 1234", redactSensitiveOutbound("VERIFY acct 1234"))
        assertEquals("PRIVMSG #a :hi", redactSensitiveOutbound("PRIVMSG #a :hi"))
    }
}
