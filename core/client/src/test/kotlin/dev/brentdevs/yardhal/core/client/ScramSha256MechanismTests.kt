package dev.brentdevs.yardhal.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScramSha256MechanismTests {

    private val rfcClientNonce = "rOprNGfwEbeRWgbNEkqO"
    private val rfcServerFirst = "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF\$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"
    private val rfcClientFinal =
        "c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF\$k0,p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ="
    private val rfcServerFinal = "v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4="

    private fun rfcMechanism() = ScramSha256Mechanism("user", "pencil", clientNonce = rfcClientNonce)

    private fun SaslMechanism.step(text: String): String =
        String(respond(text.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)

    @Test
    fun rfc7677TestVectorRoundTrip() {
        val mechanism = rfcMechanism()
        assertEquals("n,,n=user,r=rOprNGfwEbeRWgbNEkqO", mechanism.step(""))
        assertEquals(rfcClientFinal, mechanism.step(rfcServerFirst))
        assertFalse(mechanism.serverVerified)
        assertEquals("", mechanism.step(rfcServerFinal))
        assertTrue(mechanism.serverVerified)
    }

    @Test
    fun serverSignatureMismatchFailsClosed() {
        val mechanism = rfcMechanism()
        mechanism.step("")
        mechanism.step(rfcServerFirst)
        val error = assertFailsWith<SaslMechanismException> {
            mechanism.step("v=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        }
        assertTrue(error.message.orEmpty().contains("signature"))
        assertFalse(mechanism.serverVerified)
    }

    @Test
    fun serverErrorAttributeFails() {
        val mechanism = rfcMechanism()
        mechanism.step("")
        mechanism.step(rfcServerFirst)
        assertFailsWith<SaslMechanismException> { mechanism.step("e=invalid-proof") }
        assertFalse(mechanism.serverVerified)
    }

    @Test
    fun serverNonceMustExtendClientNonce() {
        val mechanism = rfcMechanism()
        mechanism.step("")
        assertFailsWith<SaslMechanismException> {
            mechanism.step("r=someoneElsesNonce,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096")
        }
        val echoOnly = rfcMechanism()
        echoOnly.step("")
        assertFailsWith<SaslMechanismException> {
            echoOnly.step("r=$rfcClientNonce,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096")
        }
    }

    @Test
    fun lowIterationCountAndMandatoryExtensionsRejected() {
        val weak = rfcMechanism()
        weak.step("")
        assertFailsWith<SaslMechanismException> {
            weak.step("r=${rfcClientNonce}xyz,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=1")
        }
        val extended = rfcMechanism()
        extended.step("")
        assertFailsWith<SaslMechanismException> {
            extended.step("m=ext,r=${rfcClientNonce}xyz,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096")
        }
    }

    @Test
    fun usernameIsEscapedAndPrepared() {
        val mechanism = ScramSha256Mechanism("a=b,c\u00A0d", "pw", clientNonce = "nonce")
        assertEquals("n,,n=a=3Db=2Cc d,r=nonce", mechanism.step(""))
        assertEquals("fix", SaslPrep.prepare("\uFB01\u00ADx", SaslPrep.Policy.QUERY))
    }

    @Test
    fun interoperatesWithIndependentPbkdf2Server() {
        val server = ScramServerFixture(password = "correct horse")
        val mechanism = ScramSha256Mechanism("jilles", "correct horse")
        val serverFirst = server.serverFirst(mechanism.step(""))
        val serverFinal = server.serverFinal(mechanism.step(serverFirst))
        assertTrue(server.clientProofValid)
        mechanism.step(serverFinal)
        assertTrue(mechanism.serverVerified)
    }

    @Test
    fun unicodeCredentialsArePreparedBeforeProofAndUsernameEscaping() {
        val server = ScramServerFixture(password = "p\u00E9ncil\uD834\uDD57")
        val mechanism = ScramSha256Mechanism(
            "\u2168\u00AD\uFF1D\uFF0C\uD835\uDC00",
            "pe\u0301ncil\uD834\uDD57",
            clientNonce = "unicodeNonce",
        )
        val clientFirst = mechanism.step("")
        assertEquals("n,,n=IX=3D=2CA,r=unicodeNonce", clientFirst)
        val serverFinal = server.serverFinal(mechanism.step(server.serverFirst(clientFirst)))
        assertTrue(server.clientProofValid)
        mechanism.step(serverFinal)
        assertTrue(mechanism.serverVerified)
    }

    @Test
    fun usernameAllowsUnicode32UnassignedQueryCharactersWithoutModernNormalization() {
        val mechanism = ScramSha256Mechanism("\u1D2C\uD83D\uDE00", "password", clientNonce = "nonce")
        assertEquals("n,,n=\u1D2C\uD83D\uDE00,r=nonce", mechanism.step(""))
    }

    @Test
    fun passwordRejectsUnicode32UnassignedStoredCharacters() {
        val failure = assertFailsWith<SaslMechanismException> {
            ScramSha256Mechanism("user", "password\uD83D\uDE00")
        }
        assertEquals(SaslMechanismFailure.UNASSIGNED_CHARACTER, failure.failure)
        assertEquals(0x1F600, failure.codePoint)
    }

    @Test
    fun preparationMustNotEraseEntireCredentials() {
        val usernameFailure = assertFailsWith<SaslMechanismException> {
            ScramSha256Mechanism("\u00AD\u200B", "password")
        }
        assertEquals(SaslMechanismFailure.EMPTY_USERNAME, usernameFailure.failure)
        val passwordFailure = assertFailsWith<SaslMechanismException> {
            ScramSha256Mechanism("user", "\u00AD\u200B")
        }
        assertEquals(SaslMechanismFailure.EMPTY_PASSWORD, passwordFailure.failure)
    }
}
