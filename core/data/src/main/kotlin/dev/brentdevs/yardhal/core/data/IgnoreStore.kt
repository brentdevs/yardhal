package dev.brentdevs.yardhal.core.data

import java.io.File
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

public class IgnoreStore(directory: File) {

    private val file = File(directory, "ignores.json")
    private val store = JsonFileStore(
        file = file,
        serializer = SetSerializer(String.serializer()),
    )

    @Volatile
    private var ignores: MutableSet<String> = store.loadOrDefault(emptySet()).toMutableSet()

    public fun all(): Set<String> = synchronized(ignores) { ignores.toSet() }

    public fun isIgnored(nick: String): Boolean = synchronized(ignores) {
        ignores.any { pattern -> matches(pattern, nick) }
    }

    public fun add(mask: String): Boolean = synchronized(ignores) {
        if (!ignores.add(mask.lowercase())) return false
        store.save(ignores.toSet())
        true
    }

    public fun remove(mask: String): Boolean = synchronized(ignores) {
        if (!ignores.remove(mask.lowercase())) return false
        store.save(ignores.toSet())
        true
    }

    public companion object {
        public fun matches(pattern: String, nick: String): Boolean {
            val p = pattern.lowercase()
            val n = nick.lowercase()
            if ('*' !in p && '?' !in p) return p == n
            return regexFor(p).matches(n)
        }

        private val cache = HashMap<String, Regex>()

        private fun regexFor(pattern: String): Regex = synchronized(cache) {
            cache.getOrPut(pattern) {
                buildString {
                    append('^')
                    for (ch in pattern) {
                        when (ch) {
                            '*' -> append(".*")
                            '?' -> append('.')
                            else -> append(Regex.escape(ch.toString()))
                        }
                    }
                    append('$')
                }.toRegex()
            }
        }
    }
}
