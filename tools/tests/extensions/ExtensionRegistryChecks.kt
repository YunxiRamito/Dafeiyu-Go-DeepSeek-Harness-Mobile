package com.dafeiyu.mobile.data

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONObject

private data class MetadataPause(val arrived: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))

/** Real loopback HTTP, production npm proxy/downloader, and actual pinned manager plus host pnpm. */
internal suspend fun verifyExtensionRegistry(root: File, node: String, dsh: String, helper: String, pnpm: String): Int =
    withContext(Dispatchers.IO) {
        var checks = 0
        fun verify(label: String, condition: Boolean) { check(condition) { "FAIL $label" }; checks++; println("PASS $label") }
        val fixtures = File(root, "network-fixtures").apply { check(mkdirs()) }
        val cache = File(fixtures, "cache").apply { check(mkdirs()) }
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val downloader = ArtifactDownloader(fixtures, { client })
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        val executor = Executors.newFixedThreadPool(12) { task -> Thread(task, "extension-fixture").apply { isDaemon = true } }
        server.executor = executor
        val origin = "http://127.0.0.1:${server.address.port}"
        val packuments = ConcurrentHashMap<String, JSONObject>()
        val tarballs = ConcurrentHashMap<String, ByteArray>()
        val ranges = ConcurrentHashMap<String, AtomicInteger>()
        val concurrent = ConcurrentHashMap<String, AtomicInteger>()
        val maximum = ConcurrentHashMap<String, AtomicInteger>()
        val threadCounts = ConcurrentHashMap.newKeySet<Int>()
        val metadataPause = AtomicReference<MetadataPause?>(null)
        fun packageFixture(name: String, size: Int, dependencies: JSONObject? = null): JSONObject {
            val manifest = JSONObject().put("name", name).put("version", "1.0.0").put("license", "MIT")
            dependencies?.let { manifest.put("dependencies", it) }
            val archive = File(fixtures, "$name.tgz")
            val bytes = ByteArray(size).also { kotlin.random.Random(size).nextBytes(it) }
            TarArchiveOutputStream(GZIPOutputStream(archive.outputStream())).use { output ->
                for ((path, contents) in listOf("package/package.json" to manifest.toString().toByteArray(), "package/data.bin" to bytes)) {
                    output.putArchiveEntry(TarArchiveEntry(path).apply { this.size = contents.size.toLong() })
                    output.write(contents); output.closeArchiveEntry()
                }
            }
            val payload = archive.readBytes()
            tarballs["/$name/-/$name-1.0.0.tgz"] = payload
            val integrity = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(payload))
            val version = JSONObject(manifest.toString()).put("dist", JSONObject().put("integrity", integrity)
                .put("tarball", "$origin/$name/-/$name-1.0.0.tgz"))
            packuments["/$name"] = JSONObject().put("name", name).put("dist-tags", JSONObject().put("latest", "1.0.0"))
                .put("versions", JSONObject().put("1.0.0", version))
            return version
        }
        packageFixture("fixture-four", 2 * 1024 * 1024)
        packageFixture("fixture-eight", 7 * 1024 * 1024)
        val corrupt = packageFixture("fixture-corrupt", 3_000)
        corrupt.getJSONObject("dist").put("integrity", "sha512-" + Base64.getEncoder().encodeToString(ByteArray(64)))
        packuments["/fixture-corrupt"]!!.getJSONObject("versions").put("1.0.0", corrupt)
        val foreign = packageFixture("fixture-foreign", 3_000)
        foreign.getJSONObject("dist").put("tarball", "https://example.invalid/uncontrolled.tgz")
        packuments["/fixture-foreign"]!!.getJSONObject("versions").put("1.0.0", foreign)
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                val payload = tarballs[path]
                if (payload != null) {
                    val range = exchange.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d+)").matchEntire(it) }
                    val start = range?.groupValues?.get(1)?.toInt() ?: 0
                    val end = range?.groupValues?.get(2)?.toInt() ?: (payload.size - 1)
                    check(start in payload.indices && end in start until payload.size)
                    exchange.responseHeaders.add("ETag", "\"fixture-$path\"")
                    exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                    if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                    exchange.sendResponseHeaders(if (range != null) 206 else 200, (end - start + 1).toLong())
                    val active = concurrent.computeIfAbsent(path) { AtomicInteger() }
                    val isSegment = range != null && end > start
                    if (isSegment) {
                        ranges.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                        maximum.computeIfAbsent(path) { AtomicInteger() }.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        Thread.sleep(25)
                    }
                    try { exchange.responseBody.use { output ->
                        var cursor = start
                        while (cursor <= end) {
                            val count = minOf(64 * 1024, end - cursor + 1)
                            output.write(payload, cursor, count); output.flush(); cursor += count
                            if (isSegment) Thread.sleep(2)
                        }
                    } } finally { if (isSegment) active.decrementAndGet() }
                } else {
                    val document = packuments[path]
                    if (document != null) metadataPause.get()?.let { pause ->
                        pause.arrived.countDown()
                        check(pause.release.await(15, TimeUnit.SECONDS)) { "Fixture metadata pause timed out" }
                    }
                    val body = (document?.toString() ?: "{\"error\":\"not found\"}").toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(if (document == null) 404 else 200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            } finally { exchange.close() }
        }
        server.start()
        val home = File(fixtures, "home").apply { check(mkdirs()) }
        val sources = File(fixtures, "sources").apply { check(mkdirs()) }
        val plugin = File(sources, "network-plugin").apply { check(mkdirs()) }
        File(plugin, "package.json").writeText(JSONObject().put("name", "dafeiyu-network-fixture").put("version", "1.0.0")
            .put("license", "MIT").put("dependencies", JSONObject().put("fixture-four", "1.0.0").put("fixture-eight", "1.0.0"))
            .put("dsh", JSONObject().put("bundle", JSONObject().put("patch", "cordis.patch.yml"))).toString())
        File(plugin, "cordis.patch.yml").writeText("- insert:\n    - id: never-execute\n      name: ./never-execute.mjs\n")
        File(plugin, "never-execute.mjs").writeText("throw new Error('Installation must not execute this module');")
        fun startInvocation(frame: JSONObject, selectedHome: File = home): Pair<Process, File> {
            frame.put("sourceRoot", sources.path)
            val outputFile = File(fixtures, "manager-${UUID.randomUUID()}.json")
            val pmArguments = if (pnpm.endsWith(".exe")) listOf(pnpm) else listOf(node, pnpm)
            val process = ProcessBuilder(listOf(node, helper, dsh, selectedHome.path) + pmArguments)
                .directory(fixtures).redirectOutput(outputFile).redirectError(File(fixtures, "manager-stderr.log"))
                .apply { environment().apply { put("TEMP", fixtures.path); put("TMP", fixtures.path); put("TMPDIR", fixtures.path) } }.start()
            process.outputStream.use { it.write(frame.toString().toByteArray()) }
            return process to outputFile
        }
        fun awaitInvocation(started: Pair<Process, File>): JSONObject {
            val (process, outputFile) = started
            try {
                check(process.waitFor(60, TimeUnit.SECONDS)) { "Actual network package install timed out" }
                return JSONObject(outputFile.readText())
            } finally { if (process.isAlive) {
                val handle = Process::class.java.getMethod("toHandle").invoke(process)
                val type = Class.forName("java.lang.ProcessHandle")
                @Suppress("UNCHECKED_CAST")
                val descendants = type.getMethod("descendants").invoke(handle) as java.util.stream.Stream<Any>
                descendants.use { stream -> stream.forEach { child -> type.getMethod("destroyForcibly").invoke(child) } }
                process.destroyForcibly()
            } }
        }
        fun invoke(frame: JSONObject, selectedHome: File = home): JSONObject = awaitInvocation(startInvocation(frame, selectedHome))
        fun get(url: String): Int = client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
        try {
            NpmArtifactRegistry(cache, origin, downloader) { threadCounts += it.threads }.use { registry ->
                val resolved = registry.resolve("fixture-four@latest")
                verify("npm proxy locks a tag to the actual metadata version", resolved.getString("version") == "1.0.0")
                verify("npm proxy rewrites tarballs exclusively to its loopback token", resolved.getJSONObject("dist").getString("tarball").startsWith(registry.url + "artifact/"))
                val address = java.net.URI(registry.url)
                verify("a wrong npm registry token cannot read metadata", get("http://${address.host}:${address.port}/${"b".repeat(48)}/fixture-four") == 502)
                val installed = invoke(JSONObject().put("operation", "install").put("registry", registry.url).put("spec", "file:${plugin.path}"))
                verify("actual pnpm installs transitive dependencies through the production npm proxy", installed.optBoolean("ok") && installed.getJSONObject("value").optString("application") !in setOf("failed", "cancelled"))
                val modules = File(home, "profiles/web/node_modules")
                fun packageData(name: String): File? = File(modules, "$name/data.bin").takeIf { it.isFile }
                    ?: File(modules, ".pnpm").listFiles().orEmpty().firstOrNull { it.name.startsWith("$name@1.0.0") }
                        ?.let { File(it, "node_modules/$name/data.bin") }
                verify("real installed transitive package bytes match their acquired archive", packageData("fixture-four")?.length() == 2L * 1024 * 1024 && packageData("fixture-eight")?.length() == 7L * 1024 * 1024)
                verify("npm dependency above 1 MiB performs four real parallel ranges", ranges["/fixture-four/-/fixture-four-1.0.0.tgz"]?.get() == 4 && (maximum["/fixture-four/-/fixture-four-1.0.0.tgz"]?.get() ?: 0) >= 2 && 4 in threadCounts)
                verify("npm dependency above 6 MiB performs eight real parallel ranges", ranges["/fixture-eight/-/fixture-eight-1.0.0.tgz"]?.get() == 8 && (maximum["/fixture-eight/-/fixture-eight-1.0.0.tgz"]?.get() ?: 0) >= 2 && 8 in threadCounts)
                verify("temporary npm registry token is absent from the persistent lock", !File(home, "profiles/web/pnpm-lock.yaml").readText().contains(registry.url))
                val bad = registry.resolve("fixture-corrupt@1.0.0").getJSONObject("dist").getString("tarball")
                verify("npm proxy rejects actual tarball bytes with an incorrect SRI digest", get(bad) == 502 && cache.listFiles().orEmpty().none { it.isFile && it.name.startsWith("sha_512-" + "00".repeat(64)) })
                val rejected = try { registry.resolve("fixture-foreign@1.0.0"); false } catch (_: Exception) { true }
                verify("npm metadata cannot route a tarball to an uncontrolled origin", rejected)
                verify("loopback registry persists only checksum-verified complete tarballs", cache.listFiles().orEmpty().filter { it.isFile }.size == 2)
            }
            NpmArtifactRegistry(cache, origin, downloader).use { renewed ->
                val repaired = invoke(JSONObject().put("operation", "repair").put("registry", renewed.url).put("name", "dafeiyu-network-fixture"))
                verify("actual fixed local source repair succeeds with a fresh registry lifetime", repaired.optBoolean("ok") && repaired.getJSONObject("value").optString("application") == "restart-required")
                val removed = invoke(JSONObject().put("operation", "remove").put("registry", renewed.url).put("name", "dafeiyu-network-fixture"))
                verify("real dependency-backed bundle removal succeeds through a fresh registry", removed.optBoolean("ok") && removed.getJSONObject("value").optString("application") !in setOf("failed", "cancelled") &&
                    JSONObject(File(home, "profiles/web/package.json").readText()).optJSONObject("dependencies")?.has("dafeiyu-network-fixture") != true)
            }
            val interrupted = File(fixtures, "interrupted-home").apply { check(mkdirs()) }
            check(invoke(JSONObject().put("operation", "list"), interrupted).getBoolean("ok"))
            val beforeProfile = File(interrupted, "profiles/web/package.json").readText()
            val captured = File(fixtures, "captured-real-journal")
            val pause = MetadataPause()
            NpmArtifactRegistry(cache, origin, downloader).use { registry ->
                metadataPause.set(pause)
                val active = startInvocation(JSONObject().put("operation", "install").put("registry", registry.url)
                    .put("spec", "file:${plugin.path}"), interrupted)
                try {
                    check(pause.arrived.await(15, TimeUnit.SECONDS)) { "Actual pnpm never requested paused fixture metadata" }
                    verify("actual pnpm work is preceded by a durable first-party profile transaction", DshExtensionTransaction.pending(interrupted))
                    check(File(interrupted, DshExtensionTransaction.DIRECTORY).copyRecursively(captured))
                } finally { metadataPause.set(null); pause.release.countDown() }
                val completed = awaitInvocation(active)
                verify("a successful real manager mutation removes its transaction journal", completed.optBoolean("ok") &&
                    completed.getJSONObject("value").optString("application") != "failed" && !DshExtensionTransaction.pending(interrupted))
            }
            val journal = File(interrupted, DshExtensionTransaction.DIRECTORY)
            check(captured.copyRecursively(journal))
            val partial = "{\"name\":\"simulated-partial-profile\"}"
            File(interrupted, "profiles/web/package.json").writeText(partial)
            val denied = try { DshExtensionTransaction.requireReady(interrupted); false } catch (_: Exception) { true }
            verify("pending durable recovery blocks the application launch guard", denied)
            val blocked = invoke(JSONObject().put("operation", "list"), interrupted)
            verify("the real Node manager refuses a partially committed profile until recovery", !blocked.optBoolean("ok") &&
                blocked.optString("error").contains("profile-recovery-required") && File(interrupted, "profiles/web/package.json").readText() == partial)
            val skillBlocked = try { SkillInstallStore(interrupted).publishLocal(File(root, "skill-source")); false } catch (_: Exception) { true }
            verify("pending profile recovery blocks shared skill publication without writing a skill", skillBlocked &&
                !File(interrupted, "skills/sample-skill/SKILL.md").exists())
            File(journal, "package.json").appendText("tamper")
            val corruptRecovery = invoke(JSONObject().put("operation", "recover"), interrupted)
            verify("recovery validates snapshot SHA256 before changing any current profile file", !corruptRecovery.optBoolean("ok") &&
                DshExtensionTransaction.pending(interrupted) && File(interrupted, "profiles/web/package.json").readText() == partial)
            File(captured, "package.json").copyTo(File(journal, "package.json"), overwrite = true)
            val recovered = invoke(JSONObject().put("operation", "recover"), interrupted)
            verify("explicit real helper recovery restores exact original profile bytes", recovered.optBoolean("ok") &&
                recovered.getJSONObject("value").optBoolean("recovered") && File(interrupted, "profiles/web/package.json").readText() == beforeProfile)
            verify("only completed recovery removes the launch-blocking journal", !DshExtensionTransaction.pending(interrupted) &&
                runCatching { DshExtensionTransaction.requireReady(interrupted) }.isSuccess)
        } finally { server.stop(0); executor.shutdownNow() }
        checks
    }
