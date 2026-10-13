package com.dafeiyu.mobile.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class AndroidInstallLaunch { INSTALLER_OPENED, SOURCE_PERMISSION_OPENED }

/** Signed metadata and APK verification. Installation is only opened by an explicit user action. */
class AndroidReleaseClient(
    context: Context,
    private val downloads: ComponentDownloadClient = ComponentDownloadClient(context),
) {
    private val app = context.applicationContext
    private val client by lazy { pinnedClient() }
    private val root = File(app.filesDir, "updates")

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun getDshCurrent(abi: String, channel: String): Result<DshRuntimeManifest> = withContext(Dispatchers.IO) {
        resultUnlessCancelled {
            require(abi in setOf("arm64-v8a", "x86_64") && channel in setOf("stable", "preview")) { "DSH 架构或通道无效" }
            val call = client.newCall(Request.Builder().url("$BASE_URL/api/dsh/linux/releases/current?abi=$abi&channel=$channel")
                .header("Accept", "application/json").build())
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true,
                invokeImmediately = true) { if (it != null) call.cancel() }
            try {
                call.execute().use { response ->
                    if (response.code == 404) error("该通道尚无可用的 DSH Linux 运行包")
                    if (!response.isSuccessful) error("DSH 版本服务返回 HTTP ${response.code}")
                    val body = response.body ?: error("DSH 运行包清单为空")
                    val bytes = body.byteStream().use { readBounded(it, AndroidReleaseManifestCodec.MAX_MANIFEST_BYTES) }
                    require(response.header("X-DSH-Manifest-Signature-Algorithm") == "rsa-sha256") { "DSH 清单签名算法不支持" }
                    verifyRawSignature(bytes, response.header("X-DSH-Manifest-Signature") ?: error("DSH 清单缺少签名"))
                    val descriptor = DshRuntimeManifest.fromJson(org.json.JSONObject(bytes.toString(Charsets.UTF_8)))
                    require(descriptor.abi == abi && descriptor.dependencyLockSha256 != null &&
                        (channel != "stable" || '-' !in descriptor.dshVersion)) { "DSH 清单与当前选择不符" }
                    descriptor
                }
            } finally { cancellation?.dispose() }
        }
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun getCurrent(channel: String = "stable"): Result<AndroidReleaseManifest> = withContext(Dispatchers.IO) {
        resultUnlessCancelled {
            require(channel in setOf("stable", "preview")) { "更新通道无效" }
            val request = Request.Builder().url("$BASE_URL/api/android/releases/current?channel=$channel")
                .header("Accept", "application/json").build()
            val call = client.newCall(request)
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true,
                invokeImmediately = true) { if (it != null) call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) error("更新服务返回 HTTP ${response.code}")
                    val body = response.body ?: error("更新清单为空")
                    require(body.contentLength() <= AndroidReleaseManifestCodec.MAX_MANIFEST_BYTES) { "更新清单过大" }
                    val bytes = body.byteStream().use { readBounded(it, AndroidReleaseManifestCodec.MAX_MANIFEST_BYTES) }
                    val encodedSignature = response.header("X-Android-Manifest-Signature") ?: error("更新清单缺少签名")
                    require(response.header("X-Android-Manifest-Signature-Algorithm") == "rsa-sha256") {
                        "不支持的更新清单签名算法"
                    }
                    val manifest = authenticateManifest(bytes, encodedSignature, channel)
                    currentCoroutineContext().ensureActive()
                    saveSignedManifest(manifest, bytes, encodedSignature)
                    manifest
                }
            } finally { cancellation?.dispose() }
        }
    }

    suspend fun download(
        manifest: AndroidReleaseManifest,
        destination: File = candidateFile(manifest),
        source: String = "cdn",
        progress: (ArtifactDownloadProgress) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        resultUnlessCancelled {
            requireTrustedManifest(manifest)
            requireInstallable(manifest)
            require(destination.canonicalFile == candidateFile(manifest).canonicalFile) { "更新存储路径无效" }
            downloads.downloadArtifact(ArtifactDownloadRequest(
                urls = listOf("$BASE_URL/api/android/releases/${Uri.encode(manifest.version)}/apk"),
                destination = destination, checksum = ArtifactChecksum("SHA-256", manifest.sha256),
                expectedSize = manifest.byteSize, maxBytes = AndroidReleaseManifestCodec.MAX_APK_BYTES,
            ), source = source, progress = progress)
            verifyCandidate(manifest, destination)
            destination
        }
    }

    fun candidateFile(manifest: AndroidReleaseManifest): File {
        require(manifest.version.matches(Regex("[A-Za-z0-9.-]{1,80}"))) { "更新版本路径无效" }
        return File(root, "${manifest.version}/app.apk")
    }

    suspend fun findVerifiedDownload(manifest: AndroidReleaseManifest): Result<File?> = withContext(Dispatchers.IO) {
        resultUnlessCancelled {
            requireTrustedManifest(manifest)
            val file = candidateFile(manifest)
            if (!file.isFile) null else { verifyCandidate(manifest, file); file }
        }
    }

    suspend fun launchUserConfirmedInstall(manifest: AndroidReleaseManifest): Result<AndroidInstallLaunch> =
        withContext(Dispatchers.IO) {
            resultUnlessCancelled {
                requireTrustedManifest(manifest)
                requireInstallable(manifest)
                val apk = candidateFile(manifest)
                verifyCandidate(manifest, apk)
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main) {
                    if (!app.packageManager.canRequestPackageInstalls()) {
                        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        require(settings.resolveActivity(app.packageManager) != null) { "系统没有安装来源授权入口" }
                        app.startActivity(settings)
                        AndroidInstallLaunch.SOURCE_PERMISSION_OPENED
                    } else {
                        val uri = FileProvider.getUriForFile(app, "${app.packageName}.attachments", apk)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        require(intent.resolveActivity(app.packageManager) != null) { "系统没有可用的安装程序" }
                        app.startActivity(intent)
                        AndroidInstallLaunch.INSTALLER_OPENED
                    }
                }
            }
        }

    private fun verifyCandidate(manifest: AndroidReleaseManifest, apk: File) {
        val signerHash = AndroidApkIntegrity.verify(apk, manifest, Build.VERSION.SDK_INT)
        require(signerHash == RELEASE_CERT_SHA256) { "更新包不是本应用发行签名" }
        val archive = app.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("无法读取更新 APK 的包信息")
        require(archive.packageName == manifest.packageName && archive.longVersionCode == manifest.versionCode &&
            archive.versionName == manifest.version) { "更新 APK 的包身份或版本与签名清单不匹配" }
        val application = archive.applicationInfo ?: error("更新 APK 应用信息缺失")
        require(application.minSdkVersion == manifest.minSdk && application.targetSdkVersion == manifest.targetSdk &&
            application.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { "更新 APK 的构建或系统要求不匹配" }
        require(certificateHash(archive.signingInfo?.apkContentsSigners?.map { it.toByteArray() }) == signerHash) {
            "系统读取的更新发行证书不匹配"
        }
    }

    private fun requireInstallable(manifest: AndroidReleaseManifest) {
        val installed = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(manifest.versionCode > installed.longVersionCode) { "只可安装更高版本的更新" }
        require(manifest.minSdk <= Build.VERSION.SDK_INT && manifest.abis.any { it in Build.SUPPORTED_ABIS }) {
            "更新不支持当前系统或处理器"
        }
        require(certificateHash(installed.signingInfo?.apkContentsSigners?.map { it.toByteArray() }) == RELEASE_CERT_SHA256) {
            "当前应用不是受信任的正式发行包"
        }
    }

    private fun certificateHash(signers: List<ByteArray>?): String {
        val signer = signers?.singleOrNull() ?: error("应用发行证书数量不匹配")
        return MessageDigest.getInstance("SHA-256").digest(signer).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun authenticateManifest(bytes: ByteArray, encodedSignature: String, channel: String): AndroidReleaseManifest {
        verifyRawSignature(bytes, encodedSignature)
        return AndroidReleaseManifestCodec.parse(bytes, app.packageName, channel).also {
            require(it.signingCertificateSha256 == RELEASE_CERT_SHA256) { "更新发行证书不在信任范围内" }
        }
    }

    private fun verifyRawSignature(bytes: ByteArray, encodedSignature: String) {
        require(encodedSignature.length in 1..2048 && encodedSignature.none(Char::isWhitespace)) { "清单签名格式无效" }
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(metadataPublicKey())
        verifier.update(bytes)
        require(verifier.verify(Base64.getDecoder().decode(encodedSignature))) { "更新清单签名校验失败" }
    }

    private fun saveSignedManifest(manifest: AndroidReleaseManifest, bytes: ByteArray, encodedSignature: String) {
        val path = File(candidateFile(manifest).parentFile, "manifest.signed")
        require(path.parentFile?.mkdirs() == true || path.parentFile?.isDirectory == true)
        val file = AtomicFile(path)
        val output = file.startWrite()
        try {
            output.write((encodedSignature + "\n").toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Throwable) { file.failWrite(output); throw failure }
    }

    private fun requireTrustedManifest(expected: AndroidReleaseManifest) {
        val path = File(candidateFile(expected).parentFile, "manifest.signed")
        val envelope = AtomicFile(path).openRead().use { readBounded(it, AndroidReleaseManifestCodec.MAX_MANIFEST_BYTES + 2049) }
        val separator = envelope.indexOf('\n'.code.toByte())
        require(separator in 1..2048) { "本地签名更新清单无效" }
        val signature = envelope.copyOfRange(0, separator).toString(Charsets.US_ASCII)
        val bytes = envelope.copyOfRange(separator + 1, envelope.size)
        require(authenticateManifest(bytes, signature, expected.channel) == expected) { "更新清单已变更，请重新检查更新" }
    }

    private fun metadataPublicKey() = app.resources.openRawResource(com.dafeiyu.mobile.R.raw.apk_metadata_public).use {
        val pem = it.readBytes().toString(Charsets.US_ASCII).replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "").filterNot(Char::isWhitespace)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pem)))
    }

    private fun pinnedClient(): OkHttpClient {
        val certificates = app.resources.openRawResource(com.dafeiyu.mobile.R.raw.dafeiyu_download_ca).use {
            CertificateFactory.getInstance("X.509").generateCertificates(it)
        }
        val store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
            load(null)
            certificates.forEachIndexed { index, certificate -> setCertificateEntry("server-$index", certificate) }
        }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        val pin = HEX_PIN.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager)
            .certificatePinner(okhttp3.CertificatePinner.Builder().add("202.189.21.218",
                "sha256/" + Base64.getEncoder().encodeToString(pin)).build())
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).followRedirects(false).build()
    }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (bytes.size() <= maximum) {
            val count = input.read(buffer, 0, minOf(buffer.size, maximum + 1 - bytes.size()))
            if (count < 0) break
            bytes.write(buffer, 0, count)
        }
        require(bytes.size() <= maximum) { "更新清单过大" }
        return bytes.toByteArray()
    }

    private suspend fun <T> resultUnlessCancelled(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(failure)
    }

    companion object {
        const val BASE_URL = "https://202.189.21.218:8787"
        const val RELEASE_CERT_SHA256 = "d4341e66075d1e35909796cb5962b22f8aaed378ca50e057a1b25ed97fe0c64b"
        private const val HEX_PIN = "2c72728755267c607124b2ac86e431743c34af5c6069aa22bbcc401e0f626f0b"
    }
}
