package com.example.localpost

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun GossipScreen(
    identity: Identity?,
    posts: List<Post>,
    onPost: (String) -> Boolean,
    modifier: Modifier = Modifier,
    connectedCount: Int = 0,
    events: List<String> = emptyList(),
    permissionMessage: String? = null,
    onRetryPermissions: () -> Unit = {},
    error: String? = null
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var debugExpanded by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    Column(modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(identity?.displayName?.ifBlank { "Public Gossip" } ?: "Public Gossip",
                style = MaterialTheme.typography.titleLarge)
            Text("${identity?.authorId?.take(8) ?: ""}  |  $connectedCount nearby connected",
                style = MaterialTheme.typography.bodySmall)
            if (permissionMessage != null) {
                Text(permissionMessage, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetryPermissions) { Text("Retry permissions") }
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(posts, key = { it.id }) { post ->
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(post.authorName + if (post.authorId == identity?.authorId) " (you)" else "",
                        style = MaterialTheme.typography.titleSmall)
                    Text(post.text)
                    val route = when {
                        post.authorId == identity?.authorId -> "own post"
                        post.hops == 1 -> "direct"
                        else -> "via ${post.via ?: "unknown"} \u00b7 ${post.hops} hops"
                    }
                    Text("${DateUtils.getRelativeTimeSpanString(post.createdAt, now, DateUtils.MINUTE_IN_MILLIS)} | $route",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
        TextButton(onClick = { debugExpanded = !debugExpanded }) {
            Text(if (debugExpanded) "Hide debug log" else "Debug log (${events.size})")
        }
        if (debugExpanded) {
            val scroll = rememberScrollState()
            LaunchedEffect(events) { scroll.animateScrollTo(scroll.maxValue) }
            Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(scroll).padding(horizontal = 16.dp)) {
                events.forEach { event -> Text(event, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(500) },
                modifier = Modifier.weight(1f),
                label = { Text("Public post") },
                maxLines = 4,
                supportingText = { Text("${draft.length}/500") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                enabled = !identity?.displayName.isNullOrBlank()
            )
            Button(onClick = { if (onPost(draft.trim())) draft = "" },
                enabled = draft.isNotBlank() && !identity?.displayName.isNullOrBlank()) { Text("Post") }
        }
    }
}