package dev.brentdevs.yardhal.media

import dev.brentdevs.yardhal.core.data.PhotoMetadataPolicy
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentSanitizerTests {
    @get:Rule val temporary = TemporaryFolder()

    private fun sanitize(bytes: ByteArray, mime: String, policy: PhotoMetadataPolicy = PhotoMetadataPolicy.STRIP, maximum: Long = 1024 * 1024): ByteArray {
        val source = File(temporary.root, "source").apply { writeBytes(bytes) }
        val output = File(temporary.root, "sanitized")
        AttachmentSanitizer.sanitize(source, output, mime, policy, maximum)
        return output.readBytes()
    }

    @Test
    fun jpegStripsLocationAndCommentsWhileRetainingOnlyOrientationAndPixels() {
        val base = encodedBitmap(2, 3, Bitmap.CompressFormat.JPEG)
        val exif = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("Exif\u0000\u0000II".toByteArray()); putShort(42); putInt(8); putShort(1)
            putShort(0x0112); putShort(3); putInt(1); putShort(6); putShort(0); putInt(0)
            put("GPS-private-home".toByteArray())
        }.array()
        val app = byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, (exif.size + 2).toByte()) + exif
        val source = base.copyOfRange(0, 2) + app + base.copyOfRange(2, base.size)
        val result = sanitize(source, "image/jpeg")
        assertFalse(String(result, Charsets.ISO_8859_1).contains("GPS-private-home"))
        val offset = String(result, Charsets.ISO_8859_1).indexOf("Exif")
        assertTrue(offset >= 0)
        assertEquals(6, result[offset + 24].toInt())
        val decoded = assertNotNull(BitmapFactory.decodeByteArray(result, 0, result.size))
        assertEquals(2, decoded.width)
        assertEquals(3, decoded.height)
    }

    @Test
    fun adobeCmykJpegKeepsDecodedColorInterpretationWithoutRetainingLocationMetadata() {
        val cmyk = Base64.getDecoder().decode("/9j/7gAOQWRvYmUAZAAAAAAA/9sAQwABAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB/8AAFAgAAgACBEMRAE0RAFkRAEsRAP/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/aAA4EQwBNAFkASwAAPwD+jr/gnt/wT2/YF+Pf7Av7D3x0+On7D37IHxo+Nvxo/ZA/Zp+LHxi+MXxZ/Zp+C/xG+KfxY+KfxG+C/grxh8QfiX8S/iF4w8Fax4t8d/EDx34t1jV/FPjLxl4p1fVfEfifxHqupa3repX2p311dS/18/8ABJ3/AJRZf8E0/wDswD9jf/1nX4c1/Xz/AMOnf+CWX/SNP9gD/wAQ3/Z1/wDnc1/fxX//2Q==")
        val metadata = "private-location".toByteArray()
        val source = cmyk.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, (metadata.size + 2).toByte()) + metadata + cmyk.copyOfRange(2, cmyk.size)
        val stripped = sanitize(source, "image/jpeg")
        assertFalse(String(stripped, Charsets.ISO_8859_1).contains("private-location"))
        val before = assertNotNull(BitmapFactory.decodeByteArray(source, 0, source.size))
        val after = assertNotNull(BitmapFactory.decodeByteArray(stripped, 0, stripped.size))
        try {
            assertEquals(before.width, after.width)
            assertEquals(before.height, after.height)
            for (y in 0 until before.height) for (x in 0 until before.width) {
                assertEquals(before.getPixel(x, y), after.getPixel(x, y))
            }
        } finally {
            before.recycle()
            after.recycle()
        }
    }

    @Test
    fun pngDropsTextLocationMetadataButKeepsPixelChunksAndRejectsAnimation() {
        val png = encodedBitmap(2, 2, Bitmap.CompressFormat.PNG)
        val prefixEnd = 8 + 12 + 13
        val text = chunk("tEXt", "GPS\u0000private-home".toByteArray())
        val source = png.copyOfRange(0, prefixEnd) + text + png.copyOfRange(prefixEnd, png.size)
        val result = sanitize(source, "image/png")
        assertFalse(String(result, Charsets.ISO_8859_1).contains("private-home"))
        assertEquals(2, assertNotNull(BitmapFactory.decodeByteArray(result, 0, result.size)).width)
        val animated = png.copyOfRange(0, prefixEnd) + chunk("acTL", ByteArray(8)) + png.copyOfRange(prefixEnd, png.size)
        assertFailsWith<AttachmentSanitizationException> { sanitize(animated, "image/png") }
        assertContentEquals(animated, sanitize(animated, "image/png", PhotoMetadataPolicy.KEEP))
    }

    @Test
    fun gifNeverSilentlyFlattensEvenWhenProviderMislabelsItAsBinary() {
        val gif = Base64.getDecoder().decode("R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBCgABACwAAAAAAgACAIEAAP8AAAAAAAAAAAAIBgABCAQQEAA7")
        assertFailsWith<AttachmentSanitizationException> { sanitize(gif, "application/octet-stream") }
        assertContentEquals(gif, sanitize(gif, "image/gif", PhotoMetadataPolicy.KEEP))
    }

    @Test
    fun staticWebpStripsExifXmpAndCorrectsContainerFlagsAndLength() {
        val vp8x = byteArrayOf(0x0c, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val exif = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("II".toByteArray()); putShort(42); putInt(8); putShort(1); putShort(0x0112); putShort(3); putInt(1); putShort(1); putShort(0); putInt(0)
        }.array()
        val content = webpChunk("VP8X", vp8x) + webpChunk("EXIF", exif) + webpChunk("XMP ", "private-location".toByteArray()) + webpChunk("VP8 ", byteArrayOf(1, 2))
        val source = "RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(content.size + 4).array() + "WEBP".toByteArray() + content
        val result = sanitize(source, "image/webp")
        assertFalse(String(result).contains("private-location"))
        assertFalse(String(result).contains("EXIF"))
        assertEquals(0, result[20].toInt())
        assertEquals(result.size - 8, ByteBuffer.wrap(result, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun unknownImagesAndMalformedPhotosRequireExplicitKeepPolicy() {
        assertFailsWith<AttachmentSanitizationException> { sanitize(byteArrayOf(1, 2, 3), "image/heic") }
        assertFailsWith<AttachmentSanitizationException> { sanitize(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), "image/jpeg") }
        assertContentEquals(byteArrayOf(1, 2, 3), sanitize(byteArrayOf(1, 2, 3), "image/heic", PhotoMetadataPolicy.KEEP))
    }

    @Test
    fun unknownLengthAndSanitizedOutputEnforceTheExactSizeBoundary() {
        val exact = ByteArrayOutputStream()
        assertEquals(4L, copyBoundedAttachment(ByteArrayInputStream(ByteArray(4)), exact, 4))
        assertFailsWith<AttachmentSizeException> { copyBoundedAttachment(ByteArrayInputStream(ByteArray(5)), ByteArrayOutputStream(), 4) }
        assertFailsWith<AttachmentSizeException> { sanitize(ByteArray(5), "application/octet-stream", maximum = 4) }
        val limited = BoundedAttachmentOutput(ByteArrayOutputStream(), 4)
        limited.write(ByteArray(4))
        assertFailsWith<AttachmentSizeException> { limited.write(0) }
    }

    private fun encodedBitmap(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        return try {
            ByteArrayOutputStream().also { bitmap.compress(format, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun chunk(type: String, bytes: ByteArray): ByteArray {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(name); update(bytes) }
        return ByteBuffer.allocate(4).putInt(bytes.size).array() + name + bytes + ByteBuffer.allocate(4).putInt(crc.value.toInt()).array()
    }
    private fun webpChunk(type: String, bytes: ByteArray): ByteArray = type.toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array() + bytes + if (bytes.size % 2 == 0) ByteArray(0) else byteArrayOf(0)
}
