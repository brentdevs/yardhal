package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.protocol.IrcMessage

internal fun Reduction.correlateLabel(message: IrcMessage, command: String): PendingLabel? {
    val label = message.tag("label")
    if (label != null) {
        val opensBatch = command == "BATCH" && message.parameters.firstOrNull()?.startsWith("+") == true
        return if (opensBatch) state.pendingLabels[label] else state.pendingLabels.remove(label)
    }
    var reference = message.tag("batch")
    while (reference != null) {
        val batch = state.openBatches[reference] ?: return null
        batch.label?.let { return state.pendingLabels[it] }
        reference = batch.parent
    }
    return null
}

internal fun Reduction.releaseBatchLabel(batch: OpenBatch) {
    val label = batch.label ?: return
    state.pendingLabels.remove(label)
}

internal fun labelLine(line: String, label: String): String =
    if (line.startsWith("@")) "@label=$label;${line.drop(1)}" else "@label=$label $line"
