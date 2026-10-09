package dev.brentdevs.yardhal.ui.image

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.brentdevs.yardhal.core.data.MediaPreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal object DefaultMediaSignals {
    val online = MutableStateFlow(true)
    val active = MutableStateFlow(true)
    val preferences = MutableStateFlow(dev.brentdevs.yardhal.core.data.MediaPreferences())
    val reveals = MutableStateFlow<Map<String, dev.brentdevs.yardhal.core.data.MediaRevealState>>(emptyMap())
    val reducedMotion = MutableStateFlow(false)
    val revision = MutableStateFlow(0L)
}

public class MediaEnvironment(public val loader: RemoteImageLoader, public val preferences: MediaPreferencesStore) {
    private val mutableOnline = MutableStateFlow(true)
    private val mutableActive = MutableStateFlow(false)
    private val mutableSystemReducedMotion = MutableStateFlow(false)
    private val mutableCacheRevision = MutableStateFlow(0L)
    public val online: StateFlow<Boolean> = mutableOnline.asStateFlow()
    public val appActive: StateFlow<Boolean> = mutableActive.asStateFlow()
    public val systemReducedMotion: StateFlow<Boolean> = mutableSystemReducedMotion.asStateFlow()
    public val cacheRevision: StateFlow<Long> = mutableCacheRevision.asStateFlow()
    init {
        loader.setNetworkAllowed(false)
        val initial = preferences.snapshot()
        loader.configureBudgets(initial.mediaCacheBytes, initial.avatarCacheBytes)
    }

    @Synchronized
    public fun setOnline(online: Boolean) {
        if (online && !mutableOnline.value) loader.retryFailures()
        mutableOnline.value = online
        loader.setNetworkAllowed(online && mutableActive.value)
    }

    @Synchronized
    public fun setAppActive(active: Boolean) {
        mutableActive.value = active
        loader.setNetworkAllowed(active && mutableOnline.value)
    }

    public fun setSystemReducedMotion(reduced: Boolean) { mutableSystemReducedMotion.value = reduced }

    public suspend fun clearCache(category: ImageCacheCategory) {
        withContext(Dispatchers.IO) { loader.clear(category) }
        mutableCacheRevision.value += 1
    }
}

public val LocalMediaEnvironment: androidx.compose.runtime.ProvidableCompositionLocal<MediaEnvironment?> =
    staticCompositionLocalOf { null }

@Composable
public fun MediaEnvironmentProvider(environment: MediaEnvironment, content: @Composable () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val preferences by environment.preferences.preferences.collectAsState()
    LaunchedEffect(environment, preferences.mediaCacheBytes, preferences.avatarCacheBytes) {
        withContext(Dispatchers.IO) { environment.loader.configureBudgets(preferences.mediaCacheBytes, preferences.avatarCacheBytes) }
    }
    DisposableEffect(environment, owner) {
        val observer = LifecycleEventObserver { _, _ ->
            environment.setAppActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        owner.lifecycle.addObserver(observer)
        environment.setAppActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            owner.lifecycle.removeObserver(observer)
            environment.setAppActive(false)
        }
    }
    DisposableEffect(environment, context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { environment.setSystemReducedMotion(!ValueAnimator.areAnimatorsEnabled()) }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        environment.setSystemReducedMotion(!ValueAnimator.areAnimatorsEnabled())
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalMediaEnvironment provides environment, LocalRemoteImageLoader provides environment.loader, content = content)
}
