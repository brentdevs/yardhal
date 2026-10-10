package dev.brentdevs.yardhal.core.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull

@Serializable
public enum class TimestampFormat { TIME_24, TIME_12, TIME_SECONDS, ISO_DATE_TIME }

@Serializable
public enum class TimestampStyle { NORMAL, MUTED }

@Serializable
public enum class TimestampPosition { INLINE, ABOVE, BELOW }

@Serializable
public enum class MessageFont { SYSTEM, SANS, SERIF, MONOSPACE }

@Serializable
public enum class NicknameSuggestionOrder { ROLE, ALPHABETICAL, RECENT }

@Serializable
public data class ChatAppearancePreferences(
    public val compact: Boolean = false,
    public val textScale: Float = 1f,
    public val dynamicColor: Boolean = false,
    public val amoledDark: Boolean = false,
    public val timestampFormat: TimestampFormat = TimestampFormat.TIME_24,
    public val timestampStyle: TimestampStyle = TimestampStyle.MUTED,
    public val timestampPosition: TimestampPosition = TimestampPosition.INLINE,
    public val font: MessageFont = MessageFont.SYSTEM,
    public val nicknameSuggestionOrder: NicknameSuggestionOrder = NicknameSuggestionOrder.ROLE,
    public val showAvatars: Boolean = true,
    public val showUnreadCounts: Boolean = true,
)

public class ChatAppearanceStore(directory: File) {

    private val migration = AppearanceMigration()
    private val store = JsonFileStore(
        file = File(directory, "chat-appearance.json"),
        serializer = migration,
    )
    private val mutablePreferences = MutableStateFlow(
        store.loadOrDefault(ChatAppearancePreferences()).normalized(),
    )
    public val preferences: StateFlow<ChatAppearancePreferences> = mutablePreferences.asStateFlow()

    init {
        if (migration.migrated) {
            try {
                store.save(mutablePreferences.value)
            } catch (_: IOException) {
                reportMigrationFailure()
            } catch (_: SecurityException) {
                reportMigrationFailure()
            }
        }
    }

    private fun reportMigrationFailure() {
        StorageRecovery.report(StorageRecoveryNotice(
            "Appearance format could not be durably updated. Existing preferences remain active. " +
                "Changing appearance retries the migration when storage is available.",
            quarantinePath = null,
            temporary = false,
        ))
    }

    @Synchronized
    public fun snapshot(): ChatAppearancePreferences = mutablePreferences.value

    @Synchronized
    public fun update(transform: (ChatAppearancePreferences) -> ChatAppearancePreferences): ChatAppearancePreferences {
        val next = transform(mutablePreferences.value).normalized()
        store.save(next)
        mutablePreferences.value = next
        return next
    }

    private fun ChatAppearancePreferences.normalized(): ChatAppearancePreferences =
        copy(textScale = if (textScale.isFinite()) textScale.coerceIn(0.85f, 1.25f) else 1f)
}

private class AppearanceMigration : JsonTransformingSerializer<ChatAppearancePreferences>(
    ChatAppearancePreferences.serializer(),
) {
    var migrated = false
        private set

    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject || "monospaceFont" !in element) return element
        migrated = true
        val fields = element.toMutableMap()
        val legacy = (fields.remove("monospaceFont") as? JsonPrimitive)?.booleanOrNull
            ?: throw SerializationException("Legacy monospaceFont must be a Boolean")
        if ("font" !in fields) fields["font"] = JsonPrimitive(if (legacy) "MONOSPACE" else "SYSTEM")
        return JsonObject(fields)
    }
}
