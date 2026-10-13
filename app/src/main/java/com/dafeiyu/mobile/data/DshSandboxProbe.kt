package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Actual kernel enforcement evidence. A PRoot directory alone never implies
 * sandbox support; introducing a full Linux VM remains a separate decision. */
class DshSandboxProbe(
    context: Context,
    private val runtime: LinuxRuntimeManager = LinuxRuntimeManager(context),
    private val installer: DshRuntimeInstaller = DshRuntimeInstaller(context, runtime),
) {
    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, "dsh-sandbox-probe")

    suspend fun run(): JSONObject = withContext(Dispatchers.IO) {
        val slot = installer.current() ?: error("请先安装 DSH 完整运行组件")
        installer.withCurrentSlot(slot.manifest.digest()) { validated ->
            require(!LinuxArchiveExtractor.isSymlink(root) && (root.isDirectory || root.mkdirs()))
            val script = File(root, "sandbox-probe.mjs")
            require(!LinuxArchiveExtractor.isSymlink(script))
            app.assets.open("dsh-runtime/sandbox-probe.mjs").use { input -> script.outputStream().use { input.copyTo(it) } }
            val adapter = File(root, "android-sandbox.mjs")
            require(!LinuxArchiveExtractor.isSymlink(adapter))
            app.assets.open("dsh-runtime/android-sandbox.mjs").use { input -> adapter.outputStream().use { input.copyTo(it) } }
            val process = runtime.startManagedProcess("dsh-probe-${UUID.randomUUID()}",
                listOf("/usr/local/bin/node", "/dafeiyu-sandbox-probe/sandbox-probe.mjs", "/dafeiyu-dsh", "/dafeiyu-sandbox-probe",
                    app.applicationInfo.nativeLibraryDir),
                mapOf("/dafeiyu-dsh" to validated.directory, "/dafeiyu-sandbox-probe" to root))
            try {
                val output = ByteArrayOutputStream()
                val error = ByteArrayOutputStream()
                val report = withTimeout(120_000) {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        for ((input, target) in listOf(process.output to output, process.errors to error)) {
                            while (input.available() > 0) {
                                require(target.size() < 512 * 1024) { "沙箱探测输出超过限制" }
                                val bytes = ByteArray(minOf(8192, input.available(), 512 * 1024 - target.size()))
                                val count = input.read(bytes)
                                if (count < 0) break
                                target.write(bytes, 0, count)
                            }
                        }
                        val text = output.toString(Charsets.UTF_8.name())
                        if (text.endsWith('\n')) return@withTimeout JSONObject(text.trim())
                        check(process.isAlive) { "沙箱探测无法完成；请检查实际运行日志" }
                        delay(20)
                    }
                    @Suppress("UNREACHABLE_CODE") JSONObject()
                }
                require(report.optInt("schema") == 1 && report.optString("dshVersion") == validated.manifest.dshVersion)
                require(report.optString("platform") == "linux" && report.optString("architecture") == validated.manifest.nodeArchitecture)
                report.put("observed_at", System.currentTimeMillis()).put("dsh_manifest_sha256", validated.manifest.digest())
                val receipt = AtomicFile(File(root, "latest-report.json"))
                val stream = receipt.startWrite()
                try { stream.write(report.toString().toByteArray(Charsets.UTF_8)); receipt.finishWrite(stream) }
                catch (failure: Throwable) { receipt.failWrite(stream); throw failure }
                report
            } finally {
                withContext(NonCancellable) { process.stop() }
            }
        }
    }
}
