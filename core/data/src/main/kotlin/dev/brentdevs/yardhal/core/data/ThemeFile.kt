package dev.brentdevs.yardhal.core.data

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
public data class ThemeColors(
    public val background: Long,
    public val primary: Long,
    public val secondary: Long,
    public val tertiary: Long,
    public val surfaceVariant: Long,
)

@Serializable
public data class ThemeDefinition(
    public val name: String,
    public val dark: Boolean,
    public val colors: ThemeColors,
)

@Serializable
public data class ThemeVariants(
    public val name: String,
    public val light: ThemeDefinition? = null,
    public val dark: ThemeDefinition? = null,
) {
    public fun resolve(isDark: Boolean): ThemeDefinition =
        (if (isDark) dark ?: light else light ?: dark)
            ?: throw IllegalArgumentException("A theme needs a light or dark variant")

    public fun validated(): ThemeVariants {
        require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) {
            "Theme name must contain 1–80 printable characters"
        }
        require(light != null || dark != null) { "A theme needs a light or dark variant" }
        light?.let { require(!it.dark) { "Light variant must have dark=false" }; validateColors(it.colors) }
        dark?.let { require(it.dark) { "Dark variant must have dark=true" }; validateColors(it.colors) }
        return copy(light = light?.copy(name = name), dark = dark?.copy(name = name))
    }
}

public fun validateThemeColor(color: Long) {
    require(color in 0xFF000000L..0xFFFFFFFFL) { "Colors must be opaque #RRGGBB or #FFRRGGBB" }
}

private fun validateColors(colors: ThemeColors) {
    listOf(colors.background, colors.primary, colors.secondary, colors.tertiary, colors.surfaceVariant)
        .forEach(::validateThemeColor)
}

public object ThemeFileParser {
    public const val MAX_BYTES: Int = 16 * 1024
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val colorKeys = setOf("background", "primary", "secondary", "tertiary", "surface_variant")
    private val themeKeys = setOf("name", "dark")
    private val sectionNames = setOf("[theme]", "[colors]", "[light.colors]", "[dark.colors]")
    private val colorPattern = Regex("#[0-9a-fA-F]{6}|#[fF]{2}[0-9a-fA-F]{6}")

    public fun parse(text: String): ThemeDefinition? =
        runCatching { importTheme(text).resolve(true) }.getOrNull()

    public fun importTheme(text: String): ThemeVariants {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Theme file exceeds 16 KiB" }
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var section = ""
        for ((index, raw) in text.lineSequence().withIndex()) {
            val line = stripComment(raw).trim()
            if (line.isEmpty()) continue
            val location = "Line ${index + 1}"
            if (line.startsWith('[')) {
                require(line in sectionNames) {
                    "$location: unknown or malformed section"
                }
                section = line.substring(1, line.length - 1)
                require(section !in sections) { "$location: duplicate section $section" }
                sections[section] = linkedMapOf()
                continue
            }
            val values = sections[section] ?: throw IllegalArgumentException("$location: expected a section")
            val separator = line.indexOf('=')
            require(separator > 0) { "$location: expected key = value" }
            val key = line.substring(0, separator).trim().let { if (it == "surfaceVariant") "surface_variant" else it }
            require(key in if (section == "theme") themeKeys else colorKeys) {
                "$location: unknown key $key"
            }
            require(key !in values) { "$location: duplicate key $key" }
            val rawValue = line.substring(separator + 1).trim()
            val value = if (key == "dark") {
                require(rawValue == "true" || rawValue == "false") { "$location: dark must be true or false" }
                rawValue
            } else {
                require(rawValue.startsWith('"') && rawValue.endsWith('"')) { "$location: $key must be quoted" }
                try {
                    json.decodeFromString<String>(rawValue)
                } catch (failure: IllegalArgumentException) {
                    throw IllegalArgumentException("$location: invalid quoted $key", failure)
                }
            }
            if (section != "theme") parseColor(value)
            values[key] = value
        }
        val metadata = sections["theme"] ?: throw IllegalArgumentException("Missing [theme] section")
        val name = metadata["name"] ?: throw IllegalArgumentException("Missing theme name")
        val legacy = sections["colors"]
        require(legacy == null || ("light.colors" !in sections && "dark.colors" !in sections)) {
            "Use [colors] for one variant or [light.colors]/[dark.colors] for variants, not both"
        }
        fun definition(values: Map<String, String>, dark: Boolean): ThemeDefinition {
            val background = values["background"]?.let(::parseColor) ?: if (dark) 0xFF101418L else 0xFFFAFAFAL
            val primary = values["primary"]?.let(::parseColor) ?: if (dark) 0xFF9ACBFFL else 0xFF00639AL
            fun blend(a: Long, b: Long): Long = 0xFF000000L or
                ((((a shr 16 and 255) + (b shr 16 and 255)) / 2) shl 16) or
                ((((a shr 8 and 255) + (b shr 8 and 255)) / 2) shl 8) or
                (((a and 255) + (b and 255)) / 2)
            return ThemeDefinition(name, dark, ThemeColors(
                background, primary,
                values["secondary"]?.let(::parseColor) ?: blend(background, primary),
                values["tertiary"]?.let(::parseColor) ?: blend(background, primary),
                values["surface_variant"]?.let(::parseColor) ?: blend(background, 0xFFCCCCCCL),
            ))
        }
        if (legacy != null || ("light.colors" !in sections && "dark.colors" !in sections)) {
            val dark = metadata["dark"] != "false"
            val def = definition(legacy.orEmpty(), dark)
            return ThemeVariants(name, light = def.takeUnless { dark }, dark = def.takeIf { dark }).validated()
        }
        require("dark" !in metadata) { "Variants do not use a theme-level dark flag" }
        return ThemeVariants(
            name,
            sections["light.colors"]?.let { definition(it, false) },
            sections["dark.colors"]?.let { definition(it, true) },
        ).validated()
    }

    public fun export(theme: ThemeVariants): String = buildString {
        val valid = theme.validated()
        append("[theme]\nname = ${json.encodeToString(valid.name)}\n")
        fun variant(label: String, definition: ThemeDefinition?) {
            if (definition == null) return
            append("\n[$label.colors]\n")
            val colors = definition.colors
            for ((key, value) in listOf(
                "background" to colors.background, "primary" to colors.primary,
                "secondary" to colors.secondary, "tertiary" to colors.tertiary,
                "surface_variant" to colors.surfaceVariant,
            )) append("$key = \"#${(value and 0xFFFFFF).toString(16).padStart(6, '0')}\"\n")
        }
        variant("light", valid.light)
        variant("dark", valid.dark)
    }

    public fun parseColor(value: String): Long {
        require(colorPattern.matches(value)) {
            "Invalid color '$value': use opaque #RRGGBB or #FFRRGGBB"
        }
        return 0xFF000000L or value.removePrefix("#").toLong(16)
    }

    private fun stripComment(line: String): String {
        var inQuotes = false
        var escaped = false
        for (index in line.indices) {
            val ch = line[index]
            if (ch == '#' && !inQuotes) return line.substring(0, index)
            if (ch == '"' && !escaped) inQuotes = !inQuotes
            escaped = ch == '\\' && !escaped
        }
        return line
    }

    public const val SAMPLE_TOML: String = """
[theme]
name = "Yardhal Night"
dark = true

[colors]
background = "#101418"
primary = "#9ACBFF"
secondary = "#526069"
tertiary = "#CDBEEA"
surface_variant = "#1C2126"
"""
}

public object ThemeShareLink {
    public const val PREFIX: String = "yardhal://theme/v1/"
    public const val MAX_LENGTH: Int = 24 * 1024

    public fun encode(theme: ThemeVariants): String {
        val bytes = ThemeFileParser.export(theme).toByteArray(Charsets.UTF_8)
        require(bytes.size <= ThemeFileParser.MAX_BYTES) { "Theme exceeds portable link limit" }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    public fun decode(link: String): ThemeVariants {
        require(link.length <= MAX_LENGTH) { "Theme link exceeds 24 KiB" }
        require(link.startsWith(PREFIX)) { "Expected a yardhal://theme/v1/ link" }
        val payload = link.removePrefix(PREFIX)
        require(payload.isNotEmpty() && payload.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) {
            "Theme link contains invalid encoding"
        }
        val bytes = try {
            Base64.getUrlDecoder().decode(payload)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Theme link contains invalid Base64", failure)
        }
        require(bytes.size <= ThemeFileParser.MAX_BYTES) { "Decoded theme exceeds 16 KiB" }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == payload) {
            "Theme link uses noncanonical encoding"
        }
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Theme link is not valid UTF-8; re-export the original theme", failure)
        }
        return try {
            ThemeFileParser.importTheme(text)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid theme link: ${failure.message}", failure)
        }
    }
}
