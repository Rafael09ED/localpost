package com.example.localpost

import android.content.SharedPreferences
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class Identity(val keys: KeyPair, val displayName: String) {
    val pubKey: String = Base64.getEncoder().encodeToString(keys.public.encoded)
    val authorId: String = authorIdFor(keys.public.encoded)

    companion object {
        fun authorIdFor(publicKey: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(publicKey).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }

        fun generateKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        fun load(preferences: SharedPreferences): Identity {
            val privateKey = preferences.getString("privateKey", null)
            val publicKey = preferences.getString("publicKey", null)
            val keys = if (privateKey != null && publicKey != null) {
                val factory = KeyFactory.getInstance("EC")
                KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))),
                    factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)))
                )
            } else {
                check(privateKey == null && publicKey == null) { "Incomplete saved identity" }
                generateKeys().also {
                    check(preferences.edit()
                        .putString("privateKey", Base64.getEncoder().encodeToString(it.private.encoded))
                        .putString("publicKey", Base64.getEncoder().encodeToString(it.public.encoded))
                        .commit()) { "Could not save identity" }
                }
            }
            return Identity(keys, preferences.getString("displayName", "") ?: "")
        }
    }
}