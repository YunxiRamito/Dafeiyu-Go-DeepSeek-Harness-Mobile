package com.dafeiyu.mobile.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.RemoteDshApplicationController
import com.dafeiyu.mobile.data.AriesProviderException
import com.dafeiyu.mobile.data.RemoteWorkspaceStore
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Computer paths remain metadata. The picker never asks Android to mount or copy them. */
@Composable
fun RemoteDirectoryPicker(controller: RemoteDshApplicationController?, onDismiss: () -> Unit,
    notify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val expectedScope = remember(controller) { controller?.state?.value?.projection?.binding }
    val currentScope = controller?.state?.collectAsState()?.value?.projection?.binding
    val sameScope = expectedScope != null && expectedScope == currentScope
    var directory by remember { mutableStateOf<RemoteWorkspaceStore.DirectoryListing?>(null) }
    var name by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun browse(path: String?) {
        scope.launch {
            loading = true; error = null
            try {
                val expected = expectedScope ?: throw AriesProviderException("connection_unavailable")
                directory = requireNotNull(controller).listDirectory(path, expected)
                if (name.isBlank()) name = directory?.path?.trimEnd('/', '\\')?.substringAfterLast('/')
                    ?.substringAfterLast('\\')?.takeIf { it.isNotBlank() && ':' !in it } ?: "工作区"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = RemoteDshApplicationController.remoteFailure(failure) }
            finally { loading = false }
        }
    }
    LaunchedEffect(controller) { browse(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择电脑上的文件夹") }, text = {
        Column(Modifier.animateContentSize(tween(if (LocalReduceGlassMotion.current) 0 else GlassMotion.MorphMillis,
            easing = GlassMotion.EaseOut)),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("任务和文件保存在电脑；生成文件需要手动下载。", style = MaterialTheme.typography.bodySmall)
            if (expectedScope != null && !sameScope)
                Text("连接设备已改变，请关闭后重新选择文件夹。", color = MaterialTheme.colorScheme.error)
            directory?.let { listing ->
                Text(listing.path, style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(listing.crumbs.filter { it.path != listing.path }, key = { "crumb-${it.path}" }) { crumb ->
                        TextButton(onClick = { browse(crumb.path) }, enabled = !loading && sameScope) {
                            FishGlyph("back", Modifier.size(18.dp)); Text(crumb.name, Modifier.padding(start = 8.dp))
                        }
                    }
                    items(listing.entries, key = { it.path }) { entry ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !loading && sameScope) { browse(entry.path) }
                            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FishGlyph("folder", Modifier.size(20.dp)); Text(entry.name)
                        }
                    }
                }
                if (listing.truncated) Text("目录条目过多，请进入具体文件夹。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.take(128) }, label = { Text("工作区名称") }, singleLine = true,
                    enabled = sameScope && !loading)
            }
            if (loading) CircularProgressIndicator(Modifier.size(24.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = sameScope && !loading && directory != null && name.trim().isNotEmpty() &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }, onClick = {
            scope.launch {
                loading = true
                try {
                    val expected = expectedScope ?: throw AriesProviderException("connection_unavailable")
                    val receipt = requireNotNull(controller).resolveWorkspace(name, requireNotNull(directory).path, expected)
                    if (receipt.optString("status") == "resolved") { notify("电脑工作区已连接"); onDismiss() }
                    else error = "存在多个同名工作区，请从工作目录列表选择。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = RemoteDshApplicationController.remoteFailure(failure) }
                finally { loading = false }
            }
        }) { Text("使用此文件夹") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
