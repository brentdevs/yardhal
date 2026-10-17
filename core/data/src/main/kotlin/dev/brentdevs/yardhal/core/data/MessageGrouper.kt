package dev.brentdevs.yardhal.core.data

public data class GroupedMessage(
    public val index: Int,
    public val groupedWithPrevious: Boolean,
)

public object MessageGrouper {

    public const val GROUP_WINDOW_MS: Long = 5 * 60 * 1000

    public fun group(messages: List<Long>, isGroupable: (Int) -> Boolean, senderOf: (Int) -> String): List<GroupedMessage> {
        val result = ArrayList<GroupedMessage>(messages.size)
        for (index in messages.indices) {
            val olderIndex = index + 1
            val grouped =
                olderIndex < messages.size &&
                    isGroupable(index) &&
                    isGroupable(olderIndex) &&
                    senderOf(index) == senderOf(olderIndex) &&
                    messages[index] - messages[olderIndex] < GROUP_WINDOW_MS
            result.add(GroupedMessage(index = index, groupedWithPrevious = grouped))
        }
        return result
    }
}
