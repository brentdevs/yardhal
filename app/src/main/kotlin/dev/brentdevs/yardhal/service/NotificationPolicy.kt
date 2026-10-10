package dev.brentdevs.yardhal.service

import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.NotificationKind
import dev.brentdevs.yardhal.core.data.NotificationKindPreferences
import dev.brentdevs.yardhal.core.data.NotificationPreferences

public data class NotificationEvent(
    public val kind: NotificationKind,
    public val ref: ConversationRef,
    public val networkName: String,
    public val sender: String,
    public val text: String,
    public val timestampMs: Long,
    public val eventId: String,
    public val msgid: String? = null,
    public val avatarUrl: String? = null,
)

public data class NotificationEligibility(
    public val appended: Boolean = true,
    public val sentByUs: Boolean = false,
    public val ignored: Boolean = false,
    public val playback: Boolean = false,
    public val historyContext: Boolean = false,
    public val muted: Boolean = false,
    public val viewed: Boolean = false,
    public val read: Boolean = false,
)

public fun interface ConversationNotifier {
    public fun notify(event: NotificationEvent, eligibility: NotificationEligibility)
}

public enum class NotificationSuppression {
    INVALID_EVENT, PERMISSION, DISABLED, DUPLICATE, OWN_MESSAGE, IGNORED, HISTORY, MUTED, VIEWED, READ,
}

public sealed interface NotificationDecision {
    public data class Show(public val settings: NotificationKindPreferences) : NotificationDecision
    public data class Suppress(public val reason: NotificationSuppression) : NotificationDecision
}

public object NotificationPolicy {
    public fun messageKind(ref: ConversationRef, highlightsMe: Boolean): NotificationKind? = when {
        ref.kind == ConversationKind.DIRECT_MESSAGE -> NotificationKind.DIRECT_MESSAGE
        ref.kind == ConversationKind.CHANNEL && highlightsMe -> NotificationKind.MENTION
        else -> null
    }

    public fun decide(
        event: NotificationEvent,
        eligibility: NotificationEligibility,
        preferences: NotificationPreferences,
        permissionGranted: Boolean,
    ): NotificationDecision {
        val reason = when {
            !validEvent(event) -> NotificationSuppression.INVALID_EVENT
            !permissionGranted -> NotificationSuppression.PERMISSION
            !preferences.forKind(event.kind).enabled -> NotificationSuppression.DISABLED
            !eligibility.appended -> NotificationSuppression.DUPLICATE
            eligibility.sentByUs -> NotificationSuppression.OWN_MESSAGE
            eligibility.ignored -> NotificationSuppression.IGNORED
            eligibility.playback || eligibility.historyContext -> NotificationSuppression.HISTORY
            eligibility.muted -> NotificationSuppression.MUTED
            eligibility.viewed -> NotificationSuppression.VIEWED
            eligibility.read -> NotificationSuppression.READ
            else -> null
        }
        return if (reason == null) NotificationDecision.Show(preferences.forKind(event.kind)) else NotificationDecision.Suppress(reason)
    }

    private fun validEvent(event: NotificationEvent): Boolean =
        NotificationRoutes.validRoute(NotificationRoutes.fromEvent(event)) &&
            when (event.kind) {
                NotificationKind.MENTION, NotificationKind.INVITE -> event.ref.kind == ConversationKind.CHANNEL
                NotificationKind.DIRECT_MESSAGE -> event.ref.kind == ConversationKind.DIRECT_MESSAGE
            }
}
