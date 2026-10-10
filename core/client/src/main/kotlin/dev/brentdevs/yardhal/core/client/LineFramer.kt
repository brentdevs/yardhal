package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage

public enum class LineRejection {
    BASE_TOO_LONG,
    TAGS_TOO_LONG,
    MISSING_TAG_SEPARATOR,
}

public class LineFramer(
    private val onRejected: (LineRejection) -> Unit = {},
    private val sink: (String) -> Unit,
) {
    private val buffer = ByteArray(IrcMessage.TAGGED_CONTENT_LIMIT_BYTES)
    private var buffered = 0
    private var readingTags = false
    private var tagSectionBytes = 0
    private var rejection: LineRejection? = null

    public fun feed(data: ByteArray, length: Int = data.size) {
        var offset = 0
        while (offset < length) {
            val newline = indexOfNewline(data, offset, length)
            if (newline < 0) {
                append(data, offset, length - offset)
                return
            }
            append(data, offset, newline - offset)
            val lineEnd = newline +
                (if (data[newline] == '\r'.code.toByte() &&
                    newline + 1 < length &&
                    data[newline + 1] == '\n'.code.toByte()
                ) 2 else 1)
            emitBuffered()
            offset = lineEnd
        }
    }

    public fun finish(): List<String> {
        if (rejection == null && readingTags) reject(LineRejection.MISSING_TAG_SEPARATOR)
        val remainder = if (rejection == null && buffered > 0) decodeBuffered() else null
        reset()
        return if (remainder == null) emptyList() else listOf(remainder)
    }

    private fun indexOfNewline(data: ByteArray, from: Int, endExclusive: Int): Int {
        for (i in from until endExclusive) {
            if (data[i] == '\n'.code.toByte() || data[i] == '\r'.code.toByte()) return i
        }
        return -1
    }

    private fun append(data: ByteArray, from: Int, count: Int) {
        if (count == 0 || rejection != null) return
        if (buffered == 0 && data[from] == '@'.code.toByte()) readingTags = true
        val baseBuffered = if (readingTags) 0 else buffered - tagSectionBytes
        var baseCount = count
        if (readingTags) {
            val scanEnd = from + minOf(count, IrcMessage.TAG_SECTION_LIMIT_BYTES - buffered)
            var separator = from
            while (separator < scanEnd && data[separator] != ' '.code.toByte()) separator++
            val foundSeparator = separator < scanEnd
            val tagCount = if (foundSeparator) separator - from + 1 else count
            val tagLimit = IrcMessage.TAG_SECTION_LIMIT_BYTES - if (foundSeparator) 0 else 1
            if (tagCount > tagLimit - buffered) {
                reject(LineRejection.TAGS_TOO_LONG)
                return
            }
            if (foundSeparator) {
                readingTags = false
                tagSectionBytes = buffered + tagCount
                baseCount -= tagCount
            } else {
                baseCount = 0
            }
        }
        if (baseCount > IrcMessage.BASE_CONTENT_LIMIT_BYTES - baseBuffered) {
            reject(LineRejection.BASE_TOO_LONG)
            return
        }
        System.arraycopy(data, from, buffer, buffered, count)
        buffered += count
    }

    private fun reject(reason: LineRejection) {
        rejection = reason
        buffered = 0
        onRejected(reason)
    }

    private fun emitBuffered() {
        if (rejection == null && readingTags) reject(LineRejection.MISSING_TAG_SEPARATOR)
        val line = if (rejection == null && buffered > 0) decodeBuffered() else null
        reset()
        if (line != null) sink(line)
    }

    private fun reset() {
        buffered = 0
        readingTags = false
        tagSectionBytes = 0
        rejection = null
    }

    private fun decodeBuffered(): String = String(buffer, 0, buffered, Charsets.UTF_8)
}
