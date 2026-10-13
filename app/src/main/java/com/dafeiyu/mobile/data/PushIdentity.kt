package com.dafeiyu.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.json.JSONObject

internal interface PushProofSigner {
    fun installationKey(): String
    fun sign(path: String, payload: ByteArray): JSONObject
}

/** Independent, non-exportable installation key; excluded from .dym and Aries identity. */
internal class PushIdentity(context: Context) : PushProofSigner {
    private val packageName = context.applicationContext.packageName
    private val alias = "$packageName.android-push.identity.v1"

    override fun installationKey(): String = synchronized(keyLock) { digest(publicKey()) }

    override fun sign(path: String, payload: ByteArray): JSONObject = synchronized(keyLock) {
        require(path in routes && payload.size in 1..16_384)
        val key = store()
        val public = publicKey(key)
        val owner = digest(public)
        val nonce = encode(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val timestamp = System.currentTimeMillis() / 1000
        val canonical = canonical(path, owner, timestamp, nonce, payload)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(key.getKey(alias, null) as java.security.PrivateKey)
            update(canonical)
        }.sign()
        JSONObject().put("installationKey", owner).put("publicKey", encode(public)).put("timestamp", timestamp)
            .put("nonce", nonce).put("payload", encode(payload)).put("signature", encode(signature))
    }

    private fun publicKey(key: KeyStore = store()): ByteArray = key.getCertificate(alias).publicKey.encoded.also {
        check(it.size in 1..128) { "推送安装公钥不可用" }
    }
    private fun store(): KeyStore {
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!key.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        return key
    }
    override fun toString(): String = "PushIdentity(privateKey=[non-exportable])"
    companion object {
        const val PROTOCOL = "dafeiyu-go.android-push.v1"
        private val keyLock = Any()
        internal val routes = setOf("register", "preferences", "unregister", "state").map { "/api/android/push/$it" }.toSet()
        internal fun canonical(path: String, owner: String, timestamp: Long, nonce: String, payload: ByteArray): ByteArray =
            "$PROTOCOL\nPOST\n$path\n$owner\n$timestamp\n$nonce\n${digest(payload)}".toByteArray(Charsets.UTF_8)
        internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        internal fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
