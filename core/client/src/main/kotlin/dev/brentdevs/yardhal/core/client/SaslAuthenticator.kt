package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import java.util.Base64

public sealed interface SaslOutcome {
    public data object Success : SaslOutcome
    public data class Failure(val numeric: Int, val description: String) : SaslOutcome
}

internal class SaslAuthenticator(
    authcid: String,
    password: String,
    advertisedMechanisms: Set<String>?,
    private val sendRaw: (String) -> Unit,
    private val onOutcome: (SaslOutcome) -> Unit,
    private val createMechanism: (String) -> SaslMechanism = { name -> defaultMechanism(name, authcid, password) },
    private val mode: String = "AUTO",
) {
    private var candidates: List<String> = selectedAmong(advertisedMechanisms)
    private val attempted = HashSet<String>()
    private var mechanism: SaslMechanism? = null
    private var serverMechanisms: Set<String>? = null
    private val inbound = StringBuilder()

    var isFinished: Boolean = false
        private set

    val currentMechanism: String?
        get() = mechanism?.name

    fun start() {
        if (isFinished) return
        if (!startNextMechanism()) fail(0, "server offers no supported SASL mechanism")
    }

    fun cancel(description: String) {
        fail(0, description)
    }

    fun handleMessage(message: IrcMessage) {
        if (!message.command.equals(AUTHENTICATE_COMMAND, ignoreCase = true)) return
        if (isFinished) return
        val current = mechanism ?: return
        val chunk = message.parameters.firstOrNull() ?: return
        if (chunk != CONTINUATION_MARKER) inbound.append(chunk)
        if (inbound.length > MAX_CHALLENGE_LENGTH) {
            abort("SASL challenge exceeds $MAX_CHALLENGE_LENGTH bytes")
            return
        }
        if (chunk.length == CHUNK_LENGTH) return
        val encoded = inbound.toString()
        inbound.setLength(0)
        val challenge = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            abort("malformed base64 in AUTHENTICATE payload")
            return
        }
        val response = try {
            current.respond(challenge)
        } catch (error: SaslMechanismException) {
            abort(error.message ?: "SASL mechanism failure")
            return
        }
        sendPayload(response)
    }

    fun handleNumeric(numeric: Int, message: IrcMessage) {
        if (isFinished) return
        when (numeric) {
            RPL_SASLMECHS -> serverMechanisms = parseMechanismList(message.parameters.getOrNull(1))
            RPL_SASLSUCCESS ->
                if (mechanism?.serverVerified == true) {
                    succeed()
                } else {
                    fail(numeric, "server reported success without proving its identity")
                }
            ERR_SASLFAIL -> if (!fallBackToOfferedMechanism()) fail(numeric, "SASL credentials were rejected; check the account and authentication settings")
            ERR_NICKLOCKED -> fail(numeric, "SASL account is locked or unavailable")
            ERR_SASLTOOLONG -> fail(numeric, "Server rejected the SASL response length")
            ERR_SASLABORTED -> fail(numeric, "SASL authentication was aborted")
            ERR_SASLALREADY -> fail(numeric, "Server rejected the requested SASL authentication exchange")
            else -> Unit
        }
    }

    private fun fallBackToOfferedMechanism(): Boolean {
        val offered = serverMechanisms ?: return false
        val current = mechanism?.name
        if (current != null && current in offered) return false
        candidates = selectedAmong(offered)
        return startNextMechanism()
    }

    private fun startNextMechanism(): Boolean {
        val next = candidates.firstOrNull { it !in attempted } ?: return false
        attempted += next
        mechanism = try {
            createMechanism(next)
        } catch (error: SaslMechanismException) {
            fail(0, error.message ?: "SASL mechanism failure")
            return false
        }
        serverMechanisms = null
        inbound.setLength(0)
        sendRaw("$AUTHENTICATE_COMMAND $next")
        return true
    }

    private fun sendPayload(payload: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(payload)
        var offset = 0
        while (offset < encoded.length) {
            val end = minOf(offset + CHUNK_LENGTH, encoded.length)
            sendRaw("$AUTHENTICATE_COMMAND ${encoded.substring(offset, end)}")
            offset = end
        }
        if (encoded.length % CHUNK_LENGTH == 0) sendRaw("$AUTHENTICATE_COMMAND $CONTINUATION_MARKER")
    }

    private fun abort(description: String) {
        if (isFinished) return
        sendRaw("$AUTHENTICATE_COMMAND $ABORT_MARKER")
        fail(0, description)
    }

    private fun succeed() {
        if (isFinished) return
        isFinished = true
        onOutcome(SaslOutcome.Success)
    }

    private fun fail(numeric: Int, description: String) {
        if (isFinished) return
        isFinished = true
        onOutcome(SaslOutcome.Failure(numeric, description))
    }

    private fun selectedAmong(offered: Set<String>?): List<String> {
        val selected = when (mode.uppercase()) {
            "AUTO" -> if (offered == null) listOf(PLAIN) else PREFERRED_MECHANISMS
            "PLAIN" -> listOf(PLAIN)
            "SCRAM_SHA_256" -> listOf(SCRAM_SHA_256)
            "EXTERNAL" -> listOf(EXTERNAL)
            else -> emptyList()
        }
        return if (offered == null) selected else selected.filter { it in offered }
    }

    internal companion object {
        const val PLAIN: String = "PLAIN"
        const val SCRAM_SHA_256: String = "SCRAM-SHA-256"
        const val EXTERNAL: String = "EXTERNAL"
        val PREFERRED_MECHANISMS: List<String> = listOf(SCRAM_SHA_256, PLAIN)

        const val AUTHENTICATE_COMMAND: String = "AUTHENTICATE"
        const val CONTINUATION_MARKER: String = "+"
        const val ABORT_MARKER: String = "*"
        const val CHUNK_LENGTH: Int = 400
        private const val MAX_CHALLENGE_LENGTH = 16_384

        const val ERR_NICKLOCKED: Int = 902
        const val RPL_SASLSUCCESS: Int = 903
        const val ERR_SASLFAIL: Int = 904
        const val ERR_SASLTOOLONG: Int = 905
        const val ERR_SASLABORTED: Int = 906
        const val ERR_SASLALREADY: Int = 907
        const val RPL_SASLMECHS: Int = 908

        fun parseMechanismList(value: String?): Set<String>? {
            val names = value?.split(',')?.map { it.trim().uppercase() }?.filterTo(LinkedHashSet()) { it.isNotEmpty() }
            return names?.takeIf { it.isNotEmpty() }
        }


        private fun defaultMechanism(name: String, authcid: String, password: String): SaslMechanism =
            when (name) {
                SCRAM_SHA_256 -> ScramSha256Mechanism(authcid, password)
                PLAIN -> PlainMechanism(authcid, password)
                EXTERNAL -> ExternalMechanism(authcid)
                else -> throw SaslMechanismException("unsupported SASL mechanism")
            }
    }
}
