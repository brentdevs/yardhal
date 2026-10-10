package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
private data class RecentEmoji(val values: List<String> = emptyList())

public class RecentEmojiStore(directory: File) {
    private val store = JsonFileStore(File(directory, "recent-emoji.json"), RecentEmoji.serializer())
    private val state = MutableStateFlow(store.loadOrDefault(RecentEmoji()).values.take(48))
    public val recent: StateFlow<List<String>> = state.asStateFlow()

    @Synchronized
    public fun record(emoji: String) {
        require(emoji.isNotBlank() && emoji.length <= 64 && emoji.none { it.isISOControl() })
        val next = (listOf(emoji) + state.value.filterNot { it == emoji }).take(48)
        store.save(RecentEmoji(next))
        state.value = next
    }
}
