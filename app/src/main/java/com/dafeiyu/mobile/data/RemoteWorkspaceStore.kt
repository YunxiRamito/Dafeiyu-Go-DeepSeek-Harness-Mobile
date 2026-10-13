package com.dafeiyu.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Computer-owned paths are display metadata. Nothing here accesses or mirrors those paths. */
class RemoteWorkspaceStore(private val directory: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "remote-dsh/workspaces"))

    data class Workspace(val id: String, val title: String, val path: String, val sessionIds: List<String>)
    data class Session(val id: String, val cwd: String?, val running: Boolean, val blank: Boolean,
        val agentAvailable: Boolean, val updatedAt: Long, val parentId: String? = null, val origin: String? = null)
    data class Selection(val provider: String, val model: String, val reasoningEffort: String? = null)
    data class Effort(val id: String, val name: String, val description: String? = null)
    data class Model(val id: String, val name: String, val description: String?, val efforts: List<Effort>, val defaultEffort: String?)
    data class Provider(val id: String, val name: String, val models: List<Model>)
    data class CatalogFailure(val id: String, val name: String, val message: String)
    data class Catalog(val selection: Selection, val providers: List<Provider>, val routable: List<String>, val failures: List<CatalogFailure>)
    data class SandboxOption(val value: String, val label: String)
    data class Settings(val selection: Selection?, val sandbox: String?, val options: List<SandboxOption>)
    data class DirectoryItem(val name: String, val path: String, val hidden: Boolean)
    data class DirectoryListing(val path: String, val home: String, val crumbs: List<DirectoryItem>,
        val entries: List<DirectoryItem>, val truncated: Boolean)
    data class Activity(val sessionId: String, val running: Boolean, val queued: Int, val activity: Int)
    data class Removal(val ticket: String, val workspace: Workspace, val order: Int, val phase: String,
        val createdAt: Long, val removedAt: Long?, val restoredId: String?, val filesDeleted: Boolean,
        val activity: List<Activity> = emptyList(), val stopRequired: Boolean = false, val filesIdentityVerified: Boolean = false)
    data class Archive(val sessionId: String, val workspace: Workspace?, val order: Int, val archivedAt: Long?,
        val expiresAt: Long?, val remainingDays: Int?, val archiveVersion: String? = null)
    /** A confirmation belongs to one exact relationship and one archive epoch. */
    data class ArchiveDeletion(val scope: RemoteDshScope, val ticket: String, val sessionId: String,
        val archiveVersion: String, val archivedAt: Long?, val expiresAt: Long?, val executionDevice: String,
        val status: String, val deleted: Boolean, val deletedAt: Long? = null) {
        fun toJson(): JSONObject = JSONObject().put("scope", scope.toJson()).put("ticket", ticket)
            .put("sessionId", sessionId).put("archiveVersion", archiveVersion)
            .put("archivedAt", archivedAt ?: JSONObject.NULL).put("expiresAt", expiresAt ?: JSONObject.NULL)
            .put("executionDevice", executionDevice).put("status", status).put("deleted", deleted)
            .also { if (deletedAt != null) it.put("deletedAt", deletedAt) }
        fun sameConfirmation(other: ArchiveDeletion): Boolean = scope == other.scope && ticket == other.ticket &&
            sessionId == other.sessionId && archiveVersion == other.archiveVersion && archivedAt == other.archivedAt &&
            expiresAt == other.expiresAt && executionDevice == other.executionDevice
    }
    data class Snapshot(val scope: RemoteDshScope, val workspaces: List<Workspace> = emptyList(),
        val sessions: List<Session> = emptyList(), val catalog: Catalog? = null, val settings: Settings? = null,
        val directories: List<DirectoryListing> = emptyList(), val removals: List<Removal> = emptyList(),
        val archives: List<Archive> = emptyList(), val archiveDeletions: List<ArchiveDeletion> = emptyList())

    fun read(scope: RemoteDshScope): Snapshot = synchronized(LOCK) {
        val value = file(scope).read() ?: return@synchronized Snapshot(scope)
        require(value.optInt("schema") == 1 && RemoteDshScope.fromJson(value.getJSONObject("scope")) == scope)
        decode(scope, value)
    }

    /** Results may be replayed after a lost acknowledgement; replacement is idempotent. */
    fun applyOperationResult(scope: RemoteDshScope, operation: String, result: Any?): Snapshot = synchronized(LOCK) {
        remoteValidateJson(result)
        val previous = read(scope)
        val candidate = when (operation) {
            "workspace.list" -> previous.copy(workspaces = parseWorkspaces(result as JSONObject))
            "workspace.resolve" -> {
                val receipt = result as JSONObject
                if (receipt.optString("status") == "resolved") {
                    val workspace = parseWorkspaces(JSONObject().put("workspaces", JSONArray().put(receipt.getJSONObject("workspace")))).single()
                    previous.copy(workspaces = if (previous.workspaces.any { it.id == workspace.id }) previous.workspaces.map { if (it.id == workspace.id) workspace else it }
                        else listOf(workspace) + previous.workspaces)
                } else previous
            }
            "session.list" -> previous.copy(sessions = parseSessions(result))
            "models.catalog" -> previous.copy(catalog = parseCatalog(result as JSONObject))
            "directory.list" -> {
                val listing = parseDirectory(result as JSONObject)
                previous.copy(directories = (previous.directories.filterNot { it.path == listing.path } + listing).takeLast(32))
            }
            "settings.describe" -> previous.copy(settings = parseSettings(result as JSONObject, previous.settings, false))
            "settings.update", "session.selectModel" -> previous.copy(settings = parseSettings(result as JSONObject, previous.settings, true))
            "permissions.catalog" -> previous.copy(settings = parseSettings(JSONObject().put("permissions", result), previous.settings, false))
            "workspace.remove.prepare", "workspace.remove", "workspace.files.delete", "workspace.removed.restore" -> {
                val receipt = result as JSONObject
                val removal = parseRemoval(receipt, previous.removals.firstOrNull { it.ticket == receipt.optString("ticket") })
                val old = previous.removals.firstOrNull { it.ticket == removal.ticket }
                val remaining = previous.workspaces.filterNot { it.id == (old?.workspace?.id ?: removal.workspace.id) }
                when {
                    removal.phase == "restored" && receipt.optString("status") == "restored" -> {
                        val restored = strings(receipt.getJSONArray("restoredSessions"), 1000)
                        previous.copy(workspaces = remaining.filterNot { it.id == removal.workspace.id }.toMutableList().also {
                            it.add(removal.order.coerceAtMost(it.size), removal.workspace)
                        }, removals = previous.removals.filterNot { it.ticket == removal.ticket },
                            archives = previous.archives.filterNot { it.sessionId in restored })
                    }
                    removal.phase in setOf("removed", "files_moving", "files_deleting", "files_deleted") ->
                        previous.copy(workspaces = remaining, sessions = previous.sessions.filterNot { it.id in removal.workspace.sessionIds },
                            removals = previous.removals.filterNot { it.ticket == removal.ticket } + removal)
                    else -> previous.copy(removals = previous.removals.filterNot { it.ticket == removal.ticket } + removal)
                }
            }
            "workspace.removed.list" -> previous.copy(removals = (result as JSONObject).getJSONArray("removals")
                .remoteObjects(512).map { parseRemoval(it) }.also { unique(it.map(Removal::ticket)) })
            "session.archive" -> {
                val receipt = result as JSONObject
                require(receipt.optBoolean("archived") && receipt.optBoolean("tasksStopped")) { "remote_archive_not_stopped" }
                val archive = parseArchive(receipt)
                previous.copy(sessions = previous.sessions.filterNot { it.id == archive.sessionId },
                    archives = previous.archives.filterNot { it.sessionId == archive.sessionId } + archive)
            }
            "session.restore" -> {
                val receipt = result as JSONObject
                if (receipt.getBoolean("restored")) previous.copy(archives = previous.archives.filterNot { it.sessionId == receipt.remoteId("sessionId") }) else previous
            }
            "session.archived.list" -> previous.copy(archives = (result as JSONObject).getJSONArray("archives").remoteObjects(2000)
                .map(::parseArchive).also { unique(it.map(Archive::sessionId)) })
            "session.archived.delete.prepare", "session.archived.delete" -> {
                val receipt = result as JSONObject
                val old = previous.archiveDeletions.firstOrNull { it.ticket == receipt.optString("ticket") }
                val deletion = parseArchiveDeletion(scope, receipt, old)
                require(deletion.deleted == (operation == "session.archived.delete")) { "remote_archive_delete_receipt_invalid" }
                if (operation == "session.archived.delete") require(old != null && deletion.sameConfirmation(old)) {
                    "remote_archive_confirmation_missing"
                }
                require(old == null || old.status != "deleted" || deletion.status == "deleted") { "remote_archive_deleted" }
                val records = previous.archiveDeletions.filterNot { it.ticket == deletion.ticket } + deletion
                require(records.size <= 4096) { "remote_archive_confirmation_cache_full" }
                previous.copy(archiveDeletions = records, archives = previous.archives.map { archive ->
                    if (archive.sessionId == deletion.sessionId && !deletion.deleted) {
                        require(archive.archiveVersion == null || archive.archiveVersion == deletion.archiveVersion) {
                            "remote_archive_version_changed"
                        }
                        archive.copy(archiveVersion = deletion.archiveVersion, archivedAt = deletion.archivedAt,
                            expiresAt = deletion.expiresAt, remainingDays = if (deletion.archivedAt == null) null else archive.remainingDays ?: 30)
                    } else archive
                })
            }
            else -> previous
        }
        // Exact durable terminal receipts also protect delayed catalogue replies
        // and removed-workspace restoration metadata from reviving a deleted ID.
        val deleted = candidate.archiveDeletions.filter(ArchiveDeletion::deleted).map(ArchiveDeletion::sessionId).toSet()
        val updated = if (deleted.isEmpty()) candidate else candidate.copy(
            workspaces = candidate.workspaces.map { it.copy(sessionIds = it.sessionIds.filterNot(deleted::contains)) },
            sessions = candidate.sessions.filterNot { it.id in deleted },
            archives = candidate.archives.filterNot { it.sessionId in deleted },
            removals = candidate.removals.map { it.copy(workspace = it.workspace.copy(
                sessionIds = it.workspace.sessionIds.filterNot(deleted::contains))) })
        if (updated != previous) file(scope).write(encode(updated))
        updated
    }

    fun directory(scope: RemoteDshScope, path: String): DirectoryListing? = read(scope).directories.firstOrNull { it.path == path }
    fun workspaceNamed(scope: RemoteDshScope, name: String): Workspace? = read(scope).workspaces.firstOrNull { it.title == name }

    private fun file(scope: RemoteDshScope) = RemoteDshJsonFile(File(directory, "${scope.storageKey}.json"))

    private fun decode(scope: RemoteDshScope, value: JSONObject): Snapshot = Snapshot(scope,
        parseWorkspaces(value), parseSessions(value.getJSONArray("sessions")),
        value.optJSONObject("catalog")?.let(::parseCatalog), value.optJSONObject("settings")?.let {
            parseSettings(it, null, false)
        }, value.getJSONArray("directories").remoteObjects(32).map(::parseDirectory),
        value.optJSONArray("removals")?.remoteObjects(512)?.map { parseRemoval(it) }.orEmpty().also { unique(it.map(Removal::ticket)) },
        value.optJSONArray("archives")?.remoteObjects(2000)?.map(::parseArchive).orEmpty().also { unique(it.map(Archive::sessionId)) },
        value.optJSONArray("archiveDeletions")?.remoteObjects(4096)?.map { parseArchiveDeletion(scope, it) }.orEmpty()
            .also { unique(it.map(ArchiveDeletion::ticket)) })

    private fun encode(snapshot: Snapshot): JSONObject = JSONObject().put("schema", 1).put("scope", snapshot.scope.toJson())
        .put("workspaces", JSONArray(snapshot.workspaces.map { JSONObject().put("workspaceId", it.id).put("title", it.title)
            .put("path", it.path).put("sessionIds", JSONArray(it.sessionIds)) }))
        .put("sessions", JSONArray(snapshot.sessions.map { JSONObject().put("sessionId", it.id).put("cwd", it.cwd)
            .put("running", it.running).put("blank", it.blank).put("agentAvailable", it.agentAvailable)
            .put("updatedAt", it.updatedAt).put("parentSessionId", it.parentId).put("origin", it.origin) }))
        .put("catalog", snapshot.catalog?.let { catalog -> JSONObject().put("default", selectionJson(catalog.selection))
            .put("routableProviders", JSONArray(catalog.routable)).put("groups", JSONArray(catalog.providers.map { provider ->
                JSONObject().put("id", provider.id).put("name", provider.name).put("models", JSONArray(provider.models.map { model ->
                    JSONObject().put("id", model.id).put("name", model.name).put("description", model.description)
                        .put("reasoning", JSONObject().put("efforts", JSONArray(model.efforts.map { effort ->
                            JSONObject().put("id", effort.id).put("name", effort.name).put("description", effort.description)
                        })).put("defaultEffort", model.defaultEffort))
                }))
            })).put("failures", JSONArray(catalog.failures.map { JSONObject().put("id", it.id)
                .put("name", it.name).put("message", it.message) })) })
        .put("settings", snapshot.settings?.let { settings -> JSONObject().put("selection", settings.selection?.let(::selectionJson))
            .put("permissions", JSONObject().put("defaultPreset", settings.sandbox)
                .put("defaultOptions", JSONArray(settings.options.map { JSONObject().put("value", it.value).put("label", it.label) }))) })
        .put("directories", JSONArray(snapshot.directories.map { listing -> JSONObject().put("path", listing.path)
            .put("home", listing.home).put("truncated", listing.truncated).put("crumbs", directoryItemsJson(listing.crumbs))
            .put("entries", directoryItemsJson(listing.entries)) }))
        .put("removals", JSONArray(snapshot.removals.map { JSONObject().put("ticket", it.ticket).put("workspace", workspaceJson(it.workspace))
            .put("order", it.order).put("phase", it.phase).put("createdAt", it.createdAt).put("removedAt", it.removedAt ?: JSONObject.NULL)
            .put("restoredId", it.restoredId ?: JSONObject.NULL).put("filesDeleted", it.filesDeleted).put("executionDevice", "remote_computer")
            .put("filesDeleteRequiresSeparateConfirmation", true).put("stopRequired", it.stopRequired).put("filesIdentityVerified", it.filesIdentityVerified)
            .put("activity", JSONArray(it.activity.map { activity -> JSONObject().put("sessionId", activity.sessionId).put("running", activity.running)
                .put("queued", activity.queued).put("activity", activity.activity) })) }))
        .put("archives", JSONArray(snapshot.archives.map { JSONObject().put("sessionId", it.sessionId).put("workspace", it.workspace?.let(::workspaceJson) ?: JSONObject.NULL)
            .put("order", it.order).put("archivedAt", it.archivedAt ?: JSONObject.NULL).put("expiresAt", it.expiresAt ?: JSONObject.NULL)
            .put("remainingDays", it.remainingDays ?: JSONObject.NULL).put("archiveVersion", it.archiveVersion ?: JSONObject.NULL) }))
        .put("archiveDeletions", JSONArray(snapshot.archiveDeletions.map(ArchiveDeletion::toJson)))

    private fun workspaceJson(value: Workspace) = JSONObject().put("workspaceId", value.id).put("title", value.title)
        .put("path", value.path).put("sessionIds", JSONArray(value.sessionIds))
    private fun workspace(value: JSONObject): Workspace = parseWorkspaces(JSONObject().put("workspaces", JSONArray().put(value))).single()
    private fun number(value: JSONObject, key: String, maximum: Long): Int = value.remoteLong(key).also { require(it <= maximum) }.toInt()
    private fun optionalTime(value: JSONObject, key: String): Long? = if (!value.has(key) || value.isNull(key)) null else value.remoteLong(key)
    private fun parseRemoval(value: JSONObject, previous: Removal? = null): Removal {
        require(value.getString("executionDevice") == "remote_computer" && value.getBoolean("filesDeleteRequiresSeparateConfirmation"))
        val phase = value.getString("phase").also { require(it in setOf("prepared", "archiving", "removed", "restored", "files_moving", "files_deleting", "files_deleted")) }
        val ticket = value.remoteId("ticket").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
        val activity = value.optJSONArray("activity")?.remoteObjects(1000)?.map {
            Activity(it.remoteId("sessionId"), it.getBoolean("running"), number(it, "queued", 512), number(it, "activity", 64))
        }?.also { unique(it.map(Activity::sessionId)) } ?: previous?.activity.orEmpty()
        val filesDeleted = value.getBoolean("filesDeleted").also { require(it == (phase == "files_deleted")) }
        return Removal(ticket, workspace(value.getJSONObject("workspace")), number(value, "order", 10_000), phase,
            value.remoteLong("createdAt"), optionalTime(value, "removedAt"), value.remoteOptionalString("restoredId"), filesDeleted,
            activity, if (value.has("stopRequired")) value.getBoolean("stopRequired") else previous?.stopRequired ?: false,
            if (value.has("filesIdentityVerified")) value.getBoolean("filesIdentityVerified") else previous?.filesIdentityVerified ?: false)
    }
    private fun parseArchive(value: JSONObject): Archive {
        val archivedAt = optionalTime(value, "archivedAt")
        val expiresAt = optionalTime(value, "expiresAt")
        val days = if (!value.has("remainingDays") || value.isNull("remainingDays")) null else number(value, "remainingDays", 30)
        require(if (archivedAt == null) expiresAt == null && days == null else
            archivedAt <= Long.MAX_VALUE - 30L * 86_400_000 && expiresAt == archivedAt + 30L * 86_400_000 && days != null)
        return Archive(value.remoteId("sessionId"), value.optJSONObject("workspace")?.let(::workspace),
            number(value, "order", 10_000), archivedAt, expiresAt, days, value.remoteOptionalString("archiveVersion")?.also {
                require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "remote_archive_version_invalid" }
            })
    }

    private fun parseWorkspaces(result: JSONObject): List<Workspace> = result.getJSONArray("workspaces").remoteObjects(200).map {
        Workspace(it.remoteId("workspaceId"), it.remoteOptionalString("title") ?: "工作区", path(it.getString("path")),
            strings(it.getJSONArray("sessionIds"), 1000))
    }.also { unique(it.map(Workspace::id)) }

    private fun parseSessions(result: Any?): List<Session> {
        val array = when (result) { is JSONArray -> result; is JSONObject -> result.optJSONArray("items") ?: result.getJSONArray("sessions"); else -> error("remote_session_list_invalid") }
        return array.remoteObjects(1000).map { Session(it.remoteId("sessionId"), it.remoteOptionalString("cwd")?.let(::path),
            it.getBoolean("running"), it.getBoolean("blank"), it.getBoolean("agentAvailable"), it.remoteLong("updatedAt"),
            it.remoteOptionalString("parentSessionId"), it.remoteOptionalString("origin")) }.also { unique(it.map(Session::id)) }
    }

    private fun parseCatalog(result: JSONObject): Catalog {
        var count = 0
        val providers = result.getJSONArray("groups").remoteObjects(256).map { provider ->
            Provider(provider.remoteId("id"), provider.remoteId("name"), provider.getJSONArray("models").remoteObjects(2000).map { model ->
                require(++count <= 2000)
                val reasoning = model.optJSONObject("reasoning")
                val efforts = reasoning?.optJSONArray("efforts")?.remoteObjects(32)?.map {
                    Effort(it.remoteId("id"), it.remoteId("name"), it.remoteOptionalString("description"))
                }.orEmpty().also { unique(it.map(Effort::id)) }
                val default = reasoning?.remoteOptionalString("defaultEffort")
                require(default == null || efforts.any { it.id == default })
                Model(model.remoteId("id"), model.remoteId("name"), model.remoteOptionalString("description"), efforts, default)
            }.also { unique(it.map(Model::id)) })
        }.also { unique(it.map(Provider::id)) }
        val selection = parseSelection(result.getJSONObject("default"))
        val routable = strings(result.getJSONArray("routableProviders"), 256)
        val failures = result.getJSONArray("failures").remoteObjects(256).map {
            CatalogFailure(it.remoteId("id"), it.remoteId("name"), it.remoteOptionalString("message") ?: "目录读取失败")
        }
        return Catalog(selection, providers, routable, failures)
    }

    private fun parseSettings(result: JSONObject, previous: Settings?, requirePersisted: Boolean): Settings {
        if (requirePersisted) require(result.optBoolean("persisted") == true) { "remote_settings_not_persisted" }
        val selection = (result.optJSONObject("selected") ?: result.optJSONObject("selection"))?.let(::parseSelection) ?: previous?.selection
        val permissions = result.optJSONObject("permissions")
        val sandbox = result.remoteOptionalString("sandbox") ?: permissions?.remoteOptionalString("defaultPreset") ?: previous?.sandbox
        val options = permissions?.optJSONArray("defaultOptions")?.remoteObjects(64)?.map {
            SandboxOption(it.remoteId("value"), it.remoteOptionalString("name") ?: it.remoteOptionalString("label") ?: it.remoteId("value"))
        } ?: previous?.options.orEmpty()
        unique(options.map(SandboxOption::value))
        return Settings(selection, sandbox, options)
    }

    private fun parseSelection(value: JSONObject) = Selection(value.remoteId("provider"), value.remoteId("model"), value.remoteOptionalString("reasoningEffort"))
    private fun selectionJson(value: Selection) = JSONObject().put("provider", value.provider).put("model", value.model).put("reasoningEffort", value.reasoningEffort)
    private fun parseDirectory(value: JSONObject) = DirectoryListing(path(value.getString("path")), path(value.getString("home")),
        directoryItems(value.getJSONArray("crumbs"), 128), directoryItems(value.getJSONArray("entries"), 2000), value.getBoolean("truncated"))
    private fun directoryItems(value: JSONArray, max: Int) = value.remoteObjects(max).map {
        DirectoryItem(it.remoteId("name"), path(it.getString("path")), it.getBoolean("hidden"))
    }.also { unique(it.map(DirectoryItem::path)) }
    private fun directoryItemsJson(value: List<DirectoryItem>) = JSONArray(value.map {
        JSONObject().put("name", it.name).put("path", it.path).put("hidden", it.hidden)
    })
    private fun strings(array: JSONArray, max: Int): List<String> {
        require(array.length() <= max)
        return List(array.length()) { array.getString(it).also { value -> require(value.isNotEmpty() && value.length <= 512 && value.none(Char::isISOControl)) } }
            .also(::unique)
    }
    private fun unique(values: List<String>) { require(values.toSet().size == values.size) { "remote_duplicate_identifier" } }
    private fun path(value: String): String = value.also {
        require(it.length in 1..16_384 && !it.contains('\u0000'))
        require(it.startsWith('/') || it.startsWith("\\\\") || it.matches(Regex("[A-Za-z]:[\\\\/].*"))) { "remote_path_not_absolute" }
    }

    companion object {
        private val LOCK = Any()
        private const val ARCHIVE_RETENTION_MILLIS = 30L * 86_400_000
        fun archiveDeletionFromJson(value: JSONObject): ArchiveDeletion =
            parseArchiveDeletion(RemoteDshScope.fromJson(value.getJSONObject("scope")), value)

        /** Accepts only complete prepared or deleted receipts, never an intermediate claim. */
        fun parseArchiveDeletion(scope: RemoteDshScope, value: JSONObject, previous: ArchiveDeletion? = null): ArchiveDeletion {
            remoteValidateJson(value)
            require(value.keys().asSequence().all { it in setOf("scope", "ticket", "sessionId", "archiveVersion",
                "archivedAt", "expiresAt", "executionDevice", "status", "deleted", "deletedAt") }) {
                "remote_archive_delete_receipt_invalid"
            }
            if (value.has("scope")) require(RemoteDshScope.fromJson(value.getJSONObject("scope")) == scope) {
                "remote_archive_confirmation_scope_changed"
            }
            require(value.getString("executionDevice") == "remote_computer") { "remote_archive_delete_device_invalid" }
            val status = value.getString("status")
            val deleted = value.getBoolean("deleted")
            require(status in setOf("prepared", "deleted") && deleted == (status == "deleted")) {
                "remote_archive_delete_receipt_invalid"
            }
            val ticket = value.remoteId("ticket").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
            val version = value.remoteId("archiveVersion").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
            require(value.has("archivedAt")) { "remote_archive_delete_receipt_invalid" }
            val archivedAt = if (value.isNull("archivedAt")) null else value.remoteLong("archivedAt")
            val expiresAt = when {
                value.has("expiresAt") -> if (value.isNull("expiresAt")) null else value.remoteLong("expiresAt")
                previous != null -> previous.expiresAt
                archivedAt != null && archivedAt <= Long.MAX_VALUE - ARCHIVE_RETENTION_MILLIS -> archivedAt + ARCHIVE_RETENTION_MILLIS
                else -> null
            }
            require(if (archivedAt == null) expiresAt == null else
                archivedAt <= Long.MAX_VALUE - ARCHIVE_RETENTION_MILLIS && expiresAt == archivedAt + ARCHIVE_RETENTION_MILLIS) {
                "remote_archive_delete_retention_invalid"
            }
            if (!deleted) require(value.has("expiresAt")) { "remote_archive_delete_receipt_invalid" }
            val deletedAt = if (value.has("deletedAt") && !value.isNull("deletedAt")) value.remoteLong("deletedAt") else null
            require(if (deleted) deletedAt != null && (archivedAt == null || deletedAt >= archivedAt) else deletedAt == null) {
                "remote_archive_delete_receipt_invalid"
            }
            val result = ArchiveDeletion(scope, ticket, value.remoteId("sessionId"), version, archivedAt, expiresAt,
                "remote_computer", status, deleted, deletedAt)
            if (previous != null) require(result.sameConfirmation(previous)) { "remote_archive_confirmation_changed" }
            return result
        }
    }
}
