package dev.brentdevs.yardhal.core.data

public data class ThemeColors(
    public val background: Long,
    public val primary: Long,
    public val secondary: Long,
    public val tertiary: Long,
    public val surfaceVariant: Long,
)

public data class ThemeDefinition(
    public val name: String,
    public val dark: Boolean,
    public val colors: ThemeColors,
)

public object ThemeFileParser {

    private val FALLBACK_BACKGROUND: Long = 0xFF101418UL.toLong()
    private val FALLBACK_PRIMARY: Long = 0xFF9ACBFFUL.toLong()

    public fun parse(text: String): ThemeDefinition? {
        var name: String? = null
        var dark = true
        val values = LinkedHashMap<String, Long>()

        for (rawLine in text.lines()) {
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("[") -> Unit
                line.startsWith("\"") -> Unit
                else -> {
                    val separator = line.indexOf('=')
                    if (separator > 0) {
                        val key = line.substring(0, separator).trim()
                        val value = line.substring(separator + 1).trim().trim('"')
                        when (key) {
                            "name" -> name = value
                            "dark" -> dark = value == "true"
                            else -> parseColor(value)?.let { values[key] = it }
                        }
                    }
                }
            }
        }

        val resolvedName = name ?: return null
        return ThemeDefinition(
            name = resolvedName,
            dark = dark,
            colors = ThemeColors(
                background = values["background"] ?: FALLBACK_BACKGROUND,
                primary = values["primary"] ?: FALLBACK_PRIMARY,
                secondary = values["secondary"] ?: blend(values["background"], values["primary"]),
                tertiary = values["tertiary"] ?: blend(values["background"], values["primary"]),
                surfaceVariant = values["surface_variant"]
                    ?: values["surfaceVariant"]
                    ?: blend(values["background"], 0xFFCCCCCCUL.toLong()),
            ),
        )
    }

    private fun stripComment(line: String): String {
        var inQuotes = false
        for (index in line.indices) {
            val ch = line[index]
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == '#' && !inQuotes -> return line.substring(0, index)
            }
        }
        return line
    }

    private fun parseColor(raw: String): Long? =
        raw.removePrefix("#").toLongOrNull(16)?.let { 0xFF000000UL.toLong() or it }

    private fun blend(base: Long?, overlay: Long?): Long {
        val b = base ?: return overlay ?: FALLBACK_BACKGROUND
        val o = overlay ?: return b
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val or = (o shr 16) and 0xFF
        val og = (o shr 8) and 0xFF
        val ob = o and 0xFF
        val mix = { x: Long, y: Long -> (x + y) / 2 }
        return 0xFF000000UL.toLong() or
            (mix(br, or) shl 16) or
            (mix(bg, og) shl 8) or
            mix(bb, ob)
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
