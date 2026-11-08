package io.github.androidconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process (and therefore the connection) alive while connected,
 * holds a low-latency Wi-Fi lock, and shows an ongoing notification.
 */
class DisplayService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var wifiLock: WifiManager.WifiLock? = null

    /** A second screen that is switched off is no use: disconnect so neither device stays awake. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) App.instance.link.disconnect()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Second screen connection", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Connecting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 34) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                   else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "androidconnect").apply {
            setReferenceCounted(false)
            acquire()
        }

        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))

        val link = App.instance.link
        scope.launch {
            link.state.collect { s ->
                when (s) {
                    is LinkState.Idle, is LinkState.Failed -> stopSelf()
                    is LinkState.Streaming -> update("Extending the screen of ${s.name}")
                    is LinkState.Reconnecting -> update("Reconnecting to ${s.name}…")
                    is LinkState.Pairing -> update("Pairing with ${s.name}…")
                    is LinkState.Connecting -> update("Connecting to ${s.name}…")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) App.instance.link.disconnect()
        return START_NOT_STICKY
    }

    /** The app was swiped away from Recents. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        App.instance.link.disconnect()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        scope.cancel()
        wifiLock?.release()
        super.onDestroy()
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 1,
            Intent(this, DisplayService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Android Connect")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "disconnect"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DisplayService::class.java))
        }
    }
}
