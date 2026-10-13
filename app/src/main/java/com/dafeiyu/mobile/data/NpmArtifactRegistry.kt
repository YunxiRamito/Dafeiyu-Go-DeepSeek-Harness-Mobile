package com.dafeiyu.mobile.data

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.json.JSONObject

/** A short-lived loopback registry. pnpm gets only integrity-verified tarballs through the common download layer. */
class NpmArtifactRegistry private constructor(
    private val cache: File,
    private val source: String,
    private val progress: (ArtifactDownloadProgress) -> Unit,
    private val upstreamOrigin: String,
    private val downloadArtifact: suspend (ArtifactDownloadRequest, String, (ArtifactDownloadProgress) -> Unit) -> ArtifactDownloadResult,
) : AutoCloseable {
    constructor(context: Context, source: String, progress: (ArtifactDownloadProgress) -> Unit = {}) : this(
        File(context.applicationContext.filesDir, "dsh-extension-artifacts"), source, progress, "https://registry.npmjs.org",
        ComponentDownloadClient(context.applicationContext)::downloadArtifact,
    )
    /** An internal offline transport seam; the application always pins the official npm metadata origin above. */
    internal constructor(cache: File, upstreamOrigin: String, downloader: ArtifactDownloader,
        progress: (ArtifactDownloadProgress) -> Unit = {}) : this(cache, "official", progress, upstreamOrigin,
        { request, _, report -> downloader.download(request.copy(allowHttpForTests = true), report) })
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val limit = Semaphore(8)
    private val token = ByteArray(24).apply { SecureRandom().nextBytes(this) }.joinToString("") { "%02x".format(it) }
    val url: String = "http://127.0.0.1:${server.localPort}/$token/"
    private val artifacts = ConcurrentHashMap<String, NpmTarball>()
    private val artifactLocks = ConcurrentHashMap<String, Mutex>()
    private val packuments = ConcurrentHashMap<String, JSONObject>()

    init {
        ExtensionFiles.directory(cache)
        scope.launch {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                if (connections.size >= 16) { socket.close(); continue }
                connections += socket
                launch {
                    try { limit.withPermit { handle(socket) } }
                    finally { connections -= socket; runCatching { socket.close() } }
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun metadata(name: String): JSONObject {
        require(NpmExtensionSpec.isValid(name) && NpmExtensionSpec.packageName(name) == name)
        val uri = upstreamOrigin + "/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val call = http.newCall(Request.Builder().url(uri).header("Accept", "application/vnd.npm.install-v1+json").build())
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
        try { return call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("npm 元数据 HTTP ${response.code}")
            JSONObject(ExtensionFiles.utf8(requireNotNull(response.body).byteStream().use { ExtensionFiles.bytes(it, 8 * 1024 * 1024L) }))
        } } finally { cancellation?.dispose() }
    }

    suspend fun resolve(spec: String): JSONObject {
        require(NpmExtensionSpec.isValid(spec)) { "请输入 npm 包名或包名@版本" }
        val name = NpmExtensionSpec.packageName(spec)
        val document = packuments[name] ?: rewrite(name, metadata(name)).also { packuments.putIfAbsent(name, it) }
        val requested = spec.removePrefix(name).removePrefix("@").ifBlank { "latest" }
        val version = document.optJSONObject("dist-tags")?.optString(requested)?.takeIf { it.isNotBlank() } ?: requested
        return document.getJSONObject("versions").optJSONObject(version)
            ?: throw IOException("没有该 npm 版本，或其摘要／依赖源不满足校验要求")
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val header = ByteArrayOutputStream()
            var previous = 0
            while (header.size() < 16_384) {
                val value = input.read()
                require(value >= 0)
                header.write(value)
                previous = (previous shl 8) or value
                if (previous == 0x0d0a0d0a) break
            }
            require(previous == 0x0d0a0d0a)
            val lines = header.toString(Charsets.US_ASCII.name()).split("\r\n")
            val start = lines.first().split(' ')
            require(start.size == 3 && start[0] in setOf("GET", "HEAD") && start[2] == "HTTP/1.1")
            val request = URI(start[1])
            require(!request.isAbsolute && request.rawQuery == null && request.rawFragment == null)
            val prefix = "/$token/"
            require(request.rawPath.startsWith(prefix)) { "registry token mismatch" }
            val path = request.rawPath.removePrefix(prefix)
            val method = start[0]
            if (path.startsWith("artifact/")) {
                val id = path.removePrefix("artifact/")
                val descriptor = artifacts[id] ?: throw IOException("npm 包摘要尚未登记")
                val file = File(cache, "$id.tgz")
                serveArtifact(socket, descriptor, file, method)
            } else {
                val decoded = URLDecoder.decode(path, "UTF-8")
                val segments = decoded.split('/')
                val name = if (decoded.startsWith('@')) segments.take(2).joinToString("/") else segments.first()
                require(NpmExtensionSpec.isValid(name) && NpmExtensionSpec.packageName(name) == name)
                val offset = if (name.startsWith('@')) 2 else 1
                require(segments.size in offset..offset + 1 || segments.size == offset + 2 && segments[offset] == "-")
                check(packuments.size <= 256) { "插件依赖数超过安装上限" }
                val document = packuments[name] ?: rewrite(name, metadata(name)).also { packuments.putIfAbsent(name, it) }
                if (segments.size == offset + 2) {
                    val basename = name.substringAfter('/') + "-"
                    val filename = segments.last()
                    require(filename.startsWith(basename) && filename.endsWith(".tgz"))
                    val version = filename.removePrefix(basename).removeSuffix(".tgz")
                    val entry = document.getJSONObject("versions").optJSONObject(version) ?: throw IOException("npm 锁定版本不存在")
                    val id = entry.getJSONObject("dist").getString("tarball").substringAfterLast('/')
                    val descriptor = artifacts[id] ?: throw IOException("npm 摘要尚未登记")
                    serveArtifact(socket, descriptor, File(cache, "$id.tgz"), method)
                    return
                }
                val body = if (segments.size == offset) document else {
                    val version = segments.last()
                    val selected = document.optJSONObject("dist-tags")?.optString(version)?.takeIf { it.isNotBlank() } ?: version
                    document.getJSONObject("versions").optJSONObject(selected) ?: throw IOException("指定的 npm 版本没有可信摘要或不受支持")
                }
                val bytes = body.toString().toByteArray()
                respond(socket, 200, "application/json", bytes.size.toLong(), method == "HEAD") { it.write(bytes) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val body = "{\"error\":\"Verified mobile registry rejected this request; inspect installation diagnostics\"}".toByteArray()
            runCatching { respond(socket, 502, "application/json", body.size.toLong(), false) { it.write(body) } }
        }
    }

    private suspend fun serveArtifact(socket: Socket, descriptor: NpmTarball, file: File, method: String) {
        artifactLocks.computeIfAbsent(file.name) { Mutex() }.withLock {
            downloadArtifact(ArtifactDownloadRequest(listOf(descriptor.url), file, descriptor.checksum,
                maxBytes = ExtensionFiles.MAX_PACKAGE_BYTES), source, progress)
            verifyPackage(file, descriptor.name, descriptor.version)
        }
        respond(socket, 200, "application/octet-stream", file.length(), method == "HEAD") { output -> file.inputStream().use { it.copyTo(output) } }
    }

    private fun rewrite(name: String, metadata: JSONObject): JSONObject {
        require(metadata.getString("name") == name)
        val input = metadata.getJSONObject("versions")
        val versions = JSONObject()
        val keys = input.keys().asSequence().toList()
        require(keys.size <= 20_000)
        for (version in keys) {
            val item = input.getJSONObject(version)
            val dist = item.optJSONObject("dist") ?: continue
            val integrity = dist.optString("integrity")
            val checksum = runCatching { ArtifactChecksum.parse(integrity) }.getOrNull() ?: continue
            val tarball = dist.getString("tarball")
            val uri = URI(tarball)
            val pinnedOrigin = URI(upstreamOrigin)
            require(uri.scheme == pinnedOrigin.scheme && uri.host == pinnedOrigin.host && uri.port == pinnedOrigin.port &&
                uri.userInfo == null && uri.fragment == null && uri.rawQuery == null) {
                "npm tarball 未来自官方注册表"
            }
            // Unsafe dependency versions are omitted, so pnpm cannot bypass the controlled downloader with Git/URL dependencies.
            if (!dependencySourcesAllowed(item)) continue
            val id = checksum.algorithm.lowercase().replace('-', '_') + "-" + checksum.value
            check(artifacts.size < 100_000)
            val descriptor = NpmTarball(name, version, tarball, checksum)
            val previous = artifacts.putIfAbsent(id, descriptor)
            require(previous == null || previous == descriptor) { "同一 npm 摘要对应了不同的包身份" }
            val copy = JSONObject(item.toString())
            copy.getJSONObject("dist").put("tarball", url + "artifact/$id")
            copy.remove("readme")
            versions.put(version, copy)
        }
        require(versions.length() > 0) { "npm 包没有可校验的版本或引用了不支持的依赖源" }
        val tags = JSONObject()
        metadata.optJSONObject("dist-tags")?.let { inputTags -> inputTags.keys().forEach { tag ->
            val version = inputTags.getString(tag)
            if (versions.has(version)) tags.put(tag, version)
        } }
        return JSONObject().put("name", name).put("versions", versions).put("dist-tags", tags)
    }

    private fun respond(socket: Socket, status: Int, type: String, size: Long, head: Boolean,
        body: (java.io.OutputStream) -> Unit) {
        val output = socket.getOutputStream()
        output.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Gateway"}\r\nContent-Type: $type\r\n" +
            "Content-Length: $size\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray(Charsets.US_ASCII))
        if (!head) body(output)
        output.flush()
    }

    override fun close() {
        scope.cancel()
        runCatching { server.close() }
        connections.forEach { runCatching { it.close() } }
        packuments.clear(); artifacts.clear()
        artifactLocks.clear()
    }

    private data class NpmTarball(val name: String, val version: String, val url: String, val checksum: ArtifactChecksum)

    companion object {
        internal fun dependencySourcesAllowed(manifest: JSONObject): Boolean {
            if (manifest.has("pnpm")) {
                val pnpm = manifest.optJSONObject("pnpm") ?: return false
                if (pnpm.has("patchedDependencies") || pnpm.has("overrides") || pnpm.has("packageExtensions")) return false
            }
            for (field in listOf("dependencies", "optionalDependencies", "peerDependencies")) {
                val dependencies = manifest.optJSONObject(field) ?: continue
                for (key in dependencies.keys()) {
                    if (!NpmExtensionSpec.isValid(key) || NpmExtensionSpec.packageName(key) != key) return false
                    val value = dependencies.opt(key) as? String ?: return false
                    if (value.length > 240 || value.isBlank() || value.any { it.code < 32 }) return false
                    if (value.startsWith("npm:")) {
                        val alias = value.removePrefix("npm:")
                        val versionSeparator = alias.indexOf('@', 1)
                        if (versionSeparator < 0 || !NpmExtensionSpec.isValid(alias.substring(0, versionSeparator)) ||
                            !versionRange(alias.substring(versionSeparator + 1))) return false
                    } else if (!versionRange(value)) return false
                }
            }
            return true
        }

        private fun versionRange(value: String): Boolean = value.matches(Regex("[a-zA-Z0-9.*+~^<>=| _-]{1,240}")) &&
            value.none { it in setOf(':', '/', '\\', '@') }

        internal suspend fun verifyPackage(file: File, name: String, version: String) {
            var manifest: JSONObject? = null
            var bytes = 0L
            var files = 0
            TarArchiveInputStream(GZIPInputStream(file.inputStream())).use { input ->
                val paths = HashSet<String>()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = input.nextEntry ?: break
                    require(entry.linkFlag in setOf(TarConstants.LF_NORMAL, TarConstants.LF_OLDNORM, TarConstants.LF_DIR)) {
                        "npm 包包含链接或特殊文件"
                    }
                    val path = ExtensionFiles.relative(entry.name)
                    require(paths.add(path.lowercase()) && ++files <= 20_000 && entry.size in 0..512 * 1024 * 1024L)
                    bytes += entry.size
                    require(bytes <= 512 * 1024 * 1024L) { "npm 包展开过大" }
                    if (path == "package/package.json") manifest = JSONObject(ExtensionFiles.utf8(ExtensionFiles.bytes(input, 1024 * 1024L)))
                }
            }
            val value = requireNotNull(manifest) { "npm 包没有 package.json" }
            require(value.getString("name") == name && value.getString("version") == version && dependencySourcesAllowed(value)) {
                "npm 包身份或依赖来源与目录不一致"
            }
        }
    }
}
