package com.dafeiyu.mobile.data

/** Captured before removing the workspace record; messages and tool history keep their IDs. */
data class ConversationArchiveOrigin(
    val workspace: Workspace,
    val workspaceOrder: Int,
    val conversationOrder: Int,
    val directoryPaths: List<String> = emptyList(),
)

data class ConversationArchiveGroup(
    val workspaceId: String,
    val workspaceName: String,
    val workspaceOrder: Int,
    val directoryPaths: List<String>,
    val externalUri: String?,
    val workspaceRemoved: Boolean,
    val conversations: List<Conversation>,
)

/** Read-only confirmation information. A request returning true is accepted, not completed. */
data class WorkspaceRemovalDetails(
    val workspace: Workspace,
    val deviceLabel: String,
    val directoryPaths: List<String>,
    val externalUri: String? = workspace.uri,
    val hasRunningTasks: Boolean = false,
    val canDeleteOwnedFiles: Boolean = true,
)

/** Workspace contents do not hide the action. Task and file decisions belong to confirmation. */
internal fun canRemoveWorkspaceRecord(
    workspaceId: String,
    workspaces: List<Workspace>,
    initialized: Boolean,
    busy: Boolean,
    storageReady: Boolean,
): Boolean = initialized && !busy && storageReady && workspaces.any { it.id == workspaceId }

internal fun prepareWorkspaceRemovalSnapshot(
    snapshot: WorkspaceSnapshot,
    workspaceId: String,
    directoryPaths: List<String>,
    replacementWorkspace: Workspace? = null,
    nowMillis: Long = System.currentTimeMillis(),
): WorkspaceSnapshot {
    require(nowMillis >= 0L) { "归档时间无效" }
    val workspace = snapshot.workspaces.firstOrNull { it.id == workspaceId }
        ?: error("工作区已不存在")
    val workspaceOrder = workspaceOrderIncludingArchives(snapshot.workspaces, snapshot.conversations).indexOf(workspaceId)
    val remainingWorkspaces = snapshot.workspaces.filterNot { it.id == workspaceId }.ifEmpty {
        requireNotNull(replacementWorkspace) { "移除最后一个工作区前需要准备默认工作区" }.let {
            require(it.id != workspaceId) { "默认工作区必须使用新身份" }
            listOf(it)
        }
    }
    val archivedConversations = snapshot.conversations.mapIndexed { order, conversation ->
        if (conversation.workspaceId != workspaceId) conversation else conversation.copy(
            archivedAtMillis = conversation.archivedAtMillis ?: nowMillis,
            archiveOrigin = ConversationArchiveOrigin(workspace, workspaceOrder, order, directoryPaths.toList()),
        )
    }
    val selectedWorkspace = snapshot.selectedWorkspaceId.takeIf { selected ->
        remainingWorkspaces.any { it.id == selected }
    } ?: remainingWorkspaces.first().id
    val selectedConversation = archivedConversations.firstOrNull {
        it.id == snapshot.selectedConversationId && it.workspaceId == selectedWorkspace &&
            it.archivedAtMillis == null
    } ?: archivedConversations.firstOrNull {
        it.workspaceId == selectedWorkspace && it.archivedAtMillis == null
    }
    return snapshot.copy(
        workspaces = remainingWorkspaces,
        conversations = archivedConversations,
        selectedWorkspaceId = selectedWorkspace,
        selectedConversationId = selectedConversation?.id.orEmpty(),
    )
}

/** Restores the originating workspace at its old position when its record was removed. */
internal fun prepareConversationRestoreSnapshot(
    snapshot: WorkspaceSnapshot,
    conversationId: String,
): WorkspaceSnapshot {
    val conversation = snapshot.conversations.firstOrNull { it.id == conversationId }
        ?: error("归档对话已不存在")
    require(conversation.archivedAtMillis != null) { "对话已还原" }
    val restoredWorkspaces = snapshot.workspaces.toMutableList()
    if (restoredWorkspaces.none { it.id == conversation.workspaceId }) {
        val origin = requireNotNull(conversation.archiveOrigin) { "归档缺少原工作区信息" }
        require(origin.workspace.id == conversation.workspaceId) { "归档工作区身份不匹配" }
        val orderedIds = workspaceOrderIncludingArchives(snapshot.workspaces, snapshot.conversations)
        val position = orderedIds.indexOf(conversation.workspaceId)
        val precedingLiveIds = orderedIds.take(position.coerceAtLeast(0)).toHashSet()
        val insertion = restoredWorkspaces.count { it.id in precedingLiveIds }
        restoredWorkspaces.add(insertion, origin.workspace)
    }
    return snapshot.copy(
        workspaces = restoredWorkspaces,
        conversations = snapshot.conversations.map {
            if (it.id == conversationId) it.copy(archivedAtMillis = null, archiveOrigin = null) else it
        },
    )
}

fun conversationArchiveGroups(
    workspaces: List<Workspace>,
    conversations: List<Conversation>,
): List<ConversationArchiveGroup> {
    val activeWorkspaces = workspaces.associateBy { it.id }
    val workspaceOrders = workspaceOrderIncludingArchives(workspaces, conversations)
        .mapIndexed { index, id -> id to index }.toMap()
    val conversationOrders = conversations.mapIndexed { index, conversation -> conversation.id to index }.toMap()
    return conversations.filter { it.archivedAtMillis != null }.groupBy { it.workspaceId }.map { (id, archived) ->
        val active = activeWorkspaces[id]
        val origin = archived.firstNotNullOfOrNull { it.archiveOrigin?.takeIf { item -> item.workspace.id == id } }
        ConversationArchiveGroup(
            workspaceId = id,
            workspaceName = active?.name ?: origin?.workspace?.name ?: "已移除的工作区",
            workspaceOrder = workspaceOrders[id] ?: origin?.workspaceOrder ?: Int.MAX_VALUE,
            directoryPaths = origin?.directoryPaths.orEmpty(),
            externalUri = active?.uri ?: origin?.workspace?.uri,
            workspaceRemoved = active == null,
            conversations = archived.sortedWith(compareBy<Conversation> {
                it.archiveOrigin?.conversationOrder ?: conversationOrders[it.id] ?: Int.MAX_VALUE
            }.thenBy { conversationOrders[it.id] ?: Int.MAX_VALUE }),
        )
    }.sortedWith(compareBy<ConversationArchiveGroup> { it.workspaceOrder }
        .thenBy { conversationOrders[it.conversations.firstOrNull()?.id] ?: Int.MAX_VALUE })
}

/** Retained origins occupy their old positions even after several successive removals. */
private fun workspaceOrderIncludingArchives(
    workspaces: List<Workspace>,
    conversations: List<Conversation>,
): List<String> {
    val orderedIds = workspaces.mapTo(mutableListOf()) { it.id }
    val liveIds = orderedIds.toHashSet()
    val origins = conversations.asSequence().filter { it.archivedAtMillis != null }
        .mapNotNull { it.archiveOrigin?.takeIf { origin -> origin.workspace.id == it.workspaceId } }
        .distinctBy { it.workspace.id }.filterNot { it.workspace.id in liveIds }
        .sortedBy { it.workspaceOrder }.toList()
    origins.forEach { origin ->
        orderedIds.add(origin.workspaceOrder.coerceIn(0, orderedIds.size), origin.workspace.id)
    }
    return orderedIds
}

fun archiveRemainingDays(conversation: Conversation, nowMillis: Long): Long? {
    val archivedAt = conversation.archivedAtMillis ?: return null
    val elapsed = if (nowMillis <= archivedAt) 0L else nowMillis - archivedAt
    val remaining = (ARCHIVE_RETENTION_MILLIS - elapsed).coerceAtLeast(0L)
    val dayMillis = 24L * 60L * 60L * 1000L
    return (remaining + dayMillis - 1L) / dayMillis
}
