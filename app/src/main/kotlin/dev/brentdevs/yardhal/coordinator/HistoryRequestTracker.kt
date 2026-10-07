package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.protocol.IrcMessage

internal enum class HistoryOperation { LATEST, BEFORE, BETWEEN, TARGETS }

internal enum class HistoryTransport { CHATHISTORY, ZNC_PLAYBACK }

internal data class PendingHistoryRequest(
    val id: Long,
    val target: String?,
    val operation: HistoryOperation,
    val line: String,
    val limit: Int,
    val label: String? = null,
    val transport: HistoryTransport = HistoryTransport.CHATHISTORY,
    val fromTimestampMs: Long? = null,
    val toTimestampMs: Long? = null,
)

internal data class HistoryTarget(val target: String, val timestampMs: Long)

internal data class HistoryResult(
    val request: PendingHistoryRequest,
    val frames: List<IrcMessage> = emptyList(),
    val targets: List<HistoryTarget> = emptyList(),
    val end: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
    val batchComplete: Boolean = false,
)

internal data class HistoryReceipt(val consumed: Boolean, val result: HistoryResult? = null)

internal class HistoryRequestTracker(
    private val fold: (String) -> String,
    private val ownNick: () -> String,
) {
    private enum class BatchKind { OTHER, WRAPPER, HISTORY }

    private data class Batch(
        val parent: String?,
        val label: String?,
        val kind: BatchKind,
        val ownerId: Long?,
        var retired: Boolean = false,
    )

    private class Active(val request: PendingHistoryRequest, nowMs: Long) {
        val frames = ArrayList<IrcMessage>()
        val targets = ArrayList<HistoryTarget>()
        val historyRoots = mutableSetOf<String>()
        val hardDeadlineMs = nowMs + REQUEST_TIMEOUT_MS
        var captureWeight = 0
        var completionReference: String? = null
        var openBatchCount = 0
        var historySeen = false
        var payloadSeen = false
        var end = false
        var lastActivityMs = nowMs
    }

    private val batches = mutableMapOf<String, Batch>()
    private val retiredRequests = ArrayDeque<PendingHistoryRequest>()
    private var active: Active? = null

    val pending: PendingHistoryRequest?
        get() = active?.request

    val deadlineMs: Long?
        get() {
            val current = active ?: return null
            return if (current.request.transport == HistoryTransport.ZNC_PLAYBACK && current.openBatchCount == 0) {
                minOf(current.hardDeadlineMs, current.lastActivityMs + ZNC_QUIET_MS)
            } else {
                current.hardDeadlineMs
            }
        }

    var requiresReconnect: Boolean = false
        private set

    fun begin(request: PendingHistoryRequest, nowMs: Long): Boolean {
        if (active != null || requiresReconnect) return false
        if (request.label != null && retiredRequests.any { it.label == request.label }) return false
        active = Active(request, nowMs)
        return true
    }

    fun receive(message: IrcMessage, nowMs: Long): HistoryReceipt {
        val expired = expire(nowMs)
        val receipt = receiveCurrent(message, nowMs)
        return if (expired != null) receipt.copy(result = expired) else receipt
    }

    fun expire(nowMs: Long): HistoryResult? {
        val current = active ?: return null
        val deadline = deadlineMs ?: return null
        if (nowMs < deadline) return null
        if (current.request.transport == HistoryTransport.ZNC_PLAYBACK &&
            current.openBatchCount == 0 && current.lastActivityMs + ZNC_QUIET_MS < current.hardDeadlineMs
        ) {
            return finish(current)
        }
        return finish(current, "History request timed out", uncertain = true)
    }

    fun cancel(reason: String): HistoryResult? {
        val current = active
        val reset = reason.equals("connection reset", ignoreCase = true)
        val result = current?.let { finish(it, reason, uncertain = !reset) }
        if (reset) {
            batches.clear()
            retiredRequests.clear()
            requiresReconnect = false
        }
        return result
    }

    private fun receiveCurrent(message: IrcMessage, nowMs: Long): HistoryReceipt {
        if (message.command.equals("BATCH", ignoreCase = true)) return receiveBatch(message, nowMs)
        val batch = message.tag("batch")?.let(batches::get)
        val label = effectiveLabel(message, batch)
        if (batch?.retired == true && labelMatches(message, batch.label, label)) return HistoryReceipt(true)
        if (isRetiredReply(message, label)) return HistoryReceipt(true)
        val current = active ?: return HistoryReceipt(false)
        if (!labelMatches(message, current.request.label, label)) return HistoryReceipt(false)
        val owned = batch?.takeIf { it.ownerId == current.request.id && !it.retired }
        if (message.tag("batch") != null && owned == null) return HistoryReceipt(false)
        val error = responseError(message, current.request)
        if (error != null) {
            val code = if (message.command.equals("FAIL", ignoreCase = true)) message.parameters.getOrNull(1) else null
            return HistoryReceipt(true, finish(current, error, uncertain = current.historyRoots.isNotEmpty(), errorCode = code))
        }
        if (owned?.kind == BatchKind.HISTORY) {
            current.payloadSeen = true
            return capturePayload(current, message, nowMs)
        }
        if (owned?.kind == BatchKind.WRAPPER && message.command.equals("ACK", ignoreCase = true)) {
            return HistoryReceipt(true)
        }
        if (current.request.label != null && message.command.equals("ACK", ignoreCase = true)) {
            return HistoryReceipt(true, finish(
                current,
                "History response acknowledged without a complete history batch",
                uncertain = current.openBatchCount > 0,
            ))
        }
        if (current.request.transport == HistoryTransport.ZNC_PLAYBACK &&
            message.tag("batch") == null && matchesBarePlayback(message, current.request)
        ) {
            current.payloadSeen = true
            return capturePayload(current, message, nowMs)
        }
        return HistoryReceipt(false)
    }

    private fun receiveBatch(message: IrcMessage, nowMs: Long): HistoryReceipt {
        val head = message.parameters.firstOrNull() ?: return HistoryReceipt(false)
        if (head.length < 2) return HistoryReceipt(false)
        val reference = head.drop(1)
        return when (head.first()) {
            '+' -> openBatch(message, reference, nowMs)
            '-' -> closeBatch(message, reference, nowMs)
            else -> HistoryReceipt(false)
        }
    }

    private fun openBatch(message: IrcMessage, reference: String, nowMs: Long): HistoryReceipt {
        val type = message.parameters.getOrNull(1) ?: return HistoryReceipt(false)
        val parentReference = message.tag("batch")
        val parent = parentReference?.let(batches::get)
        val label = effectiveLabel(message, parent)
        val current = active
        if (reference in batches) {
            return if (current != null && batches[reference]?.ownerId == current.request.id) {
                HistoryReceipt(true, finish(current, "Duplicate history batch reference", uncertain = true))
            } else {
                HistoryReceipt(false)
            }
        }
        val retired = parent?.retired == true && labelMatches(message, parent.label, label) ||
            retiredRequests.any { request ->
                (parentReference == null || parent?.retired == true) &&
                    (request.target != "*" || request.label != null || requiresReconnect) &&
                    labelMatches(message, request.label, label) &&
                    (matchesRoot(message, request) || request.label != null && type == "labeled-response")
            }
        val ownedParent = parent?.takeIf { current != null && it.ownerId == current.request.id && !it.retired }
        val eligible = current != null && labelMatches(message, current.request.label, label) &&
            (parentReference == null || ownedParent != null)
        val kind = when {
            !eligible || retired -> BatchKind.OTHER
            ownedParent?.kind == BatchKind.HISTORY -> BatchKind.HISTORY
            matchesRoot(message, current.request) -> BatchKind.HISTORY
            type == "labeled-response" && current.request.label != null -> BatchKind.WRAPPER
            else -> BatchKind.OTHER
        }
        val owned = kind != BatchKind.OTHER
        if (batches.size >= MAX_OPEN_BATCHES) {
            return if (owned && current != null) {
                HistoryReceipt(true, finish(current, "Too many open history batches", uncertain = true))
            } else {
                HistoryReceipt(false)
            }
        }
        batches[reference] = Batch(parentReference, label, kind, current?.request?.id?.takeIf { owned }, retired)
        if (retired) return HistoryReceipt(true)
        if (!owned || current == null) return HistoryReceipt(false)
        current.openBatchCount++
        if (current.completionReference == null) current.completionReference = reference
        if (kind == BatchKind.HISTORY && ownedParent?.kind != BatchKind.HISTORY) {
            current.historySeen = true
            current.historyRoots.add(reference)
            if ("draft/chathistory-end" in message.tags && current.request.transport == HistoryTransport.CHATHISTORY) {
                current.end = true
            }
        }
        return capture(current, message, nowMs)
    }

    private fun closeBatch(message: IrcMessage, reference: String, nowMs: Long): HistoryReceipt {
        val batch = batches[reference] ?: return HistoryReceipt(false)
        val label = effectiveLabel(message, batch)
        if (!labelMatches(message, batch.label, label)) return HistoryReceipt(false)
        val current = active
        if (batch.retired) {
            batches.remove(reference)
            return HistoryReceipt(true)
        }
        if (current == null || batch.ownerId != current.request.id) {
            batches.remove(reference)
            return HistoryReceipt(false)
        }
        batches.remove(reference)
        current.openBatchCount--
        current.historyRoots.remove(reference)
        val captured = capture(current, message, nowMs)
        if (captured.result != null) return captured
        if (batches.any { (_, child) -> child.parent == reference && child.ownerId == current.request.id }) {
            return HistoryReceipt(true, finish(current, "History batch closed with unfinished nested batches", uncertain = true))
        }
        if (current.request.transport == HistoryTransport.ZNC_PLAYBACK && current.request.target == "*") {
            return HistoryReceipt(true)
        }
        if (current.completionReference != reference) return HistoryReceipt(true)
        if (!current.historySeen || current.historyRoots.isNotEmpty()) {
            return HistoryReceipt(true, finish(current, "History response completed without a complete history batch", uncertain = true))
        }
        return HistoryReceipt(true, finish(current))
    }

    private fun capturePayload(current: Active, message: IrcMessage, nowMs: Long): HistoryReceipt {
        if (current.request.operation == HistoryOperation.TARGETS) {
            val timestamp = message.parameters.getOrNull(2)?.let(::parseServerTime)
            if (!message.command.equals("CHATHISTORY", ignoreCase = true) ||
                !message.parameters.firstOrNull().equals("TARGETS", ignoreCase = true) ||
                message.parameters.size != 3 || timestamp == null
            ) {
                return HistoryReceipt(true, finish(current, "Invalid CHATHISTORY TARGETS response", uncertain = true))
            }
            val captured = capture(current, message, nowMs)
            if (captured.result == null) current.targets.add(HistoryTarget(message.parameters[1], timestamp))
            return captured
        }
        return capture(current, message, nowMs)
    }

    private fun capture(current: Active, message: IrcMessage, nowMs: Long): HistoryReceipt {
        val weight = captureWeight(message)
        if (weight > MAX_CAPTURE_WEIGHT - current.captureWeight) {
            return HistoryReceipt(true, finish(current, "History response exceeded the capture limit", uncertain = true))
        }
        current.captureWeight += weight
        current.frames.add(message)
        current.lastActivityMs = nowMs
        return HistoryReceipt(true)
    }

    private fun captureWeight(message: IrcMessage): Int {
        var weight = 512 + message.command.length * 2
        message.prefix?.let { weight += 160 + (it.nick.length + (it.user?.length ?: 0) + (it.host?.length ?: 0)) * 2 }
        for (parameter in message.parameters) weight += 64 + parameter.length * 2
        for ((key, value) in message.tags) weight += 128 + (key.length + (value?.length ?: 0)) * 2
        return weight
    }

    private fun finish(current: Active, error: String? = null, uncertain: Boolean = false, errorCode: String? = null): HistoryResult {
        active = null
        if (error != null) {
            if (uncertain || current.request.label != null) retire(current.request)
            if (uncertain && current.request.label == null) requiresReconnect = true
            batches.values.forEach { batch ->
                if (batch.ownerId == current.request.id) batch.retired = true
            }
        } else {
            val iterator = batches.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.ownerId == current.request.id) iterator.remove()
            }
        }
        return HistoryResult(
            request = current.request,
            frames = current.frames,
            targets = current.targets,
            end = error == null && current.request.transport == HistoryTransport.CHATHISTORY &&
                (current.end || !current.payloadSeen),
            error = error,
            errorCode = errorCode,
            batchComplete = error == null && current.historySeen && current.openBatchCount == 0,
        )
    }

    private fun retire(request: PendingHistoryRequest) {
        if (retiredRequests.size == MAX_RETIRED_REQUESTS) retiredRequests.removeFirst()
        retiredRequests.addLast(request)
    }

    private fun effectiveLabel(message: IrcMessage, batch: Batch?): String? =
        if ("label" in message.tags) message.tag("label") else batch?.label

    private fun labelMatches(message: IrcMessage, expected: String?, actual: String?): Boolean =
        actual == expected && ("label" !in message.tags || message.tag("label") != null)

    private fun matchesRoot(message: IrcMessage, request: PendingHistoryRequest): Boolean {
        val type = message.parameters.getOrNull(1) ?: return false
        if (request.transport == HistoryTransport.ZNC_PLAYBACK) {
            val target = message.parameters.getOrNull(2) ?: return false
            return type == "znc.in/playback" &&
                (sameTarget(target, request.target) || request.target == "*" && !target.startsWith("*"))
        }
        if (request.operation == HistoryOperation.TARGETS) {
            return type == "draft/chathistory-targets" || type == "chathistory-targets"
        }
        return type == "chathistory" && sameTarget(message.parameters.getOrNull(2), request.target)
    }

    private fun sameTarget(actual: String?, expected: String?): Boolean =
        actual != null && expected != null && fold(actual) == fold(expected)

    private fun matchesBarePlayback(message: IrcMessage, request: PendingHistoryRequest): Boolean {
        if (!message.command.equals("PRIVMSG", ignoreCase = true) &&
            !message.command.equals("NOTICE", ignoreCase = true)
        ) return false
        val timestamp = parseServerTime(message.tag("time")) ?: return false
        val from = request.fromTimestampMs ?: return false
        val to = request.toTimestampMs ?: return false
        if (timestamp <= from || timestamp > to) return false
        val target = message.parameters.firstOrNull() ?: return false
        val sender = message.prefix?.nick ?: return false
        if (target.startsWith("*") || sender.startsWith("*")) return false
        val own = fold(ownNick())
        val foldedSender = fold(sender)
        val foldedTarget = fold(target)
        val fromUs = foldedSender == own
        val toUs = foldedTarget == own
        val channel = target.firstOrNull()?.let { it in CHANNEL_PREFIXES } == true
        if (request.target == "*") return channel || fromUs || toUs
        val requestedTarget = request.target?.let(fold) ?: return false
        if (channel) return foldedTarget == requestedTarget
        return fromUs && foldedTarget == requestedTarget || toUs && foldedSender == requestedTarget
    }

    private fun responseError(message: IrcMessage, request: PendingHistoryRequest): String? {
        if (message.command.equals("FAIL", ignoreCase = true)) {
            if (!message.parameters.firstOrNull().equals("CHATHISTORY", ignoreCase = true) ||
                request.transport != HistoryTransport.CHATHISTORY
            ) return null
            val operation = message.parameters.getOrNull(2)
            if (operation != null && message.parameters.size > 3 && !operation.equals(request.operation.name, ignoreCase = true)) {
                return null
            }
            val code = message.parameters.getOrNull(1).orEmpty()
            if (code in TARGET_FAILURE_CODES && message.parameters.size > 4 && request.target != null &&
                !sameTarget(message.parameters[3], request.target)
            ) return null
            return message.parameters.drop(1).joinToString(" ").ifEmpty { "CHATHISTORY failed" }
        }
        val numeric = message.numeric
        if (numeric == 421 || numeric == 461) {
            val command = if (request.transport == HistoryTransport.CHATHISTORY) "CHATHISTORY" else "ZNC"
            if (message.parameters.getOrNull(1).equals(command, ignoreCase = true)) {
                return message.parameters.lastOrNull() ?: "History command failed"
            }
        }
        if (numeric in TARGET_ERROR_NUMERICS) {
            val target = if (request.transport == HistoryTransport.CHATHISTORY) request.target else "*playback"
            if (sameTarget(message.parameters.getOrNull(1), target)) {
                return message.parameters.lastOrNull() ?: "History target failed"
            }
        }
        if (request.transport == HistoryTransport.ZNC_PLAYBACK &&
            (message.command.equals("PRIVMSG", ignoreCase = true) || message.command.equals("NOTICE", ignoreCase = true)) &&
            sameTarget(message.prefix?.nick, "*playback")
        ) {
            val text = message.parameters.getOrNull(1) ?: return null
            if (PLAYBACK_ERROR_PREFIXES.any { text.startsWith(it, ignoreCase = true) }) return text
        }
        return null
    }

    private fun isRetiredReply(message: IrcMessage, label: String?): Boolean {
        if (label == null) return requiresReconnect && retiredRequests.any {
            it.label == null && it.transport == HistoryTransport.ZNC_PLAYBACK && matchesBarePlayback(message, it)
        }
        return retiredRequests.any { request ->
            request.label == label && (responseError(message, request) != null || message.command.equals("ACK", ignoreCase = true))
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 6_000L
        const val ZNC_QUIET_MS = 2_000L
        const val MAX_CAPTURE_WEIGHT = 8 * 1024 * 1024
        const val MAX_OPEN_BATCHES = 256
        const val MAX_RETIRED_REQUESTS = 64
        val TARGET_FAILURE_CODES = setOf("INVALID_TARGET", "MESSAGE_ERROR", "INVALID_MSGREFTYPE")
        val CHANNEL_PREFIXES = setOf('#', '&', '+', '!')
        val TARGET_ERROR_NUMERICS = setOf(401, 403, 404, 442)
        val PLAYBACK_ERROR_PREFIXES = listOf("Error", "Usage:", "Unknown command", "Invalid", "Unable", "Failed", "No such")
    }
}
