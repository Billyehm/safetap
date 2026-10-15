package com.safetap

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

// Everything the app needs to remember lives here, encrypted at rest:
// the auth token, the PIN hash, emergency contacts, and the medical profile.
data class Contact(val name: String, val phone: String)

data class MedicalProfile(
    val bloodType: String = "",
    val allergies: String = "",
    val conditions: String = "",
    val medications: String = "",
    val doctorNumber: String = "",
    val note: String = ""
)

class Prefs(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val store = EncryptedSharedPreferences.create(
        context,
        "safetap_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var token: String?
        get() = store.getString("token", null)
        set(v) = store.edit().putString("token", v).apply()

    var userName: String
        get() = store.getString("user_name", "") ?: ""
        set(v) = store.edit().putString("user_name", v).apply()

    var userPhone: String
        get() = store.getString("user_phone", "") ?: ""
        set(v) = store.edit().putString("user_phone", v).apply()

    // Backend address, changeable in the Safety tab so the user can point
    // at their own machine without rebuilding the app.
    var serverUrl: String
        get() = (store.getString("server_url", null) ?: ApiClient.DEFAULT_BASE_URL)
            .trim().trimEnd('/')
        set(v) = store.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    // The login PIN, kept encrypted. Needed because the false-alarm call
    // sends the PIN to the server for verification (biometric alone cannot
    // recover it).
    var pin: String?
        get() = store.getString("pin_plain", null)
        set(v) = store.edit().putString("pin_plain", v).apply()

    fun savePinHash(pin: String) {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray())
            .joinToString("") { "%02x".format(it) }
        store.edit().putString("pin_hash", hash).apply()
    }

    fun checkPin(pin: String): Boolean {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return hash == store.getString("pin_hash", "")
    }

    val hasPin: Boolean get() = store.contains("pin_hash")

    // Free tier: at most 2 emergency contacts.
    fun getContacts(): List<Contact> {
        val raw = store.getString("contacts", "[]") ?: "[]"
        val out = mutableListOf<Contact>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Contact(o.optString("name"), o.optString("phone")))
        }
        return out
    }

    fun saveContacts(contacts: List<Contact>) {
        val arr = JSONArray()
        contacts.take(MAX_FREE_CONTACTS).forEach {
            arr.put(JSONObject().put("name", it.name).put("phone", it.phone))
        }
        store.edit().putString("contacts", arr.toString()).apply()
    }

    fun getMedical(): MedicalProfile {
        return MedicalProfile(
            bloodType = store.getString("med_blood", "") ?: "",
            allergies = store.getString("med_allergies", "") ?: "",
            conditions = store.getString("med_conditions", "") ?: "",
            medications = store.getString("med_meds", "") ?: "",
            doctorNumber = store.getString("med_doctor", "") ?: "",
            note = store.getString("med_note", "") ?: ""
        )
    }

    fun saveMedical(m: MedicalProfile) {
        store.edit()
            .putString("med_blood", m.bloodType)
            .putString("med_allergies", m.allergies)
            .putString("med_conditions", m.conditions)
            .putString("med_meds", m.medications)
            .putString("med_doctor", m.doctorNumber)
            .putString("med_note", m.note)
            .apply()
    }

    var activeAlertId: String?
        get() = store.getString("active_alert", null)
        set(v) = store.edit().putString("active_alert", v).apply()

    var activeCheckinId: String?
        get() = store.getString("active_checkin", null)
        set(v) = store.edit().putString("active_checkin", v).apply()

    // Simple abuse guard: max 7 alerts per day, then a cooldown.
    fun canTriggerAlert(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val day = store.getString("alert_day", "")
        val count = if (day == today) store.getInt("alert_count", 0) else 0
        return count < MAX_ALERTS_PER_DAY
    }

    fun recordAlert() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val day = store.getString("alert_day", "")
        val count = if (day == today) store.getInt("alert_count", 0) else 0
        store.edit()
            .putString("alert_day", today)
            .putInt("alert_count", count + 1)
            .apply()
    }

    var lastLat: Double
        get() = store.getString("last_lat", "0.0")!!.toDouble()
        set(v) = store.edit().putString("last_lat", v.toString()).apply()

    var lastLng: Double
        get() = store.getString("last_lng", "0.0")!!.toDouble()
        set(v) = store.edit().putString("last_lng", v.toString()).apply()

    val hasLastFix: Boolean get() = store.contains("last_lat")

    companion object {
        const val MAX_FREE_CONTACTS = 2
        const val MAX_ALERTS_PER_DAY = 7
    }
}
