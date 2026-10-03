package dev.brentdevs.yardhal.core.client

public class LineFramer(
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
    private val sink: (String) -> Unit,
) {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var buffered = 0
    private var truncated = false

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
        val remainder = decodeBuffered()
        reset()
        return listOfNotNull(remainder.takeIf { it.isNotEmpty() })
    }

    private fun indexOfNewline(data: ByteArray, from: Int, endExclusive: Int): Int {
        for (i in from until endExclusive) {
            if (data[i] == '\n'.code.toByte() || data[i] == '\r'.code.toByte()) return i
        }
        return -1
    }

    private fun append(data: ByteArray, from: Int, count: Int) {
        if (count == 0) return
        val capacityLeft = maxLineBytes - buffered
        if (capacityLeft < count) truncated = true
        if (capacityLeft <= 0) return
        val take = minOf(count, capacityLeft)
        if (buffer.size < buffered + take) {
            buffer = buffer.copyOf(maxOf(buffered + take, minOf(buffer.size * 2, maxLineBytes)))
        }
        System.arraycopy(data, from, buffer, buffered, take)
        buffered += take
    }

    private fun emitBuffered() {
        val line = decodeBuffered()
        reset()
        if (line.isNotEmpty()) sink(line)
    }

    private fun reset() {
        buffered = 0
        truncated = false
    }

    private fun decodeBuffered(): String {
        val end = if (truncated) completeCodepointEnd() else buffered
        return if (end == 0) "" else String(buffer, 0, end, Charsets.UTF_8)
    }

    private fun completeCodepointEnd(): Int {
        var start = buffered
        while (start > 0 && buffered - start < MAX_UTF8_SEQUENCE_BYTES && isContinuationByte(buffer[start - 1])) start--
        if (start == 0) return buffered
        val leadIndex = start - 1
        val expected = utf8SequenceLength(buffer[leadIndex])
        return if (expected > buffered - leadIndex) leadIndex else buffered
    }

    private fun isContinuationByte(byte: Byte): Boolean = byte.toInt() and 0xC0 == 0x80

    private fun utf8SequenceLength(lead: Byte): Int {
        val value = lead.toInt() and 0xFF
        return when {
            value >= 0xF0 -> 4
            value >= 0xE0 -> 3
            value >= 0xC0 -> 2
            else -> 1
        }
    }

    public companion object {
        public const val DEFAULT_MAX_LINE_BYTES: Int = 8192
        private const val INITIAL_CAPACITY = 512
        private const val MAX_UTF8_SEQUENCE_BYTES = 3
    }
}
