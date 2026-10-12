package com.dafeiyu.mobile.data

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Owns the local Aries Ed25519 identity.
 *
 * The Ed25519 private key is serialized only to an AES/GCM ciphertext. The AES key
 * lives in Android Keystore, and the private key is never included in preferences,
 * backups, logs, or network payloads. This store is deliberately independent of the
 * server enrollment state: losing a server registration must not create a new key.
 */
class AriesIdentityStore(context: Context) {
    data class Identity(
        val publicKey: ByteArray,
        val publicKeyBase64Url: String,
        val deviceId: String?,
    ) {
        fun copyPublicKey(): ByteArray = publicKey.copyOf()
    }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = IDENTITY_LOCK

    fun identity(): Identity = synchronized(lock) {
        if (!hasStoredIdentity()) return@synchronized generateAndPersist()
        check(loadPrivateKeyOrNull() != null) {
            "Aries private key is missing; refusing to create a replacement identity"
        }
        val publicKey = preferences.getString(KEY_PUBLIC_KEY, null)
            ?.let(::decodeBase64Url)
            ?.takeIf { it.size == ED25519_PUBLIC_KEY_BYTES }
        check(publicKey != null) {
            "Aries public key is missing or invalid; refusing to create a replacement identity"
        }
        Identity(
            publicKey = publicKey,
            publicKeyBase64Url = encodeBase64Url(publicKey),
            deviceId = preferences.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() },
        )
    }

    fun setDeviceId(deviceId: String) = synchronized(lock) {
        require(DEVICE_ID_PATTERN.matches(deviceId)) { "Aries device id is invalid" }
        check(preferences.edit().putString(KEY_DEVICE_ID, deviceId).commit()) {
            "unable to persist Aries device id"
        }
    }

    fun clearDeviceRegistration() = synchronized(lock) {
        check(preferences.edit().remove(KEY_DEVICE_ID).commit()) {
            "unable to clear Aries device id"
        }
    }

    fun sign(message: ByteArray): ByteArray = synchronized(lock) {
        identity()
        val privateKey = loadPrivateKeyOrNull() ?: error("Aries private key was not persisted")
        Signature.getInstance("Ed25519").apply {
            initSign(privateKey)
            update(message)
        }.sign()
    }

    internal fun <T> withSeed(operation: (ByteArray) -> T): T = synchronized(lock) {
        identity()
        val encoded = requireNotNull(loadPrivateKeyOrNull()).encoded
        val parsed = org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(encoded)
        val seed = org.bouncycastle.asn1.ASN1OctetString.getInstance(parsed.parsePrivateKey()).octets
        require(seed.size == 32)
        try { operation(seed) } finally { seed.fill(0); encoded.fill(0) }
    }

    private fun generateAndPersist(): Identity {
        check(!hasStoredIdentity()) { "refusing to replace an existing Aries identity" }
        val keyPair = runCatching {
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        }.getOrElse { error("此 Android 版本不支持 Ed25519 设备身份：${it.message ?: "provider unavailable"}") }
        val publicKey = rawPublicKey(keyPair)
        val privateBytes = keyPair.private.encoded
            ?: error("Ed25519 provider did not expose a private key encoding")
        try {
            val encrypted = encrypt(privateBytes)
            check(preferences.edit()
                .putString(KEY_PUBLIC_KEY, encodeBase64Url(publicKey))
                .putString(KEY_PRIVATE_CIPHERTEXT, encodeBase64(encrypted.ciphertext))
                .putString(KEY_PRIVATE_IV, encodeBase64(encrypted.iv))
                .commit()) { "unable to persist Aries identity" }
            return Identity(publicKey, encodeBase64Url(publicKey), null)
        } finally {
            privateBytes.fill(0)
        }
    }

    private fun hasStoredIdentity(): Boolean =
        listOf(KEY_PUBLIC_KEY, KEY_PRIVATE_CIPHERTEXT, KEY_PRIVATE_IV, KEY_DEVICE_ID).any(preferences::contains)

    private fun loadPrivateKeyOrNull(): PrivateKey? {
        val storedCiphertext = preferences.getString(KEY_PRIVATE_CIPHERTEXT, null)
        val storedIv = preferences.getString(KEY_PRIVATE_IV, null)
        if (storedCiphertext == null && storedIv == null) return null
        val ciphertext = storedCiphertext?.let(::decodeBase64)
            ?: error("Aries private key is missing or invalid; refusing to create a replacement identity")
        val iv = storedIv?.let(::decodeBase64)
            ?: error("Aries private key nonce is missing or invalid; refusing to create a replacement identity")
        val encoded = try {
            decrypt(ciphertext, iv)
        } catch (error: Exception) {
            throw IllegalStateException(
                "Aries private key cannot be decrypted; refusing to create a replacement identity", error,
            )
        }
        return try {
            KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(encoded))
        } finally {
            encoded.fill(0)
        }
    }

    private fun encrypt(plainText: ByteArray): EncryptedBytes {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, encryptionKey(createIfMissing = true))
        }
        val ciphertext = cipher.doFinal(plainText)
        val iv = cipher.iv.copyOf()
        check(iv.size == GCM_IV_BYTES) { "Android Keystore returned an invalid Aries identity IV" }
        return EncryptedBytes(iv, ciphertext)
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        require(iv.size == GCM_IV_BYTES) { "invalid Aries identity nonce" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, encryptionKey(createIfMissing = false), GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(ciphertext)
        }
    }

    private fun encryptionKey(createIfMissing: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(ENCRYPTION_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        check(createIfMissing && !keyStore.containsAlias(ENCRYPTION_ALIAS)) {
            "Aries identity encryption key is missing or invalid; refusing to create a replacement key"
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(
            ENCRYPTION_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun rawPublicKey(keyPair: KeyPair): ByteArray {
        val encoded = keyPair.public.encoded ?: error("Ed25519 provider did not expose a public key encoding")
        if (encoded.size == ED25519_PUBLIC_KEY_BYTES) return encoded
        require(encoded.size >= ED25519_PUBLIC_KEY_BYTES) { "unexpected Ed25519 public key encoding" }
        return encoded.copyOfRange(encoded.size - ED25519_PUBLIC_KEY_BYTES, encoded.size)
    }

    private data class EncryptedBytes(val iv: ByteArray, val ciphertext: ByteArray)

    companion object {
        private const val PREFERENCES = "aries_device_identity"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_ALIAS = "dafeiyu_aries_identity_aes"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_PRIVATE_CIPHERTEXT = "private_key_ciphertext"
        private const val KEY_PRIVATE_IV = "private_key_iv"
        private const val KEY_DEVICE_ID = "device_id"
        private const val ED25519_PUBLIC_KEY_BYTES = 32
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val IDENTITY_LOCK = Any()
        private val DEVICE_ID_PATTERN = Regex("dev_[a-f0-9]{32}")

        private fun encodeBase64Url(value: ByteArray): String =
            Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        private fun decodeBase64Url(value: String): ByteArray? = runCatching {
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }.getOrNull()

        private fun encodeBase64(value: ByteArray): String =
            Base64.encodeToString(value, Base64.NO_WRAP)

        private fun decodeBase64(value: String): ByteArray? = runCatching {
            Base64.decode(value, Base64.NO_WRAP)
        }.getOrNull()
    }
}
