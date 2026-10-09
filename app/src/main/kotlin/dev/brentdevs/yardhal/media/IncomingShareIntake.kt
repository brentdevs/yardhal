package dev.brentdevs.yardhal.media

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import java.util.Locale

public data class IncomingAttachmentShare(public val uris: List<Uri>, public val caption: String)

public object IncomingShareIntake {
    public fun fromIntent(intent: Intent): IncomingAttachmentShare? {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
        val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        val clip = intent.clipData
        val uris = (streams + if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }).distinct()
        if (uris.isEmpty()) return null
        return IncomingAttachmentShare(uris, intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
    }
}

public data class AttachmentUriDescription(public val name: String, public val mimeType: String, public val advertisedBytes: Long?)

public interface AttachmentUriAccess {
    public fun describe(uri: Uri): AttachmentUriDescription
    public fun open(uri: Uri): InputStream
}

public class AttachmentUriAccessException(message: String) : IOException(message)

public class AndroidAttachmentUriAccess(private val resolver: ContentResolver) : AttachmentUriAccess {
    override fun describe(uri: Uri): AttachmentUriDescription {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw AttachmentUriAccessException("Unsupported share URI. Select the file with the Android document picker instead.")
        var name = "attachment"
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
        val safeName = name.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.take(255).ifBlank { "attachment" }
        val mime = resolver.getType(uri)?.substringBefore(';')?.lowercase(Locale.ROOT)?.takeIf {
            it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))
        } ?: "application/octet-stream"
        return AttachmentUriDescription(safeName, mime, size)
    }

    override fun open(uri: Uri): InputStream = resolver.openInputStream(uri)
        ?: throw AttachmentUriAccessException("The shared file is unavailable. Select or share the file again.")
}
