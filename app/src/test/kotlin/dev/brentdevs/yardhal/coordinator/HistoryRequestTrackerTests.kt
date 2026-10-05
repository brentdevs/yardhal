package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryRequestTrackerTests {
    private fun tracker(): HistoryRequestTracker = HistoryRequestTracker(
        fold = { value -> value.lowercase().replace('[', '{').replace(']', '}').replace('\\', '|').replace('^', '~') },
        ownNick = { "Me" },
    )

    private fun request(
        id: Long = 1L,
        target: String? = "#room",
        operation: HistoryOperation = HistoryOperation.BEFORE,
        label: String? = null,
        limit: Int = 100,
    ): PendingHistoryRequest = PendingHistoryRequest(
        id = id,
        target = target,
        operation = operation,
        line = "CHATHISTORY ${operation.name} ${target ?: ""} timestamp=1970-01-01T00:00:03.000Z $limit",
        limit = limit,
        label = label,
    )

    private fun znc(target: String = "Bob", limit: Int = 100): PendingHistoryRequest = PendingHistoryRequest(
        id = 1L,
        target = target,
        operation = HistoryOperation.BETWEEN,
        line = "ZNC *playback PLAY $target 1 3",
        limit = limit,
        transport = HistoryTransport.ZNC_PLAYBACK,
        fromTimestampMs = 1_000L,
        toTimestampMs = 3_000L,
    )

    private fun message(line: String): IrcMessage = IrcMessage.parse(line) ?: error("Invalid IRC message: $line")

    private fun HistoryRequestTracker.feed(line: String, nowMs: Long = 100L): HistoryReceipt = receive(message(line), nowMs)

    private fun chat(timestampMs: Long, sender: String, target: String, command: String = "PRIVMSG"): String =
        "@time=${Instant.ofEpochMilli(timestampMs)} :$sender!u@h $command $target :historical message"

    @Test
    fun oneRequestAtATimeAndSilenceHasAFiniteDeadline() {
        val tracker = tracker()
        val first = request()
        assertTrue(tracker.begin(first, 500L))
        assertEquals(first, tracker.pending)
        assertEquals(6_500L, tracker.deadlineMs)
        assertFalse(tracker.begin(request(id = 2L), 600L))
        assertNull(tracker.expire(6_499L))
        val result = assertNotNull(tracker.expire(6_500L))
        assertEquals(first, result.request)
        assertEquals("History request timed out", result.error)
        assertFalse(result.end)
        assertNull(tracker.pending)
        assertNull(tracker.deadlineMs)
        assertNull(tracker.expire(7_000L))
        assertTrue(tracker.requiresReconnect)
        assertFalse(tracker.begin(request(id = 2L), 7_000L))
    }

    @Test
    fun labeledCasefoldedHistoryPreservesNestedMultilineWireOrder() {
        val tracker = tracker()
        assertTrue(tracker.begin(request(target = "#[Room]", label = "first"), 0L))
        val lines = listOf(
            "@label=first;draft/chathistory-end :srv BATCH +history chathistory #{ROOM}",
            "@batch=history;msgid=original :bob!u@h BATCH +multi draft/multiline #{ROOM}",
            "@batch=multi;time=1970-01-01T00:00:01.100Z :bob!u@h PRIVMSG #{ROOM} :first line",
            "@batch=multi;draft/multiline-concat :bob!u@h PRIVMSG #{ROOM} :second line",
            ":srv BATCH -multi",
            ":srv BATCH -history",
        )
        lines.dropLast(1).forEach { line ->
            val receipt = tracker.feed(line)
            assertTrue(receipt.consumed, line)
            assertNull(receipt.result, line)
        }
        val result = assertNotNull(tracker.feed(lines.last()).result)
        assertEquals(lines.map(::message), result.frames)
        assertTrue(result.end)
        assertNull(result.error)
        assertNull(tracker.pending)
        assertFalse(tracker.requiresReconnect)
    }

    @Test
    fun outerLabeledResponseOwnsCompletionAndInheritsLabels() {
        val tracker = tracker()
        tracker.begin(request(label = "first"), 0L)
        val lines = listOf(
            "@label=first :srv BATCH +response labeled-response",
            "@batch=response :srv BATCH +history chathistory #ROOM",
            "@batch=history :bob!u@h NOTICE #room :one message",
            "@batch=response :srv BATCH -history",
            ":srv BATCH -response",
        )
        lines.dropLast(1).forEach { line ->
            assertNull(tracker.feed(line).result)
        }
        assertNotNull(tracker.pending)
        val result = assertNotNull(tracker.feed(lines.last()).result)
        assertEquals(lines.map(::message), result.frames)
        assertFalse(result.end)
        assertNull(result.error)
    }

    @Test
    fun emptyHistoryCompletesOnlyOnRootCloseAndSignalsEnd() {
        val tracker = tracker()
        tracker.begin(request(), 0L)
        assertNull(tracker.feed(":srv BATCH +empty chathistory #room").result)
        assertNotNull(tracker.pending)
        val result = assertNotNull(tracker.feed(":srv BATCH -empty").result)
        assertTrue(result.end)
        assertNull(result.error)
        assertEquals(2, result.frames.size)
    }

    @Test
    fun wrongLabelsTargetsAndUnrelatedLiveTrafficAreNotConsumed() {
        val tracker = tracker()
        tracker.begin(request(label = "wanted"), 0L)
        val unrelated = listOf(
            "@label=wrong :srv BATCH +wrong chathistory #room",
            "@batch=wrong :bob!u@h PRIVMSG #room :wrong label",
            ":srv BATCH -wrong",
            "@label=wanted :srv BATCH +other chathistory #other",
            "@batch=other :bob!u@h PRIVMSG #other :wrong target",
            ":srv BATCH -other",
            ":srv BATCH +unlabelled chathistory #room",
            "@batch=unlabelled :bob!u@h PRIVMSG #room :missing label",
            ":srv BATCH -unlabelled",
            "@label=wanted :srv BATCH +znc znc.in/playback #room",
            ":srv BATCH -znc",
            ":bob!u@h PRIVMSG #room :live message",
            ":srv PING :keepalive",
        )
        unrelated.forEach { line -> assertFalse(tracker.feed(line).consumed, line) }
        assertNotNull(tracker.pending)
        tracker.feed("@label=wanted :srv BATCH +good chathistory #ROOM")
        assertNotNull(tracker.feed(":srv BATCH -good").result)
    }

    @Test
    fun foreignOuterLabelCannotBeBypassedByUnlabelledChild() {
        val tracker = tracker()
        tracker.begin(request(), 0L)
        val lines = listOf(
            "@label=foreign :srv BATCH +outer labeled-response",
            "@batch=outer :srv BATCH +inner chathistory #room",
            "@batch=inner :bob!u@h PRIVMSG #room :unrelated replay",
            ":srv BATCH -inner",
            ":srv BATCH -outer",
        )
        lines.forEach { line -> assertFalse(tracker.feed(line).consumed, line) }
        assertNotNull(tracker.pending)
    }

    @Test
    fun explicitWrongLabelInsideOwnedHistoryRemainsIsolated() {
        val tracker = tracker()
        tracker.begin(request(label = "wanted"), 0L)
        tracker.feed("@label=wanted :srv BATCH +history chathistory #room")
        assertFalse(tracker.feed("@batch=history;label=wrong :bob!u@h PRIVMSG #room :not ours").consumed)
        assertFalse(tracker.feed("@label=wrong :srv BATCH -history").consumed)
        assertNotNull(tracker.pending)
        val result = assertNotNull(tracker.feed(":srv BATCH -history").result)
        assertEquals(2, result.frames.size)
        assertTrue(result.end)
    }

    @Test
    fun labeledFailRequiresMatchingOperationAndTarget() {
        val tracker = tracker()
        tracker.begin(request(label = "wanted"), 0L)
        val wrong = listOf(
            "@label=wrong :srv FAIL CHATHISTORY MESSAGE_ERROR BEFORE #room :Denied",
            "@label=wanted :srv FAIL CHATHISTORY MESSAGE_ERROR LATEST #room :Denied",
            "@label=wanted :srv FAIL CHATHISTORY MESSAGE_ERROR BEFORE #other :Denied",
            "@label=wanted :srv FAIL PRIVMSG INVALID_TARGET #room :Denied",
        )
        wrong.forEach { line -> assertFalse(tracker.feed(line).consumed, line) }
        val result = assertNotNull(tracker.feed("@label=wanted :srv FAIL CHATHISTORY INVALID_MSGREFTYPE BEFORE #ROOM :Unsupported reference").result)
        assertTrue(assertNotNull(result.error).contains("Unsupported reference"))
        assertFalse(result.end)
        assertNull(tracker.pending)
    }

    @Test
    fun inheritedLabelCorrelatesFailAndRetainsPartialFramesWithoutCoverage() {
        val tracker = tracker()
        tracker.begin(request(label = "wanted"), 0L)
        tracker.feed("@label=wanted :srv BATCH +outer labeled-response")
        tracker.feed("@batch=outer;draft/chathistory-end :srv BATCH +history chathistory #room")
        tracker.feed("@batch=history :bob!u@h PRIVMSG #room :partial")
        val result = assertNotNull(tracker.feed("@batch=outer :srv FAIL CHATHISTORY MESSAGE_ERROR BEFORE #room :Interrupted").result)
        assertEquals(3, result.frames.size)
        assertNotNull(result.error)
        assertFalse(result.end)
        assertTrue(tracker.feed(":srv BATCH -history").consumed)
        assertTrue(tracker.feed(":srv BATCH -outer").consumed)
    }

    @Test
    fun numericErrorsRequireTheHistoryCommandOrTarget() {
        val tracker = tracker()
        tracker.begin(request(), 0L)
        assertFalse(tracker.feed(":srv 401 Me other :No such nick").consumed)
        assertFalse(tracker.feed(":srv 421 Me WHO :Unknown command").consumed)
        val result = assertNotNull(tracker.feed(":srv 461 Me CHATHISTORY :Not enough parameters").result)
        assertEquals("Not enough parameters", result.error)
        assertFalse(tracker.requiresReconnect)
        assertTrue(tracker.begin(request(id = 2L), 200L))
        assertNotNull(tracker.feed(":srv 403 Me #ROOM :No such channel", 300L).result)
    }

    @Test
    fun timeoutRetiresOnlyMatchingRootsAndBlocksAmbiguousRetry() {
        val tracker = tracker()
        tracker.begin(request(), 0L)
        tracker.feed("@draft/chathistory-end :srv BATCH +history chathistory #room")
        tracker.feed("@batch=history :bob!u@h PRIVMSG #room :partial")
        val result = assertNotNull(tracker.expire(6_000L))
        assertEquals(2, result.frames.size)
        assertFalse(result.end)
        assertNotNull(result.error)
        assertTrue(tracker.requiresReconnect)
        assertTrue(tracker.feed("@batch=history :bob!u@h PRIVMSG #room :late", 6_100L).consumed)
        assertTrue(tracker.feed(":srv BATCH -history", 6_100L).consumed)
        assertTrue(tracker.feed(":srv BATCH +late chathistory #ROOM", 6_200L).consumed)
        assertTrue(tracker.feed("@batch=late :bob!u@h PRIVMSG #room :late root", 6_200L).consumed)
        assertTrue(tracker.feed(":srv BATCH -late", 6_200L).consumed)
        assertFalse(tracker.feed(":srv BATCH +unrelated chathistory #other", 6_200L).consumed)
        assertFalse(tracker.feed(":srv BATCH +znc znc.in/playback #room", 6_200L).consumed)
        assertFalse(tracker.feed("@label=foreign :srv BATCH +foreign chathistory #room", 6_200L).consumed)
        assertFalse(tracker.begin(request(id = 2L), 6_300L))
        assertNull(tracker.cancel("connection reset"))
        assertFalse(tracker.requiresReconnect)
        assertTrue(tracker.begin(request(id = 2L), 6_300L))
    }

    @Test
    fun labeledRetiredOuterReplyCannotSatisfyANewLabel() {
        val tracker = tracker()
        tracker.begin(request(label = "old"), 0L)
        assertNotNull(tracker.expire(6_000L))
        assertFalse(tracker.requiresReconnect)
        assertFalse(tracker.begin(request(id = 2L, label = "old"), 6_100L))
        val replacement = request(id = 2L, label = "new")
        assertTrue(tracker.begin(replacement, 6_100L))
        val late = listOf(
            "@label=old :srv BATCH +oldouter labeled-response",
            "@batch=oldouter :srv BATCH +oldhistory chathistory #room",
            "@batch=oldhistory :bob!u@h PRIVMSG #room :late reply",
            ":srv BATCH -oldhistory",
            ":srv BATCH -oldouter",
        )
        late.forEach { line ->
            val receipt = tracker.feed(line, 6_200L)
            assertTrue(receipt.consumed, line)
            assertNull(receipt.result, line)
        }
        assertEquals(replacement, tracker.pending)
        tracker.feed("@label=new :srv BATCH +newhistory chathistory #room", 6_300L)
        assertEquals(replacement, tracker.feed(":srv BATCH -newhistory", 6_300L).result?.request)
    }

    @Test
    fun cancellationRetainsPartialReplayButClearsPendingAndDeadline() {
        val tracker = tracker()
        tracker.begin(request(label = "cancelled"), 0L)
        tracker.feed("@label=cancelled;draft/chathistory-end :srv BATCH +history chathistory #room")
        tracker.feed("@batch=history :bob!u@h PRIVMSG #room :partial")
        val result = assertNotNull(tracker.cancel("Cancelled by user"))
        assertEquals("Cancelled by user", result.error)
        assertFalse(result.end)
        assertEquals(2, result.frames.size)
        assertNull(tracker.pending)
        assertNull(tracker.deadlineMs)
        assertTrue(tracker.feed(":srv BATCH -history").consumed)
        assertFalse(tracker.requiresReconnect)
        assertTrue(tracker.begin(request(id = 2L, label = "replacement"), 200L))
    }

    @Test
    fun unlabelledCancellationAndConnectionResetClearOwnedState() {
        val tracker = tracker()
        tracker.begin(request(), 0L)
        tracker.feed(":srv BATCH +history chathistory #room")
        assertNotNull(tracker.cancel("Cancelled"))
        assertTrue(tracker.requiresReconnect)
        assertNull(tracker.cancel("connection reset"))
        assertFalse(tracker.requiresReconnect)
        assertFalse(tracker.feed("@batch=history :bob!u@h PRIVMSG #room :old session").consumed)
        assertFalse(tracker.feed(":srv BATCH -history").consumed)
        assertTrue(tracker.begin(request(id = 2L), 200L))
        assertNotNull(tracker.cancel("connection reset"))
        assertFalse(tracker.requiresReconnect)
    }

    @Test
    fun targetsRowsPreserveServerOrderAndWaitForBatchCompletion() {
        val tracker = tracker()
        tracker.begin(request(target = null, operation = HistoryOperation.TARGETS, label = "targets"), 0L)
        tracker.feed("@label=targets;draft/chathistory-end :srv BATCH +targets draft/chathistory-targets")
        assertFalse(tracker.feed("CHATHISTORY TARGETS nobody 1970-01-01T00:00:02.000Z").consumed)
        val first = tracker.feed("@batch=targets :srv CHATHISTORY TARGETS Bob 1970-01-01T00:00:02.000Z")
        assertTrue(first.consumed)
        assertNull(first.result)
        tracker.feed("@batch=targets :srv CHATHISTORY TARGETS #room 1970-01-01T00:00:01.500Z")
        val result = assertNotNull(tracker.feed(":srv BATCH -targets").result)
        assertEquals(listOf(HistoryTarget("Bob", 2_000L), HistoryTarget("#room", 1_500L)), result.targets)
        assertEquals(4, result.frames.size)
        assertTrue(result.end)
        assertNull(result.error)
    }

    @Test
    fun emptyTargetsAndMalformedTargetsHaveDifferentOutcomes() {
        val empty = tracker()
        empty.begin(request(target = null, operation = HistoryOperation.TARGETS), 0L)
        empty.feed(":srv BATCH +targets draft/chathistory-targets")
        val complete = assertNotNull(empty.feed(":srv BATCH -targets").result)
        assertTrue(complete.end)
        assertTrue(complete.targets.isEmpty())
        assertNull(complete.error)
        val malformed = tracker()
        malformed.begin(request(target = null, operation = HistoryOperation.TARGETS), 0L)
        malformed.feed(":srv BATCH +targets draft/chathistory-targets")
        val failed = assertNotNull(malformed.feed("@batch=targets :srv CHATHISTORY TARGETS Bob not-a-timestamp").result)
        assertNotNull(failed.error)
        assertFalse(failed.end)
    }

    @Test
    fun unfinishedNestedBatchFailsRatherThanClaimingCoverage() {
        val tracker = tracker()
        tracker.begin(request(), 0L)
        tracker.feed(":srv BATCH +history chathistory #room")
        tracker.feed("@batch=history :bob!u@h BATCH +multi draft/multiline #room")
        val result = assertNotNull(tracker.feed(":srv BATCH -history").result)
        assertNotNull(result.error)
        assertFalse(result.end)
        assertNull(tracker.pending)
        assertTrue(tracker.feed(":srv BATCH -multi").consumed)
    }

    @Test
    fun ackIsNotHistoryCompletionAndEmptyOuterResponseFails() {
        val tracker = tracker()
        tracker.begin(request(label = "ack"), 0L)
        val ack = tracker.feed("@label=ack :srv ACK")
        assertTrue(ack.consumed)
        assertNull(ack.result)
        assertNotNull(tracker.pending)
        assertNotNull(tracker.expire(6_000L)?.error)
        val outer = tracker()
        outer.begin(request(label = "empty"), 0L)
        outer.feed("@label=empty :srv BATCH +outer labeled-response")
        val result = assertNotNull(outer.feed(":srv BATCH -outer").result)
        assertNotNull(result.error)
        assertFalse(result.end)
    }

    @Test
    fun receiveAtExpiredDeadlineReportsTimeoutAndQuarantinesLateRoot() {
        val tracker = tracker()
        tracker.begin(request(), 0L)
        val receipt = tracker.feed(":srv BATCH +late chathistory #room", 6_000L)
        assertTrue(receipt.consumed)
        assertEquals("History request timed out", receipt.result?.error)
        assertNull(tracker.pending)
        assertTrue(tracker.requiresReconnect)
    }

    @Test
    fun bareZncPlaybackRequiresTimestampBoundsAndCorrectDmIdentity() {
        val tracker = tracker()
        tracker.begin(znc(), 0L)
        val unrelated = listOf(
            chat(1_000L, "Bob", "Me"),
            chat(3_001L, "Bob", "Me"),
            chat(2_000L, "Alice", "Me"),
            chat(2_000L, "Alice", "Bob"),
            chat(2_000L, "Me", "Alice"),
            chat(2_000L, "Bob", "#elsewhere"),
            chat(2_000L, "Bob", "someoneElse"),
            ":Bob!u@h PRIVMSG Me :no server time",
            "@time=not-a-time :Bob!u@h PRIVMSG Me :invalid server time",
            "@label=foreign;time=1970-01-01T00:00:02.000Z :Bob!u@h PRIVMSG Me :other request",
        )
        unrelated.forEach { line -> assertFalse(tracker.feed(line, 100L).consumed, line) }
        val incoming = chat(1_001L, "bOB", "mE")
        val outgoing = chat(3_000L, "ME", "BOB", command = "NOTICE")
        assertTrue(tracker.feed(incoming, 500L).consumed)
        assertEquals(2_500L, tracker.deadlineMs)
        assertTrue(tracker.feed(outgoing, 700L).consumed)
        assertEquals(2_700L, tracker.deadlineMs)
        assertNull(tracker.expire(2_699L))
        val result = assertNotNull(tracker.expire(2_700L))
        assertEquals(listOf(message(incoming), message(outgoing)), result.frames)
        assertFalse(result.end)
        assertNull(result.error)
    }

    @Test
    fun bareZncChannelReplayDoesNotRequireOwnNickAsSender() {
        val tracker = tracker()
        tracker.begin(znc(target = "#[Room]"), 0L)
        assertTrue(tracker.feed(chat(2_000L, "Bob", "#{ROOM}"), 500L).consumed)
        assertFalse(tracker.feed(chat(2_000L, "Bob", "Me"), 600L).consumed)
        assertEquals(2_500L, tracker.deadlineMs)
        assertNull(tracker.expire(2_499L))
        assertNull(assertNotNull(tracker.expire(2_500L)).error)
    }

    @Test
    fun emptyZncWindowQuietSettlesWithoutUniversalExhaustion() {
        val tracker = tracker()
        tracker.begin(znc(), 0L)
        assertEquals(2_000L, tracker.deadlineMs)
        assertFalse(tracker.feed(chat(4_000L, "Bob", "Me"), 1_000L).consumed)
        assertEquals(2_000L, tracker.deadlineMs)
        val result = assertNotNull(tracker.expire(2_000L))
        assertTrue(result.frames.isEmpty())
        assertFalse(result.end)
        assertNull(result.error)
        assertFalse(tracker.requiresReconnect)
        assertNull(tracker.pending)
    }

    @Test
    fun singleTargetZncBatchClosesImmediatelyAndPreservesMultiline() {
        val tracker = tracker()
        tracker.begin(znc(), 0L)
        assertFalse(tracker.feed(":znc.in BATCH +other znc.in/playback Alice").consumed)
        assertFalse(tracker.feed("@batch=other :Alice!u@h PRIVMSG Me :unrelated playback").consumed)
        assertFalse(tracker.feed(":znc.in BATCH -other").consumed)
        val lines = listOf(
            ":znc.in BATCH +history znc.in/playback bOB",
            "@batch=history :Bob!u@h BATCH +multi draft/multiline Me",
            "@batch=multi;time=1970-01-01T00:00:02.000Z :Bob!u@h PRIVMSG Me :first",
            "@batch=multi :Bob!u@h PRIVMSG Me :second",
            ":znc.in BATCH -multi",
            ":znc.in BATCH -history",
        )
        lines.dropLast(1).forEach { line ->
            assertNull(tracker.feed(line, 500L).result)
            assertEquals(6_000L, tracker.deadlineMs)
        }
        val result = assertNotNull(tracker.feed(lines.last(), 500L).result)
        assertEquals(lines.map(::message), result.frames)
        assertFalse(result.end)
        assertNull(result.error)
    }

    @Test
    fun zncModuleErrorsAndMissingModuleHaveFiniteFailureResults() {
        val tracker = tracker()
        tracker.begin(znc(), 0L)
        assertFalse(tracker.feed(":*playback!znc@znc.in NOTICE Me :Bob 1 3").consumed)
        assertFalse(tracker.feed(":*other!znc@znc.in NOTICE Me :Error: unrelated module").consumed)
        val result = assertNotNull(tracker.feed(":*playback!znc@znc.in PRIVMSG Me :Unknown command! Try Help.").result)
        assertEquals("Unknown command! Try Help.", result.error)
        assertFalse(result.end)
        assertTrue(tracker.begin(znc(), 200L))
        val missing = assertNotNull(tracker.feed(":znc.in 401 Me *playback :No such module", 300L).result)
        assertEquals("No such module", missing.error)
    }

    @Test
    fun continuallyActiveBareZncPlaybackHitsHardCapWithoutCoverage() {
        val tracker = tracker()
        tracker.begin(znc(), 0L)
        for (nowMs in listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L)) {
            assertTrue(tracker.feed(chat(2_000L, "Bob", "Me"), nowMs).consumed)
        }
        assertEquals(6_000L, tracker.deadlineMs)
        val result = assertNotNull(tracker.expire(6_000L))
        assertEquals(5, result.frames.size)
        assertNotNull(result.error)
        assertFalse(result.end)
        assertTrue(tracker.requiresReconnect)
    }

    @Test
    fun openZncBatchCannotQuietSettleAndTimesOutAtHardCap() {
        val tracker = tracker()
        tracker.begin(znc(), 0L)
        tracker.feed(":znc.in BATCH +history znc.in/playback Bob", 500L)
        assertEquals(6_000L, tracker.deadlineMs)
        assertNull(tracker.expire(2_500L))
        val result = assertNotNull(tracker.expire(6_000L))
        assertNotNull(result.error)
        assertFalse(result.end)
        assertTrue(tracker.feed("@batch=history :Bob!u@h PRIVMSG Me :late playback", 6_100L).consumed)
        assertTrue(tracker.feed(":znc.in BATCH -history", 6_100L).consumed)
    }

    @Test
    fun wildcardZncDiscoveryWaitsForAllTargetsAndQuietAfterLastRootClose() {
        val tracker = tracker()
        tracker.begin(znc(target = "*", limit = 4_096), 0L)
        val lines = listOf(
            ":znc.in BATCH +channel znc.in/playback #room",
            "@batch=channel;time=1970-01-01T00:00:02.000Z :Alice!u@h PRIVMSG #room :old channel message",
            ":znc.in BATCH -channel",
            ":znc.in BATCH +direct znc.in/playback PreviouslyUnknown",
            "@batch=direct;time=1970-01-01T00:00:02.500Z :PreviouslyUnknown!u@h PRIVMSG Me :offline DM",
            ":znc.in BATCH -direct",
        )
        lines.forEachIndexed { index, line ->
            val receipt = tracker.feed(line, 500L + index * 100L)
            assertTrue(receipt.consumed, line)
            assertNull(receipt.result, line)
        }
        assertEquals(3_000L, tracker.deadlineMs)
        assertNull(tracker.expire(2_999L))
        val result = assertNotNull(tracker.expire(3_000L))
        assertEquals(lines.map(::message), result.frames)
        assertNull(result.error)
        assertFalse(result.end)
    }

    @Test
    fun wildcardBareZncIncludesIncomingOutgoingAndChannelButNotModuleTraffic() {
        val tracker = tracker()
        tracker.begin(znc(target = "*"), 0L)
        val lines = listOf(
            chat(2_000L, "Unknown", "Me"),
            chat(2_000L, "Me", "Unknown"),
            chat(2_000L, "Someone", "#room"),
        )
        lines.forEach { line -> assertTrue(tracker.feed(line, 500L).consumed, line) }
        assertFalse(tracker.feed(chat(2_000L, "Someone", "Other"), 600L).consumed)
        assertFalse(tracker.feed(chat(2_000L, "Me", "*playback"), 600L).consumed)
        assertFalse(tracker.feed(chat(2_000L, "*playback", "Me", command = "NOTICE"), 600L).consumed)
        assertFalse(tracker.feed(chat(4_000L, "Unknown", "Me"), 600L).consumed)
        val result = assertNotNull(tracker.expire(2_500L))
        assertEquals(lines.map(::message), result.frames)
        assertFalse(result.end)
    }

    @Test
    fun delayedExpiryHonorsAlreadyReachedQuietDeadline() {
        val tracker = tracker()
        tracker.begin(znc(), 0L)
        val result = assertNotNull(tracker.expire(10_000L))
        assertNull(result.error)
        assertFalse(result.end)
        assertFalse(tracker.requiresReconnect)
    }

    @Test
    fun labeledZncWrapperCannotQuietSettleBeforeItsBatchEnding() {
        val tracker = tracker()
        tracker.begin(znc().copy(label = "znc"), 0L)
        tracker.feed("@label=znc :znc.in BATCH +outer labeled-response", 100L)
        assertEquals(6_000L, tracker.deadlineMs)
        assertNull(tracker.expire(2_100L))
        tracker.feed("@batch=outer :znc.in BATCH +history znc.in/playback Bob", 2_200L)
        tracker.feed("@batch=history :Bob!u@h PRIVMSG Me :replay", 2_300L)
        assertNull(tracker.feed(":znc.in BATCH -history", 2_400L).result)
        assertEquals(6_000L, tracker.deadlineMs)
        val result = assertNotNull(tracker.feed(":znc.in BATCH -outer", 2_500L).result)
        assertNull(result.error)
        assertFalse(result.end)
    }

    @Test
    fun unsolicitedOpenPlaybackIsNotAdoptedByALaterRequest() {
        val tracker = tracker()
        assertFalse(tracker.feed(":znc.in BATCH +unsolicited znc.in/playback Bob", 0L).consumed)
        tracker.begin(znc(), 100L)
        assertFalse(tracker.feed("@batch=unsolicited :Bob!u@h PRIVMSG Me :automatic replay", 200L).consumed)
        assertFalse(tracker.feed(":znc.in BATCH -unsolicited", 300L).consumed)
        assertEquals(2_100L, tracker.deadlineMs)
        val result = assertNotNull(tracker.expire(2_100L))
        assertTrue(result.frames.isEmpty())
        assertNull(result.error)
        assertFalse(result.end)
    }

    @Test
    fun wildcardZncCaptureHonorsItsLargerRequestedLimit() {
        val tracker = tracker()
        tracker.begin(znc(target = "*", limit = 4_096), 0L)
        repeat(1_024) { index ->
            val receipt = tracker.feed(chat(2_000L, "User$index", "Me"), 500L)
            assertTrue(receipt.consumed)
            assertNull(receipt.result)
        }
        val result = assertNotNull(tracker.expire(2_500L))
        assertEquals(1_024, result.frames.size)
        assertNull(result.error)
        assertFalse(result.end)
    }
}
