package com.dafeiyu.mobile.data

/** File-effect modes match the actual DSH/PC vocabulary. Full access remains
 * bounded by the app UID and Android SELinux. Network/approvals are separate. */
object LinuxSandboxContract {
    const val READ_ONLY = "read-only"
    const val WORKSPACE_WRITE = "workspace-write"
    const val FULL_ACCESS = "danger-full-access"
    val modes: Set<String> = setOf(READ_ONLY, WORKSPACE_WRITE, FULL_ACCESS)
    const val GUEST_NATIVE = "/dafeiyu-sandbox-native"
    const val GUEST_LAUNCHER = "$GUEST_NATIVE/landlock-run"
    const val PROBE_COMPLETE = "landlock: fully enforced"

    internal fun digest(abi: String): String = when (abi) {
        "x86_64" -> "a752bc72f111fcc573c3e61fb90fa544541dac0ca498d2e279e1630d7c659b31"
        "arm64-v8a" -> "f6ae2ad5893e3123f45329ade5518b33c3ac3b102978001ff1c6a6a8ebe2ad9b"
        else -> error("当前架构没有已校验的 Landlock 运行器")
    }

    internal fun confine(command: List<String>, mode: String, nativeDirectory: String): List<String> {
        require(mode in modes && command.isNotEmpty())
        require(nativeDirectory.startsWith("/data/app/") && nativeDirectory.split('/').none { it == ".." } &&
            nativeDirectory.none { it == '\u0000' || it == '\n' || it == '\r' })
        if (mode == FULL_ACCESS) return command
        val readOnly = listOf("/", "/system", "/apex", "/proc", "/sys", "/dev", "/workspace", "/dafeiyu-script", GUEST_NATIVE, nativeDirectory)
        val writable = if (mode == WORKSPACE_WRITE) listOf("/dev/null", "/tmp", "/workspace") else listOf("/dev/null")
        return listOf(GUEST_LAUNCHER) + readOnly.flatMap { listOf("--ro", it) } + writable.flatMap { listOf("--rw", it) } + listOf("--") + command
    }
}

data class LinuxSandboxStatus(val enforcement: String, val detail: String) {
    val supportsConstrainedModes: Boolean get() = enforcement == "full"
}
