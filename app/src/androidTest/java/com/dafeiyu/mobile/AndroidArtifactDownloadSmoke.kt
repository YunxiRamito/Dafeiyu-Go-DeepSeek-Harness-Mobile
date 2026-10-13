package com.dafeiyu.mobile

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dafeiyu.mobile.data.ArtifactChecksum
import com.dafeiyu.mobile.data.ArtifactDownloadRequest
import com.dafeiyu.mobile.data.ArtifactDownloadResult
import com.dafeiyu.mobile.data.ComponentDownloadClient
import com.dafeiyu.mobile.data.ComponentDownloadSources
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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

/** Actual Android production downloads, pinned public backend and persistent
 * HTTP Range resume. The fixed pnpm resource is downloaded as bytes only;
 * nothing is installed or executed, and no remote fixture is uploaded. */
@RunWith(AndroidJUnit4::class)
class AndroidArtifactDownloadSmoke {
    @Test
    fun actualFourPartsCancellationAndResume(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        check(app.packageName == "com.dafeiyu.mobile.dev")
        val runId = checkNotNull(InstrumentationRegistry.getArguments().getString("artifact_run_id"))
        check(UUID.fromString(runId).toString() == runId)
        val root = ownedPath(app.filesDir, "android-artifact-smoke/$runId")
        check(!root.exists() && root.mkdirs())
        val data = ownedPath(root, "files").apply { check(mkdir()) }
        val cache = ownedPath(root, "cache").apply { check(mkdir()) }
        val vault = ownedPath(root, "vault").apply { check(mkdir()) }
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = data
            override fun getCacheDir(): File = cache
            override fun getNoBackupFilesDir(): File = vault
        }
        val results = JSONArray()
        val report = JSONObject().put("schema", 1).put("test", "AndroidArtifactDownloadSmoke").put("runId", runId)
            .put("hostPackage", app.packageName).put("sdk", Build.VERSION.SDK_INT).put("started_at", System.currentTimeMillis())
            .put("paidModelRequests", 0).put("installationLaunches", 0).put("permissionRequests", 0).put("fixtureUploads", 0)
            .put("fixedResource", JSONObject().put("name", "pnpm").put("version", "10.34.5").put("upstreamUrl", SOURCE_URL)
                .put("bytes", BYTES).put("sha256", SHA256).put("integrity", INTEGRITY)
                .put("provenance", "existing linux-x86_64.lock.json and reviewed pnpm10.34.5 archive"))
            .put("checks", results).put("passed", false)
        val reportFile = File(app.filesDir, "android-artifact-smoke-report.json")
        fun persist() { reportFile.writeText(report.toString(2), Charsets.UTF_8) }
        suspend fun checkCase(name: String, block: suspend (JSONObject) -> Unit) {
            val evidence = JSONObject()
            val item = JSONObject().put("name", name).put("passed", false).put("evidence", evidence)
            results.put(item); persist()
            try { withTimeout(300_000) { block(evidence) }; item.put("passed", true) }
            catch (failure: Throwable) {
                item.put("error", failure.javaClass.simpleName).put("message", failure.message?.take(400) ?: "")
                throw failure
            } finally { persist() }
        }
        val backendUrl = ComponentDownloadSources.candidates(SOURCE_URL, "backend").single()
        check(backendUrl.startsWith(ComponentDownloadSources.BACKEND + "/api/download?url="))
        // Existing constructor injection changes only read-only observations.
        // Production ComponentDownloadClient still builds and verifies its
        // backend CA/hostname/SPKI pin, prepares the real cache and invokes
        // the unmodified ArtifactDownloader with the real source policy.
        val pinnedTemplate = ComponentDownloadClient(isolated).pinnedBackendClient()
        fun client(observer: NetworkObserver) = ComponentDownloadClient(isolated,
            pinnedTemplate.newBuilder().addNetworkInterceptor(observer).build())
        fun request(target: File) = ArtifactDownloadRequest(listOf(SOURCE_URL), target,
            ArtifactChecksum("SHA-256", SHA256), expectedSize = BYTES, maxBytes = BYTES)
        val fresh = ownedPath(data, "fresh-pnpm.tgz")
        val resumeTarget = ownedPath(data, "resumed-pnpm.tgz")
        var retained = listOf<Long>()
        var savedEtag: String? = null
        try {
            checkCase("actual production facade downloads fixed public resource through four concurrent TLS Range parts") { evidence ->
                val observer = NetworkObserver(backendUrl)
                val progressThreads = ConcurrentLinkedQueue<Int>()
                val result = client(observer).downloadArtifact(request(fresh), "backend") { progressThreads += it.threads }
                evidence.put("network", observer.json()).put("progressThreads", JSONArray(progressThreads.toSet().sorted()))
                    .put("reportedThreads", result.threads).put("source", result.source)
                    .put("singleStreamReason", result.singleStreamReason ?: JSONObject.NULL)
                    .put("sha256", digest(fresh, "SHA-256")).put("sha512Integrity", integrity(fresh))
                check(result.threads == 4 && result.resumedBytes == 0L && result.source == backendUrl)
                check(fresh.length() == BYTES && digest(fresh, "SHA-256") == SHA256 && integrity(fresh) == INTEGRITY)
                observer.requireCompletedParts(List(4) { index -> range(index, 0) })
                check(observer.maximumBodies.get() >= 2) { "No overlapping actual Range response bodies were observed" }
                evidence.put("actualNetworkConcurrencyObserved", true).put("allFourPartsObserved", true)
                    .put("fullySimultaneousFourBodiesObserved", observer.maximumBodies.get() == 4)
            }
            checkCase("actual cancellation closes live network calls and retains only validated incomplete disk parts") { evidence ->
                val observer = NetworkObserver(backendUrl)
                val downloader = client(observer)
                val requested = AtomicBoolean()
                val cancellationAt = CompletableDeferred<Long>()
                coroutineScope {
                    lateinit var pending: Deferred<ArtifactDownloadResult>
                    pending = async(start = CoroutineStart.LAZY) {
                        downloader.downloadArtifact(request(resumeTarget), "backend") { progress ->
                            if (progress.threads == 4 && progress.receivedBytes >= 128 * 1024 && requested.compareAndSet(false, true)) {
                                cancellationAt.complete(progress.receivedBytes)
                                pending.cancel(CancellationException("Test-owned user cancellation after real received data"))
                            }
                        }
                    }
                    pending.start()
                    val receivedWhenCancelled = withTimeout(30_000) { cancellationAt.await() }
                    withTimeout(10_000) { pending.cancelAndJoin() }
                    evidence.put("cancelled", pending.isCancelled).put("receivedWhenCancellationRequested", receivedWhenCancelled)
                    check(pending.isCancelled && !resumeTarget.exists()) { "Cancellation committed a final destination or did not cancel" }
                }
                val partial = ownedPath(data, ".${resumeTarget.name}.download")
                check(partial.isDirectory) { "This source did not retain validator-bound partial data for resume" }
                val metadata = JSONObject(ownedPath(partial, "metadata.json").readText())
                savedEtag = metadata.optString("etag").takeIf { it.isNotEmpty() && it != "null" }
                check(metadata.getString("url") == backendUrl && metadata.getLong("size") == BYTES && metadata.getInt("threads") == 4)
                check(metadata.getString("algorithm") == "SHA-256" && metadata.getString("checksum") == SHA256)
                check(savedEtag == "\"$SHA256\"") { "Existing backend did not supply the expected fixed-content strong ETag" }
                retained = List(4) { index ->
                    val part = ownedPath(partial, "part-$index")
                    if (part.isFile) part.length() else 0L
                }
                check(retained.indices.all { retained[it] in 0..partBytes(it) } && retained.sum() in 1 until BYTES)
                check(observer.activeBodies.get() == 0)
                check(observer.responses.any { it.isPart && it.cancelled.get() }) { "No actual network call cancellation was observed" }
                evidence.put("network", observer.json()).put("retainedPartBytes", JSONArray(retained)).put("retainedBytes", retained.sum())
                    .put("validator", savedEtag).put("finalDestinationExists", false)
                    .put("artificialNetworkDelays", false).put("productionNetworkCancellationObserved", true)
            }
            checkCase("actual resume requests retained byte offsets and commits the same fixed digest") { evidence ->
                check(retained.size == 4)
                val observer = NetworkObserver(backendUrl)
                val progress = ConcurrentLinkedQueue<Long>()
                val result = client(observer).downloadArtifact(request(resumeTarget), "backend") {
                    if (it.threads == 4) progress += it.receivedBytes
                }
                evidence.put("network", observer.json()).put("reportedThreads", result.threads).put("resumedBytes", result.resumedBytes)
                    .put("initialProgressBytes", progress.peek() ?: JSONObject.NULL).put("retainedBytesBeforeResume", retained.sum())
                    .put("retainedPartBytes", JSONArray(retained)).put("sha256", digest(resumeTarget, "SHA-256"))
                check(result.threads == 4 && result.source == backendUrl && result.resumedBytes == retained.sum())
                check(progress.peek() == retained.sum())
                check(resumeTarget.length() == BYTES && digest(resumeTarget, "SHA-256") == SHA256 && integrity(resumeTarget) == INTEGRITY)
                val required = retained.indices.filter { retained[it] < partBytes(it) }.map { range(it, retained[it]) }
                observer.requireCompletedParts(required)
                val transferred = observer.responses.filter { it.isPart }.sumOf { it.bytesRead.get() }
                check(transferred == BYTES - retained.sum())
                check(observer.responses.filter { it.isPart }.all { it.ifMatch == savedEtag && it.etag == savedEtag })
                check(!ownedPath(data, ".${resumeTarget.name}.download").exists())
                evidence.put("actualResumedNetworkBytes", transferred).put("savedBytesReused", retained.sum())
                    .put("finalValidator", savedEtag).put("temporaryPartsRemoved", true)
                    .put("actualNetworkConcurrencyObserved", observer.maximumBodies.get() >= 2)
            }
            report.put("passed", true)
        } catch (failure: Throwable) {
            report.put("failure", failure.javaClass.simpleName).put("message", failure.message?.take(400) ?: "")
            throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                check(root.canonicalPath == File(app.filesDir.canonicalFile, "android-artifact-smoke/$runId").absolutePath)
                val removed = root.deleteRecursively()
                report.put("testOwnedCleanup", removed).put("finished_at", System.currentTimeMillis()); persist()
                check(removed) { "Artifact smoke test-owned cleanup failed" }
            }
        }
    }

    private fun ownedPath(parent: File, child: String): File {
        val target = File(parent, child)
        check(!Files.isSymbolicLink(parent.toPath()) && !Files.isSymbolicLink(target.toPath()))
        check(target.canonicalFile == File(parent.canonicalFile, child).absoluteFile &&
            target.canonicalPath.startsWith(parent.canonicalPath + File.separator))
        return target
    }

    private fun digest(file: File, algorithm: String): String = rawDigest(file, algorithm).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun integrity(file: File): String = "sha512-" + java.util.Base64.getEncoder().encodeToString(rawDigest(file, "SHA-512"))
    private fun rawDigest(file: File, algorithm: String): ByteArray = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        hash.digest()
    }

    private fun partBytes(index: Int): Long = BYTES * (index + 1) / 4 - BYTES * index / 4
    private fun range(index: Int, retained: Long): String = "bytes=${BYTES * index / 4 + retained}-${BYTES * (index + 1) / 4 - 1}"

    private data class NetworkResponse(
        val range: String,
        val contentRange: String?,
        val status: Int,
        val tls: String,
        val etag: String?,
        val ifMatch: String?,
        val expectedBytes: Long,
        val isPart: Boolean,
        val started: Long = System.nanoTime(),
        val bytesRead: AtomicLong = AtomicLong(),
        val finished: AtomicBoolean = AtomicBoolean(),
        val cancelled: AtomicBoolean = AtomicBoolean(),
        @Volatile var ended: Long = 0,
        @Volatile var failure: String? = null,
    ) {
        fun json() = JSONObject().put("range", range).put("contentRange", contentRange ?: JSONObject.NULL)
            .put("status", status).put("tls", tls).put("etag", etag ?: JSONObject.NULL).put("ifMatch", ifMatch ?: JSONObject.NULL)
            .put("bytesRead", bytesRead.get()).put("expectedBytes", expectedBytes).put("finished", finished.get())
            .put("networkCallCancelled", cancelled.get()).put("readFailure", failure ?: JSONObject.NULL)
            .put("elapsedMillis", if (ended == 0L) JSONObject.NULL else (ended - started) / 1_000_000)
    }

    private class NetworkObserver(private val expectedUrl: String) : Interceptor {
        val responses = ConcurrentLinkedQueue<NetworkResponse>()
        val activeBodies = AtomicInteger()
        val maximumBodies = AtomicInteger()
        val connectionFailures = ConcurrentLinkedQueue<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            if (chain.request().url.toString() != expectedUrl) return chain.proceed(chain.request())
            val response = try { chain.proceed(chain.request()) }
            catch (failure: java.io.IOException) {
                connectionFailures += if (chain.call().isCanceled()) "cancelled-before-response" else failure.javaClass.simpleName
                throw failure
            }
            val body = response.body ?: error("Actual artifact response body is missing")
            val handshake = response.handshake ?: error("Actual backend response did not use TLS")
            val range = chain.request().header("Range") ?: "missing"
            val record = NetworkResponse(range, response.header("Content-Range"), response.code, handshake.tlsVersion.javaName,
                response.header("ETag"), chain.request().header("If-Match"), body.contentLength(), range != "bytes=0-0")
            responses += record
            if (record.isPart) {
                val count = activeBodies.incrementAndGet()
                maximumBodies.updateAndGet { previous -> maxOf(previous, count) }
            }
            fun finish(failure: Throwable? = null) {
                if (record.finished.compareAndSet(false, true)) {
                    record.cancelled.set(chain.call().isCanceled())
                    record.failure = failure?.javaClass?.simpleName
                    record.ended = System.nanoTime()
                    if (record.isPart) activeBodies.decrementAndGet()
                }
            }
            val observed = object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength(): Long = body.contentLength()
                private val source: BufferedSource = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long = try {
                        super.read(sink, byteCount).also { count -> if (count < 0) finish() else record.bytesRead.addAndGet(count) }
                    } catch (failure: Throwable) { finish(failure); throw failure }
                    override fun close() { try { super.close() } finally { finish() } }
                }.buffer()
                override fun source(): BufferedSource = source
            }
            return response.newBuilder().body(observed).build()
        }

        fun requireCompletedParts(required: List<String>) {
            check(responses.count { it.range == "bytes=0-0" && it.status == 206 } == 1)
            val parts = responses.filter { it.isPart }
            check(parts.size == required.size && parts.map { it.range }.toSet() == required.toSet())
            check(parts.all { it.status == 206 && it.finished.get() && it.failure == null &&
                !it.cancelled.get() && it.bytesRead.get() == it.expectedBytes })
            check(activeBodies.get() == 0 && connectionFailures.isEmpty())
        }

        fun json() = JSONObject().put("observer", "read-only network interceptor inherited by the actual production facade pinned backend")
            .put("responses", JSONArray(responses.map { it.json() })).put("connectionFailures", JSONArray(connectionFailures.toList()))
            .put("maxOverlappingRangeBodies", maximumBodies.get()).put("activeRangeBodies", activeBodies.get())
            .put("payloadDelaysOrReplacement", false)
    }

    companion object {
        private const val SOURCE_URL = "https://registry.npmjs.org/pnpm/-/pnpm-10.34.5.tgz"
        private const val BYTES = 4_862_438L
        private const val SHA256 = "ccb5c479cab1b00621325bfe7d4c9a8a8031e7a525d7249e275ecbec81b08db2"
        private const val INTEGRITY = "sha512-pO4F8vc2WCVb1qiYWcBlpFwopX2u+uLIk6Fo7itzFow3uR6D5X6mdlStA/AwMXRkMOi84442LgQmBfuKvIAZLg=="
    }
}
