package com.dafeiyu.mobile.data

import java.math.BigDecimal
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import org.json.JSONArray

/** Pure codec shared by Android persistence and the offline file-recovery tests. */
internal object OfficialCredentialJournalCodec {
    // One bounded provider restore (production vault max 8 MiB), old-key
    // snapshots and recovery receipts.  Still reject oversized authenticated
    // state instead of silently dropping a pending transaction.
    const val MAX_BYTES = 10 * 1024 * 1024
    private val aad = "dafeiyu:official-deepseek-transaction:v1".toByteArray(Charsets.UTF_8)

    fun same(left: Any?, right: Any?): Boolean = when {
        left is JSONObject && right is JSONObject -> left.keys().asSequence().toSet() == right.keys().asSequence().toSet() &&
            left.keys().asSequence().all { same(left.get(it), right.get(it)) }
        left is JSONArray && right is JSONArray -> left.length() == right.length() && (0 until left.length()).all { same(left.get(it), right.get(it)) }
        // Android JSONTokener narrows small Long values to Integer. Compare
        // exact numeric values without coercing revisions through Double.
        left is Number && right is Number -> try {
            BigDecimal(left.toString()).compareTo(BigDecimal(right.toString())) == 0
        } catch (_: NumberFormatException) { false }
        else -> left == right
    }

    fun seal(document: JSONObject, key: SecretKey): ByteArray {
        OfficialDeepSeekCredentialTransaction.validatedJournal(document)
        val clear = document.toString().toByteArray(Charsets.UTF_8)
        try {
            require(clear.size <= MAX_BYTES - 64) { "INVALID_JOURNAL" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(clear)
            try {
                require(cipher.iv.size == 12)
                return byteArrayOf(1, 12) + cipher.iv + encrypted
            } finally { encrypted.fill(0) }
        } finally { clear.fill(0) }
    }

    fun open(payload: ByteArray, key: SecretKey): JSONObject {
        try {
            require(payload.size in 31..MAX_BYTES && payload[0] == 1.toByte() && payload[1] == 12.toByte()) { "INVALID_JOURNAL" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.copyOfRange(2, 14)))
            cipher.updateAAD(aad)
            val clear = cipher.doFinal(payload, 14, payload.size - 14)
            return try { OfficialDeepSeekCredentialTransaction.validatedJournal(JSONObject(clear.toString(Charsets.UTF_8))) }
            finally { clear.fill(0) }
        } catch (_: Exception) {
            // Never forward a JSON/parser/crypto error containing a private payload.
            throw IllegalStateException("INVALID_JOURNAL")
        }
    }
}
