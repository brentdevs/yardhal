package dev.brentdevs.yardhal.core.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
public data class MediaPreferences(
    public val autoLoadImages: Boolean = false,
    public val loadAvatars: Boolean = true,
    public val enableVideos: Boolean = false,
    public val animateImages: Boolean = true,
    public val reducedMotion: Boolean = false,
    public val mediaCacheBytes: Long = 32L * 1024 * 1024,
    public val avatarCacheBytes: Long = 8L * 1024 * 1024,
) {
    public fun normalized(): MediaPreferences = copy(
        mediaCacheBytes = mediaCacheBytes.coerceIn(0L, MAX_CACHE_BYTES),
        avatarCacheBytes = avatarCacheBytes.coerceIn(0L, MAX_CACHE_BYTES),
    )

    public companion object {
        public const val MAX_CACHE_BYTES: Long = 512L * 1024 * 1024
    }
}

@Serializable
public enum class MediaRevealState { DEFAULT, REVEALED, HIDDEN }

@Serializable
private data class PersistedMediaPreferences(
    val preferences: MediaPreferences = MediaPreferences(),
    val reveals: Map<String, MediaRevealState> = emptyMap(),
)

public class MediaPreferencesStore(directory: File) {
    private val store = JsonFileStore(File(directory, "media-preferences.json"), PersistedMediaPreferences.serializer())
    private var state = store.loadOrDefault(PersistedMediaPreferences()).let {
        it.copy(preferences = it.preferences.normalized(), reveals = retainedReveals(LinkedHashMap(it.reveals), emptySet()))
    }
    private val mutablePreferences = MutableStateFlow(state.preferences)
    private val mutableReveals = MutableStateFlow(state.reveals)
    private val activeReveals = mutableMapOf<String, Int>()

    public val preferences: StateFlow<MediaPreferences> = mutablePreferences.asStateFlow()
    public val reveals: StateFlow<Map<String, MediaRevealState>> = mutableReveals.asStateFlow()

    @Synchronized
    public fun snapshot(): MediaPreferences = state.preferences

    @Synchronized
    public fun update(transform: (MediaPreferences) -> MediaPreferences) {
        val next = state.copy(preferences = transform(state.preferences).normalized())
        store.save(next)
        state = next
        mutablePreferences.value = next.preferences
    }

    @Synchronized
    public fun reveal(identity: String, url: String): MediaRevealState =
        state.reveals[revealKey(identity, url)] ?: MediaRevealState.DEFAULT

    @Synchronized
    public fun setReveal(identity: String, url: String, reveal: MediaRevealState) {
        require(identity.isNotBlank())
        val key = revealKey(identity, url)
        val reveals = LinkedHashMap(state.reveals)
        reveals.remove(key)
        if (reveal != MediaRevealState.DEFAULT) reveals[key] = reveal
        val next = state.copy(reveals = retainedReveals(reveals, activeReveals.keys))
        store.save(next)
        state = next
        mutableReveals.value = next.reveals
    }

    @Synchronized
    public fun retainReveal(identity: String, url: String): AutoCloseable {
        val key = revealKey(identity, url)
        activeReveals[key] = (activeReveals[key] ?: 0) + 1
        return object : AutoCloseable {
            private var closed = false
            override fun close() {
                synchronized(this@MediaPreferencesStore) {
                    if (closed) return
                    closed = true
                    val remaining = (activeReveals[key] ?: 1) - 1
                    if (remaining == 0) activeReveals.remove(key) else activeReveals[key] = remaining
                    if (state.reveals.size > maxOf(MAX_REVEAL_ENTRIES, activeReveals.size + RECENT_REVEAL_ENTRIES)) {
                        val reveals = retainedReveals(LinkedHashMap(state.reveals), activeReveals.keys)
                        val next = state.copy(reveals = reveals)
                        store.save(next)
                        state = next
                        mutableReveals.value = reveals
                    }
                }
            }
        }
    }

    public companion object {
        public const val MAX_REVEAL_ENTRIES: Int = 4096
        public const val RECENT_REVEAL_ENTRIES: Int = 128

        private fun retainedReveals(retained: LinkedHashMap<String, MediaRevealState>, active: Set<String>): Map<String, MediaRevealState> {
            retained.entries.removeIf { it.value == MediaRevealState.DEFAULT }
            var excess = retained.size - maxOf(MAX_REVEAL_ENTRIES, active.size + RECENT_REVEAL_ENTRIES)
            for (pass in 0..1) {
                if (excess <= 0) break
                val revealToEvict = if (pass == 0) MediaRevealState.REVEALED else MediaRevealState.HIDDEN
                var candidates = (retained.size - RECENT_REVEAL_ENTRIES).coerceAtLeast(0)
                val iterator = retained.entries.iterator()
                while (excess > 0 && candidates > 0 && iterator.hasNext()) {
                    val entry = iterator.next()
                    candidates--
                    if (entry.value == revealToEvict && entry.key !in active) {
                        iterator.remove()
                        excess--
                    }
                }
            }
            return retained
        }
        public fun revealKey(identity: String, url: String): String {
            val encoded = "${identity.length}:$identity$url".toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
        }
    }
}
