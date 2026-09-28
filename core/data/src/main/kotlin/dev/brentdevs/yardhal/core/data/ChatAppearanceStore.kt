package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlinx.serialization.Serializable

@Serializable
public data class ChatAppearancePreferences(
    public val compact: Boolean = false,
    public val textScale: Float = 1f,
)

public class ChatAppearanceStore(directory: File) {

    private val store = JsonFileStore(
        file = File(directory, "chat-appearance.json"),
        serializer = ChatAppearancePreferences.serializer(),
    )

    private var state = store.loadOrDefault(ChatAppearancePreferences())

    @Synchronized
    public fun snapshot(): ChatAppearancePreferences = state

    @Synchronized
    public fun update(preferences: ChatAppearancePreferences) {
        state = preferences.copy(textScale = preferences.textScale.coerceIn(0.85f, 1.25f))
        store.save(state)
    }
}
