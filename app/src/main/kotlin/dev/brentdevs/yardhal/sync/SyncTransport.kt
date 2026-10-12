package dev.brentdevs.yardhal.sync

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull

internal class SyncTransportFailure(
    val code: Int,
    val retryable: Boolean,
    description: String,
    val serverEpoch: String? = null,
) : Exception(description)

internal data class SyncEnrollment(val token: String, val epoch: String)
internal data class SyncWireChange(
    val seq: Long,
    val kind: String,
    val key: String,
    val value: JsonElement?,
    val origin: String,
)
internal data class SyncBatch(
    val epoch: String,
    val cursor: Long,
    val changes: List<SyncWireChange>,
    val accepted: List<String>,
    val rejected: List<Pair<String, String>>,
    val hasMore: Boolean,
)

internal class SyncTransport(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = false }

    fun validateEndpoint(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("Enter a valid sync server URL")
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("Sync server URL needs a host")
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.isOpaque ||
            uri.rawPath.orEmpty() !in listOf("", "/") || uri.port > 65535 || uri.port == 0 || uri.port < -1
        ) throw IllegalArgumentException("Use a sync server origin without credentials, query or path")
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val allowedLoopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
        if (uri.scheme != "https" && !(debug && uri.scheme == "http" && allowedLoopback)) {
            throw IllegalArgumentException("Sync requires HTTPS; only debug builds may use loopback HTTP")
        }
        if (host.any { it.isISOControl() }) {
            throw IllegalArgumentException("Sync requires a trusted HTTPS server")
        }
        return URI(uri.scheme, null, host, uri.port, null, null, null).toASCIIString().trimEnd('/')
    }

    suspend fun enroll(endpoint: String, account: String, secret: String, device: String): SyncEnrollment {
        val response = post(endpoint, "/v1/enroll", null, JsonObject(mapOf(
            "account" to JsonPrimitive(account), "secret" to JsonPrimitive(secret), "device" to JsonPrimitive(device),
        )))
        return SyncEnrollment(response.required("token"), response.required("epoch"))
    }

    suspend fun sync(
        endpoint: String,
        token: String,
        since: Long,
        epoch: String?,
        changes: List<JsonObject>,
    ): SyncBatch {
        val response = post(endpoint, "/v1/sync", token, JsonObject(mapOf(
            "since" to JsonPrimitive(since),
            "epoch" to (epoch?.let(::JsonPrimitive) ?: JsonNull),
            "changes" to JsonArray(changes),
            "limit" to JsonPrimitive(12),
        )))
        val remote = response.getValue("changes").jsonArray.map { raw ->
            val entry = raw.jsonObject
            SyncWireChange(entry.getValue("seq").jsonPrimitive.long, entry.required("kind"),
                entry.required("key"), entry["value"]?.takeUnless { it is JsonNull }, entry.required("origin"))
        }
        return SyncBatch(response.required("epoch"), response.getValue("cursor").jsonPrimitive.long,
            remote, response.getValue("accepted").jsonArray.map { it.jsonPrimitive.content },
            response.getValue("rejected").jsonArray.map { it.jsonObject.let { entry -> entry.required("id") to entry.required("reason") } },
            response.getValue("hasMore").jsonPrimitive.content.toBooleanStrict())
    }

    suspend fun revokeDevice(endpoint: String, token: String) {
        post(endpoint, "/v1/device/revoke", token, JsonObject(emptyMap()))
    }

    suspend fun deleteAccount(endpoint: String, token: String, secret: String) {
        post(endpoint, "/v1/account/delete", token, JsonObject(mapOf("secret" to JsonPrimitive(secret))))
    }

    private suspend fun post(endpoint: String, path: String, token: String?, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val safeEndpoint = validateEndpoint(endpoint)
        val connection = (URI(safeEndpoint + path).toURL().openConnection() as HttpURLConnection)
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        connection.doOutput = true
        try {
            val encoded = json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
            if (encoded.size > 250_000) throw SyncTransportFailure(413, false, "Too many changes to upload at once")
            connection.setFixedLengthStreamingMode(encoded.size)
            connection.outputStream.use { it.write(encoded) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val description = when (status) {
                    400, 413, 422 -> "Sync data was refused by the server; review the account and retry after an app update"
                    401, 403 -> "Sync account credentials were refused; reconnect this device"
                    404 -> "Sync endpoint is unavailable; check its server URL"
                    409 -> "Sync server changed its data generation; refreshing without discarding local changes"
                    410 -> "Sync account or device was removed; connect a valid account"
                    429, 500, 502, 503, 504 -> "Sync server is temporarily unavailable; retry later"
                    else -> "Sync server returned HTTP $status"
                }
                val serverEpoch = if (status == 409) runCatching {
                    connection.errorStream?.use { input ->
                        val bytes = input.readNBytes(4096)
                        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["epoch"]?.jsonPrimitive?.contentOrNull
                    }
                }.getOrNull() else null
                throw SyncTransportFailure(status, status == 409 || status == 429 || status >= 500, description, serverEpoch)
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 524_288) throw SyncTransportFailure(413, false, "Sync server response exceeded the size limit")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            json.parseToJsonElement(text).jsonObject
        } catch (failure: SyncTransportFailure) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw SyncTransportFailure(0, true, "Unable to reach the sync server; local IRC remains available")
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.required(key: String): String = getValue(key).jsonPrimitive.contentOrNull
        ?: throw SyncTransportFailure(422, false, "Sync server returned an incomplete response")
}
