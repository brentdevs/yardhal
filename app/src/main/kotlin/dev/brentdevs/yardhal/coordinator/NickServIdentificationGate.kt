package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.IrcFormatting
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcPrefix

enum class NickServOutcome {
    WAITING,
    IDENTIFIED,
    REJECTED,
    TIMED_OUT,
}

class NickServIdentificationGate(
    private val service: String = "NickServ",
    private var ourNick: String,
    private val account: String? = null,
    timeoutMillis: Long = 7000,
    nowMillis: Long = 0,
) {
    init {
        require(timeoutMillis >= 0)
    }

    val deadlineMs: Long = if (nowMillis > Long.MAX_VALUE - timeoutMillis) Long.MAX_VALUE else nowMillis + timeoutMillis

    private var currentOutcome: NickServOutcome = NickServOutcome.WAITING

    val outcome: NickServOutcome
        get() = currentOutcome

    val currentNick: String
        get() = ourNick

    fun receive(message: IrcMessage): NickServOutcome {
        if (outcome != NickServOutcome.WAITING || isHistorical(message)) return outcome
        if (message.command.equals("NICK", true) && isOwnUser(message.prefix)) {
            val nickname = message.parameters.singleOrNull()
            if (nickname != null && nickname.isNotEmpty() && nickname.none { it.isWhitespace() || it == '\u0000' }) {
                ourNick = nickname
            }
            return outcome
        }
        currentOutcome = when {
            message.command.equals("ACCOUNT", true) -> receiveAccount(message)
            message.command.equals("NOTICE", true) -> receiveNotice(message)
            message.numeric != null -> receiveNumeric(message)
            else -> NickServOutcome.WAITING
        }
        return outcome
    }

    fun expire(nowMillis: Long): NickServOutcome {
        if (outcome == NickServOutcome.WAITING && nowMillis >= deadlineMs) currentOutcome = NickServOutcome.TIMED_OUT
        return outcome
    }

    private fun receiveAccount(message: IrcMessage): NickServOutcome {
        if (!isOwnUser(message.prefix) || message.parameters.size != 1) return NickServOutcome.WAITING
        return if (matchesAccount(message.parameters[0])) NickServOutcome.IDENTIFIED else NickServOutcome.WAITING
    }

    private fun receiveNumeric(message: IrcMessage): NickServOutcome {
        if (message.prefix?.isServer != true || !isOwnTarget(message)) return NickServOutcome.WAITING
        return when (message.numeric) {
            900 -> {
                if (message.parameters.size < 3) return NickServOutcome.WAITING
                val loggedInUser = IrcPrefix.parse(message.parameters[1])
                if (isOwnUser(loggedInUser) && matchesAccount(message.parameters[2])) {
                    NickServOutcome.IDENTIFIED
                } else {
                    NickServOutcome.WAITING
                }
            }
            464 -> NickServOutcome.REJECTED
            401 -> {
                if (message.parameters.size >= 3 && sameName(message.parameters[1], service)) {
                    NickServOutcome.REJECTED
                } else {
                    NickServOutcome.WAITING
                }
            }
            400, 451, 461 -> {
                if (message.parameters.size >= 3 && message.parameters[1].equals("IDENTIFY", true)) {
                    NickServOutcome.REJECTED
                } else {
                    NickServOutcome.WAITING
                }
            }
            else -> NickServOutcome.WAITING
        }
    }

    private fun receiveNotice(message: IrcMessage): NickServOutcome {
        if (!sameName(message.prefix?.nick, service) || !isOwnTarget(message) || message.parameters.size != 2) {
            return NickServOutcome.WAITING
        }
        val rawBody = message.parameters[1]
        val body = if (rawBody.any { it < ' ' }) IrcFormatting.plainText(IrcFormatting.parse(rawBody)).trim() else rawBody.trim()
        if ('?' in body) return NickServOutcome.WAITING
        var languageBody: StringBuilder? = null
        var validatedAccountReferenceEnd = -1
        for (reference in ACCOUNT_REFERENCE.findAll(body)) {
            val quotedAccount = reference.groups[1]?.value
            val namedAccount = quotedAccount ?: reference.groups[2]?.value?.trimEnd('.', '!', ':')
            if (namedAccount == null || !matchesAccount(namedAccount)) return NickServOutcome.WAITING
            validatedAccountReferenceEnd = reference.range.last
            val accountGroup = reference.groups[1] ?: reference.groups[2] ?: return NickServOutcome.WAITING
            val language = languageBody ?: StringBuilder(body).also { languageBody = it }
            for (index in accountGroup.range) language.setCharAt(index, ' ')
        }
        val danglingReference = DANGLING_ACCOUNT_REFERENCE.find(body)
        if (danglingReference != null && danglingReference.range.last > validatedAccountReferenceEnd) return NickServOutcome.WAITING
        if (REJECTION.containsMatchIn(body)) return NickServOutcome.REJECTED
        val language = languageBody?.toString() ?: body
        if (NEGATIVE.containsMatchIn(language) || NON_CONFIRMATION.containsMatchIn(language)) return NickServOutcome.WAITING
        return if (CONFIRMATION.containsMatchIn(body)) NickServOutcome.IDENTIFIED else NickServOutcome.WAITING
    }

    private fun isOwnTarget(message: IrcMessage): Boolean = sameName(message.parameters.firstOrNull(), ourNick)

    private fun isOwnUser(prefix: IrcPrefix?): Boolean =
        prefix?.user != null && prefix.host != null && sameName(prefix.nick, ourNick)

    private fun matchesAccount(value: String): Boolean =
        value.isNotEmpty() && value != "*" && value.none(Char::isWhitespace) && (account == null || sameName(value, account))

    private fun sameName(actual: String?, expected: String): Boolean =
        actual != null && actual.isNotEmpty() && CaseMapping.ASCII.equal(actual, expected)

    private fun isHistorical(message: IrcMessage): Boolean =
        "batch" in message.tags || "draft/chathistory-context" in message.tags

    private companion object {
        val CONFIRMATION = Regex(
            """^(?:(?:you are |you['’]re )(?:now|already) (?:identified|logged in\b)|now identified|(?:you are )?now recognized|password accepted|(?:you (?:have|are) )?successfully identified)(?=\s*(?:$|[.!:;,\-–—()]|(?:for|as|to|and)\b))""",
            RegexOption.IGNORE_CASE,
        )
        val NEGATIVE = Regex(
            """\b(?:not|never|unidentified|unrecognized|unrecognised|unsuccessful(?:ly)?|cannot|unable|failed|failure|incorrect|invalid|wrong|denied|rejected|no longer)\b|\b\w+n['’]t\b""",
            RegexOption.IGNORE_CASE,
        )
        val NON_CONFIRMATION = Regex(
            """\b(?:if|unless|until|when|will|would|should|could|may|might|must)\b""",
            RegexOption.IGNORE_CASE,
        )
        val REJECTION = Regex(
            """^(?:error:\s*)?(?:(?:your|the|this)\s+)?(?:(?:incorrect|invalid|wrong|bad)\s+(?:password|credentials)|(?:password|credentials)(?:\s+is|\s+are)?\s+(?:incorrect|invalid|wrong|rejected|not accepted)|password verification failed|(?:authentication|identification|identify|login|log in)\s+(?:has\s+)?(?:failed|failure|denied|rejected|incorrect)|(?:access|permission)\s+denied|(?:cannot|unable to|could not|failed to)\s+(?:identify|authenticate|log in)|(?:account|nickname|nick)\s+(?:is\s+|has been\s+)?(?:not registered|does not exist|suspended|disabled|locked)|too many\s+(?:failed\s+)?(?:login|authentication|identification)\s+attempts)\b""",
            RegexOption.IGNORE_CASE,
        )
        val ACCOUNT_REFERENCE = Regex(
            """\b(?:(?:identified|recognized|password accepted|logged in)\s+(?:for|as|to)\s+(?:(?:the\s+)?account\s*[:=]?\s+)?|(?:for|to)\s+account\s*[:=]?\s+|account\s*[:=]\s*)(?:["'‘’“”]([^"'‘’“”]+)["'‘’“”]|([^\s,;]+))""",
            RegexOption.IGNORE_CASE,
        )
        val DANGLING_ACCOUNT_REFERENCE = Regex(
            """\b(?:identified|recognized|password accepted|logged in)\s+(?:for|as|to)(?:\s+(?:the\s+)?account\s*[:=]?)?\s*[.!]?\s*$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
