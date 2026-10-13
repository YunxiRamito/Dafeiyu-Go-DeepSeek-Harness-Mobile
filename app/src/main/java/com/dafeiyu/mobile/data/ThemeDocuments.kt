package com.dafeiyu.mobile.data

import com.dafeiyu.mobile.glasslab.LabDocument
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.json.JSONObject

/** Shared, bounded material data. No URI, script, account or executable is part of a theme. */
internal object ThemeDocuments {
    const val MIME_TYPE = "application/vnd.dafeiyu.theme+json"
    const val DEFAULT_ID = "default"
    const val CUSTOM_ID = "custom"

    fun read(input: InputStream, maximum: Int = LabDocument.MAX_IMPORT_BYTES): ByteArray {
        require(maximum in 1..2 * 1024 * 1024)
        val output = ByteArrayOutputStream(minOf(maximum, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maximum) { "主题文件不可超过 ${maximum / 1024} KiB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun decode(bytes: ByteArray): LabDocument {
        require(bytes.size <= LabDocument.MAX_IMPORT_BYTES) { "主题文件不可超过 256 KiB" }
        return LabDocument.decode(utf8(bytes)).snapshot()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    /** Display-only WebView context, never include a selected photo or the preview backdrop. */
    fun materialContext(document: LabDocument): JSONObject {
        val encoded = JSONObject(document.encode())
        return JSONObject().put("schema", LabDocument.SCHEMA).put("renderer", LabDocument.RENDERER)
            .put("light", encoded.getJSONObject("light")).put("dark", encoded.getJSONObject("dark"))
    }

    /** A WebView receives only validated renderer data, regardless of how the internal caller obtained it. */
    fun validateMaterialContext(text: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "网页主题超过大小限制" }
        val json = JSONObject(text)
        require(json.keys().asSequence().toSet() == setOf("schema", "renderer", "light", "dark")) {
            "网页主题只能包含材质参数"
        }
        val checked = JSONObject(LabDocument.defaults().encode())
            .put("schema", json.get("schema")).put("renderer", json.get("renderer"))
            .put("light", json.getJSONObject("light")).put("dark", json.getJSONObject("dark"))
        return materialContext(LabDocument.decode(checked.toString()))
    }

    fun safeTitle(value: String): String {
        require(value.isNotBlank() && value.codePointCount(0, value.length) <= 80 &&
            value.none { Character.isISOControl(it) }) { "主题名称无效" }
        return value.trim()
    }

    fun safeId(value: String): String {
        require(value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}"))) { "主题编号无效" }
        require(value != DEFAULT_ID && value != CUSTOM_ID) { "主题编号保留" }
        return value
    }
}

internal data class InstalledTheme(
    val id: String,
    val title: String,
    val revision: Int,
    val sha256: String,
    val document: LabDocument,
)

internal data class ThemeSelection(
    val activeId: String = ThemeDocuments.DEFAULT_ID,
    val custom: InstalledTheme? = null,
    val installed: List<InstalledTheme> = emptyList(),
) {
    fun active(): InstalledTheme? = if (activeId == ThemeDocuments.CUSTOM_ID) custom
        else installed.firstOrNull { it.id == activeId }
}
