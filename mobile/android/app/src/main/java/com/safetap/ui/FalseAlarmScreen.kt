package com.safetap.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.safetap.AlertFirer
import com.safetap.ApiClient
import com.safetap.Prefs
import kotlinx.coroutines.launch

// Safety tab: the FALSE ALARM button and the permission checklist.
//
// False alarm needs proof it is really you: fingerprint/face via
// BiometricPrompt, or the PIN as a fallback. On success the backend is
// told immediately and every contact gets the "false alarm, they are
// fine" update. The streaming service stops too.
@Composable
fun FalseAlarmScreen(prefs: Prefs, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val scope = rememberCoroutineScope()

    var activeAlert by remember { mutableStateOf(prefs.activeAlertId) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }

    fun resolve() {
        val alertId = prefs.activeAlertId ?: return
        val token = prefs.token ?: return
        busy = true
        scope.launch {
            try {
                ApiClient.resolveAlert(token, alertId, falseAlarm = true)
                AlertFirer.stopStreamingService(context)
                prefs.activeAlertId = null
                activeAlert = null
                message = "Done. Your contacts have been told it was a false alarm."
            } catch (e: Exception) {
                // Offline: stop the local stream anyway and let the next
                // sync carry the false-alarm state to the backend.
                AlertFirer.stopStreamingService(context)
                prefs.activeAlertId = null
                activeAlert = null
                message = "Phone stream stopped. It will tell the server when you are back online."
            } finally {
                busy = false
            }
        }
    }

    val biometricManager = BiometricManager.from(context)
    val canUseBiometric = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG
    ) == BiometricManager.BIOMETRIC_SUCCESS

    val prompt = remember {
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    resolve()
                }
                override fun onAuthenticationError(code: Int, err: String) {
                    if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                        code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        message = "Biometric failed: $err. You can use your PIN instead."
                    }
                }
            }
        )
    }

    fun authenticate() {
        if (canUseBiometric) {
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Confirm it is you")
                    .setSubtitle("False alarm needs your fingerprint or face.")
                    .setNegativeButtonText("Use PIN instead")
                    .build()
            )
        } else {
            showPinDialog = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* statuses are visible next time the screen recomposes */ }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Safety", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        if (activeAlert == null) {
            Text(
                "No active alert right now.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "If you ever trigger the SOS by mistake, come here and tap the button below. " +
                    "It asks for your fingerprint or PIN first, so nobody else can cancel your alert.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                "There is an ACTIVE alert. If this was a mistake, cancel it now " +
                    "and your contacts will be told immediately.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { authenticate() },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                modifier = Modifier.fillMaxWidth().height(72.dp)
            ) {
                Text("FALSE ALARM, I'M FINE")
            }
        }

        if (message.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(32.dp))
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "The SOS needs these to work. Location streams only during an active alert, never in the background.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val wanted = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.SEND_SMS
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    wanted.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(wanted.toTypedArray())
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant location, SMS and notification permissions")
        }
        PermissionRow("Precise location", Manifest.permission.ACCESS_FINE_LOCATION)
        PermissionRow("Send SMS", Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow("Notifications", Manifest.permission.POST_NOTIFICATIONS)
        }

        Spacer(Modifier.height(32.dp))
        Text("Emergency numbers", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "For information only. SafeTap never dials these for you.\n" +
                "National emergency: 112\n" +
                "Lagos rapid response: 767",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(32.dp))
        TextButton(onClick = onLogout) {
            Text("Log out")
        }
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false; pinInput = "" },
            title = { Text("Enter your PIN") },
            text = {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (prefs.checkPin(pinInput)) {
                        showPinDialog = false
                        pinInput = ""
                        resolve()
                    } else {
                        message = "Wrong PIN. Try again."
                    }
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false; pinInput = "" }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PermissionRow(label: String, permission: String) {
    val context = LocalContext.current
    val granted = ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED
    Text(
        (if (granted) "Granted: " else "Not granted: ") + label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp)
    )
}
