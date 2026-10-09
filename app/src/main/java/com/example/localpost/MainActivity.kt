package com.example.localpost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
    private val debugLog = DebugLog()
    private lateinit var transport: NearbyTransport
    private lateinit var sync: GossipSync
    private var started = false
    private var requestingPermissions = false
    private var permissionMessage by mutableStateOf<String?>(null)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        requestingPermissions = false
        debugLog.add("Permission result: $grants")
        updateNearby()
        if (!NearbyPermissions.granted(this)) {
            permissionMessage = "Nearby permissions are required; retry or allow them in app Settings."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("identity", MODE_PRIVATE)
        val loaded = runCatching { Identity.load(preferences) }
        identity = loaded.getOrNull()
        error = loaded.exceptionOrNull()?.let { "Unable to load identity: ${it.message}" }
        store = PostStore(File(filesDir, "posts.json"), debugLog::add)
        transport = NearbyTransport(this, debugLog)
        sync = GossipSync({ checkNotNull(identity) }, store,
            { transport.peers.value.keys }, transport::send, debugLog::add)
        transport.onConnected = sync::connected
        transport.onDisconnected = sync::disconnected
        transport.onMessage = sync::receive
        setContent {
            LocalPostTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val posts by store.posts.collectAsStateWithLifecycle()
                    val peers by transport.peers.collectAsStateWithLifecycle()
                    val events by debugLog.events.collectAsStateWithLifecycle()
                    val current = identity
                    GossipScreen(
                        identity = current,
                        posts = posts,
                        error = error,
                        connectedCount = peers.size,
                        events = events,
                        permissionMessage = permissionMessage,
                        onRetryPermissions = { requestPermissions() },
                        onPost = { text ->
                            runCatching {
                                sync.create(text)
                            }.onFailure { error = "Could not save post: ${it.message}" }.isSuccess
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                    if (current != null && current.displayName.isEmpty()) {
                        NameDialog { name ->
                            if (preferences.edit().putString("displayName", name).commit()) {
                                identity = current.copy(displayName = name)
                                updateNearby()
                                requestInitialPermissions()
                            } else {
                                error = "Could not save name"
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        updateNearby()
        requestInitialPermissions()
    }

    override fun onStop() {
        started = false
        transport.stop()
        super.onStop()
    }

    private fun requestInitialPermissions() {
        if (!identity?.displayName.isNullOrBlank() &&
            !getSharedPreferences("identity", MODE_PRIVATE).getBoolean("permissionsAsked", false)) {
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        if (!started || requestingPermissions || identity?.displayName.isNullOrBlank()) return
        if (NearbyPermissions.granted(this)) {
            updateNearby()
            return
        }
        getSharedPreferences("identity", MODE_PRIVATE).edit().putBoolean("permissionsAsked", true).apply()
        requestingPermissions = true
        val required = NearbyPermissions.required().toTypedArray()
        debugLog.add("Requesting permissions: ${required.joinToString()}")
        permissionLauncher.launch(required)
    }

    private fun updateNearby() {
        val current = identity ?: return
        if (!started || current.displayName.isBlank()) return
        if (!NearbyPermissions.granted(this)) {
            permissionMessage = "Nearby permissions are required to connect to other phones."
            return
        }
        permissionMessage = null
        runCatching { transport.start(current) }.onFailure {
            transport.stop()
            debugLog.add("Nearby start failed: ${it.message}")
            error = "Nearby could not start: ${it.message}"
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