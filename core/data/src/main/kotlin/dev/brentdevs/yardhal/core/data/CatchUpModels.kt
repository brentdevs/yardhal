package dev.brentdevs.yardhal.core.data

import androidx.room.Embedded
import dev.brentdevs.yardhal.core.protocol.IrcFormatting

public const val CATCH_UP_PAGE_LIMIT: Int = 200
public const val CATCH_UP_VISIBLE_LIMIT: Int = 2000

public data class CatchUpAnchor(public val networkId: String, public val rowId: Long, public val msgid: String?) {
    public val key: String get() = "$networkId|$rowId"
}

public data class CatchUpCursor(public val timestampMs: Long, public val rowId: Long)

public data class CatchUpQueryRow(
    @Embedded public val message: MessageRow,
    public val activityTimestampMs: Long,
)

public data class CatchUpRetainedScope(
    public val networkId: String,
    public val conversation: String,
    public val retainedCount: Long,
    public val oldestTimestampMs: Long,
    public val newestTimestampMs: Long,
)

public data class CatchUpQueryPage(
    public val rows: List<CatchUpQueryRow>,
    public val scopes: List<CatchUpRetainedScope>,
    public val retention: List<MessageRetentionRow>,
    public val hasMore: Boolean,
)

public data class CatchUpCandidate(
    public val message: StoredMessage,
    public val activityTimestampMs: Long,
    public val repliesToUs: Boolean,
    public val reactions: List<MessageReactionRow>,
)

public data class CatchUpMessagePage(
    public val candidates: List<CatchUpCandidate>,
    public val scopes: List<CatchUpRetainedScope>,
    public val retention: List<MessageRetentionRow>,
    public val hasMore: Boolean,
    public val next: CatchUpCursor?,
)

public enum class CatchUpFilter { ALL, UNREAD, MENTIONS, DIRECT_MESSAGES, REPLIES, REACTIONS }
public enum class CatchUpReason { MENTION, REPLY, DIRECT_MESSAGE, REACTION, UNREAD, MESSAGE }

public data class CatchUpActivity(
    public val anchor: CatchUpAnchor,
    public val message: StoredMessage,
    public val activityTimestampMs: Long,
    public val reason: CatchUpReason,
    public val filters: Set<CatchUpFilter>,
    public val reactions: List<MessageReactionRow>,
) {
    public val text: String get() = IrcFormatting.plainText(IrcFormatting.parse(message.text))
    public fun matches(filter: CatchUpFilter): Boolean = filter == CatchUpFilter.ALL || filter in filters
}

public data class CatchUpLink(
    public val canonicalUrl: String,
    public val occurrences: List<CatchUpActivity>,
) {
    public val anchor: CatchUpAnchor get() = occurrences.first().anchor
}

public data class CatchUpGroup(
    public val conversation: ConversationRef,
    public val activities: List<CatchUpActivity>,
)

public data class CatchUpCoverage(
    public val queriedMessages: Int = 0,
    public val retainedMessages: Long = 0,
    public val queriedOldestTimestampMs: Long? = null,
    public val queriedNewestTimestampMs: Long? = null,
    public val knownHistoryGaps: Int = 0,
    public val prunedScopes: Int = 0,
    public val unknownMentionMessages: Int = 0,
    public val truncatedReactionMessages: Int = 0,
    public val hasMore: Boolean = false,
    public val limitReached: Boolean = false,
) {
    public val label: String get() = buildString {
        append("Queried ").append(queriedMessages).append(" retained messages; ")
        append(retainedMessages).append(" retained at the latest query in this scope. ")
        if (hasMore) append("More retained messages remain. ")
        if (limitReached) append("The 2,000-message view limit was reached; older retained messages are not shown. ")
        if (knownHistoryGaps > 0) append(knownHistoryGaps).append(" known server-history gaps in retained conversations. ")
        if (prunedScopes > 0) append("Older local messages were pruned in ").append(prunedScopes).append(" scopes. ")
        if (unknownMentionMessages > 0) append("Mention status is unknown for ").append(unknownMentionMessages).append(" queried messages. ")
        if (truncatedReactionMessages > 0) append("Reactions were trimmed for ").append(truncatedReactionMessages).append(" queried messages. ")
        append("Only retained messages and retained reaction memberships are included; absence of known gaps does not establish complete server history.")
    }
}

public data class CatchUpState(
    public val groups: List<CatchUpGroup> = emptyList(),
    public val links: List<CatchUpLink> = emptyList(),
    public val filter: CatchUpFilter = CatchUpFilter.ALL,
    public val coverage: CatchUpCoverage = CatchUpCoverage(),
    public val loading: Boolean = false,
    public val error: String? = null,
)

public fun catchUpActivity(candidate: CatchUpCandidate, cursor: ReadCursor): CatchUpActivity? {
    val message = candidate.message
    if (message.redacted || message.historyContext || message.conversation.kind == ConversationKind.SERVER ||
        (message.kind != MessageKind.PRIVMSG && message.kind != MessageKind.NOTICE && message.kind != MessageKind.ACTION)
    ) return null
    val filters = buildSet {
        if (!message.sentByUs && !message.playback && ReadCursor(message.timestampMs, message.rowId) > cursor) add(CatchUpFilter.UNREAD)
        if (!message.sentByUs && message.highlightsKnown && message.highlightsMe) add(CatchUpFilter.MENTIONS)
        if (!message.sentByUs && message.conversation.kind == ConversationKind.DIRECT_MESSAGE) add(CatchUpFilter.DIRECT_MESSAGES)
        if (!message.sentByUs && candidate.repliesToUs) add(CatchUpFilter.REPLIES)
        if (message.sentByUs && candidate.reactions.isNotEmpty()) add(CatchUpFilter.REACTIONS)
    }
    val reason = when {
        CatchUpFilter.MENTIONS in filters -> CatchUpReason.MENTION
        CatchUpFilter.REPLIES in filters -> CatchUpReason.REPLY
        CatchUpFilter.DIRECT_MESSAGES in filters -> CatchUpReason.DIRECT_MESSAGE
        CatchUpFilter.REACTIONS in filters -> CatchUpReason.REACTION
        CatchUpFilter.UNREAD in filters -> CatchUpReason.UNREAD
        else -> CatchUpReason.MESSAGE
    }
    return CatchUpActivity(
        CatchUpAnchor(message.networkId, message.rowId, message.msgid), message,
        candidate.activityTimestampMs, reason, filters, candidate.reactions,
    )
}
