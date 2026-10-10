package dev.brentdevs.yardhal.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public class CatchUpController(
    private val messages: MessageStore,
    private val dismissals: CatchUpStore,
    private val readCursor: (ConversationRef) -> ReadCursor,
    private val historyGaps: (ConversationRef) -> List<StoredHistoryGap>,
    private val onJump: suspend (StoredMessage) -> Unit,
    private val onRead: suspend (ConversationRef, ReadCursor) -> Unit,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CatchUpState())
    public val state: StateFlow<CatchUpState> = mutableState.asStateFlow()
    private var networkIds: List<String> = emptyList()
    private var next: CatchUpCursor? = null
    @Volatile private var candidates: List<CatchUpCandidate> = emptyList()
    @Volatile private var scopes: List<CatchUpRetainedScope> = emptyList()
    @Volatile private var retention: List<MessageRetentionRow> = emptyList()
    @Volatile private var hasMore = false

    public suspend fun refresh(networkIds: List<String>): Unit = mutex.withLock {
        operation {
            val ids = networkIds.distinct()
            val page = withContext(Dispatchers.IO) { messages.catchUpPage(ids) }
            this.networkIds = ids
            candidates = page.candidates
            scopes = page.scopes
            retention = page.retention
            next = page.next
            hasMore = page.hasMore
            publish()
        }
    }

    public suspend fun reload(): Unit = refresh(networkIds)

    public suspend fun loadMore(): Unit = mutex.withLock {
        if (!hasMore || candidates.size >= CATCH_UP_VISIBLE_LIMIT) return@withLock
        operation {
            val page = withContext(Dispatchers.IO) {
                messages.catchUpPage(networkIds, next, minOf(CATCH_UP_PAGE_LIMIT, CATCH_UP_VISIBLE_LIMIT - candidates.size))
            }
            candidates = (candidates + page.candidates).distinctBy { it.message.rowId }
            scopes = page.scopes
            retention = page.retention
            next = page.next
            hasMore = page.hasMore
            publish()
        }
    }

    public fun setFilter(filter: CatchUpFilter) {
        mutableState.update { project(it.copy(filter = filter)) }
    }

    public suspend fun dismiss(anchor: CatchUpAnchor): Unit = mutex.withLock {
        operation {
            val candidate = candidates.firstOrNull { it.message.networkId == anchor.networkId && it.message.rowId == anchor.rowId }
                ?: return@operation
            withContext(Dispatchers.IO) { dismissals.dismiss(anchor, candidate.activityTimestampMs) }
            publish()
        }
    }

    public suspend fun restoreDismissed(): Unit = mutex.withLock {
        operation {
            withContext(Dispatchers.IO) { dismissals.restoreAll() }
            publish()
        }
    }

    public suspend fun jump(anchor: CatchUpAnchor): Boolean = mutex.withLock {
        var jumped = false
        operation {
            val message = resolve(anchor) ?: return@operation
            onJump(message)
            jumped = true
        }
        jumped
    }

    public suspend fun markRead(anchor: CatchUpAnchor): Unit = mutex.withLock {
        operation {
            val message = resolve(anchor) ?: return@operation
            onRead(message.conversation, ReadCursor(message.timestampMs, message.rowId))
            publish()
        }
    }

    private suspend fun resolve(anchor: CatchUpAnchor): StoredMessage? {
        val message = withContext(Dispatchers.IO) { messages.resolveCatchUpAnchor(anchor) }
        if (message == null) {
            candidates = candidates.filterNot { it.message.networkId == anchor.networkId && it.message.rowId == anchor.rowId }
            publish()
            mutableState.update { it.copy(error = "This message was removed, redacted, or merged. Refresh Catch Up to locate retained messages.") }
        } else {
            candidates = candidates.map {
                if (it.message.networkId == anchor.networkId && it.message.rowId == anchor.rowId) it.copy(message = message) else it
            }
        }
        return message
    }

    public suspend fun openLink(
        anchor: CatchUpAnchor,
        canonicalUrl: String,
        onOpen: (String) -> Unit,
    ): Unit = mutex.withLock {
        operation {
            val message = resolve(anchor) ?: return@operation
            if (canonicalUrl !in catchUpUrls(message)) {
                mutableState.update { it.copy(error = "This link is no longer present in the retained message. Refresh Catch Up.") }
                return@operation
            }
            onOpen(canonicalUrl)
        }
    }

    private fun publish() {
        mutableState.update { project(it.copy(error = null)) }
    }

    private fun project(state: CatchUpState): CatchUpState {
        val retained = candidates
        val activities = retained.mapNotNull { candidate ->
            catchUpActivity(candidate, readCursor(candidate.message.conversation))?.takeUnless {
                dismissals.isDismissed(it.anchor, it.activityTimestampMs)
            }
        }
        val groups = activities.filter {
            (!it.message.sentByUs || it.reason == CatchUpReason.REACTION) && it.matches(state.filter)
        }.groupBy { it.message.conversation.storageKey }.values.map { CatchUpGroup(it.first().message.conversation, it) }
        val gaps = scopes.sumOf {
            val ref = ConversationRef(it.networkId, conversationKind(it.conversation), it.conversation, it.conversation)
            historyGaps(ref).size
        }
        return state.copy(
            groups = groups,
            links = catchUpLinks(activities, state.filter),
            coverage = CatchUpCoverage(
                queriedMessages = retained.size,
                retainedMessages = scopes.sumOf { it.retainedCount },
                queriedOldestTimestampMs = retained.minOfOrNull { it.message.timestampMs },
                queriedNewestTimestampMs = retained.maxOfOrNull { it.message.timestampMs },
                knownHistoryGaps = gaps,
                prunedScopes = retention.size,
                unknownMentionMessages = retained.count { !it.message.highlightsKnown },
                truncatedReactionMessages = retained.count { it.message.reactionsTruncated },
                hasMore = hasMore,
                limitReached = hasMore && retained.size >= CATCH_UP_VISIBLE_LIMIT,
            ),
        )
    }

    private suspend fun operation(action: suspend () -> Unit) {
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            action()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            mutableState.update { it.copy(error = failure.message ?: "Catch Up could not finish. Try again.") }
        } finally {
            mutableState.update { it.copy(loading = false) }
        }
    }

    private fun conversationKind(target: String): ConversationKind = when {
        target == ConversationRef.SERVER_TARGET -> ConversationKind.SERVER
        target.firstOrNull()?.let { it in "#&" } == true -> ConversationKind.CHANNEL
        else -> ConversationKind.DIRECT_MESSAGE
    }
}
