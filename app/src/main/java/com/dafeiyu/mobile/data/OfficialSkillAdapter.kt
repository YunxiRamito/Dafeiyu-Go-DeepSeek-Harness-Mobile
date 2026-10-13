package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Skills are read on demand. Their content does not bypass the Android tool dispatcher. */
class OfficialSkillAdapter private constructor(private val store: SkillInstallStore, private val workspace: File?) : OfficialToolSkillProvider {
    constructor(context: Context, workspaceId: String) : this(SkillInstallStore(context), localWorkspace(context, workspaceId))
    internal constructor(store: SkillInstallStore) : this(store, null)

    override suspend fun list(offset: Int, limit: Int): JSONObject {
        require(offset in 0..10000 && limit in 1..30)
        val catalog = store.catalogForModel(workspace)
        val selected = catalog.items.drop(offset).take(limit)
        return JSONObject().put("execution_device", "android_local").put("total", catalog.items.size)
            .put("items", JSONArray(selected.map { item -> JSONObject().put("name", item.metadata.name)
                .put("description", item.metadata.description.take(512)).put("when_to_use", item.metadata.whenToUse?.take(512) ?: JSONObject.NULL)
                .put("source", item.source) }))
            .put("next_offset", (offset + selected.size).takeIf { it < catalog.items.size } ?: JSONObject.NULL)
            .put("warnings", JSONArray(catalog.warnings.take(8).map { it.take(256) }))
    }

    override suspend fun read(name: String, file: String?, offset: Int, limit: Int): JSONObject = withContext(Dispatchers.IO) {
        require(offset in 0..1048576 && limit in 2..16000)
        val content = store.load(name, modelInvoked = true, workspace = workspace)
        val text = if (file == null || file == "SKILL.md") content.body else {
            require(file.length <= 256 && file.split('/').all { it.isNotBlank() && it !in setOf(".", "..") } &&
                !file.contains('\\') && !file.contains(':') && file.none { it.isISOControl() })
            val directory = requireNotNull(File(content.path).parentFile).canonicalFile
            val target = File(directory, file)
            require(target.canonicalFile == target.absoluteFile && target.canonicalPath.startsWith(directory.path + File.separator))
            var at: File? = target
            while (at != null && at != directory) { require(!Files.isSymbolicLink(at.toPath())); at = at.parentFile }
            require(target.isFile && target.length() <= SkillMarkdown.MAX_BYTES)
            val bytes = target.readBytes()
            require(bytes.size <= SkillMarkdown.MAX_BYTES)
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }
        require(offset <= text.length && (offset == 0 || offset == text.length || !text[offset].isLowSurrogate()))
        var end = minOf(text.length, offset + limit)
        if (end < text.length && end > offset && text[end].isLowSurrogate()) end--
        JSONObject().put("execution_device", "android_local").put("name", content.metadata.name).put("source", content.source)
            .put("file", file ?: "SKILL.md").put("content", text.substring(offset, end)).put("total_characters", text.length)
            .put("next_offset", end.takeIf { it < text.length } ?: JSONObject.NULL).put("truncated", end < text.length)
    }

    companion object {
        private fun localWorkspace(context: Context, id: String): File {
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            return File(context.applicationContext.filesDir, "linux-workspaces/$id")
        }
    }
}
