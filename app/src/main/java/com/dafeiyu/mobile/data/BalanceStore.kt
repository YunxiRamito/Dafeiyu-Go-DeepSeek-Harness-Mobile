package com.dafeiyu.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user supplied DeepSeek API key outside SharedPreferences.
 *
 * The encrypted payload is kept in the app's no-backup private directory. The
 * AES key never leaves Android Keystore, and this class exposes no public method
 * that returns the plaintext key. It performs no network work or background
 * scheduling.
 */
class BalanceStore(context: Context) {
    class StorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private val appContext = context.applicationContext
    private val lock = STORAGE_LOCK
    private val credentialFile = File(appContext.noBackupFilesDir, CREDENTIAL_FILE_NAME)
    private val credential = AtomicFile(credentialFile)

    /** Encrypts and persists a user supplied API key. */
    fun setApiKey(apiKey: String) = synchronized(lock) {
        val normalized = normalizeApiKey(apiKey)
        val clearBytes = normalized.toByteArray(StandardCharsets.UTF_8)
        try {
            val encrypted = encrypt(clearBytes)
            val payload = ByteArray(1 + 1 + encrypted.iv.size + encrypted.ciphertext.size)
            payload[0] = FORMAT_VERSION
            payload[1] = encrypted.iv.size.toByte()
            encrypted.iv.copyInto(payload, destinationOffset = 2)
            encrypted.ciphertext.copyInto(payload, destinationOffset = 2 + encrypted.iv.size)
            try {
                writePayload(payload)
            } finally {
                payload.fill(0)
                encrypted.iv.fill(0)
                encrypted.ciphertext.fill(0)
            }
        } finally {
            clearBytes.fill(0)
        }
    }

    /** Removes the encrypted payload and its Android Keystore AES key. */
    fun clearApiKey() = synchronized(lock) {
        credential.delete()
        if (credentialFile.exists() || File(credentialFile.path + ".bak").exists() ||
            File(credentialFile.path + ".new").exists()) {
            throw StorageException("无法删除 DeepSeek API Key 存储文件")
        }
        try {
            val keyStore = keyStore()
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (error: Exception) {
            throw StorageException("无法清理 DeepSeek API Key 加密密钥", error)
        }
    }

    /** Returns true only when a readable, authenticated encrypted key is present. */
    fun hasKey(): Boolean = synchronized(lock) {
        runCatching { readApiKeyLocked() != null }.getOrDefault(false)
    }

    /** Used only by the package-local balance client immediately before a request. */
    internal fun readApiKeyForRequest(): String? = synchronized(lock) {
        readApiKeyLocked()
    }

    /** A tombstone retains the AES alias and durable ownership proof until recovery completes. */
    internal fun officialCredentialSnapshot(): OfficialNativeCredentialSnapshot = synchronized(lock) { readOfficialSnapshotLocked() }

    internal fun compareAndSetOfficialCredential(expectedRevision: String, apiKey: String?, transactionId: String) = synchronized(lock) {
        require(transactionId.matches(Regex("[a-f0-9]{32}")))
        check(readOfficialSnapshotLocked().revision == expectedRevision) { "NATIVE_CAS_CONFLICT" }
        val key = apiKey?.let(::normalizeApiKey)
        val clearBytes = JSONObject().put("schema", 2).put("key", key ?: JSONObject.NULL)
            .put("transactionId", transactionId).toString().toByteArray(StandardCharsets.UTF_8)
        try {
            val encrypted = encrypt(clearBytes)
            val payload = ByteArray(2 + encrypted.iv.size + encrypted.ciphertext.size)
            payload[0] = TRANSACTION_FORMAT_VERSION
            payload[1] = encrypted.iv.size.toByte()
            encrypted.iv.copyInto(payload, destinationOffset = 2)
            encrypted.ciphertext.copyInto(payload, destinationOffset = 2 + encrypted.iv.size)
            try { writePayload(payload) }
            finally { payload.fill(0); encrypted.iv.fill(0); encrypted.ciphertext.fill(0) }
        } finally { clearBytes.fill(0) }
    }

    private fun readApiKeyLocked(): String? = readOfficialSnapshotLocked().key

    private fun readOfficialSnapshotLocked(): OfficialNativeCredentialSnapshot {
        val payload = try {
            if (!credentialFile.isFile && !File(credentialFile.path + ".bak").isFile)
                return OfficialNativeCredentialSnapshot("absent", null, null)
            credential.openRead().use { input ->
                val buffer = ByteArray(1024)
                val bounded = ByteArrayOutputStream()
                try {
                    while (bounded.size() <= MAX_PAYLOAD_BYTES) {
                        val read = input.read(buffer, 0, minOf(buffer.size, MAX_PAYLOAD_BYTES + 1 - bounded.size()))
                        if (read < 0) break
                        bounded.write(buffer, 0, read)
                    }
                } finally {
                    buffer.fill(0)
                }
                val bytes = bounded.toByteArray()
                if (bytes.size !in MIN_PAYLOAD_BYTES..MAX_PAYLOAD_BYTES) {
                    bytes.fill(0)
                    throw StorageException("DeepSeek API Key 存储数据大小无效")
                }
                bytes
            }
        } catch (error: StorageException) {
            throw error
        } catch (error: Exception) {
            throw StorageException("无法读取 DeepSeek API Key 存储数据", error)
        }

        try {
            require(payload.size in MIN_PAYLOAD_BYTES..MAX_PAYLOAD_BYTES) {
                "DeepSeek API Key 存储数据大小无效"
            }
            require(payload[0] == FORMAT_VERSION || payload[0] == TRANSACTION_FORMAT_VERSION) { "DeepSeek API Key 存储版本不受支持" }
            val ivLength = payload[1].toInt() and 0xff
            require(ivLength == GCM_IV_BYTES) { "DeepSeek API Key 存储随机数无效" }
            require(payload.size > 2 + ivLength + GCM_TAG_BYTES) {
                "DeepSeek API Key 密文不完整"
            }
            val iv = payload.copyOfRange(2, 2 + ivLength)
            val ciphertext = payload.copyOfRange(2 + ivLength, payload.size)
            try {
                val clearBytes = decrypt(ciphertext, iv)
                try {
                    val revision = OfficialDeepSeekCredentialTransaction.encryptedRevision(payload)
                    val clear = String(clearBytes, StandardCharsets.UTF_8)
                    if (payload[0] == FORMAT_VERSION) return OfficialNativeCredentialSnapshot(revision, normalizeApiKey(clear), null)
                    val stored = JSONObject(clear)
                    require(stored.getInt("schema") == 2 && stored.keys().asSequence().toSet() == setOf("schema", "key", "transactionId"))
                    val transactionId = stored.getString("transactionId").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
                    val key = if (stored.isNull("key")) null else normalizeApiKey(stored.getString("key"))
                    return OfficialNativeCredentialSnapshot(revision, key, transactionId)
                } finally {
                    clearBytes.fill(0)
                }
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        } catch (error: StorageException) {
            throw error
        } catch (error: Exception) {
            throw StorageException("无法解密 DeepSeek API Key", error)
        } finally {
            payload.fill(0)
        }
    }

    private fun writePayload(payload: ByteArray) {
        var output: FileOutputStream? = null
        try {
            credentialFile.parentFile?.mkdirs()
            val stream = credential.startWrite()
            output = stream
            stream.write(payload)
            credential.finishWrite(stream)
            output = null
        } catch (error: Exception) {
            output?.let { credential.failWrite(it) }
            throw StorageException("无法保存 DeepSeek API Key", error)
        }
    }

    private fun encrypt(clearBytes: ByteArray): EncryptedBytes {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, encryptionKey(createIfMissing = true))
            }
            val ciphertext = cipher.doFinal(clearBytes)
            val iv = cipher.iv.copyOf()
            check(iv.size == GCM_IV_BYTES) { "Android Keystore returned an invalid GCM IV" }
            EncryptedBytes(iv, ciphertext)
        } catch (error: Exception) {
            throw StorageException("无法加密 DeepSeek API Key", error)
        }
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, encryptionKey(createIfMissing = false), GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(ciphertext)
            }
        } catch (error: StorageException) {
            throw error
        } catch (error: Exception) {
            throw StorageException("无法解密 DeepSeek API Key", error)
        }
    }

    private fun encryptionKey(createIfMissing: Boolean): SecretKey {
        return try {
            val keyStore = keyStore()
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing
            if (!createIfMissing || keyStore.containsAlias(KEY_ALIAS)) {
                throw StorageException("DeepSeek API Key 加密密钥已失效，请重新输入 API Key")
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(AES_KEY_BITS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        } catch (error: StorageException) {
            throw error
        } catch (error: Exception) {
            throw StorageException("无法访问 Android Keystore", error)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun normalizeApiKey(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "DeepSeek API Key 不能为空" }
        require(normalized.length <= MAX_API_KEY_CHARS) { "DeepSeek API Key 长度无效" }
        require(normalized.none { it.isWhitespace() || it.isISOControl() }) {
            "DeepSeek API Key 不能包含空白或控制字符"
        }
        return normalized
    }

    private data class EncryptedBytes(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dafeiyu_deepseek_api_key_aes_v1"
        private const val CREDENTIAL_FILE_NAME = ".deepseek_api_key.v1.bin"
        private const val FORMAT_VERSION: Byte = 1
        private const val TRANSACTION_FORMAT_VERSION: Byte = 2
        private const val AES_KEY_BITS = 256
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val MIN_PAYLOAD_BYTES = 2 + GCM_IV_BYTES + GCM_TAG_BYTES + 1
        private const val MAX_API_KEY_CHARS = 512
        private const val MAX_PAYLOAD_BYTES = 16 * 1024
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val STORAGE_LOCK = Any()
    }
}
