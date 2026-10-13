package com.safetap

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Thin HTTP client for the FastAPI backend. Plain HttpURLConnection on
// Dispatchers.IO, so there is one less dependency to go stale.
object ApiClient {

    // THE backend URL. 10.0.2.2 is the emulator's alias for the host
    // machine's localhost. On a real phone, replace this with the dev
    // machine's LAN IP (e.g. http://192.168.1.40:8000).
    const val BASE_URL = "http://10.0.2.2:8000"

    private suspend fun post(path: String, body: JSONObject, token: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) throw IOException("POST $path -> $code: $text")
                JSONObject(text.ifBlank { "{}" })
            } finally {
                conn.disconnect()
            }
        }

    private suspend fun get(path: String, token: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) throw IOException("GET $path -> $code: $text")
                JSONObject(text.ifBlank { "{}" })
            } finally {
                conn.disconnect()
            }
        }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun login(phone: String, pin: String): JSONObject =
        post("/api/auth/login", JSONObject().put("phone", phone).put("pin", pin), null)

    // Fires a new alert. type is "panic" or "checkin_missed". Returns the alert id.
    suspend fun triggerAlert(
        token: String, lat: Double, lng: Double, battery: Int, type: String
    ): String {
        val res = post(
            "/api/alerts/trigger",
            JSONObject()
                .put("lat", lat)
                .put("lng", lng)
                .put("battery", battery)
                .put("type", type),
            token
        )
        return res.getString("id")
    }

    suspend fun postLocation(token: String, alertId: String, lat: Double, lng: Double, battery: Int) {
        post(
            "/api/alerts/$alertId/location",
            JSONObject().put("lat", lat).put("lng", lng).put("battery", battery),
            token
        )
    }

    suspend fun getAlertStatus(token: String, alertId: String): String =
        get("/api/alerts/$alertId", token).optString("status", "")

    suspend fun resolveAlert(token: String, alertId: String, falseAlarm: Boolean) {
        post(
            "/api/alerts/$alertId/resolve",
            JSONObject().put("false_alarm", falseAlarm),
            token
        )
    }

    suspend fun createCheckin(token: String, durationMinutes: Int): String =
        post(
            "/api/checkins",
            JSONObject().put("duration_minutes", durationMinutes),
            token
        ).getString("id")

    suspend fun cancelCheckin(token: String, checkinId: String) {
        post("/api/checkins/$checkinId/cancel", JSONObject(), token)
    }
}
