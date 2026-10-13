package com.safetap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.safetap.ApiClient
import com.safetap.Prefs
import kotlinx.coroutines.launch
import org.json.JSONObject

// First-run gate: phone number + PIN. The backend returns a Bearer token,
// which we keep in EncryptedSharedPreferences. The PIN is also hashed
// locally so the false-alarm screen can fall back to it.
@Composable
fun LoginScreen(prefs: Prefs, onLoggedIn: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        Text("SafeTap", style = androidx.compose.material3.MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Log in with your phone number and PIN to arm the SOS widget.")
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = phone, onValueChange = { phone = it; error = "" },
            label = { Text("Phone number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pin, onValueChange = { pin = it; error = "" },
            label = { Text("PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (phone.isBlank() || pin.isBlank()) {
                    error = "Enter both phone number and PIN."
                    return@Button
                }
                busy = true
                scope.launch {
                    try {
                        val res: JSONObject = ApiClient.login(phone.trim(), pin.trim())
                        val token = res.getString("token")
                        prefs.token = token
                        prefs.userPhone = phone.trim()
                        prefs.userName = res.optString("name", "")
                        prefs.setPin(pin.trim())
                        onLoggedIn(token)
                    } catch (e: Exception) {
                        error = "Login failed. Check the number, PIN, and connection."
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Logging in..." else "Log in")
        }
    }
}
