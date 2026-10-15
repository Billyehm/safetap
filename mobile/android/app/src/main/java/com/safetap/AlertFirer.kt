package com.safetap

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// One place that knows how to fire an alert, used by both the panic SOS
// flow (SosActivity) and the missed check-in escalation (CheckInReceiver).
//
// Online: POST /api/alerts/trigger, then start the foreground service that
// streams GPS every 15 seconds.
// Offline: send a plain SMS straight to each contact with the last known
// coordinates and a Google Maps link, then start the service anyway so it
// can sync with the backend when data comes back.
object AlertFirer {

    sealed interface FireResult {
        data class Online(val alertId: String) : FireResult
        data class OfflineSms(val localId: String, val sentTo: Int) : FireResult
        data object RateLimited : FireResult
        data class Failed(val reason: String) : FireResult
    }

    suspend fun fire(context: Context, type: String): FireResult {
        val prefs = Prefs(context)
        val token = prefs.token

        if (!prefs.canTriggerAlert()) return FireResult.RateLimited
        if (token == null) return FireResult.Failed("Not signed in. Open the app and log in first.")

        // Best-effort fresh fix, with a short timeout so a panicking user
        // is never left staring at a spinner. Fall back to last known.
        val fix = freshFix(context) ?: lastKnown(prefs)
        val lat = fix?.first ?: 0.0
        val lng = fix?.second ?: 0.0
        val battery = batteryPct(context)

        prefs.recordAlert()

        return if (ApiClient.isOnline(context)) {
            try {
                val alertId = ApiClient.triggerAlert(token, lat, lng, battery, type)
                prefs.activeAlertId = alertId
                startStreamingService(context, alertId)
                FireResult.Online(alertId)
            } catch (e: Exception) {
                // The network said yes but the request failed: fall back to SMS.
                FireResult.OfflineSms(sendSmsFallback(context, prefs, lat, lng), 0)
            }
        } else {
            val localId = sendSmsFallback(context, prefs, lat, lng)
            FireResult.OfflineSms(localId, prefs.getContacts().size)
        }
    }

    private suspend fun freshFix(context: Context, timeoutMs: Long = 8000): Pair<Double, Double>? {
        return try {
            val fused = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()
            val loc: android.location.Location? = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                        .addOnSuccessListener { l -> cont.resume(l) }
                        .addOnFailureListener { e -> cont.resumeWithException(e) }
                }
            }
            if (loc != null) {
                val prefs = Prefs(context)
                prefs.lastLat = loc.latitude
                prefs.lastLng = loc.longitude
                loc.latitude to loc.longitude
            } else null
        } catch (e: SecurityException) {
            // Location permission not granted yet.
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun lastKnown(prefs: Prefs): Pair<Double, Double>? =
        if (prefs.hasLastFix) prefs.lastLat to prefs.lastLng else null

    fun batteryPct(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    // Sends the no-data SOS as plain text messages, one per contact, and
    // starts the streaming service with a local id so it can sync later.
    private fun sendSmsFallback(
        context: Context, prefs: Prefs, lat: Double, lng: Double
    ): String {
        val localId = "local-${System.currentTimeMillis()}"
        val name = prefs.userName.ifBlank { "Someone" }
        val body = "SAFETAP SOS: $name needs help NOW. " +
            "Last known location: $lat,$lng " +
            "https://maps.google.com/?q=$lat,$lng " +
            "Call them immediately."
        val sms = context.getSystemService(SmsManager::class.java)
        prefs.getContacts().forEach { c ->
            try {
                val parts = sms.divideMessage(body)
                sms.sendMultipartTextMessage(c.phone, null, parts, null, null)
            } catch (e: Exception) {
                // One failed contact must not block the others.
            }
        }
        prefs.activeAlertId = localId
        startStreamingService(context, localId)
        return localId
    }

    fun startStreamingService(context: Context, alertId: String) {
        val intent = Intent(context, SosForegroundService::class.java).apply {
            action = SosForegroundService.ACTION_START
            putExtra(SosForegroundService.EXTRA_ALERT_ID, alertId)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    // Start the service without an alert id. It polls the server until the
    // check-in SOS appears, then streams to it. Used when the phone was
    // offline (or the watchdog had not ticked yet) at escalation time.
    fun startAttachService(context: Context) {
        val intent = Intent(context, SosForegroundService::class.java).apply {
            action = SosForegroundService.ACTION_ATTACH
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopStreamingService(context: Context) {
        val intent = Intent(context, SosForegroundService::class.java).apply {
            action = SosForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }
}
