package io.github.nanomuse.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the phone on the hub while the app is in the background: a quiet foreground service
 * whose notification says whether the phone is reachable and how many devices are around.
 * Started by [Hub.start], stopped by [Hub.stop]; type `remoteMessaging`, which is what it is.
 */
class HubService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Hub.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notif = build(Hub.connected.value, Hub.detail.value, Hub.others(this).count { it.online })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "startForeground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        registerNetworkCallback()
        if (watch == null) {
            watch = scope.launch {
                combine(Hub.connected, Hub.detail, Hub.devices) { on, detail, list ->
                    Triple(on, detail, list.count { it.online && it.kind != "web" && it.id != Hub.deviceId(this@HubService) })
                }.collect { (on, detail, n) ->
                    notificationManager().notify(NOTIFICATION_ID, build(on, detail, n))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        scope.cancel()
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!Hub.isConnected) {
                    AppLogger.info(TAG, "network became available; reconnecting hub")
                    Hub.reconnect(resetBackoff = true)
                }
            }
        }
        netCallback = callback
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    private fun unregisterNetworkCallback() {
        netCallback?.let { cb ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { cm?.unregisterNetworkCallback(cb) }
            netCallback = null
        }
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun build(connected: Boolean, detail: String, others: Int): Notification {
        val nm = notificationManager()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.nm_hub_service_channel), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HubService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            !connected -> when (detail) {
                "bad_key", "bad_device" -> getString(R.string.nm_devices_refused)
                "hub_paused" -> getString(R.string.nm_devices_paused)
                else -> getString(R.string.nm_hub_service_connecting)
            }
            others == 0 -> getString(R.string.nm_hub_service_alone)
            else -> resources.getQuantityString(R.plurals.nm_hub_service_devices, others, others)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nanomuse)
            .setContentTitle(getString(R.string.nm_hub_service_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply { if (open != null) setContentIntent(open) }
            .addAction(0, getString(R.string.nm_hub_service_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "HubService"
        private const val CHANNEL = "nanomuse_hub_service"
        private const val NOTIFICATION_ID = 9107
        private const val ACTION_STOP = "io.github.nanomuse.hub.STOP"

        fun start(context: Context) {
            val intent = Intent(context, HubService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                // Background start restrictions: the socket still runs in-process while the app lives.
                AppLogger.warning(TAG, "could not start: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HubService::class.java))
        }
    }
}
