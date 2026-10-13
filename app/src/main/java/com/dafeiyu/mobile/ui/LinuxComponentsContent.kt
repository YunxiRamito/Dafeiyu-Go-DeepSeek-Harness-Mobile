package com.dafeiyu.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.LinuxComponentState
import com.dafeiyu.mobile.data.LinuxComponentStatus
import com.dafeiyu.mobile.data.LinuxExecutionResult
import com.dafeiyu.mobile.data.LinuxRuntimeManager
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

internal fun componentDownloadSourceLabel(source: String): String = when (source) {
    "official" -> "官方来源"
    "backend" -> "Dafeiyu-GO 后端"
    else -> "CDN 加速"
}

private data class LinuxComponentTestScript(val language: String, val script: String)

/** Explicit offline checks; selecting or opening the page never runs these scripts. */
private fun linuxComponentTestScript(id: String): LinuxComponentTestScript? = when (id) {
    "ubuntu" -> LinuxComponentTestScript("bash", ". /etc/os-release\nprintf 'Ubuntu: %s\\nShell: %s\\n' \"\$PRETTY_NAME\" \"\$BASH_VERSION\"\n")
    "node" -> LinuxComponentTestScript("node", "console.log('Node.js ' + process.version);\nconsole.log('2 + 2 = ' + (2 + 2));\n")
    "python" -> LinuxComponentTestScript("python", "import sys\nprint('Python ' + sys.version.split()[0])\nprint('2 + 2 = ' + str(2 + 2))\n")
    "git" -> LinuxComponentTestScript("bash", "git --version\n")
    "pnpm" -> LinuxComponentTestScript("bash", "pnpm --version\n")
    else -> null
}

private data class LinuxComponentTestOutcome(
    val result: LinuxExecutionResult? = null,
    val error: String? = null,
    val cancelled: Boolean = false,
)

/** The manager owns persistent installation work; leaving this page only stops UI observation. */
@Composable
internal fun LinuxComponentsContent(repository: DemoRepository) {
    val context = LocalContext.current
    val manager = remember(context) { LinuxRuntimeManager(context.applicationContext) }
    val components by manager.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(true) }
    var startingId by remember { mutableStateOf<String?>(null) }
    var cancelling by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var cancellingTest by remember { mutableStateOf(false) }
    val testOutcomes = remember { mutableStateMapOf<String, LinuxComponentTestOutcome>() }
    val installingId = components.firstOrNull { it.status == LinuxComponentStatus.INSTALLING }?.id
    val busy = installingId != null || startingId != null

    suspend fun refresh() {
        refreshing = true
        operationError = null
        try {
            manager.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            operationError = error.message ?: "组件状态读取失败，请重试"
        } finally {
            refreshing = false
        }
    }

    fun testComponent(component: LinuxComponentState) {
        if (busy || refreshing || testingId != null || component.status != LinuxComponentStatus.READY) return
        val script = linuxComponentTestScript(component.id) ?: return
        testingId = component.id
        cancellingTest = false
        testOutcomes.remove(component.id)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val workspace = withContext(Dispatchers.IO) {
                    val privateRoot = context.applicationContext.filesDir.canonicalFile
                    val directory = File(privateRoot, "linux-workspaces/component-check").canonicalFile
                    require(directory.path.startsWith(privateRoot.path + File.separator)) { "测试目录必须位于应用私有范围" }
                    check(directory.isDirectory || directory.mkdirs()) { "无法准备脚本测试目录" }
                    directory
                }
                val result = manager.execute(script.language, script.script, workspace, timeoutSeconds = 10)
                testOutcomes[component.id] = LinuxComponentTestOutcome(result = result)
            } catch (cancelled: CancellationException) {
                testOutcomes[component.id] = LinuxComponentTestOutcome(cancelled = true)
                throw cancelled
            } catch (error: Exception) {
                testOutcomes[component.id] = LinuxComponentTestOutcome(error = error.message ?: "脚本测试失败")
            } finally {
                testingId = null
                cancellingTest = false
                testJob = null
            }
        }
        testJob = job
        job.start()
    }

    LaunchedEffect(manager) { refresh() }
    LaunchedEffect(installingId, startingId) { if (!busy) cancelling = false }

    LinuxComponentCard {
        TinyLabel("Linux 运行环境", primary = true)
        Text("本机开发组件", style = MaterialTheme.typography.titleLarge)
        Hint("安装 Ubuntu 环境和开发工具后，可在工作区运行 Shell、Python 和 JavaScript 脚本。组件状态与版本来自本机检测。")
        LinuxAnimatedText(
            when {
                refreshing -> "正在检测本机组件…"
                busy -> "正在准备组件，离开页面后可返回查看进度"
                testingId != null -> "正在测试 ${components.firstOrNull { it.id == testingId }?.title.orEmpty()}"
                components.isEmpty() -> "暂未读取到组件状态"
                else -> "${components.count { it.status == LinuxComponentStatus.READY }}/${components.size} 项可用"
            },
        )
        Hint("新下载使用：${componentDownloadSourceLabel(repository.downloadSource)}。已开始的下载沿用启动时的线路。")
        FishButton(
            onClick = { scope.launch { refresh() } },
            enabled = !refreshing && !busy && testingId == null,
            accent = false,
            consumeDrag = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FishGlyph("refresh", Modifier.size(18.dp))
            Text(if (refreshing) "检测中…" else "重新检测")
        }
        AnimatedVisibility(
            visible = operationError != null,
            enter = fadeIn(GlassMotion.scalar(GlassMotion.PressMillis, LocalReduceGlassMotion.current)),
            exit = fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, LocalReduceGlassMotion.current)),
        ) {
            Text(operationError.orEmpty(), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }

    components.forEach { actual ->
        key(actual.id) {
            val component = if (actual.id == startingId && installingId == null && actual.status != LinuxComponentStatus.INSTALLING)
                actual.copy(status = LinuxComponentStatus.INSTALLING, detail = "正在准备安装…", progress = null)
            else actual
            LinuxComponentItem(
                component = component,
                canInstall = !busy && !refreshing && testingId == null,
                cancelling = cancelling,
                onInstall = {
                    startingId = component.id
                    operationError = null
                    val source = repository.downloadSource
                    scope.launch {
                        try {
                            manager.install(component.id, source)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            operationError = error.message ?: "组件安装失败，请重试"
                        } finally {
                            startingId = null
                        }
                    }
                },
                onCancel = {
                    cancelling = true
                    manager.cancelInstall()
                },
                canTest = !busy && !refreshing && testingId == null,
                testing = testingId == component.id,
                cancellingTest = cancellingTest,
                testOutcome = testOutcomes[component.id],
                onTest = { testComponent(component) },
                onCancelTest = {
                    cancellingTest = true
                    testJob?.cancel(CancellationException("用户取消组件测试"))
                },
            )
        }
    }
}

@Composable
private fun LinuxComponentCard(content: @Composable ColumnScope.() -> Unit) {
    val reduced = LocalReduceGlassMotion.current
    val duration = if (reduced) 0 else GlassMotion.MorphMillis
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(GlassMotion.scalar(GlassMotion.PressMillis, reduced)) +
            expandVertically(tween(duration, easing = GlassMotion.EaseOut)),
        exit = fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reduced)) +
            shrinkVertically(tween(duration, easing = GlassMotion.EaseOut)),
    ) {
        DetailCard(Modifier.animateContentSize(tween(duration, easing = GlassMotion.EaseOut)), content)
    }
}

private fun LinuxComponentStatus.label(): String = when (this) {
    LinuxComponentStatus.MISSING -> "未安装"
    LinuxComponentStatus.INSTALLING -> "安装中"
    LinuxComponentStatus.READY -> "可用"
    LinuxComponentStatus.ERROR -> "需处理"
    LinuxComponentStatus.UNSUPPORTED -> "当前不支持"
}

@Composable
private fun LinuxAnimatedText(value: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val reduced = LocalReduceGlassMotion.current
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (fadeIn(GlassMotion.scalar(GlassMotion.PressMillis, reduced)) togetherWith
                fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reduced)))
                .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ ->
                    tween(if (reduced) 0 else GlassMotion.PressMillis, easing = GlassMotion.EaseOut)
                }))
        },
        label = "linuxComponentText",
    ) { text -> Text(text, style = MaterialTheme.typography.bodySmall, color = color) }
}

@Composable
private fun LinuxComponentItem(
    component: LinuxComponentState,
    canInstall: Boolean,
    cancelling: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    canTest: Boolean,
    testing: Boolean,
    cancellingTest: Boolean,
    testOutcome: LinuxComponentTestOutcome?,
    onTest: () -> Unit,
    onCancelTest: () -> Unit,
) {
    val reduced = LocalReduceGlassMotion.current
    val status = component.status
    val statusColor by animateColorAsState(
        when (status) {
            LinuxComponentStatus.READY -> MaterialTheme.colorScheme.primary
            LinuxComponentStatus.ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        tween(if (reduced) 0 else GlassMotion.PressMillis, easing = GlassMotion.EaseOut),
        label = "linuxComponentStatusColor",
    )
    LinuxComponentCard {
        Row(Modifier.fillMaxWidth().semantics {
            stateDescription = "${component.title}，${status.label()}" +
                component.version.takeIf { it.isNotBlank() }?.let { "，版本 $it" }.orEmpty()
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FishGlyph(if (component.id == "git") "folder" else "terminal", Modifier.size(24.dp), statusColor)
            Text(component.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            LinuxAnimatedText(status.label(), statusColor)
        }
        Hint(when (component.id) {
            "ubuntu" -> "Shell 与 Linux 工具的基础运行环境"
            "node" -> "JavaScript 脚本运行时"
            "git" -> "工作区版本管理工具"
            "pnpm" -> "JavaScript 项目依赖管理工具"
            "python" -> "Python 脚本运行时"
            else -> "本机运行组件"
        })
        AnimatedVisibility(
            visible = component.version.isNotBlank(),
            enter = fadeIn(GlassMotion.scalar(GlassMotion.PressMillis, reduced)),
            exit = fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reduced)),
        ) {
            SelectionContainer { LinuxAnimatedText("版本 ${component.version}") }
        }
        if (component.detail.isNotBlank()) LinuxAnimatedText(component.detail, statusColor)
        AnimatedVisibility(
            visible = status == LinuxComponentStatus.INSTALLING,
            enter = fadeIn(GlassMotion.scalar(GlassMotion.PressMillis, reduced)) +
                expandVertically(tween(if (reduced) 0 else GlassMotion.PressMillis, easing = GlassMotion.EaseOut)),
            exit = fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reduced)) +
                shrinkVertically(tween(if (reduced) 0 else GlassMotion.PressMillis, easing = GlassMotion.EaseOut)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val progress = component.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                if (progress != null) {
                    val animatedProgress by animateFloatAsState(progress, GlassMotion.scalar(GlassMotion.PressMillis, reduced), label = "linuxInstallProgress")
                    LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                } else if (!reduced) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                FishButton(onClick = onCancel, enabled = !cancelling, accent = false,
                    consumeDrag = false, modifier = Modifier.fillMaxWidth()) {
                    FishGlyph("close", Modifier.size(18.dp))
                    Text(if (cancelling) "正在取消…" else "取消安装")
                }
            }
        }
        AnimatedVisibility(
            visible = status == LinuxComponentStatus.MISSING || status == LinuxComponentStatus.ERROR ||
                status == LinuxComponentStatus.UNSUPPORTED,
            enter = fadeIn(GlassMotion.scalar(GlassMotion.PressMillis, reduced)),
            exit = fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reduced)),
        ) {
            FishButton(onClick = onInstall, enabled = canInstall && status != LinuxComponentStatus.UNSUPPORTED,
                consumeDrag = false, modifier = Modifier.fillMaxWidth()) {
                FishGlyph("download", Modifier.size(18.dp))
                Text(when (status) {
                    LinuxComponentStatus.ERROR -> "重新安装"
                    LinuxComponentStatus.UNSUPPORTED -> "当前设备暂不支持"
                    else -> "下载并安装"
                })
            }
        }
        AnimatedVisibility(
            visible = (status == LinuxComponentStatus.READY || testing) && linuxComponentTestScript(component.id) != null,
            enter = fadeIn(GlassMotion.scalar(GlassMotion.PressMillis, reduced)),
            exit = fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reduced)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Hint("手动运行离线脚本，执行超时 10 秒。")
                FishButton(onClick = onTest, enabled = canTest && status == LinuxComponentStatus.READY,
                    accent = false, consumeDrag = false, modifier = Modifier.fillMaxWidth()) {
                    FishGlyph("play", Modifier.size(18.dp))
                    Text(if (testing) "测试中…" else "测试脚本")
                }
                AnimatedVisibility(
                    visible = testing,
                    enter = fadeIn(GlassMotion.scalar(GlassMotion.PressMillis, reduced)),
                    exit = fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reduced)),
                ) {
                    FishButton(onClick = onCancelTest, enabled = !cancellingTest,
                        accent = false, consumeDrag = false, modifier = Modifier.fillMaxWidth()) {
                        FishGlyph("close", Modifier.size(18.dp))
                        Text(if (cancellingTest) "正在取消测试…" else "取消测试")
                    }
                }
            }
        }
        AnimatedContent(
            targetState = testOutcome,
            transitionSpec = {
                (fadeIn(GlassMotion.scalar(GlassMotion.PressMillis, reduced)) togetherWith
                    fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reduced)))
                    .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ ->
                        tween(if (reduced) 0 else GlassMotion.PressMillis, easing = GlassMotion.EaseOut)
                    }))
            },
            label = "linuxComponentTestOutcome",
        ) { outcome -> outcome?.let { LinuxComponentTestResult(it) } }
    }
}

@Composable
private fun LinuxComponentTestResult(outcome: LinuxComponentTestOutcome) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            outcome.cancelled -> LinuxAnimatedText("测试已取消")
            outcome.error != null -> LinuxAnimatedText("测试失败：${outcome.error}", MaterialTheme.colorScheme.error)
            outcome.result != null -> {
                val result = outcome.result
                Text("上次测试结果", style = MaterialTheme.typography.titleSmall)
                LinuxAnimatedText("退出码：${result.exitCode} · 超时：${if (result.timedOut) "是" else "否"}",
                    if (result.exitCode != 0 || result.timedOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("标准输出", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(result.stdout.ifEmpty { "（无输出）" }, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("标准错误", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(result.stderr.ifEmpty { "（无输出）" }, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.stderr.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
                if (result.outputTruncated) Hint("输出达到运行器上限，已截断。")
            }
        }
    }
}
