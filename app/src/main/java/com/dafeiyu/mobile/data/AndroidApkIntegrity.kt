package com.dafeiyu.mobile.data

import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.nio.file.Files

/** Verify the APK's actual signed bytes, rather than trusting its certificate entry alone. */
object AndroidApkIntegrity {
    fun verify(file: File, manifest: AndroidReleaseManifest, deviceSdk: Int): String {
        require(!Files.isSymbolicLink(file.toPath())) { "更新文件不可为链接" }
        require(file.isFile && file.length() == manifest.byteSize) { "更新文件大小不匹配" }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var readBytes = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                readBytes += count
                require(readBytes <= manifest.byteSize) { "更新文件在校验期间发生变化" }
                hash.update(buffer, 0, count)
            }
            require(readBytes == manifest.byteSize) { "更新文件在校验期间发生变化" }
        }
        require(hash.digest().toHex() == manifest.sha256) { "更新文件摘要校验失败" }
        val nativeAbis = ZipFile(file).use { zip -> zip.entries().asSequence()
            .map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
            .map { it.split('/').also { parts -> require(parts.size == 3) }[1] }.toSet() }
        require(nativeAbis == manifest.abis.toSet()) { "更新 APK 的处理器架构与清单不匹配" }
        val result = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(manifest.minSdk)
            .setMaxCheckedPlatformVersion(maxOf(deviceSdk, manifest.minSdk)).build().verify()
        require(result.isVerified && (result.isVerifiedUsingV2Scheme || result.isVerifiedUsingV3Scheme ||
            result.isVerifiedUsingV31Scheme)) { "更新 APK 签名校验失败" }
        val signer = result.signerCertificates.singleOrNull() ?: error("更新 APK 发行证书数量不匹配")
        val certificateHash = MessageDigest.getInstance("SHA-256").digest(signer.encoded).toHex()
        require(certificateHash == manifest.signingCertificateSha256) { "更新 APK 发行证书不匹配" }
        return certificateHash
    }

    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
}
