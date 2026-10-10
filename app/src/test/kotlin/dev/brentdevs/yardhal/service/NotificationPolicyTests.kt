package dev.brentdevs.yardhal.service

import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.NotificationKind
import dev.brentdevs.yardhal.core.data.NotificationKindPreferences
import dev.brentdevs.yardhal.core.data.NotificationPreferences
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test

class NotificationPolicyTests {
    private val event = NotificationEvent(NotificationKind.MENTION, ConversationRef.channel("network", "#yardhal"), "Network", "Alice", "hello", 100, "1")

    @Test
    fun directMessagesUseTheirOwnPreferencesEvenWhenTheyMentionUs() {
        val ref = ConversationRef.directMessage("network", "Alice")
        val kind = requireNotNull(NotificationPolicy.messageKind(ref, highlightsMe = true))
        assertEquals(NotificationKind.DIRECT_MESSAGE, kind)
        val preferences = NotificationPreferences(mentions = NotificationKindPreferences(enabled = false))
        assertIs<NotificationDecision.Show>(NotificationPolicy.decide(event.copy(kind = kind, ref = ref), NotificationEligibility(), preferences, true))
        val disabledDm = preferences.copy(directMessages = NotificationKindPreferences(enabled = false))
        assertEquals(NotificationDecision.Suppress(NotificationSuppression.DISABLED), NotificationPolicy.decide(event.copy(kind = kind, ref = ref), NotificationEligibility(), disabledDm, true))
        assertNull(NotificationPolicy.messageKind(ConversationRef.channel("network", "#yardhal"), false))
        assertNull(NotificationPolicy.messageKind(ConversationRef.server("network"), true))
    }

    @Test
    fun suppressionPrecedenceIsDeterministicAndEverySafetyGateCanBlockIndependently() {
        val all = NotificationEligibility(appended = false, sentByUs = true, ignored = true, playback = true, historyContext = true, muted = true, viewed = true, read = true)
        val disabled = NotificationPreferences(mentions = NotificationKindPreferences(enabled = false))
        fun reason(flags: NotificationEligibility, preferences: NotificationPreferences = NotificationPreferences(), allowed: Boolean = true): NotificationSuppression =
            assertIs<NotificationDecision.Suppress>(NotificationPolicy.decide(event, flags, preferences, allowed)).reason
        assertEquals(NotificationSuppression.PERMISSION, reason(all, disabled, false))
        assertEquals(NotificationSuppression.DISABLED, reason(all, disabled))
        assertEquals(NotificationSuppression.DUPLICATE, reason(all))
        val cases = listOf(
            NotificationEligibility(sentByUs = true) to NotificationSuppression.OWN_MESSAGE,
            NotificationEligibility(ignored = true) to NotificationSuppression.IGNORED,
            NotificationEligibility(playback = true) to NotificationSuppression.HISTORY,
            NotificationEligibility(historyContext = true) to NotificationSuppression.HISTORY,
            NotificationEligibility(muted = true) to NotificationSuppression.MUTED,
            NotificationEligibility(viewed = true) to NotificationSuppression.VIEWED,
            NotificationEligibility(read = true) to NotificationSuppression.READ,
        )
        for ((flags, expected) in cases) assertEquals(expected, reason(flags))
        assertEquals(NotificationSuppression.OWN_MESSAGE, reason(all.copy(appended = true)))
        assertEquals(NotificationSuppression.IGNORED, reason(all.copy(appended = true, sentByUs = false)))
        assertEquals(NotificationSuppression.HISTORY, reason(all.copy(appended = true, sentByUs = false, ignored = false)))
        assertEquals(NotificationSuppression.MUTED, reason(NotificationEligibility(muted = true, viewed = true, read = true)))
        assertEquals(NotificationSuppression.VIEWED, reason(NotificationEligibility(viewed = true, read = true)))
        assertIs<NotificationDecision.Show>(NotificationPolicy.decide(event, NotificationEligibility(), NotificationPreferences(), true))
    }

    @Test
    fun invitationsHaveIndependentPreferencesButStillRespectReplayIgnoreMuteReadAndViewed() {
        val invite = event.copy(kind = NotificationKind.INVITE)
        val preferences = NotificationPreferences(mentions = NotificationKindPreferences(enabled = false), directMessages = NotificationKindPreferences(enabled = false))
        assertIs<NotificationDecision.Show>(NotificationPolicy.decide(invite, NotificationEligibility(), preferences, true))
        val filters = listOf(NotificationEligibility(playback = true), NotificationEligibility(historyContext = true), NotificationEligibility(ignored = true), NotificationEligibility(muted = true), NotificationEligibility(read = true), NotificationEligibility(viewed = true))
        for (filter in filters) assertIs<NotificationDecision.Suppress>(NotificationPolicy.decide(invite, filter, preferences, true))
        assertIs<NotificationDecision.Suppress>(NotificationPolicy.decide(invite, NotificationEligibility(), preferences.copy(invites = NotificationKindPreferences(enabled = false)), true))
    }

    @Test
    fun malformedOrMismatchedRoutesAreRejectedBeforePermissionOrPreferences() {
        val invalid = listOf(event.copy(eventId = ""), event.copy(ref = ConversationRef.server("network")), event.copy(kind = NotificationKind.DIRECT_MESSAGE), event.copy(sender = ""), event.copy(sender = "Alice\r\n"), event.copy(eventId = "bad\nid"), event.copy(ref = ConversationRef.channel("network", "#bad\r\nJOIN")))
        for (candidate in invalid) assertEquals(NotificationDecision.Suppress(NotificationSuppression.INVALID_EVENT), NotificationPolicy.decide(candidate, NotificationEligibility(), NotificationPreferences(), false))
    }
}
