package com.safetap

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Owns the check-in timer lifecycle:
//
//   widget tap -> POST /api/checkins, arm an exact alarm at check-in time
//   alarm fires -> full-screen CheckInActivity, loud buzz, 10-minute grace
//   grace expires -> the SERVER has fired the SOS by now (its watchdog runs
//     on checkin_at + 10 minutes). The phone attaches to that alert and
//     starts streaming location to it. The phone never fires a second SOS,
//     so contacts are never notified twice.
//
// If the phone is offline at grace end, the server still notified the
// contacts. The streaming service keeps polling until it finds the alert.
class CheckInReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_CHECKIN -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        startCheckin(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_CHECKIN_EXPIRED -> onExpired(context)
            ACTION_CHECKIN_ESCALATE -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        onEscalate(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_CANCEL_CHECKIN -> cancelAll(context)
        }
    }

    private suspend fun startCheckin(context: Context) {
        val prefs = Prefs(context)
        val token = prefs.token
        if (token == null) {
            toastOnMain(context, "Log in to SafeTap first, then start a check-in.")
            return
        }
        if (prefs.activeCheckinId != null) {
            toastOnMain(context, "A check-in timer is already running.")
            return
        }
        try {
            val at = System.currentTimeMillis() + DEFAULT_CHECKIN_MINUTES * 60_000L
            val iso = isoUtc(at)
            val res = ApiClient.createCheckin(token, iso, "Started from the widget")
            val id = res.getString("id")
            prefs.activeCheckinId = id
            scheduleAlarm(context, ACTION_CHECKIN_EXPIRED, at)
            toastOnMain(
                context,
                "Check-in started. We will ask if you are okay in $DEFAULT_CHECKIN_MINUTES minutes."
            )
        } catch (e: Exception) {
            toastOnMain(context, "Could not reach the server. Check-in not started.")
        }
    }

    private fun onExpired(context: Context) {
        val prefs = Prefs(context)
        if (prefs.activeCheckinId == null) return // already cancelled
        // Open the loud full-screen prompt.
        val open = Intent(context, CheckInActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(open)
        // If they ignore it for 10 minutes, the server fires the SOS.
        scheduleAlarm(
            context, ACTION_CHECKIN_ESCALATE,
            System.currentTimeMillis() + ESCALATE_AFTER_MINUTES * 60_000L
        )
    }

    private suspend fun onEscalate(context: Context) {
        val prefs = Prefs(context)
        if (prefs.activeCheckinId == null) return
        prefs.activeCheckinId = null
        val token = prefs.token
        if (token == null) {
            toastOnMain(context, "Check-in missed.")
            return
        }
        // The server should have fired the SOS by now. Attach to it; if it
        // is not there yet (or we are offline), the service keeps polling.
        try {
            val active = ApiClient.getActiveAlert(token)
            if (active.optBoolean("active", false)) {
                val alertId = active.getString("id")
                prefs.activeAlertId = alertId
                AlertFirer.startStreamingService(context, alertId)
                toastOnMain(context, "Check-in missed. SOS fired, sharing your location.")
                return
            }
        } catch (e: Exception) {
            // Fall through to attach mode below.
        }
        AlertFirer.startAttachService(context)
        toastOnMain(context, "Check-in missed. SOS fired, sharing your location.")
    }

    private fun scheduleAlarm(context: Context, action: String, atMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, CheckInReceiver::class.java).apply { this.action = action }
        val pi = PendingIntent.getBroadcast(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // No exact-alarm permission: fall back to an inexact alarm. The
            // 10-minute grace makes a few minutes of drift acceptable.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    fun cancelAlarm(context: Context, action: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, CheckInReceiver::class.java).apply { this.action = action }
        val pi = PendingIntent.getBroadcast(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    private fun cancelAll(context: Context) {
        cancelAlarm(context, ACTION_CHECKIN_EXPIRED)
        cancelAlarm(context, ACTION_CHECKIN_ESCALATE)
        Prefs(context).activeCheckinId = null
    }

    private fun toastOnMain(context: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION_START_CHECKIN = "com.safetap.START_CHECKIN"
        const val ACTION_CHECKIN_EXPIRED = "com.safetap.CHECKIN_EXPIRED"
        const val ACTION_CHECKIN_ESCALATE = "com.safetap.CHECKIN_ESCALATE"
        const val ACTION_CANCEL_CHECKIN = "com.safetap.CANCEL_CHECKIN"

        // One-tap default. A duration picker can replace this later.
        const val DEFAULT_CHECKIN_MINUTES = 60
        const val ESCALATE_AFTER_MINUTES = 10

        fun isoUtc(millis: Long): String {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date(millis))
        }
    }
}
