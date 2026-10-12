package com.dafeiyu.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.Conversation
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.ui.components.FishDivider
import com.dafeiyu.mobile.ui.components.FishGlyph
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private const val ARCHIVE_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

@Composable
fun ConversationArchiveContent(repository: DemoRepository, notify: (String) -> Unit) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(repository) {
        repository.purgeExpiredArchives(nowMillis)
        while (true) {
            delay(60_000L)
            nowMillis = System.currentTimeMillis()
            repository.purgeExpiredArchives(nowMillis)
        }
    }

    val archived = repository.archivedConversations
    DetailCard {
        TinyLabel("归档管理", primary = true)
        Text("归档对话会保留 30 天", style = MaterialTheme.typography.titleMedium)
        Hint("到期后自动永久删除。你也可以随时还原，或立即永久删除。")
    }

    if (archived.isEmpty()) {
        DetailCard {
            FishGlyph("folder", Modifier.size(26.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Text("这里暂时没有归档对话", style = MaterialTheme.typography.titleMedium)
            Hint("在工作目录中向对话行右滑，即可将对话归档。")
        }
    } else {
        archived.forEach { conversation ->
            ArchiveConversationCard(
                conversation = conversation,
                nowMillis = nowMillis,
                onRestore = {
                    repository.restoreConversation(conversation.id)
                    notify("已还原：${conversation.title}")
                },
                onDelete = { deleteTarget = conversation.id },
            )
        }
    }

    val target = archived.firstOrNull { it.id == deleteTarget }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("永久删除此对话？") },
            text = { Text("“${target.title}”及其消息将从本机删除，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    repository.deleteArchivedConversation(target.id)
                    deleteTarget = null
                    notify("已永久删除对话")
                }) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ArchiveConversationCard(
    conversation: Conversation,
    nowMillis: Long,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val archivedAt = conversation.archivedAtMillis ?: nowMillis
    val expiresAt = archivedAt + ARCHIVE_RETENTION_MILLIS
    val remainingDays = ((expiresAt - nowMillis).coerceAtLeast(0L) + DAY_MILLIS - 1) / DAY_MILLIS
    val archivedLabel = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(archivedAt))

    DetailCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FishGlyph("chat", Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(conversation.title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("归档于 $archivedLabel", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FishDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${remainingDays.coerceAtLeast(1)} 天后自动删除", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRestore) { Text("还原") }
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
