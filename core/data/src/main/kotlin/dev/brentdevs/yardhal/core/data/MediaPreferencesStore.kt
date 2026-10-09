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
        it.copy(preferences = it.preferences.normalized())
    }
    private val mutablePreferences = MutableStateFlow(state.preferences)
    private val mutableReveals = MutableStateFlow(state.reveals)

    public val preferences: StateFlow<MediaPreferences> = mutablePreferences.asStateFlow()
    public val reveals: StateFlow<Map<String, MediaRevealState>> = mutableReveals.asStateFlow()

    @Synchronized
    public fun snapshot(): MediaPreferences = state.preferences

    @Synchronized
    public fun update(preferences: MediaPreferences) {
        val next = state.copy(preferences = preferences.normalized())
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
        val next = state.copy(reveals = if (reveal == MediaRevealState.DEFAULT) state.reveals - key else state.reveals + (key to reveal))
        store.save(next)
        state = next
        mutableReveals.value = next.reveals
    }

    public companion object {
        public fun revealKey(identity: String, url: String): String {
            val encoded = "${identity.length}:$identity$url".toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
        }
    }
}
