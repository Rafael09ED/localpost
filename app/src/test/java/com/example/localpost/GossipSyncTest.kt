package com.example.localpost

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GossipSyncTest {
    private val local = Identity(Identity.generateKeys(), "Local")
    private val alice = Identity(Identity.generateKeys(), "Alice")
    private lateinit var directory: File
    private lateinit var store: PostStore
    private lateinit var sync: GossipSync
    private val peers = linkedSetOf("B", "C")
    private val sent = mutableListOf<Pair<String, JSONObject>>()

    @Before fun setup() {
        directory = Files.createTempDirectory("sync-test").toFile()
        store = PostStore(File(directory, "posts.json"))
        sync = GossipSync({ local }, store, { peers }, { endpoint, bytes ->
            sent.add(endpoint to JSONObject(String(bytes, Charsets.UTF_8)))
        })
    }

    @After fun cleanup() { directory.deleteRecursively() }

    private fun hello(endpoint: String = "B", name: String = "Bob") {
        sync.receive(endpoint, JSONObject().put("type", "hello").put("authorId", alice.authorId)
            .put("name", name).toString().toByteArray(Charsets.UTF_8))
    }

    private fun receive(post: Post, endpoint: String = "B") {
        sync.receive(endpoint, JSONObject().put("type", "post").put("post", post.toJson())
            .toString().toByteArray(Charsets.UTF_8))
    }

    @Test fun helloThenSnapshotPreservesStoredHops() {
        val post = Post.create(alice, "A relayed post").copy(hops = 2, via = "Earlier")
        store.add(post)
        sync.connected("B")
        assertEquals(2, sent.size)
        assertEquals("hello", sent[0].second.getString("type"))
        assertEquals(local.authorId, sent[0].second.getString("authorId"))
        assertEquals(post, Post.fromJson(sent[1].second.getJSONObject("post")))
        assertTrue(sent.all { it.first == "B" })
    }

    @Test fun localPostBroadcastToEveryPeer() {
        val post = sync.create("Public hello")
        assertTrue(post.verify())
        assertEquals(0, post.hops)
        assertNull(post.via)
        assertEquals(setOf("B", "C"), sent.map { it.first }.toSet())
        assertEquals(listOf(post), store.posts.value)
    }

    @Test fun fakeSenderAttributionAndDuplicateIgnore() {
        hello()
        val post = Post.create(alice, "From Alice")
        receive(post)
        val expected = post.copy(hops = 1, via = "Bob")
        assertEquals(listOf(expected), store.posts.value)
        receive(post.copy(hops = 8, via = "Wrong"))
        assertEquals(listOf(expected), store.posts.value)
        assertEquals(listOf(expected), PostStore(File(directory, "posts.json")).posts.value)
    }

    @Test fun rejectsInvalidAndMalformedMessages() {
        hello()
        val post = Post.create(alice, "Original")
        receive(post.copy(text = "Tampered"))
        receive(post.copy(authorId = local.authorId))
        receive(post.copy(hops = Int.MAX_VALUE))
        sync.receive("B", "not JSON".toByteArray())
        receive(post, "disconnected")
        assertTrue(store.posts.value.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun requiresHelloAndClearsItOnDisconnect() {
        val post = Post.create(alice, "First")
        receive(post)
        assertTrue(store.posts.value.isEmpty())
        hello()
        sync.disconnected("B")
        receive(post)
        assertTrue(store.posts.value.isEmpty())
    }

    @Test fun forwardsToOthersNotSourceAndNeverTwice() {
        peers.add("D")
        hello()
        val post = Post.create(alice, "Forward me")
        receive(post)
        assertEquals(setOf("C", "D"), sent.map { it.first }.toSet())
        assertEquals(2, sent.size)
        sent.forEach { (_, message) ->
            val forwarded = Post.fromJson(message.getJSONObject("post"))
            assertEquals(post.copy(hops = 1, via = "Bob"), forwarded)
            assertTrue(forwarded.verify())
        }
        receive(post)
        hello("C", "Charlie")
        receive(post.copy(hops = 1, via = "Bob"), "C")
        assertEquals(2, sent.size)
        assertEquals(1, store.posts.value.size)
    }

    @Test fun relayedPostGainsSecondHopAndImmediateSenderName() {
        hello(name = "Bob")
        val post = Post.create(alice, "Alice never met this phone").copy(hops = 1, via = "Alice")
        receive(post)
        val received = store.posts.value.single()
        assertEquals(2, received.hops)
        assertEquals("Bob", received.via)
        assertEquals("Alice", received.authorName)
        assertEquals(post.sig, received.sig)
        assertTrue(received.verify())
    }

    @Test fun maximumStoredHopCountReloadsButCannotOverflowOnReceive() {
        hello()
        val post = Post.create(alice, "Boundary").copy(hops = Int.MAX_VALUE - 1)
        receive(post)
        val received = store.posts.value.single()
        assertEquals(Int.MAX_VALUE, received.hops)
        assertEquals(listOf(received), PostStore(File(directory, "posts.json")).posts.value)
        val fresh = Post.create(alice, "Overflow").copy(hops = Int.MAX_VALUE)
        val count = sent.size
        receive(fresh)
        assertEquals(1, store.posts.value.size)
        assertEquals(count, sent.size)
    }
}