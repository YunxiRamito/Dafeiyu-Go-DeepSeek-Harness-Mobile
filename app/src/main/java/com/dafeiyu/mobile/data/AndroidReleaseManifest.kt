package com.dafeiyu.mobile.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class AndroidReleaseManifest(
    val schemaVersion: Int,
    val packageName: String,
    val version: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val abis: List<String>,
    val sha256: String,
    val byteSize: Long,
    val fileName: String,
    val signingCertificateSha256: String,
    val apkSignatureSchemes: List<String>,
    val channel: String,
    val buildType: String,
    val publishedAt: String,
    val releaseNotes: String,
    val installationMode: String,
    val allowDowngrade: Boolean,
    val allowSilentInstall: Boolean,
    val recoveryStrategy: String,
    val recoveryOfVersion: String?,
    val supersedesVersionCode: Long?,
)

/** The signed server schema, with strict JSON types and no duplicate property names. */
object AndroidReleaseManifestCodec {
    const val MAX_MANIFEST_BYTES = 32 * 1024
    const val MAX_APK_BYTES = 1024L * 1024 * 1024
    private val versionPattern = Regex("\\d{1,5}\\.\\d{1,5}\\.\\d{1,5}(?:-[A-Za-z0-9]+(?:\\.[A-Za-z0-9]+)*)?")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val requiredKeys = setOf(
        "schemaVersion", "packageName", "version", "versionCode", "minSdk", "targetSdk", "abis",
        "sha256", "byteSize", "fileName", "signingCertificateSha256", "apkSignatureSchemes",
        "channel", "buildType", "publishedAt", "installationPolicy",
    )
    private val optionalKeys = setOf("releaseNotes", "recoveryOfVersion", "supersedesVersionCode")

    fun parse(bytes: ByteArray, expectedPackage: String, channel: String, now: Instant = Instant.now()): AndroidReleaseManifest {
        require(bytes.size in 1..MAX_MANIFEST_BYTES) { "更新清单大小无效" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = StrictReleaseJson(text).readObject()
        require(root.keys.containsAll(requiredKeys) && root.keys.all { it in requiredKeys || it in optionalKeys }) {
            "更新清单字段不完整或包含未知字段"
        }
        val policy = root["installationPolicy"] as? Map<*, *> ?: error("更新安装策略无效")
        require(policy.keys == setOf("mode", "allowDowngrade", "allowSilentInstall", "recoveryStrategy")) {
            "更新安装策略字段不完整"
        }
        val manifest = AndroidReleaseManifest(
            schemaVersion = root.integer("schemaVersion").boundedInt(),
            packageName = root.string("packageName"), version = root.string("version"),
            versionCode = root.integer("versionCode"), minSdk = root.integer("minSdk").boundedInt(),
            targetSdk = root.integer("targetSdk").boundedInt(), abis = root.strings("abis"),
            sha256 = root.string("sha256"), byteSize = root.integer("byteSize"), fileName = root.string("fileName"),
            signingCertificateSha256 = root.string("signingCertificateSha256"),
            apkSignatureSchemes = root.strings("apkSignatureSchemes"), channel = root.string("channel"),
            buildType = root.string("buildType"), publishedAt = root.string("publishedAt"),
            releaseNotes = if (root.containsKey("releaseNotes")) root.string("releaseNotes") else "",
            installationMode = policy.string("mode"), allowDowngrade = policy.boolean("allowDowngrade"),
            allowSilentInstall = policy.boolean("allowSilentInstall"), recoveryStrategy = policy.string("recoveryStrategy"),
            recoveryOfVersion = root.optionalString("recoveryOfVersion"),
            supersedesVersionCode = root.optionalInteger("supersedesVersionCode"),
        )
        require(manifest.schemaVersion == 1) { "不支持的更新清单版本" }
        require(manifest.packageName == expectedPackage) { "更新包属于其他应用" }
        require(channel in setOf("stable", "preview") && manifest.channel == channel && manifest.buildType == "release") {
            "更新通道或构建类型不匹配"
        }
        require(manifest.version.length <= 80 && versionPattern.matches(manifest.version)) { "更新版本无效" }
        require(manifest.versionCode in 1..Int.MAX_VALUE.toLong()) { "更新版本编号无效" }
        require(manifest.minSdk in 1..1000 && manifest.targetSdk in manifest.minSdk..1000) { "更新系统版本无效" }
        require(manifest.byteSize in 1..MAX_APK_BYTES && hashPattern.matches(manifest.sha256)) { "更新文件摘要或大小无效" }
        require(manifest.fileName == "app.apk") { "更新文件名无效" }
        require(hashPattern.matches(manifest.signingCertificateSha256)) { "更新发行证书无效" }
        require(manifest.abis.isNotEmpty() && manifest.abis.size <= 4 && manifest.abis.distinct() == manifest.abis &&
            manifest.abis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }) { "更新架构无效" }
        require(manifest.apkSignatureSchemes.isNotEmpty() && manifest.apkSignatureSchemes.size <= 4 &&
            manifest.apkSignatureSchemes.distinct() == manifest.apkSignatureSchemes &&
            manifest.apkSignatureSchemes.all { it in setOf("v1", "v2", "v3", "v3.1") } &&
            manifest.apkSignatureSchemes.any { it != "v1" }) { "更新 APK 签名方案无效" }
        val publication = OffsetDateTime.parse(manifest.publishedAt)
        require(publication.offset == ZoneOffset.UTC && publication.toInstant() <= now.plusSeconds(86_400)) {
            "更新发布时间无效，请检查设备时间"
        }
        require(manifest.releaseNotes.length <= 12_000 &&
            manifest.releaseNotes.none { Character.isISOControl(it) && it !in "\r\n\t" }) { "更新说明无效" }
        require(manifest.installationMode == "user-confirmed" && !manifest.allowDowngrade && !manifest.allowSilentInstall &&
            manifest.recoveryStrategy == "higher-version-code-reissue") { "更新安装策略不受支持" }
        val recovery = manifest.recoveryOfVersion
        val supersedes = manifest.supersedesVersionCode
        require((recovery == null && supersedes == null) ||
            (recovery != null && versionPattern.matches(recovery) && recovery != manifest.version &&
                supersedes != null && supersedes > 0L && manifest.versionCode > supersedes)) { "恢复版本信息不完整" }
        return manifest
    }

    private fun Map<*, *>.string(name: String): String = this[name] as? String ?: error("$name 必须为字符串")
    private fun Map<*, *>.boolean(name: String): Boolean = this[name] as? Boolean ?: error("$name 必须为布尔值")
    private fun Map<*, *>.integer(name: String): Long = (this[name] as? ReleaseNumber)?.value
        ?.takeIf { it.matches(Regex("-?(?:0|[1-9][0-9]*)")) }?.toLongOrNull() ?: error("$name 必须为整数")
    private fun Long.boundedInt(): Int { require(this in Int.MIN_VALUE..Int.MAX_VALUE.toLong()); return toInt() }
    private fun Map<*, *>.optionalString(name: String): String? = if (this[name] == null) null else string(name)
    private fun Map<*, *>.optionalInteger(name: String): Long? = if (this[name] == null) null else integer(name)
    private fun Map<*, *>.strings(name: String): List<String> = (this[name] as? List<*>)
        ?.map { it as? String ?: error("$name 必须为字符串数组") } ?: error("$name 必须为数组")
}

private data class ReleaseNumber(val value: String)

private class StrictReleaseJson(text: String) {
    init {
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                require(character >= ' ') { "JSON 字符串包含未转义的控制字符" }
                if (escaped) escaped = false
                else when (character) {
                    '\\' -> escaped = true
                    '"' -> quoted = false
                }
            } else if (character == '"') quoted = true
        }
        require(!quoted && !escaped) { "JSON 字符串未结束" }
    }
    @Suppress("DEPRECATION")
    private val reader = JsonReader(StringReader(text)).apply { isLenient = false }
    private var nodes = 0
    fun readObject(): Map<String, Any?> = reader.use {
        require(it.peek() == JsonToken.BEGIN_OBJECT) { "更新清单必须为 JSON 对象" }
        val root = readObjectValue(0)
        require(it.peek() == JsonToken.END_DOCUMENT) { "更新清单包含多余内容" }
        root
    }
    private fun value(depth: Int): Any? {
        require(depth <= 4 && ++nodes <= 128) { "更新清单结构过大" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> readObjectValue(depth)
            JsonToken.BEGIN_ARRAY -> buildList {
                reader.beginArray()
                while (reader.hasNext()) { require(size < 8); add(value(depth + 1)) }
                reader.endArray()
            }
            JsonToken.STRING -> reader.nextString().also(::checkUnicode)
            JsonToken.NUMBER -> ReleaseNumber(reader.nextString())
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); null }
            else -> error("更新清单 JSON 无效")
        }
    }
    private fun readObjectValue(depth: Int): Map<String, Any?> {
        val values = linkedMapOf<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName().also(::checkUnicode)
            require(!values.containsKey(name)) { "更新清单包含重复字段 $name" }
            values[name] = value(depth + 1)
        }
        reader.endObject()
        return values
    }
    private fun checkUnicode(value: String) {
        var at = 0
        while (at < value.length) {
            val character = value[at++]
            if (Character.isHighSurrogate(character)) require(at < value.length && Character.isLowSurrogate(value[at++]))
            else require(!Character.isLowSurrogate(character))
        }
    }
}
