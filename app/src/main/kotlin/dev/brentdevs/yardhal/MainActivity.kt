package dev.brentdevs.yardhal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkPresets
import dev.brentdevs.yardhal.ui.YardhalAppRoot
import dev.brentdevs.yardhal.ui.image.LocalRemoteImageLoader
import dev.brentdevs.yardhal.ui.screens.NetworkDraft
import dev.brentdevs.yardhal.ui.screens.NetworkPresetUi
import dev.brentdevs.yardhal.ui.theme.YardhalTheme
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var coordinator: LiveCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as YardhalApplication
        coordinator = app.coordinator

        setContent {
            YardhalTheme(
                themeDefinition = if (isSystemInDarkTheme()) loadBundledTheme() else null,
            ) {
                CompositionLocalProvider(LocalRemoteImageLoader provides app.remoteImages) {
                    YardhalAppRoot(
                        coordinator = coordinator,
                        appearanceStore = app.chatAppearanceStore,
                        presets = NetworkPresets.ALL.map {
                            NetworkPresetUi(it.id, it.name, it.host, it.port, it.tls)
                        },
                        onNetworkSaved = ::saveNetwork,
                        sharedTextProvider = { (application as YardhalApplication).sharedText },
                        onSharedConsumed = { (application as YardhalApplication).sharedText = null },
                        modifier = Modifier,
                    )
                }
            }
        }

        consumeShareIntent(intent)
        requestNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeShareIntent(intent)
    }

    private fun consumeShareIntent(incoming: Intent?) {
        if (incoming?.action != android.content.Intent.ACTION_SEND) return
        val text = incoming.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: return
        (application as YardhalApplication).sharedText = text
    }

    private fun loadBundledTheme(): dev.brentdevs.yardhal.core.data.ThemeDefinition? = runCatching {
        val text = assets.open("themes/night.toml").bufferedReader().use { it.readText() }
        dev.brentdevs.yardhal.core.data.ThemeFileParser.parse(text)
    }.getOrNull()

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

    private fun saveNetwork(draft: NetworkDraft) {
        val app = application as YardhalApplication
        val existing = draft.networkId?.let { app.networkStore.byId(it) ?: return }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val previousPasswordRef = existing?.saslPasswordRef
        val replacementPassword = draft.saslPassword.takeUnless { draft.clearSaslPassword }
        val passwordRef = when {
            draft.clearSaslPassword -> null
            replacementPassword != null -> {
                if (previousPasswordRef != null && previousPasswordRef == existing?.serverPasswordRef) {
                    "sasl-$id-${UUID.randomUUID()}"
                } else {
                    previousPasswordRef ?: "sasl-$id"
                }
            }
            else -> previousPasswordRef
        }
        val authcid = draft.saslAuthcid ?: passwordRef?.let { draft.nick }
        val config = existing?.copy(
            name = draft.displayName,
            host = draft.host,
            port = draft.port,
            tls = draft.tls,
            nick = draft.nick,
            autojoin = draft.autojoin,
            saslAuthcid = authcid,
            saslPasswordRef = passwordRef,
            saslPassword = null,
        ) ?: NetworkConfig(
            id = id,
            name = draft.displayName,
            host = draft.host,
            port = draft.port,
            tls = draft.tls,
            nick = draft.nick,
            autojoin = draft.autojoin,
            saslAuthcid = authcid,
            saslPasswordRef = passwordRef,
        )
        if (replacementPassword != null && passwordRef != null) {
            app.vault.storePassword(passwordRef, replacementPassword)
        }
        if (existing == null) {
            if (!app.networkStore.add(config)) return
            coordinator.connect(config)
        } else if (!coordinator.updateNetwork(config, credentialsChanged = replacementPassword != null)) {
            return
        }
        if (previousPasswordRef != null && previousPasswordRef != passwordRef &&
            previousPasswordRef != config.serverPasswordRef
        ) {
            app.vault.deletePassword(previousPasswordRef)
        }
    }
}
