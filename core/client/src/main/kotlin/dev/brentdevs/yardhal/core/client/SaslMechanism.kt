package dev.brentdevs.yardhal.core.client

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class SaslMechanismException(message: String) : Exception(message)

internal interface SaslMechanism {
    val name: String
    val serverVerified: Boolean
    fun respond(challenge: ByteArray): ByteArray
}

internal class PlainMechanism(
    private val authcid: String,
    private val password: String,
    private val authzid: String = "",
) : SaslMechanism {
    private var credentialsSent = false

    override val name: String = SaslAuthenticator.PLAIN
    override val serverVerified: Boolean = true

    override fun respond(challenge: ByteArray): ByteArray {
        if (credentialsSent || challenge.isNotEmpty()) {
            throw SaslMechanismException("unexpected AUTHENTICATE payload from server")
        }
        credentialsSent = true
        return "$authzid\u0000$authcid\u0000$password".toByteArray(Charsets.UTF_8)
    }
}

internal class ScramSha256Mechanism(
    username: String,
    password: String,
    private val clientNonce: String = randomNonce(),
) : SaslMechanism {
    private enum class Step { CLIENT_FIRST, CLIENT_FINAL, VERIFY_SERVER, DONE }

    private val clientFirstBare = "n=${escapeUsername(saslPrep(username))},r=$clientNonce"
    private val passwordBytes = saslPrep(password).toByteArray(Charsets.UTF_8)
    private var step = Step.CLIENT_FIRST
    private var expectedServerSignature = ByteArray(0)

    override val name: String = SaslAuthenticator.SCRAM_SHA_256

    override var serverVerified: Boolean = false
        private set

    override fun respond(challenge: ByteArray): ByteArray = when (step) {
        Step.CLIENT_FIRST -> {
            if (challenge.isNotEmpty()) throw SaslMechanismException("unexpected SCRAM challenge before client-first")
            step = Step.CLIENT_FINAL
            (GS2_HEADER + clientFirstBare).toByteArray(Charsets.UTF_8)
        }
        Step.CLIENT_FINAL -> clientFinal(String(challenge, Charsets.UTF_8))
        Step.VERIFY_SERVER -> verifyServerFinal(String(challenge, Charsets.UTF_8))
        Step.DONE -> throw SaslMechanismException("unexpected SCRAM challenge after completion")
    }

    private fun clientFinal(serverFirst: String): ByteArray {
        val attributes = parseAttributes(serverFirst)
        attributes["e"]?.let { throw SaslMechanismException("server rejected SCRAM: $it") }
        if (attributes.containsKey("m")) throw SaslMechanismException("unsupported mandatory SCRAM extension")
        val nonce = attributes["r"] ?: throw SaslMechanismException("SCRAM server-first lacks nonce")
        if (!nonce.startsWith(clientNonce) || nonce.length == clientNonce.length) {
            throw SaslMechanismException("SCRAM server nonce does not extend client nonce")
        }
        val salt = decodeBase64(attributes["s"] ?: throw SaslMechanismException("SCRAM server-first lacks salt"))
        if (salt.isEmpty()) throw SaslMechanismException("SCRAM salt is empty")
        val iterations = attributes["i"]?.toIntOrNull()
            ?: throw SaslMechanismException("SCRAM server-first lacks iteration count")
        if (iterations < MIN_ITERATIONS) throw SaslMechanismException("SCRAM iteration count $iterations is too low")
        if (passwordBytes.isEmpty()) throw SaslMechanismException("SCRAM requires a non-empty password")

        val saltedPassword = hi(passwordBytes, salt, iterations)
        val clientKey = hmac(saltedPassword, "Client Key".toByteArray(Charsets.UTF_8))
        val storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey)
        val clientFinalWithoutProof = "c=${base64(GS2_HEADER.toByteArray(Charsets.UTF_8))},r=$nonce"
        val authMessage = "$clientFirstBare,$serverFirst,$clientFinalWithoutProof".toByteArray(Charsets.UTF_8)
        val clientSignature = hmac(storedKey, authMessage)
        val clientProof = ByteArray(clientKey.size) { (clientKey[it].toInt() xor clientSignature[it].toInt()).toByte() }
        val serverKey = hmac(saltedPassword, "Server Key".toByteArray(Charsets.UTF_8))
        expectedServerSignature = hmac(serverKey, authMessage)
        step = Step.VERIFY_SERVER
        return "$clientFinalWithoutProof,p=${base64(clientProof)}".toByteArray(Charsets.UTF_8)
    }

    private fun verifyServerFinal(serverFinal: String): ByteArray {
        val attributes = parseAttributes(serverFinal)
        attributes["e"]?.let { throw SaslMechanismException("server rejected SCRAM: $it") }
        val signature = decodeBase64(attributes["v"] ?: throw SaslMechanismException("SCRAM server-final lacks verifier"))
        if (!MessageDigest.isEqual(signature, expectedServerSignature)) {
            throw SaslMechanismException("SCRAM server signature mismatch")
        }
        serverVerified = true
        step = Step.DONE
        return ByteArray(0)
    }

    internal companion object {
        private const val GS2_HEADER = "n,,"
        private const val HMAC_SHA256 = "HmacSHA256"
        private const val NONCE_BYTES = 18
        const val MIN_ITERATIONS: Int = 4096

        private val NON_ASCII_SPACES = setOf(
            '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006',
            '\u2007', '\u2008', '\u2009', '\u200A', '\u200B', '\u202F', '\u205F', '\u3000',
        )
        private val MAPPED_TO_NOTHING = setOf(
            '\u00AD', '\u034F', '\u1806', '\u180B', '\u180C', '\u180D', '\u200C', '\u200D', '\u2060', '\uFEFF',
        ) + ('\uFE00'..'\uFE0F')

        private val random = SecureRandom()

        fun randomNonce(): String {
            val bytes = ByteArray(NONCE_BYTES)
            random.nextBytes(bytes)
            return base64(bytes)
        }

        fun saslPrep(value: String): String {
            if (value.all { it.code in 0x20..0x7E }) return value
            val mapped = StringBuilder(value.length)
            for (ch in value) {
                when (ch) {
                    in NON_ASCII_SPACES -> mapped.append(' ')
                    in MAPPED_TO_NOTHING -> Unit
                    else -> mapped.append(ch)
                }
            }
            return Normalizer.normalize(mapped, Normalizer.Form.NFKC)
        }

        fun escapeUsername(username: String): String =
            username.replace("=", "=3D").replace(",", "=2C")

        private fun parseAttributes(message: String): Map<String, String> {
            val attributes = LinkedHashMap<String, String>()
            for (part in message.split(',')) {
                if (part.length < 2 || part[1] != '=') throw SaslMechanismException("malformed SCRAM attribute")
                attributes.putIfAbsent(part.substring(0, 1), part.substring(2))
            }
            return attributes
        }

        private fun hi(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(password, HMAC_SHA256))
            mac.update(salt)
            var block = mac.doFinal(byteArrayOf(0, 0, 0, 1))
            val result = block.copyOf()
            repeat(iterations - 1) {
                block = mac.doFinal(block)
                for (index in result.indices) result[index] = (result[index].toInt() xor block[index].toInt()).toByte()
            }
            return result
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(key, HMAC_SHA256))
            return mac.doFinal(data)
        }

        private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        private fun decodeBase64(text: String): ByteArray =
            try {
                Base64.getDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                throw SaslMechanismException("malformed base64 in SCRAM message")
            }
    }
}
