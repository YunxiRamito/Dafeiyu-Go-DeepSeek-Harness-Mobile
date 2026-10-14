package com.dafeiyu.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/** A separate no-backup Android Keystore journal preserves both old native keys. */
internal class AndroidOfficialCredentialJournal(context: Context) : OfficialCredentialJournalPort {
    private val file = File(context.applicationContext.noBackupFilesDir, "official-deepseek-transaction.v1.enc")
    private val atomic = AtomicFile(file)
    private val fileLock = fileLocks.computeIfAbsent(file.canonicalPath) { Any() }
    override val transactionMutex = transactionLocks.computeIfAbsent(file.canonicalPath) { Mutex() }

    override fun read(): JSONObject = synchronized(fileLock) { readLocked() }

    override fun compareAndSet(expectedRevision: Long, replacement: JSONObject): JSONObject = synchronized(fileLock) {
        check(readLocked().getLong("revision") == expectedRevision && replacement.getLong("revision") == expectedRevision) { "NATIVE_CAS_CONFLICT" }
        val next = JSONObject(replacement.toString()).put("revision", Math.addExact(expectedRevision, 1))
        val payload = OfficialCredentialJournalCodec.seal(next, key())
        try {
            require(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
            val output = atomic.startWrite()
            try { output.write(payload); atomic.finishWrite(output) }
            catch (failure: Exception) { atomic.failWrite(output); throw failure }
        } finally { payload.fill(0) }
        readLocked().also {
            check(OfficialCredentialJournalCodec.same(next, it)) { "INVALID_JOURNAL" }
        }
    }

    private fun readLocked(): JSONObject {
        if (!file.exists() && !File(file.path + ".bak").exists()) return OfficialDeepSeekCredentialTransaction.emptyJournal()
        val payload = atomic.openRead().use { input ->
            val content = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            try {
                while (content.size() <= OfficialCredentialJournalCodec.MAX_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, OfficialCredentialJournalCodec.MAX_BYTES + 1 - content.size()))
                    if (count < 0) break
                    content.write(buffer, 0, count)
                }
                content.toByteArray().also { require(it.size in 31..OfficialCredentialJournalCodec.MAX_BYTES) { "INVALID_JOURNAL" } }
            } finally { buffer.fill(0) }
        }
        return try { OfficialCredentialJournalCodec.open(payload, key()) }
        finally { payload.fill(0) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(!file.exists() && !File(file.path + ".bak").exists() && !store.containsAlias(KEY_ALIAS)) { "INVALID_JOURNAL" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "dafeiyu_official_deepseek_transaction_aes_v1"
        val fileLocks = ConcurrentHashMap<String, Any>()
        val transactionLocks = ConcurrentHashMap<String, Mutex>()
    }
}
