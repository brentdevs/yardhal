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
        assertEquals("fix", ScramSha256Mechanism.saslPrep("\uFB01\u00ADx"))
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
}
