package com.dafeiyu.mobile.data

import com.android.apksig.ApkVerifier
import com.android.apksig.util.DataSink
import com.android.apksig.util.DataSource
import com.android.apksig.util.DataSources
import java.io.File
import java.io.RandomAccessFile
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.nio.file.Files

/** Verify the APK's actual signed bytes, rather than trusting its certificate entry alone. */
object AndroidApkIntegrity {
    fun verify(file: File, manifest: AndroidReleaseManifest, deviceSdk: Int,
               cancellationProbe: () -> Unit = {}): String {
        cancellationProbe()
        require(!Files.isSymbolicLink(file.toPath())) { "更新文件不可为链接" }
        require(file.isFile && file.length() == manifest.byteSize) { "更新文件大小不匹配" }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var readBytes = 0L
            while (true) {
                cancellationProbe()
                val count = input.read(buffer)
                if (count < 0) break
                readBytes += count
                require(readBytes <= manifest.byteSize) { "更新文件在校验期间发生变化" }
                hash.update(buffer, 0, count)
                cancellationProbe()
            }
            require(readBytes == manifest.byteSize) { "更新文件在校验期间发生变化" }
        }
        cancellationProbe()
        require(hash.digest().toHex() == manifest.sha256) { "更新文件摘要校验失败" }
        val nativeAbis = ZipFile(file).use { zip ->
            val abis = mutableSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                cancellationProbe()
                val name = entries.nextElement().name
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    val parts = name.split('/')
                    require(parts.size == 3)
                    abis += parts[1]
                }
            }
            abis
        }
        cancellationProbe()
        require(nativeAbis == manifest.abis.toSet()) { "更新 APK 的处理器架构与清单不匹配" }
        // apksig 8.9.2 accepts a public DataSource. Keep its real verifier and probe every file chunk,
        // including slices used by the single-threaded v2/v3 content digester.
        val result = RandomAccessFile(file, "r").use { input ->
            val source = CancellationAwareApkDataSource(DataSources.asDataSource(input, 0, input.length()), cancellationProbe)
            ApkVerifier.Builder(source).setMinCheckedPlatformVersion(manifest.minSdk)
                .setMaxCheckedPlatformVersion(maxOf(deviceSdk, manifest.minSdk)).build().verify()
        }
        cancellationProbe()
        require(result.isVerified && (result.isVerifiedUsingV2Scheme || result.isVerifiedUsingV3Scheme ||
            result.isVerifiedUsingV31Scheme)) { "更新 APK 签名校验失败" }
        val signer = result.signerCertificates.singleOrNull() ?: error("更新 APK 发行证书数量不匹配")
        val certificateHash = MessageDigest.getInstance("SHA-256").digest(signer.encoded).toHex()
        require(certificateHash == manifest.signingCertificateSha256) { "更新 APK 发行证书不匹配" }
        cancellationProbe()
        return certificateHash
    }

    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** An adapter, not an APK/signature parser. Slices retain the same caller-owned cancellation probe. */
internal class CancellationAwareApkDataSource(
    private val delegate: DataSource,
    private val cancellationProbe: () -> Unit,
) : DataSource {
    override fun size(): Long {
        cancellationProbe()
        return delegate.size()
    }

    override fun slice(offset: Long, size: Long): DataSource {
        cancellationProbe()
        return CancellationAwareApkDataSource(delegate.slice(offset, size), cancellationProbe)
    }

    override fun feed(offset: Long, size: Long, sink: DataSink) {
        cancellationProbe()
        val region = delegate.slice(offset, size)
        val probedSink = object : DataSink {
            override fun consume(buf: ByteArray, offset: Int, length: Int) {
                cancellationProbe()
                sink.consume(buf, offset, length)
                cancellationProbe()
            }

            override fun consume(buf: ByteBuffer) {
                cancellationProbe()
                sink.consume(buf)
                cancellationProbe()
            }
        }
        var read = 0L
        while (read < size) {
            cancellationProbe()
            val count = minOf(CHUNK_BYTES.toLong(), size - read)
            region.feed(read, count, probedSink)
            read += count
            cancellationProbe()
        }
    }

    override fun copyTo(offset: Long, size: Int, dest: ByteBuffer) {
        cancellationProbe()
        val region = delegate.slice(offset, size.toLong())
        if (size > dest.remaining()) throw BufferOverflowException()
        var read = 0
        while (read < size) {
            cancellationProbe()
            val count = minOf(CHUNK_BYTES, size - read)
            region.copyTo(read.toLong(), count, dest)
            read += count
            cancellationProbe()
        }
    }

    override fun getByteBuffer(offset: Long, size: Int): ByteBuffer {
        cancellationProbe()
        val region = slice(offset, size.toLong())
        val buffer = ByteBuffer.allocate(size)
        region.copyTo(0, size, buffer)
        buffer.flip()
        return buffer
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}
