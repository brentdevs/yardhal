package dev.brentdevs.yardhal.core.data

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import dev.brentdevs.yardhal.core.protocol.IrcFormatting

private val CATCH_UP_URL = Regex("""https?://[^\s<>\"{}|\\^`\[\]]+""", RegexOption.IGNORE_CASE)
private val CATCH_UP_ESCAPE = Regex("%[0-9a-fA-F]{2}")

public fun canonicalCatchUpUrl(value: String): String? {
    if (value.any { it.isWhitespace() || it.isISOControl() } || '\\' in value) return null
    val uri = try { URI(value) } catch (_: URISyntaxException) { return null }
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (uri.rawUserInfo != null || uri.isOpaque) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val port = uri.port
    if (port < -1 || port > 65535) return null
    val canonicalPort = if (port == -1 || scheme == "http" && port == 80 || scheme == "https" && port == 443) "" else ":$port"
    val path = uri.rawPath.orEmpty().ifEmpty { "/" }
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
    return CATCH_UP_ESCAPE.replace("$scheme://$host$canonicalPort$path$query$fragment") { it.value.uppercase(Locale.ROOT) }
}

public fun catchUpUrls(message: StoredMessage): List<String> {
    if (message.redacted) return emptyList()
    val text = IrcFormatting.plainText(IrcFormatting.parse(message.text))
    return buildSet {
        for (match in CATCH_UP_URL.findAll(text)) {
            var value = match.value.trimEnd('.', ',', ';', '!', '?')
            while (value.endsWith(')') && value.count { it == ')' } > value.count { it == '(' }) value = value.dropLast(1)
            canonicalCatchUpUrl(value)?.let { add(it) }
        }
        message.attachmentUrl?.let { canonicalCatchUpUrl(it)?.let { url -> add(url) } }
    }.toList()
}

public fun catchUpLinks(activities: List<CatchUpActivity>, filter: CatchUpFilter): List<CatchUpLink> {
    val links = linkedMapOf<String, MutableList<CatchUpActivity>>()
    for (activity in activities) {
        if (!activity.matches(filter)) continue
        for (url in catchUpUrls(activity.message)) links.getOrPut(url) { mutableListOf() }.add(activity)
    }
    return links.map { (url, occurrences) -> CatchUpLink(url, occurrences.toList()) }
}
