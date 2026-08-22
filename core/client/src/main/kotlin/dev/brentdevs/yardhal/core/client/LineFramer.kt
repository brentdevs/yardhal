package dev.brentdevs.yardhal.core.client

public class LineFramer(
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
    private val sink: (String) -> Unit,
) {
    private var buffer = ByteArray(0)
    private var buffered = 0

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
        buffer = ByteArray(0)
        buffered = 0
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
        if (capacityLeft <= 0) return
        val take = minOf(count, capacityLeft)
        if (buffer.size < buffered + take) {
            buffer = buffer.copyOf(maxOf(buffered + take, buffer.size * 2, 256))
        }
        System.arraycopy(data, from, buffer, buffered, take)
        buffered += take
    }

    private fun emitBuffered() {
        val line = decodeBuffered()
        buffer = ByteArray(0)
        buffered = 0
        if (line.isNotEmpty()) sink(line)
    }

    private fun decodeBuffered(): String =
        if (buffered == 0) "" else String(buffer, 0, buffered, Charsets.UTF_8)

    public companion object {
        public const val DEFAULT_MAX_LINE_BYTES: Int = 8192
    }
}
