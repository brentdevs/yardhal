package dev.brentdevs.yardhal.core.client

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import java.util.Base64

public sealed interface SaslOutcome {
    public data object Success : SaslOutcome
    public data class Failure(val numeric: Int, val description: String) : SaslOutcome
}

public class SaslPlainHandler(
    private val authcid: String,
    private val password: String,
    private val authzid: String = "",
    private val sendRaw: (String) -> Unit,
    private val onOutcome: (SaslOutcome) -> Unit,
) {
    private var awaitingPayloadRequest: Boolean = false
    private var completed: Boolean = false

    public fun start() {
        sendRaw("AUTHENTICATE ${MECHANISM}")
        awaitingPayloadRequest = true
    }

    public fun handleMessage(message: IrcMessage): Boolean {
        if (!message.command.equals(AUTHENTICATE_COMMAND, ignoreCase = true)) return false
        if (completed) return true
        val payload = message.parameters.firstOrNull() ?: return true
        if (!awaitingPayloadRequest) return true
        if (payload != CONTINUATION_MARKER) {
            fail(0, "unexpected AUTHENTICATE payload from server")
            return true
        }
        sendRaw("AUTHENTICATE ${encodeCredentials()}")
        awaitingPayloadRequest = false
        return true
    }

    public fun handleNumeric(numeric: Int, message: IrcMessage): Boolean {
        when (numeric) {
            903 -> {
                succeed()
                return true
            }
            902, 904, 905, 906 -> {
                fail(numeric, message.parameters.lastOrNull() ?: "authentication failed")
                return true
            }
            else -> return false
        }
    }

    private fun succeed() {
        if (completed) return
        completed = true
        onOutcome(SaslOutcome.Success)
    }

    private fun fail(numeric: Int, description: String) {
        if (completed) return
        completed = true
        onOutcome(SaslOutcome.Failure(numeric, description))
    }

    private fun encodeCredentials(): String {
        val inner = "$authzid\u0000$authcid\u0000$password"
        return Base64.getEncoder().encodeToString(inner.toByteArray(Charsets.UTF_8))
    }

    public companion object {
        public const val MECHANISM: String = "PLAIN"
        public const val AUTHENTICATE_COMMAND: String = "AUTHENTICATE"
        public const val CONTINUATION_MARKER: String = "+"

        public const val RPL_LOGGEDIN: Int = 900
        public const val RPL_SASLSUCCESS: Int = 903
        public const val ERR_NICKLOCKED: Int = 902
        public const val ERR_SASLFAIL: Int = 904
        public const val ERR_SASLTOOLONG: Int = 905
        public const val ERR_SASLABORTED: Int = 906
    }
}
