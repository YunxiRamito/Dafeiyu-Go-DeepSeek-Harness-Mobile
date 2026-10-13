package com.dafeiyu.mobile.data

import org.json.JSONObject

/** A confirmation belongs to the exact computer relationship and the prepared path. */
internal data class RemoteWorkspaceRemovalConfirmation(
    val binding: RemoteDshScope,
    val removal: RemoteWorkspaceStore.Removal,
) {
    fun requireCurrent(connection: JSONObject, snapshot: RemoteWorkspaceStore.Snapshot, workspaceId: String) {
        binding.requireCurrent(connection, requireOnline = true)
        require(snapshot.scope == binding && removal.workspace.id == workspaceId && removal.phase == "prepared") {
            "remote_removal_confirmation_changed"
        }
        val workspace = snapshot.workspaces.firstOrNull { it.id == workspaceId }
            ?: error("remote_workspace_missing")
        require(workspace.path == removal.workspace.path && workspace.title == removal.workspace.title) {
            "remote_removal_confirmation_changed"
        }
        require(connection.optJSONObject("capabilities")?.optBoolean("remote_workspace_lifecycle") == true) {
            "remote_lifecycle_unavailable"
        }
    }

    fun details(deviceLabel: String) = WorkspaceRemovalDetails(
        workspace = Workspace(removal.workspace.id, removal.workspace.title, removal.workspace.path),
        deviceLabel = deviceLabel,
        directoryPaths = listOf(removal.workspace.path),
        externalUri = null,
        hasRunningTasks = removal.stopRequired || removal.activity.any { it.running || it.queued > 0 || it.activity > 0 },
        canDeleteOwnedFiles = removal.filesIdentityVerified,
    )
}

internal fun remoteWorkspaceLifecycleAvailable(connection: JSONObject?, snapshot: RemoteWorkspaceStore.Snapshot?): Boolean {
    if (connection == null || snapshot == null ||
        connection.optJSONObject("capabilities")?.optBoolean("remote_workspace_lifecycle") != true) return false
    return runCatching { snapshot.scope.requireCurrent(connection, requireOnline = true) }.isSuccess
}

/** Archived computer sessions remain metadata; this does not mirror their files. */
internal fun remoteConversationArchiveGroups(
    snapshot: RemoteWorkspaceStore.Snapshot,
    titles: Map<String, String>,
): List<ConversationArchiveGroup> {
    val removals = snapshot.removals.filter { it.phase in REMOVED_PHASES }.distinctBy { it.workspace.id }
    val order = snapshot.workspaces.map { it.id }.toMutableList()
    removals.sortedBy { it.order }.forEach { removal ->
        if (removal.workspace.id !in order) order.add(removal.order.coerceAtMost(order.size), removal.workspace.id)
    }
    val grouped = snapshot.archives.groupBy { it.workspace?.id ?: UNKNOWN_WORKSPACE }
    val groupIds = (grouped.keys + removals.map { it.workspace.id }).distinct()
    return groupIds.map { id ->
        val archives = grouped[id].orEmpty().sortedBy { it.order }
        val removal = removals.firstOrNull { it.workspace.id == id }
        val workspace = snapshot.workspaces.firstOrNull { it.id == id }
            ?: removal?.workspace ?: archives.firstNotNullOfOrNull { it.workspace }
        val workspaceOrder = order.indexOf(id).takeIf { it >= 0 } ?: Int.MAX_VALUE
        val source = Workspace(id, workspace?.title ?: "未记录工作区", workspace?.path)
        ConversationArchiveGroup(
            workspaceId = id,
            workspaceName = source.name,
            workspaceOrder = workspaceOrder,
            directoryPaths = workspace?.path?.let(::listOf).orEmpty(),
            externalUri = null,
            workspaceRemoved = snapshot.workspaces.none { it.id == id },
            conversations = archives.map { archive ->
                Conversation(archive.sessionId, titles[archive.sessionId]?.takeIf { it.isNotBlank() }
                    ?: "电脑会话 · ${archive.sessionId.take(8)}", id, archive.archivedAt,
                    ConversationArchiveOrigin(source, workspaceOrder, archive.order,
                        workspace?.path?.let(::listOf).orEmpty()))
            },
        )
    }.sortedWith(compareBy<ConversationArchiveGroup> { it.workspaceOrder }.thenBy { it.workspaceId })
}

internal val REMOVED_PHASES = setOf("removed", "files_moving", "files_deleting", "files_deleted")
private const val UNKNOWN_WORKSPACE = "remote-unknown-workspace"
