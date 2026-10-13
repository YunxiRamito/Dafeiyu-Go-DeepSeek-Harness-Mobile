package com.dafeiyu.mobile.data

import java.net.URI
import org.json.JSONObject

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
    val sandboxMode: String? = null,
    val sandboxEnforcement: String? = null,
)

internal data class LinuxRuntimeAsset(val name: String, val sha256: String, val expandedBytes: Long)

/** APK-pinned standalone guest artifact; publication fills the actual HTTPS
 * URL only after the corresponding archive has been uploaded and checked. */
internal data class LinuxGuestRuntimeManifest(
    val runtimeId: String,
    val abi: String,
    val archiveUrl: String,
    val archiveSha256: String,
    val archiveSize: Long,
    val maximumExpandedBytes: Long,
    val lockSha256: String,
) {
    init {
        require(runtimeId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,80}")))
        require(abi == "x86_64")
        val uri = URI(archiveUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        require(archiveSha256.matches(Regex("[a-fA-F0-9]{64}")) && lockSha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(archiveSize in 1L..1024L * 1024 * 1024 && maximumExpandedBytes in archiveSize..(2L * 1024 * 1024 * 1024))
    }

    companion object {
        fun fromJson(value: JSONObject): LinuxGuestRuntimeManifest {
            require(value.getInt("schema") == 1)
            check(!value.isNull("archiveUrl") && value.optString("archiveUrl").isNotBlank()) { "原生 x86_64 Linux 资源尚未发布；暂不能下载" }
            return LinuxGuestRuntimeManifest(value.getString("runtimeId"), value.getString("abi"),
                value.getString("archiveUrl"), value.getString("archiveSha256"), value.getLong("archiveSize"),
                value.getLong("maximumExpandedBytes"), value.getString("lockSha256"))
        }
    }
}

/** Unchanged official Termux ELF inputs, kept separate per native ABI. */
internal object LinuxNativeRelease {
    fun hashes(abi: String): Map<String, String> = when (abi) {
        "arm64-v8a" -> mapOf(
            "liblinux_proot.so" to "1545b85b312505db6eb6908ff8b2ded0a77a3bd689c50ae85aa7c1d8445dd717",
            "liblinux_prootloader.so" to "cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551",
            "liblinux_prootloader32.so" to "c429996fee730e1a4fe12625b22dab5a5bc2847a4cae274d36fd4a19cd8299a8",
            "liblinux_talloc.so" to "742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309",
            "liblinux_androidshmem.so" to "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731")
        "x86_64" -> mapOf(
            "liblinux_proot.so" to "9a1303a51e69b89d78beaf40e4ec829c5c2ca98cbd5672411bdb15eebd871316",
            "liblinux_prootloader.so" to "a836b0569c84b1661333d16bacf6b396ff08fea816a5c936a82e08ddeeca8341",
            "liblinux_prootloader32.so" to "fd5b394aa0801619e293b76fe6e830a3beb363aed4fa4b9a5a41f1388a0cb9b9",
            "liblinux_talloc.so" to "7fa6722a4320495b1b85832fc7fd02de0cb367b45f116c1921b2830a6936da90",
            "liblinux_androidshmem.so" to "092926060298acd3778e6239033d7aef1280dcb59aebe021a3719612e6a3465f")
        else -> error("没有对应主架构的原生执行资源")
    }
}

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
