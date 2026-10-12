package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import com.dafeiyu.mobile.R
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Prepares the immutable shared package; runtime loading is owned by the host. */
class ConnectionPluginPackage(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "connection-plugin").apply { mkdirs() }
    private val prepared = AtomicFile(File(root, "prepared.json"))
    private val legacy = AtomicFile(File(root, "active.json"))

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

    suspend fun ensureBundled(): File = withContext(Dispatchers.IO) {
        lock.withLock {
            current()?.let { return@withLock it }
            val manifest = context.assets.open("connection-plugin/manifest.json").use {
                ConnectionPackageValidator.manifest(ConnectionPackageValidator.readBounded(it, ConnectionPackageValidator.MAX_MANIFEST))
            }
            context.assets.open("connection-plugin/${manifest.fileName}").use {
                install(manifest, ConnectionPackageValidator.readBounded(it, ConnectionPackageValidator.MAX_ARCHIVE))
            }
        }
    }

    suspend fun update(baseUrl: String = DeveloperCenterPresenceClient.DEFAULT_BASE_URL): String =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val base = URI(baseUrl)
                require(base.scheme == "https" && base.host != null && base.userInfo == null &&
                    base.rawQuery == null && base.fragment == null && base.path.orEmpty() in setOf("", "/"))
                val manifest = ConnectionPackageValidator.manifest(
                    fetch(base.resolve("/api/connection-plugin/releases/current"), ConnectionPackageValidator.MAX_MANIFEST))
                val archive = fetch(base.resolve(manifest.archivePath), ConnectionPackageValidator.MAX_ARCHIVE)
                install(manifest, archive)
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

    private fun fetch(uri: URI, limit: Int): ByteArray {
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000; connection.readTimeout = 20000
            val ca = context.resources.openRawResource(R.raw.dafeiyu_download_ca).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null); setCertificateEntry("dafeiyu", ca)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            connection.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
            connection.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            require(connection.responseCode == 200 && connection.contentLengthLong <= limit) { "plugin_download_failed" }
            return connection.inputStream.use { input ->
                ConnectionPackageValidator.readBounded(input, limit)
            }
        } finally { connection.disconnect() }
    }

    companion object {
        private val lock = Mutex()
        private val storageLock = Any()
        private const val MAX_RECEIPT = 2 * ConnectionPackageValidator.MAX_MANIFEST + 1024
    }
}
