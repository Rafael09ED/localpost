package com.example.localpost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localpost.ui.theme.LocalPostTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private var identity by mutableStateOf<Identity?>(null)
    private lateinit var store: PostStore
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("identity", MODE_PRIVATE)
        val loaded = runCatching { Identity.load(preferences) }
        identity = loaded.getOrNull()
        error = loaded.exceptionOrNull()?.let { "Unable to load identity: ${it.message}" }
        store = PostStore(File(filesDir, "posts.json")) { error = it }
        setContent {
            LocalPostTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val posts by store.posts.collectAsStateWithLifecycle()
                    val current = identity
                    GossipScreen(
                        identity = current,
                        posts = posts,
                        error = error,
                        onPost = { text ->
                            runCatching {
                                checkNotNull(identity).let { store.add(Post.create(it, text)) }
                            }.onFailure { error = "Could not save post: ${it.message}" }.isSuccess
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                    if (current != null && current.displayName.isEmpty()) {
                        NameDialog { name ->
                            if (preferences.edit().putString("displayName", name).commit()) {
                                identity = current.copy(displayName = name)
                            } else {
                                error = "Could not save name"
                            }
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