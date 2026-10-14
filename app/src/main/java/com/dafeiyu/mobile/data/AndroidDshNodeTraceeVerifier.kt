package com.dafeiyu.mobile.data

import android.os.Process
import android.system.Os
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Native /proc reads, outside PRoot's translated /proc/self/exe. The reviewed
 * PRoot loader may remain the kernel executable while mapping real Node. */
internal class AndroidDshNodeTraceeVerifier(
    private val runtime: LinuxManagedRuntimeIdentity,
    private val command: List<String>,
) {
    private var verified: ProcNodeTraceeProof? = null
    val verifiedPid: Int? get() = verified?.nodePid

    @Synchronized fun verify(helper: LinuxManagedProcess, reportedPid: Int) {
        val proof = readProof(helper, reportedPid)
        val before = verified
        check(before == null || before == proof) { "HOST_UNAVAILABLE" }
        verified = proof
    }

    @Synchronized fun requireCurrent(helper: LinuxManagedProcess) {
        val before = verified ?: error("HOST_UNAVAILABLE")
        check(readProof(helper, before.nodePid) == before) { "HOST_UNAVAILABLE" }
    }

    private fun readProof(helper: LinuxManagedProcess, nodePid: Int): ProcNodeTraceeProof {
        check(helper.isAlive && nodePid > 1 && nodePid != helper.pid && nodePid != Process.myPid()) { "HOST_UNAVAILABLE" }
        val tracer = snapshot(helper.pid)
        val node = snapshot(nodePid)
        val nodeCmdline = ProcNodeIdentityRules.cmdline(read(File("/proc/$nodePid/cmdline"), 32 * 1024))
        check(nodeCmdline == command) { "HOST_UNAVAILABLE" }
        val tracerExecutable = Os.readlink("/proc/${helper.pid}/exe")
        check(tracerExecutable == File(runtime.nativeDirectory, "liblinux_proot.so").canonicalPath) { "HOST_UNAVAILABLE" }
        val nodeExecutable = Os.readlink("/proc/$nodePid/exe")
        check(nodeExecutable in setOf(runtime.nodeExecutable.canonicalPath,
            File(runtime.nativeDirectory, "liblinux_prootloader.so").canonicalPath)) { "HOST_UNAVAILABLE" }
        val nodeStat = Os.stat(runtime.nodeExecutable.path)
        val maps = text(File("/proc/$nodePid/maps"), 2 * 1024 * 1024)
        check(ProcNodeIdentityRules.executableMapping(maps, runtime.nodeExecutable.canonicalPath, nodeStat.st_ino)) {
            "HOST_UNAVAILABLE"
        }
        val ancestry = mutableListOf(node)
        val seen = mutableSetOf(nodePid)
        while (ancestry.last().pid != tracer.pid) {
            check(ancestry.size < 32) { "HOST_UNAVAILABLE" }
            val parent = ancestry.last().parentPid
            check(parent > 1 && seen.add(parent)) { "HOST_UNAVAILABLE" }
            ancestry += if (parent == tracer.pid) tracer else snapshot(parent)
        }
        ProcNodeIdentityRules.requireAncestry(ancestry, Process.myUid(), Process.myPid(), tracer.pid)
        // Re-read every sampled identity after cmdline/maps/ancestry traversal;
        // PID reuse or reparenting during the sample is not accepted.
        check(helper.isAlive && ancestry.all { snapshot(it.pid) == it } &&
            ProcNodeIdentityRules.cmdline(read(File("/proc/$nodePid/cmdline"), 32 * 1024)) == nodeCmdline &&
            Os.readlink("/proc/$nodePid/exe") == nodeExecutable) { "HOST_UNAVAILABLE" }
        return ProcNodeTraceeProof(nodePid, node.started, helper.pid, tracer.started, ancestry.toList(),
            nodeExecutable, nodeCmdline.toList(), nodeStat.st_ino)
    }

    private fun snapshot(pid: Int): ProcIdentity {
        val before = ProcNodeIdentityRules.stat(text(File("/proc/$pid/stat"), 8192))
        val status = ProcNodeIdentityRules.status(pid, text(File("/proc/$pid/status"), 64 * 1024), before.started)
        val after = ProcNodeIdentityRules.stat(text(File("/proc/$pid/stat"), 8192))
        check(before == after && status.pid == before.pid && status.parentPid == before.parentPid) { "HOST_UNAVAILABLE" }
        return status
    }

    private fun text(file: File, limit: Int): String = strictUtf8(read(file, limit))
    private fun read(file: File, limit: Int): ByteArray = file.inputStream().use { ConnectionPackageValidator.readBounded(it, limit) }
    private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}

internal data class ProcStat(val pid: Int, val parentPid: Int, val started: Long)
internal data class ProcIdentity(val pid: Int, val parentPid: Int, val tracerPid: Int, val uids: List<Int>, val started: Long)
internal data class ProcNodeTraceeProof(
    val nodePid: Int,
    val nodeStarted: Long,
    val tracerPid: Int,
    val tracerStarted: Long,
    val ancestry: List<ProcIdentity>,
    val executable: String,
    val command: List<String>,
    val nodeInode: Long,
)

/** Pure format/rule checks have host fixtures; their success does not prove
 * Android proc visibility, mapping provenance, or a real PRoot process. */
internal object ProcNodeIdentityRules {
    fun stat(raw: String): ProcStat {
        val open = raw.indexOf(" (")
        val close = raw.lastIndexOf(") ")
        check(open > 0 && close > open && raw.length <= 8192) { "HOST_UNAVAILABLE" }
        val pid = raw.substring(0, open).toIntOrNull() ?: error("HOST_UNAVAILABLE")
        val fields = raw.substring(close + 2).trim().split(Regex("\\s+"))
        check(fields.size >= 20 && fields[0].length == 1 && fields[0] !in setOf("Z", "X", "x")) { "HOST_UNAVAILABLE" }
        val parent = fields[1].toIntOrNull() ?: error("HOST_UNAVAILABLE")
        val started = fields[19].toLongOrNull() ?: error("HOST_UNAVAILABLE")
        check(pid > 1 && parent > 0 && started > 0) { "HOST_UNAVAILABLE" }
        return ProcStat(pid, parent, started)
    }

    fun status(pid: Int, raw: String, started: Long): ProcIdentity {
        fun single(name: String): String {
            val values = raw.lineSequence().filter { it.startsWith("$name:") }.toList()
            check(values.size == 1) { "HOST_UNAVAILABLE" }
            return values.single().substringAfter(':').trim()
        }
        val actualPid = single("Pid").toIntOrNull() ?: error("HOST_UNAVAILABLE")
        val parent = single("PPid").toIntOrNull() ?: error("HOST_UNAVAILABLE")
        val tracer = single("TracerPid").toIntOrNull() ?: error("HOST_UNAVAILABLE")
        val uids = single("Uid").split(Regex("\\s+")).map { it.toIntOrNull() ?: error("HOST_UNAVAILABLE") }
        check(pid == actualPid && parent > 0 && tracer >= 0 && uids.size == 4 && uids.all { it >= 0 }) { "HOST_UNAVAILABLE" }
        return ProcIdentity(pid, parent, tracer, uids, started)
    }

    fun cmdline(raw: ByteArray): List<String> {
        check(raw.isNotEmpty() && raw.size <= 32 * 1024 && raw.last() == 0.toByte()) { "HOST_UNAVAILABLE" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        return text.dropLast(1).split('\u0000').also { parts ->
            check(parts.size in 1..64 && parts.all { it.isNotEmpty() }) { "HOST_UNAVAILABLE" }
        }
    }

    fun executableMapping(raw: String, nodePath: String, inode: Long): Boolean {
        if (inode <= 0) return false
        return raw.lineSequence().any { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 6)
            if (parts.size != 6 || parts[1].length != 4 || parts[1][2] != 'x' || parts[4].toLongOrNull() != inode) false
            else decodeMapPath(parts[5]) == nodePath && !parts[5].endsWith(" (deleted)")
        }
    }

    private fun decodeMapPath(path: String): String = path.replace("\\040", " ").replace("\\011", "\t")
        .replace("\\012", "\n").replace("\\134", "\\")

    fun requireAncestry(rows: List<ProcIdentity>, uid: Int, appPid: Int, tracerPid: Int) {
        check(rows.size in 2..32 && rows.first().pid != tracerPid && rows.last().pid == tracerPid &&
            rows.map { it.pid }.toSet().size == rows.size && rows.all { it.uids == List(4) { uid } }) { "HOST_UNAVAILABLE" }
        val tracer = rows.last()
        check(tracer.parentPid == appPid && tracer.tracerPid == 0 && tracer.started > 0) { "HOST_UNAVAILABLE" }
        check(rows.dropLast(1).all { it.tracerPid == tracerPid && it.started >= tracer.started } &&
            rows.zipWithNext().all { (child, parent) -> child.parentPid == parent.pid && child.started >= parent.started }) {
            "HOST_UNAVAILABLE"
        }
    }
}
