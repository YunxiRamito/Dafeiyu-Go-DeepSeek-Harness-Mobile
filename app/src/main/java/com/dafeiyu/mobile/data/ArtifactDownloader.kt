package com.dafeiyu.mobile.data

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

data class ArtifactChecksum(val algorithm: String, val value: String) {
    internal fun normalized(): ArtifactChecksum {
        val kind = algorithm.uppercase().replace("SHA256", "SHA-256").replace("SHA512", "SHA-512")
        require(kind in setOf("SHA-256", "SHA-512", "GIT-SHA1")) { "下载摘要算法不受支持" }
        val length = when (kind) { "GIT-SHA1" -> 40; "SHA-256" -> 64; else -> 128 }
        require(value.matches(Regex("[a-fA-F0-9]{$length}"))) { "下载摘要格式无效" }
        return ArtifactChecksum(kind, value.lowercase())
    }

    internal fun digestForSize(bytes: Long): MessageDigest {
        require(bytes >= 0)
        val digest = MessageDigest.getInstance(if (algorithm == "GIT-SHA1") "SHA-1" else algorithm)
        if (algorithm == "GIT-SHA1") digest.update("blob $bytes\u0000".toByteArray(Charsets.US_ASCII))
        return digest
    }

    companion object {
        /** Only for a blob ID from a fixed Git commit/tree; never an HTTP ETag. */
        fun gitBlob(value: String): ArtifactChecksum = ArtifactChecksum("GIT-SHA1", value).normalized()

        fun parse(value: String): ArtifactChecksum {
            val token = value.trim().split(Regex("\\s+")).firstOrNull { it.startsWith("sha512-") }
                ?: value.trim().split(Regex("\\s+")).firstOrNull { it.startsWith("sha256-") }
            if (token != null) {
                val algorithm = if (token.startsWith("sha512-")) "SHA-512" else "SHA-256"
                val decoded = Base64.getDecoder().decode(token.substringAfter('-'))
                return ArtifactChecksum(algorithm, decoded.joinToString("") { "%02x".format(it) }).normalized()
            }
            return ArtifactChecksum(if (value.length == 128) "SHA-512" else "SHA-256", value).normalized()
        }
    }
}

data class ArtifactDownloadRequest(
    val urls: List<String>,
    val destination: File,
    val checksum: ArtifactChecksum,
    val expectedSize: Long? = null,
    val maxBytes: Long = 512L * 1024L * 1024L,
    val headers: Map<String, String> = emptyMap(),
    val allowHttpForTests: Boolean = false,
    /** A production Aries adapter may serve only its own explicitly bound IPv4 loopback port. */
    val allowedLoopbackPort: Int? = null,
    /** Stable content identity for a newly bound Aries loopback; final content remains digest-bound. */
    val loopbackResumeIdentity: String? = null,
)

data class ArtifactDownloadProgress(
    val receivedBytes: Long,
    val totalBytes: Long?,
    val threads: Int,
    val source: String,
    val reason: String? = null,
)

data class ArtifactDownloadResult(
    val file: File,
    val source: String,
    val threads: Int,
    val resumedBytes: Long,
    val singleStreamReason: String?,
)

/** OkHttp transport with trial Range, validator-bound partials and checksum-before-commit. */
class ArtifactDownloader(
    private val privateRoot: File,
    private val clientForUrl: (String) -> OkHttpClient,
    private val beforeSource: suspend (String) -> Unit = {},
) {
    private data class Source(val url: String, val size: Long?, val etag: String?, val lastModified: String?,
        val range: Boolean, val reason: String?)

    private companion object {
        const val MIB = 1024L * 1024L
        val destinationLocks = Array(32) { Mutex() }
        val contentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }

    suspend fun download(request: ArtifactDownloadRequest,
        progress: (ArtifactDownloadProgress) -> Unit = {}): ArtifactDownloadResult = withContext(Dispatchers.IO) {
        val checksum = request.checksum.normalized()
        val target = checkedTarget(request.destination)
        val lock = destinationLocks[(target.path.hashCode() and Int.MAX_VALUE) % destinationLocks.size]
        lock.withLock {
            require(request.urls.isNotEmpty() && request.urls.size <= 20)
            require(request.loopbackResumeIdentity == null || request.allowedLoopbackPort != null &&
                request.loopbackResumeIdentity.matches(Regex("[a-f0-9]{64}")))
            require(request.maxBytes > 0 && (request.expectedSize == null || request.expectedSize in 0..request.maxBytes))
            require(checksum.algorithm != "GIT-SHA1" || request.expectedSize != null) { "Git 文件下载必须指定固定 tree 中的大小" }
            check(request.headers.keys.none { it.equals("Range", true) || it.equals("If-Range", true) ||
                it.equals("If-Match", true) || it.equals("Accept-Encoding", true) }) { "下载请求头与分段协议冲突" }
            if (target.isFile && (request.expectedSize == null || target.length() == request.expectedSize) &&
                hash(target, checksum) == checksum.value) {
                progress(ArtifactDownloadProgress(target.length(), target.length(), 0, "cache"))
                return@withLock ArtifactDownloadResult(target, "cache", 0, 0, null)
            }
            check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建下载目录" }
            val sources = mutableListOf<Source>()
            var lastFailure: Exception? = null
            for (url in request.urls.distinct()) {
                currentCoroutineContext().ensureActive()
                try {
                    validateUrl(url, request.allowHttpForTests, request.allowedLoopbackPort)
                    beforeSource(url)
                    val source = probe(url, request)
                    sources += source
                    if (source.range || source.size != null && source.size <= MIB) {
                        try { return@withLock transfer(source, request, checksum, target, progress) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failed: Exception) { lastFailure = failed }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failed: Exception) { currentCoroutineContext().ensureActive(); lastFailure = failed }
            }
            // Large files try every available Range-capable route before using a single stream.
            for (source in sources.filterNot { it.range }) {
                try { return@withLock transfer(source, request, checksum, target, progress) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failed: Exception) { currentCoroutineContext().ensureActive(); lastFailure = failed }
            }
            throw IOException("下载线路均失败：${lastFailure?.message ?: "没有可用线路"}", lastFailure)
        }
    }

    private suspend fun probe(url: String, request: ArtifactDownloadRequest): Source {
        val callRequest = baseRequest(url, request).header("Range", "bytes=0-0").build()
        return execute(url, callRequest) { response ->
            checkResponseTransport(response, request)
            if (response.code !in setOf(200, 206)) throw IOException("下载探测 HTTP ${response.code}")
            checkIdentityEncoding(response)
            val range = response.header("Content-Range")?.let { contentRange.matchEntire(it.trim()) }
            val ranged = response.code == 206 && range != null && range.groupValues[1] == "0" && range.groupValues[2] == "0"
            if (response.code == 206 && !ranged) throw IOException("服务器返回无效分段范围")
            val size = if (ranged) range!!.groupValues[3].toLong() else response.body?.contentLength()?.takeIf { it >= 0 }
            check(size == null || size in 0..request.maxBytes) { "文件超过下载上限" }
            check(request.expectedSize == null || size == null || request.expectedSize == size) { "文件大小与安装描述不一致" }
            val etag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') && !it.startsWith("W/") }
            val modified = response.header("Last-Modified")
            Source(url, size ?: request.expectedSize, etag, modified, ranged,
                if (!ranged) "所有候选线路均未提供可用 HTTP Range，使用单流下载" else null)
        }
    }

    private suspend fun transfer(source: Source, request: ArtifactDownloadRequest, checksum: ArtifactChecksum,
        target: File, progress: (ArtifactDownloadProgress) -> Unit): ArtifactDownloadResult {
        val threads = if (!source.range || source.size == null || source.size <= MIB) 1
            else if (source.size <= 6L * MIB) 4 else 8
        val directory = File(target.parentFile, ".${target.name}.download")
        check(!Files.isSymbolicLink(directory.toPath())) { "下载临时目录不可用" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建下载临时目录" }
        val metadata = File(directory, "metadata.json")
        val identity = JSONObject().put("schema", 1).put("url", request.loopbackResumeIdentity ?: source.url)
            .put("size", source.size ?: JSONObject.NULL).put("etag", source.etag ?: JSONObject.NULL)
            .put("modified", source.lastModified ?: JSONObject.NULL).put("threads", threads)
            .put("algorithm", checksum.algorithm).put("checksum", checksum.value).toString()
        val hasValidator = source.etag != null || source.lastModified != null
        val reuse = hasValidator && metadata.isFile && !Files.isSymbolicLink(metadata.toPath()) &&
            metadata.length() <= 16 * 1024 && runCatching { metadata.readText() == identity }.getOrDefault(false)
        if (!reuse) clearDownloadDirectory(directory)
        metadata.writeText(identity)
        val sizes = List(threads) { index ->
            source.size?.let { total -> total * (index + 1) / threads - total * index / threads }
        }
        val parts = List(threads) { index -> File(directory, "part-$index") }
        val initial = parts.mapIndexed { index, part ->
            check(!Files.isSymbolicLink(part.toPath())) { "下载分片路径不可用" }
            val length = if (reuse && source.range && part.isFile) part.length() else 0L
            if (sizes[index] != null && length > sizes[index]!!) { part.delete(); 0L } else length
        }
        val received = AtomicLong(initial.sum())
        val resumed = received.get()
        val reason = source.reason.takeIf { threads == 1 }
        val reportLock = Any()
        fun report() = synchronized(reportLock) {
            progress(ArtifactDownloadProgress(received.get(), source.size, threads, source.url, reason))
        }
        report()
        try {
            coroutineScope {
                parts.mapIndexed { index, part -> async(Dispatchers.IO) {
                    val total = source.size
                    val start = if (total == null) 0L else total * index / threads
                    val expected = sizes[index]
                    val have = initial[index]
                    if (expected != null && have == expected && source.range) return@async
                    val builder = baseRequest(source.url, request)
                    if (source.range && total != null) {
                        builder.header("Range", "bytes=${start + have}-${start + expected!! - 1}")
                        source.etag?.let { builder.header("If-Match", it) }
                            ?: source.lastModified?.let { builder.header("If-Range", it) }
                    }
                    execute(source.url, builder.build()) { response ->
                        checkResponseTransport(response, request)
                        checkIdentityEncoding(response)
                        if (source.range && total != null) {
                            check(response.code == 206) { "分段线路在下载中失效，已保留原文件" }
                            val range = response.header("Content-Range")?.let { contentRange.matchEntire(it.trim()) }
                            check(range != null && range.groupValues[1].toLong() == start + have &&
                                range.groupValues[2].toLong() == start + expected!! - 1 &&
                                range.groupValues[3].toLong() == total) { "分段响应范围不匹配" }
                        } else check(response.code == 200) { "下载响应 HTTP ${response.code}" }
                        check(source.etag == null || response.header("ETag") == source.etag) { "下载 ETag 已改变" }
                        check(source.lastModified == null || source.etag != null ||
                            response.header("Last-Modified") == source.lastModified) { "下载资源已改变" }
                        val body = response.body ?: throw IOException("下载内容为空")
                        var partSize = have
                        // Open append only for a validated resume, otherwise truncate.
                        body.byteStream().use { input -> java.io.FileOutputStream(part, have > 0).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                partSize += count
                                val all = received.addAndGet(count.toLong())
                                check(all <= request.maxBytes && (expected == null || partSize <= expected)) { "下载分片超出范围" }
                                output.write(buffer, 0, count)
                                report()
                            }
                            output.fd.sync()
                        } }
                        check(expected == null || partSize == expected) { "下载内容不完整" }
                    }
                } }.awaitAll()
            }
            currentCoroutineContext().ensureActive()
            val assembled = File(directory, "complete")
            check(!Files.isSymbolicLink(assembled.toPath()))
            val digest = checksum.digestForSize(request.expectedSize ?: 0L)
            var completeSize = 0L
            assembled.outputStream().use { output ->
                parts.forEach { part -> part.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        completeSize += count
                        check(completeSize <= request.maxBytes)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                } }
            }
            check((request.expectedSize == null || completeSize == request.expectedSize) &&
                (source.size == null || completeSize == source.size)) { "完整文件大小校验失败" }
            check(digest.digest().joinToString("") { "%02x".format(it) } == checksum.value) { "${checksum.algorithm} 校验失败" }
            currentCoroutineContext().ensureActive()
            checkedTarget(target)
            Files.move(assembled.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            clearDownloadDirectory(directory)
            directory.delete()
            return ArtifactDownloadResult(target, source.url, threads, resumed, reason)
        } catch (cancelled: CancellationException) {
            if (!hasValidator || !source.range) { clearDownloadDirectory(directory); directory.delete() }
            throw cancelled
        } catch (failed: Exception) {
            currentCoroutineContext().ensureActive()
            if (failed is IllegalStateException || !hasValidator || !source.range) {
                clearDownloadDirectory(directory); directory.delete()
            }
            throw failed
        }
    }

    private fun baseRequest(url: String, request: ArtifactDownloadRequest): Request.Builder = Request.Builder()
        .url(url).header("User-Agent", "Dafeiyu-Go-Android/ArtifactDownloader")
        .apply { request.headers.forEach { (key, value) -> header(key, value) } }
        .header("Accept-Encoding", "identity")

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun <T> execute(url: String, request: Request, body: suspend (Response) -> T): T {
        val call = clientForUrl(url).newCall(request)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) call.cancel()
        }
        try { return call.execute().use { body(it) } }
        finally { cancellation?.dispose() }
    }

    private fun checkedTarget(destination: File): File {
        val root = privateRoot.canonicalFile
        val absolute = destination.absoluteFile
        val declaredRoot = privateRoot.absoluteFile
        check(absolute.path.startsWith(declaredRoot.path + File.separator) || absolute.path.startsWith(root.path + File.separator)) {
            "下载必须位于应用私有目录"
        }
        val expected = if (absolute.path.startsWith(declaredRoot.path + File.separator))
            File(root, absolute.relativeTo(declaredRoot).path) else absolute
        check(absolute.canonicalFile == expected.absoluteFile) { "下载目录存在链接或越界路径" }
        check(!Files.isSymbolicLink(absolute.toPath())) { "下载目标不能是链接" }
        return expected
    }

    private fun validateUrl(url: String, allowHttp: Boolean, allowedLoopbackPort: Int? = null) {
        val uri = URI(url)
        require(allowedLoopbackPort == null || allowedLoopbackPort in 1..65535)
        val ownLoopback = allowedLoopbackPort != null && uri.scheme == "http" &&
            uri.host == "127.0.0.1" && uri.port == allowedLoopbackPort
        require(uri.userInfo == null && uri.fragment == null && !uri.host.isNullOrBlank() &&
            (uri.scheme == "https" || ownLoopback || allowHttp && uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost"))) {
            "下载地址必须使用 HTTPS"
        }
        if (allowedLoopbackPort != null) require(ownLoopback) { "远程文件下载只能使用本次应用内连接" }
    }

    private fun checkResponseTransport(response: Response, request: ArtifactDownloadRequest) {
        validateUrl(response.request.url.toString(), request.allowHttpForTests, request.allowedLoopbackPort)
    }

    private fun checkIdentityEncoding(response: Response) {
        check(response.header("Content-Encoding").let { it == null || it.equals("identity", true) }) {
            "下载线路压缩了分段内容，无法安全组合"
        }
    }

    private suspend fun hash(file: File, checksum: ArtifactChecksum): String {
        val digest = checksum.digestForSize(file.length())
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun clearDownloadDirectory(directory: File) {
        check(!Files.isSymbolicLink(directory.toPath()))
        directory.listFiles()?.forEach { file ->
            check(file.name == "metadata.json" || file.name == "complete" || file.name.matches(Regex("part-[0-7]"))) {
                "下载临时目录包含未知文件，已保留"
            }
            check(!file.isDirectory || Files.isSymbolicLink(file.toPath()))
            check(file.delete()) { "无法清理旧下载分片" }
        }
    }
}
