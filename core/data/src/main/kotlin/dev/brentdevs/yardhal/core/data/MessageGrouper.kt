package dev.brentdevs.yardhal.core.data

public data class GroupedMessage(
    public val index: Int,
    public val groupedWithPrevious: Boolean,
)

public object MessageGrouper {

    public const val GROUP_WINDOW_MS: Long = 5 * 60 * 1000

    public fun group(messages: List<Long>, isGroupable: (Int) -> Boolean, senderOf: (Int) -> String): List<GroupedMessage> {
        val result = ArrayList<GroupedMessage>(messages.size)
        var previousIndex = -1
        for (index in messages.indices) {
            val grouped =
                previousIndex >= 0 &&
                    isGroupable(index) &&
                    isGroupable(previousIndex) &&
                    senderOf(index) == senderOf(previousIndex) &&
                    messages[previousIndex] - messages[index] < GROUP_WINDOW_MS
            result.add(GroupedMessage(index = index, groupedWithPrevious = grouped))
            previousIndex = index
        }
        return result
    }
}
