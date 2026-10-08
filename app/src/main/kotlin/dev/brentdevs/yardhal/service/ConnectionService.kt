package dev.brentdevs.yardhal.service

import android.app.Service
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.brentdevs.yardhal.YardhalApplication
import dev.brentdevs.yardhal.coordinator.RecoveryPhase

public class ConnectionService : Service() {
    private var foregroundNetworkCount = 0
    private var foregroundNotification: Notification? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        val notification = Notifications.ongoing(this, foregroundNetworkCount)
        foregroundNotification = notification
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stopping = intent?.action == ACTION_STOP ||
            (intent == null && (application as? YardhalApplication)?.coordinator?.networks?.value
                ?.any { it.connectionPhase.keepsRecoveryService() } != true)
        val count = if (stopping) foregroundNetworkCount else intent?.getIntExtra(EXTRA_NETWORK_COUNT, 0) ?: 0
        val notification = if (count == foregroundNetworkCount) {
            requireNotNull(foregroundNotification)
        } else {
            Notifications.ongoing(this, count).also {
                foregroundNetworkCount = count
                foregroundNotification = it
            }
        }
        startForeground(NOTIFICATION_ID, notification)
        if (stopping) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    public companion object {
        public const val EXTRA_NETWORK_COUNT: String = "network_count"
        private const val NOTIFICATION_ID: Int = 1
        private const val ACTION_STOP = "dev.brentdevs.yardhal.STOP_CONNECTION_SERVICE"

        public fun start(context: Context, networkCount: Int) {
            val intent = Intent(context, ConnectionService::class.java)
                .putExtra(EXTRA_NETWORK_COUNT, networkCount)
            ContextCompat.startForegroundService(context, intent)
        }

        public fun stop(context: Context) {
            context.startService(Intent(context, ConnectionService::class.java).setAction(ACTION_STOP))
        }
    }
}

internal fun RecoveryPhase.keepsRecoveryService(): Boolean = when (this) {
    RecoveryPhase.CONNECTING, RecoveryPhase.REGISTERED, RecoveryPhase.IDENTIFYING,
    RecoveryPhase.OFFLINE, RecoveryPhase.SERVER_UNREACHABLE -> true
    RecoveryPhase.DISCONNECTED, RecoveryPhase.USER_DISCONNECTED,
    RecoveryPhase.AUTHENTICATION_REJECTED, RecoveryPhase.CERTIFICATE_REJECTED -> false
}
