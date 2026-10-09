package dev.brentdevs.yardhal.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import dev.brentdevs.yardhal.coordinator.BouncerOperationOutcome
import dev.brentdevs.yardhal.coordinator.BouncerOperationStatus
import dev.brentdevs.yardhal.coordinator.SojuChannelSettings
import dev.brentdevs.yardhal.core.data.BouncerNetworkDraft
import dev.brentdevs.yardhal.core.data.ZncChannelDraft
import dev.brentdevs.yardhal.core.data.ZncNetworkDraft
import dev.brentdevs.yardhal.core.data.ZncServerDraft

internal class BouncerEditorDraft<T>(initial: T, private val rebase: (T, T, T) -> T) {
    var baseline by mutableStateOf(initial, referentialEqualityPolicy())
        private set
    var draft by mutableStateOf(initial)

    fun observeBaseline(refreshed: T) {
        baseline = refreshed
    }

    fun acknowledge(submitted: T, refreshed: T?, outcome: BouncerOperationOutcome) {
        if (refreshed != null && outcome.refreshError == null) baseline = refreshed
        if (outcome.status == BouncerOperationStatus.SUCCESS) {
            draft = rebase(draft, submitted, refreshed ?: submitted)
        }
    }
}

internal fun <T> retainConcurrentEdit(current: T, submitted: T, refreshed: T): T =
    if (current == submitted) refreshed else current

internal fun rebaseSojuDraft(current: BouncerNetworkDraft, submitted: BouncerNetworkDraft, refreshed: BouncerNetworkDraft): BouncerNetworkDraft =
    current.copy(
        addr = retainConcurrentEdit(current.addr, submitted.addr, refreshed.addr),
        name = retainConcurrentEdit(current.name, submitted.name, refreshed.name),
        nick = retainConcurrentEdit(current.nick, submitted.nick, refreshed.nick),
        username = retainConcurrentEdit(current.username, submitted.username, refreshed.username),
        realname = retainConcurrentEdit(current.realname, submitted.realname, refreshed.realname),
        enabled = retainConcurrentEdit(current.enabled, submitted.enabled, refreshed.enabled),
        password = retainConcurrentEdit(current.password, submitted.password, refreshed.password),
        passwordChanged = if (current.password == submitted.password && current.passwordChanged == submitted.passwordChanged) {
            refreshed.passwordChanged
        } else current.passwordChanged,
    )

internal fun rebaseZncNetworkDraft(current: ZncNetworkDraft, submitted: ZncNetworkDraft, refreshed: ZncNetworkDraft): ZncNetworkDraft =
    current.copy(
        settings = current.settings.mapValues { (variable, value) ->
            if (value == submitted.settings[variable]) refreshed.settings[variable] ?: value else value
        },
        servers = if (current.servers == submitted.servers) refreshed.servers else current.servers.mapIndexed { index, server ->
            if (server == submitted.servers.getOrNull(index)) {
                refreshed.servers.firstOrNull { it.host == server.host && it.port == server.port && it.tls == server.tls } ?: server
            } else server
        },
    )

internal fun rebaseZncChannelDraft(current: ZncChannelDraft, submitted: ZncChannelDraft, refreshed: ZncChannelDraft): ZncChannelDraft =
    current.copy(
        detached = retainConcurrentEdit(current.detached, submitted.detached, refreshed.detached),
        disabled = retainConcurrentEdit(current.disabled, submitted.disabled, refreshed.disabled),
        bufferSize = retainConcurrentEdit(current.bufferSize, submitted.bufferSize, refreshed.bufferSize),
        autoClearChanBuffer = retainConcurrentEdit(current.autoClearChanBuffer, submitted.autoClearChanBuffer, refreshed.autoClearChanBuffer),
    )

internal fun rebaseSojuChannelDraft(current: SojuChannelSettings, submitted: SojuChannelSettings, refreshed: SojuChannelSettings): SojuChannelSettings =
    current.copy(
        status = refreshed.status,
        detached = retainConcurrentEdit(current.detached, submitted.detached, refreshed.detached),
        detachAfterSeconds = retainConcurrentEdit(current.detachAfterSeconds, submitted.detachAfterSeconds, refreshed.detachAfterSeconds),
        relayDetached = retainConcurrentEdit(current.relayDetached, submitted.relayDetached, refreshed.relayDetached),
        reattachOn = retainConcurrentEdit(current.reattachOn, submitted.reattachOn, refreshed.reattachOn),
    )

internal data class ZncServerEditorDraft(val server: ZncServerDraft, val port: String = server.port.toString()) {
    fun withPort(text: String): ZncServerEditorDraft = copy(
        server = server.copy(port = text.toIntOrNull()?.takeIf { it in 1..65535 } ?: 0),
        port = text,
    )
}
