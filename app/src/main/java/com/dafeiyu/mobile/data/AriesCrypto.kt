package com.dafeiyu.mobile.data

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONObject

/** Wire-compatible with the desktop AriesCrypto; no cryptographic keys cross the plugin bridge. */
internal object AriesCrypto {
    private val random = SecureRandom()
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String, length: Int? = null): ByteArray {
        require(text.isNotEmpty() && text.length % 4 != 1 && text.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' })
        val bytes = Base64.getUrlDecoder().decode(text)
        require(encode(bytes) == text && (length == null || bytes.size == length)) { "invalid base64url" }
        return bytes
    }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun publicKey(seed: ByteArray): String = encode(Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded)
    fun identity(id: String, public: String) {
        val bytes = decode(public, 32)
        require(Ed25519.validatePublicKeyFull(bytes, 0) && id == "dev_" + hash(bytes).take(32)) { "identity mismatch" }
    }
    fun fingerprint(public: String): String {
        val bytes = decode(public, 32)
        require(Ed25519.validatePublicKeyFull(bytes, 0))
        return hash(bytes).uppercase()
    }
    fun sign(seed: ByteArray, text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return encode(Ed25519Signer().run {
            init(true, Ed25519PrivateKeyParameters(seed, 0)); update(bytes, 0, bytes.size); generateSignature()
        })
    }
    private fun verify(public: String, text: String, signature: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(decode(public, 32), 0))
            update(bytes, 0, bytes.size); verifySignature(decode(signature, 64))
        }) { "invalid signature" }
    }
    private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(bytes)
    }
    private fun crypt(encrypt: Boolean, key: ByteArray, nonce: ByteArray, bytes: ByteArray, context: String): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            require(key.size == 32 && nonce.size == 12)
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(context.toByteArray(Charsets.UTF_8)); doFinal(bytes)
        }
    private fun required(json: JSONObject, field: String): String {
        val text = json.getString(field)
        require(text.isNotEmpty() && text.length <= 128 && text.none(Char::isISOControl)) { "invalid $field" }
        return text
    }
    private fun pairContext(e: JSONObject): String {
        require(e.getString("protocol") == "dafeiyu-aries-pair-key" && e.getInt("version") == 1 &&
            e.getString("suite") == "Ed25519-X25519-HKDF-SHA256-AES256GCM")
        val epoch = e.getLong("epoch"); require(epoch > 0)
        return listOf(e.getString("protocol"), "1", e.getString("suite"), required(e, "node_id"),
            required(e, "group_id"), epoch.toString(), required(e, "application_id"), required(e, "sender_id"),
            required(e, "sender_public_key"), required(e, "recipient_id"), required(e, "recipient_public_key"),
            required(e, "ephemeral_public_key"), required(e, "nonce")).joinToString("\n")
    }
    private fun montgomery(public: String): ByteArray {
        val point = decode(public, 32)
        require(Ed25519.validatePublicKeyFull(point, 0))
        point[31] = (point[31].toInt() and 127).toByte()
        val p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        val y = BigInteger(1, point.reversedArray())
        val u = BigInteger.ONE.add(y).multiply(BigInteger.ONE.subtract(y).mod(p).modInverse(p)).mod(p)
        val raw = u.toByteArray().reversedArray()
        return ByteArray(32).also { raw.copyInto(it, endIndex = minOf(raw.size, 32)) }
    }
    private fun wrapping(secret: ByteArray, context: String): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256").digest(context.toByteArray())
        val prk = hmac(salt, secret)
        return try { hmac(prk, "dafeiyu-aries-pair/v1/content-key\u0001".toByteArray()) } finally { prk.fill(0) }
    }
    private fun agreement(private: X25519PrivateKeyParameters, public: ByteArray): ByteArray = ByteArray(32).also {
        private.generateSecret(X25519PublicKeyParameters(public, 0), it, 0)
        require(it.any { b -> b != 0.toByte() }) { "invalid agreement" }
    }
    fun readPair(encoded: String): JSONObject {
        require(encoded.length <= 11000)
        val raw = decode(encoded); require(raw.size <= 8192)
        return JSONObject(String(raw, Charsets.UTF_8)).also { e ->
            val context = pairContext(e)
            identity(e.getString("sender_id"), e.getString("sender_public_key"))
            identity(e.getString("recipient_id"), e.getString("recipient_public_key"))
            decode(e.getString("ephemeral_public_key"), 32); decode(e.getString("nonce"), 12)
            decode(e.getString("ciphertext"), 32); decode(e.getString("tag"), 16)
            verify(e.getString("sender_public_key"), context + "\n" + e.getString("ciphertext") + "\n" + e.getString("tag"), e.getString("signature"))
        }
    }
    fun openPair(seed: ByteArray, envelope: JSONObject): ByteArray {
        require(publicKey(seed) == envelope.getString("recipient_public_key")) { "wrong recipient" }
        val digest = MessageDigest.getInstance("SHA-512").digest(seed)
        val scalar = digest.copyOf(32)
        scalar[0] = (scalar[0].toInt() and 248).toByte()
        scalar[31] = ((scalar[31].toInt() and 127) or 64).toByte()
        val secret = try { agreement(X25519PrivateKeyParameters(scalar, 0), decode(envelope.getString("ephemeral_public_key"), 32)) }
            finally { digest.fill(0); scalar.fill(0) }
        val context = pairContext(envelope)
        val wrap = wrapping(secret, context)
        return try { crypt(false, wrap, decode(envelope.getString("nonce"), 12),
            decode(envelope.getString("ciphertext"), 32) + decode(envelope.getString("tag"), 16), context) }
            finally { secret.fill(0); wrap.fill(0) }
    }
    fun createPair(seed: ByteArray, key: ByteArray, node: String, group: String, epoch: Long,
        application: String, recipient: String, public: String): String {
        require(key.size == 32); identity(recipient, public)
        val ephemeral = X25519PrivateKeyParameters(random)
        val senderPublic = publicKey(seed)
        val e = JSONObject().put("protocol", "dafeiyu-aries-pair-key").put("version", 1)
            .put("suite", "Ed25519-X25519-HKDF-SHA256-AES256GCM").put("node_id", node).put("group_id", group)
            .put("epoch", epoch).put("application_id", application).put("sender_id", "dev_" + hash(decode(senderPublic)).take(32))
            .put("sender_public_key", senderPublic).put("recipient_id", recipient).put("recipient_public_key", public)
            .put("ephemeral_public_key", encode(ephemeral.generatePublicKey().encoded))
            .put("nonce", encode(ByteArray(12).also(random::nextBytes)))
        val context = pairContext(e)
        val secret = agreement(ephemeral, montgomery(public)); val wrap = wrapping(secret, context)
        try {
            val sealed = crypt(true, wrap, decode(e.getString("nonce")), key, context)
            e.put("ciphertext", encode(sealed.copyOfRange(0, 32))).put("tag", encode(sealed.copyOfRange(32, 48)))
            e.put("signature", sign(seed, context + "\n" + e.getString("ciphertext") + "\n" + e.getString("tag")))
            return encode(e.toString().toByteArray())
        } finally { secret.fill(0); wrap.fill(0) }
    }
    fun envelope(type: String, node: String, group: String, epoch: Long, sender: String, id: String): JSONObject =
        JSONObject().put("protocol", "dafeiyu-aries-content").put("version", 1).put("type", type)
            .put("node_id", node).put("group_id", group).put("epoch", epoch).put("sender_id", sender).put("object_id", id)
    private fun context(e: JSONObject, type: String = e.getString("type"), index: Int? = null): String {
        require(e.getString("protocol") == "dafeiyu-aries-content" && e.getInt("version") == 1 &&
            e.getString("type") in listOf("message", "file") && e.getLong("epoch") > 0)
        return listOf(e.getString("protocol"), "1", type, required(e, "node_id"), required(e, "group_id"),
            e.getLong("epoch").toString(), required(e, "sender_id"), required(e, "object_id")).joinToString("\n") +
            (index?.let { require(it >= 0); "\n$it" } ?: "")
    }
    fun readContent(encoded: String): JSONObject {
        require(encoded.length <= 1500000)
        return JSONObject(String(decode(encoded), Charsets.UTF_8)).also { context(it) }
    }
    private fun signature(context: String, nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray) =
        listOf(context, encode(nonce), encode(ciphertext), encode(tag)).joinToString("\n")
    private fun cipherKey(key: ByteArray, type: String): ByteArray {
        require(key.size == 32)
        return hmac(key, "dafeiyu-aries-content\n1\n$type".toByteArray())
    }
    fun sealContent(seed: ByteArray, key: ByteArray, e: JSONObject, plaintext: ByteArray): String {
        val ctx = context(e); val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = cipherKey(key, e.getString("type"))
        try {
            val raw = crypt(true, cipher, nonce, plaintext, ctx)
            val ciphertext = raw.copyOfRange(0, raw.size - 16); val tag = raw.takeLast(16).toByteArray()
            e.put("nonce", encode(nonce)).put("ciphertext", encode(ciphertext)).put("tag", encode(tag))
                .put("signature", sign(seed, signature(ctx, nonce, ciphertext, tag)))
            return encode(e.toString().toByteArray())
        } finally { cipher.fill(0) }
    }
    fun openContent(key: ByteArray, e: JSONObject, public: String): ByteArray {
        val ctx = context(e); identity(e.getString("sender_id"), public)
        val nonce = decode(e.getString("nonce"), 12); val tag = decode(e.getString("tag"), 16)
        // An empty plaintext has an empty base64url ciphertext.
        val ciphertext = e.getString("ciphertext").let { if (it.isEmpty()) ByteArray(0) else decode(it) }
        verify(public, signature(ctx, nonce, ciphertext, tag), e.getString("signature"))
        val cipher = cipherKey(key, e.getString("type"))
        return try { crypt(false, cipher, nonce, ciphertext + tag, ctx) } finally { cipher.fill(0) }
    }
    fun sealChunk(seed: ByteArray, key: ByteArray, e: JSONObject, index: Int, plaintext: ByteArray): ByteArray {
        val ctx = context(e, "chunk", index); val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = cipherKey(key, "chunk")
        try {
            val raw = crypt(true, cipher, nonce, plaintext, ctx)
            return nonce + raw + decode(sign(seed, signature(ctx, nonce, raw.copyOfRange(0, raw.size - 16), raw.takeLast(16).toByteArray())), 64)
        } finally { cipher.fill(0) }
    }
    fun openChunk(key: ByteArray, e: JSONObject, public: String, index: Int, raw: ByteArray): ByteArray {
        require(raw.size in 92..4194304)
        val ctx = context(e, "chunk", index); identity(e.getString("sender_id"), public)
        val nonce = raw.copyOfRange(0, 12); val ciphertext = raw.copyOfRange(12, raw.size - 80)
        val tag = raw.copyOfRange(raw.size - 80, raw.size - 64)
        verify(public, signature(ctx, nonce, ciphertext, tag), encode(raw.takeLast(64).toByteArray()))
        val cipher = cipherKey(key, "chunk")
        return try { crypt(false, cipher, nonce, ciphertext + tag, ctx) } finally { cipher.fill(0) }
    }
}
