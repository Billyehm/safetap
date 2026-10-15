package com.safetap.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
import androidx.compose.runtime.LaunchedEffect
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
import com.safetap.BillingManager
import com.safetap.CheckInWidgetProvider
import com.safetap.Prefs
import com.safetap.SosWidgetProvider
import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.launch

// Safety tab: the FALSE ALARM button, the server address setting, the
// permission checklist, and emergency numbers.
//
// False alarm needs proof it is really you: fingerprint/face via
// BiometricPrompt, or the PIN typed in. Either way the PIN goes to the
// server, which verifies it and tells every contact "false alarm, they
// are fine". The location stream stops too.
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
    var serverInput by remember { mutableStateOf(prefs.serverUrl) }
    var serverMessage by remember { mutableStateOf("") }
    var widgetMessage by remember { mutableStateOf("") }

    // Google Play Billing for the Family subscription.
    val billing = remember(activity) { BillingManager(activity) }
    var billingReady by remember { mutableStateOf(false) }
    var products by remember { mutableStateOf(listOf<ProductDetails>()) }
    var billingMessage by remember { mutableStateOf("") }
    var isPremium by remember { mutableStateOf(prefs.isPremium) }

    LaunchedEffect(Unit) {
        billing.onProducts = { products = it }
        billing.onPurchase = { ok, msg ->
            billingMessage = msg
            if (ok) {
                isPremium = true
                prefs.isPremium = true
            }
        }
        billing.connect { ok ->
            billingReady = ok
            if (ok) {
                billing.queryProducts()
                billing.restore { active ->
                    isPremium = active
                    prefs.isPremium = active
                }
            }
        }
    }

    fun pinWidget(provider: Class<*>) {
        val mgr = context.getSystemService(AppWidgetManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(context, provider), null, null)
        } else {
            widgetMessage = "Your launcher can't pin widgets automatically. " +
                "Long-press the home screen, tap Widgets, and add SafeTap manually."
        }
    }

    fun standDown(pin: String) {
        val alertId = prefs.activeAlertId
        if (alertId == null) {
            message = "No active alert."
            return
        }
        busy = true
        scope.launch {
            try {
                val token = prefs.token ?: throw IllegalStateException("Not logged in")
                if (alertId.startsWith("local-")) {
                    // Fired offline and never reached the server: nothing to stand down.
                    message = "The alert never reached the server, so there is no one to stand down."
                } else {
                    ApiClient.falseAlarm(token, alertId, pin)
                    message = "Done. Your contacts have been told it was a false alarm."
                }
                AlertFirer.stopStreamingService(context)
                prefs.activeAlertId = null
                activeAlert = null
            } catch (e: Exception) {
                // Do NOT clear state here: the alert is still live on the
                // server and contacts are still watching it. Say so plainly.
                message = "Could not reach the server, so your alert is still live. " +
                    "Try again when you have a connection."
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
                    val pin = prefs.pin
                    if (pin.isNullOrBlank()) {
                        // Should not happen: the PIN is saved at login.
                        showPinDialog = true
                    } else {
                        standDown(pin)
                    }
                }
                override fun onAuthenticationError(code: Int, err: CharSequence) {
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
        if (activeAlert == null) {
            message = "No active alert."
            return
        }
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

        if (activeAlert != null) {
            Text(
                "An SOS is live right now. If this was a mistake, stand it down below. " +
                    "Your contacts will be told immediately.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { authenticate() },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32),
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Text("FALSE ALARM, I'M FINE")
            }
        } else {
            Text(
                "No SOS is live. If you trigger one by accident, come back here " +
                    "to stand it down.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(32.dp))
        Text("Server", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "The address of your SafeTap backend. Point it at the machine " +
                "running the server, then test it.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = serverInput,
            onValueChange = { serverInput = it; serverMessage = "" },
            label = { Text("Server address") },
            placeholder = { Text("http://192.168.1.40:8000") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        RowButtons(
            onSave = {
                val clean = serverInput.trim().trimEnd('/')
                if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                    serverMessage = "Start the address with http:// or https://"
                    return@RowButtons
                }
                prefs.serverUrl = clean
                ApiClient.baseUrl = clean
                serverMessage = "Saved. New alerts will use this server."
            },
            onTest = {
                val clean = serverInput.trim().trimEnd('/')
                ApiClient.baseUrl = clean
                scope.launch {
                    serverMessage = if (ApiClient.healthCheck()) {
                        prefs.serverUrl = clean
                        "Connected. This server is answering."
                    } else {
                        "No answer from that address. Check it and your connection."
                    }
                }
            }
        )
        if (serverMessage.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(serverMessage, color = MaterialTheme.colorScheme.primary)
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
        Text("Home screen widgets", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Put the SOS button on your home screen so it is one tap away.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { pinWidget(SosWidgetProvider::class.java) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add SOS widget to home screen")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { pinWidget(CheckInWidgetProvider::class.java) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add check-in widget to home screen")
        }
        if (widgetMessage.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(widgetMessage, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(32.dp))
        Text("SafeTap Family", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Free forever: the SOS alert, 3 contacts, tracking page. " +
                "Family adds up to 10 contacts, unlimited check-in timers, " +
                "and location history during alerts. Paid through Google Play.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        if (isPremium) {
            Text(
                "Family is active on this device.",
                color = Color(0xFF2E7D32),
                style = MaterialTheme.typography.bodyMedium
            )
        } else if (!billingReady) {
            Text(
                "Subscriptions appear in the Play Store release. " +
                    "This test build cannot sell them yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        } else if (products.isEmpty()) {
            Text(
                "No subscription products found. They must be created in " +
                    "Play Console first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        } else {
            products.forEach { product ->
                val price = product.subscriptionOfferDetails
                    ?.firstOrNull()?.pricingPhases?.pricingPhaseList
                    ?.firstOrNull()?.formattedPrice ?: ""
                val label = when (product.productId) {
                    BillingManager.PRODUCT_YEARLY -> "Family yearly"
                    else -> "Family monthly"
                }
                OutlinedButton(
                    onClick = { billing.launchPurchase(product) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("$label — $price")
                }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = {
                billing.restore { active ->
                    isPremium = active
                    prefs.isPremium = active
                    billingMessage = if (active) "Family restored."
                    else "No active Family subscription found."
                }
            }) {
                Text("Restore purchase")
            }
        }
        if (billingMessage.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(billingMessage, color = MaterialTheme.colorScheme.primary)
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
                    if (pinInput.isBlank()) {
                        return@TextButton
                    }
                    showPinDialog = false
                    val entered = pinInput
                    pinInput = ""
                    standDown(entered)
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false; pinInput = "" }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RowButtons(onSave: () -> Unit, onTest: () -> Unit) {
    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) {
            Text("Save")
        }
        Spacer(Modifier.padding(4.dp))
        Button(onClick = onTest, modifier = Modifier.weight(1f)) {
            Text("Test connection")
        }
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
