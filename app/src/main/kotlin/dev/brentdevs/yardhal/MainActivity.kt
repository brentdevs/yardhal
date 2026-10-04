package dev.brentdevs.yardhal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            var appearance by remember { mutableStateOf(app.chatAppearanceStore.snapshot()) }
            val isDark = isSystemInDarkTheme()
            YardhalTheme(
                themeDefinition = if (isDark && !appearance.dynamicColor) loadBundledTheme() else null,
                dynamicColor = appearance.dynamicColor,
                amoledDark = appearance.amoledDark,
            ) {
                CompositionLocalProvider(LocalRemoteImageLoader provides app.remoteImages) {
                    YardhalAppRoot(
                        coordinator = coordinator,
                        appearanceStore = app.chatAppearanceStore,
                        presets = NetworkPresets.ALL.map {
                            NetworkPresetUi(it.id, it.name, it.host, it.port, it.tls)
                        },
                        onNetworkSaved = { draft -> saveAndConnect(draft) },
                        sharedTextProvider = { (application as YardhalApplication).sharedText },
                        onSharedConsumed = { (application as YardhalApplication).sharedText = null },
                        onAppearanceChanged = { appearance = it },
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
