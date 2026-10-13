package com.safetap

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.fragment.app.FragmentActivity
import com.safetap.ui.ContactsScreen
import com.safetap.ui.FalseAlarmScreen
import com.safetap.ui.LoginScreen
import com.safetap.ui.MedicalScreen

// The main app. FragmentActivity (not plain ComponentActivity) because the
// false-alarm screen needs BiometricPrompt, which requires a fragment host.
//
// Flow: if there is no auth token, show the login screen (phone + PIN).
// Otherwise show three tabs: Contacts, Medical profile, Safety (false
// alarm + permissions). The SOS screen can jump straight to the Safety
// tab via EXTRA_OPEN_FALSE_ALARM.
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openFalseAlarm = intent.getBooleanExtra(EXTRA_OPEN_FALSE_ALARM, false)
        setContent {
            MaterialTheme {
                val prefs = remember { Prefs(this) }
                var token by remember { mutableStateOf(prefs.token) }
                if (token == null) {
                    LoginScreen(prefs = prefs, onLoggedIn = { token = it })
                } else {
                    MainTabs(
                        prefs = prefs,
                        startOnFalseAlarm = openFalseAlarm,
                        onLogout = {
                            prefs.token = null
                            token = null
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_FALSE_ALARM = "open_false_alarm"
    }
}

private data class Tab(val title: String, val icon: ImageVector)

@Composable
private fun MainTabs(prefs: Prefs, startOnFalseAlarm: Boolean, onLogout: () -> Unit) {
    val tabs = listOf(
        Tab("Contacts", Icons.Filled.Contacts),
        Tab("Medical", Icons.Filled.MedicalServices),
        Tab("Safety", Icons.Filled.Shield)
    )
    var selected by remember { mutableIntStateOf(if (startOnFalseAlarm) 2 else 0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { inner ->
        when (selected) {
            0 -> ContactsScreen(prefs, Modifier.padding(inner))
            1 -> MedicalScreen(prefs, Modifier.padding(inner))
            else -> FalseAlarmScreen(prefs, onLogout, Modifier.padding(inner))
        }
    }
}
