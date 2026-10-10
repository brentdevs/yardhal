package dev.brentdevs.yardhal.ui.image

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.brentdevs.yardhal.core.data.MediaPreferencesStore
import dev.brentdevs.yardhal.core.data.MediaRevealState
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface PreviewState {
    data object Idle : PreviewState
    data object Loading : PreviewState
    data object Unavailable : PreviewState
    data object File : PreviewState
    data class Image(val drawable: Drawable) : PreviewState
    data class Video(val video: RemoteVideo) : PreviewState
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
public fun MediaPreview(
    descriptor: MediaDescriptor,
    mediaIdentity: String?,
    visible: Boolean,
    onOpen: (() -> Unit)?,
    metadataInMessageSummary: Boolean = false,
) {
    val environment = LocalMediaEnvironment.current
    val loader = environment?.loader ?: LocalRemoteImageLoader.current
    val preferences by (environment?.preferences?.preferences ?: DefaultMediaSignals.preferences).collectAsState()
    val reveals by (environment?.preferences?.reveals ?: DefaultMediaSignals.reveals).collectAsState()
    val online by (environment?.online ?: DefaultMediaSignals.online).collectAsState()
    val active by (environment?.appActive ?: DefaultMediaSignals.active).collectAsState()
    val systemReducedMotion by (environment?.systemReducedMotion ?: DefaultMediaSignals.reducedMotion).collectAsState()
    val revision by (environment?.cacheRevision ?: DefaultMediaSignals.revision).collectAsState()
    val scope = rememberCoroutineScope()
    var localReveal by remember(descriptor.url) { mutableStateOf(MediaRevealState.DEFAULT) }
    var error by remember(descriptor.url) { mutableStateOf<String?>(null) }
    var discovered by remember(descriptor.url, descriptor.mimeType) { mutableStateOf(descriptor) }
    val reveal = if (environment != null && mediaIdentity != null) {
        reveals[MediaPreferencesStore.revealKey(mediaIdentity, descriptor.url)] ?: MediaRevealState.DEFAULT
    } else localReveal
    val eligible = MediaPolicy.mayLoad(preferences.autoLoadImages, reveal, visible, active)
    val animate = MediaPolicy.mayAnimate(preferences.animateImages, preferences.reducedMotion || systemReducedMotion, visible, active)
    var playRequested by remember(descriptor.url, visible, active, online, preferences.enableVideos) { mutableStateOf(false) }
    val requestAnimation = discovered.kind != MediaKind.VIDEO && animate
    val wantsVideo = discovered.kind == MediaKind.VIDEO && preferences.enableVideos && reveal != MediaRevealState.HIDDEN && playRequested && visible && active
    var state by remember(descriptor.url, eligible, wantsVideo, online, requestAnimation, revision) {
        mutableStateOf<PreviewState>(if ((eligible && discovered.kind != MediaKind.VIDEO || wantsVideo) && online) PreviewState.Loading else PreviewState.Idle)
    }
    var retry by remember(descriptor.url) { mutableStateOf(0) }
    DisposableEffect(environment, mediaIdentity, descriptor.url, visible, active) {
        val retained = if (mediaIdentity != null && visible && active) environment?.preferences?.retainReveal(mediaIdentity, descriptor.url) else null
        onDispose { if (retained != null) environment?.releaseReveal(retained) }
    }
    fun setReveal(next: MediaRevealState) {
        if (environment == null || mediaIdentity == null) {
            localReveal = next
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { environment.preferences.setReveal(mediaIdentity, descriptor.url, next) }
                error = null
            } catch (_: IOException) {
                error = "This media choice could not be saved. Try again when storage is available."
            } catch (_: SecurityException) {
                error = "Media choice storage is unavailable."
            }
        }
    }
    LaunchedEffect(loader, descriptor.url, eligible, wantsVideo, online, requestAnimation, revision, retry) {
        if (!online || loader == null) {
            state = if (eligible || wantsVideo) PreviewState.Unavailable else PreviewState.Idle
            return@LaunchedEffect
        }
        if (wantsVideo) {
            state = PreviewState.Loading
            var video: RemoteVideo? = null
            try {
                video = loader.loadVideo(descriptor.url)
                coroutineContext.ensureActive()
                state = video?.let { PreviewState.Video(it) } ?: PreviewState.Unavailable
                if (video != null) kotlinx.coroutines.awaitCancellation()
            } finally {
                video?.close()
            }
            return@LaunchedEffect
        }
        if (!eligible || discovered.kind == MediaKind.VIDEO) {
            state = PreviewState.Idle
            return@LaunchedEffect
        }
        state = PreviewState.Loading
        if (discovered.kind == MediaKind.UNKNOWN) {
            when (val probe = loader.probe(descriptor.url)) {
                is MediaProbeResult.Image -> discovered = descriptor.copy(mimeType = probe.mimeType, sizeBytes = descriptor.sizeBytes ?: probe.sizeBytes, kind = MediaKind.IMAGE)
                MediaProbeResult.NotImage -> {
                    discovered = descriptor.copy(kind = MediaKind.FILE)
                    state = PreviewState.File
                    return@LaunchedEffect
                }
                MediaProbeResult.Unavailable -> {
                    state = PreviewState.Unavailable
                    return@LaunchedEffect
                }
            }
        }
        state = if (discovered.kind == MediaKind.IMAGE) loader.loadDrawable(descriptor.url, 1024, animate)?.let { PreviewState.Image(it) } ?: PreviewState.Unavailable
        else PreviewState.File
    }
    val displayName = MediaPolicy.displayName(discovered)
    val host = runCatching { URI(descriptor.url).host }.getOrNull().orEmpty()
    val metadataModifier = if (metadataInMessageSummary) Modifier.clearAndSetSemantics {} else Modifier
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(displayName, style = MaterialTheme.typography.labelMedium, modifier = metadataModifier)
            Text(listOfNotNull(discovered.mimeType ?: when (discovered.kind) {
                MediaKind.IMAGE -> "Image"
                MediaKind.VIDEO -> "Video · explicit playback only"
                MediaKind.FILE -> "File"
                MediaKind.UNKNOWN -> "Link · image type not yet checked"
            }, discovered.sizeBytes?.let(::formatMediaSize), host.takeIf { it.isNotEmpty() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = metadataModifier)
            when (val preview = state) {
                is PreviewState.Image -> InlineImage(preview.drawable, displayName, animate, onOpen)
                is PreviewState.Video -> InlineVideo(preview.video, displayName, onStop = { playRequested = false }, onError = {
                    playRequested = false
                    error = "Video format could not be played. Open externally or try again."
                })
                PreviewState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp).clearAndSetSemantics {}, strokeWidth = 2.dp)
                    Text(if (wantsVideo) "Downloading bounded video…" else "Loading image preview…", style = MaterialTheme.typography.bodySmall)
                }
                PreviewState.Unavailable -> Text(if (!online) "Offline · preview will retry when connected" else "Preview unavailable · unsupported content or safety limit", style = MaterialTheme.typography.bodySmall)
                PreviewState.Idle, PreviewState.File -> Unit
            }
            if (discovered.kind == MediaKind.VIDEO && !preferences.enableVideos) Text("Video playback is disabled in media settings.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (discovered.kind == MediaKind.IMAGE || discovered.kind == MediaKind.UNKNOWN) {
                    if (!eligible) MediaActionButton(if (discovered.kind == MediaKind.UNKNOWN) "Check for image" else "Reveal image", displayName, onClick = { setReveal(MediaRevealState.REVEALED) })
                    else MediaActionButton("Hide media", displayName, onClick = { setReveal(MediaRevealState.HIDDEN) })
                }
                if (descriptor.kind == MediaKind.UNKNOWN && discovered.kind == MediaKind.FILE) {
                    MediaActionButton("Recheck for image", displayName, enabled = online && visible && active, onClick = {
                        discovered = descriptor
                        setReveal(MediaRevealState.REVEALED)
                        loader?.retryFailures()
                        retry++
                    })
                }
                if (discovered.kind == MediaKind.VIDEO && preferences.enableVideos && state !is PreviewState.Video) {
                    MediaActionButton(if (reveal == MediaRevealState.HIDDEN) "Reveal video" else "Play video", displayName, enabled = online && visible && active && state != PreviewState.Loading, onClick = {
                        if (reveal == MediaRevealState.HIDDEN) setReveal(MediaRevealState.REVEALED)
                        else {
                            if (reveal != MediaRevealState.REVEALED) setReveal(MediaRevealState.REVEALED)
                            playRequested = true
                        }
                    })
                }
                if (discovered.kind == MediaKind.VIDEO && reveal != MediaRevealState.HIDDEN) MediaActionButton("Hide video", displayName, onClick = { playRequested = false; setReveal(MediaRevealState.HIDDEN) })
                if (state == PreviewState.Unavailable && online && (eligible || wantsVideo)) MediaActionButton("Retry preview", displayName, onClick = { loader?.retryFailures(); retry++ })
                if (onOpen != null) MediaActionButton("Open externally", displayName, onClick = onOpen)
            }
            if (reveal != MediaRevealState.DEFAULT) MediaActionButton("Use global media setting", displayName, onClick = { setReveal(MediaRevealState.DEFAULT) })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun MediaActionButton(label: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "$label: $description" },
    ) {
        Text(label, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun InlineImage(drawable: Drawable, description: String, animate: Boolean, onOpen: (() -> Unit)?) {
    DisposableEffect(drawable, animate) {
        val animated = drawable as? Animatable
        if (animate) animated?.start() else animated?.stop()
        onDispose { animated?.stop() }
    }
    AndroidView(factory = { context ->
        ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    }, update = { view ->
        view.contentDescription = "Image preview: $description${if (onOpen != null) ". Open externally" else ""}"
        view.setImageDrawable(drawable)
        view.setOnClickListener(if (onOpen != null) android.view.View.OnClickListener { onOpen() } else null)
        view.isClickable = onOpen != null
    }, modifier = Modifier.fillMaxWidth().height(220.dp))
}

@Composable
private fun InlineVideo(video: RemoteVideo, description: String, onStop: () -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val view = remember(video.file) {
        VideoView(context).apply {
            contentDescription = "Video preview: $description"
            setMediaController(MediaController(context).also { it.setAnchorView(this) })
            setVideoPath(video.file.absolutePath)
            setOnPreparedListener { start() }
            setOnCompletionListener { onStop() }
            setOnErrorListener { _, _, _ -> onError(); true }
        }
    }
    DisposableEffect(view) { onDispose { view.stopPlayback() } }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(220.dp))
    MediaActionButton("Stop video", description, onClick = onStop)
}
