package dev.brentdevs.yardhal.core.protocol

public data class MultilineLimits(
    public val maxBytes: Int,
    public val maxLines: Int? = null,
) {
    public companion object {
        public fun parse(value: String?): MultilineLimits? {
            if (value.isNullOrEmpty()) return null
            var maxBytes: Int? = null
            var maxLines: Int? = null
            for (token in value.split(',')) {
                val key = token.substringBefore('=')
                val raw = token.substringAfter('=', "")
                when (key) {
                    "max-bytes" -> maxBytes = raw.toIntOrNull()?.takeIf { it > 0 }
                    "max-lines" -> maxLines = raw.toIntOrNull()?.takeIf { it > 0 }
                }
            }
            return maxBytes?.let { MultilineLimits(it, maxLines) }
        }
    }
}

public data class MultilineLine(
    public val text: String,
    public val concat: Boolean = false,
)

public object IrcMultiline {
    public const val CAPABILITY: String = "draft/multiline"
    public const val BATCH_TYPE: String = "draft/multiline"
    public const val CONCAT_TAG: String = "draft/multiline-concat"

    private const val FIXED_OVERHEAD = 14
    private const val SAFETY_MARGIN = 10
    private const val WORST_CASE_USER_HOST = 20 + 63
    private const val MINIMUM_LINE_BYTES = 64

    public fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")

    public fun lineBudget(nick: String, target: String): Int {
        val variable = utf8Length(nick) + utf8Length(target) + WORST_CASE_USER_HOST
        return maxOf(MINIMUM_LINE_BYTES, IrcMessage.BASE_MESSAGE_LIMIT_BYTES - FIXED_OVERHEAD - SAFETY_MARGIN - variable)
    }

    public fun combine(lines: List<MultilineLine>): String = buildString {
        lines.forEachIndexed { index, line ->
            if (index > 0 && !line.concat) append('\n')
            append(line.text)
        }
    }

    public fun combinedByteLength(lines: List<MultilineLine>): Int =
        lines.withIndex().sumOf { (index, line) ->
            utf8Length(line.text) + if (index > 0 && !line.concat) 1 else 0
        }

    public fun split(text: String, limits: MultilineLimits, maxLineBytes: Int): List<List<MultilineLine>> {
        val lineBytes = maxOf(1, minOf(maxLineBytes, limits.maxBytes))
        val wireLines = normalize(text).split('\n').flatMap { splitLongLine(it, lineBytes) }
        val batches = ArrayList<List<MultilineLine>>()
        var current = ArrayList<MultilineLine>()
        var currentBytes = 0
        for (line in wireLines) {
            val separator = if (current.isEmpty() || line.concat) 0 else 1
            val added = utf8Length(line.text) + separator
            val overLines = limits.maxLines?.let { current.size + 1 > it } ?: false
            if (current.isNotEmpty() && (overLines || currentBytes + added > limits.maxBytes)) {
                batches += current
                current = ArrayList()
                currentBytes = 0
            }
            if (current.isEmpty()) {
                current += line.copy(concat = false)
                currentBytes = utf8Length(line.text)
            } else {
                current += line
                currentBytes += added
            }
        }
        if (current.isNotEmpty()) batches += current
        return batches.filter { batch -> batch.any { it.text.isNotEmpty() } }
    }

    public fun frame(
        reference: String,
        command: String,
        target: String,
        lines: List<MultilineLine>,
        batchTags: Map<String, String?> = emptyMap(),
    ): List<IrcMessage> {
        val opening = IrcMessage(tags = batchTags, command = "BATCH", parameters = listOf("+$reference", BATCH_TYPE, target))
        val body = lines.map { line ->
            val tags = LinkedHashMap<String, String?>()
            tags["batch"] = reference
            if (line.concat) tags[CONCAT_TAG] = null
            IrcMessage(tags = tags, command = command, parameters = listOf(target, line.text))
        }
        val closing = IrcMessage(command = "BATCH", parameters = listOf("-$reference"))
        return listOf(opening) + body + closing
    }

    private fun splitLongLine(line: String, maxBytes: Int): List<MultilineLine> {
        if (utf8Length(line) <= maxBytes) return listOf(MultilineLine(line))
        val chunks = ArrayList<MultilineLine>()
        var remaining = line
        while (utf8Length(remaining) > maxBytes) {
            val hardCut = longestPrefixWithin(remaining, maxBytes)
            val space = remaining.lastIndexOf(' ', hardCut - 1)
            val cut = if (space > 0) space + 1 else hardCut
            chunks += MultilineLine(remaining.substring(0, cut), concat = chunks.isNotEmpty())
            remaining = remaining.substring(cut)
        }
        if (remaining.isNotEmpty()) chunks += MultilineLine(remaining, concat = true)
        return chunks
    }

    private fun longestPrefixWithin(text: String, maxBytes: Int): Int {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            val size = utf8Length(text.substring(index, index + width))
            if (bytes + size > maxBytes) break
            bytes += size
            index += width
        }
        return maxOf(index, Character.charCount(text.codePointAt(0)))
    }

    private fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size
}
