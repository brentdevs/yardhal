package dev.brentdevs.yardhal.core.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
public data class LibraryTheme(public val id: String, public val variants: ThemeVariants)

@Serializable
public data class ConversationAccentPreference(
    public val networkId: String,
    public val normalizedTarget: String,
    public val color: Long,
)

@Serializable
public data class ThemeLibraryState(
    public val themes: List<LibraryTheme> = emptyList(),
    public val selectedId: String? = null,
    public val accents: List<ConversationAccentPreference> = emptyList(),
) {
    public fun definition(isDark: Boolean): ThemeDefinition? =
        themes.firstOrNull { it.id == selectedId }?.variants?.resolve(isDark)

    public fun accent(conversation: ConversationRef): Long? = accents.firstOrNull {
        it.networkId == conversation.networkId && it.normalizedTarget == conversation.normalizedTarget
    }?.color
}

public class ThemeLibraryStore(
    directory: File,
    bundled: List<ThemeDefinition> = listOf(
        requireNotNull(ThemeFileParser.parse(ThemeFileParser.SAMPLE_TOML)),
    ),
) {
    private val store = JsonFileStore(File(directory, "theme-library.json"), ThemeLibraryState.serializer())
    private val initialThemes = bundled.mapIndexed { index, definition ->
        LibraryTheme("bundled-$index", ThemeVariants(
            definition.name,
            light = definition.takeUnless { it.dark },
            dark = definition.takeIf { it.dark },
        ).validated())
    }
    private val mutableState = MutableStateFlow(store.loadOrDefault(
        ThemeLibraryState(initialThemes, initialThemes.firstOrNull()?.id),
    ))
    public val state: StateFlow<ThemeLibraryState> = mutableState.asStateFlow()

    @Synchronized
    public fun snapshot(): ThemeLibraryState = mutableState.value

    @Synchronized
    public fun select(id: String?) {
        val current = mutableState.value
        require(id == null || current.themes.any { it.id == id }) { "Theme no longer exists" }
        save(current.copy(selectedId = id))
    }

    @Synchronized
    public fun saveTheme(id: String?, variants: ThemeVariants): String {
        val valid = variants.validated()
        val current = mutableState.value
        require(id == null || current.themes.any { it.id == id }) { "Theme no longer exists; duplicate the draft instead" }
        require(id != null || current.themes.size < MAX_THEMES) { "Theme library is full; delete a theme before importing" }
        val resolvedId = id ?: UUID.randomUUID().toString()
        val entry = LibraryTheme(resolvedId, valid)
        val themes = if (id == null) current.themes + entry else current.themes.map { if (it.id == id) entry else it }
        save(current.copy(themes = themes))
        return resolvedId
    }

    @Synchronized
    public fun duplicate(id: String): String {
        val theme = mutableState.value.themes.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Theme no longer exists")
        return saveTheme(null, theme.variants.copy(name = theme.variants.name.take(75) + " copy"))
    }

    @Synchronized
    public fun delete(id: String) {
        val current = mutableState.value
        require(current.themes.any { it.id == id }) { "Theme no longer exists" }
        save(current.copy(
            themes = current.themes.filterNot { it.id == id },
            selectedId = current.selectedId.takeUnless { it == id },
        ))
    }

    @Synchronized
    public fun setAccent(conversation: ConversationRef, color: Long?) {
        color?.let(::validateThemeColor)
        val current = mutableState.value
        val retained = current.accents.filterNot {
            it.networkId == conversation.networkId && it.normalizedTarget == conversation.normalizedTarget
        }
        require(color == null || retained.size < MAX_ACCENTS) { "Accent library is full; reset an unused conversation accent" }
        val accents = if (color == null) retained else retained + ConversationAccentPreference(
            conversation.networkId, conversation.normalizedTarget, color,
        )
        save(current.copy(accents = accents))
    }

    @Synchronized
    public fun metadataKeys(): Set<String> =
        mutableState.value.accents.mapTo(mutableSetOf()) { "${it.networkId}|${it.normalizedTarget}" }

    @Synchronized
    public fun rename(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        val current = mutableState.value
        val source = current.accents.firstOrNull { "${it.networkId}|${it.normalizedTarget}" == fromKey } ?: return
        val retained = current.accents.filterNot { it == source }
        val hasDestination = retained.any { "${it.networkId}|${it.normalizedTarget}" == toKey }
        val accents = if (hasDestination) retained else retained + source.copy(
            networkId = toKey.substringBefore('|'),
            normalizedTarget = toKey.substringAfter('|'),
        )
        save(current.copy(accents = accents))
    }

    @Synchronized
    public fun deleteNetwork(networkId: String) {
        val current = mutableState.value
        val retained = current.accents.filterNot { it.networkId == networkId }
        if (retained.size != current.accents.size) save(current.copy(accents = retained))
    }

    private fun save(next: ThemeLibraryState) {
        store.save(next)
        mutableState.value = next
    }

    public companion object {
        public const val MAX_THEMES: Int = 100
        public const val MAX_ACCENTS: Int = 1000
    }
}

public class ThemeEditorDraft(
    public val themeId: String?,
    initialText: String,
) {
    public var text: String = initialText
    public var error: String? = null
        private set

    public fun save(store: ThemeLibraryStore): String? {
        return try {
            val variants = if (text.startsWith(ThemeShareLink.PREFIX)) ThemeShareLink.decode(text) else ThemeFileParser.importTheme(text)
            val id = store.saveTheme(themeId, variants)
            error = null
            id
        } catch (failure: IllegalArgumentException) {
            error = failure.message ?: "Theme is invalid"
            null
        } catch (failure: java.io.IOException) {
            error = "Theme durable save could not be confirmed. Your draft is retained; check storage before saving again."
            null
        } catch (failure: SecurityException) {
            error = "Theme storage access failed. Your draft is retained; restore access before saving again."
            null
        }
    }
}
