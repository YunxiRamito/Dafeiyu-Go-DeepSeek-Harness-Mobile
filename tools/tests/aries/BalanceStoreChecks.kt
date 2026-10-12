package com.dafeiyu.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** JVM provider verifies storage branches using real AES/GCM, not hardware Keystore. */
class TestKeyStore : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
    override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
    override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
    override fun engineAliases() = Collections.enumeration(keys.keys)
    override fun engineSize() = keys.size
    override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String) = false
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificateAlias(cert: Certificate): String? = null
    override fun engineGetCreationDate(alias: String) = Date()
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) = Unit
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    companion object {
        val keys = linkedMapOf<String, SecretKey>()
        var generated = 0
    }
}

class TestKeyGenerator : KeyGeneratorSpi() {
    private var alias = ""
    override fun engineInit(random: SecureRandom?) = Unit
    override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
    override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
        alias = (params as KeyGenParameterSpec).alias
    }
    override fun engineGenerateKey(): SecretKey {
        val key = KeyGenerator.getInstance("AES", "SunJCE").apply { init(256) }.generateKey()
        TestKeyStore.keys[alias] = key
        TestKeyStore.generated++
        return key
    }
}

fun main(args: Array<String>) {
    val root = File(args.single()).apply { mkdirs() }
    Security.addProvider(object : Provider("AndroidKeyStore", "1", "JVM storage fixture") {
        init {
            put("KeyStore.AndroidKeyStore", TestKeyStore::class.java.name)
            put("KeyGenerator.AES", TestKeyGenerator::class.java.name)
        }
    })
    val store = BalanceStore(Context(root))
    val file = File(root, ".deepseek_api_key.v1.bin")
    var checks = 0
    fun checkCase(name: String, action: () -> Unit) {
        action()
        checks++
        println("PASS $name")
    }
    checkCase("empty storage does not create a key") {
        check(store.readApiKeyForRequest() == null && TestKeyStore.generated == 0)
    }
    checkCase("random IV roundtrip in v1 format") {
        store.setApiKey("test-first-credential")
        check(store.hasKey() && store.readApiKeyForRequest() == "test-first-credential")
        val first = file.readBytes()
        check(first[0] == 1.toByte() && first[1] == 12.toByte())
        store.setApiKey("test-first-credential")
        check(!first.copyOfRange(2, 14).contentEquals(file.readBytes().copyOfRange(2, 14)))
        check(TestKeyStore.generated == 1)
    }
    checkCase("legacy zero IV v1 remains decryptable") {
        val iv = ByteArray(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, TestKeyStore.keys.values.single(), GCMParameterSpec(128, iv))
        }
        file.writeBytes(byteArrayOf(1, 12) + iv + cipher.doFinal("test-legacy".toByteArray()))
        check(store.readApiKeyForRequest() == "test-legacy")
    }
    checkCase("failed atomic replacement preserves existing bytes") {
        val before = file.readBytes()
        AtomicFile.failFinish = true
        try { check(runCatching { store.setApiKey("test-replacement") }.isFailure) }
        finally { AtomicFile.failFinish = false }
        check(before.contentEquals(file.readBytes()) && store.readApiKeyForRequest() == "test-legacy")
    }
    checkCase("backup-only legacy file is recovered") {
        val backup = File(file.path + ".bak")
        check(file.renameTo(backup))
        check(store.hasKey() && store.readApiKeyForRequest() == "test-legacy")
    }
    checkCase("missing alias reports invalidation without generating key or clearing payload") {
        val before = file.readBytes()
        TestKeyStore.keys.clear()
        val generated = TestKeyStore.generated
        val error = runCatching { store.readApiKeyForRequest() }.exceptionOrNull()
        check(error is BalanceStore.StorageException && error.message?.contains("已失效") == true)
        check(!store.hasKey() && TestKeyStore.generated == generated && before.contentEquals(file.readBytes()))
    }
    checkCase("explicit resave can create replacement alias") {
        store.setApiKey("test-resaved")
        check(store.readApiKeyForRequest() == "test-resaved")
    }
    checkCase("tampered GCM authentication fails and preserves payload") {
        val corrupted = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(corrupted)
        check(runCatching { store.readApiKeyForRequest() }.isFailure)
        check(corrupted.contentEquals(file.readBytes()))
    }
    checkCase("oversized payload is rejected and retained") {
        file.writeBytes(ByteArray(16385))
        check(runCatching { store.readApiKeyForRequest() }.isFailure && file.length() == 16385L)
    }
    println("Balance store checks: $checks PASS (JVM Keystore/AtomicFile fixtures)")
}
