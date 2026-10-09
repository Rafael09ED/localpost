package com.example.localpost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.localpost.ui.theme.LocalPostTheme

class MainActivity : ComponentActivity() {
    private var identity by mutableStateOf<Identity?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("identity", MODE_PRIVATE)
        val loaded = runCatching { Identity.load(preferences) }
        identity = loaded.getOrNull()
        setContent {
            LocalPostTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(Modifier.padding(innerPadding).padding(16.dp)) {
                        Text("Public Gossip")
                        val current = identity
                        if (current != null) {
                            Text(current.displayName)
                            Text(current.authorId)
                            Text("0 nearby connected")
                            if (current.displayName.isEmpty()) {
                                NameDialog { name ->
                                    if (preferences.edit().putString("displayName", name).commit()) {
                                        identity = current.copy(displayName = name)
                                    }
                                }
                            }
                        } else {
                            Text("Unable to load identity: ${loaded.exceptionOrNull()?.message}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Display name") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                singleLine = true,
                label = { Text("Name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        }
    )
}