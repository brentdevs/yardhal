package dev.brentdevs.yardhal.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.brentdevs.yardhal.MainActivity
import dev.brentdevs.yardhal.R

public object Notifications {

    public const val CHANNEL_ONGOING: String = "connection"
    public const val CHANNEL_MESSAGES: String = "messages"

    public const val ONGOING_NOTIFICATION_ID: Int = 1
    private var highlightId: Int = 100

    public fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                "Connection status",
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Highlights and messages",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    public fun ongoing(context: Context, networkCount: Int): Notification {
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text =
            if (networkCount == 1) "Connected to 1 network" else "Connected to $networkCount networks"
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle("Yardhal")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(intent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    public fun highlight(
        context: Context,
        networkName: String,
        sender: String,
        conversationName: String,
        text: String,
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle("$sender in $conversationName · $networkName")
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        manager.notify(highlightId++, notification)
    }
}
