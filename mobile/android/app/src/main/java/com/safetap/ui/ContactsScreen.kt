package com.safetap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.safetap.ApiClient
import com.safetap.Contact
import com.safetap.Prefs
import kotlinx.coroutines.launch

// Emergency contacts. These live on the server: the SOS fan-out reads them
// from there. A local copy is kept too, for the no-data SMS fallback that
// sends straight from this phone.
@Composable
fun ContactsScreen(prefs: Prefs, modifier: Modifier = Modifier) {
    val contacts = remember { mutableStateListOf<ServerContact>() }
    var loading by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refreshCache() {
        prefs.saveContacts(contacts.map { Contact(it.name, it.phone) })
    }

    fun load() {
        scope.launch {
            loading = true
            error = ""
            try {
                val token = prefs.token ?: throw IllegalStateException("Not logged in")
                val arr = ApiClient.getContacts(token)
                contacts.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    contacts.add(
                        ServerContact(
                            o.getString("id"),
                            o.getString("name"),
                            o.getString("phone")
                        )
                    )
                }
                refreshCache()
            } catch (e: Exception) {
                // Offline: show the cached copy so the SMS fallback still works.
                contacts.clear()
                contacts.addAll(prefs.getContacts().map {
                    ServerContact("", it.name, it.phone)
                })
                if (contacts.isEmpty()) error = "Could not reach the server."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Emergency contacts", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "These people get your SOS. They do not need the app installed.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(contacts, key = { it.key }) { c ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            Text(c.phone, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    val token = prefs.token ?: return@launch
                                    if (c.id.isNotEmpty()) ApiClient.deleteContact(token, c.id)
                                    contacts.remove(c)
                                    refreshCache()
                                } catch (e: Exception) {
                                    error = "Could not remove. Check your connection."
                                }
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove ${c.name}")
                        }
                    }
                }
            }
        }

        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp))
        }

        if (!loading) {
            if (contacts.size >= Prefs.MAX_FREE_CONTACTS) {
                Text(
                    "Free plan allows ${Prefs.MAX_FREE_CONTACTS} contacts. " +
                        "Remove one to add another.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = "" },
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it; error = "" },
                    label = { Text("Phone, e.g. 0803 123 4567") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (name.isBlank() || phone.isBlank()) {
                            error = "Enter a name and a phone number."
                            return@Button
                        }
                        busy = true
                        scope.launch {
                            try {
                                val token = prefs.token
                                    ?: throw IllegalStateException("Not logged in")
                                val id = ApiClient.addContact(
                                    token, name.trim(), phone.trim()
                                )
                                contacts.add(ServerContact(id, name.trim(), phone.trim()))
                                refreshCache()
                                name = ""
                                phone = ""
                                error = ""
                            } catch (e: Exception) {
                                error = "Could not add. Check the number and your connection."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Adding..." else "Add contact")
                }
            }
        }
    }
}

private data class ServerContact(val id: String, val name: String, val phone: String) {
    // Stable key for the list: server id when we have one, phone otherwise.
    val key: String get() = if (id.isNotEmpty()) id else "local-$phone"
}
