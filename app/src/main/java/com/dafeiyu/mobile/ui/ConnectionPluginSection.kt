package com.dafeiyu.mobile.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dafeiyu.mobile.data.ConnectionPluginPackage
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishCard
import com.dafeiyu.mobile.ui.components.FishGlyph
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private enum class ConnectionPackageOperation(val label: String) {
    READ("正在读取安装状态"),
    INSTALL("正在安装内置包"),
    UPDATE("正在检查并安装更新"),
}

@Stable
internal class ConnectionPluginUiState(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var currentVersion by mutableStateOf<String?>(null)
        private set
    var hasLoaded by mutableStateOf(false)
        private set
    var readFailed by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var operation by mutableStateOf<ConnectionPackageOperation?>(null)
    val busy: Boolean get() = operation != null || !hasLoaded
    val progressLabel: String get() = operation?.label ?: ConnectionPackageOperation.READ.label

    fun matches(query: String): Boolean = query.isBlank() ||
        "大肥鱼连接插件 dafeiyu-go-connect 设备 同步 APK 内置 0.2.0 待运行时接入 ${currentVersion.orEmpty()}"
            .contains(query.trim(), ignoreCase = true)

    fun refresh() = perform(ConnectionPackageOperation.READ)
    fun installBundled() = perform(ConnectionPackageOperation.INSTALL)
    fun update() = perform(ConnectionPackageOperation.UPDATE)

    private fun perform(requested: ConnectionPackageOperation) {
        if (operation != null) return
        operation = requested
        message = null
        error = null
        val previousVersion = currentVersion
        scope.launch {
            try {
                val installedVersion = withContext(Dispatchers.IO) {
                    val packages = ConnectionPluginPackage(context)
                    when (requested) {
                        ConnectionPackageOperation.READ -> packages.current()?.let(::readConnectionPackageVersion)
                        ConnectionPackageOperation.INSTALL ->
                            readConnectionPackageVersion(packages.ensureBundled())
                        ConnectionPackageOperation.UPDATE -> {
                            val publishedVersion = packages.update()
                            val installed = packages.current() ?: error("未读取到已安装包")
                            readConnectionPackageVersion(installed).also {
                                check(it == publishedVersion) { "安装版本与发布包不一致" }
                            }
                        }
                    }
                }
                currentVersion = installedVersion
                readFailed = false
                message = when (requested) {
                    ConnectionPackageOperation.READ -> null
                    ConnectionPackageOperation.INSTALL -> "已安装连接插件包 v$installedVersion。"
                    ConnectionPackageOperation.UPDATE -> if (previousVersion == installedVersion) {
                        "已核对当前发布包，安装版本为 v$installedVersion。"
                    } else {
                        "已安装当前发布包 v$installedVersion。"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (requested == ConnectionPackageOperation.READ) readFailed = true
                val retained = if (requested == ConnectionPackageOperation.UPDATE && previousVersion != null) {
                    " 保留原安装版本 v$previousVersion。"
                } else ""
                error = connectionPackageError(requested, failure) + retained
            } finally {
                hasLoaded = true
                operation = null
            }
        }
    }
}

@Composable
internal fun rememberConnectionPluginUiState(refreshRevision: Int): ConnectionPluginUiState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val state = remember(context, scope) { ConnectionPluginUiState(context, scope) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state, refreshRevision) { state.refresh() }
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

@Composable
internal fun ConnectionPluginSection(state: ConnectionPluginUiState) {
    val colors = MaterialTheme.colorScheme
    FishCard {
        Column(
            Modifier.fillMaxWidth().padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                FishGlyph("link", Modifier.size(28.dp), colors.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("大肥鱼连接插件", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("dafeiyu-go-connect", fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
            }
            Text("APK 内置包 v0.2.0", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Text(
                when {
                    !state.hasLoaded -> "安装状态：正在读取"
                    state.readFailed -> state.currentVersion?.let { "安装状态：读取失败；上次读取为 v$it" }
                        ?: "安装状态：读取失败"
                    state.currentVersion != null -> "已安装包 v${state.currentVersion}"
                    else -> "尚未安装连接插件包"
                },
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
            )
            Text("待运行时接入", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Text(
                "已安装包不代表 DSH 运行时已加载。当前运行时尚未接入，连接、同步和配对功能尚不能由此插件执行。",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = colors.onSurfaceVariant,
            )
            if (state.busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(state.progressLabel, fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
            }
            state.error?.let {
                Text(it, fontSize = 12.sp, lineHeight = 19.sp, color = colors.error)
            }
            state.message?.let {
                Text(it, fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
            }
            if (state.currentVersion == null) {
                FishButton(
                    onClick = state::installBundled,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FishGlyph("archive", Modifier.size(18.dp))
                    Text("安装内置包 v0.2.0", fontSize = 13.sp)
                }
            }
            FishButton(
                onClick = state::update,
                enabled = !state.busy,
                accent = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                FishGlyph("download", Modifier.size(18.dp))
                Text("检查并安装更新", fontSize = 13.sp)
            }
            if (state.readFailed) {
                FishButton(
                    onClick = state::refresh,
                    enabled = !state.busy,
                    accent = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FishGlyph("document", Modifier.size(18.dp))
                    Text("重新读取安装状态", fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(1.dp))
            Text(
                "更新来源：DeveloperCenter。更新失败时保留之前的安装包。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private fun readConnectionPackageVersion(packageDirectory: File): String {
    val descriptor = File(packageDirectory, "package.json")
    require(descriptor.isFile && descriptor.length() in 1..65536L)
    val json = JSONObject(descriptor.readText(Charsets.UTF_8))
    require(json.getString("name") == "dafeiyu-go-connect")
    return json.getString("version").also {
        require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
    }
}

private fun connectionPackageError(operation: ConnectionPackageOperation, error: Exception): String {
    if (operation == ConnectionPackageOperation.READ) return "无法读取已安装包，请重新读取安装状态。"
    return when (error) {
        is UnknownHostException -> "无法连接更新服务，请检查网络后重试。"
        is SocketTimeoutException -> "更新服务响应超时，请稍后重试。"
        is SSLException -> "无法验证更新服务的安全连接，请稍后重试。"
        is FileNotFoundException -> if (operation == ConnectionPackageOperation.INSTALL) {
            "APK 内置包暂不可用，请检查当前应用版本。"
        } else "无法取得发布包，请稍后重试。"
        is SecurityException -> "无法访问插件安装目录，请稍后重试。"
        is IllegalArgumentException, is IllegalStateException -> "插件包校验或安装失败，请稍后重试。"
        is IOException -> if (operation == ConnectionPackageOperation.UPDATE) {
            "无法取得或保存发布包，请检查网络后重试。"
        } else "无法读取或保存内置包，请稍后重试。"
        else -> "插件包操作未完成，请稍后重试。"
    }
}
