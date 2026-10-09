package dev.brentdevs.yardhal.ui.screens

import dev.brentdevs.yardhal.coordinator.BouncerOperationOutcome
import dev.brentdevs.yardhal.coordinator.BouncerOperationStatus
import dev.brentdevs.yardhal.core.data.BouncerNetworkDraft
import dev.brentdevs.yardhal.core.data.ZncChannelDraft
import dev.brentdevs.yardhal.core.data.ZncNetworkDraft
import dev.brentdevs.yardhal.core.data.ZncNetworkVariable
import dev.brentdevs.yardhal.core.data.ZncServerDraft
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class BouncerEditorDraftTests {
    private val success = BouncerOperationOutcome(BouncerOperationStatus.SUCCESS, applied = 1, total = 1)

    @Test
    fun acknowledgedSojuApplyClearsSubmittedSecretButKeepsConcurrentNicknameEdit() {
        val baseline = BouncerNetworkDraft(addr = "ircs://host", name = "old")
        val editor = BouncerEditorDraft(baseline, ::rebaseSojuDraft)
        val submitted = baseline.copy(name = "new", password = "replacement", passwordChanged = true)
        editor.draft = submitted.copy(nick = "typed-while-waiting")
        val refreshed = baseline.copy(name = "new")
        editor.acknowledge(submitted, refreshed, success)
        assertSame(refreshed, editor.baseline)
        assertEquals("new", editor.draft.name)
        assertEquals("typed-while-waiting", editor.draft.nick)
        assertEquals("", editor.draft.password)
        assertFalse(editor.draft.passwordChanged)
    }

    @Test
    fun acknowledgedSojuApplyDoesNotEraseAConcurrentPasswordReplacement() {
        val baseline = BouncerNetworkDraft(addr = "ircs://host")
        val editor = BouncerEditorDraft(baseline, ::rebaseSojuDraft)
        val submitted = baseline.copy(password = "first", passwordChanged = true)
        editor.draft = submitted.copy(password = "second")
        editor.acknowledge(submitted, baseline, success)
        assertEquals("second", editor.draft.password)
        assertTrue(editor.draft.passwordChanged)
    }

    @Test
    fun failedAndPartialOutcomesKeepDraftButUseRefetchedBaselineForRetry() {
        val initial = ZncNetworkDraft("network", servers = listOf(ZncServerDraft("old")))
        val submitted = initial.copy(servers = listOf(ZncServerDraft("new", password = "secret", passwordChanged = true)))
        for (status in listOf(BouncerOperationStatus.ERROR, BouncerOperationStatus.PARTIAL)) {
            val editor = BouncerEditorDraft(initial, ::rebaseZncNetworkDraft)
            editor.draft = submitted
            val refreshed = initial.copy(servers = listOf(ZncServerDraft("new")))
            editor.acknowledge(submitted, refreshed, BouncerOperationOutcome(status, applied = 1, total = 2))
            assertSame(refreshed, editor.baseline)
            assertEquals(submitted, editor.draft)
        }
    }

    @Test
    fun failedRefreshDoesNotPromoteAnUnverifiedBaseline() {
        val initial = ZncChannelDraft("#channel", bufferSize = 50)
        val editor = BouncerEditorDraft(initial, ::rebaseZncChannelDraft)
        val submitted = initial.copy(bufferSize = 100)
        editor.draft = submitted
        editor.acknowledge(submitted, initial.copy(bufferSize = 200), BouncerOperationOutcome(
            BouncerOperationStatus.PARTIAL, applied = 1, total = 1, refreshError = "Refresh failed",
        ))
        assertSame(initial, editor.baseline)
        assertEquals(submitted, editor.draft)
    }

    @Test
    fun successfulZncApplyRestoresMaskedServerStateWithoutErasingConcurrentSettings() {
        val initial = ZncNetworkDraft("network", settings = mapOf(ZncNetworkVariable.Nick to "old"), servers = listOf(ZncServerDraft("host")))
        val submitted = initial.copy(servers = listOf(ZncServerDraft("host", password = "replacement", passwordChanged = true)))
        val editor = BouncerEditorDraft(initial, ::rebaseZncNetworkDraft)
        editor.draft = submitted.copy(settings = mapOf(ZncNetworkVariable.Nick to "typed"))
        editor.acknowledge(submitted, initial, success)
        assertEquals("typed", editor.draft.settings[ZncNetworkVariable.Nick])
        assertEquals(initial.servers, editor.draft.servers)
        assertFalse(editor.draft.servers.single().passwordChanged)
    }

    @Test
    fun successfulApplyClearsUnchangedServerSecretWhileKeepingAnotherServerEdit() {
        val initial = ZncNetworkDraft("network", servers = listOf(ZncServerDraft("first"), ZncServerDraft("second")))
        val submitted = initial.copy(servers = listOf(initial.servers[0].copy(password = "replacement", passwordChanged = true), initial.servers[1]))
        val editor = BouncerEditorDraft(initial, ::rebaseZncNetworkDraft)
        editor.draft = submitted.copy(servers = listOf(submitted.servers[0], submitted.servers[1].copy(host = "third")))
        editor.acknowledge(submitted, initial, success)
        assertEquals(initial.servers[0], editor.draft.servers[0])
        assertEquals("third", editor.draft.servers[1].host)
    }

    @Test
    fun portTypingRetainsRawTextAcrossHostChangesAndIncompleteValues() {
        var input = ZncServerEditorDraft(ZncServerDraft("host"))
        input = input.withPort("")
        assertEquals("", input.port)
        assertEquals(0, input.server.port)
        input = input.copy(server = input.server.copy(host = "other"))
        assertEquals("", input.port)
        input = input.withPort("006697")
        assertEquals("006697", input.port)
        assertEquals(6697, input.server.port)
        input = input.copy(server = input.server.copy(tls = false))
        assertEquals("006697", input.port)
        input = input.withPort("bad")
        assertEquals("bad", input.port)
        assertEquals(0, input.server.port)
        input = input.withPort("7000")
        assertEquals("7000", input.port)
        assertEquals(7000, input.server.port)
    }

    @Test
    fun equalRefreshStillReplacesReferenceUsedForZncMutationSafety() {
        val initial = ZncNetworkDraft("network", servers = listOf(ZncServerDraft("host")))
        val editor = BouncerEditorDraft(initial, ::rebaseZncNetworkDraft)
        val refreshed = initial.copy()
        assertEquals(initial, refreshed)
        editor.observeBaseline(refreshed)
        assertSame(refreshed, editor.baseline)
        val secondRefresh = refreshed.copy()
        editor.acknowledge(initial, secondRefresh, success)
        assertSame(secondRefresh, editor.baseline)
    }

    @Test
    fun externalRefreshUpdatesHeldBaselineWithoutWipingTypedValues() {
        val initial = ZncChannelDraft("#channel", bufferSize = 50)
        val editor = BouncerEditorDraft(initial, ::rebaseZncChannelDraft)
        editor.draft = initial.copy(bufferSize = 75)
        val refreshed = initial.copy(detached = true)
        editor.observeBaseline(refreshed)
        assertSame(refreshed, editor.baseline)
        assertEquals(75, editor.draft.bufferSize)
    }
}
