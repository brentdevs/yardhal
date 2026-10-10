package dev.brentdevs.yardhal.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import dev.brentdevs.yardhal.MainActivity
import dev.brentdevs.yardhal.R
import dev.brentdevs.yardhal.core.data.NotificationKind
import dev.brentdevs.yardhal.core.data.NotificationKindPreferences
import dev.brentdevs.yardhal.core.data.NotificationPreferences
import dev.brentdevs.yardhal.core.data.NotificationPriority

public object Notifications {
    public const val CHANNEL_ONGOING: String = "connection"
    public const val ONGOING_NOTIFICATION_ID: Int = 1
    private const val EVENT_NOTIFICATION_ID = 2

    public fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Connection status", NotificationManager.IMPORTANCE_MIN))
    }

    public fun channelId(kind: NotificationKind, settings: NotificationKindPreferences): String =
        "events-v1-${kind.name.lowercase(java.util.Locale.ROOT)}-${settings.priority.name.lowercase(java.util.Locale.ROOT)}-${if (effectiveSound(settings)) "sound" else "silent"}"

    public fun ensureEventChannel(context: Context, kind: NotificationKind, settings: NotificationKindPreferences): NotificationChannel {
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = channelId(kind, settings)
        manager.getNotificationChannel(id)?.let { return it }
        val importance = when (settings.priority) {
            NotificationPriority.QUIET -> NotificationManager.IMPORTANCE_LOW
            NotificationPriority.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
            NotificationPriority.HIGH -> NotificationManager.IMPORTANCE_HIGH
        }
        val channel = NotificationChannel(id, "${kindLabel(kind)} · ${settings.priority.name.lowercase(java.util.Locale.ROOT)} · ${if (effectiveSound(settings)) "sound" else "silent"}", importance)
        channel.description = "Yardhal ${kindLabel(kind).lowercase(java.util.Locale.ROOT)}. Android controls this channel; changing Yardhal sound or priority selects a different channel."
        channel.setSound(if (effectiveSound(settings)) Settings.System.DEFAULT_NOTIFICATION_URI else null,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        channel.enableVibration(settings.priority != NotificationPriority.QUIET && settings.sound)
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        manager.createNotificationChannel(channel)
        return requireNotNull(manager.getNotificationChannel(id))
    }

    public fun settingsIntent(context: Context, kind: NotificationKind? = null, preferences: NotificationPreferences = NotificationPreferences()): Intent {
        if (kind == null) return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        val channel = ensureEventChannel(context, kind, preferences.forKind(kind))
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel.id)
    }

    public fun permissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    public fun ongoing(context: Context, networkCount: Int): Notification {
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when (networkCount) {
            0 -> "No networks connected"
            1 -> "Connected to 1 network"
            else -> "Connected to $networkCount networks"
        }
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle("Yardhal")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(intent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    public fun pendingIntent(context: Context, route: NotificationRoute): PendingIntent = PendingIntent.getActivity(
        context, 0, NotificationRoutes.intent(context, route), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    public fun post(
        context: Context,
        event: NotificationEvent,
        eligibility: NotificationEligibility,
        preferences: NotificationPreferences,
        avatar: Bitmap? = null,
    ): Boolean {
        val decision = NotificationPolicy.decide(event, eligibility, preferences, permissionGranted(context))
        if (decision !is NotificationDecision.Show) return false
        val channel = ensureEventChannel(context, event.kind, decision.settings)
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        val route = NotificationRoutes.fromEvent(event)
        val title = "${event.ref.rawTarget} · ${event.networkName}"
        val person = Person.Builder().setName(event.sender).setKey("${event.ref.networkId}|${event.sender}")
        val presentationAvatar = if (preferences.showAvatars) avatar ?: initialAvatar(event.sender) else null
        if (presentationAvatar != null) person.setIcon(IconCompat.createWithBitmap(presentationAvatar))
        val sender = person.build()
        val content = if (preferences.showPreview) {
            if (event.kind == NotificationKind.INVITE) "${event.sender} invited you to ${event.ref.rawTarget}" else event.text
        } else {
            if (event.kind == NotificationKind.INVITE) "Channel invitation" else "New ${kindLabel(event.kind).lowercase(java.util.Locale.ROOT)}"
        }
        val builder = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(title)
            .setContentText("${event.sender}: $content")
            .setWhen(event.timestampMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent(context, route))
            .setCategory(if (event.kind == NotificationKind.INVITE) Notification.CATEGORY_EVENT else Notification.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup("conversation:${event.ref.storageKey}")
        if (presentationAvatar != null) builder.setLargeIcon(presentationAvatar)
        if (event.kind == NotificationKind.INVITE) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(content))
        } else {
            builder.setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
                .setConversationTitle(title)
                .setGroupConversation(event.kind == NotificationKind.MENTION)
                .addMessage(content, event.timestampMs, sender))
        }
        return try {
            context.getSystemService(NotificationManager::class.java).notify(NotificationRoutes.identity(route), EVENT_NOTIFICATION_ID, builder.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    public fun cancelConversation(context: Context, ref: dev.brentdevs.yardhal.core.data.ConversationRef) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val group = "conversation:${ref.storageKey}"
        for (posted in manager.activeNotifications) {
            if (posted.id == EVENT_NOTIFICATION_ID && posted.notification.group == group) manager.cancel(posted.tag, posted.id)
        }
    }

    public fun kindLabel(kind: NotificationKind): String = when (kind) {
        NotificationKind.MENTION -> "Mentions"
        NotificationKind.DIRECT_MESSAGE -> "Direct messages"
        NotificationKind.INVITE -> "Invitations"
    }

    private fun effectiveSound(settings: NotificationKindPreferences): Boolean = settings.sound && settings.priority != NotificationPriority.QUIET

    private fun initialAvatar(sender: String): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(48, 70, 94)
        canvas.drawCircle(48f, 48f, 48f, paint)
        paint.color = Color.WHITE
        paint.textSize = 48f
        paint.textAlign = Paint.Align.CENTER
        val initial = sender.take(1).uppercase(java.util.Locale.ROOT)
        canvas.drawText(initial, 48f, 48f - (paint.ascent() + paint.descent()) / 2f, paint)
        return bitmap
    }
}
