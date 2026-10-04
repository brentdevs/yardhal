package dev.brentdevs.yardhal.ui.image

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

public object ImageUrlPolicy {
    public const val MAX_BYTES: Int = 1_048_576
    public const val MAX_URL_LENGTH: Int = 2_048
    private const val SIZE_TEMPLATE = "{size}"
    private const val READ_CHUNK_BYTES = 8_192

    public fun isAllowed(rawUrl: String): Boolean = resolve(rawUrl, 1) != null

    public fun resolve(rawUrl: String?, sizePx: Int): String? {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
        val expanded = trimmed.replace(SIZE_TEMPLATE, sizePx.coerceAtLeast(1).toString())
        val uri = runCatching { URI(expanded) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
        return uri.toASCIIString()
    }

    public fun acceptsContentLength(length: Long): Boolean = length <= MAX_BYTES

    public fun readCapped(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return output.toByteArray()
            if (output.size() + read > maxBytes) return null
            output.write(buffer, 0, read)
        }
    }

    public fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (width <= 0 || height <= 0 || targetPx <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) sample *= 2
        return sample
    }
}
