package dev.brentdevs.yardhal.core.client

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID

public data class OutgoingFile(
    public val name: String,
    public val mimeType: String,
    public val bytes: ByteArray,
)

public sealed class FilehostException(message: String) : IOException(message) {
    public class Unauthorized(message: String = "filehost rejected credentials") : FilehostException(message)
    public class HttpStatus(public val code: Int) : FilehostException("filehost returned $code")
    public class MissingLocation : FilehostException("filehost omitted Location header")
    public class InsecureTransport : FilehostException("plain-text filehost refused over TLS IRC connection")
    public class Transport(cause: Throwable) : FilehostException(cause.message ?: "transport failure")
}

public object FilehostUploader {

    public const val ISUPPORT_TOKEN: String = "soju.im/FILEHOST"

    public data class Uploaded(val url: String)

    public fun upload(
        endpointUrl: String,
        file: OutgoingFile,
        ircConnectionIsTls: Boolean,
        saslUser: String? = null,
        saslPassword: String? = null,
        random: UUID = UUID.randomUUID(),
    ): Uploaded {
        val uri = URI(endpointUrl)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") throw FilehostException.Transport(IllegalStateException("unsupported scheme"))
        if (scheme == "http" && ircConnectionIsTls) throw FilehostException.InsecureTransport()

        try {
            return postRaw(uri.toString(), file, saslUser, saslPassword)
        } catch (error: FilehostException.HttpStatus) {
            if (error.code in BODY_REJECTION_CODES) {
                return postMultipart(uri.toString(), file, saslUser, saslPassword, random)
            }
            throw error
        }
    }

    private const val USER_AGENT = "Yardhal"

    private val BODY_REJECTION_CODES = setOf(400, 415, 422)

    private fun openConnection(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun authenticate(connection: HttpURLConnection, user: String?, password: String?) {
        if (user == null || password == null) return
        val pair = "$user:$password"
        val encoded = Base64.getEncoder().encodeToString(pair.toByteArray(Charsets.UTF_8))
        connection.setRequestProperty("Authorization", "Basic $encoded")
    }

    private fun contentDisposition(name: String): String =
        "attachment; filename=\"${escapeQuoted(name)}\""

    private fun escapeQuoted(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")

    private fun postRaw(
        url: String,
        file: OutgoingFile,
        user: String?,
        password: String?,
    ): Uploaded {
        val connection = openConnection(url)
        try {
            authenticate(connection, user, password)
            connection.setRequestProperty("Content-Type", file.mimeType)
            connection.setRequestProperty("Content-Disposition", contentDisposition(file.name))
            connection.setFixedLengthStreamingMode(file.bytes.size)
            connection.outputStream.use { it.write(file.bytes) }
            return resolve(connection)
        } catch (error: FilehostException) {
            throw error
        } catch (error: IOException) {
            throw FilehostException.Transport(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun postMultipart(
        url: String,
        file: OutgoingFile,
        user: String?,
        password: String?,
        random: UUID,
    ): Uploaded {
        val boundary = "Boundary-$random"
        val header = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"${escapeQuoted(file.name)}\"\r\n")
            append("Content-Type: ${file.mimeType}\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = header + file.bytes + footer

        val connection = openConnection(url)
        try {
            authenticate(connection, user, password)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            return resolve(connection)
        } catch (error: FilehostException) {
            throw error
        } catch (error: IOException) {
            throw FilehostException.Transport(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun resolve(connection: HttpURLConnection): Uploaded {
        val status = try {
            connection.responseCode
        } catch (error: IOException) {
            throw FilehostException.Transport(error)
        }
        when (status) {
            201 -> {
                val location = connection.getHeaderField("Location")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw FilehostException.MissingLocation()
                val resolved = try {
                    connection.url.toURI().resolve(location)
                } catch (_: Exception) {
                    null
                } ?: throw FilehostException.MissingLocation()
                if (resolved.scheme.isNullOrBlank()) throw FilehostException.MissingLocation()
                return Uploaded(resolved.toString())
            }
            401, 403 -> throw FilehostException.Unauthorized()
            else -> throw FilehostException.HttpStatus(status)
        }
    }
}
