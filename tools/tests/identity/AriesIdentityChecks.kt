package com.dafeiyu.mobile.data

import android.content.Context
import java.security.KeyFactory
import java.security.Provider
import java.security.Security
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

fun main() {
    Security.addProvider(object : Provider("AndroidKeyStore", "1", "JVM identity fixture") {
        init {
            put("KeyStore.AndroidKeyStore", TestKeyStore::class.java.name)
            put("KeyGenerator.AES", TestKeyGenerator::class.java.name)
        }
    })
    var checks = 0
    fun test(name: String, action: () -> Unit) {
        action()
        checks++
        println("PASS $name")
    }
    fun prefs(context: Context) = context.getSharedPreferences("aries_device_identity", 0)
    val alias = "dafeiyu_aries_identity_aes"
    val context = Context()
    val store = AriesIdentityStore(context)
    lateinit var original: AriesIdentityStore.Identity
    test("fresh identity serializes the existing format and alias") {
        original = store.identity()
        val record = prefs(context).snapshot()
        check(record.keys == setOf("public_key", "private_key_ciphertext", "private_key_iv"))
        check(java.util.Base64.getDecoder().decode(record.getValue("private_key_iv")).size == 12)
        check(TestKeyStore.keys.containsKey(alias) && TestKeyStore.generated == 1)
        check(original.deviceId == null && original.publicKey.size == 32)
    }
    test("existing identity signs verifiable Ed25519 data across instances") {
        val second = AriesIdentityStore(context)
        check(second.identity().publicKeyBase64Url == original.publicKeyBase64Url)
        val message = "fixture-enrollment-proof".toByteArray()
        val signature = second.sign(message)
        val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0)
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(prefix + original.publicKey))
        check(Signature.getInstance("Ed25519").run { initVerify(publicKey); update(message); verify(signature) })
    }
    test("multiple fresh identities use distinct generated IVs") {
        val another = Context()
        AriesIdentityStore(another).identity()
        check(prefs(context).getString("private_key_iv", null) != prefs(another).getString("private_key_iv", null))
    }
    test("legacy caller-supplied IV record decrypts without rewrite") {
        val record = prefs(context)
        val iv = java.util.Base64.getDecoder().decode(record.getString("private_key_iv", null))
        val cipherBytes = java.util.Base64.getDecoder().decode(record.getString("private_key_ciphertext", null))
        val key = TestKeyStore.keys.getValue(alias)
        val clear = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)); doFinal(cipherBytes)
        }
        val legacyIv = ByteArray(12)
        val legacyCipher = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, legacyIv)); doFinal(clear)
        }
        clear.fill(0)
        record.edit().putString("private_key_iv", java.util.Base64.getEncoder().encodeToString(legacyIv))
            .putString("private_key_ciphertext", java.util.Base64.getEncoder().encodeToString(legacyCipher)).commit()
        val before = record.snapshot()
        check(store.identity().publicKeyBase64Url == original.publicKeyBase64Url && record.snapshot() == before)
    }
    test("missing alias errors without key generation or preference mutation") {
        val before = prefs(context).snapshot()
        val saved = TestKeyStore.keys.remove(alias)!!
        val generated = TestKeyStore.generated
        try {
            check(runCatching { store.identity() }.isFailure)
            check(runCatching { store.sign(byteArrayOf(1)) }.isFailure)
            check(runCatching { store.withSeed { it.copyOf() } }.isFailure)
            check(TestKeyStore.generated == generated && prefs(context).snapshot() == before)
        } finally { TestKeyStore.keys[alias] = saved }
    }
    test("tampered ciphertext errors and preserves identity") {
        val record = prefs(context)
        val before = record.snapshot()
        val corrupted = java.util.Base64.getDecoder().decode(before.getValue("private_key_ciphertext"))
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
        record.edit().putString("private_key_ciphertext", java.util.Base64.getEncoder().encodeToString(corrupted)).commit()
        val bad = record.snapshot()
        check(runCatching { store.identity() }.isFailure && record.snapshot() == bad)
        record.edit().putString("private_key_ciphertext", before.getValue("private_key_ciphertext")).commit()
    }
    test("every partial identity record refuses regeneration including device-only") {
        val originalRecord = prefs(context).snapshot()
        val records = originalRecord.keys.map { omitted -> originalRecord.filterKeys { it != omitted } } +
            listOf(mapOf("device_id" to "dev_" + "0".repeat(32)), mapOf("public_key" to "bad"))
        for (partial in records) {
            val isolated = Context()
            val record = prefs(isolated)
            val editor = record.edit()
            partial.forEach { (name, value) -> editor.putString(name, value) }
            editor.commit()
            val generated = TestKeyStore.generated
            val identity = AriesIdentityStore(isolated)
            check(runCatching { identity.identity() }.isFailure)
            check(runCatching { identity.sign(byteArrayOf(1)) }.isFailure)
            check(record.snapshot() == partial && TestKeyStore.generated == generated)
        }
    }
    test("invalid base64 IV is retained rather than treated as absent") {
        val isolated = Context()
        prefs(isolated).edit().putString("private_key_iv", "bad!").putString("private_key_ciphertext", "bad!").commit()
        val before = prefs(isolated).snapshot()
        check(runCatching { AriesIdentityStore(isolated).identity() }.isFailure && prefs(isolated).snapshot() == before)
    }
    test("concurrent stores create one identity and preserve one signature identity") {
        val isolated = Context()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val generated = TestKeyStore.generated
        try {
            val results = (1..16).map {
                pool.submit(Callable { start.await(); AriesIdentityStore(isolated).identity().publicKeyBase64Url })
            }
            start.countDown()
            check(results.map { it.get(10, TimeUnit.SECONDS) }.distinct().size == 1)
            check(TestKeyStore.generated == generated)
        } finally { pool.shutdownNow() }
    }
    test("concurrent first use creates only one missing encryption alias") {
        val saved = TestKeyStore.keys.remove(alias)!!
        val isolated = Context()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val generated = TestKeyStore.generated
        try {
            val results = (1..16).map {
                pool.submit(Callable { start.await(); AriesIdentityStore(isolated).identity().publicKeyBase64Url })
            }
            start.countDown()
            check(results.map { it.get(10, TimeUnit.SECONDS) }.distinct().size == 1)
            check(TestKeyStore.generated == generated + 1)
        } finally {
            pool.shutdownNow()
            TestKeyStore.keys[alias] = saved
        }
    }
    test("registered device id retained on successful reads") {
        val id = "dev_" + "a".repeat(32)
        store.setDeviceId(id)
        check(AriesIdentityStore(context).identity().deviceId == id)
    }
    println("Aries identity checks: $checks PASS (production store, JVM Keystore/prefs fixtures)")
}
