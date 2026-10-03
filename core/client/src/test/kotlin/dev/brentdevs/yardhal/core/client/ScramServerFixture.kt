package dev.brentdevs.yardhal.core.client

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal class ScramServerFixture(
    private val password: String,
    private val salt: ByteArray = "yardhal-loopback-salt".toByteArray(Charsets.UTF_8),
    private val iterations: Int = 4096,
    private val serverNonceSuffix: String = "loopbackServerNonce",
) {
    private var clientFirstBare: String = ""
    private var serverFirst: String = ""

    var clientProofValid: Boolean = false
        private set

    fun serverFirst(clientFirst: String): String {
        clientFirstBare = clientFirst.removePrefix("n,,")
        val clientNonce = clientFirstBare.substringAfter(",r=")
        serverFirst = "r=$clientNonce$serverNonceSuffix,s=${encode(salt)},i=$iterations"
        return serverFirst
    }

    fun serverFinal(clientFinal: String, tamperSignature: Boolean = false): String {
        val withoutProof = clientFinal.substringBefore(",p=")
        val proof = Base64.getDecoder().decode(clientFinal.substringAfter(",p="))
        val authMessage = "$clientFirstBare,$serverFirst,$withoutProof".toByteArray(Charsets.UTF_8)
        val salted = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256))
            .encoded
        val clientKey = hmac(salted, "Client Key".toByteArray(Charsets.UTF_8))
        val storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey)
        val clientSignature = hmac(storedKey, authMessage)
        val recoveredKey = ByteArray(proof.size) { (proof[it].toInt() xor clientSignature[it].toInt()).toByte() }
        clientProofValid = MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(recoveredKey), storedKey)
        val signature = hmac(hmac(salted, "Server Key".toByteArray(Charsets.UTF_8)), authMessage)
        if (tamperSignature) signature[0] = (signature[0].toInt() xor 0x01).toByte()
        return "v=${encode(signature)}"
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    companion object {
        fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun encode(text: String): String = encode(text.toByteArray(Charsets.UTF_8))

        fun decode(text: String): String = String(Base64.getDecoder().decode(text), Charsets.UTF_8)
    }
}
