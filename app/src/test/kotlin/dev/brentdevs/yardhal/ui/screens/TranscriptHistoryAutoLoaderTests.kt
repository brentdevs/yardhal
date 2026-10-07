package dev.brentdevs.yardhal.ui.screens

import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.coordinator.ConversationHistory
import dev.brentdevs.yardhal.coordinator.HistoryGap
import dev.brentdevs.yardhal.coordinator.HistoryLoadState
import dev.brentdevs.yardhal.coordinator.HistoryLoadStatus
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.protocol.HistoryAnchor
import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptHistoryAutoLoaderTests {
    private val messages = listOf(message(1, 1_000), message(2, 2_000))
    private val gap = HistoryGap("missing", HistoryAnchor(1_000, "message-1"), HistoryAnchor(2_000, "message-2"))
    private val buffer = ConversationBuffer(
        ref = ConversationRef.channel("network", "#room"),
        displayName = "#room",
        messages = messages,
        history = ConversationHistory(gaps = listOf(gap)),
    )
    private val visibleKeys = listOf("history-older", "gap-missing")

    @Test
    fun offlineStoredPagingDoesNotConsumeTheVisibleGapAndRecoveryRetriesTheSameOlderBoundary() {
        val loader = TranscriptHistoryAutoLoader(connected = false)
        val requests = mutableListOf<String>()

        load(loader, requests, connected = false)
        load(loader, requests, connected = false)
        assertEquals(listOf("older"), requests)

        load(loader, requests, connected = true)
        load(loader, requests, connected = true)
        assertEquals(listOf("older", "older", "gap-missing"), requests)
    }

    @Test
    fun reconnectRearmsAlreadyVisibleHistoryWithoutNeedingTheUserToScrollAway() {
        val loader = TranscriptHistoryAutoLoader(connected = true)
        val requests = mutableListOf<String>()

        load(loader, requests)
        load(loader, requests, connected = false)
        load(loader, requests, connected = true)
        load(loader, requests, connected = true)

        assertEquals(listOf("older", "gap-missing", "older", "gap-missing"), requests)
    }

    @Test
    fun failuresRearmHistoryButDoNotAutomaticallyLoopWhileTheFailureRemains() {
        val loader = TranscriptHistoryAutoLoader(connected = true)
        val requests = mutableListOf<String>()
        val failure = HistoryLoadState(HistoryLoadStatus.FAILED, "Unable to send request")
        val failed = buffer.copy(history = buffer.history.copy(older = failure, gaps = listOf(gap.copy(state = failure))))

        load(loader, requests)
        load(loader, requests, current = failed)
        load(loader, requests, current = failed)
        assertEquals(listOf("older", "gap-missing"), requests)

        load(loader, requests)
        load(loader, requests)
        assertEquals(listOf("older", "gap-missing", "older", "gap-missing"), requests)
    }

    @Test
    fun reconnectRequiredCancellationBlocksAutomaticRequestsUntilRecoveryClearsTheState() {
        val loader = TranscriptHistoryAutoLoader(connected = true)
        val requests = mutableListOf<String>()
        val cancellation = HistoryLoadState(HistoryLoadStatus.CANCELLED, "Connection closed", requiresReconnect = true)
        val cancelled = buffer.copy(history = buffer.history.copy(older = cancellation, gaps = listOf(gap.copy(state = cancellation))))

        load(loader, requests)
        load(loader, requests, current = cancelled)
        load(loader, requests, current = cancelled, connected = false)
        load(loader, requests, current = cancelled, connected = true)
        assertEquals(listOf("older", "gap-missing"), requests)

        load(loader, requests)
        load(loader, requests)
        assertEquals(listOf("older", "gap-missing", "older", "gap-missing"), requests)
    }

    @Test
    fun unchangedIdleOrCompletedLoadingDoesNotCauseAnInfiniteAutomaticRetry() {
        val loader = TranscriptHistoryAutoLoader(connected = true)
        val requests = mutableListOf<String>()
        val loading = HistoryLoadState(HistoryLoadStatus.LOADING)
        val active = buffer.copy(history = buffer.history.copy(older = loading, gaps = listOf(gap.copy(state = loading))))

        load(loader, requests)
        repeat(3) { load(loader, requests) }
        load(loader, requests, current = active)
        load(loader, requests)
        load(loader, requests, keys = emptyList())
        load(loader, requests)

        assertEquals(listOf("older", "gap-missing"), requests)
    }

    @Test
    fun olderHistoryAdvancesWithTheLoadedBoundaryWithoutRepeatingAnUnchangedGap() {
        val loader = TranscriptHistoryAutoLoader(connected = true)
        val requests = mutableListOf<String>()
        val prepended = buffer.copy(messages = listOf(message(3, 500)) + messages)

        load(loader, requests)
        load(loader, requests, current = prepended)
        load(loader, requests, current = prepended)

        assertEquals(listOf("older", "gap-missing", "older"), requests)
    }

    @Test
    fun searchNavigationDefersAutomaticHistoryUntilTheTargetIsShown() {
        val loader = TranscriptHistoryAutoLoader(connected = false)
        val requests = mutableListOf<String>()

        load(loader, requests, connected = false, searchTarget = 42)
        load(loader, requests, connected = true, searchTarget = 42)
        assertEquals(emptyList(), requests)

        load(loader, requests, connected = true)
        load(loader, requests, connected = true)
        assertEquals(listOf("older", "gap-missing"), requests)
    }

    @Test
    fun recoveryOutsideTheHistoryViewportDoesNotLoadUntilTheRowsBecomeVisibleAgain() {
        val loader = TranscriptHistoryAutoLoader(connected = true)
        val requests = mutableListOf<String>()

        load(loader, requests)
        load(loader, requests, keys = emptyList(), connected = false)
        load(loader, requests, keys = emptyList(), connected = true)
        assertEquals(listOf("older", "gap-missing"), requests)

        load(loader, requests)
        load(loader, requests)
        assertEquals(listOf("older", "gap-missing", "older", "gap-missing"), requests)
    }

    private fun load(
        loader: TranscriptHistoryAutoLoader,
        requests: MutableList<String>,
        current: ConversationBuffer = buffer,
        keys: List<Any> = visibleKeys,
        connected: Boolean = true,
        searchTarget: Long? = null,
    ) {
        loader.loadVisible(
            buffer = current,
            visibleKeys = keys,
            connected = connected,
            searchTargetRowId = searchTarget,
            loadOlder = { requests.add("older") },
            fillGap = { requests.add("gap-$it") },
        )
    }

    private fun message(localId: Long, timestampMs: Long): ChatMessage = ChatMessage(
        localId = localId,
        sender = "alice",
        kind = MessageKind.PRIVMSG,
        text = "message $localId",
        timestampMs = timestampMs,
        sentByUs = false,
        highlightsMe = false,
        msgid = "message-$localId",
    )
}
