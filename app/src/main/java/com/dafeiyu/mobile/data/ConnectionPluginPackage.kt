package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import com.dafeiyu.mobile.R
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** Prepares the immutable shared package; runtime loading is owned by the host. */
class ConnectionPluginPackage(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "connection-plugin").apply { mkdirs() }
    private val prepared = AtomicFile(File(root, "prepared.json"))
    private val legacy = AtomicFile(File(root, "active.json"))
    private val downloadClient by lazy {
        val certificates = context.resources.openRawResource(R.raw.dafeiyu_download_ca).use {
            CertificateFactory.getInstance("X.509").generateCertificates(it)
        }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); certificates.forEachIndexed { index, certificate -> setCertificateEntry("server-$index", certificate) }
        }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        val pin = "2c72728755267c607124b2ac86e431743c34af5c6069aa22bbcc401e0f626f0b"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager)
            .certificatePinner(okhttp3.CertificatePinner.Builder().add("202.189.21.218",
                "sha256/" + Base64.getEncoder().encodeToString(pin)).build())
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()
    }

    fun current(): File? = synchronized(storageLock) { currentManifest()?.let { installed(it) } }

    /** A prepared package version, never a claim that Node/DSH has loaded it. */
    fun currentVersion(): String? = synchronized(storageLock) {
        currentManifest()?.let { installed(it); it.version }
    }

    private data class PreparedReceipt(
        val current: ConnectionPackageValidator.Manifest,
        val previous: ConnectionPackageValidator.Manifest?,
    )

    private fun currentManifest(): ConnectionPackageValidator.Manifest? = readReceipt()?.current

    private fun readReceipt(): PreparedReceipt? {
        // openRead also recovers AtomicFile's backup after an interrupted replacement.
        val bytes = try {
            prepared.openRead().use { ConnectionPackageValidator.readBounded(it, MAX_RECEIPT) }
        } catch (error: java.io.FileNotFoundException) {
            if (prepared.baseFile.exists()) throw error
            return try {
                legacy.openRead().use {
                    PreparedReceipt(ConnectionPackageValidator.manifest(
                        ConnectionPackageValidator.readBounded(it, ConnectionPackageValidator.MAX_MANIFEST)), null)
                }
            } catch (missing: java.io.FileNotFoundException) {
                if (legacy.baseFile.exists()) throw missing
                null
            }
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("schema_version") == 1 && json.getString("state") == "prepared") { "plugin_receipt_invalid" }
        fun manifest(key: String) = ConnectionPackageValidator.manifest(
            json.getJSONObject(key).toString().toByteArray(Charsets.UTF_8))
        return PreparedReceipt(manifest("current"), if (json.isNull("previous")) null else manifest("previous"))
    }

    private fun writeReceipt(receipt: PreparedReceipt) {
        val json = JSONObject().put("schema_version", 1).put("state", "prepared")
            .put("current", JSONObject(receipt.current.bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")))
            .put("previous", receipt.previous?.let {
                JSONObject(it.bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
            } ?: JSONObject.NULL)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RECEIPT) { "plugin_receipt_too_large" }
        val out = prepared.startWrite()
        try { out.write(bytes); prepared.finishWrite(out) }
        catch (e: Throwable) { prepared.failWrite(out); throw e }
    }

    private fun installed(manifest: ConnectionPackageValidator.Manifest) =
        ConnectionPackageValidator.verifyInstalled(manifest, File(root, "slots/${manifest.sha256}"))

    /** Host may request this after its own load failure; no runtime success is recorded here. */
    suspend fun rollbackPrepared(): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            synchronized(storageLock) {
                val receipt = readReceipt() ?: return@synchronized null
                val previous = receipt.previous ?: return@synchronized null
                installed(previous)
                writeReceipt(PreparedReceipt(previous, receipt.current))
                previous.version
            }
        }
    }

    suspend fun ensureBundled(requiredBundledVersion: String? = null): File = withContext(Dispatchers.IO) {
        lock.withLock {
            if (requiredBundledVersion == null) current()?.let { return@withLock it }
            val manifest = context.assets.open("connection-plugin/manifest.json").use {
                ConnectionPackageValidator.manifest(ConnectionPackageValidator.readBounded(it, ConnectionPackageValidator.MAX_MANIFEST))
            }
            require(requiredBundledVersion == null || manifest.version == requiredBundledVersion) { "plugin_host_version_mismatch" }
            synchronized(storageLock) {
                currentManifest()?.takeIf { it.version == manifest.version && it.sha256 == manifest.sha256 }?.let(::installed)
            }?.let { return@withLock it }
            context.assets.open("connection-plugin/${manifest.fileName}").use {
                install(manifest, ConnectionPackageValidator.readBounded(it, ConnectionPackageValidator.MAX_ARCHIVE))
            }
        }
    }

    suspend fun update(baseUrl: String = DeveloperCenterPresenceClient.DEFAULT_BASE_URL,
        progress: (ArtifactDownloadProgress) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val base = URI(baseUrl)
                require(base.scheme == "https" && base.host != null && base.userInfo == null &&
                    base.rawQuery == null && base.fragment == null && base.path.orEmpty() in setOf("", "/"))
                val manifest = ConnectionPackageValidator.manifest(
                    fetch(base.resolve("/api/connection-plugin/releases/current"), ConnectionPackageValidator.MAX_MANIFEST))
                val downloads = File(root, "downloads")
                val downloaded = ArtifactDownloader(downloads, { downloadClient }).download(ArtifactDownloadRequest(
                    urls = listOf(base.resolve(manifest.archivePath).toString()), destination = File(downloads, "${manifest.sha256}.tgz"),
                    checksum = ArtifactChecksum("SHA-256", manifest.sha256), expectedSize = manifest.sizeBytes,
                    maxBytes = ConnectionPackageValidator.MAX_ARCHIVE.toLong(),
                ), progress).file
                val archive = downloaded.inputStream().use { ConnectionPackageValidator.readBounded(it, ConnectionPackageValidator.MAX_ARCHIVE) }
                currentCoroutineContext().ensureActive()
                install(manifest, archive)
                // Installation already committed its durable receipt. A cache
                // cleanup failure must not report the old version as retained.
                downloaded.delete()
                manifest.version
            }
        }

    internal fun install(manifest: ConnectionPackageValidator.Manifest, archive: ByteArray): File {
        ConnectionPackageValidator.verifyArchive(manifest, archive)
        val target = File(root, "slots/${manifest.sha256}")
        if (!target.exists()) {
            val stage = File(root, "stage-${UUID.randomUUID()}").apply { check(mkdir()) }
            try {
                ConnectionPackageValidator.extract(manifest, archive, stage)
                File(stage, "archive.tgz").outputStream().use { output ->
                    output.write(archive)
                    output.fd.sync()
                }
                check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                check(stage.renameTo(target)) { "plugin_slot_commit_failed" }
            } finally {
                // This is an app-created staging directory under noBackupFilesDir.
                if (stage.exists()) stage.deleteRecursively()
            }
        }
        val pkg = ConnectionPackageValidator.verifyInstalled(manifest, target)
        synchronized(storageLock) {
            val receipt = readReceipt()
            val previous = if (receipt?.current?.sha256 == manifest.sha256) receipt.previous else receipt?.current
            writeReceipt(PreparedReceipt(manifest, previous))
        }
        return pkg
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun fetch(uri: URI, limit: Int): ByteArray {
        currentCoroutineContext().ensureActive()
        val call = downloadClient.newCall(Request.Builder().url(uri.toString()).build())
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) call.cancel()
        }
        return try { call.execute().use { response ->
            require(response.code == 200 && response.body?.contentLength()?.let { it <= limit } == true) { "plugin_download_failed" }
            val bytes = requireNotNull(response.body).byteStream().use { ConnectionPackageValidator.readBounded(it, limit) }
            currentCoroutineContext().ensureActive()
            bytes
        } } finally { cancellation?.dispose() }
    }

    companion object {
        private val lock = Mutex()
        private val storageLock = Any()
        private const val MAX_RECEIPT = 2 * ConnectionPackageValidator.MAX_MANIFEST + 1024
    }
}
