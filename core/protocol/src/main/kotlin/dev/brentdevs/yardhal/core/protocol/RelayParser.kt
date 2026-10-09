package dev.brentdevs.yardhal.core.protocol

public enum class RelayFormat { ANGLE, BRACKET, COLON }

public data class RelayedMessage(public val sender: String, public val body: String, public val source: String)

public object RelayParser {
    public fun parse(
        wireSender: String,
        body: String,
        allowedSender: String,
        format: RelayFormat,
        mapping: CaseMapping = CaseMapping.RFC1459,
    ): RelayedMessage? {
        if (allowedSender.isBlank() || mapping.fold(wireSender) != mapping.fold(allowedSender)) return null
        val delimiter = when (format) {
            RelayFormat.ANGLE -> if (body.startsWith('<')) "> " else return null
            RelayFormat.BRACKET -> if (body.startsWith('[')) "] " else return null
            RelayFormat.COLON -> ": "
        }
        val start = if (format == RelayFormat.COLON) 0 else 1
        val end = body.indexOf(delimiter, start)
        if (end <= start || end - start > 64) return null
        val sender = body.substring(start, end)
        if (sender.any { it.isWhitespace() || it.isISOControl() || it in "<>[]:" }) return null
        val text = body.substring(end + delimiter.length)
        if (text.isBlank() || text.any { it == '\u0000' || it == '\r' }) return null
        return RelayedMessage(sender, text, wireSender)
    }
}
