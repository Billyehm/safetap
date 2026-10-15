package com.safetap

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Thin HTTP client for the SafeTap FastAPI backend. Plain HttpURLConnection
// on Dispatchers.IO, so there is one less dependency to go stale.
//
// Every endpoint here matches backend/app/main.py exactly. If the backend
// changes, this file is the one place to update.
object ApiClient {

    // Default backend. SafeTapApp sets this from the saved server address
    // on every launch, so the user can point at their own machine without
    // rebuilding. 10.0.2.2 is the emulator's alias for the host's localhost.
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"

    @Volatile
    var baseUrl: String = DEFAULT_BASE_URL

    private suspend fun request(
        method: String, path: String, body: JSONObject?, token: String?
    ): JSONObject = JSONObject(requestRaw(method, path, body, token).ifBlank { "{}" })

    private suspend fun requestRaw(
        method: String, path: String, body: JSONObject?, token: String?
    ): String = withContext(Dispatchers.IO) {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
            }
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw IOException("$method $path -> $code: $text")
            text
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun get(path: String, token: String? = null) =
        request("GET", path, null, token)

    private suspend fun post(path: String, body: JSONObject, token: String? = null) =
        request("POST", path, body, token)

    private suspend fun put(path: String, body: JSONObject, token: String) =
        request("PUT", path, body, token)

    private suspend fun delete(path: String, token: String) =
        request("DELETE", path, null, token)

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun healthCheck(): Boolean = try {
        get("/api/health").optBoolean("ok", false)
    } catch (e: Exception) {
        false
    }

    // ---- auth ----

    suspend fun register(phone: String, name: String, pin: String): JSONObject =
        post("/api/auth/register",
            JSONObject().put("phone", phone).put("name", name).put("pin", pin))

    suspend fun login(phone: String, pin: String): JSONObject =
        post("/api/auth/login", JSONObject().put("phone", phone).put("pin", pin))

    // ---- medical profile (encrypted server-side, shown on the tracking page) ----

    suspend fun getMedical(token: String): JSONObject =
        get("/api/me/medical", token)

    suspend fun saveMedical(token: String, body: JSONObject) {
        put("/api/me/medical", body, token)
    }

    // ---- contacts (the backend fans out to these on SOS) ----

    suspend fun getContacts(token: String): JSONArray =
        JSONArray(requestRaw("GET", "/api/contacts", null, token).ifBlank { "[]" })

    suspend fun addContact(token: String, name: String, phone: String): String =
        post("/api/contacts",
            JSONObject().put("name", name).put("phone", phone), token)
            .getString("id")

    suspend fun deleteContact(token: String, id: String) {
        delete("/api/contacts/$id", token)
    }

    // ---- alerts ----

    // Fires a new alert. type is "panic" or "checkin_missed". Returns the alert id.
    suspend fun triggerAlert(
        token: String, lat: Double, lng: Double, battery: Int, type: String
    ): String {
        val res = post(
            "/api/alerts",
            JSONObject()
                .put("type", type)
                .put("lat", lat)
                .put("lng", lng)
                .put("battery", battery),
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

    // The user's currently active alert, if any. Used to attach to a
    // check-in SOS the server fired on its own.
    suspend fun getActiveAlert(token: String): JSONObject =
        get("/api/alerts/active", token)

    // "FALSE ALARM, I'M FINE." The PIN goes to the server for verification.
    suspend fun falseAlarm(token: String, alertId: String, pin: String) {
        post("/api/alerts/$alertId/false-alarm", JSONObject().put("pin", pin), token)
    }

    suspend fun resolveAlert(token: String, alertId: String) {
        post("/api/alerts/$alertId/resolve", JSONObject(), token)
    }

    // ---- check-in timers (the server owns the escalation) ----

    // checkinAtIso: "2026-10-15T22:30:00". Returns {"id", "sos_fires_at"}.
    suspend fun createCheckin(token: String, checkinAtIso: String, note: String): JSONObject =
        post("/api/checkins",
            JSONObject().put("checkin_at", checkinAtIso).put("note", note), token)

    suspend fun getActiveCheckin(token: String): JSONObject =
        get("/api/checkins/active", token)

    // "I'm fine." Disarms the timer.
    suspend fun confirmCheckin(token: String, checkinId: String) {
        post("/api/checkins/$checkinId/confirm", JSONObject(), token)
    }
}
