package dev.codexpocket.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Keeps this application in the foreground while Codex runs on this phone, so its tasks are not ended in the background. */
class LocalRuntimeService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("local-runtime", getString(R.string.notification_channel_local_runtime), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, LocalRuntimeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "local-runtime").setSmallIcon(R.drawable.ic_pocket_notification)
            .setContentTitle(getString(R.string.notification_local_runtime_title)).setContentText(getString(R.string.notification_local_runtime_text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.notification_local_runtime_stop), stop).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(102, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Stopping from the notification ends Codex on this phone, not only the notification.
        if (intent?.action == ACTION_STOP) (application as PocketApplication).locations.stopThisPhone()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_STOP = "stop"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, LocalRuntimeService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, LocalRuntimeService::class.java)) }
    }
}
