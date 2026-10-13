package com.dafeiyu.mobile.data

import java.net.URI
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable description of one DSH runtime archive.
 *
 * The manifest is deliberately separate from the installer.  A release feed can
 * provide a newer, reviewed bundle without changing the process lifecycle code.
 * A manifest never grants trust by itself: the archive digest and the version
 * probe are checked before the slot is published.
 */
data class DshRuntimeManifest(
    val schema: Int,
    val runtimeId: String,
    val dshVersion: String,
    val nodeVersion: String,
    val archiveUrl: String,
    val archiveSha256: String,
    val archiveSize: Long,
    val maximumExpandedBytes: Long,
    val entryPoint: String,
    val healthPath: String = "/",
    val requiredFiles: List<String> = listOf("node_modules/@deepseek-ai/dsh/package.json"),
    val sourceCommit: String? = null,
    val license: String = "MIT",
    val abi: String = "arm64-v8a",
    val dependencyLockSha256: String? = null,
) {
    // A caller cannot alter a previously validated receipt by mutating a list.
    internal val bundleRequiredFiles: List<String> = requiredFiles.toList()
    init { validate() }

    internal val nodeArchitecture: String get() = if (abi == "arm64-v8a") "arm64" else "x64"

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", schema)
        put("runtimeId", runtimeId)
        put("dshVersion", dshVersion)
        put("nodeVersion", nodeVersion)
        put("archiveUrl", archiveUrl)
        put("archiveSha256", archiveSha256)
        put("archiveSize", archiveSize)
        put("maximumExpandedBytes", maximumExpandedBytes)
        put("entryPoint", entryPoint)
        put("healthPath", healthPath)
        put("requiredFiles", JSONArray(bundleRequiredFiles))
        sourceCommit?.let { put("sourceCommit", it) }
        put("license", license)
        put("abi", abi)
        dependencyLockSha256?.let { put("dependencyLockSha256", it) }
    }

    /** An ordered projection avoids relying on JSONObject's map iteration order. */
    fun digest(): String = MessageDigest.getInstance("SHA-256")
        .digest(JSONArray(listOf(schema, runtimeId, dshVersion, nodeVersion, archiveUrl,
            archiveSha256.lowercase(), archiveSize, maximumExpandedBytes, entryPoint, healthPath,
            JSONArray(bundleRequiredFiles), sourceCommit ?: JSONObject.NULL, license, abi,
            dependencyLockSha256?.lowercase() ?: JSONObject.NULL)).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun validate() {
        require(schema == 1) { "不支持的 DSH 运行时描述版本" }
        require(runtimeId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) { "DSH 运行时 ID 无效" }
        require(dshVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?"))) {
            "DSH 版本无效"
        }
        require(nodeVersion.matches(Regex("v?[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?"))) {
            "Node.js 版本无效"
        }
        val uri = URI(archiveUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "DSH 运行时只能从 HTTPS 线路下载"
        }
        require(archiveSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "DSH 归档 SHA-256 无效" }
        require(archiveSize in 1L..2L * 1024 * 1024 * 1024) { "DSH 归档大小超出允许范围" }
        require(maximumExpandedBytes in archiveSize..(4L * 1024 * 1024 * 1024)) {
            "DSH 归档展开上限无效"
        }
        require(relativePath(entryPoint) && entryPoint.endsWith(".js")) { "DSH 入口无效" }
        require(healthPath == "/") {
            "DSH 健康路径无效"
        }
        require(bundleRequiredFiles.isNotEmpty() && bundleRequiredFiles.size <= 512 && bundleRequiredFiles.all(::relativePath)) {
            "DSH 必需文件清单无效"
        }
        require(sourceCommit == null || sourceCommit.matches(Regex("[a-fA-F0-9]{7,64}"))) {
            "DSH 源码提交号无效"
        }
        require(license.length in 1..120 && '\u0000' !in license) { "DSH 许可标识无效" }
        require(abi in setOf("arm64-v8a", "x86_64")) { "不支持的 DSH Linux ABI" }
        require(dependencyLockSha256 == null || dependencyLockSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "DSH 依赖锁定清单 SHA-256 无效"
        }
    }

    companion object {
        /** Parse only the release-feed shape; unknown fields are intentionally ignored. */
        fun fromJson(value: JSONObject): DshRuntimeManifest = DshRuntimeManifest(
            schema = value.getInt("schema"),
            runtimeId = value.getString("runtimeId"),
            dshVersion = value.getString("dshVersion"),
            nodeVersion = value.getString("nodeVersion"),
            archiveUrl = value.getString("archiveUrl"),
            archiveSha256 = value.getString("archiveSha256"),
            archiveSize = value.getLong("archiveSize"),
            maximumExpandedBytes = value.getLong("maximumExpandedBytes"),
            entryPoint = value.getString("entryPoint"),
            healthPath = value.optString("healthPath", "/"),
            requiredFiles = value.optJSONArray("requiredFiles")?.let { array ->
                List(array.length()) { index -> array.getString(index) }
            } ?: listOf("node_modules/@deepseek-ai/dsh/package.json"),
            sourceCommit = value.optString("sourceCommit").takeIf { it.isNotBlank() },
            license = value.optString("license", "MIT"),
            abi = value.optString("abi", "arm64-v8a"),
            dependencyLockSha256 = value.optString("dependencyLockSha256").takeIf { it.isNotBlank() },
        )

        private fun relativePath(path: String): Boolean = path.length in 1..512 &&
            '\u0000' !in path && '\\' !in path && !path.startsWith('/') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}

