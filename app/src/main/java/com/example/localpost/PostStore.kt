package com.example.localpost

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PostStore(private val file: File, private val log: (String) -> Unit = {}) {
    private val postsById = LinkedHashMap<String, Post>()
    private val state = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = state.asStateFlow()

    init {
        if (file.exists()) {
            runCatching {
                val array = JSONArray(file.readText(Charsets.UTF_8))
                for (index in 0 until array.length()) {
                    runCatching { Post.fromJson(array.getJSONObject(index)) }.onSuccess { post ->
                        if (post.verify()) postsById.putIfAbsent(post.id, post)
                        else log("Invalid saved post dropped")
                    }.onFailure { log("Malformed saved post dropped: ${it.message}") }
                }
            }.onFailure { log("Post file load failed: ${it.message}") }
        }
        publish()
    }

    fun contains(id: String): Boolean = postsById.containsKey(id)

    fun add(post: Post): Boolean {
        if (contains(post.id)) return false
        val array = JSONArray()
        postsById.values.forEach { array.put(it.toJson()) }
        array.put(post.toJson())
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { output ->
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
        postsById[post.id] = post
        publish()
        return true
    }

    private fun publish() {
        state.value = postsById.values.sortedByDescending { it.createdAt }
    }
}