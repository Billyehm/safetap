package com.safetap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Runs while an alert is live. Every 15 seconds it grabs a GPS fix and
// POSTs it to /api/alerts/{id}/location with the battery level. It also
// polls the alert status each round and stops itself the moment the alert
// is RESOLVED or marked FALSE_ALARM.
//
// If the alert fired offline (a "local-..." id), the service keeps trying
// to register it with the backend; once that succeeds it streams against
// the real id and the contacts' tracking page comes alive.
class SosForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var alertId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                alertId = intent.getStringExtra(EXTRA_ALERT_ID)
                startAsForeground()
                startLoop()
            }
            ACTION_ATTACH -> {
                alertId = null // attach mode: find the alert first
                startAsForeground()
                startLoop()
            }
            ACTION_STOP -> stopEverything()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Active SOS alert",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SOS active")
            .setContentText("Sharing your location with your emergency contacts.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            val prefs = Prefs(this@SosForegroundService)
            while (isActive) {
                try {
                    tick(prefs)
                } catch (e: Exception) {
                    // One bad round must not kill the stream.
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private suspend fun tick(prefs: Prefs) {
        var id = alertId
        val token = prefs.token ?: return

        // Attach mode: the server fired a check-in SOS on its own and we do
        // not know the id yet. Poll until it appears.
        if (id == null) {
            try {
                val active = ApiClient.getActiveAlert(token)
                if (active.optBoolean("active", false)) {
                    id = active.getString("id")
                    alertId = id
                    prefs.activeAlertId = id
                } else {
                    return // try again next round
                }
            } catch (e: Exception) {
                return // offline or server hiccup; try again next round
            }
        }

        val fix: Location? = withTimeoutOrNull(10_000) {
            val fused = LocationServices.getFusedLocationProviderClient(this@SosForegroundService)
            suspendCancellableCoroutine { cont ->
                fused.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    CancellationTokenSource().token
                ).addOnSuccessListener { loc -> cont.resume(loc) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
        }
        val lat = fix?.latitude ?: prefs.lastLat
        val lng = fix?.longitude ?: prefs.lastLng
        if (fix != null) {
            prefs.lastLat = lat
            prefs.lastLng = lng
        }
        val battery = AlertFirer.batteryPct(this@SosForegroundService)

        if (!ApiClient.isOnline(this@SosForegroundService)) return // try again next round

        // Offline-fired alert: register it now so the tracking page wakes up.
        if (id.startsWith("local-")) {
            id = ApiClient.triggerAlert(token, lat, lng, battery, "panic")
            alertId = id
            prefs.activeAlertId = id
        }

        ApiClient.postLocation(token, id, lat, lng, battery)

        val status = ApiClient.getAlertStatus(token, id)
        if (status == "RESOLVED" || status == "FALSE_ALARM") {
            prefs.activeAlertId = null
            stopEverything()
        }
    }

    private fun stopEverything() {
        loopJob?.cancel()
        loopJob = null
        alertId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.safetap.SERVICE_START"
        const val ACTION_ATTACH = "com.safetap.SERVICE_ATTACH"
        const val ACTION_STOP = "com.safetap.SERVICE_STOP"
        const val EXTRA_ALERT_ID = "alert_id"

        private const val CHANNEL_ID = "safetap_alert"
        private const val NOTIF_ID = 1001
        private const val PING_INTERVAL_MS = 15_000L
    }
}
