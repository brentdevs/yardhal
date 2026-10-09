package dev.brentdevs.yardhal.core.data

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

public object DerivedNetworkIdentity {
    private val namespace = UUID.fromString("C40D04F5-6B4E-4E37-9C79-6F1B2A6E5A31")
    private val namespaceBytes = ByteBuffer.allocate(16)
        .putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array()

    public fun sojuNetworkId(parentId: String, netId: String): String {
        val parent = runCatching { UUID.fromString(parentId) }.getOrElse {
            UUID.nameUUIDFromBytes(parentId.toByteArray(Charsets.UTF_8))
        }
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(namespaceBytes)
        digest.update(parent.toString().uppercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        digest.update(':'.code.toByte())
        val bytes = digest.digest(netId.toByteArray(Charsets.UTF_8))
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        var most = 0L
        var least = 0L
        for (index in 0..7) most = (most shl 8) or (bytes[index].toLong() and 0xff)
        for (index in 8..15) least = (least shl 8) or (bytes[index].toLong() and 0xff)
        return UUID(most, least).toString()
    }
}
