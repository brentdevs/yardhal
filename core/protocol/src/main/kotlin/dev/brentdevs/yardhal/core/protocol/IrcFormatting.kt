package dev.brentdevs.yardhal.core.protocol

public data class TextStyle(
    public val bold: Boolean = false,
    public val italic: Boolean = false,
    public val underline: Boolean = false,
    public val strikethrough: Boolean = false,
    public val monospace: Boolean = false,
    public val reverse: Boolean = false,
    public val foreground: IrcColor? = null,
    public val background: IrcColor? = null,
)

public sealed interface IrcColor {
    public data class Standard(public val index: Int) : IrcColor
    public data class Rgb(public val red: Int, public val green: Int, public val blue: Int) : IrcColor
    public data class Rgba(public val red: Int, public val green: Int, public val blue: Int, public val alpha: Int) : IrcColor
}

public object IrcFormatting {

    private const val BOLD = '\u0002'
    private const val COLOR = '\u0003'
    private const val HEX_COLOR = '\u0004'
    private const val ITALIC = '\u001d'
    private const val REVERSE = '\u0016'
    private const val MONOSPACE = '\u0011'
    private const val RESET = '\u000f'
    private const val STRIKETHROUGH = '\u001e'
    private const val UNDERLINE = '\u001f'
    private const val RGBA_COLOR = '\u0010'

    public fun parse(text: String): List<StyledSpan> {
        if (text.none { it < ' ' }) {
            return if (text.isEmpty()) emptyList() else listOf(StyledSpan(text, TextStyle()))
        }
        val spans = ArrayList<StyledSpan>(4)
        var style = TextStyle()
        var buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                spans.add(StyledSpan(buffer.toString(), style))
                buffer = StringBuilder()
            }
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when (ch) {
                BOLD -> {
                    flush()
                    style = style.copy(bold = !style.bold)
                    i++
                }
                ITALIC -> {
                    flush()
                    style = style.copy(italic = !style.italic)
                    i++
                }
                UNDERLINE -> {
                    flush()
                    style = style.copy(underline = !style.underline)
                    i++
                }
                STRIKETHROUGH -> {
                    flush()
                    style = style.copy(strikethrough = !style.strikethrough)
                    i++
                }
                MONOSPACE -> {
                    flush()
                    style = style.copy(monospace = !style.monospace)
                    i++
                }
                REVERSE -> {
                    flush()
                    style = style.copy(reverse = !style.reverse)
                    i++
                }
                RESET -> {
                    flush()
                    style = TextStyle()
                    i++
                }
                COLOR -> {
                    flush()
                    val consumed = consumeColor(text, i + 1)
                    style = if (consumed.consumed == 0) {
                        style.copy(foreground = null, background = null)
                    } else {
                        style.copy(foreground = consumed.foreground, background = consumed.background)
                    }
                    i += 1 + consumed.consumed
                }
                HEX_COLOR -> {
                    flush()
                    val hex = readHex(text, i + 1, 6)
                    style = if (hex == null) style.copy(foreground = null, background = null) else style.copy(foreground = hexColor(hex))
                    i += 1 + (hex?.let { 6 } ?: 0)
                }
                RGBA_COLOR -> {
                    flush()
                    val hex = readHex(text, i + 1, 8)
                    style = if (hex == null) style.copy(foreground = null, background = null) else style.copy(foreground = rgbaColor(hex))
                    i += 1 + (hex?.let { 8 } ?: 0)
                }
                else -> {
                    buffer.append(ch)
                    i++
                }
            }
        }
        flush()
        val merged = ArrayList<StyledSpan>(spans.size)
        for (span in spans) {
            val previous = merged.lastOrNull()
            if (previous != null && previous.style == span.style) {
                merged[merged.size - 1] = previous.copy(text = previous.text + span.text)
            } else {
                merged.add(span)
            }
        }
        return merged
    }

    public fun plainText(spans: List<StyledSpan>): String = spans.joinToString("") { it.text }

    public data class StyledSpan(public val text: String, public val style: TextStyle)

    private class ColorConsumption(
        val foreground: IrcColor?,
        val background: IrcColor?,
        val consumed: Int,
    )

    private fun consumeColor(source: String, start: Int): ColorConsumption {
        if (start >= source.length) return ColorConsumption(null, null, 0)
        val first = readNumericOrHex(source, start)
        val foreground =
            when {
                first.digits == 6 -> rgbColor(first.value)
                else -> standardColor(first.value)
            }?.takeIf { first.digits > 0 }
            ?: return ColorConsumption(null, null, 0)

        var index = start + first.length
        var background: IrcColor? = null
        if (index < source.length && source[index] == ',') {
            val second = readNumericOrHex(source, index + 1)
            if (second.digits > 0) {
                background =
                    when {
                        second.digits == 6 -> rgbColor(second.value)
                        else -> standardColor(second.value)
                    }
                index += 1 + second.length
            }
        }
        return ColorConsumption(foreground, background, index - start)
    }

    private class NumericRead(val value: Long, val digits: Int, val length: Int)

    private fun readNumericOrHex(source: String, start: Int): NumericRead {
        if (start >= source.length) return NumericRead(0, 0, 0)
        val isHexCandidate = source[start].isDigit() || source[start] in 'a'..'f' || source[start] in 'A'..'F'
        if (!isHexCandidate) return NumericRead(0, 0, 0)
        val six = readHex(source, start, 6)
        if (six != null) return NumericRead(hexToLong(six), digits = 6, length = 6)
        var digits = 0
        var value = 0L
        var index = start
        while (index < source.length && source[index].isDigit() && digits < 2) {
            value = value * 10 + (source[index] - '0')
            digits++
            index++
        }
        return NumericRead(value, digits, index - start)
    }

    private fun readHex(source: String, start: Int, count: Int): String? {
        if (start + count > source.length) return null
        for (offset in 0 until count) {
            val ch = source[start + offset]
            if (!ch.isDigit() && ch !in 'a'..'f' && ch !in 'A'..'F') return null
        }
        return source.substring(start, start + count).lowercase()
    }

    private fun hexToLong(hex: String): Long = hex.toLong(16)

    private fun hexColor(hex: String): IrcColor.Rgb = rgbColor(hexToLong(hex))

    private fun rgbaColor(hex: String): IrcColor.Rgba {
        val value = hexToLong(hex)
        return IrcColor.Rgba(
            red = ((value shr 24) and 0xff).toInt(),
            green = ((value shr 16) and 0xff).toInt(),
            blue = ((value shr 8) and 0xff).toInt(),
            alpha = (value and 0xff).toInt(),
        )
    }

    private fun rgbColor(value: Long): IrcColor.Rgb =
        IrcColor.Rgb(
            red = ((value shr 16) and 0xff).toInt(),
            green = ((value shr 8) and 0xff).toInt(),
            blue = (value and 0xff).toInt(),
        )

    private fun standardColor(index: Long): IrcColor.Standard? =
        index.takeIf { it in 0..15 }?.toInt()?.let(IrcColor::Standard)
}
