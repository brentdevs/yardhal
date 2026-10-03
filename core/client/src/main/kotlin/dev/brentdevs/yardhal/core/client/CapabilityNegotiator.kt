package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage

public class CapabilityNegotiator(
    private val wanted: Set<String>,
    private val sendRaw: (String) -> Unit,
    private val onSaslAcknowledged: () -> Unit,
    private val onFinished: () -> Unit,
    private val beforeCapEnd: () -> Unit = {},
    private val onDeleted: (Set<String>) -> Unit = {},
    private val registrationHandshake: Boolean = true,
) {
    public enum class Phase { IDLE, LISTING, REQUESTING, AUTHENTICATING, FINISHED }

    public var phase: Phase = Phase.IDLE
        private set

    public val available: MutableSet<String> = LinkedHashSet()
    public val acknowledged: MutableSet<String> = LinkedHashSet()
    public val declined: MutableSet<String> = LinkedHashSet()

    private val values = LinkedHashMap<String, String>()
    private val acknowledgedThisRound = LinkedHashSet<String>()

    public val advertisedValues: Map<String, String>
        get() = values

    private var capEndSent: Boolean = false

    public fun begin() {
        if (phase != Phase.IDLE) return
        phase = Phase.LISTING
        sendRaw("CAP LS 302")
    }

    public fun handle(message: IrcMessage): Boolean {
        if (!message.command.equals(CAP_COMMAND, ignoreCase = true)) return false
        val params = message.parameters
        if (params.isEmpty()) return true

        var verbIndex = -1
        var verb = ""
        for (index in 0 until minOf(2, params.size)) {
            val candidate = params[index]
            if (candidate.uppercase() in KNOWN_VERBS) {
                verbIndex = index
                verb = candidate.uppercase()
                break
            }
        }
        if (verbIndex < 0) return true

        val tail = params.drop(verbIndex + 1)
        var multiline = false
        val payloadParts = ArrayList<String>(tail.size)
        for ((offset, part) in tail.withIndex()) {
            if (offset == 0 && part == "*") {
                multiline = true
            } else {
                payloadParts.add(part)
            }
        }
        val payload = payloadParts.joinToString(" ")

        when (verb) {
            "LS" -> {
                available += recordAdvertised(payload)
                if (!multiline && phase == Phase.LISTING) completeListing()
            }
            "ACK" -> {
                val names = splitNames(payload)
                acknowledged += names
                acknowledgedThisRound += names
                if (!multiline && phase == Phase.REQUESTING) completeRequest()
            }
            "NAK" -> {
                declined += splitNames(payload)
                if (!multiline && phase == Phase.REQUESTING) completeRequest()
            }
            "NEW" -> {
                val names = recordAdvertised(payload)
                available += names
                requestSubset((wanted intersect names) - acknowledged)
            }
            "DEL" -> {
                val names = splitNames(payload)
                available -= names
                acknowledged -= names
                for (name in names) values.remove(name)
                if (names.isNotEmpty()) onDeleted(names)
            }
        }
        return true
    }

    public fun saslFinished() {
        if (phase == Phase.AUTHENTICATING) finish()
    }

    private fun recordAdvertised(payload: String): Set<String> {
        val names = LinkedHashSet<String>()
        for (token in splitNames(payload)) {
            val separator = token.indexOf('=')
            val name = if (separator < 0) token else token.substring(0, separator)
            if (separator < 0) values.remove(name) else values[name] = token.substring(separator + 1)
            names += name
        }
        return names
    }

    private fun completeListing() {
        requestSubset(wanted intersect available)
    }

    private fun requestSubset(subset: Set<String>) {
        if (subset.isEmpty()) {
            if (phase == Phase.LISTING || phase == Phase.REQUESTING) finish()
            return
        }
        phase = Phase.REQUESTING
        acknowledgedThisRound.clear()
        sendRaw("CAP REQ :${subset.sorted().joinToString(" ")}")
    }

    private fun completeRequest() {
        if (phase != Phase.REQUESTING) return
        if (SASL_CAP in acknowledgedThisRound) {
            phase = Phase.AUTHENTICATING
            onSaslAcknowledged()
        } else {
            finish()
        }
    }

    private fun finish() {
        val alreadyDone = phase == Phase.FINISHED
        phase = Phase.FINISHED
        if (registrationHandshake && !alreadyDone && !capEndSent) {
            beforeCapEnd()
            capEndSent = true
            sendRaw("CAP END")
        }
        onFinished()
    }

    public companion object {
        public const val SASL_CAP: String = "sasl"
        private const val CAP_COMMAND = "CAP"
        private val KNOWN_VERBS = setOf("LS", "LIST", "ACK", "NAK", "END", "NEW", "DEL")

        internal fun splitNames(payload: String): Set<String> =
            payload.split(' ').filter { it.isNotEmpty() }.toSet()

    }
}
