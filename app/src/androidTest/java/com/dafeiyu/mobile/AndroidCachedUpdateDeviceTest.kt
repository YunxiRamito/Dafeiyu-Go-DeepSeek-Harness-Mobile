package com.dafeiyu.mobile

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dafeiyu.mobile.data.AndroidReleaseClient
import com.dafeiyu.mobile.data.AndroidReleaseManifestCodec
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real signed release files, Android AtomicFile and the installed formal
 * PackageManager identity. Only UUID-owned test caches are read or changed. */
@RunWith(AndroidJUnit4::class)
class AndroidCachedUpdateDeviceTest {
    @Test
    fun authenticatedCacheSurvivesClientRecreation(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        check(app.packageName == "com.dafeiyu.mobile.dev")
        val args = InstrumentationRegistry.getArguments()
        val id = checkNotNull(args.getString("cached_update_run_id"))
        check(UUID.fromString(id).toString() == id)
        val input = owned(app.filesDir, "android-cached-update-input/$id")
        val work = owned(app.filesDir, "android-cached-update/$id")
        check(input.isDirectory && !work.exists() && work.mkdirs())
        val data = File(work, "files").apply { check(mkdir()) }
        val cache = File(work, "cache").apply { check(mkdir()) }
        val vault = File(work, "vault").apply { check(mkdir()) }
        val reportFile = File(app.filesDir, "android-cached-update-report.json")
        val checks = JSONArray()
        var launches = 0
        val report = JSONObject().put("schema", 1).put("test", "AndroidCachedUpdateDeviceTest")
            .put("runId", id).put("hostPackage", app.packageName).put("releasePackage", FORMAL_PACKAGE)
            .put("sdk", Build.VERSION.SDK_INT).put("deviceAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("packageManager", "actual installed formal package; no mock")
            .put("atomicFile", "actual Android cache metadata recovery")
            .put("paidModelRequests", 0).put("networkRequests", 0).put("permissionRequests", 0)
            .put("installationLaunches", 0).put("checks", checks).put("passed", false)
        fun persist() { reportFile.writeText(report.toString(2), Charsets.UTF_8) }
        suspend fun checkCase(name: String, action: suspend (JSONObject) -> Unit) {
            val detail = JSONObject()
            val item = JSONObject().put("name", name).put("passed", false).put("evidence", detail)
            checks.put(item); persist()
            try { action(detail); item.put("passed", true) }
            catch (failure: Throwable) {
                item.put("error", failure.javaClass.simpleName).put("message", failure.message?.take(400).orEmpty())
                throw failure
            } finally { persist() }
        }
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = FORMAL_PACKAGE
            override fun getFilesDir(): File = data
            override fun getCacheDir(): File = cache
            override fun getNoBackupFilesDir(): File = vault
            override fun startActivity(intent: Intent) { launches++; error("Cache discovery must not open an activity") }
            override fun startActivity(intent: Intent, options: android.os.Bundle?) {
                launches++; error("Cache discovery must not open an activity")
            }
        }
        persist()
        try {
        val current = app.packageManager.getPackageInfo(FORMAL_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val installedCode = current.longVersionCode
        val installedVersion = current.versionName
        fun installedCertificate(): String = digest(checkNotNull(current.signingInfo).apkContentsSigners.single().toByteArray())
        val metadata = File(input, "manifest.json").readBytes()
        val signature = File(input, "manifest.sig").readText(Charsets.US_ASCII).trim()
        val expected = AndroidReleaseManifestCodec.parse(metadata, FORMAL_PACKAGE, "stable")
        val sourceApk = File(input, "candidate.apk")
        val updates = File(data, "updates")
        val versionRoot = File(updates, expected.version)
        val envelope = signature.toByteArray(Charsets.US_ASCII) + byteArrayOf(10) + metadata
        fun stageMetadata() {
            check(versionRoot.mkdirs() || versionRoot.isDirectory)
            File(versionRoot, "manifest.signed").writeBytes(envelope)
        }
        fun stageApk() { sourceApk.copyTo(File(versionRoot, "app.apk"), overwrite = true) }
        fun unusedTransport(client: AndroidReleaseClient): Boolean {
            // Inspect laziness only: no production transport, pin or signature is replaced.
            val field = AndroidReleaseClient::class.java.getDeclaredField("client\$delegate").apply { isAccessible = true }
            return !(field.get(client) as Lazy<*>).isInitialized()
        }
        suspend fun absent(): Boolean {
            val client = AndroidReleaseClient(isolated)
            val result = client.findCachedInstallableUpdate("stable").getOrThrow()
            check(unusedTransport(client))
            return result == null
        }
            checkCase("real formal baseline and higher signed candidate have the fixed release identity") { detail ->
                check(installedCode < expected.versionCode)
                check(installedCertificate() == AndroidReleaseClient.RELEASE_CERT_SHA256)
                check(sourceApk.isFile && sourceApk.length() == expected.byteSize)
                check(digest(sourceApk) == expected.sha256)
                detail.put("installedVersion", installedVersion).put("installedCode", installedCode)
                    .put("candidateVersion", expected.version).put("candidateCode", expected.versionCode)
                    .put("candidateBytes", sourceApk.length()).put("candidateSha256", expected.sha256)
                    .put("certificateSha256", installedCertificate())
            }
            checkCase("empty app-owned cache is successful absence without constructing HTTP transport") { _ -> check(absent()) }
            stageMetadata(); stageApk()
            checkCase("fresh production client discovers and fully authenticates the real APK offline") { detail ->
                val client = AndroidReleaseClient(isolated)
                val result = checkNotNull(client.findCachedInstallableUpdate().getOrThrow())
                check(result.manifest == expected && result.apk.canonicalFile == File(versionRoot, "app.apk").canonicalFile)
                check(unusedTransport(client))
                detail.put("recoveredVersion", result.manifest.version).put("recoveredCode", result.manifest.versionCode)
                    .put("transportInitialized", false).put("installerOpened", false)
            }
            checkCase("reconstructed client restores the same persistent candidate without online checks") { _ ->
                val client = AndroidReleaseClient(isolated)
                check(client.findCachedInstallableUpdate().getOrThrow()?.manifest == expected)
                check(unusedTransport(client))
            }
            checkCase("wrong requested channel does not trust a stable cached release") { _ ->
                val client = AndroidReleaseClient(isolated)
                check(client.findCachedInstallableUpdate("preview").getOrThrow() == null)
                check(unusedTransport(client))
            }
            checkCase("modified issuer-signed metadata cannot authenticate the cached APK") { _ ->
                val changed = JSONObject(metadata.toString(Charsets.UTF_8)).put("releaseNotes", "tampered").toString().toByteArray()
                File(versionRoot, "manifest.signed").writeBytes(signature.toByteArray() + byteArrayOf(10) + changed)
                check(absent()); stageMetadata()
            }
            checkCase("unsigned metadata cannot make a complete APK installable") { _ ->
                File(versionRoot, "manifest.signed").writeBytes(metadata)
                check(absent()); stageMetadata()
            }
            checkCase("interrupted or truncated APK is not returned as a valid local update") { _ ->
                java.io.RandomAccessFile(File(versionRoot, "app.apk"), "rw").use { it.setLength(1024) }
                check(absent()); stageApk()
            }
            checkCase("Android symlink APK is skipped and the linked input remains unchanged") { _ ->
                val apk = File(versionRoot, "app.apk")
                check(apk.delete())
                Files.createSymbolicLink(apk.toPath(), sourceApk.toPath())
                check(absent())
                check(sourceApk.length() == expected.byteSize && digest(sourceApk) == expected.sha256)
                Files.delete(apk.toPath()); stageApk()
            }
            checkCase("real AtomicFile backup recovers committed signed metadata") { _ ->
                val manifestFile = File(versionRoot, "manifest.signed")
                File(versionRoot, "manifest.signed.bak").writeBytes(envelope)
                manifestFile.writeText("interrupted metadata")
                val client = AndroidReleaseClient(isolated)
                check(client.findCachedInstallableUpdate().getOrThrow()?.manifest == expected)
                check(manifestFile.readBytes().contentEquals(envelope) && unusedTransport(client))
            }
            checkCase("uncommitted AtomicFile pending metadata is not authenticated") { _ ->
                check(File(versionRoot, "manifest.signed").delete())
                File(versionRoot, "manifest.signed.new").writeBytes(envelope)
                check(absent())
                File(versionRoot, "manifest.signed.new").delete(); stageMetadata()
            }
            checkCase("cancelled cache recovery propagates cancellation and keeps the signed files") { _ ->
                val job = async(start = CoroutineStart.LAZY) { AndroidReleaseClient(isolated).findCachedInstallableUpdate() }
                job.cancel()
                try { job.await(); error("Cancelled discovery returned normally") }
                catch (_: CancellationException) { }
                check(File(versionRoot, "manifest.signed").readBytes().contentEquals(envelope))
                check(File(versionRoot, "app.apk").length() == expected.byteSize)
            }
            checkCase("discovery never installs, requests permissions or modifies the formal package") { detail ->
                val after = app.packageManager.getPackageInfo(FORMAL_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
                check(launches == 0 && after.longVersionCode == installedCode && after.versionName == installedVersion)
                check(digest(checkNotNull(after.signingInfo).apkContentsSigners.single().toByteArray()) == installedCertificate())
                detail.put("installerLaunches", launches).put("installedCodeAfter", after.longVersionCode)
            }
            report.put("passed", true).put("total", checks.length())
        } catch (failure: Throwable) {
            report.put("error", failure.javaClass.simpleName)
                .put("message", failure.message?.take(400).orEmpty())
            throw failure
        } finally {
            report.put("installationLaunches", launches).put("finished_at", System.currentTimeMillis())
            persist()
            check(work.canonicalPath.startsWith(app.filesDir.canonicalPath + File.separator))
            check(work.name == id && !Files.isSymbolicLink(work.toPath()))
            check(work.deleteRecursively()) { "Owned cache test directory could not be removed" }
        }
    }

    private fun owned(root: File, relative: String): File = File(root, relative).also {
        check(it.canonicalPath.startsWith(root.canonicalPath + File.separator))
        check(!Files.isSymbolicLink(it.toPath()))
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                hash.update(bytes, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object { private const val FORMAL_PACKAGE = "com.dafeiyu.mobile" }
}
