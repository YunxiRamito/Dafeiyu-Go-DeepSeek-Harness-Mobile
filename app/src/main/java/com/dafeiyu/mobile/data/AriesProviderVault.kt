package com.dafeiyu.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Durable content keys and journal. Losing the wrapping key fails closed. */
internal class AriesProviderVault(context: Context) {
    val directory = File(context.noBackupFilesDir, "aries-provider").apply { mkdirs() }
    private val file = AtomicFile(File(directory, "state.gcm"))
    private val aad = "dafeiyu-mobile-aries-provider-v1".toByteArray()
    fun load(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            return JSONObject().put("version", 1).put("bindings", JSONObject())
                .put("mutations", JSONObject()).put("transfers", JSONObject()).put("receipts", JSONObject())
        }
        val raw = file.openRead().use { input ->
            require(file.baseFile.length() <= 16 * 1024 * 1024 + 28); input.readBytes()
        }
        require(raw.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        val clear = cipher.doFinal(raw.copyOfRange(12, raw.size))
        return try {
            JSONObject(String(clear, Charsets.UTF_8)).also {
                require(it.getInt("version") == 1)
                listOf("bindings", "mutations", "transfers", "receipts").forEach(it::getJSONObject)
            }
        } finally { clear.fill(0) }
    }
    fun save(state: JSONObject) {
        val clear = state.toString().toByteArray()
        try {
            require(clear.size <= 16 * 1024 * 1024)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad)
            write(file.baseFile, cipher.iv + cipher.doFinal(clear))
        } finally { clear.fill(0) }
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create && !store.containsAlias(ALIAS)) { "provider_key_unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    companion object {
        private const val ALIAS = "dafeiyu_aries_provider_v1"
        fun write(path: File, bytes: ByteArray) {
            path.parentFile?.mkdirs()
            val atomic = AtomicFile(path)
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
        }
    }
}
