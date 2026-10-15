package com.safetap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safetap.ApiClient
import com.safetap.MedicalProfile
import com.safetap.Prefs
import kotlinx.coroutines.launch
import org.json.JSONObject

// One screen, plain language. Saved to the server (encrypted there), so it
// rides along with every alert and shows on the contact's tracking page.
// A local copy is kept too, for offline reference.
@Composable
fun MedicalScreen(prefs: Prefs, modifier: Modifier = Modifier) {
    val saved = remember { prefs.getMedical() }
    var blood by remember { mutableStateOf(saved.bloodType) }
    var allergies by remember { mutableStateOf(saved.allergies) }
    var conditions by remember { mutableStateOf(saved.conditions) }
    var meds by remember { mutableStateOf(saved.medications) }
    var doctor by remember { mutableStateOf(saved.doctorNumber) }
    var note by remember { mutableStateOf(saved.note) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun applyJson(o: JSONObject) {
        blood = o.optString("blood_type", "")
        allergies = o.optString("allergies", "")
        conditions = o.optString("conditions", "")
        meds = o.optString("medications", "")
        doctor = o.optString("doctor_phone", "")
        note = o.optString("note", "")
    }

    // Load the server copy on open (it is the source of truth).
    LaunchedEffect(Unit) {
        try {
            val token = prefs.token ?: return@LaunchedEffect
            applyJson(ApiClient.getMedical(token))
        } catch (e: Exception) {
            // Offline: the cached copy stays on screen.
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Medical profile", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Shown to your contacts during an alert. Keep it short and true.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))

        Field("Blood type", blood, "e.g. O+") { blood = it }
        Field("Allergies", allergies, "e.g. Penicillin, groundnuts") { allergies = it }
        Field("Chronic conditions", conditions, "e.g. Asthma, epilepsy") { conditions = it }
        Field("Medications", meds, "e.g. Inhaler, daily") { meds = it }
        Field("Doctor's number", doctor, "e.g. 0803...") { doctor = it }
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            label = { Text("Note for responders") },
            placeholder = { Text("e.g. I am epileptic, do not restrain me") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    val profile = MedicalProfile(
                        bloodType = blood.trim(),
                        allergies = allergies.trim(),
                        conditions = conditions.trim(),
                        medications = meds.trim(),
                        doctorNumber = doctor.trim(),
                        note = note.trim()
                    )
                    try {
                        val token = prefs.token
                            ?: throw IllegalStateException("Not logged in")
                        ApiClient.saveMedical(
                            token,
                            JSONObject()
                                .put("blood_type", profile.bloodType)
                                .put("allergies", profile.allergies)
                                .put("conditions", profile.conditions)
                                .put("medications", profile.medications)
                                .put("doctor_phone", profile.doctorNumber)
                                .put("note", profile.note)
                        )
                        prefs.saveMedical(profile)
                        message = "Saved. This goes out with every alert."
                    } catch (e: Exception) {
                        message = "Could not reach the server. Saved on this phone only."
                        prefs.saveMedical(profile)
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Saving..." else "Save")
        }
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    )
}
