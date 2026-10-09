package com.example.localpost

import org.json.JSONObject

class GossipSync(
    private val identity: () -> Identity,
    private val store: PostStore,
    private val endpoints: () -> Set<String>,
    private val send: (String, ByteArray) -> Unit,
    private val log: (String) -> Unit = {}
) {
    private val helloNames = mutableMapOf<String, String>()

    fun connected(endpointId: String) {
        helloNames.remove(endpointId)
        val local = identity()
        sendJson(endpointId, JSONObject().put("type", "hello")
            .put("authorId", local.authorId).put("name", local.displayName))
        store.posts.value.forEach { sendPost(endpointId, it) }
    }

    fun disconnected(endpointId: String) {
        helloNames.remove(endpointId)
    }

    fun create(text: String): Post {
        val post = Post.create(identity(), text)
        if (store.add(post)) {
            log("Local post stored ${post.id}")
            endpoints().toList().forEach { sendPost(it, post) }
        }
        return post
    }

    fun receive(endpointId: String, bytes: ByteArray) {
        if (endpointId !in endpoints()) {
            log("Payload from disconnected endpoint dropped $endpointId")
            return
        }
        runCatching {
            require(bytes.size <= 32_768)
            val message = JSONObject(String(bytes, Charsets.UTF_8))
            when (message.get("type") as String) {
                "hello" -> {
                    val authorId = message.get("authorId") as String
                    val name = message.get("name") as String
                    require(authorId.matches(Regex("[0-9a-f]{16}")))
                    require(name.isNotBlank() && name.length <= 24)
                    helloNames[endpointId] = name
                    log("Sender hello recorded $endpointId $name")
                }
                "post" -> {
                    val json = message.getJSONObject("post")
                    val id = json.get("id") as String
                    if (store.contains(id)) {
                        log("Duplicate post ignored $id")
                        return
                    }
                    val post = Post.fromJson(json)
                    if (!post.verify()) {
                        log("Invalid post dropped $id")
                        return
                    }
                    val name = helloNames[endpointId]
                    if (name == null) {
                        log("Post before hello dropped $endpointId")
                        return
                    }
                    val received = post.copy(hops = post.hops + 1, via = name)
                    if (store.add(received)) log("Received post stored $id via $name hops=${received.hops}")
                }
                else -> log("Unknown message type dropped $endpointId")
            }
        }.onFailure { log("Malformed or unsaved message dropped $endpointId: ${it.message}") }
    }

    private fun sendPost(endpointId: String, post: Post) {
        sendJson(endpointId, JSONObject().put("type", "post").put("post", post.toJson()))
    }

    private fun sendJson(endpointId: String, message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 32_768) {
            log("Oversized message not sent $endpointId")
            return
        }
        runCatching { send(endpointId, bytes) }.onFailure {
            log("Send failed $endpointId: ${it.message}")
        }
    }
}