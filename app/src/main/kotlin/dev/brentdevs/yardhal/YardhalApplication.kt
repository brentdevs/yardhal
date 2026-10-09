package dev.brentdevs.yardhal

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.brentdevs.yardhal.coordinator.ConnectionFactory
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.client.Socks5Config
import dev.brentdevs.yardhal.core.data.AndroidTlsIdentityProvider
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ChatAppearanceStore
import dev.brentdevs.yardhal.core.data.CredentialVault
import dev.brentdevs.yardhal.core.data.DatabaseRecovery
import dev.brentdevs.yardhal.core.data.FileStsPolicyStore
import dev.brentdevs.yardhal.core.data.IgnoreStore
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.OfflineStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.StorageRecovery
import dev.brentdevs.yardhal.service.ConnectionService
import dev.brentdevs.yardhal.service.Notifications
import dev.brentdevs.yardhal.service.keepsRecoveryService
import dev.brentdevs.yardhal.ui.image.RemoteImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

enum class ApplicationStartup {
    LOADING,
    READY,
    FAILED,
}

class YardhalApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableStartup = MutableStateFlow(ApplicationStartup.LOADING)
    val startup: StateFlow<ApplicationStartup> = mutableStartup.asStateFlow()

    suspend fun awaitInitialization(): Boolean = startup.first { it != ApplicationStartup.LOADING } == ApplicationStartup.READY

    var sharedText: String? by mutableStateOf(null)

    lateinit var networkStore: NetworkStore
        private set
    lateinit var messageStore: MessageStore
        private set
    lateinit var readMarkerStore: ReadMarkerStore
        private set
    lateinit var muteStore: MuteStore
        private set
    lateinit var vault: CredentialVault
        private set
    lateinit var coordinator: LiveCoordinator
        private set
    lateinit var chatAppearanceStore: ChatAppearanceStore
        private set

    private lateinit var connectivityObserver: AndroidConnectivityObserver

    val remoteImages: RemoteImageLoader by lazy { RemoteImageLoader(cacheDir) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch(Dispatchers.IO) {
            try {
                initializeStoresAndCoordinator()
                mutableStartup.value = ApplicationStartup.READY
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.e("Yardhal", "Local initialization failed", failure)
                mutableStartup.value = ApplicationStartup.FAILED
            }
        }
    }

    private fun initializeStoresAndCoordinator() {
        val dir = filesDir
        networkStore = NetworkStore(dir)
        val db = DatabaseRecovery.open(this)
        messageStore = MessageStore(db.messageDao())
        readMarkerStore = ReadMarkerStore(dir)
        muteStore = MuteStore(dir)
        vault = AndroidCredentialVault(this)
        chatAppearanceStore = ChatAppearanceStore(dir)
        val stsPolicies = FileStsPolicyStore(dir)
        val tlsIdentityProvider = AndroidTlsIdentityProvider(this)
        Notifications.ensureChannels(this)
        coordinator = LiveCoordinator(
            scope = appScope,
            networkStore = networkStore,
            messageStore = messageStore,
            readMarkers = readMarkerStore,
            historyCoverage = dev.brentdevs.yardhal.core.data.HistoryCoverageStore(dir),
            mutes = muteStore,
            vault = vault,
            channelOrder = ChannelOrderStore(dir),
            connectionFactory = ConnectionFactory { config, onStsUpgrade ->
                IrcConnection(
                    config = IrcConnectionConfig(
                        host = config.host,
                        port = config.port,
                        tls = config.tls,
                        nick = config.nick,
                        username = config.username,
                        realName = config.realName,
                        saslAuthcid = config.saslAuthcid,
                        saslPassword = config.saslPassword,
                        serverPassword = config.serverPassword,
                        alternateNicks = config.alternateNicks,
                        saslMode = config.saslMode.name,
                        proxy = config.proxy?.let {
                            Socks5Config(it.host, it.port, it.username, config.proxyPassword)
                        },
                        tlsClientIdentity = config.tlsClientIdentity,
                        trustedCertificateSha256 = config.certificatePin?.takeIf {
                            config.tls && it.host.equals(config.host, ignoreCase = true) && it.port == config.port
                        }?.sha256,
                        nickServService = config.nickServService,
                        knownSecrets = setOfNotNull(
                            config.saslPassword,
                            config.serverPassword,
                            config.nickServPassword,
                            config.proxyPassword,
                        ),
                    ),
                    rawTap = { outbound, line -> coordinator.ingestRaw(config.id, outbound, line) },
                    stsPolicyStore = stsPolicies,
                    onStsUpgrade = onStsUpgrade,
                )
            },
            clientIdentityProvider = tlsIdentityProvider::resolve,
            stsPolicies = stsPolicies,
            notifier = LiveCoordinator.HighlightNotifier { networkName, sender, conversation, text ->
                Notifications.highlight(this, networkName, sender, conversation, text)
            },
            offlineStore = OfflineStore(db.offlineDao()),
        )
        coordinator.attachIgnores(IgnoreStore(dir))
        connectivityObserver = AndroidConnectivityObserver(this, coordinator::updateConnectivity)
        connectivityObserver.start()
        coordinator.startAll()
        appScope.launch {
            var serviceWanted = false
            var foregroundCount = 0
            coordinator.networks.collect { networks ->
                val wanted = networks.any { it.connectionPhase.keepsRecoveryService() }
                val count = networks.count { it.status == ConnectionStatus.REGISTERED }
                if (wanted) {
                    if (!serviceWanted || count != foregroundCount) {
                        ConnectionService.start(this@YardhalApplication, count)
                    }
                } else if (serviceWanted) {
                    ConnectionService.stop(this@YardhalApplication)
                }
                serviceWanted = wanted
                foregroundCount = count
            }
        }
        backfillSearchIndex()
        appScope.launch(Dispatchers.IO) {
            while (true) {
                remoteImages.maintain()
                delay(5L * 60 * 1_000)
            }
        }
    }

    override fun onTerminate() {
        if (::connectivityObserver.isInitialized) connectivityObserver.close()
        appScope.cancel()
        super.onTerminate()
    }

    private fun backfillSearchIndex() {
        if (StorageRecovery.databaseTemporary.value) return
        val prefs = getSharedPreferences("yardhal-meta", MODE_PRIVATE)
        if (prefs.getBoolean("fts_backfill_v2", false)) return
        appScope.launch {
            runCatching { messageStore.reindexAll() }
                .onSuccess { prefs.edit().putBoolean("fts_backfill_v2", true).apply() }
        }
    }
}

