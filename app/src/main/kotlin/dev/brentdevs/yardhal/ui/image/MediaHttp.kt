package dev.brentdevs.yardhal.ui.image

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

public sealed interface MediaProbeResult {
    public data class Image(public val mimeType: String, public val sizeBytes: Long?) : MediaProbeResult
    public data object NotImage : MediaProbeResult
    public data object Unavailable : MediaProbeResult
}

internal class MediaRequestCancellation {
    private var cancelled = false
    private var connection: HttpURLConnection? = null

    @Synchronized
    fun attach(value: HttpURLConnection) {
        if (cancelled) {
            value.disconnect()
            throw CancellationException("Media request cancelled")
        }
        connection = value
    }

    @Synchronized
    fun detach(value: HttpURLConnection) {
        if (connection === value) connection = null
        value.disconnect()
    }

    @Synchronized
    fun cancel() {
        cancelled = true
        connection?.disconnect()
        connection = null
    }

    @Synchronized
    fun check() {
        if (cancelled) throw CancellationException("Media request cancelled")
    }
}

internal class MediaHttp(private val connectionFactory: (URL) -> HttpURLConnection) {
    private class HeadResult(val result: MediaProbeResult?)
    fun probe(url: String, cancellation: MediaRequestCancellation): MediaProbeResult {
        val head = request(url, "HEAD", cancellation) { connection ->
            when {
                connection.responseCode == 405 || connection.responseCode == 501 -> HeadResult(null)
                connection.responseCode !in 200..299 -> HeadResult(MediaProbeResult.Unavailable)
                connection.contentLengthLong > MediaPolicy.MAX_IMAGE_BYTES -> HeadResult(MediaProbeResult.NotImage)
                MediaPolicy.isImageMime(connection.contentType) -> HeadResult(MediaProbeResult.Image(
                    MediaPolicy.mime(connection.contentType).orEmpty(), connection.contentLengthLong.takeIf { it >= 0 },
                ))
                !connection.contentType.isNullOrBlank() && MediaPolicy.mime(connection.contentType) != "application/octet-stream" -> HeadResult(MediaProbeResult.NotImage)
                else -> HeadResult(null)
            }
        }
        if (head == null) return MediaProbeResult.Unavailable
        head.result?.let { return it }
        return request(url, "GET", cancellation, range = true) { connection ->
            if (connection.responseCode != 200 && connection.responseCode != 206) return@request MediaProbeResult.Unavailable
            val total = if (connection.responseCode == 206) {
                connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            } else connection.contentLengthLong.takeIf { it >= 0 }
            if (total != null && total > MediaPolicy.MAX_IMAGE_BYTES) return@request MediaProbeResult.NotImage
            if (!MediaPolicy.isImageMime(connection.contentType)) return@request MediaProbeResult.NotImage
            connection.inputStream.use { input ->
                val buffer = ByteArray(MediaPolicy.PROBE_BYTES)
                var count = 0
                while (count < buffer.size) {
                    cancellation.check()
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
            }
            MediaProbeResult.Image(MediaPolicy.mime(connection.contentType).orEmpty(), total)
        } ?: MediaProbeResult.Unavailable
    }

    fun download(url: String, maxBytes: Int, video: Boolean, cancellation: MediaRequestCancellation): ByteArray? =
        request(url, "GET", cancellation) { connection ->
            if (connection.responseCode != 200 || connection.contentLengthLong > maxBytes) return@request null
            val mime = MediaPolicy.mime(connection.contentType)
            if (mime != null && mime != "application/octet-stream" &&
                !(if (video) MediaPolicy.isVideoMime(mime) else MediaPolicy.isImageMime(mime))) return@request null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    cancellation.check()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size().toLong() + read > maxBytes) return@request null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }

    fun downloadVideo(url: String, target: File, cancellation: MediaRequestCancellation): Boolean =
        request(url, "GET", cancellation) { connection ->
            if (connection.responseCode != 200 || connection.contentLengthLong > MediaPolicy.MAX_VIDEO_BYTES) return@request false
            val mime = MediaPolicy.mime(connection.contentType)
            if (mime != null && mime != "application/octet-stream" && !MediaPolicy.isVideoMime(mime)) return@request false
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        cancellation.check()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MediaPolicy.MAX_VIDEO_BYTES) return@request false
                        output.write(buffer, 0, read)
                    }
                }
            }
            true
        } ?: false

    private fun <T> request(
        originalUrl: String,
        method: String,
        cancellation: MediaRequestCancellation,
        range: Boolean = false,
        consume: (HttpURLConnection) -> T?,
    ): T? {
        var url = ImageUrlPolicy.resolve(originalUrl, 1) ?: return null
        for (redirect in 0..MediaPolicy.MAX_REDIRECTS) {
            cancellation.check()
            val connection = try {
                connectionFactory(URL(url))
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
            try {
                cancellation.attach(connection)
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.setRequestProperty("User-Agent", "Yardhal")
                connection.setRequestProperty("Accept", "image/*, video/*;q=0.8")
                if (range) connection.setRequestProperty("Range", "bytes=0-${MediaPolicy.PROBE_BYTES - 1}")
                if (connection.responseCode in setOf(301, 302, 303, 307, 308)) {
                    if (redirect == MediaPolicy.MAX_REDIRECTS) return null
                    url = MediaPolicy.redirect(url, connection.getHeaderField("Location")) ?: return null
                } else {
                    return consume(connection)
                }
            } catch (_: IOException) {
                cancellation.check()
                return null
            } catch (_: SecurityException) {
                return null
            } finally {
                cancellation.detach(connection)
            }
        }
        return null
    }
}
