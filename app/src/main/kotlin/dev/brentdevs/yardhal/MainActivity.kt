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
import dev.brentdevs.yardhal.ui.image.MediaEnvironmentProvider
import dev.brentdevs.yardhal.ui.screens.NetworkPresetUi
import dev.brentdevs.yardhal.ui.theme.YardhalTheme
import dev.brentdevs.yardhal.media.IncomingShareIntake
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    private var shareRequestId: String = UUID.randomUUID().toString()
    private var shareHandled = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareRequestId = savedInstanceState?.getString("share-request-id") ?: UUID.randomUUID().toString()
        shareHandled = savedInstanceState?.getBoolean("share-handled") ?: false
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
                MediaEnvironmentProvider(app.mediaEnvironment) {
                    YardhalAppRoot(
                        coordinator = coordinator,
                        appearanceStore = app.chatAppearanceStore,
                        mediaEnvironment = app.mediaEnvironment,
                        attachmentStages = app.attachmentStages,
                        uploadSettings = app.uploadSettings,
                        recentEmojiStore = app.recentEmoji,
                        relayConfigurations = app.relayConfigurations,
                        presets = presets,
                        onNetworkSaved = networkSaver::save,
                        sharedTextProvider = { (application as YardhalApplication).sharedText },
                        onSharedConsumed = { (application as YardhalApplication).sharedText = null },
                        incomingShareError = app.sharedAttachmentError,
                        onSharedErrorConsumed = { error ->
                            if (app.sharedAttachmentError == error) app.sharedAttachmentError = null
                        },
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
        setIntent(intent)
        shareRequestId = UUID.randomUUID().toString()
        shareHandled = false
        consumeShareIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("share-request-id", shareRequestId)
        outState.putBoolean("share-handled", shareHandled)
        super.onSaveInstanceState(outState)
    }

    private fun consumeShareIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND && incoming?.action != Intent.ACTION_SEND_MULTIPLE) return
        if (shareHandled) return
        val app = application as YardhalApplication
        val share = try {
            IncomingShareIntake.fromIntent(incoming)
        } catch (_: RuntimeException) {
            app.sharedAttachmentError = "Unable to read this share. Select the files with the Android document picker or share them again."
            return
        }
        if (share == null) {
            if (!shareHandled) {
                val text = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (text != null) app.sharedText = text
                else app.sharedAttachmentError = "The share contained no readable files or text. Select files with the Android document picker."
            }
            shareHandled = true
            return
        }
        val requestId = shareRequestId
        app.appScope.launch(Dispatchers.Main.immediate) {
            if (shareRequestId != requestId || shareHandled) return@launch
            if (!app.awaitInitialization()) {
                app.sharedAttachmentError = "Local storage is unavailable. Reopen Yardhal and share the files again."
                return@launch
            }
            if (shareRequestId != requestId || shareHandled) return@launch
            try {
                app.attachmentStages.stageIncoming(share.uris, share.caption, requestId = requestId)
                if (shareRequestId == requestId) {
                    shareHandled = true
                    incoming.action = Intent.ACTION_MAIN
                    setIntent(incoming)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                app.sharedAttachmentError = "Unable to stage shared files. Free local storage or select the files again."
            }
        }
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
