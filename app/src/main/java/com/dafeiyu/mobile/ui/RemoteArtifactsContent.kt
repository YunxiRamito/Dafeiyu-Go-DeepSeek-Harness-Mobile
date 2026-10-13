package com.dafeiyu.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.AriesProviderException
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.RemoteArtifactDownloadController
import com.dafeiyu.mobile.data.RemoteArtifactListing
import com.dafeiyu.mobile.data.RemoteDshApplicationController
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Browse computer metadata. A file is fetched only after its download action is selected. */
@Composable
fun RemoteArtifactsContent(repository: DemoRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = repository.remoteArtifactDownloads
    val binding = remember(controller) { repository.remoteWorkspaceScope }
    val workspaceId = remember(controller) { repository.displayWorkspaceId }
    val currentBinding = repository.remoteWorkspaceScope
    val sameSelection = binding != null && binding == currentBinding && workspaceId == repository.displayWorkspaceId
    val capability = repository.remoteDshState.connection?.optJSONObject("capabilities")?.optBoolean("remote_artifacts") == true
    val ready = sameSelection && controller != null && capability && repository.remoteDshState.remoteOnline
    val download = controller?.state?.collectAsState()?.value ?: RemoteArtifactDownloadController.State()
    var listing by remember { mutableStateOf<RemoteArtifactListing?>(null) }
    var directory by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<RemoteArtifactDownloadController.State?>(null) }
    var saving by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val expected = pendingExport
        pendingExport = null
        if (uri != null && expected != null && controller != null) scope.launch {
            saving = true
            try { controller.export(context, uri, expected); repository.updateNotice("文件已保存") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { repository.updateNotice("文件保存未完成，请重新选择位置") }
            finally { saving = false }
        }
    }
    suspend fun browse(next: String, cursor: String? = null) {
        if (!ready) return
        loading = true; error = null
        try {
            val page = requireNotNull(controller).list(requireNotNull(binding), workspaceId, next, cursor)
            if (repository.remoteWorkspaceScope != binding || repository.displayWorkspaceId != workspaceId)
                throw AriesProviderException("conflict")
            listing = if (cursor != null && listing?.directory == next) page.copy(entries = listing!!.entries + page.entries) else page
            directory = next
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = RemoteDshApplicationController.remoteFailure(failure) }
        finally { loading = false }
    }
    LaunchedEffect(controller, binding, capability) { browse("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("电脑工作区文件") }, text = {
        Column(Modifier.animateContentSize(tween(if (LocalReduceGlassMotion.current) 0 else GlassMotion.MorphMillis, easing = GlassMotion.EaseOut)),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("文件保存在电脑。点下载后才传到手机，再选择保存位置。", style = MaterialTheme.typography.bodySmall)
            if (!sameSelection) Text("设备或工作区已改变，请关闭后重新打开。", color = MaterialTheme.colorScheme.error)
            else if (!capability) Text("电脑文件下载（预留）", style = MaterialTheme.typography.bodySmall)
            Text(directory.ifEmpty { "工作区根目录" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = ready && !loading && directory.isNotEmpty(), onClick = {
                    scope.launch { browse(directory.substringBeforeLast('/', "")) }
                }) { FishGlyph("back", Modifier.size(18.dp)); Text("上一级") }
                TextButton(enabled = ready && !loading, onClick = { scope.launch { browse(directory) } }) { Text("刷新") }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(listing?.entries.orEmpty(), key = { it.relativePath }) { entry ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = ready && !loading && entry.directory) {
                        scope.launch { browse(entry.relativePath) }
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FishGlyph(if (entry.directory) "folder" else "document", Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!entry.directory) Text("${(entry.size + 1023) / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!entry.directory) TextButton(enabled = ready && !loading && !download.busy, onClick = {
                            controller?.download(requireNotNull(binding), workspaceId, entry)
                        }) { Text("下载") }
                    }
                }
                listing?.nextCursor?.let { cursor -> item("next") {
                    TextButton(enabled = ready && !loading, onClick = { scope.launch { browse(directory, cursor) } }) { Text("加载更多") }
                } }
            }
            if (loading) CircularProgressIndicator(Modifier.size(24.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (download.busy) {
                Text(download.fileName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val total = download.totalBytes
                if (total != null && total > 0) LinearProgressIndicator(progress = {
                    (download.receivedBytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
                }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("${(download.receivedBytes + 1023) / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { controller?.cancel() }) { Text("暂停下载") }
            }
            download.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (download.completed != null) TextButton(enabled = !saving && !download.busy, onClick = {
                pendingExport = download
                save.launch(download.fileName ?: "电脑文件")
            }) { Text(if (saving) "正在保存…" else "保存到文件") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
