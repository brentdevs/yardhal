package dev.brentdevs.yardhal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.Modifier
import dev.brentdevs.yardhal.coordinator.ConnectionFactory
import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.core.client.IrcConnection
import dev.brentdevs.yardhal.core.client.IrcConnectionConfig
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkPresets
import dev.brentdevs.yardhal.service.Notifications
import dev.brentdevs.yardhal.ui.YardhalAppRoot
import dev.brentdevs.yardhal.ui.screens.NetworkDraft
import dev.brentdevs.yardhal.ui.screens.NetworkPresetUi
import dev.brentdevs.yardhal.ui.theme.YardhalTheme
import java.util.UUID
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var coordinator: LiveCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.ensureChannels(this)

        val app = application as YardhalApplication
        coordinator = LiveCoordinator(
            scope = app.appScope,
            networkStore = app.networkStore,
            messageStore = app.messageStore,
            readMarkers = app.readMarkerStore,
            mutes = app.muteStore,
            vault = app.vault,
            connectionFactory = ConnectionFactory { config ->
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
                    ),
                    rawTap = { outbound, line -> coordinator.ingestRaw(config.id, outbound, line) },
                )
            },
            notifier = LiveCoordinator.HighlightNotifier { networkName, sender, conversation, text ->
                Notifications.highlight(this@MainActivity, networkName, sender, conversation, text)
            },
        )

        setContent {
            YardhalTheme {
                YardhalAppRoot(
                    coordinator = coordinator,
                    presets = NetworkPresets.ALL.map {
                        NetworkPresetUi(it.id, it.name, it.host, it.port, it.tls)
                    },
                    onNetworkSaved = { draft -> saveAndConnect(draft) },
                    modifier = Modifier,
                )
            }
        }

        if (savedInstanceState == null) {
            coordinator.startAll()
        }

        requestNotificationPermission()
        observeConnectionCount()
    }

    private fun requestNotificationPermission() {
        androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ).let { granted ->
            if (granted != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1,
                )
            }
        }
    }

    private fun observeConnectionCount() {
        val app = application as YardhalApplication
        app.appScope.launch {
            coordinator.networks.collect { networks ->
                val registered = networks.count { it.status == dev.brentdevs.yardhal.coordinator.ConnectionStatus.REGISTERED }
                if (registered > 0) {
                    dev.brentdevs.yardhal.service.ConnectionService.start(this@MainActivity, registered)
                } else if (networks.isEmpty()) {
                    dev.brentdevs.yardhal.service.ConnectionService.stop(this@MainActivity)
                }
            }
        }
    }

    private fun saveAndConnect(draft: NetworkDraft) {
        val id = UUID.randomUUID().toString()
        val app = application as YardhalApplication
        val passwordRef = draft.saslPassword?.let { password ->
            val key = "sasl-$id"
            app.vault.storePassword(key, password)
            key
        }
        val config = NetworkConfig(
            id = id,
            name = draft.displayName,
            host = draft.host,
            port = draft.port,
            tls = draft.tls,
            nick = draft.nick,
            autojoin = draft.autojoin,
            saslAuthcid = passwordRef?.let { draft.nick },
            saslPasswordRef = passwordRef,
        )
        app.networkStore.add(config)
        coordinator.connect(config)
    }
}
