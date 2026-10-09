package com.example.localpost

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PostTest {
    private val identity = Identity(Identity.generateKeys(), "Alice")

    @Test fun signatureRoundTripAndTampering() {
        val post = Post.create(identity, "Hello\nworld")
        assertTrue(post.verify())
        assertFalse(post.copy(text = "Tampered").verify())
        assertFalse(post.copy(authorId = "0000000000000000").verify())
        assertFalse(post.copy(pubKey = "broken").verify())
        assertTrue(post.copy(hops = 2, via = "Bob").verify())
    }

    @Test fun canonicalFieldOrder() {
        val post = Post.create(identity, "Hello", 42)
        assertEquals(listOf(post.id, post.authorId, post.authorName, post.pubKey, "Hello", "42", "public")
            .joinToString("\n"), String(post.signedBytes(), Charsets.UTF_8))
    }

    @Test fun jsonRoundTrip() {
        val post = Post.create(identity, "Line one\nLine two \u2603")
        assertEquals(post, Post.fromJson(JSONObject(post.toJson().toString())))
        val relayed = post.copy(hops = 3, via = "Bob")
        assertEquals(relayed, Post.fromJson(JSONObject(relayed.toJson().toString())))
    }

    @Test fun duplicateAndPersistence() {
        val directory = Files.createTempDirectory("gossip-test").toFile()
        try {
            val file = File(directory, "posts.json")
            val store = PostStore(file)
            val first = Post.create(identity, "First", 1)
            val second = Post.create(identity, "Second", 2)
            assertTrue(store.add(first))
            assertFalse(store.add(first.copy(text = "duplicate")))
            assertTrue(store.add(second))
            assertEquals(listOf(second, first), store.posts.value)
            assertEquals(store.posts.value, PostStore(file).posts.value)
        } finally {
            directory.deleteRecursively()
        }
    }
}