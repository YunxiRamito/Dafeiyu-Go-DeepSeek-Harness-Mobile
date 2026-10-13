package com.dafeiyu.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeiyu.mobile.data.DymBackupCoordinator
import com.dafeiyu.mobile.data.DymRetainedSnapshot
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishCard
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Kept apart from installed data so users can retrieve either side of a conflict. */
@Composable
fun DymRetainedResourcesContent(coordinator: DymBackupCoordinator, revision: Int = 0) {
    var snapshots by remember(coordinator) { mutableStateOf<List<DymRetainedSnapshot>>(emptyList()) }
    var pendingId by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reduced = LocalReduceGlassMotion.current
    val colors = MaterialTheme.colorScheme
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = pendingId
        pendingId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            exporting = true
            try {
                val bytes = coordinator.exportRetained(uri, id)
                status = "原始资源已导出 · ${retainedSize(bytes)}"
                failed = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "原始资源导出失败，请重新选择保存位置"; failed = true }
            finally { exporting = false }
        }
    }
    LaunchedEffect(coordinator, revision) {
        try { snapshots = coordinator.retainedSnapshots() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = "原始资源读取失败，请检查应用存储空间"; failed = true }
    }
    AnimatedVisibility(snapshots.isNotEmpty() || status != null,
        enter = fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, reduced)),
        exit = fadeOut(GlassMotion.scalar(GlassMotion.MorphMillis, reduced))) {
        FishCard(modifier = Modifier.fillMaxWidth().animateContentSize(tween(
            if (reduced) 0 else GlassMotion.MorphMillis, easing = GlassMotion.EaseOut))) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("导入的原始资源", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("导入文件独立保留。遇到冲突时，可以导出原来的版本，当前资源不会被替换。",
                    color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                for ((index, snapshot) in snapshots.withIndex()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("原始资源 ${index + 1}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(snapshot.importedAtMillis))
                            Text("$date · ${snapshot.files} 个文件 · ${retainedSize(snapshot.bytes)}",
                                color = colors.onSurfaceVariant, fontSize = 11.sp)
                        }
                        FishButton(onClick = {
                            pendingId = snapshot.id
                            launcher.launch("Dafeiyu-原始资源-${snapshot.id.takeLast(8)}.dym")
                        }, accent = false, enabled = !exporting && pendingId == null) {
                            Text(if (exporting) "导出中" else "导出", fontSize = 12.sp)
                        }
                    }
                }
                status?.let { Text(it, fontSize = 12.sp, lineHeight = 18.sp,
                    color = if (failed) colors.error else colors.onSurfaceVariant) }
            }
        }
    }
}

private fun retainedSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
