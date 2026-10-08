package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.protocol.IrcMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NickServIdentificationGateTests {
    private fun gate(account: String? = "our-account"): NickServIdentificationGate =
        NickServIdentificationGate(ourNick = "OurNick", account = account)

    private fun NickServIdentificationGate.feed(line: String): NickServOutcome =
        receive(checkNotNull(IrcMessage.parse(line)))

    @Test
    fun defaultsWaitForSevenSecondsAndExpireAtTheDeadline() {
        val gate = NickServIdentificationGate(ourNick = "OurNick", nowMillis = 1234)
        assertEquals(8234L, gate.deadlineMs)
        assertEquals(NickServOutcome.WAITING, gate.outcome)
        assertEquals(NickServOutcome.WAITING, gate.expire(8233))
        assertEquals(NickServOutcome.TIMED_OUT, gate.expire(8234))
    }

    @Test
    fun supportedServicePhrasesConfirmIdentification() {
        for (body in listOf(
            "You are now identified.",
            "You are already identified.",
            "You're now identified.",
            "You're already identified.",
            "Now identified for our-account.",
            "Now recognized.",
            "You are now recognized.",
            "Password accepted.",
            "Successfully identified.",
            "You have successfully identified.",
            "Password accepted - you are now recognized.",
        )) {
            assertEquals(NickServOutcome.IDENTIFIED, gate().feed(":NickServ!service@services NOTICE OurNick :$body"), body)
        }
    }

    @Test
    fun loggedInServiceNoticeConfirmsOnlyTheRequestedIdentity() {
        assertEquals(
            NickServOutcome.IDENTIFIED,
            gate().feed(":NickServ!NickServ@localhost NOTICE OurNick :You're now logged in as our-account"),
        )
        for (body in listOf(
            "You're now logged in as someone-else",
            "You're now logged in as *",
            "You're now logged in as",
            "You're already logged into an account",
        )) {
            assertEquals(NickServOutcome.WAITING, gate().feed(":NickServ!NickServ@localhost NOTICE OurNick :$body"), body)
        }
    }

    @Test
    fun serviceInitiatedOwnNickChangeRetargetsConfirmationWithoutExtendingDeadline() {
        val gate = gate()
        val deadline = gate.deadlineMs
        assertEquals(NickServOutcome.WAITING, gate.feed(":OurNick!u@h NICK our-account"))
        assertEquals(NickServOutcome.WAITING,
            gate.feed(":NickServ!NickServ@localhost NOTICE OurNick :You're now logged in as our-account"))
        assertEquals(deadline, gate.deadlineMs)
        assertEquals(NickServOutcome.IDENTIFIED,
            gate.feed(":NickServ!NickServ@localhost NOTICE our-account :You're now logged in as our-account"))
    }

    @Test
    fun foreignNickChangeCannotRedirectOurIdentificationConfirmation() {
        val gate = gate()
        assertEquals(NickServOutcome.WAITING, gate.feed(":OtherNick!u@h NICK our-account"))
        assertEquals(NickServOutcome.WAITING,
            gate.feed(":NickServ!NickServ@localhost NOTICE our-account :You're now logged in as our-account"))
        assertEquals(NickServOutcome.IDENTIFIED,
            gate.feed(":NickServ!NickServ@localhost NOTICE OurNick :You're now logged in as our-account"))
    }

    @Test
    fun caseInsensitiveServiceNickAndAccountMatchingIsAsciiOnly() {
        val gate = NickServIdentificationGate(service = "Auth[Bot]", ourNick = "Nick[One]", account = "Account[One]")
        assertEquals(NickServOutcome.WAITING, gate.feed(":Auth{Bot}!s@h NOTICE Nick[One] :You are now identified."))
        assertEquals(NickServOutcome.WAITING, gate.feed(":Auth[Bot]!s@h NOTICE Nick{One} :You are now identified."))
        assertEquals(NickServOutcome.WAITING, gate.feed(":Nick[One]!u@h ACCOUNT Account{One}"))
        assertEquals(NickServOutcome.IDENTIFIED, gate.feed(":auth[bot]!s@h notice nick[one] :You are now identified as ACCOUNT[ONE]."))
    }

    @Test
    fun ircFormattingDoesNotHideAServiceConfirmationOrItsAccount() {
        assertEquals(
            NickServOutcome.IDENTIFIED,
            gate().feed(":NickServ!service@services NOTICE OurNick :\u0002You are now identified\u000f for \u000304our-account\u000f."),
        )
    }

    @Test
    fun configuredServiceRequiresAnExactSenderIncludingBareServicePrefixes() {
        val gate = NickServIdentificationGate(service = "AccountServ", ourNick = "OurNick", account = "our-account")
        assertEquals(NickServOutcome.WAITING, gate.feed(":NickServ!service@services NOTICE OurNick :Password accepted."))
        assertEquals(NickServOutcome.WAITING, gate.feed(":AccountServFake!service@services NOTICE OurNick :Password accepted."))
        assertEquals(NickServOutcome.IDENTIFIED, gate.feed(":AccountServ NOTICE OurNick :Password accepted."))
    }

    @Test
    fun broadServiceLookingSendersAndMissingPrefixesCannotConfirm() {
        for (prefix in listOf("", ":alice!a@services.example ", ":NickServFake!s@h ", ":services ", ":services.example ")) {
            assertEquals(NickServOutcome.WAITING, gate().feed("${prefix}NOTICE OurNick :You are now identified."), prefix)
        }
    }

    @Test
    fun serviceNoticesMustTargetOnlyOurNickAndHaveExactlyTwoParameters() {
        for (parameters in listOf(
            "SomeoneElse :You are now identified.",
            "#channel :You are now identified.",
            "* :You are now identified.",
            "OurNick,SomeoneElse :You are now identified.",
            ":You are now identified.",
            "OurNick extra :You are now identified.",
        )) {
            assertEquals(NickServOutcome.WAITING, gate().feed(":NickServ!service@services NOTICE $parameters"), parameters)
        }
    }

    @Test
    fun explicitlyNamedForeignServiceAccountsCannotConfirm() {
        for (body in listOf(
            "You are now identified for someone-else.",
            "You're already identified as someone-else.",
            "You are now identified to account someone-else.",
            "You are now recognized as 'someone-else'.",
            "Password accepted for account someone-else.",
            "Password accepted - you are now recognized as someone-else.",
            "You are now identified for *.",
            "You are now identified for.",
            "You are now identified for account.",
        )) {
            assertEquals(NickServOutcome.WAITING, gate().feed(":NickServ!service@services NOTICE OurNick :$body"), body)
        }
    }

    @Test
    fun matchingAccountReferencesMayBeQuotedOrServiceFormatted() {
        for (body in listOf(
            "You are now identified for our-account.",
            "You are already identified to account OUR-ACCOUNT.",
            "You're now identified as 'our-account'.",
            "You are now recognized as \"our-account\".",
            "Successfully identified to the account our-account.",
            "Password accepted for account: our-account.",
        )) {
            assertEquals(NickServOutcome.IDENTIFIED, gate().feed(":NickServ!service@services NOTICE OurNick :$body"), body)
        }
    }

    @Test
    fun negativePhrasesNeverBecomePositiveBySubstringMatching() {
        for (body in listOf(
            "You are not identified.",
            "You are not yet successfully identified.",
            "You aren't successfully identified.",
            "You haven't successfully identified.",
            "You are unsuccessfully identified.",
            "You are now unidentified.",
            "You are now identified but no longer recognized.",
            "You are now identified but are not identified for account our-account.",
            "Password accepted does not mean you are identified.",
            "You are now recognized, but authentication failed.",
            "Password accepted, but your credentials are invalid.",
            "You are now identified, but isn't identified is the actual status.",
        )) {
            assertEquals(NickServOutcome.WAITING, gate().feed(":NickServ!service@services NOTICE OurNick :$body"), body)
        }
    }

    @Test
    fun quotedHelpConditionalPromisesAndQuestionsCannotConfirm() {
        for (body in listOf(
            "If successful, you are now identified.",
            "You are now identified if your password is correct.",
            "You are now identified when login finishes.",
            "You will be successfully identified.",
            "You are now identified?",
            "Password accepted messages are only shown after login.",
            "\"You are now identified\" means authentication succeeded.",
            "Syntax: IDENTIFY password. You are now identified is shown on success.",
        )) {
            assertEquals(NickServOutcome.WAITING, gate().feed(":NickServ!service@services NOTICE OurNick :$body"), body)
        }
    }

    @Test
    fun ownAccountNotificationRequiresACompleteUserPrefixAndMatchingSingleAccount() {
        val gate = gate()
        for (line in listOf(
            ":someone!u@h ACCOUNT our-account",
            ":OurNick ACCOUNT our-account",
            ":OurNick!u ACCOUNT our-account",
            ":OurNick@h ACCOUNT our-account",
            "ACCOUNT our-account",
            ":OurNick!u@h ACCOUNT someone-else",
            ":OurNick!u@h ACCOUNT *",
            ":OurNick!u@h ACCOUNT :",
            ":OurNick!u@h ACCOUNT",
            ":OurNick!u@h ACCOUNT our-account extra",
        )) {
            assertEquals(NickServOutcome.WAITING, gate.feed(line), line)
        }
        assertEquals(NickServOutcome.IDENTIFIED, gate.feed(":ournick!u@h account OUR-ACCOUNT"))
    }

    @Test
    fun loggedInNumericRequiresServerSenderOwnTargetOwnUsermaskAndMatchingAccount() {
        val gate = gate()
        for (line in listOf(
            "900 OurNick OurNick!u@h our-account :You are now logged in",
            ":attacker!u@h 900 OurNick OurNick!u@h our-account :You are now logged in",
            ":server 900 SomeoneElse OurNick!u@h our-account :You are now logged in",
            ":server 900 OurNick SomeoneElse!u@h our-account :You are now logged in",
            ":server 900 OurNick OurNick our-account :You are now logged in",
            ":server 900 OurNick OurNick!u our-account :You are now logged in",
            ":server 900 OurNick OurNick@h our-account :You are now logged in",
            ":server 900 OurNick OurNick!u@h someone-else :You are now logged in",
            ":server 900 OurNick OurNick!u@h * :You are now logged in",
            ":server 900 OurNick OurNick!u@h",
        )) {
            assertEquals(NickServOutcome.WAITING, gate.feed(line), line)
        }
        assertEquals(NickServOutcome.IDENTIFIED, gate.feed(":server 900 ournick OURNICK!u@h OUR-ACCOUNT :You are now logged in"))
    }

    @Test
    fun unspecifiedAccountAcceptsOnlyValidAccountsBoundToOurIdentity() {
        assertEquals(NickServOutcome.IDENTIFIED, gate(null).feed(":OurNick!u@h ACCOUNT a-different-account"))
        assertEquals(NickServOutcome.IDENTIFIED, gate(null).feed(":server 900 OurNick OurNick!u@h a-different-account :Logged in"))
        assertEquals(NickServOutcome.WAITING, gate(null).feed(":OurNick!u@h ACCOUNT :two accounts"))
        assertEquals(NickServOutcome.WAITING, gate(null).feed(":someone!u@h ACCOUNT a-different-account"))
        assertEquals(NickServOutcome.WAITING, gate(null).feed(":NickServ!s@h NOTICE OurNick :You are now identified as *."))
    }

    @Test
    fun explicitServiceAuthenticationRejectionsAreTerminal() {
        for (body in listOf(
            "Invalid password for account our-account.",
            "Incorrect password.",
            "Wrong credentials.",
            "Password is incorrect.",
            "Your password is not accepted.",
            "Password verification failed.",
            "Authentication failed.",
            "Identification has failed.",
            "Login incorrect.",
            "Error: Access denied.",
            "Permission denied.",
            "Cannot identify.",
            "Unable to authenticate.",
            "Could not identify.",
            "Failed to log in.",
            "This nickname is not registered.",
            "Your account has been suspended.",
            "Too many failed login attempts.",
        )) {
            val gate = gate()
            assertEquals(NickServOutcome.REJECTED, gate.feed(":NickServ!service@services NOTICE OurNick :$body"), body)
            assertEquals(NickServOutcome.REJECTED, gate.feed(":NickServ!service@services NOTICE OurNick :Password accepted."), body)
        }
    }

    @Test
    fun foreignRejectionNoticesCannotTerminateOurAuthentication() {
        val gate = gate()
        for (line in listOf(
            ":attacker!u@h NOTICE OurNick :Invalid password.",
            ":NickServ!s@h NOTICE SomeoneElse :Invalid password.",
            ":NickServ!s@h NOTICE OurNick :Invalid password for account someone-else.",
            ":NickServ!s@h NOTICE OurNick :If authentication failed, try again.",
        )) {
            assertEquals(NickServOutcome.WAITING, gate.feed(line), line)
        }
        assertEquals(NickServOutcome.IDENTIFIED, gate.feed(":NickServ!s@h NOTICE OurNick :Password accepted."))
    }

    @Test
    fun explicitAuthenticationFailureNumericsRequireServerSenderAndOwnTarget() {
        for (numeric in listOf(464, 902, 904, 905, 906)) {
            val gate = gate()
            assertEquals(NickServOutcome.WAITING, gate.feed(":server $numeric SomeoneElse :Authentication failed"))
            assertEquals(NickServOutcome.WAITING, gate.feed(":attacker!u@h $numeric OurNick :Authentication failed"))
            assertEquals(NickServOutcome.REJECTED, gate.feed(":server $numeric OurNick :Authentication failed"))
        }
    }

    @Test
    fun serviceUnavailableAndCommandRejectionsRequireMatchingContext() {
        val gate = gate()
        for (line in listOf(
            ":server 401 OurNick SomeoneElse :No such nick",
            ":server 400 OurNick PRIVMSG :Generic failure",
            ":server 451 OurNick :You have not registered",
            ":server 461 OurNick JOIN :Not enough parameters",
            ":attacker!u@h 401 OurNick NickServ :No such nick",
        )) {
            assertEquals(NickServOutcome.WAITING, gate.feed(line), line)
        }
        assertEquals(NickServOutcome.REJECTED, gate.feed(":server 401 OurNick nickserv :No such nick"))
        for (numeric in listOf(400, 451, 461)) {
            assertEquals(NickServOutcome.REJECTED, this.gate().feed(":server $numeric OurNick IDENTIFY :Identification command rejected"))
        }
    }

    @Test
    fun saslSuccessAlreadyAuthenticatedAndLogoutAreNotNickServConfirmationOrRejection() {
        val gate = gate()
        for (line in listOf(
            ":server 903 OurNick :SASL authentication successful",
            ":server 907 OurNick :You have already authenticated",
            ":server 908 OurNick PLAIN :Available SASL mechanisms",
            ":server 901 OurNick OurNick!u@h our-account :You are now logged out",
            ":OurNick!u@h ACCOUNT *",
            ":NickServ!s@h PRIVMSG OurNick :Password accepted.",
        )) {
            assertEquals(NickServOutcome.WAITING, gate.feed(line), line)
        }
    }

    @Test
    fun batchTaggedAndHistoryContextRepliesCannotAffectAnActiveGate() {
        val gate = gate()
        for (line in listOf(
            "@batch=history :NickServ!s@h NOTICE OurNick :Password accepted.",
            "@batch=wrapper :OurNick!u@h ACCOUNT our-account",
            "@batch=history :server 900 OurNick OurNick!u@h our-account :Logged in",
            "@draft/chathistory-context=#room :NickServ!s@h NOTICE OurNick :Password accepted.",
            "@batch=history :NickServ!s@h NOTICE OurNick :Invalid password.",
            "@batch=history :server 904 OurNick :Authentication failed",
        )) {
            assertEquals(NickServOutcome.WAITING, gate.feed(line), line)
        }
        assertEquals(NickServOutcome.IDENTIFIED, gate.feed(":NickServ!s@h NOTICE OurNick :Password accepted."))
    }

    @Test
    fun ordinaryServerTimeAndAccountTagsDoNotTurnLiveRepliesIntoHistory() {
        assertEquals(
            NickServOutcome.IDENTIFIED,
            gate().feed("@time=2026-10-08T12:00:00Z;account=services :NickServ!s@h NOTICE OurNick :Password accepted."),
        )
    }

    @Test
    fun repeatedAndLateRepliesCannotReopenAnyTerminalOutcome() {
        val identified = gate()
        assertEquals(NickServOutcome.IDENTIFIED, identified.feed(":NickServ!s@h NOTICE OurNick :Password accepted."))
        assertEquals(NickServOutcome.IDENTIFIED, identified.feed(":NickServ!s@h NOTICE OurNick :Password accepted."))
        assertEquals(NickServOutcome.IDENTIFIED, identified.feed(":NickServ!s@h NOTICE OurNick :Invalid password."))
        assertEquals(NickServOutcome.IDENTIFIED, identified.expire(Long.MAX_VALUE))

        val rejected = gate()
        assertEquals(NickServOutcome.REJECTED, rejected.feed(":NickServ!s@h NOTICE OurNick :Invalid password."))
        assertEquals(NickServOutcome.REJECTED, rejected.feed(":OurNick!u@h ACCOUNT our-account"))
        assertEquals(NickServOutcome.REJECTED, rejected.expire(Long.MAX_VALUE))

        val timedOut = gate()
        assertEquals(NickServOutcome.TIMED_OUT, timedOut.expire(7000))
        assertEquals(NickServOutcome.TIMED_OUT, timedOut.feed(":server 900 OurNick OurNick!u@h our-account :Logged in"))
        assertEquals(NickServOutcome.TIMED_OUT, timedOut.feed(":NickServ!s@h NOTICE OurNick :Invalid password."))
        assertEquals(NickServOutcome.TIMED_OUT, timedOut.expire(0))
    }

    @Test
    fun customZeroAndOverflowingDeadlinesNeverWrapIntoAnEarlyTimeout() {
        val custom = NickServIdentificationGate(ourNick = "OurNick", timeoutMillis = 25, nowMillis = 100)
        assertEquals(125L, custom.deadlineMs)
        assertEquals(NickServOutcome.WAITING, custom.expire(124))
        assertEquals(NickServOutcome.TIMED_OUT, custom.expire(125))

        val immediate = NickServIdentificationGate(ourNick = "OurNick", timeoutMillis = 0, nowMillis = 100)
        assertEquals(NickServOutcome.TIMED_OUT, immediate.expire(100))

        val overflow = NickServIdentificationGate(ourNick = "OurNick", nowMillis = Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE, overflow.deadlineMs)
        assertEquals(NickServOutcome.WAITING, overflow.expire(Long.MAX_VALUE - 1))
        assertEquals(NickServOutcome.TIMED_OUT, overflow.expire(Long.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> { NickServIdentificationGate(ourNick = "OurNick", timeoutMillis = -1) }
    }
}
