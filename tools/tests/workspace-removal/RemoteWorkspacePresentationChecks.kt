package com.dafeiyu.mobile.data

import org.json.JSONArray
import org.json.JSONObject

fun main() {
    var passed = 0
    fun test(name: String, action: () -> Unit) { action(); passed++; println("PASS $name") }
    val binding = RemoteDshScope("node-a", "group-a", "phone-a", "computer-a")
    fun connection(): JSONObject = JSONObject().put("node_id", binding.nodeId).put("group_id", binding.groupId)
        .put("local_device", JSONObject().put("id", binding.localDeviceId))
        .put("remote_device", JSONObject().put("id", binding.remoteDeviceId).put("online", "online"))
        .put("pairing_status", "paired").put("server_connected", true)
        .put("capabilities", JSONObject().put("remote_workspace_lifecycle", true))
    val first = RemoteWorkspaceStore.Workspace("one", "First", "/computer/user/work/one", listOf("session-one"))
    val second = RemoteWorkspaceStore.Workspace("two", "Second", "/computer/user/work/two", listOf("session-two"))
    val prepared = RemoteWorkspaceStore.Removal("ticket-a", first, 0, "prepared", 1000L,
        null, null, false, filesIdentityVerified = true)
    val snapshot = RemoteWorkspaceStore.Snapshot(binding, listOf(first, second))
    val confirmation = RemoteWorkspaceRemovalConfirmation(binding, prepared)
    test("nonempty computer workspace can be confirmed with exact prepared relationship and path") {
        check(remoteWorkspaceLifecycleAvailable(connection(), snapshot))
        confirmation.requireCurrent(connection(), snapshot, first.id)
        check(confirmation.details("Computer").directoryPaths == listOf(first.path))
        check(confirmation.details("Computer").externalUri == null)
    }
    test("changing any relationship identity rejects an old destructive confirmation") {
        listOf("node_id", "group_id").forEach { key ->
            val changed = connection().put(key, "other")
            check(!remoteWorkspaceLifecycleAvailable(changed, snapshot))
            check(runCatching { confirmation.requireCurrent(changed, snapshot, first.id) }.isFailure)
        }
        listOf("local_device", "remote_device").forEach { key ->
            val changed = connection().also { it.getJSONObject(key).put("id", "other") }
            check(runCatching { confirmation.requireCurrent(changed, snapshot, first.id) }.isFailure)
        }
        val changedSnapshot = snapshot.copy(scope = binding.copy(groupId = "other"))
        check(runCatching { confirmation.requireCurrent(connection(), changedSnapshot, first.id) }.isFailure)
    }
    test("offline revoked or missing lifecycle capability cannot authorize removal") {
        listOf(connection().put("server_connected", false), connection().put("pairing_status", "revoked"),
            connection().put("terminal_receipt", JSONObject().put("status", "group_closed")),
            connection().also { it.getJSONObject("remote_device").put("online", "offline") },
            connection().also { it.getJSONObject("capabilities").put("remote_workspace_lifecycle", false) }).forEach { changed ->
            check(!remoteWorkspaceLifecycleAvailable(changed, snapshot))
            check(runCatching { confirmation.requireCurrent(changed, snapshot, first.id) }.isFailure)
        }
    }
    test("changed computer path or title and missing workspace invalidate confirmation") {
        listOf(first.copy(path = "/computer/other"), first.copy(title = "Renamed")).forEach { changed ->
            check(runCatching { confirmation.requireCurrent(connection(), snapshot.copy(workspaces = listOf(changed)), first.id) }.isFailure)
        }
        check(runCatching { confirmation.requireCurrent(connection(), snapshot.copy(workspaces = emptyList()), first.id) }.isFailure)
        check(runCatching { confirmation.requireCurrent(connection(), snapshot, second.id) }.isFailure)
    }
    test("a removed ticket cannot be reused as a fresh prepare confirmation") {
        val removed = confirmation.copy(removal = prepared.copy(phase = "removed", removedAt = 2000L))
        check(runCatching { removed.requireCurrent(connection(), snapshot, first.id) }.isFailure)
    }
    test("running queued or tool activity requires a stop confirmation") {
        listOf(RemoteWorkspaceStore.Activity("session-one", true, 0, 0),
            RemoteWorkspaceStore.Activity("session-one", false, 1, 0),
            RemoteWorkspaceStore.Activity("session-one", false, 0, 1)).forEach { activity ->
            check(confirmation.copy(removal = prepared.copy(activity = listOf(activity))).details("Computer").hasRunningTasks)
        }
        check(confirmation.copy(removal = prepared.copy(stopRequired = true)).details("Computer").hasRunningTasks)
        check(!confirmation.details("Computer").hasRunningTasks)
    }
    test("unverified file identity keeps file deletion disabled") {
        check(!confirmation.copy(removal = prepared.copy(filesIdentityVerified = false)).details("Computer").canDeleteOwnedFiles)
        check(confirmation.details("Computer").canDeleteOwnedFiles)
    }
    test("removed workspace keeps original order path name and conversation ownership") {
        val removed = prepared.copy(phase = "removed", removedAt = 2000L)
        val archives = listOf(RemoteWorkspaceStore.Archive("session-one", first, 0, 2000L,
            2000L + 30L * 86_400_000L, 30), RemoteWorkspaceStore.Archive("session-two", second, 0, 2001L,
            2001L + 30L * 86_400_000L, 30))
        val groups = remoteConversationArchiveGroups(snapshot.copy(workspaces = listOf(second),
            removals = listOf(removed), archives = archives), mapOf("session-one" to "Original title"))
        check(groups.map { it.workspaceId } == listOf("one", "two"))
        check(groups.first().workspaceName == "First" && groups.first().workspaceRemoved)
        check(groups.first().directoryPaths == listOf(first.path))
        check(groups.first().conversations.single().title == "Original title")
        check(groups.first().conversations.single().archiveOrigin?.workspace?.id == "one")
    }
    test("empty removed workspace remains visible for restoring its record") {
        val groups = remoteConversationArchiveGroups(snapshot.copy(workspaces = listOf(second),
            removals = listOf(prepared.copy(phase = "removed", removedAt = 2000L))), emptyMap())
        check(groups.single().workspaceId == "one" && groups.single().conversations.isEmpty())
    }
    test("unknown archive time and workspace remain unknown rather than inventing a deadline") {
        val archives = listOf(RemoteWorkspaceStore.Archive("legacy", null, 0, null, null, null))
        val groups = remoteConversationArchiveGroups(snapshot.copy(archives = archives), emptyMap())
        check(groups.single().conversations.single().archivedAtMillis == null)
        check(groups.single().directoryPaths.isEmpty())
        check(groups.single().workspaceName == "未记录工作区")
    }
    test("archive order is independent of JSON arrival order") {
        val archives = listOf(RemoteWorkspaceStore.Archive("later", first, 5, null, null, null),
            RemoteWorkspaceStore.Archive("earlier", first, 1, null, null, null))
        val groups = remoteConversationArchiveGroups(snapshot.copy(archives = archives), emptyMap())
        check(groups.single().conversations.map { it.id } == listOf("earlier", "later"))
        check(JSONArray(groups.single().conversations.map { it.id }).length() == 2)
    }
    println("$passed remote workspace presentation checks passed; no computer files or paid requests were accessed")
}
