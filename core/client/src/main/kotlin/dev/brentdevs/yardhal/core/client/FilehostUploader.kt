package dev.brentdevs.yardhal.core.client

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID

public class OutgoingFile(
    public val name: String,
    public val mimeType: String,
    public val sizeBytes: Long,
    public val openStream: () -> InputStream,
)

public sealed class FilehostException(message: String) : IOException(message) {
    public class Unauthorized : FilehostException("Upload provider rejected credentials. Update upload settings.")
    public class HttpStatus(public val code: Int) : FilehostException("Upload provider returned HTTP $code. Retry or select another provider.")
    public class MissingLocation : FilehostException("Upload provider omitted the uploaded file URL.")
    public class InsecureTransport : FilehostException("HTTPS is required by the IRC TLS/STS or authentication policy.")
    public class UnsafeUrl : FilehostException("Upload provider supplied an unsafe or unsupported URL. Select a valid HTTP(S) provider.")
    public class Cancelled : FilehostException("Upload cancelled. The staged file is available to retry.")
    public class SizeMismatch : FilehostException("The staged file changed size. Remove it and select the file again.")
    public class Transport : FilehostException("Upload connection failed. Check connectivity and retry.")
    public class LocalRead : FilehostException("Unable to read the staged file. Remove it and select the file again.")
}

public class UploadCancellation {
    private var cancelled = false
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null

    public fun cancel() {
        val resources = synchronized(this) { cancelled = true; connection to input }
        resources.first?.disconnect()
        try { resources.second?.close() } catch (_: IOException) { }
    }

    @Synchronized
    public fun check() { if (cancelled) throw FilehostException.Cancelled() }

    @Synchronized
    internal fun attach(value: HttpURLConnection) { check(); connection = value }

    @Synchronized
    internal fun attach(value: InputStream) { check(); input = value }

    @Synchronized
    internal fun detach() { connection = null; input = null }
}

public object FilehostUploader {
    public const val ISUPPORT_TOKEN: String = "soju.im/FILEHOST"
    public data class Uploaded(public val url: String)

    public fun upload(
        endpointUrl: String,
        file: OutgoingFile,
        ircConnectionIsTls: Boolean,
        saslUser: String? = null,
        saslPassword: String? = null,
        random: UUID = UUID.randomUUID(),
        cancellation: UploadCancellation = UploadCancellation(),
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Uploaded {
        require(file.sizeBytes >= 0) { "File size must be known before upload" }
        require(file.mimeType.matches(MIME_TYPE_PATTERN)) { "Invalid file MIME type" }
        val authenticated = saslUser != null && saslPassword != null
        if (saslUser != null) require(':' !in saslUser && saslUser.none { it.isISOControl() }) { "Invalid authentication username" }
        val uri = UploadUrlPolicy.validate(endpointUrl, ircConnectionIsTls || authenticated)
        cancellation.check()
        try {
            return post(uri, file, saslUser, saslPassword, cancellation, onProgress, null)
        } catch (error: FilehostException.HttpStatus) {
            cancellation.check()
            if (error.code !in BODY_REJECTION_CODES) throw error
            return post(uri, file, saslUser, saslPassword, cancellation, onProgress, random)
        }
    }

    private val BODY_REJECTION_CODES = setOf(400, 415, 422)
    private val EMPTY_BYTES = ByteArray(0)
    private val MIME_TYPE_PATTERN = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")

    private fun escapeQuoted(value: String): String {
        val sanitized = value.filterNot { it.isISOControl() }
        val end = sanitized.offsetByCodePoints(0, minOf(255, sanitized.codePointCount(0, sanitized.length)))
        return sanitized.substring(0, end).replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun post(
        uri: URI,
        file: OutgoingFile,
        user: String?,
        password: String?,
        cancellation: UploadCancellation,
        onProgress: (Long, Long) -> Unit,
        multipart: UUID?,
    ): Uploaded {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        val boundary = multipart?.let { "Boundary-$it" }
        val header = if (multipart == null) EMPTY_BYTES else (
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${escapeQuoted(file.name)}\"\r\n" +
                "Content-Type: ${file.mimeType}\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val footer = if (multipart == null) EMPTY_BYTES else "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        try {
            cancellation.attach(connection)
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Yardhal")
            if (user != null && password != null) {
                val encoded = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
                connection.setRequestProperty("Authorization", "Basic $encoded")
            }
            connection.setRequestProperty("Content-Type", if (multipart == null) file.mimeType else "multipart/form-data; boundary=$boundary")
            if (multipart == null) connection.setRequestProperty("Content-Disposition", "attachment; filename=\"${escapeQuoted(file.name)}\"")
            connection.setFixedLengthStreamingMode(Math.addExact(file.sizeBytes, (header.size + footer.size).toLong()))
            val source = try { file.openStream() } catch (_: IOException) {
                cancellation.check()
                throw FilehostException.LocalRead()
            } catch (_: SecurityException) {
                cancellation.check()
                throw FilehostException.LocalRead()
            }
            source.use { input ->
                cancellation.attach(input)
                connection.outputStream.use { output ->
                    output.write(header)
                    copy(input, output, file.sizeBytes, cancellation, onProgress)
                    output.write(footer)
                }
            }
            cancellation.check()
            return resolve(connection, uri)
        } catch (error: FilehostException) {
            throw error
        } catch (_: IOException) {
            cancellation.check()
            throw FilehostException.Transport()
        } finally {
            cancellation.detach()
            connection.disconnect()
        }
    }

    private fun copy(input: InputStream, output: OutputStream, size: Long, cancellation: UploadCancellation, onProgress: (Long, Long) -> Unit) {
        val buffer = ByteArray(32 * 1024)
        var sent = 0L
        onProgress(0, size)
        while (true) {
            cancellation.check()
            val count = try { input.read(buffer) } catch (_: IOException) {
                cancellation.check()
                throw FilehostException.LocalRead()
            } catch (_: SecurityException) {
                cancellation.check()
                throw FilehostException.LocalRead()
            }
            if (count < 0) break
            if (count == 0) continue
            if (count.toLong() > size - sent) throw FilehostException.SizeMismatch()
            output.write(buffer, 0, count)
            sent += count
            onProgress(sent, size)
        }
        if (sent != size) throw FilehostException.SizeMismatch()
    }

    private fun resolve(connection: HttpURLConnection, endpoint: URI): Uploaded = when (val status = connection.responseCode) {
        201 -> {
            val location = connection.getHeaderField("Location")?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw FilehostException.MissingLocation()
            Uploaded(UploadUrlPolicy.returnedUrl(endpoint, location, false))
        }
        401, 403 -> throw FilehostException.Unauthorized()
        else -> throw FilehostException.HttpStatus(status)
    }
}
