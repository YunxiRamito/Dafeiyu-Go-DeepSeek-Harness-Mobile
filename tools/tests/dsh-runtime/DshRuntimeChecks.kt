package com.dafeiyu.mobile.data

import org.json.JSONObject

private fun expectFailure(name: String, block: () -> Unit) {
    try {
        block()
        error("$name unexpectedly accepted")
    } catch (_: IllegalArgumentException) {
        println("PASS $name")
    }
}

fun main() {
    val valid = DshRuntimeManifest(
        schema = 1,
        runtimeId = "dsh-0.2.1-alpha.1-arm64",
        dshVersion = "0.2.1-alpha.1",
        nodeVersion = "v24.21.0",
        archiveUrl = "https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-0.2.1-alpha.1.tgz",
        archiveSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        archiveSize = 24_302,
        maximumExpandedBytes = 73774,
        entryPoint = "node_modules/@deepseek-ai/dsh/lib/bin.js",
        requiredFiles = listOf("node_modules/@deepseek-ai/dsh/package.json"),
        sourceCommit = "f07218c4d4bbc12bef056a7058c3dd49dfe41abe",
    )
    val roundTrip = DshRuntimeManifest.fromJson(JSONObject(valid.toJson().toString()))
    check(roundTrip == valid)
    check(valid.digest() == roundTrip.digest())
    println("PASS manifest JSON round trip and stable digest")

    expectFailure("HTTP archive URL") { valid.copy(archiveUrl = "http://example.invalid/dsh.tgz") }
    expectFailure("archive checksum") { valid.copy(archiveSha256 = "bad") }
    expectFailure("entry path escape") { valid.copy(entryPoint = "../bin.js") }
    expectFailure("unsupported health path") { valid.copy(healthPath = "/api/../secret") }
    expectFailure("archive expansion bound") { valid.copy(maximumExpandedBytes = 1) }

    check(DshRuntimeManager.PROCESS_ID == "dsh-service")
    check(DshServiceSnapshot().status == DshServiceStatus.STOPPED)
    println("PASS lifecycle status defaults and stable process identity")
    val mutable = mutableListOf("node_modules/@deepseek-ai/dsh/package.json")
    val frozen = valid.copy(requiredFiles = mutable)
    val beforeMutation = frozen.digest()
    mutable += "../../escape"
    check(frozen.digest() == beforeMutation)
    check(frozen.toJson().getJSONArray("requiredFiles").length() == 1)
    println("PASS immutable required files snapshot")
    expectFailure("unsupported ABI") { valid.copy(abi = "win32-x64") }
    expectFailure("dependency lock digest") { valid.copy(dependencyLockSha256 = "bad") }
    check(!DshServiceSnapshot(accessUrl = "http://127.0.0.1:4321/?token=SECRET").toString().contains("SECRET"))
    println("PASS process authentication URL omitted from diagnostics")
    println("DSH_MANIFEST_CHECKS=11")

    val native = "/data/app/reviewed/lib/x86_64"
    val command = listOf("/usr/local/bin/node", "/dafeiyu-script")
    val readOnly = LinuxSandboxContract.confine(command, LinuxSandboxContract.READ_ONLY, native)
    val workspaceWrite = LinuxSandboxContract.confine(command, LinuxSandboxContract.WORKSPACE_WRITE, native)
    fun grants(argv: List<String>, option: String): List<String> = argv.zipWithNext()
        .filter { (key, _) -> key == option }.map { (_, value) -> value }
    check(grants(readOnly, "--rw") == listOf("/dev/null"))
    check(grants(workspaceWrite, "--rw") == listOf("/dev/null", "/tmp", "/workspace"))
    check(grants(readOnly, "--ro").containsAll(listOf(native, "/dafeiyu-script", "/workspace")))
    check(readOnly.takeLast(3) == listOf("--") + command)
    check(LinuxSandboxContract.confine(command, LinuxSandboxContract.FULL_ACCESS, native) == command)
    check(!LinuxSandboxStatus("partial", "partial").supportsConstrainedModes)
    println("PASS constrained file grants preserve readonly mode and child command")
    expectFailure("untrusted native execution path") { LinuxSandboxContract.confine(command, LinuxSandboxContract.READ_ONLY, "/data/user/0/downloads") }
    expectFailure("native path traversal") { LinuxSandboxContract.confine(command, LinuxSandboxContract.READ_ONLY, "/data/app/../user/0") }
    expectFailure("native path control character") { LinuxSandboxContract.confine(command, LinuxSandboxContract.READ_ONLY, "/data/app/lib\nextra") }
    expectFailure("unknown file-effect mode") { LinuxSandboxContract.confine(command, "invented", native) }
    println("LINUX_SANDBOX_CHECKS=5")
}

