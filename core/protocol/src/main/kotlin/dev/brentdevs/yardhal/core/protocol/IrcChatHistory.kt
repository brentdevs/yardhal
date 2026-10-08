package dev.brentdevs.yardhal.core.protocol

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

public data class HistoryAnchor(public val timestampMs: Long, public val msgid: String? = null)

public data class ChatHistorySupport(
    public val enabled: Boolean,
    public val limit: Int,
    public val referenceTypes: List<String>,
)

public object IrcChatHistory {

    private const val CLIENT_LIMIT = 100
    private val timestampFormat = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    private val defaultReferenceTypes = listOf("timestamp", "msgid")

    public fun support(isupport: ISupport, capabilities: Set<String>): ChatHistorySupport {
        val enabled = isupport.supports("CHATHISTORY") ||
            "draft/chathistory" in capabilities || "chathistory" in capabilities
        val maximum = isupport["CHATHISTORY"]?.toLongOrNull()?.takeIf { it > 0 }
        val limit = maximum?.coerceAtMost(CLIENT_LIMIT.toLong())?.toInt() ?: CLIENT_LIMIT
        val referenceTypes = if (isupport.supports("MSGREFTYPES")) {
            isupport["MSGREFTYPES"]?.split(',')
                ?.filter { it in defaultReferenceTypes }
                ?.distinct()
                .orEmpty()
        } else {
            defaultReferenceTypes
        }
        return ChatHistorySupport(enabled, limit, referenceTypes)
    }

    public fun reference(
        anchor: HistoryAnchor,
        support: ChatHistorySupport,
        preferMsgid: Boolean = false,
    ): String? {
        if (!support.enabled) return null
        val msgid = anchor.msgid?.takeIf(::safeParameter)
        if (preferMsgid && "msgid" in support.referenceTypes && msgid != null) return "msgid=$msgid"
        for (type in support.referenceTypes) {
            when (type) {
                "msgid" -> if (msgid != null) return "msgid=$msgid"
                "timestamp" -> timestampReference(anchor.timestampMs)?.let { return it }
            }
        }
        return null
    }

    public fun latest(target: String, anchor: HistoryAnchor?, support: ChatHistorySupport): String? {
        if (!canRequest(target, support)) return null
        val selector = if (anchor == null) "*" else reference(anchor, support, preferMsgid = true) ?: return null
        return "CHATHISTORY LATEST $target $selector ${support.limit}"
    }

    public fun before(target: String, anchor: HistoryAnchor, support: ChatHistorySupport): String? {
        if (!canRequest(target, support)) return null
        val selector = reference(anchor, support, preferMsgid = true) ?: return null
        return "CHATHISTORY BEFORE $target $selector ${support.limit}"
    }

    public fun between(
        target: String,
        from: HistoryAnchor,
        to: HistoryAnchor,
        support: ChatHistorySupport,
    ): String? {
        if (!canRequest(target, support)) return null
        val first = reference(from, support, preferMsgid = true) ?: return null
        val last = reference(to, support, preferMsgid = true) ?: return null
        return "CHATHISTORY BETWEEN $target $first $last ${support.limit}"
    }

    public fun targets(fromMs: Long, toMs: Long, support: ChatHistorySupport): String? {
        if (!support.enabled || support.limit <= 0) return null
        val first = timestampReference(fromMs) ?: return null
        val last = timestampReference(toMs) ?: return null
        return "CHATHISTORY TARGETS $first $last ${support.limit}"
    }

    private fun canRequest(target: String, support: ChatHistorySupport): Boolean =
        support.enabled && support.limit > 0 && safeParameter(target) && ',' !in target

    private fun safeParameter(value: String): Boolean =
        value.isNotEmpty() && !value.startsWith(':') && value.none { it.isWhitespace() || it.isISOControl() }

    private fun timestampReference(timestampMs: Long): String? {
        val timestamp = timestampFormat.format(Instant.ofEpochMilli(timestampMs))
        return timestamp.takeIf { it.length == 24 }?.let { "timestamp=$it" }
    }
}
