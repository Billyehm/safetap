package com.safetap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.safetap.Contact
import com.safetap.Prefs

// Emergency contacts. Free tier allows MAX_FREE_CONTACTS (2). These are the
// numbers the SOS goes to, by server channels when online and by direct
// SMS from this phone when there is no data.
@Composable
fun ContactsScreen(prefs: Prefs, modifier: Modifier = Modifier) {
    val contacts = remember { mutableStateListOf<Contact>().apply { addAll(prefs.getContacts()) } }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun persist() = prefs.saveContacts(contacts.toList())

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Emergency contacts", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "These people get your SOS. They do not need the app installed.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(contacts, key = { _, c -> c.phone }) { _, c ->
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
                            contacts.remove(c)
                            persist()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove ${c.name}")
                        }
                    }
                }
            }
        }

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
            if (error.isNotEmpty()) {
                Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val digits = phone.filter { it.isDigit() }
                    val ok = digits.length == 11 && digits.startsWith("0") ||
                        digits.length == 13 && digits.startsWith("234") ||
                        phone.trim().startsWith("+234")
                    if (name.isBlank() || !ok) {
                        error = "Enter a name and a valid Nigerian number."
                        return@Button
                    }
                    contacts.add(Contact(name.trim(), phone.trim()))
                    persist()
                    name = ""
                    phone = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add contact")
            }
        }
    }
}
