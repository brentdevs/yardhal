package dev.brentdevs.yardhal.core.client

import java.net.URI
import java.util.Locale

public object UploadUrlPolicy {
    public const val MAXIMUM_URL_LENGTH: Int = 8192
    public fun validate(value: String, requireSecure: Boolean): URI {
        if (value.length > MAXIMUM_URL_LENGTH || value.any { it.isISOControl() || it.isWhitespace() }) throw FilehostException.UnsafeUrl()
        val uri = try { URI(value) } catch (_: Exception) { throw FilehostException.UnsafeUrl() }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0 || uri.isOpaque) {
            throw FilehostException.UnsafeUrl()
        }
        if (requireSecure && scheme != "https") throw FilehostException.InsecureTransport()
        return uri
    }

    public fun returnedUrl(endpoint: URI, location: String, requireSecure: Boolean): String {
        val resolved = try { endpoint.resolve(location) } catch (_: Exception) { throw FilehostException.UnsafeUrl() }
        return validate(resolved.toString(), requireSecure || endpoint.scheme.equals("https", ignoreCase = true)).toASCIIString()
    }

    public fun origin(value: String): String {
        val uri = validate(value, false)
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        val defaultPort = if (scheme == "https") 443 else 80
        return "$scheme://$host" + if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
    }
}
