package dev.brentdevs.yardhal.core.client

internal object SaslPrep {
    enum class Policy { QUERY, STORED }

    fun prepare(value: String, policy: Policy): String {
        if (value.all { it.code in 0x20..0x7E }) return value
        val normalized = NormalizedCodePoints(value.length)
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            offset += Character.charCount(codePoint)
            when {
                SaslPrepTables.contains(SaslPrepTables.mappedToNothing, codePoint) -> Unit
                SaslPrepTables.contains(SaslPrepTables.nonAsciiSpaces, codePoint) -> normalized.appendDecomposed(0x20)
                else -> normalized.appendDecomposed(codePoint)
            }
        }
        normalized.compose()
        var hasRandAL = false
        var hasL = false
        for (index in 0 until normalized.size) {
            val codePoint = normalized[index]
            if (SaslPrepTables.contains(SaslPrepTables.prohibited, codePoint)) {
                throw SaslMechanismException(
                    "SASLprep contains a prohibited code point",
                    SaslMechanismFailure.PROHIBITED_CHARACTER,
                    codePoint,
                )
            }
            if (policy == Policy.STORED && SaslPrepTables.contains(SaslPrepTables.unassigned, codePoint)) {
                throw SaslMechanismException(
                    "SASLprep contains a Unicode 3.2 unassigned code point",
                    SaslMechanismFailure.UNASSIGNED_CHARACTER,
                    codePoint,
                )
            }
            hasRandAL = hasRandAL || SaslPrepTables.contains(SaslPrepTables.randAL, codePoint)
            hasL = hasL || SaslPrepTables.contains(SaslPrepTables.leftToRight, codePoint)
        }
        if (hasRandAL && (
                hasL ||
                    !SaslPrepTables.contains(SaslPrepTables.randAL, normalized[0]) ||
                    !SaslPrepTables.contains(SaslPrepTables.randAL, normalized[normalized.size - 1])
                )
        ) {
            throw SaslMechanismException("SASLprep violates bidirectional rules", SaslMechanismFailure.BIDIRECTIONAL_STRING)
        }
        return normalized.asString(value)
    }

    private class NormalizedCodePoints(capacity: Int) {
        private var values = IntArray(capacity)
        var size: Int = 0
            private set

        operator fun get(index: Int): Int = values[index]

        fun appendDecomposed(codePoint: Int) {
            if (codePoint in S_BASE until S_BASE + S_COUNT) {
                val syllable = codePoint - S_BASE
                appendOrdered(L_BASE + syllable / N_COUNT)
                appendOrdered(V_BASE + syllable % N_COUNT / T_COUNT)
                val trailing = syllable % T_COUNT
                if (trailing != 0) appendOrdered(T_BASE + trailing)
                return
            }
            val index = SaslPrepTables.find(SaslPrepTables.decompositions, codePoint, 3)
            if (index < 0) {
                appendOrdered(codePoint)
                return
            }
            val start = SaslPrepTables.decompositions[index + 1]
            val length = SaslPrepTables.decompositions[index + 2]
            for (offset in start until start + length) appendOrdered(SaslPrepTables.decompositionValues[offset])
        }

        private fun appendOrdered(codePoint: Int) {
            if (size == values.size) values = values.copyOf(maxOf(8, size * 2))
            val combiningClass = SaslPrepTables.combiningClass(codePoint)
            var index = size
            if (combiningClass != 0) {
                while (index > 0 && SaslPrepTables.combiningClass(values[index - 1]) > combiningClass) {
                    values[index] = values[index - 1]
                    index -= 1
                }
            }
            values[index] = codePoint
            size += 1
        }

        fun compose() {
            var starterIndex = -1
            var starter = 0
            var previousClass = 0
            var outputSize = 0
            for (index in 0 until size) {
                val codePoint = values[index]
                val combiningClass = SaslPrepTables.combiningClass(codePoint)
                val composite = if (starterIndex >= 0 && (previousClass == 0 || previousClass < combiningClass)) {
                    composePair(starter, codePoint)
                } else {
                    -1
                }
                if (composite >= 0) {
                    values[starterIndex] = composite
                    starter = composite
                } else {
                    values[outputSize] = codePoint
                    if (combiningClass == 0) {
                        starterIndex = outputSize
                        starter = codePoint
                    }
                    previousClass = combiningClass
                    outputSize += 1
                }
            }
            size = outputSize
        }

        fun asString(original: String): String {
            var offset = 0
            var unchanged = true
            var utf16Length = 0
            for (index in 0 until size) {
                val codePoint = values[index]
                val charCount = Character.charCount(codePoint)
                utf16Length += charCount
                if (offset >= original.length || original.codePointAt(offset) != codePoint) unchanged = false
                offset += charCount
            }
            if (unchanged && offset == original.length) return original
            if (size == 0) return ""
            val output = StringBuilder(utf16Length)
            for (index in 0 until size) output.appendCodePoint(values[index])
            return output.toString()
        }

        private fun composePair(first: Int, second: Int): Int {
            if (first in L_BASE until L_BASE + L_COUNT && second in V_BASE until V_BASE + V_COUNT) {
                return S_BASE + ((first - L_BASE) * V_COUNT + second - V_BASE) * T_COUNT
            }
            if (first in S_BASE until S_BASE + S_COUNT && (first - S_BASE) % T_COUNT == 0 &&
                second in T_BASE + 1 until T_BASE + T_COUNT
            ) {
                return first + second - T_BASE
            }
            return SaslPrepTables.compose(first, second)
        }
    }

    private const val S_BASE = 0xAC00
    private const val L_BASE = 0x1100
    private const val V_BASE = 0x1161
    private const val T_BASE = 0x11A7
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val N_COUNT = V_COUNT * T_COUNT
    private const val S_COUNT = L_COUNT * N_COUNT
}
