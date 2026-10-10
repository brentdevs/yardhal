package dev.brentdevs.yardhal.ui.image

import dev.brentdevs.yardhal.core.data.MediaRevealState
import java.net.URI

public enum class ImageCacheCategory { MEDIA, AVATAR }
public enum class MediaKind { IMAGE, VIDEO, FILE, UNKNOWN }

public data class MediaDescriptor(
    public val url: String,
    public val name: String? = null,
    public val mimeType: String? = null,
    public val sizeBytes: Long? = null,
    public val kind: MediaKind = MediaPolicy.kind(url, mimeType),
)

public object MediaPolicy {
    public const val MAX_IMAGE_BYTES: Int = 16 * 1024 * 1024
    public const val MAX_VIDEO_BYTES: Int = 64 * 1024 * 1024
    public const val MAX_DIMENSION: Int = 8192
    public const val MAX_PIXELS: Long = 32L * 1024 * 1024
    public const val MAX_REDIRECTS: Int = 4
    public const val PROBE_BYTES: Int = 1024
    public const val MAX_LINKS_PER_MESSAGE: Int = 4
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "heic", "heif")
    private val videoExtensions = setOf("mp4", "webm", "m4v", "3gp", "mov")
    private val imageTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/bmp", "image/x-ms-bmp", "image/heic", "image/heif")
    private val videoTypes = setOf("video/mp4", "video/webm", "video/3gpp", "video/quicktime")
    private val linkPattern = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)

    public fun displayName(descriptor: MediaDescriptor): String =
        descriptor.name?.takeIf { it.isNotBlank() }
            ?: descriptor.url.substringBefore('?').substringBefore('#').substringAfterLast('/').ifBlank { "Linked file" }

    public fun mime(raw: String?): String? = raw?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    public fun isImageMime(raw: String?): Boolean = mime(raw) in imageTypes
    public fun isVideoMime(raw: String?): Boolean = mime(raw) in videoTypes

    public fun kind(url: String, mimeType: String?): MediaKind {
        if (!mimeType.isNullOrBlank()) return when {
            isImageMime(mimeType) -> MediaKind.IMAGE
            isVideoMime(mimeType) -> MediaKind.VIDEO
            else -> MediaKind.FILE
        }
        val extension = runCatching { URI(url).path }.getOrNull().orEmpty().substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when (extension) {
            in imageExtensions -> MediaKind.IMAGE
            in videoExtensions -> MediaKind.VIDEO
            "" -> MediaKind.UNKNOWN
            else -> MediaKind.UNKNOWN
        }
    }

    public fun linkedMedia(text: String, attachmentUrl: String?): List<MediaDescriptor> =
        linkPattern.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', ')', ']', '}') }
            .filter { it != attachmentUrl && ImageUrlPolicy.isAllowed(it) }.distinct()
            .take(MAX_LINKS_PER_MESSAGE).map { MediaDescriptor(it) }.toList()

    public fun dimensionsAllowed(width: Int, height: Int): Boolean =
        width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION && width.toLong() * height <= MAX_PIXELS

    public fun targetDimensions(width: Int, height: Int, targetPx: Int): Pair<Int, Int> {
        val scale = maxOf(width, height).toDouble() / targetPx.coerceIn(1, MAX_DIMENSION)
        return if (scale <= 1) width to height else maxOf(1, (width / scale).toInt()) to maxOf(1, (height / scale).toInt())
    }

    public fun mayLoad(autoLoad: Boolean, reveal: MediaRevealState, visible: Boolean, active: Boolean): Boolean =
        visible && active && reveal != MediaRevealState.HIDDEN && (autoLoad || reveal == MediaRevealState.REVEALED)

    public fun mayAnimate(enabled: Boolean, reducedMotion: Boolean, visible: Boolean, active: Boolean): Boolean =
        enabled && !reducedMotion && visible && active

    public fun redirect(url: String, location: String?): String? = location?.let {
        runCatching { URI(url).resolve(it).toString() }.getOrNull()?.let { target -> ImageUrlPolicy.resolve(target, 1) }
    }
}
