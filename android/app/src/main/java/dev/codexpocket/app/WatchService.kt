package dev.codexpocket.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.codexpocket.app.data.PocketState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = (application as PocketApplication).repository
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("watch", getString(R.string.notification_channel_connection), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("activity", getString(R.string.notification_channel_activity), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WatchService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun watchNotification(text: String) = NotificationCompat.Builder(this, "watch").setSmallIcon(R.drawable.ic_pocket_notification).setContentTitle(getString(R.string.notification_watch_title)).setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).addAction(0, getString(R.string.notification_watch_stop), stop).build()
        val notification = watchNotification(getString(watchStatusResource(repository.state.value)))
        if (Build.VERSION.SDK_INT >= 29) startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(101, notification)
        repository.setWatch(true)
        scope.launch {
            repository.state.map(::watchStatusResource).distinctUntilChanged().collect { status ->
                if (manager.areNotificationsEnabled()) manager.notify(101, watchNotification(getString(status)))
            }
        }
        scope.launch {
            repository.conversationAlerts.collect { alert ->
                val id = (alert.key.encoded + alert.kind).hashCode()
                val target = PendingIntent.getActivity(this@WatchService, id, Intent(this@WatchService, MainActivity::class.java).putExtra("pocketChat", alert.key.encoded), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                if (manager.areNotificationsEnabled()) manager.notify(id, NotificationCompat.Builder(this@WatchService, "activity").setSmallIcon(R.drawable.ic_pocket_notification).setContentTitle(alert.title).setContentText(getString(R.string.notification_review)).setContentIntent(target).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (intent?.action == "stop") stopSelf(); return START_NOT_STICKY }
    override fun onDestroy() { repository.setWatch(false); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

internal fun watchStatusResource(state: PocketState): Int = when {
    !state.paired -> R.string.delivery_watch_unpaired
    state.online && state.runtimes.none { it.online } -> R.string.delivery_watch_no_runtime
    state.online -> R.string.delivery_watch_connected
    state.connection.equals("Connecting", ignoreCase = true) -> R.string.delivery_watch_connecting
    state.connection.equals("Reconnecting", ignoreCase = true) -> R.string.delivery_watch_reconnecting
    else -> R.string.delivery_watch_offline
}
