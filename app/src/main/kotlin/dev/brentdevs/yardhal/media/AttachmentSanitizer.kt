package dev.brentdevs.yardhal.media

import dev.brentdevs.yardhal.core.data.PhotoMetadataPolicy
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class AttachmentSizeException : IOException("Attachment exceeds the configured size limit. Choose a smaller file or increase the limit in upload settings.")
class AttachmentSanitizationException : IOException("This image cannot be safely stripped without changing its appearance or animation. Choose Keep metadata explicitly, or select a supported JPEG, PNG or static WebP.")

internal class BoundedAttachmentOutput(output: OutputStream, private val maximumBytes: Long) : FilterOutputStream(output) {
    var count: Long = 0
        private set
    override fun write(value: Int) { requireSpace(1); out.write(value); count++ }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { requireSpace(length.toLong()); out.write(bytes, offset, length); count += length }
    private fun requireSpace(length: Long) { if (length > maximumBytes - count) throw AttachmentSizeException() }
}

internal fun copyBoundedAttachment(input: InputStream, output: OutputStream, maximumBytes: Long, check: () -> Unit = {}): Long {
    val bounded = BoundedAttachmentOutput(output, maximumBytes)
    val buffer = ByteArray(32 * 1024)
    while (true) {
        check()
        val count = input.read(buffer)
        if (count < 0) return bounded.count
        if (count != 0) bounded.write(buffer, 0, count)
    }
}

object AttachmentSanitizer {
    fun sanitize(source: File, destination: File, mimeType: String, policy: PhotoMetadataPolicy, maximumBytes: Long, check: () -> Unit = {}): String {
        if (source.length() > maximumBytes) throw AttachmentSizeException()
        val detected = detectImage(source)
        val effectiveMime = detected ?: mimeType
        try {
            source.inputStream().buffered().use { input ->
                FileOutputStream(destination).use { raw ->
                    val output = BoundedAttachmentOutput(raw.buffered(), maximumBytes)
                    output.use {
                        if (policy == PhotoMetadataPolicy.KEEP || (!effectiveMime.startsWith("image/") && detected == null)) {
                            copyBoundedAttachment(input, output, maximumBytes, check)
                        } else when (effectiveMime) {
                            "image/jpeg" -> jpeg(input, output, check)
                            "image/png" -> png(input, output, check)
                            "image/webp" -> webp(input, output, source, check)
                            else -> throw AttachmentSanitizationException()
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            destination.delete()
            if (failure is EOFException || failure is IllegalArgumentException || failure is IndexOutOfBoundsException) throw AttachmentSanitizationException()
            throw failure
        }
        return effectiveMime
    }

    private fun detectImage(source: File): String? {
        val prefix = ByteArray(12)
        val count = source.inputStream().use { it.read(prefix) }
        return when {
            count >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte() -> "image/jpeg"
            count >= 8 && prefix.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "image/png"
            count >= 12 && String(prefix, 0, 4, Charsets.US_ASCII) == "RIFF" && String(prefix, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            count >= 6 && String(prefix, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
            count >= 12 && String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp" && String(prefix, 8, 4, Charsets.US_ASCII) in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis") -> "image/heif"
            count >= 4 && (prefix.copyOfRange(0, 4).contentEquals(byteArrayOf(0x49, 0x49, 0x2a, 0)) || prefix.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4d, 0x4d, 0, 0x2a))) -> "image/tiff"
            else -> null
        }
    }

    private fun jpeg(source: InputStream, output: OutputStream, check: () -> Unit) {
        val input = DataInputStream(source)
        if (input.readUnsignedShort() != 0xffd8) throw AttachmentSanitizationException()
        output.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        var inScan = false
        var scanBytes = 0
        var frameSeen = false
        var scanSeen = false
        while (true) {
            check()
            var marker: Int
            if (inScan) {
                var byte = input.readUnsignedByte()
                while (byte != 0xff) {
                    output.write(byte)
                    byte = input.readUnsignedByte()
                    scanBytes++
                    if (scanBytes and 32767 == 0) check()
                }
                marker = input.readUnsignedByte()
                while (marker == 0xff) marker = input.readUnsignedByte()
                if (marker == 0 || marker in 0xd0..0xd7) {
                    output.write(0xff); output.write(marker)
                    continue
                }
                inScan = false
            } else {
                if (input.readUnsignedByte() != 0xff) throw AttachmentSanitizationException()
                marker = input.readUnsignedByte()
                while (marker == 0xff) marker = input.readUnsignedByte()
            }
            if (marker == 0xd9) {
                if (!frameSeen || !scanSeen) throw AttachmentSanitizationException()
                output.write(0xff); output.write(marker); return
            }
            if (marker == 0xd8 || marker == 0 || marker in 0xd0..0xd7) throw AttachmentSanitizationException()
            val length = input.readUnsignedShort()
            if (length < 2) throw AttachmentSanitizationException()
            val payload = ByteArray(length - 2)
            input.readFully(payload)
            if (marker == 0xe2 && payload.size >= 12 && String(payload, 0, 12, Charsets.US_ASCII) == "ICC_PROFILE\u0000") throw AttachmentSanitizationException()
            if (marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                if (payload.size < 6) throw AttachmentSanitizationException()
                frameSeen = true
            }
            if (marker == 0xe1 && payload.size >= 6 && String(payload, 0, 6, Charsets.US_ASCII) == "Exif\u0000\u0000") {
                val orientation = exifOrientation(payload.copyOfRange(6, payload.size))
                if (orientation != 1) {
                    val minimal = minimalOrientationExif(orientation)
                    output.write(0xff); output.write(0xe1)
                    output.write((minimal.size + 2) ushr 8); output.write((minimal.size + 2) and 0xff)
                    output.write(minimal)
                }
            } else if (marker == 0xee && payload.size >= 5 && String(payload, 0, 5, Charsets.US_ASCII) == "Adobe") {
                if (payload.size != 12 || payload[11].toInt() !in 0..2) throw AttachmentSanitizationException()
                output.write(0xff); output.write(marker)
                output.write(length ushr 8); output.write(length and 0xff); output.write(payload)
            } else if (marker !in 0xe0..0xef && marker != 0xfe) {
                output.write(0xff); output.write(marker)
                output.write(length ushr 8); output.write(length and 0xff); output.write(payload)
            }
            if (marker == 0xda) {
                if (!frameSeen) throw AttachmentSanitizationException()
                scanSeen = true
                inScan = true
            }
        }
    }

    private fun exifOrientation(bytes: ByteArray): Int {
        if (bytes.size < 8) throw AttachmentSanitizationException()
        val order = when (String(bytes, 0, 2, Charsets.US_ASCII)) {
            "II" -> ByteOrder.LITTLE_ENDIAN
            "MM" -> ByteOrder.BIG_ENDIAN
            else -> throw AttachmentSanitizationException()
        }
        val buffer = ByteBuffer.wrap(bytes).order(order)
        if (buffer.getShort(2).toInt() != 42) throw AttachmentSanitizationException()
        val offset = buffer.getInt(4)
        if (offset < 8 || offset > bytes.size - 2) throw AttachmentSanitizationException()
        val count = buffer.getShort(offset).toInt() and 0xffff
        if (count > (bytes.size - offset - 2) / 12) throw AttachmentSanitizationException()
        for (index in 0 until count) {
            val position = offset + 2 + index * 12
            if (buffer.getShort(position).toInt() and 0xffff == 0x0112) {
                if (buffer.getShort(position + 2).toInt() != 3 || buffer.getInt(position + 4) != 1) throw AttachmentSanitizationException()
                return (buffer.getShort(position + 8).toInt() and 0xffff).also { if (it !in 1..8) throw AttachmentSanitizationException() }
            }
        }
        return 1
    }

    private fun minimalOrientationExif(orientation: Int): ByteArray {
        val output = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        output.put("Exif\u0000\u0000II".toByteArray(Charsets.US_ASCII))
        output.putShort(42); output.putInt(8); output.putShort(1)
        output.putShort(0x0112); output.putShort(3); output.putInt(1); output.putShort(orientation.toShort()); output.putShort(0); output.putInt(0)
        return output.array()
    }

    private fun png(source: InputStream, output: OutputStream, check: () -> Unit) {
        val input = DataInputStream(source)
        val signature = ByteArray(8).also(input::readFully)
        if (!signature.contentEquals(PNG_SIGNATURE)) throw AttachmentSanitizationException()
        output.write(signature)
        var first = true
        var dataSeen = false
        val buffer = ByteArray(32 * 1024)
        while (true) {
            check()
            val length = input.readInt()
            if (length < 0) throw AttachmentSanitizationException()
            val type = ByteArray(4).also(input::readFully)
            val name = String(type, Charsets.US_ASCII)
            if ((first && (name != "IHDR" || length != 13)) || (!first && name == "IHDR")) throw AttachmentSanitizationException()
            first = false
            if (name == "IDAT" && length > 0) dataSeen = true
            if (name in PNG_ANIMATION_CHUNKS || name == "iCCP") throw AttachmentSanitizationException()
            val keep = name in PNG_RENDERING_CHUNKS
            if (!keep && type[0].toInt() and 32 == 0) throw AttachmentSanitizationException()
            val crc = CRC32().apply { update(type) }
            if (keep) { output.write(ByteBuffer.allocate(4).putInt(length).array()); output.write(type) }
            if (name == "eXIf") {
                if (length > 65535) throw AttachmentSanitizationException()
                val data = ByteArray(length).also(input::readFully)
                crc.update(data)
                if (exifOrientation(data) != 1) throw AttachmentSanitizationException()
            } else {
                transferChunk(input, if (keep) output else null, length.toLong(), buffer, check) { bytes, count -> crc.update(bytes, 0, count) }
            }
            val expected = input.readInt()
            if (crc.value.toInt() != expected) throw AttachmentSanitizationException()
            if (keep) output.write(ByteBuffer.allocate(4).putInt(expected).array())
            if (name == "IEND") { if (length != 0 || !dataSeen) throw AttachmentSanitizationException(); return }
        }
    }

    private fun webp(source: InputStream, output: OutputStream, sourceFile: File, check: () -> Unit) {
        val size = sourceFile.length()
        val input = DataInputStream(source)
        val header = ByteArray(12).also(input::readFully)
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "WEBP" ||
            (ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL) + 8 != size) throw AttachmentSanitizationException()
        var position = 12L
        var kept = 4L
        var imageSeen = false
        val buffer = ByteArray(32 * 1024)
        while (position < size) {
            check()
            val chunkHeader = ByteArray(8).also(input::readFully)
            val type = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            val padded = length + (length and 1)
            if (padded > size - position - 8) throw AttachmentSanitizationException()
            if (type in WEBP_ANIMATION_CHUNKS || type == "ICCP") throw AttachmentSanitizationException()
            when (type) {
                "VP8X" -> {
                    if (length != 10L) throw AttachmentSanitizationException()
                    val payload = ByteArray(10).also(input::readFully)
                    if (payload[0].toInt() and (2 or 0x20) != 0) throw AttachmentSanitizationException()
                    kept += 8 + padded
                }
                "EXIF" -> {
                    if (length > 65535) throw AttachmentSanitizationException()
                    val payload = ByteArray(length.toInt()).also(input::readFully)
                    val tiff = if (payload.size >= 6 && String(payload, 0, 6, Charsets.US_ASCII) == "Exif\u0000\u0000") payload.copyOfRange(6, payload.size) else payload
                    if (exifOrientation(tiff) != 1) throw AttachmentSanitizationException()
                    if (length and 1 != 0L) input.readUnsignedByte()
                }
                else -> {
                    if (type == "VP8 " || type == "VP8L") {
                        if (imageSeen) throw AttachmentSanitizationException()
                        imageSeen = true
                    }
                    if (type in WEBP_IMAGE_CHUNKS) kept += 8 + padded
                    var remaining = padded
                    while (remaining > 0) { check(); val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (count < 0) throw EOFException(); remaining -= count }
                }
            }
            position += 8 + padded
        }
        if (!imageSeen) throw AttachmentSanitizationException()
        output.write("RIFF".toByteArray(Charsets.US_ASCII)); output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(kept.toInt()).array()); output.write("WEBP".toByteArray(Charsets.US_ASCII))
        sourceFile.inputStream().buffered().use { replay ->
            skipFully(replay, 12)
            val second = DataInputStream(replay)
            var replayPosition = 12L
            while (replayPosition < size) {
                check()
                val chunkHeader = ByteArray(8).also(second::readFully)
                val type = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val length = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                val padded = length + (length and 1)
                val keep = type in WEBP_RENDERING_CHUNKS
                if (keep) output.write(chunkHeader)
                if (type == "VP8X") {
                    val payload = ByteArray(10).also(second::readFully)
                    payload[0] = (payload[0].toInt() and (0x08 or 0x04 or 0x20).inv()).toByte()
                    output.write(payload)
                } else transferChunk(second, if (keep) output else null, padded, buffer, check)
                replayPosition += 8 + padded
            }
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) { val skipped = input.skip(remaining); if (skipped <= 0) { if (input.read() < 0) throw EOFException(); remaining-- } else remaining -= skipped }
    }

    private fun transferChunk(input: InputStream, output: OutputStream?, length: Long, buffer: ByteArray, check: () -> Unit, consume: (ByteArray, Int) -> Unit = { _, _ -> }) {
        var remaining = length
        while (remaining > 0) {
            check()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException()
            consume(buffer, count); output?.write(buffer, 0, count); remaining -= count
        }
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private val PNG_ANIMATION_CHUNKS = setOf("acTL", "fcTL", "fdAT")
    private val PNG_RENDERING_CHUNKS = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB")
    private val WEBP_ANIMATION_CHUNKS = setOf("ANIM", "ANMF")
    private val WEBP_IMAGE_CHUNKS = setOf("VP8 ", "VP8L", "ALPH")
    private val WEBP_RENDERING_CHUNKS = WEBP_IMAGE_CHUNKS + "VP8X"
}
