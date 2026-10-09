package dev.brentdevs.yardhal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import dev.brentdevs.yardhal.core.data.NetworkPresets
import dev.brentdevs.yardhal.core.data.ThemeDefinition
import dev.brentdevs.yardhal.core.data.ThemeFileParser
import dev.brentdevs.yardhal.ui.YardhalAppRoot
import dev.brentdevs.yardhal.ui.image.LocalRemoteImageLoader
import dev.brentdevs.yardhal.ui.screens.NetworkPresetUi
import dev.brentdevs.yardhal.ui.theme.YardhalTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as YardhalApplication

        setContent {
            val startup by app.startup.collectAsState()
            if (startup != ApplicationStartup.READY) {
                YardhalTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (startup == ApplicationStartup.LOADING) CircularProgressIndicator()
                            Text(if (startup == ApplicationStartup.LOADING) "Loading local conversations…" else "Unable to initialize local storage.")
                        }
                    }
                }
                return@setContent
            }
            val coordinator = app.coordinator
            val networkSaver = remember(coordinator) { NetworkSaver(coordinator, app.vault) }
            var appearance by remember { mutableStateOf(app.chatAppearanceStore.snapshot()) }
            val isDark = isSystemInDarkTheme()
            val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val useDynamic = appearance.dynamicColor && dynamicSupported
            val bundledTheme = remember { loadBundledTheme() }
            val presets = remember {
                NetworkPresets.ALL.map {
                    NetworkPresetUi(it.id, it.name, it.host, it.port, it.tls)
                }
            }
            YardhalTheme(
                themeDefinition = if (isDark && !useDynamic) bundledTheme else null,
                dynamicColor = useDynamic,
                amoledDark = appearance.amoledDark,
            ) {
                CompositionLocalProvider(LocalRemoteImageLoader provides app.remoteImages) {
                    YardhalAppRoot(
                        coordinator = coordinator,
                        appearanceStore = app.chatAppearanceStore,
                        presets = presets,
                        onNetworkSaved = networkSaver::save,
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
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (app.awaitInitialization()) app.coordinator.onForegroundResume()
                awaitCancellation()
            }
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeShareIntent(intent)
    }

    private fun consumeShareIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        val text = incoming.getStringExtra(Intent.EXTRA_TEXT) ?: return
        (application as YardhalApplication).sharedText = text
    }

    private fun loadBundledTheme(): ThemeDefinition? = runCatching {
        val text = assets.open("themes/night.toml").bufferedReader().use { it.readText() }
        ThemeFileParser.parse(text)
    }.getOrNull()

    private fun requestNotificationPermission() {
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ).let { granted ->
            if (granted != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1,
                )
            }
        }
    }

}
