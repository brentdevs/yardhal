package dev.brentdevs.yardhal

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.brentdevs.yardhal.coordinator.ConnectionFactory
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ChatAppearanceStore
import dev.brentdevs.yardhal.core.data.CredentialVault
import dev.brentdevs.yardhal.core.data.FileStsPolicyStore
import dev.brentdevs.yardhal.core.data.IgnoreStore
import dev.brentdevs.yardhal.core.data.MessageStore
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.YardhalDatabase
import dev.brentdevs.yardhal.service.ConnectionService
import dev.brentdevs.yardhal.service.Notifications
import dev.brentdevs.yardhal.ui.image.RemoteImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

class YardhalApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    val remoteImages: RemoteImageLoader by lazy { RemoteImageLoader(cacheDir) }

    override fun onCreate() {
        super.onCreate()
        val dir = filesDir
        networkStore = NetworkStore(dir)
        val db = YardhalDatabase.build(this)
        messageStore = MessageStore(db.messageDao())
        readMarkerStore = ReadMarkerStore(dir)
        muteStore = MuteStore(dir)
        vault = AndroidCredentialVault(this)
        chatAppearanceStore = ChatAppearanceStore(dir)
        val stsPolicies = FileStsPolicyStore(dir)
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
                        serverPassword = config.serverPasswordRef?.let { vault.readPassword(it) },
                    ),
                    rawTap = { outbound, line -> coordinator.ingestRaw(config.id, outbound, line) },
                    stsPolicyStore = stsPolicies,
                    onStsUpgrade = onStsUpgrade,
                )
            },
            stsPolicies = stsPolicies,
            notifier = LiveCoordinator.HighlightNotifier { networkName, sender, conversation, text ->
                Notifications.highlight(this, networkName, sender, conversation, text)
            },
        )
        coordinator.attachIgnores(IgnoreStore(dir))
        coordinator.startAll()
        appScope.launch {
            coordinator.networks.collect { networks ->
                if (networks.all { it.status == ConnectionStatus.DISCONNECTED }) {
                    ConnectionService.stop(this@YardhalApplication)
                } else {
                    ConnectionService.start(
                        this@YardhalApplication,
                        networks.count { it.status == ConnectionStatus.REGISTERED },
                    )
                }
            }
        }
        backfillSearchIndex()
    }

    private fun backfillSearchIndex() {
        val prefs = getSharedPreferences("yardhal-meta", MODE_PRIVATE)
        if (prefs.getBoolean("fts_backfill_v2", false)) return
        appScope.launch {
            runCatching { messageStore.reindexAll() }
                .onSuccess { prefs.edit().putBoolean("fts_backfill_v2", true).apply() }
        }
    }
}
