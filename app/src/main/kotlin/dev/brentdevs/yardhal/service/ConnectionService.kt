package dev.brentdevs.yardhal.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat

public class ConnectionService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_NETWORK_COUNT, 0) ?: 0
        startForeground(NOTIFICATION_ID, Notifications.ongoing(this, count))
        if (count == 0 && intent?.getBooleanExtra(EXTRA_STOP, false) == true) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    public companion object {
        public const val EXTRA_NETWORK_COUNT: String = "network_count"
        public const val EXTRA_STOP: String = "stop"
        private const val NOTIFICATION_ID: Int = 1

        public fun start(context: Context, networkCount: Int) {
            val intent = Intent(context, ConnectionService::class.java)
                .putExtra(EXTRA_NETWORK_COUNT, networkCount)
            ContextCompat.startForegroundService(context, intent)
        }

        public fun stop(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
                .putExtra(EXTRA_STOP, true)
                .putExtra(EXTRA_NETWORK_COUNT, 0)
            context.startService(intent)
        }
    }
}

