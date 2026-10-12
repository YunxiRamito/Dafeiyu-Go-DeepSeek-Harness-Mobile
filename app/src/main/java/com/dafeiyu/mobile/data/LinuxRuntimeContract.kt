package com.dafeiyu.mobile.data

/** A PRoot userspace shares the Android kernel and is not a VM or a security sandbox. */
enum class LinuxComponentStatus { MISSING, INSTALLING, READY, ERROR, UNSUPPORTED }

data class LinuxComponentState(
    val id: String,
    val title: String,
    val status: LinuxComponentStatus,
    val version: String = "",
    val detail: String = "",
    val progress: Float? = null,
)

data class LinuxExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val outputTruncated: Boolean = false,
)

internal data class LinuxRuntimeAsset(val name: String, val sha256: String, val expandedBytes: Long)

/** Hashes belong to the verified v0.2.0-rc2 APK, including its optimized standard rootfs. */
internal object LinuxRuntimeRelease {
    const val TAG = "v0.2.0-rc2"
    const val COMMIT = "8362bba747fcf84c19e436c3dbb594a0fc388a67"
    const val APK_URL = "https://github.com/DSH-APP/DSHA/releases/download/v0.2.0-rc2/dsha-0.2.0-rc2.apk"
    const val APK_SHA256 = "1fedf883d431af658d89c28b86d85974cc1d11b74e0c5d46ef3d3cf8650b1961"
    val ubuntu = LinuxRuntimeAsset("offline-rootfs.bin", "b9fe8a0460bc0d0e96f1cec0d65d46c9ff71a10c6a8d05a390b732a81d30cef3", 310L * 1024 * 1024)
    val git = LinuxRuntimeAsset("ubuntu-tools.bin", "44eeebf82768323fd110be7ed07dffb4d07f369019451b6dfb2cfe5ccc84f052", 100L * 1024 * 1024)
    val python = LinuxRuntimeAsset("glibc-python.bin", "d3f472848bf56dc856f23bc7a20d546703075b4851fd3de9cb07936b52feba9d", 30L * 1024 * 1024)
    val pythonSupport = LinuxRuntimeAsset("python-support.bin", "9f8299ea3fa7a1cf84c23087ae80f584599a828585f3175f92da575955a63464", 4L * 1024 * 1024)
    val pnpm = LinuxRuntimeAsset("pnpm-runtime.bin", "08873508c20e5a4af5ffb93a02509aac62a24b91826d8abce9f0d71e9453ce9d", 24L * 1024 * 1024)
    val ids = listOf("ubuntu", "node", "git", "pnpm", "python")
    val titles = mapOf("ubuntu" to "Ubuntu", "node" to "Node.js", "git" to "Git", "pnpm" to "pnpm", "python" to "Python")
    fun initial(detail: String = "尚未安装；Ubuntu 通过 PRoot 运行，无需 root") = ids.map {
        LinuxComponentState(it, titles.getValue(it), LinuxComponentStatus.MISSING, detail = detail)
    }
}
