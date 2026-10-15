package com.safetap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

// First-run gate: register or log in with phone number + PIN. The backend
// returns a Bearer token, kept in encrypted storage along with the name,
// phone, and PIN (the PIN is needed later for the false-alarm call).
@Composable
fun LoginScreen(prefs: Prefs, onLoggedIn: (String) -> Unit) {
    var mode by remember { mutableStateOf("login") } // login | register
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var serverInput by remember { mutableStateOf(prefs.serverUrl) }
    var serverMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val registering = mode == "register"

    fun saveSession(res: JSONObject, usedPhone: String, usedPin: String) {
        val token = res.getString("token")
        val user = res.optJSONObject("user")
        prefs.token = token
        prefs.userPhone = usedPhone
        prefs.userName = user?.optString("name", "") ?: ""
        prefs.pin = usedPin
        prefs.savePinHash(usedPin)
        onLoggedIn(token)
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("SafeTap", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (registering) "Create your account. Your phone number is your identity."
            else "Log in with your phone number and PIN to arm the SOS widget."
        )
        Spacer(Modifier.height(24.dp))

        if (registering) {
            OutlinedTextField(
                value = name, onValueChange = { name = it; error = "" },
                label = { Text("Your name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }
        OutlinedTextField(
            value = phone, onValueChange = { phone = it; error = "" },
            label = { Text("Phone number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pin, onValueChange = { pin = it; error = "" },
            label = { Text("PIN (at least 4 digits)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (phone.isBlank() || pin.isBlank() || (registering && name.isBlank())) {
                    error = "Fill in every field."
                    return@Button
                }
                if (pin.trim().length < 4) {
                    error = "PIN must be at least 4 digits."
                    return@Button
                }
                busy = true
                scope.launch {
                    try {
                        val res: JSONObject = if (registering) {
                            ApiClient.register(phone.trim(), name.trim(), pin.trim())
                        } else {
                            ApiClient.login(phone.trim(), pin.trim())
                        }
                        saveSession(res, phone.trim(), pin.trim())
                    } catch (e: Exception) {
                        error = if (registering)
                            "Could not create the account. The number may already be registered."
                        else
                            "Login failed. Check the number, PIN, and connection."
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Please wait..." else if (registering) "Create account" else "Log in")
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                mode = if (registering) "login" else "register"
                error = ""
            }) {
                Text(if (registering) "I already have an account" else "Create a new account")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Server address", style = MaterialTheme.typography.titleSmall)
        Text(
            "The computer running the SafeTap backend, on your Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = serverInput,
            onValueChange = { serverInput = it; serverMessage = "" },
            placeholder = { Text("http://192.168.1.40:8000") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val clean = serverInput.trim().trimEnd('/')
                if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                    serverMessage = "Start the address with http:// or https://"
                    return@OutlinedButton
                }
                ApiClient.baseUrl = clean
                scope.launch {
                    serverMessage = if (ApiClient.healthCheck()) {
                        prefs.serverUrl = clean
                        "Connected. This server is answering."
                    } else {
                        "No answer from that address. Check it and your Wi-Fi."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Test connection")
        }
        if (serverMessage.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(serverMessage, color = MaterialTheme.colorScheme.primary)
        }
    }
}
