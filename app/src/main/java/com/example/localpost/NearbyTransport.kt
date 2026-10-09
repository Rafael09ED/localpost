package com.example.localpost

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.random.Random

class NearbyTransport(context: Context, private val log: DebugLog) {
    private val client = Nearby.getConnectionsClient(context)
    private val serviceId = context.packageName
    private val handler = Handler(Looper.getMainLooper())
    private val peerState = MutableStateFlow<Map<String, String>>(emptyMap())
    val peers = peerState.asStateFlow()
    private val found = mutableMapOf<String, String>()
    private val names = mutableMapOf<String, String>()
    private val pending = mutableSetOf<String>()
    private val attempts = mutableMapOf<String, Int>()
    private val retries = mutableMapOf<String, Runnable>()
    private var generation = 0
    private var running = false
    private lateinit var identity: Identity
    private lateinit var lifecycle: ConnectionLifecycleCallback
    var onConnected: ((String) -> Unit)? = null
    var onMessage: ((String, ByteArray) -> Unit)? = null

    fun start(localIdentity: Identity) {
        if (running) return
        check(localIdentity.displayName.isNotBlank())
        identity = localIdentity
        running = true
        val session = ++generation
        lifecycle = lifecycleCallback(session)
        log.add("Starting advertising and discovery ($serviceId)")
        client.startAdvertising(endpointName(), serviceId, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnSuccessListener {
                log.add("startAdvertising success")
                if (!running) client.stopAdvertising()
            }.addOnFailureListener { log.add("startAdvertising failed: ${it.message}") }
        client.startDiscovery(serviceId, discoveryCallback(session),
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnSuccessListener {
                log.add("startDiscovery success")
                if (!running) client.stopDiscovery()
            }.addOnFailureListener { log.add("startDiscovery failed: ${it.message}") }
    }

    fun stop() {
        running = false
        generation++
        retries.values.forEach { handler.removeCallbacks(it) }
        retries.clear()
        attempts.clear()
        pending.clear()
        found.clear()
        names.clear()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        peerState.value = emptyMap()
        log.add("Stopped advertising/discovery; disconnected all endpoints")
    }

    fun send(endpointId: String, bytes: ByteArray) {
        if (!running || endpointId !in peerState.value) return
        if (bytes.size > ConnectionsClient.MAX_BYTES_DATA_SIZE) {
            log.add("Payload too large: ${bytes.size}")
            return
        }
        log.add("sendPayload $endpointId ${bytes.size} bytes")
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnSuccessListener { log.add("sendPayload accepted $endpointId") }
            .addOnFailureListener { log.add("sendPayload failed $endpointId: ${it.message}") }
    }

    private fun endpointName() = "${identity.authorId}|${identity.displayName}"

    private fun event(session: Int, message: String, action: () -> Unit = {}) {
        handler.post {
            log.add(message)
            if (running && generation == session) action()
        }
    }

    private fun discoveryCallback(session: Int) = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            event(session, "onEndpointFound $endpointId ${info.endpointName}") {
                val parsed = info.endpointName.split('|', limit = 2)
                if (parsed.size != 2 || !parsed[0].matches(Regex("[0-9a-f]{16}")) ||
                    parsed[1].isBlank() || parsed[1].length > 24) {
                    log.add("Malformed endpoint name ignored $endpointId")
                    return@event
                }
                names[endpointId] = parsed[1]
                found[endpointId] = parsed[0]
                if (shouldDial(identity.authorId, parsed[0])) request(endpointId, session)
                else log.add("Tie-break: waiting for $endpointId")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            event(session, "onEndpointLost $endpointId") {
                found.remove(endpointId)
                cancelRetry(endpointId)
                if (endpointId !in peerState.value && endpointId !in pending) names.remove(endpointId)
            }
        }
    }

    private fun request(endpointId: String, session: Int) {
        if (!running || generation != session || endpointId !in found ||
            endpointId in pending || endpointId in peerState.value || endpointId in retries) return
        pending.add(endpointId)
        log.add("requestConnection $endpointId attempt ${(attempts[endpointId] ?: 0) + 1}")
        client.requestConnection(endpointName(), endpointId, lifecycle)
            .addOnSuccessListener { log.add("requestConnection accepted $endpointId") }
            .addOnFailureListener {
                event(session, "requestConnection failed $endpointId: ${it.message}") {
                    retry(endpointId, session)
                }
            }
    }

    private fun retry(endpointId: String, session: Int) {
        pending.remove(endpointId)
        val remoteId = found[endpointId] ?: return
        if (!shouldDial(identity.authorId, remoteId) || endpointId in retries || endpointId in peerState.value) return
        val count = attempts[endpointId] ?: 0
        if (count >= 3) {
            log.add("Retries exhausted $endpointId")
            return
        }
        attempts[endpointId] = count + 1
        val delay = Random.nextLong(1_000, 3_001)
        val action = Runnable {
            retries.remove(endpointId)
            request(endpointId, session)
        }
        retries[endpointId] = action
        log.add("Retry ${count + 1}/3 $endpointId in ${delay}ms")
        handler.postDelayed(action, delay)
    }

    private fun cancelRetry(endpointId: String) {
        retries.remove(endpointId)?.let { handler.removeCallbacks(it) }
    }

    private fun lifecycleCallback(session: Int) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            event(session, "onConnectionInitiated $endpointId ${info.endpointName}") {
                names[endpointId] = info.endpointName.substringAfter('|', "unknown").take(24)
                pending.add(endpointId)
                client.acceptConnection(endpointId, payloadCallback(session))
                    .addOnSuccessListener { log.add("acceptConnection success $endpointId") }
                    .addOnFailureListener {
                        event(session, "acceptConnection failed $endpointId: ${it.message}") {
                            retry(endpointId, session)
                        }
                    }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            event(session, "onConnectionResult $endpointId ${result.status.statusCode} ${result.status.statusMessage}") {
                pending.remove(endpointId)
                if (result.status.isSuccess) {
                    if (endpointId in peerState.value) return@event
                    cancelRetry(endpointId)
                    attempts.remove(endpointId)
                    peerState.value = peerState.value + (endpointId to (names[endpointId] ?: "unknown"))
                    val connected = onConnected
                    if (connected != null) connected(endpointId)
                    else send(endpointId, JSONObject().put("type", "hello")
                        .put("authorId", identity.authorId).put("name", identity.displayName)
                        .toString().toByteArray(Charsets.UTF_8))
                } else retry(endpointId, session)
            }
        }

        override fun onDisconnected(endpointId: String) {
            event(session, "onDisconnected $endpointId") {
                peerState.value = peerState.value - endpointId
                pending.remove(endpointId)
                names.remove(endpointId)
                attempts.remove(endpointId)
                cancelRetry(endpointId)
            }
        }
    }

    private fun payloadCallback(session: Int) = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            event(session, "onPayloadReceived $endpointId payload=${payload.id} type=${payload.type}") {
                val bytes = payload.asBytes()
                if (payload.type != Payload.Type.BYTES || bytes == null) {
                    log.add("Non-BYTES payload ignored $endpointId")
                    return@event
                }
                runCatching {
                    val json = JSONObject(String(bytes, Charsets.UTF_8))
                    if (json.optString("type") == "hello") {
                        val name = json.get("name") as String
                        val author = json.get("authorId") as String
                        require(name.isNotBlank() && name.length <= 24 && author.matches(Regex("[0-9a-f]{16}")))
                        if (endpointId in peerState.value) peerState.value = peerState.value + (endpointId to name)
                        log.add("hello $endpointId $name $author")
                    }
                    onMessage?.invoke(endpointId, bytes)
                }.onFailure { log.add("Malformed payload dropped $endpointId: ${it.message}") }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            event(session, "onPayloadTransferUpdate $endpointId payload=${update.payloadId} status=${update.status} ${update.bytesTransferred}/${update.totalBytes}")
        }
    }

    companion object {
        fun shouldDial(localId: String, remoteId: String): Boolean = localId < remoteId
    }
}