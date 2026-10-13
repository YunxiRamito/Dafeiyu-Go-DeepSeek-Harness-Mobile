package com.dafeiyu.mobile

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import com.dafeiyu.mobile.data.AndroidReleaseClient
import com.dafeiyu.mobile.data.AndroidReleaseManifest
import com.dafeiyu.mobile.data.AndroidReleaseManifestCodec
import com.dafeiyu.mobile.data.ArtifactChecksum
import com.dafeiyu.mobile.data.ArtifactDownloadRequest
import com.dafeiyu.mobile.data.ArtifactDownloader
import com.dafeiyu.mobile.data.ComponentDownloadClient
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android verifier/PackageManager, signed public feed and HTTPS Range.
 * All writes are UUID-owned. The formal package context is a metadata target;
 * this test never installs an APK or claims the dev host is a formal release. */
@RunWith(AndroidJUnit4::class)
class AndroidReleaseSmoke {
    @Test
    fun actualFormalManifestApkAndNetwork(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        check(app.packageName == "com.dafeiyu.mobile.dev")
        val args = InstrumentationRegistry.getArguments()
        val runId = checkNotNull(args.getString("release_run_id")) { "Missing test-owned candidate run ID" }
        check(UUID.fromString(runId).toString() == runId)
        val expectedSha = checkNotNull(args.getString("release_sha256")) { "Missing fixed candidate SHA-256" }
        check(expectedSha.matches(Regex("[a-f0-9]{64}")))
        val expectedSize = checkNotNull(args.getString("release_size")) { "Missing fixed candidate size" }.toLong()
        check(expectedSize in 1..AndroidReleaseManifestCodec.MAX_APK_BYTES)
        val expectedVersion = checkNotNull(args.getString("release_version")) { "Missing fixed candidate version" }
        check(expectedVersion.matches(Regex("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}(?:-[A-Za-z0-9.-]+)?")))
        val expectedCode = checkNotNull(args.getString("release_code")) { "Missing fixed candidate version code" }.toLong()
        check(expectedCode in 1..Int.MAX_VALUE.toLong())
        val channel = args.getString("release_channel") ?: "stable"
        check(channel in setOf("stable", "preview"))
        val inputRoot = ownedPath(app.filesDir, "android-release-smoke-input/$runId")
        val workRoot = ownedPath(app.filesDir, "android-release-smoke/$runId")
        check(!workRoot.exists() && workRoot.mkdirs()) { "Test-owned release directory already exists or cannot be created" }
        val data = ownedPath(workRoot, "files").apply { check(mkdir()) }
        val cache = ownedPath(workRoot, "cache").apply { check(mkdir()) }
        val vault = ownedPath(workRoot, "vault").apply { check(mkdir()) }
        val results = JSONArray()
        val report = JSONObject().put("schema", 1).put("test", "AndroidReleaseSmoke").put("runId", runId)
            .put("hostPackage", app.packageName).put("releasePackage", FORMAL_PACKAGE).put("sdk", Build.VERSION.SDK_INT)
            .put("deviceAbis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("started_at", System.currentTimeMillis())
            .put("paidModelRequests", 0).put("installationLaunches", 0).put("permissionRequests", 0)
            .put("checks", results).put("passed", false)
        val reportFile = File(app.filesDir, "android-release-smoke-report.json")
        fun persist() { reportFile.writeText(report.toString(2), Charsets.UTF_8) }
        suspend fun checkCase(name: String, block: suspend (JSONObject) -> Unit) {
            val evidence = JSONObject()
            val item = JSONObject().put("name", name).put("passed", false).put("evidence", evidence)
            results.put(item); persist()
            try { withTimeout(900_000) { block(evidence) }; item.put("passed", true) }
            catch (failure: Throwable) {
                item.put("error", failure.javaClass.simpleName).put("message", failure.message?.take(400) ?: "")
                throw failure
            } finally { persist() }
        }
        // ComponentDownloadClient resolves CA resources under the actual dev
        // package. AndroidReleaseClient targets the formal identity and uses
        // that same isolated production downloader, without installed-app APIs.
        fun isolated(formalIdentity: Boolean) = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = if (formalIdentity) FORMAL_PACKAGE else app.packageName
            override fun getFilesDir(): File = data
            override fun getCacheDir(): File = cache
            override fun getNoBackupFilesDir(): File = vault
            override fun startActivity(intent: Intent) { error("Release smoke must not launch installation or permissions") }
            override fun startActivity(intent: Intent, options: android.os.Bundle?) { error("Release smoke must not launch activities") }
        }
        val downloads = ComponentDownloadClient(isolated(formalIdentity = false))
        val releases = AndroidReleaseClient(isolated(formalIdentity = true), downloads)
        try {
            val staged = ownedPath(inputRoot, "candidate.apk")
            checkCase("staged fixed formal candidate exists with exact host size and SHA-256") { evidence ->
                check(inputRoot.isDirectory && staged.isFile) { "The final fixed release candidate was not staged" }
                val hash = digest(staged.inputStream())
                evidence.put("bytes", staged.length()).put("sha256", hash).put("source", "host-staged final candidate")
                check(staged.length() == expectedSize && hash == expectedSha) { "Staged candidate differs from the fixed host artifact" }
            }
            lateinit var manifest: AndroidReleaseManifest
            checkCase("production pinned public TLS and signed manifest match the fixed formal candidate") { evidence ->
                evidence.put("endpoint", "${AndroidReleaseClient.BASE_URL}/api/android/releases/current?channel=$channel")
                    .put("transport", "unmodified production AndroidReleaseClient CA/hostname/SPKI pin")
                    .put("installedFormalIdentityChecked", false)
                // A missing feed, invalid TLS/signature or identity mismatch is
                // a failed check; there is no staged/synthetic metadata fallback.
                manifest = releases.getCurrent(channel).getOrThrow()
                evidence.put("package", manifest.packageName).put("version", manifest.version).put("versionCode", manifest.versionCode)
                    .put("bytes", manifest.byteSize).put("sha256", manifest.sha256).put("channel", manifest.channel)
                    .put("releaseCertificateSha256", manifest.signingCertificateSha256).put("manifestSignatureVerified", true)
                check(manifest.packageName == FORMAL_PACKAGE && manifest.version == expectedVersion && manifest.versionCode == expectedCode)
                check(manifest.byteSize == expectedSize && manifest.sha256 == expectedSha)
                check(manifest.signingCertificateSha256 == AndroidReleaseClient.RELEASE_CERT_SHA256)
                check(manifest.buildType == "release" && manifest.releaseNotes.isNotBlank())
                check(manifest.minSdk <= Build.VERSION.SDK_INT && manifest.abis.any { it in Build.SUPPORTED_ABIS })
            }
            checkCase("actual Android ApkVerifier and PackageManager agree on signed formal APK identity") { evidence ->
                val candidate = releases.candidateFile(manifest)
                check(candidate.canonicalPath.startsWith(data.canonicalPath + File.separator))
                check(candidate.parentFile?.mkdirs() == true || candidate.parentFile?.isDirectory == true)
                staged.copyTo(candidate, overwrite = false)
                check(releases.findVerifiedDownload(manifest).getOrThrow()?.canonicalFile == candidate.canonicalFile)
                val verifier = ApkVerifier.Builder(candidate).setMinCheckedPlatformVersion(manifest.minSdk)
                    .setMaxCheckedPlatformVersion(maxOf(Build.VERSION.SDK_INT, manifest.minSdk)).build().verify()
                check(verifier.isVerified)
                val actualSchemes = listOfNotNull("v1".takeIf { verifier.isVerifiedUsingV1Scheme },
                    "v2".takeIf { verifier.isVerifiedUsingV2Scheme }, "v3".takeIf { verifier.isVerifiedUsingV3Scheme },
                    "v3.1".takeIf { verifier.isVerifiedUsingV31Scheme })
                check(manifest.apkSignatureSchemes.all { it in actualSchemes })
                val verifierSigner = digest(checkNotNull(verifier.signerCertificates.singleOrNull()).encoded.inputStream())
                val archive = app.packageManager.getPackageArchiveInfo(candidate.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?: error("Android PackageManager refused the final candidate APK")
                val application = archive.applicationInfo ?: error("Final APK application info is missing")
                val systemSigner = digest(checkNotNull(archive.signingInfo?.apkContentsSigners?.singleOrNull()).toByteArray().inputStream())
                val abis = ZipFile(candidate).use { zip -> zip.entries().asSequence().map { it.name }
                    .filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet() }
                check(archive.packageName == FORMAL_PACKAGE && archive.versionName == manifest.version && archive.longVersionCode == manifest.versionCode)
                check(application.minSdkVersion == manifest.minSdk && application.targetSdkVersion == manifest.targetSdk)
                check(application.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
                check(abis == manifest.abis.toSet())
                check(verifierSigner == manifest.signingCertificateSha256 && systemSigner == verifierSigner)
                evidence.put("package", archive.packageName).put("version", archive.versionName).put("versionCode", archive.longVersionCode)
                    .put("abis", JSONArray(abis.sorted())).put("minSdk", application.minSdkVersion).put("targetSdk", application.targetSdkVersion)
                    .put("apkVerifierCertificateSha256", verifierSigner).put("packageManagerCertificateSha256", systemSigner)
                    .put("actualVerifiedSignatureSchemes", JSONArray(actualSchemes)).put("debuggable", false)
                    .put("productionFindVerifiedDownload", true).put("systemInstallationOpened", false)
            }
            val apkUrl = "${AndroidReleaseClient.BASE_URL}/api/android/releases/${manifest.version}/apk"
            fun downloadRequest(name: String) = ArtifactDownloadRequest(listOf(apkUrl), ownedPath(data, name),
                ArtifactChecksum("SHA-256", manifest.sha256), expectedSize = manifest.byteSize,
                maxBytes = AndroidReleaseManifestCodec.MAX_APK_BYTES)
            checkCase("production unified downloader obtains the actual public APK with Range and fixed digest") { evidence ->
                val seenThreads = ConcurrentLinkedQueue<Int>()
                val result = downloads.downloadArtifact(downloadRequest("production-network.apk"), source = "cdn") {
                    seenThreads += it.threads
                }
                evidence.put("source", result.source).put("bytes", result.file.length()).put("sha256", digest(result.file.inputStream()))
                    .put("reportedThreads", result.threads).put("progressThreads", JSONArray(seenThreads.toSet().sorted()))
                    .put("singleStreamReason", result.singleStreamReason ?: JSONObject.NULL)
                    .put("actualNetworkConcurrencyObserved", false)
                    .put("observationBoundary", "Production client has no transport observer; thread count is configuration and validated Range result")
                check(result.file.length() == manifest.byteSize && evidence.getString("sha256") == manifest.sha256)
                check(result.source == apkUrl && result.threads == 8 && result.resumedBytes == 0L) {
                    "Formal APK did not complete through fresh eight-part HTTP Range; see reportedThreads/singleStreamReason"
                }
            }
            checkCase("same production ArtifactDownloader with pinned observer records real eight-part TLS Range traffic") { evidence ->
                val observer = RangeObserver(apkUrl)
                val observedClient = downloads.pinnedBackendClient().newBuilder().addNetworkInterceptor(observer).build()
                val result = ArtifactDownloader(data, { observedClient }).download(downloadRequest("observed-network.apk"))
                evidence.put("transport", "production pinned backend client copied only to attach a read-only network interceptor")
                    .put("downloader", "unmodified production ArtifactDownloader").put("productionFacadeObserved", false)
                    .put("reportedThreads", result.threads).put("resumedBytes", result.resumedBytes)
                    .put("singleStreamReason", result.singleStreamReason ?: JSONObject.NULL)
                    .put("receivedResponses", JSONArray(observer.responses.map { it.json() }))
                    .put("maxOverlappingRangeBodies", observer.maximumBodies.get())
                    .put("fullySimultaneousEightBodiesObserved", observer.maximumBodies.get() == 8)
                check(result.threads == 8 && result.resumedBytes == 0L && result.file.length() == manifest.byteSize)
                check(digest(result.file.inputStream()) == manifest.sha256)
                val expectedRanges = (0 until 8).map { index ->
                    "bytes=${manifest.byteSize * index / 8}-${manifest.byteSize * (index + 1) / 8 - 1}"
                }.toSet()
                val parts = observer.responses.filter { it.range != "bytes=0-0" }
                check(observer.responses.count { it.range == "bytes=0-0" && it.status == 206 } == 1)
                check(parts.size == 8 && parts.map { it.range }.toSet() == expectedRanges)
                check(parts.all { it.status == 206 && it.bytesRead.get() == it.expectedBytes && it.finished.get() && it.failure == null })
                check(observer.activeBodies.get() == 0)
                check(observer.maximumBodies.get() >= 2) { "No overlapping network response bodies were observed" }
                evidence.put("sha256", manifest.sha256).put("bytes", result.file.length()).put("allEightPartsObserved", true)
            }
            report.put("passed", true)
        } catch (failure: Throwable) {
            report.put("failure", failure.javaClass.simpleName).put("message", failure.message?.take(400) ?: "")
            throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                check(workRoot.canonicalPath == File(app.filesDir.canonicalFile, "android-release-smoke/$runId").absolutePath)
                check(inputRoot.canonicalPath == File(app.filesDir.canonicalFile, "android-release-smoke-input/$runId").absolutePath)
                val workRemoved = workRoot.deleteRecursively()
                val inputRemoved = !inputRoot.exists() || inputRoot.deleteRecursively()
                report.put("testOwnedCleanup", JSONObject().put("workRemoved", workRemoved).put("stagedCandidateRemoved", inputRemoved))
                report.put("finished_at", System.currentTimeMillis()); persist()
                check(workRemoved && inputRemoved) { "Release smoke test-owned cleanup failed" }
            }
        }
    }

    private fun ownedPath(parent: File, child: String): File {
        val target = File(parent, child)
        check(!Files.isSymbolicLink(parent.toPath()) && !Files.isSymbolicLink(target.toPath()))
        check(target.canonicalFile == File(parent.canonicalFile, child).absoluteFile &&
            target.canonicalPath.startsWith(parent.canonicalPath + File.separator)) { "Test-owned release path is linked or outside its scope" }
        return target
    }

    private fun digest(input: InputStream): String = input.use { source ->
        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(64 * 1024)
        while (true) { val count = source.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private data class RangeResponse(
        val range: String,
        val contentRange: String?,
        val status: Int,
        val tlsVersion: String,
        val expectedBytes: Long,
        val isPart: Boolean,
        val started: Long = System.nanoTime(),
        val bytesRead: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(),
        val finished: AtomicBoolean = AtomicBoolean(),
        @Volatile var ended: Long = 0,
        @Volatile var failure: String? = null,
    ) {
        fun json() = JSONObject().put("range", range).put("contentRange", contentRange ?: JSONObject.NULL).put("status", status)
            .put("tlsVersion", tlsVersion).put("bytesRead", bytesRead.get()).put("expectedBytes", expectedBytes)
            .put("bodyFinished", finished.get()).put("elapsedMillis", if (ended == 0L) JSONObject.NULL else (ended - started) / 1_000_000)
            .put("readFailure", failure ?: JSONObject.NULL)
    }

    /** Observe actual received headers and overlapping response-body lifetimes.
     * Does not alter URLs, headers, TLS trust, payload bytes or download limits. */
    private class RangeObserver(private val expectedUrl: String) : Interceptor {
        val responses = ConcurrentLinkedQueue<RangeResponse>()
        val activeBodies = AtomicInteger()
        val maximumBodies = AtomicInteger()

        override fun intercept(chain: Interceptor.Chain): Response {
            check(chain.request().url.toString() == expectedUrl)
            val response = chain.proceed(chain.request())
            val body = response.body ?: error("Observed APK response body is missing")
            val range = chain.request().header("Range") ?: "missing"
            val isPart = range != "bytes=0-0"
            val handshake = response.handshake ?: error("Actual public APK response lacks TLS handshake")
            val record = RangeResponse(range, response.header("Content-Range"), response.code,
                handshake.tlsVersion.javaName, body.contentLength(), isPart)
            responses += record
            if (isPart) {
                val count = activeBodies.incrementAndGet()
                maximumBodies.updateAndGet { before -> maxOf(before, count) }
            }
            fun finish(failure: Throwable? = null) {
                if (record.finished.compareAndSet(false, true)) {
                    record.failure = failure?.javaClass?.simpleName
                    record.ended = System.nanoTime()
                    if (isPart) activeBodies.decrementAndGet()
                }
            }
            val observed = object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength(): Long = body.contentLength()
                private val tracked: BufferedSource = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long = try {
                        super.read(sink, byteCount).also { read ->
                            if (read < 0) finish() else record.bytesRead.addAndGet(read)
                        }
                    } catch (failure: Throwable) { finish(failure); throw failure }
                    override fun close() { try { super.close() } finally { finish() } }
                }.buffer()
                override fun source(): BufferedSource = tracked
            }
            return response.newBuilder().body(observed).build()
        }
    }

    companion object { private const val FORMAL_PACKAGE = "com.dafeiyu.mobile" }
}
