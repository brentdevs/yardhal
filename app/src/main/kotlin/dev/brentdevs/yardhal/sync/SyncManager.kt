package dev.brentdevs.yardhal.sync

import android.content.Context
import android.util.AtomicFile
import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ChatAppearanceStore
import dev.brentdevs.yardhal.core.data.CredentialVault
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.SyncLedger
import dev.brentdevs.yardhal.core.data.SyncRemoteChange
import dev.brentdevs.yardhal.core.data.ThemeLibraryStore
import dev.brentdevs.yardhal.core.data.YardhalDatabase
import java.util.UUID
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public data class SyncUiState(
    val enabled: Boolean = false,
    val endpoint: String? = null,
    val accountId: String? = null,
    val deviceId: String? = null,
    val phase: String = "Off",
    val lastSuccessMs: Long? = null,
    val pending: Int = 0,
    val error: String? = null,
)

public class SyncManager(
    context: Context,
    private val scope: CoroutineScope,
    private val database: YardhalDatabase,
    private val networkStore: NetworkStore,
    channelOrder: ChannelOrderStore,
    muteStore: MuteStore,
    readMarkerStore: ReadMarkerStore,
    themeLibrary: ThemeLibraryStore,
    appearanceStore: ChatAppearanceStore,
    private val coordinator: LiveCoordinator,
    private val vault: CredentialVault,
) {
    private val preferences = context.getSharedPreferences("sync-account", Context.MODE_PRIVATE)
    private val transport = SyncTransport(context)
    private val projection = PortableSyncState(networkStore, channelOrder, muteStore, readMarkerStore,
        themeLibrary, appearanceStore, coordinator)
    private val identityFile = AtomicFile(File(context.noBackupFilesDir, "sync-device-id"))
    private val installationId: String by lazy(::loadDeviceId)
    private var identityUnavailable = false
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    @Volatile private var account: Account? = loadAccount()
    private val mutableState = MutableStateFlow(account?.let { stored ->
        SyncUiState(enabled = true, endpoint = stored.endpoint, accountId = stored.id, deviceId = stored.device,
            phase = "Waiting to sync", lastSuccessMs = preferences.getLong("lastSuccess", -1).takeIf { it >= 0 })
    } ?: SyncUiState(error = if (preferences.contains("account")) "Re-enroll sync on this installation" else null))
    public val state: StateFlow<SyncUiState> = mutableState.asStateFlow()
    private val orphanCleanup: Deferred<Unit> = scope.async {
        if (!identityUnavailable) SyncLedger.purgeInactiveAccounts(database, account?.id)
    }

    init {
        if (account != null) start()
    }

    public fun activeLedger(): SyncLedger? = account?.let { SyncLedger(database, it.id) }

    public suspend fun enable(endpoint: String, accountId: String, enrollmentSecret: String): Boolean = mutex.withLock {
        orphanCleanup.await()
        if (account != null) return@withLock false
        val safeEndpoint = try { transport.validateEndpoint(endpoint) } catch (failure: IllegalArgumentException) {
            fail(failure.message ?: "Invalid server URL")
            return@withLock false
        }
        val id = runCatching { UUID.fromString(accountId.trim()).toString() }.getOrNull()
        if (id == null || !enrollmentSecret.matches(Regex("[0-9a-fA-F]{64}"))) {
            fail("Enter the server account UUID and its 64-character enrollment secret")
            return@withLock false
        }
        val device = try { installationId } catch (_: Exception) {
            fail("Device identity storage is unavailable; local IRC still works")
            return@withLock false
        }
        if (identityUnavailable) {
            SyncLedger.purgeInactiveAccounts(database, id)
            identityUnavailable = false
        }
        val enrollment = try { transport.enroll(safeEndpoint, id, enrollmentSecret, device) } catch (failure: SyncTransportFailure) {
            fail(failure.message ?: "Account enrollment failed")
            return@withLock false
        }
        try {
            vault.storePassword(tokenRef(device), enrollment.token)
            if (!preferences.edit().putString("endpoint", safeEndpoint).putString("account", id)
                    .putString("device", device).putLong("lastSuccess", -1).commit()) {
                vault.deletePassword(tokenRef(device))
                fail("Could not save the sync account on this device")
                return@withLock false
            }
        } catch (_: Exception) {
            fail("Could not securely save the sync credential on this device")
            return@withLock false
        }
        account = Account(safeEndpoint, id, device)
        mutableState.value = SyncUiState(true, safeEndpoint, id, device, "Connecting")
        start()
        true
    }

    public fun retry() {
        if (account != null) {
            mutableState.value = mutableState.value.copy(phase = "Retrying", error = null)
            wake.trySend(Unit)
        }
    }

    public suspend fun disconnect(revokeDevice: Boolean): Boolean = mutex.withLock {
        val active = account ?: return@withLock true
        job?.cancel()
        job = null
        if (revokeDevice) {
            val token = vault.readPassword(tokenRef(active.device))
            if (token == null) {
                fail("Missing device credential; reconnect this account before revocation")
                start()
                return@withLock false
            }
            try { transport.revokeDevice(active.endpoint, token) } catch (failure: SyncTransportFailure) {
                if (failure.code !in setOf(401, 410)) {
                    fail(failure.message ?: "Could not revoke this device; retry while online")
                    start()
                    return@withLock false
                }
            }
        }
        clearAccount(active)
    }

    public suspend fun deleteAccount(secret: String): Boolean = mutex.withLock {
        val active = account ?: return@withLock false
        if (!secret.matches(Regex("[0-9a-fA-F]{64}"))) {
            fail("Enter the account enrollment secret to delete the cloud account")
            return@withLock false
        }
        job?.cancel()
        job = null
        val token = vault.readPassword(tokenRef(active.device))
        if (token == null) {
            fail("Missing device credential; reconnect this account before deletion")
            start()
            return@withLock false
        }
        try { transport.deleteAccount(active.endpoint, token, secret) } catch (failure: SyncTransportFailure) {
            fail(if (failure.code == 401) "This device is no longer authorized; cloud account deletion was not confirmed"
                else failure.message ?: "Cloud account deletion failed; local data is unchanged")
            start()
            return@withLock false
        }
        clearAccount(active)
    }

    private suspend fun clearAccount(active: Account): Boolean {
        if (!preferences.edit().remove("endpoint").remove("account").remove("lastSuccess").commit()) {
            fail("Server access ended, but local account settings could not be cleared; retry device revocation to finish cleanup")
            return false
        }
        account = null
        val vaultFailure = try {
            vault.deletePassword(tokenRef(active.device))
            false
        } catch (_: Exception) { true }
        val ledgerFailure = try {
            SyncLedger(database, active.id).purgeAccount()
            false
        } catch (_: Exception) { true }
        mutableState.value = SyncUiState(error = when {
            vaultFailure -> "Server access ended, but a revoked credential remains on this device"
            ledgerFailure -> "Server access ended, but local sync bookkeeping could not be removed; restart to retry"
            else -> null
        })
        return true
    }

    private fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var failures = 0
            while (isActive && account != null) {
                try {
                    mutex.withLock {
                        orphanCleanup.await()
                        account?.let { sync(it) }
                    }
                    failures = 0
                    withTimeoutOrNull(30_000L) { wake.receive() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: SyncTransportFailure) {
                    fail(failure.message ?: "Sync server unavailable")
                    failures += 1
                    val waitMs = if (failure.retryable) (5_000L shl failures.coerceAtMost(6)).coerceAtMost(300_000L) else 300_000L
                    withTimeoutOrNull(waitMs) { wake.receive() }
                } catch (_: Exception) {
                    fail("Sync could not apply local or remote changes. Check storage and retry.")
                    withTimeoutOrNull(60_000L) { wake.receive() }
                }
            }
        }
    }

    private suspend fun sync(active: Account) {
        coordinator.restorationReady.first { it }
        val ledger = SyncLedger(database, active.id)
        val token = vault.readPassword(tokenRef(active.device))
            ?: throw SyncTransportFailure(401, false, "The device sync credential is missing; reconnect this device")
        mutableState.value = mutableState.value.copy(phase = "Synchronizing", error = null)
        val refresh = ledger.syncPosition()
        if (refresh.refreshRequired && refresh.cursor != 0L) ledger.beginSnapshotRefresh()
        val bootstrap = ledger.syncPosition().epoch == null
        if (!bootstrap) {
            reconcile(ledger)
            materializePending(ledger)
        }
        val beforeFetch = projection.snapshot()
        if (!bootstrap) for ((kind, values) in beforeFetch) ledger.reconcileSnapshot(kind, values)
        ledger.clearBaselines()
        for ((kind, values) in beforeFetch) ledger.captureBaseline(kind, values)
        mutableState.value = mutableState.value.copy(pending = ledger.pendingCount() + ledger.failures().size)
        while (true) {
            val position = ledger.syncPosition()
            val page = try {
                transport.sync(active.endpoint, token, position.cursor, position.epoch, emptyList())
            } catch (failure: SyncTransportFailure) {
                if (failure.code != 409 || failure.serverEpoch == null) throw failure
                ledger.resetEpoch(failure.serverEpoch)
                throw SyncTransportFailure(409, true, "Sync server changed generation; refetching without losing local edits")
            }
            applyPage(ledger, page)
            if (!page.hasMore) break
        }
        reconcile(ledger)
        materializePending(ledger)
        reconcile(ledger)
        if (ledger.syncPosition().refreshRequired) ledger.completeSnapshotRefresh()
        ledger.reconcileInteractionSnapshot(networkStore.all().mapTo(mutableSetOf()) { it.id })
        var pages = 0
        while (pages++ < 20) {
            val pending = ledger.pending(6)
            if (pending.isEmpty()) break
            val position = ledger.syncPosition()
            val outgoing = pending.map { change -> JsonObject(mapOf(
                "id" to JsonPrimitive(change.id), "kind" to JsonPrimitive(change.kind),
                "key" to JsonPrimitive(change.key), "value" to (change.value ?: JsonNull),
                "base" to JsonPrimitive(change.base),
            )) }
            val page = try {
                transport.sync(active.endpoint, token, position.cursor, position.epoch, outgoing)
            } catch (failure: SyncTransportFailure) {
                if (failure.code == 409 && failure.serverEpoch != null) {
                    ledger.resetEpoch(failure.serverEpoch)
                    throw SyncTransportFailure(409, true, "Sync server reset; retrying remote bootstrap")
                }
                throw failure
            }
            ledger.acknowledge(page.accepted, page.rejected.toMap())
            applyPage(ledger, page)
            reconcile(ledger)
            materializePending(ledger)
            reconcile(ledger)
            if (ledger.syncPosition().refreshRequired) {
                wake.trySend(Unit)
                break
            }
            if (page.hasMore || page.rejected.isNotEmpty()) break
        }
        ledger.clearBaselines()
        val failures = ledger.failures()
        val outstanding = ledger.pendingCount() + failures.size
        val now = System.currentTimeMillis()
        preferences.edit().putLong("lastSuccess", now).apply()
        mutableState.value = mutableState.value.copy(phase = when {
                failures.isNotEmpty() -> "Action required"
                outstanding > 0 -> "Pending changes"
                else -> "Up to date"
            }, lastSuccessMs = now, pending = outstanding,
            error = failures.values.firstOrNull()?.let { "Sync server refused a change: $it" })
    }

    private suspend fun reconcile(ledger: SyncLedger) {
        for ((kind, values) in projection.snapshot()) ledger.reconcileSnapshot(kind, values)
    }

    private suspend fun applyPage(ledger: SyncLedger, page: SyncBatch) {
        ledger.applyRemote(page.changes.map { SyncRemoteChange(it.seq, it.kind, it.key, it.value, it.origin) },
            page.epoch, page.cursor)
    }

    private suspend fun materializePending(ledger: SyncLedger) {
        val incoming = ledger.pendingMaterializations()
        projection.stage(incoming)
        for (change in incoming) {
            projection.materialize(change)
            ledger.confirmMaterialized(change.kind, change.key, change.seq)
        }
        if (incoming.isNotEmpty()) coordinator.refreshSyncedMetadata()
    }

    private fun loadAccount(): Account? {
        val endpoint = preferences.getString("endpoint", null) ?: return null
        val id = preferences.getString("account", null) ?: return null
        val device = preferences.getString("device", null) ?: return null
        val current = try { installationId } catch (_: Exception) {
            identityUnavailable = true
            return null
        }
        return if (current == device) Account(endpoint, id, device) else null
    }

    private fun loadDeviceId(): String {
        val saved = try {
            identityFile.openRead().use { it.readNBytes(37).toString(Charsets.UTF_8) }
        } catch (_: FileNotFoundException) { null }
        if (saved != null && runCatching { UUID.fromString(saved).toString() == saved }.getOrDefault(false)) return saved
        val created = UUID.randomUUID().toString()
        val output = identityFile.startWrite()
        try {
            output.write(created.toByteArray(Charsets.UTF_8))
            identityFile.finishWrite(output)
        } catch (failure: IOException) {
            identityFile.failWrite(output)
            throw failure
        }
        return created
    }

    private fun tokenRef(device: String): String = "yardhal-sync-device:$device"

    private fun fail(message: String) {
        mutableState.value = mutableState.value.copy(phase = "Unavailable", error = message)
    }

    private data class Account(val endpoint: String, val id: String, val device: String)
}
