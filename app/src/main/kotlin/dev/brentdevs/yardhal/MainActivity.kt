package dev.brentdevs.yardhal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import dev.brentdevs.yardhal.core.data.ThemeShareLink
import dev.brentdevs.yardhal.ui.YardhalAppRoot
import dev.brentdevs.yardhal.ui.image.MediaEnvironmentProvider
import dev.brentdevs.yardhal.ui.screens.NetworkPresetUi
import dev.brentdevs.yardhal.ui.theme.YardhalTheme
import dev.brentdevs.yardhal.ui.theme.LibraryYardhalTheme
import dev.brentdevs.yardhal.service.NotificationRoutes
import android.net.Uri
import dev.brentdevs.yardhal.media.IncomingShareIntake
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    private var shareRequestId: String = UUID.randomUUID().toString()
    private var shareHandled = false
    private var handledNotificationUri: String? = null
    private var handledThemeLink: String? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareRequestId = savedInstanceState?.getString("share-request-id") ?: UUID.randomUUID().toString()
        shareHandled = savedInstanceState?.getBoolean("share-handled") ?: false
        enableEdgeToEdge()
        val app = application as YardhalApplication
        handledNotificationUri = savedInstanceState?.getString("notification-handled-uri")
        handledThemeLink = savedInstanceState?.getString("theme-handled-link")
        savedInstanceState?.getString("theme-pending-link")?.let { app.incomingThemeLink = it }
        savedInstanceState?.getStringArrayList("notification-pending")?.forEach { encoded ->
            NotificationRoutes.decode(Uri.parse(encoded))?.let(app.notificationRoutes::enqueue)
        }
        consumeNotificationIntent(intent)

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
            val appearance by app.chatAppearanceStore.preferences.collectAsState()
            val libraryState by app.themeLibrary.state.collectAsState()
            val presets = remember {
                NetworkPresets.ALL.map {
                    NetworkPresetUi(it.id, it.name, it.host, it.port, it.tls)
                }
            }
            LibraryYardhalTheme(libraryState = libraryState, appearance = appearance) {
                MediaEnvironmentProvider(app.mediaEnvironment) {
                    YardhalAppRoot(
                        coordinator = coordinator,
                        appearanceStore = app.chatAppearanceStore,
                        themeLibrary = app.themeLibrary,
                        notificationPreferencesStore = app.notificationPreferencesStore,
                        notificationRoutes = app.notificationRoutes,
                        catchUpStore = app.catchUpStore,
                        incomingThemeLink = app.incomingThemeLink,
                        onThemeLinkConsumed = { link ->
                            if (app.incomingThemeLink == link) app.incomingThemeLink = null
                        },
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
                        modifier = Modifier,
                    )
                }
            }
        }

        if (!consumeThemeIntent(intent)) consumeShareIntent(intent)
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
        handledNotificationUri = null
        handledThemeLink = null
        if (!consumeThemeIntent(intent)) consumeShareIntent(intent)
        consumeNotificationIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("share-request-id", shareRequestId)
        outState.putBoolean("share-handled", shareHandled)
        outState.putString("notification-handled-uri", handledNotificationUri)
        outState.putString("theme-handled-link", handledThemeLink)
        outState.putString("theme-pending-link", (application as YardhalApplication).incomingThemeLink)
        val pending = (application as YardhalApplication).notificationRoutes.pending.value
        outState.putStringArrayList("notification-pending", ArrayList(pending.map { NotificationRoutes.encode(it).toString() }))
        super.onSaveInstanceState(outState)
    }

    private fun consumeNotificationIntent(incoming: Intent?) {
        val route = NotificationRoutes.decode(incoming) ?: return
        val encoded = incoming?.data?.toString() ?: return
        if (handledNotificationUri == encoded) return
        (application as YardhalApplication).notificationRoutes.enqueue(route)
        handledNotificationUri = encoded
    }

    private fun consumeThemeIntent(incoming: Intent?): Boolean {
        val link = when (incoming?.action) {
            Intent.ACTION_VIEW -> incoming.data?.takeIf { it.scheme == "yardhal" && it.host == "theme" }?.toString()
            Intent.ACTION_SEND -> if (incoming.type == "text/plain" && !incoming.hasExtra(Intent.EXTRA_STREAM) &&
                incoming.clipData?.let { clip -> (0 until clip.itemCount).any { clip.getItemAt(it).uri != null } } != true
            ) {
                incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.startsWith("yardhal://theme/") }
            } else null
            else -> null
        }?.take(ThemeShareLink.MAX_LENGTH + 1) ?: return false
        if (handledThemeLink == link) return true
        (application as YardhalApplication).incomingThemeLink = link
        handledThemeLink = link
        return true
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
