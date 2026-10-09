package dev.brentdevs.yardhal.ui.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

public data class ImageCacheUsage(public val mediaBytes: Long, public val avatarBytes: Long)

public class RemoteVideo internal constructor(public val file: File, private val release: () -> Unit) : AutoCloseable {
    private var closed = false
    @Synchronized
    override fun close() {
        if (!closed) { closed = true; release() }
    }
}

public class RemoteImageLoader(
    cacheRoot: File,
    connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val directory = File(cacheRoot, DIRECTORY_NAME)
    private val avatarDirectory = File(cacheRoot, "remote-avatars")
    private val videoDirectory = File(cacheRoot, "remote-video-playback")
    private val diskLock = Any()
    private val flightLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = MediaHttp(connectionFactory)
    private val inFlight = mutableMapOf<String, Flight>()
    private val requests = ConcurrentHashMap.newKeySet<MediaRequestCancellation>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val activeVideos = ConcurrentHashMap.newKeySet<File>()
    private val mediaGeneration = AtomicLong()
    private val avatarGeneration = AtomicLong()
    private val staticImages = LruCache<String, Boolean>(256)
    @Volatile private var networkAllowed = true
    private var mediaBudget = DISK_CACHE_BYTES
    private var avatarBudget = 8L * 1024 * 1024
    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val avatarMemory = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES / 2) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    public fun cached(url: String, sizePx: Int, category: ImageCacheCategory = ImageCacheCategory.MEDIA): Bitmap? =
        memory(category).get(memoryKey(url, sizePx))

    public fun configureBudgets(mediaBytes: Long, avatarBytes: Long) {
        synchronized(diskLock) {
            mediaBudget = mediaBytes.coerceAtLeast(0)
            avatarBudget = avatarBytes.coerceAtLeast(0)
            prune(directory, mediaBudget)
            prune(avatarDirectory, avatarBudget)
        }
    }

    public fun usage(): ImageCacheUsage = synchronized(diskLock) {
        ImageCacheUsage(directory.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L,
            avatarDirectory.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L)
    }

    public fun clear(category: ImageCacheCategory) {
        generation(category).incrementAndGet()
        synchronized(flightLock) {
            inFlight.filterKeys { it.startsWith("$category|") }.values.forEach {
                it.cancellation.cancel()
                it.pending.cancel()
            }
            inFlight.keys.removeAll { it.startsWith("$category|") }
        }
        memory(category).evictAll()
        if (category == ImageCacheCategory.MEDIA) staticImages.evictAll()
        synchronized(diskLock) {
            var failed = false
            directory(category).listFiles()?.forEach { if (!it.delete() && it.exists()) failed = true }
            if (failed) throw IOException("Some cached media could not be removed")
        }
        failedAt.keys.removeIf { it.startsWith("$category|") }
    }

    public fun retryFailures() { failedAt.clear() }

    public fun setNetworkAllowed(allowed: Boolean) {
        networkAllowed = allowed
        if (!allowed) cancelRequests()
    }

    public fun cancelRequests() {
        requests.forEach { it.cancel() }
        synchronized(flightLock) {
            inFlight.values.forEach { it.cancellation.cancel(); it.pending.cancel() }
            inFlight.clear()
        }
    }

    public fun maintain() {
        synchronized(diskLock) {
            prune(directory, mediaBudget)
            prune(avatarDirectory, avatarBudget)
            videoDirectory.listFiles()?.filter { it !in activeVideos }?.forEach { it.delete() }
        }
        val now = clock()
        failedAt.entries.removeIf { now - it.value >= FAILURE_RETRY_MS }
    }

    public suspend fun load(url: String, sizePx: Int, category: ImageCacheCategory = ImageCacheCategory.MEDIA): Bitmap? {
        val resolved = ImageUrlPolicy.resolve(url, sizePx) ?: return null
        cached(resolved, sizePx, category)?.let { return it }
        val generationAtStart = generation(category).get()
        val bytes = bytes(resolved, category) ?: return null
        return withContext(Dispatchers.IO) {
            val bitmap = decode(bytes, sizePx)
            if (bitmap != null) {
                synchronized(diskLock) {
                    if (generation(category).get() == generationAtStart) memory(category).put(memoryKey(resolved, sizePx), bitmap)
                }
            } else failedAt["$category|$resolved"] = clock()
            bitmap
        }
    }

    public suspend fun loadDrawable(url: String, sizePx: Int, animate: Boolean): Drawable? {
        val resolved = ImageUrlPolicy.resolve(url, sizePx) ?: return null
        if (!animate || staticImages.get(resolved) == true) {
            cached(resolved, sizePx)?.let { return android.graphics.drawable.BitmapDrawable(null, it) }
        }
        val generationAtStart = mediaGeneration.get()
        val bytes = bytes(resolved, ImageCacheCategory.MEDIA) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                val drawable = if (animate) ImageDecoder.decodeDrawable(source) { decoder, info, _ -> configureDecoder(decoder, info, sizePx) }
                else android.graphics.drawable.BitmapDrawable(null, ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    configureDecoder(decoder, info, sizePx)
                })
                val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (bitmap != null) synchronized(diskLock) {
                    if (mediaGeneration.get() == generationAtStart) {
                        memory.put(memoryKey(resolved, sizePx), bitmap)
                        if (animate) staticImages.put(resolved, true)
                    }
                }
                drawable
            } catch (_: IOException) {
                failedAt["${ImageCacheCategory.MEDIA}|$resolved"] = clock()
                null
            } catch (_: IllegalArgumentException) {
                failedAt["${ImageCacheCategory.MEDIA}|$resolved"] = clock()
                null
            }
        }
    }

    public suspend fun probe(url: String): MediaProbeResult = kotlinx.coroutines.withTimeoutOrNull(20_000) {
        request { cancellation -> http.probe(url, cancellation) }
    } ?: MediaProbeResult.Unavailable

    public suspend fun loadVideo(url: String): RemoteVideo? {
        if (ImageUrlPolicy.resolve(url, 1) == null) return null
        return try {
            kotlinx.coroutines.withTimeoutOrNull(60_000) {
                request { cancellation ->
                    val file = synchronized(diskLock) {
                        videoDirectory.mkdirs()
                        File.createTempFile("video-", ".media", videoDirectory).also { activeVideos.add(it) }
                    }
                    var retained = false
                    try {
                        if (!http.downloadVideo(url, file, cancellation)) return@request null
                        cancellation.check()
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            if (!MediaPolicy.dimensionsAllowed(width, height)) return@request null
                        } catch (_: RuntimeException) {
                            return@request null
                        } finally {
                            retriever.release()
                        }
                        retained = true
                        RemoteVideo(file) { activeVideos.remove(file); file.delete() }
                    } finally {
                        if (!retained) { activeVideos.remove(file); file.delete() }
                    }
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private suspend fun <T> request(block: (MediaRequestCancellation) -> T): T {
        val cancellation = MediaRequestCancellation()
        val resultLock = Any()
        var delivered = false
        var abandoned = false
        var unclaimed: Any? = null
        requests.add(cancellation)
        val pending = scope.async {
            if (!networkAllowed) throw kotlinx.coroutines.CancellationException("Media networking is inactive")
            val result = block(cancellation)
            synchronized(resultLock) {
                if (abandoned) {
                    (result as? AutoCloseable)?.close()
                    throw kotlinx.coroutines.CancellationException("Media request abandoned")
                }
                unclaimed = result
            }
            result
        }
        return try {
            pending.await().also { synchronized(resultLock) { delivered = true; unclaimed = null } }
        } finally {
            synchronized(resultLock) {
                if (!delivered) {
                    abandoned = true
                    (unclaimed as? AutoCloseable)?.close()
                    unclaimed = null
                }
            }
            cancellation.cancel()
            pending.cancel()
            requests.remove(cancellation)
        }
    }

    private suspend fun bytes(url: String, category: ImageCacheCategory): ByteArray? {
        val key = "$category|$url"
        val failure = failedAt[key]
        if (failure != null && clock() - failure < FAILURE_RETRY_MS) return null
        val flight = synchronized(flightLock) {
            inFlight[key]?.also { it.readers++ } ?: run {
                val cancellation = MediaRequestCancellation()
                val pending = scope.async(start = CoroutineStart.LAZY) {
                    val result = cachedBytes(url, category) ?: if (networkAllowed) http.download(url, maxBytes(category), false, cancellation)?.also {
                        cancellation.check()
                        store(url, it, category, cancellation)
                    }
                    else null
                    if (result == null) {
                        if (networkAllowed) failedAt[key] = clock()
                    } else failedAt.remove(key)
                    result
                }
                Flight(pending, cancellation, 1).also { inFlight[key] = it; pending.start() }
            }
        }
        return try { kotlinx.coroutines.withTimeoutOrNull(30_000) { flight.pending.await() } } finally {
            synchronized(flightLock) {
                flight.readers--
                if (flight.readers == 0) {
                    flight.cancellation.cancel()
                    flight.pending.cancel()
                    if (inFlight[key] === flight) inFlight.remove(key)
                }
            }
        }
    }

    private fun configureDecoder(decoder: ImageDecoder, info: ImageDecoder.ImageInfo, sizePx: Int) {
        val width = info.size.width
        val height = info.size.height
        require(MediaPolicy.dimensionsAllowed(width, height))
        val (targetWidth, targetHeight) = MediaPolicy.targetDimensions(width, height, sizePx)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSize(targetWidth, targetHeight)
    }

    private fun decode(bytes: ByteArray, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!MediaPolicy.dimensionsAllowed(bounds.outWidth, bounds.outHeight)) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageUrlPolicy.sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun cachedBytes(url: String, category: ImageCacheCategory): ByteArray? = synchronized(diskLock) {
        val file = fileFor(url, category)
        if (!file.isFile) return@synchronized null
        if (clock() - file.lastModified() > DISK_TTL_MS || file.length() > maxBytes(category)) {
            file.delete()
            return@synchronized null
        }
        try { file.readBytes() } catch (_: IOException) { null }
    }

    private fun store(url: String, bytes: ByteArray, category: ImageCacheCategory, cancellation: MediaRequestCancellation) = synchronized(diskLock) {
        cancellation.check()
        val budget = if (category == ImageCacheCategory.MEDIA) mediaBudget else avatarBudget
        if (bytes.size > budget) return@synchronized
        try {
            directory(category).mkdirs()
            val target = fileFor(url, category)
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) temporary.delete()
            target.setLastModified(clock())
            prune(directory(category), budget)
        } catch (_: IOException) {
            return@synchronized
        }
    }

    private fun prune(targetDirectory: File, budget: Long) {
        val files = targetDirectory.listFiles() ?: return
        val now = clock()
        var total = 0L
        for (file in files) {
            if (!file.isFile) continue
            if (now - file.lastModified() > DISK_TTL_MS && file.delete()) continue
            total += file.length()
        }
        if (total <= budget) return
        files.sortBy { it.lastModified() }
        for (file in files) {
            if (total <= budget) break
            if (!file.isFile) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun directory(category: ImageCacheCategory): File = if (category == ImageCacheCategory.MEDIA) directory else avatarDirectory
    private fun memory(category: ImageCacheCategory): LruCache<String, Bitmap> = if (category == ImageCacheCategory.MEDIA) memory else avatarMemory
    private fun maxBytes(category: ImageCacheCategory): Int = if (category == ImageCacheCategory.MEDIA) MediaPolicy.MAX_IMAGE_BYTES else ImageUrlPolicy.MAX_BYTES
    private fun generation(category: ImageCacheCategory): AtomicLong = if (category == ImageCacheCategory.MEDIA) mediaGeneration else avatarGeneration

    private fun fileFor(url: String, category: ImageCacheCategory): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return File(directory(category), digest.joinToString("") { "%02x".format(it) })
    }

    private fun memoryKey(url: String, sizePx: Int): String = "$sizePx|$url"
    private class Flight(val pending: Deferred<ByteArray?>, val cancellation: MediaRequestCancellation, var readers: Int)

    private companion object {
        const val DIRECTORY_NAME = "remote-images"
        const val MEMORY_CACHE_BYTES = 8 * 1024 * 1024
        const val DISK_CACHE_BYTES = 32L * 1024 * 1024
        const val DISK_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val FAILURE_RETRY_MS = 10L * 60 * 1000
    }
}

public val LocalRemoteImageLoader: androidx.compose.runtime.ProvidableCompositionLocal<RemoteImageLoader?> =
    staticCompositionLocalOf { null }

public sealed interface RemoteImageState {
    public data object Loading : RemoteImageState
    public data class Success(public val bitmap: ImageBitmap) : RemoteImageState
    public data object Unavailable : RemoteImageState
}

@Composable
public fun rememberRemoteImage(
    loader: RemoteImageLoader?,
    rawUrl: String?,
    sizePx: Int,
    category: ImageCacheCategory = ImageCacheCategory.MEDIA,
    enabled: Boolean = true,
    visible: Boolean = true,
): State<RemoteImageState> {
    val environment = LocalMediaEnvironment.current
    val online by (environment?.online ?: DefaultMediaSignals.online).collectAsState()
    val active by (environment?.appActive ?: DefaultMediaSignals.active).collectAsState()
    val revision by (environment?.cacheRevision ?: DefaultMediaSignals.revision).collectAsState()
    val url = ImageUrlPolicy.resolve(rawUrl, sizePx)
    val eligible = enabled && visible && active
    val image = remember(loader, url, sizePx, category, eligible, online, revision) {
        val initial = if (loader == null || url == null || !eligible) RemoteImageState.Unavailable
        else loader.cached(url, sizePx, category)?.let { RemoteImageState.Success(it.asImageBitmap()) }
            ?: if (online) RemoteImageState.Loading else RemoteImageState.Unavailable
        mutableStateOf<RemoteImageState>(initial)
    }
    LaunchedEffect(loader, url, sizePx, category, eligible, online, revision) {
        if (loader != null && url != null && eligible && online) {
            val result = loader.load(url, sizePx, category)
            coroutineContext.ensureActive()
            image.value = result?.let { RemoteImageState.Success(it.asImageBitmap()) } ?: RemoteImageState.Unavailable
        }
    }
    return image
}
