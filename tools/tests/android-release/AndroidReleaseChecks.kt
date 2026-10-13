package com.dafeiyu.mobile.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile

private var passed = 0
private fun checkCase(name: String, block: () -> Unit) { block(); passed++; println("PASS $name") }

fun main(args: Array<String>) {
    val apk = File(args[0])
    val scratch = File(args[1]).apply { mkdirs() }
    val digest = apk.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(65536)
        while (true) { val read = input.read(bytes); if (read < 0) break; hash.update(bytes, 0, read) }
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    val abis = ZipFile(apk).use { zip -> zip.entries().asSequence().map { it.name }
        .filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.distinct().toList() }
    val text = """{
      "schemaVersion":1,"version":"0.1.25","packageName":"com.dafeiyu.mobile","versionCode":26,
      "minSdk":30,"targetSdk":35,"abis":[${abis.joinToString(",") { "\"$it\"" }}],
      "sha256":"$digest","byteSize":${apk.length()},"fileName":"app.apk",
      "signingCertificateSha256":"d4341e66075d1e35909796cb5962b22f8aaed378ca50e057a1b25ed97fe0c64b",
      "apkSignatureSchemes":["v2"],"channel":"stable","buildType":"release",
      "publishedAt":"2026-10-12T00:00:00Z","releaseNotes":"修复与改进\n用户确认安装",
      "installationPolicy":{"mode":"user-confirmed","allowDowngrade":false,"allowSilentInstall":false,
      "recoveryStrategy":"higher-version-code-reissue"}}
    """.trimIndent()
    fun parse(value: String) = AndroidReleaseManifestCodec.parse(value.toByteArray(), "com.dafeiyu.mobile", "stable", Instant.parse("2026-10-12T12:00:00Z"))
    fun reject(name: String, value: String) = checkCase(name) { check(runCatching { parse(value) }.isFailure) }
    val manifest = parse(text)
    checkCase("real server fileName and typed schema") { check(manifest.fileName == "app.apk" && manifest.versionCode == 26L) }
    checkCase("legacy release notes may be omitted") { check(parse(text.replace("\"releaseNotes\":\"修复与改进\\n用户确认安装\",", "")).releaseNotes.isEmpty()) }
    reject("required file name missing", text.replace("\"fileName\":\"app.apk\",", ""))
    reject("file traversal", text.replace("app.apk", "../app.apk"))
    reject("duplicate property", text.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"))
    reject("string versionCode", text.replace("\"versionCode\":26", "\"versionCode\":\"26\""))
    reject("decimal versionCode", text.replace("\"versionCode\":26", "\"versionCode\":26.0"))
    reject("exponent versionCode", text.replace("\"versionCode\":26", "\"versionCode\":2.6e1"))
    reject("overflow versionCode", text.replace("\"versionCode\":26", "\"versionCode\":2147483648"))
    reject("null release notes", text.replace("\"releaseNotes\":\"修复与改进\\n用户确认安装\"", "\"releaseNotes\":null"))
    reject("invalid unicode escape", text.replace("修复与改进", "\\ud800"))
    reject("notes control character", text.replace("修复与改进", "\\u0001"))
    reject("unescaped JSON newline", text.replace("修复与改进", "修复\n改进"))
    reject("silent install", text.replace("\"allowSilentInstall\":false", "\"allowSilentInstall\":true"))
    reject("allow downgrade", text.replace("\"allowDowngrade\":false", "\"allowDowngrade\":true"))
    reject("nested duplicate property", text.replace("\"allowDowngrade\":false", "\"allowDowngrade\":false,\"allowDowngrade\":false"))
    reject("coerced boolean", text.replace("\"allowDowngrade\":false", "\"allowDowngrade\":\"false\""))
    reject("wrong package", text.replace("com.dafeiyu.mobile", "com.other.package"))
    reject("wrong channel", text.replace("\"channel\":\"stable\"", "\"channel\":\"preview\""))
    reject("debug release", text.replace("\"buildType\":\"release\"", "\"buildType\":\"debug\""))
    reject("future timestamp", text.replace("2026-10-12T00:00:00Z", "2026-10-20T00:00:00Z"))
    reject("unknown field", text.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"extra\":true"))
    reject("recovery incomplete", text.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"recoveryOfVersion\":\"0.1.24\""))
    reject("old recovery code", text.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"recoveryOfVersion\":\"0.1.24\",\"supersedesVersionCode\":26"))
    checkCase("valid higher code recovery") { check(parse(text.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"recoveryOfVersion\":\"0.1.24\",\"supersedesVersionCode\":25")).recoveryOfVersion == "0.1.24") }
    checkCase("malformed UTF8 rejected") { check(runCatching { AndroidReleaseManifestCodec.parse(byteArrayOf(0xc3.toByte(), 0x28), "com.dafeiyu.mobile", "stable") }.isFailure) }
    reject("multiple JSON documents", text + "{}")
    reject("oversized manifest", " ".repeat(AndroidReleaseManifestCodec.MAX_MANIFEST_BYTES + 1))
    checkCase("actual formal APK signature and certificate") { check(AndroidApkIntegrity.verify(apk, manifest, 35) == manifest.signingCertificateSha256) }
    checkCase("wrong release certificate rejected") { check(runCatching { AndroidApkIntegrity.verify(apk, manifest.copy(signingCertificateSha256 = "0".repeat(64)), 35) }.isFailure) }
    checkCase("wrong ABI metadata rejected") { check(runCatching { AndroidApkIntegrity.verify(apk, manifest.copy(abis = listOf("x86")), 35) }.isFailure) }
    checkCase("tampered APK signature rejected even with matching hash") {
        val tampered = File(scratch, "tampered.apk")
        apk.copyTo(tampered, overwrite = true)
        try {
            RandomAccessFile(tampered, "rw").use { f -> f.seek(100); val b = f.readByte(); f.seek(100); f.writeByte(b.toInt() xor 1) }
            val hash = MessageDigest.getInstance("SHA-256").digest(tampered.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            check(runCatching { AndroidApkIntegrity.verify(tampered, manifest.copy(sha256 = hash), 35) }.isFailure)
        } finally { tampered.delete() }
    }
    println("$passed PASS / 0 FAIL; production parser and apksig, Android PackageManager/installer remain device checks")
}
