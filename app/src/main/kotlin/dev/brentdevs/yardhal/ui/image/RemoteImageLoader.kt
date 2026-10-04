package dev.brentdevs.yardhal.ui.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

public class RemoteImageLoader(
    cacheRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val directory = File(cacheRoot, DIRECTORY_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    public fun cached(url: String, sizePx: Int): Bitmap? = memory.get(memoryKey(url, sizePx))

    public suspend fun load(url: String, sizePx: Int): Bitmap? {
        val key = memoryKey(url, sizePx)
        memory.get(key)?.let { return it }
        val failure = failedAt[url]
        if (failure != null && clock() - failure < FAILURE_RETRY_MS) return null
        val created = scope.async(start = CoroutineStart.LAZY) { fetchAndDecode(url, sizePx) }
        val existing = inFlight.putIfAbsent(key, created)
        val pending = if (existing == null) {
            created.also { it.start() }
        } else {
            created.cancel()
            existing
        }
        return try {
            pending.await()
        } finally {
            inFlight.remove(key, pending)
        }
    }

    private fun fetchAndDecode(url: String, sizePx: Int): Bitmap? {
        val bytes = cachedBytes(url) ?: download(url)?.also { store(url, it) }
        val bitmap = bytes?.let { decode(it, sizePx) }
        if (bitmap == null) {
            failedAt[url] = clock()
            return null
        }
        failedAt.remove(url)
        memory.put(memoryKey(url, sizePx), bitmap)
        return bitmap
    }

    private fun download(url: String): ByteArray? {
        val connection = try {
            URL(url).openConnection() as? HttpURLConnection
        } catch (_: IOException) {
            null
        } ?: return null
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "image/*")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val accepted = connection.responseCode == HttpURLConnection.HTTP_OK &&
                connection.url.protocol.equals("https", ignoreCase = true) &&
                ImageUrlPolicy.acceptsContentLength(connection.contentLengthLong)
            if (accepted) connection.inputStream.use { ImageUrlPolicy.readCapped(it) } else null
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(bytes: ByteArray, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageUrlPolicy.sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun cachedBytes(url: String): ByteArray? {
        val file = fileFor(url)
        if (!file.isFile) return null
        if (clock() - file.lastModified() > DISK_TTL_MS || file.length() > ImageUrlPolicy.MAX_BYTES) {
            file.delete()
            return null
        }
        return try {
            file.readBytes()
        } catch (_: IOException) {
            null
        }
    }

    private fun store(url: String, bytes: ByteArray) {
        try {
            directory.mkdirs()
            val target = fileFor(url)
            val temporary = File(directory, target.name + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) temporary.delete()
            prune()
        } catch (_: IOException) {
            return
        }
    }

    private fun prune() {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun fileFor(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) })
    }

    private fun memoryKey(url: String, sizePx: Int): String = "$sizePx|$url"

    private companion object {
        const val DIRECTORY_NAME = "remote-images"
        const val MEMORY_CACHE_BYTES = 8 * 1024 * 1024
        const val DISK_CACHE_BYTES = 32L * 1024 * 1024
        const val DISK_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val FAILURE_RETRY_MS = 10L * 60 * 1000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val USER_AGENT = "Yardhal"
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
public fun rememberRemoteImage(loader: RemoteImageLoader?, rawUrl: String?, sizePx: Int): State<RemoteImageState> {
    val url = ImageUrlPolicy.resolve(rawUrl, sizePx)
    val image = remember(loader, url, sizePx) {
        val initial = if (loader == null || url == null) {
            RemoteImageState.Unavailable
        } else {
            loader.cached(url, sizePx)?.let { RemoteImageState.Success(it.asImageBitmap()) }
                ?: RemoteImageState.Loading
        }
        mutableStateOf<RemoteImageState>(initial)
    }
    LaunchedEffect(loader, url, sizePx) {
        if (loader != null && url != null) {
            image.value = loader.load(url, sizePx)?.let { RemoteImageState.Success(it.asImageBitmap()) }
                ?: RemoteImageState.Unavailable
        }
    }
    return image
}
