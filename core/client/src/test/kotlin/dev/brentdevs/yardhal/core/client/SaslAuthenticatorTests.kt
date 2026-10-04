package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SaslAuthenticatorTests {

    private class Harness(
        advertised: Set<String>?,
        authcid: String = "jilles",
        password: String = "sesame",
        createMechanism: ((String) -> SaslMechanism)? = null,
    ) {
        val sent = mutableListOf<String>()
        val outcomes = mutableListOf<SaslOutcome>()

        val authenticator = if (createMechanism == null) {
            SaslAuthenticator(authcid, password, advertised, sent::add, outcomes::add)
        } else {
            SaslAuthenticator(authcid, password, advertised, sent::add, outcomes::add, createMechanism)
        }

        fun server(line: String) {
            val message = IrcMessage.parse(line) ?: error("unparseable: $line")
            val numeric = message.numeric
            if (numeric != null) authenticator.handleNumeric(numeric, message)
            authenticator.handleMessage(message)
        }
    }

    @Test
    fun withoutAdvertisedListUsesPlain() {
        val harness = Harness(advertised = null)
        harness.authenticator.start()
        assertEquals(listOf("AUTHENTICATE PLAIN"), harness.sent)
    }

    @Test
    fun prefersScramWhenAdvertised() {
        val harness = Harness(advertised = SaslAuthenticator.parseMechanismList("EXTERNAL,plain,SCRAM-SHA-256,FOO"))
        harness.authenticator.start()
        assertEquals(listOf("AUTHENTICATE SCRAM-SHA-256"), harness.sent)
    }

    @Test
    fun picksPlainWhenScramAbsent() {
        val harness = Harness(advertised = setOf("EXTERNAL", "PLAIN"))
        harness.authenticator.start()
        assertEquals(listOf("AUTHENTICATE PLAIN"), harness.sent)
    }

    @Test
    fun noMutualMechanismFailsWithoutSendingAnything() {
        val harness = Harness(advertised = setOf("EXTERNAL", "ECDSA-NIST256P-CHALLENGE"))
        harness.authenticator.start()
        assertTrue(harness.sent.isEmpty())
        assertIs<SaslOutcome.Failure>(harness.outcomes.single())
        assertTrue(harness.authenticator.isFinished)
    }

    @Test
    fun mechanismListParsingHandlesMissingAndEmptyValues() {
        assertEquals(null, SaslAuthenticator.parseMechanismList(null))
        assertEquals(null, SaslAuthenticator.parseMechanismList(""))
        assertEquals(setOf("PLAIN", "EXTERNAL"), SaslAuthenticator.parseMechanismList("PLAIN,,EXTERNAL"))
    }

    @Test
    fun plainContinuationProducesBase64Credentials() {
        val harness = Harness(advertised = null)
        harness.authenticator.start()
        harness.server("AUTHENTICATE +")
        assertEquals("AUTHENTICATE AGppbGxlcwBzZXNhbWU=", harness.sent[1])
        harness.server(":srv 903 * :SASL authentication successful")
        assertEquals(listOf<SaslOutcome>(SaslOutcome.Success), harness.outcomes)
        harness.server(":srv 904 * :SASL authentication failed")
        assertEquals(listOf<SaslOutcome>(SaslOutcome.Success), harness.outcomes)
    }

    @Test
    fun failureNumericsEmitFailure() {
        for (numeric in listOf(902, 904, 905, 906, 907)) {
            val harness = Harness(advertised = null)
            harness.authenticator.start()
            harness.server(":srv $numeric * :bad")
            val failure = assertIs<SaslOutcome.Failure>(harness.outcomes.single())
            assertEquals(numeric, failure.numeric)
        }
    }

    @Test
    fun unexpectedPlainPayloadAborts() {
        val harness = Harness(advertised = null)
        harness.authenticator.start()
        harness.server("AUTHENTICATE ABCD")
        assertEquals("AUTHENTICATE *", harness.sent.last())
        assertIs<SaslOutcome.Failure>(harness.outcomes.single())
    }

    @Test
    fun unrelatedMessagesDoNotAdvanceOrFinishAuthentication() {
        val harness = Harness(advertised = null)
        harness.authenticator.start()
        harness.server("PRIVMSG #a :x")
        harness.server("001 nick :Welcome")
        assertEquals(listOf("AUTHENTICATE PLAIN"), harness.sent)
        assertTrue(harness.outcomes.isEmpty())
        assertFalse(harness.authenticator.isFinished)
        assertEquals(SaslAuthenticator.PLAIN, harness.authenticator.currentMechanism)
        harness.server("AUTHENTICATE +")
        harness.server(":srv 903 * :SASL authentication successful")
        assertEquals(listOf<SaslOutcome>(SaslOutcome.Success), harness.outcomes)
    }

    @Test
    fun saslMechsNumericFallsBackToOfferedMechanism() {
        val harness = Harness(advertised = setOf("SCRAM-SHA-256", "PLAIN"))
        harness.authenticator.start()
        assertEquals("AUTHENTICATE SCRAM-SHA-256", harness.sent.last())
        harness.server(":srv 908 jilles PLAIN,EXTERNAL :are available SASL mechanisms")
        harness.server(":srv 904 jilles :SASL authentication failed")
        assertTrue(harness.outcomes.isEmpty())
        assertEquals("AUTHENTICATE PLAIN", harness.sent.last())
        harness.server("AUTHENTICATE +")
        harness.server(":srv 903 jilles :SASL authentication successful")
        assertEquals(listOf<SaslOutcome>(SaslOutcome.Success), harness.outcomes)
    }

    @Test
    fun saslMechsWithoutUsableMechanismFails() {
        val harness = Harness(advertised = null)
        harness.authenticator.start()
        harness.server(":srv 908 jilles EXTERNAL :are available SASL mechanisms")
        harness.server(":srv 904 jilles :SASL authentication failed")
        assertEquals(904, assertIs<SaslOutcome.Failure>(harness.outcomes.single()).numeric)
    }

    @Test
    fun saslFailWhileMechanismOfferedIsRealFailure() {
        val harness = Harness(advertised = setOf("SCRAM-SHA-256", "PLAIN"))
        harness.authenticator.start()
        harness.server(":srv 908 jilles SCRAM-SHA-256,PLAIN :are available SASL mechanisms")
        harness.server(":srv 904 jilles :SASL authentication failed")
        assertIs<SaslOutcome.Failure>(harness.outcomes.single())
        assertEquals(1, harness.sent.size)
    }

    @Test
    fun longPayloadIsChunkedAt400BytesWithTerminator() {
        val password = "p".repeat(300 - "\u0000jilles\u0000".length)
        val harness = Harness(advertised = null, password = password)
        harness.authenticator.start()
        harness.server("AUTHENTICATE +")
        val payloadLines = harness.sent.drop(1).map { it.removePrefix("AUTHENTICATE ") }
        assertEquals(listOf(400, 1), payloadLines.map { it.length })
        assertEquals("+", payloadLines.last())
        val decoded = String(Base64.getDecoder().decode(payloadLines.first()), Charsets.UTF_8)
        assertEquals("\u0000jilles\u0000$password", decoded)
    }

    @Test
    fun payloadLongerThanOneChunkSplitsWithoutTerminator() {
        val password = "q".repeat(400)
        val harness = Harness(advertised = null, password = password)
        harness.authenticator.start()
        harness.server("AUTHENTICATE +")
        val payloadLines = harness.sent.drop(1).map { it.removePrefix("AUTHENTICATE ") }
        assertEquals(400, payloadLines[0].length)
        assertTrue(payloadLines[1].length in 1 until 400)
        val decoded = String(Base64.getDecoder().decode(payloadLines.joinToString("")), Charsets.UTF_8)
        assertEquals("\u0000jilles\u0000$password", decoded)
    }

    @Test
    fun inboundChunksAreReassembledBeforeDecoding() {
        val received = mutableListOf<ByteArray>()
        val recorder = object : SaslMechanism {
            override val name: String = SaslAuthenticator.PLAIN
            override val serverVerified: Boolean = true
            override fun respond(challenge: ByteArray): ByteArray {
                received += challenge
                return ByteArray(0)
            }
        }
        val harness = Harness(advertised = null, createMechanism = { recorder })
        harness.authenticator.start()
        val challenge = ByteArray(300) { it.toByte() }
        val encoded = Base64.getEncoder().encodeToString(challenge)
        assertEquals(400, encoded.length)
        harness.server("AUTHENTICATE $encoded")
        assertTrue(received.isEmpty())
        harness.server("AUTHENTICATE +")
        assertTrue(challenge.contentEquals(received.single()))
        assertEquals("AUTHENTICATE +", harness.sent.last())
    }

    @Test
    fun scramSuccessRequiresVerifiedServer() {
        val harness = Harness(advertised = setOf("SCRAM-SHA-256"))
        harness.authenticator.start()
        harness.server("AUTHENTICATE +")
        harness.server(":srv 903 jilles :SASL authentication successful")
        val failure = assertIs<SaslOutcome.Failure>(harness.outcomes.single())
        assertEquals(903, failure.numeric)
    }

    @Test
    fun scramExchangeAgainstFixtureSucceeds() {
        val server = ScramServerFixture(password = "sesame")
        val harness = Harness(advertised = setOf("SCRAM-SHA-256", "PLAIN"))
        harness.authenticator.start()
        harness.server("AUTHENTICATE +")
        val clientFirst = ScramServerFixture.decode(harness.sent.last().removePrefix("AUTHENTICATE "))
        harness.server("AUTHENTICATE ${ScramServerFixture.encode(server.serverFirst(clientFirst))}")
        val clientFinal = ScramServerFixture.decode(harness.sent.last().removePrefix("AUTHENTICATE "))
        harness.server("AUTHENTICATE ${ScramServerFixture.encode(server.serverFinal(clientFinal))}")
        assertTrue(server.clientProofValid)
        assertEquals("AUTHENTICATE +", harness.sent.last())
        harness.server(":srv 903 jilles :SASL authentication successful")
        assertEquals(listOf<SaslOutcome>(SaslOutcome.Success), harness.outcomes)
    }

    @Test
    fun scramBadServerSignatureAborts() {
        val server = ScramServerFixture(password = "sesame")
        val harness = Harness(advertised = setOf("SCRAM-SHA-256"))
        harness.authenticator.start()
        harness.server("AUTHENTICATE +")
        val clientFirst = ScramServerFixture.decode(harness.sent.last().removePrefix("AUTHENTICATE "))
        harness.server("AUTHENTICATE ${ScramServerFixture.encode(server.serverFirst(clientFirst))}")
        val clientFinal = ScramServerFixture.decode(harness.sent.last().removePrefix("AUTHENTICATE "))
        harness.server("AUTHENTICATE ${ScramServerFixture.encode(server.serverFinal(clientFinal, tamperSignature = true))}")
        assertEquals("AUTHENTICATE *", harness.sent.last())
        harness.server(":srv 903 jilles :SASL authentication successful")
        val failure = assertIs<SaslOutcome.Failure>(harness.outcomes.single())
        assertTrue(failure.description.contains("signature"))
    }

    @Test
    fun cancelReportsFailureOnce() {
        val harness = Harness(advertised = null)
        harness.authenticator.start()
        harness.authenticator.cancel("withdrawn")
        harness.server(":srv 903 * :SASL authentication successful")
        assertEquals(SaslOutcome.Failure(0, "withdrawn"), harness.outcomes.single())
    }

    @Test
    fun invalidCredentialsReportFailureWithoutSendingOrDowngrading() {
        val credentials = listOf(
            "\u0007user" to "password",
            "\u0627user" to "password",
            "\uD800" to "password",
            "\u00AD" to "password",
            "user" to "\u00AD",
            "user" to "password\u0000",
            "user" to "password\uD83D\uDE00",
        )
        for ((username, password) in credentials) {
            val harness = Harness(
                advertised = setOf("SCRAM-SHA-256", "PLAIN"),
                authcid = username,
                password = password,
            )
            harness.authenticator.start()
            val failure = assertIs<SaslOutcome.Failure>(harness.outcomes.single())
            assertEquals(0, failure.numeric)
            assertTrue(failure.description.isNotEmpty())
            assertTrue(harness.authenticator.isFinished)
            assertTrue(harness.sent.isEmpty())
            harness.authenticator.start()
            harness.server("AUTHENTICATE +")
            harness.server(":srv 903 * :SASL authentication successful")
            assertEquals(listOf<SaslOutcome>(failure), harness.outcomes)
            assertTrue(harness.sent.isEmpty())
        }
    }

    @Test
    fun fallbackMechanismConstructionFailureReportsOneOutcome() {
        val harness = Harness(advertised = setOf("SCRAM-SHA-256", "PLAIN"), createMechanism = { name ->
            if (name == SaslAuthenticator.PLAIN) throw SaslMechanismException("credentials are invalid")
            ScramSha256Mechanism("user", "password")
        })
        harness.authenticator.start()
        harness.server(":srv 908 user PLAIN :are available SASL mechanisms")
        harness.server(":srv 904 user :SASL authentication failed")
        assertEquals(SaslOutcome.Failure(0, "credentials are invalid"), harness.outcomes.single())
        assertEquals(listOf("AUTHENTICATE SCRAM-SHA-256"), harness.sent)
        assertTrue(harness.authenticator.isFinished)
    }

    @Test
    fun unicodeScramExchangeUsesPreparedCredentials() {
        val server = ScramServerFixture(password = "p\u00E9ncil")
        val harness = Harness(
            advertised = setOf("SCRAM-SHA-256", "PLAIN"),
            authcid = "\u2168\u00AD\uFF1D\uFF0C\uD835\uDC00",
            password = "pe\u0301ncil",
        )
        harness.authenticator.start()
        harness.server("AUTHENTICATE +")
        val clientFirst = ScramServerFixture.decode(harness.sent.last().removePrefix("AUTHENTICATE "))
        assertTrue(clientFirst.startsWith("n,,n=IX=3D=2CA,r="))
        harness.server("AUTHENTICATE ${ScramServerFixture.encode(server.serverFirst(clientFirst))}")
        val clientFinal = ScramServerFixture.decode(harness.sent.last().removePrefix("AUTHENTICATE "))
        harness.server("AUTHENTICATE ${ScramServerFixture.encode(server.serverFinal(clientFinal))}")
        harness.server(":srv 903 * :SASL authentication successful")
        assertTrue(server.clientProofValid)
        assertEquals(listOf<SaslOutcome>(SaslOutcome.Success), harness.outcomes)
        assertFalse("AUTHENTICATE PLAIN" in harness.sent)
    }
}
