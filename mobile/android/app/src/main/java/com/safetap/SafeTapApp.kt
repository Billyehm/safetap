package com.safetap

import android.app.Application

// Runs before any activity, service, or receiver. Points the API client at
// the server address the user saved (or the default for the emulator).
class SafeTapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.baseUrl = Prefs(this).serverUrl
    }
}
