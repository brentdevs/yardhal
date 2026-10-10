package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.protocol.IrcMessage

internal class ChannelSettingsController(
    private val state: PerNetworkState,
    private val buffer: (String) -> ConversationBuffer?,
    private val publish: (Map<String, ChannelSettingsState>) -> Unit,
    private val send: (IrcMessage) -> Boolean,
    private val deadline: (Long) -> Unit,
    private val redact: (String) -> String,
    private val nowMs: () -> Long,
) {
    private data class Request(
        val token: Long,
        val ref: ConversationRef,
        val label: String?,
        val mode: Char? = null,
        val enabled: Boolean = false,
        val parameter: String = "",
        val topic: String? = null,
        val list: Boolean = false,
        val changedMasks: MutableSet<String>? = if (list) HashSet() else null,
    )

    private val presentations = LinkedHashMap<String, ChannelSettingsState>()
    private var pending: Request? = null
    private var sequence = 0L
    private var unlabelledQuarantined = false

    fun open(key: String) {
        val current = buffer(key) ?: return
        if (current.ref.kind != dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL) return
        if (key !in presentations) {
            presentations[key] = snapshot(current, null)
            publish(presentations.toMap())
        } else refresh()
    }

    fun refresh() {
        if (presentations.isEmpty()) return
        for ((key, prior) in presentations) {
            val current = buffer(key)
            if (current == null) {
                presentations[key] = prior.copy(available = false, canEditModes = false, canEditTopic = false,
                    accessLists = invalidateAccessLists(prior.accessLists),
                    unavailableReason = "Channel is no longer joined")
            } else {
                presentations[key] = snapshot(current, prior)
            }
        }
        val request = pending
        if (request != null && presentations[request.ref.storageKey]?.available != true) {
            if (request.label == null) unlabelledQuarantined = true
            finish(request, ChannelSettingsRequestStatus.DISCONNECTED, "Channel is unavailable")
        }
        publish(presentations.toMap())
    }

    private fun snapshot(current: ConversationBuffer, prior: ChannelSettingsState?): ChannelSettingsState {
        val channel = state.channel(current.key)
        val member = channel?.members?.get(state.fold(state.ownNick))
        val rank = member?.symbol?.let(state.prefixModes::modeFor)?.let(state.prefixModes.modes::indexOf) ?: -1
        val operatorRank = state.prefixModes.modes.indexOf('o')
        val operator = rank >= 0 && operatorRank >= 0 && rank <= operatorRank
        val available = state.registered && current.joinState == JoinState.JOINED && member != null
        val priorLists = prior?.accessLists.orEmpty()
        val accessLists = if (available) priorLists else invalidateAccessLists(priorLists)
        val classes = state.isupport.chanmodes
        val modes = channel?.modeSnapshot().orEmpty()
        val supportedLists = buildSet {
            if ('b' in classes.listA) add('b')
            val except = state.isupport["EXCEPTS"]?.firstOrNull() ?: 'e'
            if (except in classes.listA) add(except)
            val invex = state.isupport["INVEX"]?.firstOrNull() ?: 'I'
            if (invex in classes.listA) add(invex)
        }.filter { it in "beI" }
        return ChannelSettingsState(
            storageKey = current.key,
            channel = current.ref.rawTarget,
            available = available,
            canEditModes = available && operator && channel?.modesComplete == true && !unlabelledQuarantined,
            canEditTopic = available && channel?.modesComplete == true && ('t'.toString() !in modes || operator) && !unlabelledQuarantined,
            topic = current.topic.orEmpty(),
            topicLengthLimit = state.isupport.topicLengthLimit,
            modes = (classes.listB + classes.alwaysWithParamC + classes.neverWithParamD).toList().distinct()
                .filterNot { it in state.prefixModes.modes }.map { mode ->
                    ChannelModeSetting(mode, mode.toString() in modes, modes[mode.toString()]?.firstOrNull().orEmpty(),
                        mode in classes.listB || mode in classes.alwaysWithParamC, mode in classes.listB)
                },
            accessLists = supportedLists.associateWith { mode ->
                (accessLists[mode] ?: ChannelAccessList(mode)).copy(limit = listLimit(mode))
            },
            requestStatus = prior?.requestStatus ?: ChannelSettingsRequestStatus.IDLE,
            requestDescription = prior?.requestDescription,
            error = prior?.error,
            unavailableReason = when {
                !available -> "Connect and join this channel to use settings"
                unlabelledQuarantined -> "Reconnect before retrying: an unlabelled request did not complete"
                channel?.modesComplete != true -> "Waiting for channel modes"
                !operator -> "Channel operator privileges are required for modes and access lists"
                else -> null
            },
        )
    }

    private fun invalidateAccessLists(lists: Map<Char, ChannelAccessList>): Map<Char, ChannelAccessList> {
        if (lists.values.all { it.entries.isEmpty() &&
                (it.status == ChannelAccessListStatus.IDLE || it.status == ChannelAccessListStatus.DISCONNECTED) }) return lists
        return lists.mapValues { (mode, access) ->
            if (access.status == ChannelAccessListStatus.DISCONNECTED) access.copy(entries = emptyList())
            else ChannelAccessList(mode, limit = access.limit)
        }
    }

    private fun listLimit(mode: Char): Int? = state.isupport["MAXLIST"]?.split(',')?.firstNotNullOfOrNull { entry ->
        val modes = entry.substringBefore(':')
        entry.substringAfter(':', "").toIntOrNull()?.takeIf { mode in modes && it > 0 }
    }

    fun mode(key: String, mode: Char, enabled: Boolean, parameter: String, list: Boolean = false) {
        open(key)
        val view = presentations[key] ?: return
        if (!view.canEditModes) return reject(key, "Channel operator privileges and a live joined channel are required")
        val definition = view.modes.firstOrNull { it.mode == mode }
        if (list && mode !in view.accessLists || !list && definition == null) return reject(key, "Mode $mode is not supported")
        val requiresParameter = list || if (enabled) definition?.parameterOnEnable == true else definition?.parameterOnDisable == true
        val value = if (!enabled && !list && definition?.parameterOnDisable == true && parameter.isEmpty()) definition.parameter else parameter
        if (requiresParameter && (value.isEmpty() || value.any { it.isWhitespace() || it.isISOControl() } || value.startsWith(':'))) {
            return reject(key, "Mode $mode requires a single nonempty parameter")
        }
        if (mode == 'l' && enabled && value.toIntOrNull()?.takeIf { it > 0 } == null) return reject(key, "Channel limit must be a positive integer")
        val limitedModes = state.isupport["MAXLIST"]?.split(',')?.firstOrNull { mode in it.substringBefore(':') }?.substringBefore(':').orEmpty()
        val access = view.accessLists[mode]
        val knownEntries = limitedModes.sumOf { limitedMode ->
            val known = view.accessLists[limitedMode]
            if (known != null && (known.status == ChannelAccessListStatus.COMPLETE || known.status == ChannelAccessListStatus.EMPTY)) {
                known.entries.size
            } else 0
        }
        if (list && enabled && access != null && access.limit?.let { knownEntries >= it } == true &&
            access.entries.none { modeParameterMatches(mode, value, it.mask) }
        ) return reject(key, "The server's access-list limit has been reached")
        begin(key, mode, enabled, value, null, false,
            IrcMessage(command = "MODE", parameters = listOf(view.channel, "${if (enabled) "+" else "-"}$mode") +
                if (requiresParameter) listOf(value) else emptyList()))
    }

    fun topic(key: String, topic: String) {
        open(key)
        val view = presentations[key] ?: return
        if (!view.canEditTopic) return reject(key, "You cannot change this channel's topic (+t requires an operator)")
        if (topic.any { it == '\r' || it == '\n' || it == '\u0000' }) return reject(key, "Topic must be one line")
        if (view.topicLengthLimit?.let { topic.toByteArray(Charsets.UTF_8).size > it } == true) return reject(key, "Topic exceeds the server's TOPICLEN limit")
        begin(key, null, false, "", topic, false, IrcMessage(command = "TOPIC", parameters = listOf(view.channel, topic)))
    }

    fun list(key: String, mode: Char) {
        open(key)
        val view = presentations[key] ?: return
        if (!view.available || unlabelledQuarantined) return reject(key, view.unavailableReason ?: "Channel unavailable")
        if (mode !in view.accessLists) return reject(key, "Access list $mode is not supported")
        begin(key, mode, false, "", null, true, IrcMessage(command = "MODE", parameters = listOf(view.channel, "+$mode")))
    }

    private fun reject(key: String, reason: String) {
        presentations[key]?.let { presentations[key] = it.copy(error = redact(reason),
            requestStatus = if (pending != null) it.requestStatus else ChannelSettingsRequestStatus.ERROR) }
        publish(presentations.toMap())
    }

    private fun begin(key: String, mode: Char?, enabled: Boolean, parameter: String, topic: String?, list: Boolean, frame: IrcMessage) {
        if (pending != null) return reject(key, "Wait for the current channel request to finish")
        val current = buffer(key) ?: return
        if (frame.toWire().toByteArray(Charsets.UTF_8).size > 510) return reject(key, "Command exceeds the IRC message limit")
        val label = state.issueLabel(current.ref, if (topic == null) LabeledCommand.MODE else LabeledCommand.TOPIC, nowMs())
        val tagged = if (label == null) frame else frame.copy(tags = mapOf("label" to label))
        val request = Request(++sequence, current.ref, label, mode, enabled, parameter, topic, list)
        pending = request
        val view = presentations.getValue(key)
        presentations[key] = if (list && mode != null) view.copy(error = null,
            accessLists = view.accessLists + (mode to ChannelAccessList(mode, ChannelAccessListStatus.LOADING, limit = listLimit(mode)))) else
            view.copy(requestStatus = ChannelSettingsRequestStatus.PENDING, requestDescription = if (topic != null) "Change topic" else redact("${if (enabled) "+" else "-"}$mode $parameter"), error = null)
        publish(presentations.toMap())
        if (!send(tagged)) finish(request, ChannelSettingsRequestStatus.ERROR, "Command was not sent; reconnect and try again") else deadline(request.token)
    }

    fun receive(message: IrcMessage, label: String?) {
        if (message.command == "MODE") reconcileAccessLists(message)
        val request = pending ?: return
        val labelled = label != null
        if (labelled && label != request.label) return
        val numeric = message.numeric
        val target = if (numeric != null) message.parameters.getOrNull(1) else message.parameters.firstOrNull()
        val sameChannel = target?.let { state.casemapping.equal(it, request.ref.rawTarget) } == true
        val standardFailure = message.command == "FAIL" && message.parameters.firstOrNull() ==
            (if (request.topic == null) "MODE" else "TOPIC")
        val failureChannel = sameChannel || standardFailure && message.parameters.any { state.casemapping.equal(it, request.ref.rawTarget) }
        val numericFailure = numeric != null && (numeric in 400..599 || numeric == 696 || numeric == 697)
        if ((numericFailure || standardFailure) &&
            (labelled && label == request.label || !labelled && request.label == null &&
                (failureChannel && (standardFailure || numeric in CHANNEL_ERROR_NUMERICS) ||
                    numeric == 461 && message.parameters.getOrNull(1) == (if (request.topic == null) "MODE" else "TOPIC") ||
                    numeric == 472 && message.parameters.getOrNull(1) == request.mode?.toString()))) {
            finish(request, ChannelSettingsRequestStatus.ERROR, "${message.command}: ${message.parameters.drop(1).joinToString(" ")}")
            return
        }
        if (!sameChannel) return
        if (request.list) {
            if (request.label != null && !labelled) return
            val mode = request.mode ?: return
            val entryNumeric = when (mode) { 'b' -> 367; 'e' -> 348; 'I' -> 346; else -> return }
            val view = presentations[request.ref.storageKey] ?: return
            val access = view.accessLists[mode] ?: return
            when (numeric) {
                entryNumeric -> {
                    val mask = message.parameters.getOrNull(2) ?: return
                    if (request.changedMasks?.contains(state.fold(normalizeAccessMask(mask))) == true) return
                    val prior = access.entries.filterNot { modeParameterMatches(mode, mask, it.mask) }
                    if (prior.size >= MAX_ENTRIES) {
                        if (request.label == null) unlabelledQuarantined = true
                        finish(request, ChannelSettingsRequestStatus.ERROR, "Access list exceeds the $MAX_ENTRIES-entry display bound; result is incomplete")
                        return
                    }
                    val entry = ChannelAccessEntry(mask, message.parameters.getOrNull(3), message.parameters.getOrNull(4)?.toLongOrNull())
                    presentations[request.ref.storageKey] = view.copy(accessLists = view.accessLists + (mode to access.copy(entries = prior + entry)))
                    publish(presentations.toMap())
                }
                entryNumeric + 1 -> finish(request, ChannelSettingsRequestStatus.CONFIRMED, null)
            }
        } else if (request.topic != null) {
            if ((message.command == "TOPIC" && (state.isOwnNick(message.prefix?.nick.orEmpty()) || labelled) || numeric == 332 && labelled || numeric == 331 && labelled) &&
                (if (numeric == 331) "" else message.parameters.lastOrNull()) == request.topic
            ) finish(request, ChannelSettingsRequestStatus.CONFIRMED, null)
        } else if (message.command == "MODE" && (state.isOwnNick(message.prefix?.nick.orEmpty()) || labelled)) {
            val confirmed = modeDeltas(message).firstOrNull { (mode, adding, value) ->
                mode == request.mode && adding == request.enabled &&
                    modeParameterMatches(mode, request.parameter, value)
            }
            if (confirmed != null) {
                val mode = request.mode
                val view = presentations[request.ref.storageKey]
                val access = view?.accessLists?.get(mode)
                if (mode != null && view != null && access != null &&
                    access.status != ChannelAccessListStatus.COMPLETE && access.status != ChannelAccessListStatus.EMPTY) {
                    val confirmedMask = confirmed.third ?: request.parameter
                    val entries = access.entries.filterNot { state.casemapping.equal(it.mask, confirmedMask) } +
                        if (request.enabled) listOf(ChannelAccessEntry(confirmedMask, message.prefix?.nick,
                            parseServerTime(message.tag("time"))?.div(1_000))) else emptyList()
                    presentations[request.ref.storageKey] = view.copy(accessLists = view.accessLists + (mode to access.copy(entries = entries)))
                }
                finish(request, ChannelSettingsRequestStatus.CONFIRMED, null)
            }
        } else if (numeric == 324 && labelled && request.mode != null) {
            val modes = state.channel(request.ref.storageKey)?.modeSnapshot().orEmpty()
            val enabled = request.mode.toString() in modes
            if (enabled == request.enabled && (!request.enabled || modeParameterMatches(request.mode, request.parameter, modes[request.mode.toString()]?.firstOrNull()))) {
                finish(request, ChannelSettingsRequestStatus.CONFIRMED, null)
            }
        }
    }

    private fun modeParameterMatches(mode: Char, expected: String, actual: String?): Boolean = when {
        expected.isEmpty() -> true
        actual == null -> false
        mode == 'l' -> expected.toIntOrNull()?.let { it == actual.toIntOrNull() } == true
        mode in state.isupport.chanmodes.listA -> state.casemapping.equal(normalizeAccessMask(expected), normalizeAccessMask(actual))
        else -> expected == actual
    }

    private fun normalizeAccessMask(mask: String): String {
        if (mask.startsWith('$') || mask.startsWith('~')) return mask
        if ('!' in mask) return if ('@' in mask) mask else "$mask@*"
        if ('@' in mask) return "*!$mask"
        return if ('.' in mask || ':' in mask) "*!*@$mask" else "$mask!*@*"
    }

    private fun reconcileAccessLists(message: IrcMessage) {
        val target = message.parameters.firstOrNull() ?: return
        val key = state.channelRef(target).storageKey
        var view = presentations[key] ?: return
        for ((mode, adding, mask) in modeDeltas(message)) {
            if (mask == null) continue
            val access = view.accessLists[mode] ?: continue
            val loading = access.status == ChannelAccessListStatus.LOADING
            val request = pending?.takeIf { loading && it.list && it.mode == mode && it.ref.storageKey == key }
            if (loading && request == null || !loading &&
                access.status != ChannelAccessListStatus.COMPLETE && access.status != ChannelAccessListStatus.EMPTY) continue
            val entry = if (adding) ChannelAccessEntry(mask, message.prefix?.nick,
                parseServerTime(message.tag("time"))?.div(1_000)) else null
            val changes = request?.changedMasks
            val prior = access.entries.filterNot { modeParameterMatches(mode, mask, it.mask) }
            val maskKey = changes?.let { state.fold(normalizeAccessMask(mask)) }
            if (request != null && (changes != null && maskKey !in changes && changes.size >= MAX_ENTRIES ||
                    adding && prior.size >= MAX_ENTRIES)) {
                if (request.label == null) unlabelledQuarantined = true
                finish(request, ChannelSettingsRequestStatus.ERROR, "Live access-list changes exceed the $MAX_ENTRIES-entry display bound; result is incomplete")
                refresh()
                return
            }
            if (changes != null && maskKey != null) changes.add(maskKey)
            val entries = if (entry != null) prior + entry else prior
            view = view.copy(accessLists = view.accessLists + (mode to access.copy(entries = entries,
                status = if (loading) ChannelAccessListStatus.LOADING else if (entries.isEmpty()) ChannelAccessListStatus.EMPTY else ChannelAccessListStatus.COMPLETE)))
        }
        presentations[key] = view
        publish(presentations.toMap())
    }

    private fun modeDeltas(message: IrcMessage): List<Triple<Char, Boolean, String?>> {
        val result = ArrayList<Triple<Char, Boolean, String?>>()
        var adding = true
        var index = 2
        val classes = state.isupport.chanmodes
        for (mode in message.parameters.getOrNull(1).orEmpty()) {
            when (mode) {
                '+' -> adding = true
                '-' -> adding = false
                else -> {
                    if (mode !in classes.listA && mode !in classes.listB && mode !in classes.alwaysWithParamC &&
                        mode !in classes.neverWithParamD && mode !in state.prefixModes.modes) return emptyList()
                    val requiresParameter = mode in classes.listA || mode in classes.listB || mode in state.prefixModes.modes ||
                        adding && mode in classes.alwaysWithParamC
                    val parameter = if (requiresParameter) message.parameters.getOrNull(index++) ?: return emptyList() else null
                    result += Triple(mode, adding, parameter)
                }
            }
        }
        return result
    }

    fun timeout(token: Long) {
        val request = pending?.takeIf { it.token == token } ?: return
        if (request.label == null) unlabelledQuarantined = true
        finish(request, ChannelSettingsRequestStatus.TIMEOUT, "Server did not complete the request; changes are unconfirmed")
        refresh()
    }

    fun disconnect() {
        for ((key, view) in presentations) {
            presentations[key] = view.copy(accessLists = invalidateAccessLists(view.accessLists))
        }
        pending?.let { finish(it, ChannelSettingsRequestStatus.DISCONNECTED, "Connection closed; changes are unconfirmed") }
        unlabelledQuarantined = false
        refresh()
    }

    private fun finish(request: Request, status: ChannelSettingsRequestStatus, error: String?) {
        if (pending !== request) return
        pending = null
        request.label?.let(state.pendingLabels::remove)
        val safeError = error?.let(redact)
        val view = presentations[request.ref.storageKey] ?: return
        presentations[request.ref.storageKey] = if (request.list && request.mode != null) {
            val access = view.accessLists[request.mode] ?: return
            val listStatus = when (status) {
                ChannelSettingsRequestStatus.CONFIRMED -> if (access.entries.isEmpty()) ChannelAccessListStatus.EMPTY else ChannelAccessListStatus.COMPLETE
                ChannelSettingsRequestStatus.TIMEOUT -> ChannelAccessListStatus.TIMEOUT
                ChannelSettingsRequestStatus.DISCONNECTED -> ChannelAccessListStatus.DISCONNECTED
                else -> ChannelAccessListStatus.ERROR
            }
            view.copy(accessLists = view.accessLists + (request.mode to access.copy(status = listStatus, error = safeError)), error = safeError)
        } else view.copy(requestStatus = status, error = safeError)
        publish(presentations.toMap())
    }

    companion object {
        const val TIMEOUT_MS = 6_000L
        val CHANNEL_ERROR_NUMERICS = setOf(403, 442, 467, 478, 482, 484, 501, 696, 697)
        const val MAX_ENTRIES = 512
    }
}
