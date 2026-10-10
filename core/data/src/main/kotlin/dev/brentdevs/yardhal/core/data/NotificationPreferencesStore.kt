package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
public enum class NotificationKind { MENTION, DIRECT_MESSAGE, INVITE }

@Serializable
public enum class NotificationPriority { QUIET, DEFAULT, HIGH }

@Serializable
public data class NotificationKindPreferences(
    public val enabled: Boolean = true,
    public val sound: Boolean = true,
    public val priority: NotificationPriority = NotificationPriority.HIGH,
)

@Serializable
public data class NotificationPreferences(
    public val mentions: NotificationKindPreferences = NotificationKindPreferences(),
    public val directMessages: NotificationKindPreferences = NotificationKindPreferences(),
    public val invites: NotificationKindPreferences = NotificationKindPreferences(),
    public val showPreview: Boolean = true,
    public val showAvatars: Boolean = true,
) {
    public fun forKind(kind: NotificationKind): NotificationKindPreferences = when (kind) {
        NotificationKind.MENTION -> mentions
        NotificationKind.DIRECT_MESSAGE -> directMessages
        NotificationKind.INVITE -> invites
    }

    public fun updateKind(kind: NotificationKind, transform: (NotificationKindPreferences) -> NotificationKindPreferences): NotificationPreferences = when (kind) {
        NotificationKind.MENTION -> copy(mentions = transform(mentions))
        NotificationKind.DIRECT_MESSAGE -> copy(directMessages = transform(directMessages))
        NotificationKind.INVITE -> copy(invites = transform(invites))
    }
}

public class NotificationPreferencesStore(directory: File) {
    private val store = JsonFileStore(File(directory, "notification-preferences.json"), NotificationPreferences.serializer())
    private var current = store.loadOrDefault(NotificationPreferences())
    private val mutablePreferences = MutableStateFlow(current)
    public val preferences: StateFlow<NotificationPreferences> = mutablePreferences.asStateFlow()

    @Synchronized
    public fun snapshot(): NotificationPreferences = current

    @Synchronized
    public fun update(transform: (NotificationPreferences) -> NotificationPreferences) {
        val next = transform(current)
        store.save(next)
        current = next
        mutablePreferences.value = next
    }
}
