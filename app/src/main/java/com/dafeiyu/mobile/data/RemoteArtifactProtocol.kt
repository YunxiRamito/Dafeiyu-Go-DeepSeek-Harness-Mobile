package com.dafeiyu.mobile.data

import org.json.JSONObject

data class RemoteArtifactEntry(val name: String, val relativePath: String, val directory: Boolean,
    val size: Long, val version: String)
data class RemoteArtifactListing(val workspaceId: String, val directory: String,
    val entries: List<RemoteArtifactEntry>, val nextCursor: String?)
data class RemoteArtifactTicket(val ticket: String, val workspaceId: String, val relativePath: String,
    val fileName: String, val version: String, val size: Long, val sha256: String, val chunkBytes: Int, val expiresAt: Long)

object RemoteArtifactProtocol {
    const val MAX_BYTES = 512L * 1024 * 1024
    const val CHUNK_BYTES = 48 * 1024
    val operations = setOf("artifact.list", "artifact.prepare", "artifact.read", "artifact.close")
    private val identifier = Regex("[A-Za-z0-9_-]{1,128}")
    private val hash = Regex("[a-f0-9]{64}")
    fun path(value: String, empty: Boolean = false): String {
        if (value.isEmpty() && empty) return value
        require(value.length in 1..4096 && '\\' !in value && ':' !in value && value.none { it.isISOControl() })
        val segments = value.split('/')
        require(segments.size <= 64 && segments.all { it.length in 1..255 && it !in setOf(".", "..") })
        return value
    }
    fun listing(value: JSONObject, workspaceId: String, directory: String): RemoteArtifactListing {
        val id = value.string("workspaceId").also { require(it == workspaceId && it.matches(identifier)) }
        val root = path(value.string("directory"), true).also { require(it == directory) }
        val entries = value.getJSONArray("entries").also { require(it.length() <= 100) }.let { list ->
            (0 until list.length()).map { index ->
                val entry = list.getJSONObject(index)
                val name = path(entry.string("name")).also { require('/' !in it) }
                val relative = path(entry.string("relativePath")).also {
                    require(it == if (root.isEmpty()) name else "$root/$name")
                }
                val kind = entry.string("kind").also { require(it in setOf("file", "directory")) }
                val size = entry.integer("size").also { require(it in 0..MAX_BYTES && (kind != "directory" || it == 0L)) }
                RemoteArtifactEntry(name, relative, kind == "directory", size, entry.string("version").also { require(it.matches(hash)) })
            }.also { list -> require(list.map { it.name }.distinct().size == list.size) }
        }
        val cursor = value.get("nextCursor").let { if (it == JSONObject.NULL) null else
            (it as? String)?.also { name -> require(entries.isNotEmpty() && name == entries.last().name) } ?: error("remote_artifact_cursor_invalid") }
        return RemoteArtifactListing(id, root, entries, cursor)
    }
    fun ticket(value: JSONObject, workspaceId: String, entry: RemoteArtifactEntry): RemoteArtifactTicket {
        require(!entry.directory)
        val ticket = value.string("ticket").also { require(it.matches(identifier)) }
        val id = value.string("workspaceId").also { require(it == workspaceId) }
        val relative = path(value.string("relativePath")).also { require(it == entry.relativePath) }
        val name = value.string("fileName").also { require(it == entry.name) }
        val version = value.string("version").also { require(it == entry.version) }
        val size = value.integer("size").also { require(it == entry.size && it in 0..MAX_BYTES) }
        val sha = value.string("sha256").also { require(it.matches(hash)) }
        val chunk = value.integer("chunkBytes").also { require(it == CHUNK_BYTES.toLong()) }.toInt()
        val expires = value.integer("expiresAt").also { require(it > 0) }
        return RemoteArtifactTicket(ticket, id, relative, name, version, size, sha, chunk, expires)
    }
    fun bytes(value: JSONObject, ticket: RemoteArtifactTicket, offset: Long, requestedLength: Int): ByteArray {
        require(offset in 0..ticket.size && requestedLength in 1..ticket.chunkBytes)
        require(value.string("ticket") == ticket.ticket && value.integer("offset") == offset &&
            value.integer("size") == ticket.size && value.string("version") == ticket.version && value.string("sha256") == ticket.sha256)
        val expected = minOf(requestedLength.toLong(), ticket.size - offset).toInt()
        val encoded = value.string("bytesBase64")
        require(encoded.length <= (ticket.chunkBytes + 2) / 3 * 4)
        val decoded = java.util.Base64.getDecoder().decode(encoded)
        require(decoded.size == expected && java.util.Base64.getEncoder().encodeToString(decoded) == encoded)
        return decoded
    }
    private fun JSONObject.string(name: String): String = get(name) as? String ?: error("remote_artifact_type_invalid")
    private fun JSONObject.integer(name: String): Long = when (val value = get(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("remote_artifact_integer_invalid")
    }
}
