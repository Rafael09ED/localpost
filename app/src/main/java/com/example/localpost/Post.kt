package com.example.localpost

import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

data class Post(
    val id: String,
    val authorId: String,
    val authorName: String,
    val pubKey: String,
    val text: String,
    val createdAt: Long,
    val audience: String,
    val sig: String,
    val hops: Int,
    val via: String?
) {
    fun signedBytes(): ByteArray = listOf(
        id, authorId, authorName, pubKey, text, createdAt.toString(), audience
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    fun verify(): Boolean = runCatching {
        require(UUID.fromString(id).toString() == id)
        require(authorName.isNotBlank() && authorName.length <= 24)
        require(text.isNotBlank() && text.length <= 500 && audience == "public")
        val encoded = Base64.getDecoder().decode(pubKey)
        require(Identity.authorIdFor(encoded) == authorId)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as ECPublicKey
        require(key.params.curve.field.fieldSize == 256)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(signedBytes())
            verify(Base64.getDecoder().decode(sig))
        }
    }.getOrDefault(false)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("authorId", authorId)
        put("authorName", authorName)
        put("pubKey", pubKey)
        put("text", text)
        put("createdAt", createdAt)
        put("audience", audience)
        put("sig", sig)
        put("hops", hops)
        put("via", via ?: JSONObject.NULL)
    }

    companion object {
        fun create(identity: Identity, text: String, now: Long = System.currentTimeMillis()): Post {
            require(text.isNotBlank() && text.length <= 500)
            require(identity.displayName.isNotBlank() && identity.displayName.length <= 24)
            val unsigned = Post(UUID.randomUUID().toString(), identity.authorId, identity.displayName,
                identity.pubKey, text, now, "public", "", 0, null)
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(identity.keys.private)
                update(unsigned.signedBytes())
                sign()
            }
            return unsigned.copy(sig = Base64.getEncoder().encodeToString(signature))
        }

        fun fromJson(json: JSONObject): Post {
            fun string(name: String): String = json.get(name) as String
            val time = json.get("createdAt")
            val hops = json.get("hops")
            require(time is Long || time is Int)
            require(hops is Int || hops is Long)
            val hopCount = (hops as Number).toLong()
            require(hopCount in 0 until Int.MAX_VALUE.toLong())
            val via = if (json.isNull("via")) null else string("via")
            require(via == null || via.length <= 24)
            return Post(string("id"), string("authorId"), string("authorName"), string("pubKey"),
                string("text"), (time as Number).toLong(), string("audience"), string("sig"),
                hopCount.toInt(), via)
        }
    }
}