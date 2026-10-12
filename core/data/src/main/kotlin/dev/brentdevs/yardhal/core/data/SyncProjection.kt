package dev.brentdevs.yardhal.core.data

import java.nio.charset.CharacterCodingException
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

public object SyncProjection {
    private val networkFields = setOf("id", "name", "host", "port", "tls", "nick", "username", "realName", "autojoin",
        "alternateNicks", "saslAuthcid", "saslMode", "nickServAccount", "nickServService", "waitForNickServ", "mode",
        "bouncerParentId", "bouncerNetId", "zncNetwork")
    private val colorFields = setOf("background", "primary", "secondary", "tertiary", "surfaceVariant")

    public fun network(config: NetworkConfig): JsonObject = JsonObject(mapOf(
        "id" to JsonPrimitive(config.id), "name" to JsonPrimitive(config.name), "host" to JsonPrimitive(config.host),
        "port" to JsonPrimitive(config.port), "tls" to JsonPrimitive(config.tls), "nick" to JsonPrimitive(config.nick),
        "username" to JsonPrimitive(config.username), "realName" to JsonPrimitive(config.realName),
        "autojoin" to kotlinx.serialization.json.JsonArray(config.autojoin.map(::JsonPrimitive)),
        "alternateNicks" to kotlinx.serialization.json.JsonArray(config.alternateNicks.map(::JsonPrimitive)),
        "saslAuthcid" to nullable(config.saslAuthcid), "saslMode" to JsonPrimitive(config.saslMode.name),
        "nickServAccount" to nullable(config.nickServAccount), "nickServService" to JsonPrimitive(config.nickServService),
        "waitForNickServ" to JsonPrimitive(config.waitForNickServ), "mode" to JsonPrimitive(config.mode.name),
        "bouncerParentId" to nullable(config.bouncerBinding?.parentId), "bouncerNetId" to nullable(config.bouncerBinding?.netId),
        "zncNetwork" to nullable(config.zncNetwork),
    )).also { requireSafe("network", config.id, it) }

    public fun read(timestampMs: Long): JsonObject = JsonObject(mapOf("timestampMs" to JsonPrimitive(timestampMs)))
        .also { require(timestampMs >= 0) }

    public fun canSync(kind: String, key: String, value: JsonElement?): Boolean = try {
        requireSafe(kind, key, value)
        true
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: CharacterCodingException) {
        false
    }

    public fun requireSafe(kind: String, key: String, value: JsonElement?) {
        require(kind in CONFIG_KINDS || kind in INTERACTION_KINDS) { "Unsupported sync kind" }
        require(key.length in 1..512 && key.none { it.isISOControl() } && !key.contains("//")) { "Invalid sync key" }
        requireKey(kind, key)
        if (value == null || value == JsonNull) {
            require(kind != "read" && kind != "redaction") { "Sync $kind metadata cannot be removed" }
            return
        }
        val objectValue = value as? JsonObject ?: throw IllegalArgumentException("Sync value must be an object")
        require(objectValue.toString().toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "Sync value is too large" }
        when (kind) {
            "network" -> {
                fields(objectValue, networkFields)
                require(text(objectValue, "id") == key)
                for (field in listOf("name", "nick", "username", "realName", "nickServService", "saslMode", "mode")) text(objectValue, field)
                for (field in listOf("saslAuthcid", "nickServAccount", "bouncerParentId", "bouncerNetId", "zncNetwork")) nullableText(objectValue, field)
                for (field in listOf("autojoin", "alternateNicks")) {
                    val entries = objectValue[field] as? kotlinx.serialization.json.JsonArray ?: throw IllegalArgumentException("Invalid $field")
                    require(entries.size <= 200)
                    for (entry in entries) safeText(entry)
                }
                require(number(objectValue, "port") in 1..65535)
                for (field in listOf("tls", "waitForNickServ")) boolean(objectValue, field)
                val host = safeText(objectValue["host"] ?: throw IllegalArgumentException("Missing host"))
                require(!host.contains('/') && !host.contains('@'))
            }
            "theme" -> {
                fields(objectValue, setOf("name", "light", "dark"))
                text(objectValue, "name")
                for (variant in listOf("light", "dark")) {
                    val entry = objectValue[variant]?.takeUnless { it == JsonNull } ?: continue
                    val definition = entry as? JsonObject ?: throw IllegalArgumentException("Invalid theme variant")
                    fields(definition, setOf("name", "dark", "colors"))
                    text(definition, "name")
                    boolean(definition, "dark")
                    val colors = definition["colors"] as? JsonObject ?: throw IllegalArgumentException("Invalid colors")
                    fields(colors, colorFields)
                    for (color in colorFields) require(number(colors, color) in 0xFF000000L..0xFFFFFFFFL)
                }
                require(objectValue["light"] != JsonNull || objectValue["dark"] != JsonNull)
            }
            "order" -> {
                fields(objectValue, setOf("after"))
                nullableText(objectValue, "after")?.let { after ->
                    require(after != key && after.substringBefore('/') == key.substringBefore('/'))
                    requireKey("order", after)
                    if (key.startsWith("channel/")) require(after.substringBeforeLast('/') == key.substringBeforeLast('/'))
                }
            }
            "pin", "mute" -> { fields(objectValue, setOf("enabled")); boolean(objectValue, "enabled") }
            "group" -> { fields(objectValue, setOf("name")); text(objectValue, "name") }
            "membership" -> { fields(objectValue, setOf("group")); nullableText(objectValue, "group") }
            "selection" -> { fields(objectValue, setOf("id")); nullableText(objectValue, "id") }
            "accent" -> { fields(objectValue, setOf("color")); require(number(objectValue, "color") in 0xFF000000L..0xFFFFFFFFL) }
            "avatar" -> { fields(objectValue, setOf("show")); boolean(objectValue, "show") }
            "read" -> { fields(objectValue, setOf("timestampMs")); require(number(objectValue, "timestampMs") >= 0) }
            "reply" -> {
                fields(objectValue, setOf("parentMsgid"))
                require(!looksLikeUrl(text(objectValue, "parentMsgid")))
            }
            "reaction" -> { fields(objectValue, setOf("present")); boolean(objectValue, "present") }
            "redaction" -> { fields(objectValue, setOf("deleted")); require(boolean(objectValue, "deleted")) }
            "attachment" -> {
                fields(objectValue, setOf("name", "mimeType", "sizeBytes", "width", "height"))
                nullableText(objectValue, "name")?.let {
                    require(!it.contains('/') && !it.contains('\\') && !looksLikeUrl(it))
                }
                nullableText(objectValue, "mimeType")?.let { require(!it.contains('\\') && !looksLikeUrl(it)) }
                for (field in listOf("sizeBytes", "width", "height")) {
                    objectValue[field]?.takeUnless { it == JsonNull }?.let { require(safeNumber(it) >= 0) }
                }
            }
        }
    }

    private fun requireKey(kind: String, key: String) {
        if (kind == "selection" || kind == "avatar") { require(key == "global"); return }
        val parts = key.split('/')
        if (kind == "order") {
            require(parts.size in 2..3 && parts[0] in setOf("network", "channel", "group"))
            require(parts.size == if (parts[0] == "channel") 3 else 2)
            if (parts[0] == "group") groupId(parts[1]) else uuid(parts[1])
            if (parts.size == 3) keyPart(parts[2])
            return
        }
        if (kind == "theme" && parts.size == 1 && parts[0].matches(Regex("bundled-[0-9]+"))) return
        if (kind == "group") groupId(parts[0]) else uuid(parts[0])
        when (kind) {
            "network", "group", "theme" -> require(parts.size == 1)
            "pin", "membership", "mute", "accent", "read" -> { require(parts.size == 2); keyPart(parts[1]) }
            "reply", "attachment", "redaction" -> {
                require(parts.size == 3)
                keyPart(parts[1])
                keyPart(parts[2], rejectUrl = true)
            }
            "reaction" -> {
                require(parts.size == 5)
                keyPart(parts[1])
                parts.drop(2).forEach { keyPart(it, rejectUrl = true) }
            }
        }
    }

    private fun keyPart(encoded: String, rejectUrl: Boolean = false) {
        val value = SyncLedger.decodeKeyPart(encoded)
        if (rejectUrl) require(!looksLikeUrl(value))
    }

    private fun groupId(value: String) {
        uuid(value.removePrefix("g-"))
    }

    private fun uuid(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "Sync key must contain a UUID" }
    }

    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun fields(value: JsonObject, expected: Set<String>) { require(value.keys == expected) { "Unsupported sync fields" } }
    private fun safeText(value: JsonElement): String {
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Expected text")
        require(primitive.isString && primitive.content.length <= 512 && primitive.content.none { it.isISOControl() })
        return primitive.content
    }
    private fun looksLikeUrl(value: String): Boolean = URL_PATTERN.containsMatchIn(value)
    private val URL_PATTERN = Regex(
        "(?i)(?:[a-z][a-z0-9+.-]*://|www\\.|(?:https?|ftp|file|content|data|ircs?|yardhal|magnet|mailto|tel|sms|javascript|blob|s3|ipfs):)",
    )
    private fun text(value: JsonObject, field: String): String = safeText(value[field] ?: throw IllegalArgumentException("Missing $field"))
    private fun nullableText(value: JsonObject, field: String): String? = value[field]?.takeUnless { it == JsonNull }?.let(::safeText)
    private fun boolean(value: JsonObject, field: String): Boolean =
        (value[field] as? JsonPrimitive)?.booleanOrNull ?: throw IllegalArgumentException("Invalid $field")
    private fun safeNumber(value: JsonElement): Long {
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Expected integer")
        require(!primitive.isString)
        return primitive.longOrNull ?: throw IllegalArgumentException("Expected integer")
    }
    private fun number(value: JsonObject, field: String): Long = safeNumber(value[field] ?: throw IllegalArgumentException("Missing $field"))
}
