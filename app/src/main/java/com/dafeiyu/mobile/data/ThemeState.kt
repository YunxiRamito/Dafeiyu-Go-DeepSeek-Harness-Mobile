package com.dafeiyu.mobile.data

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dafeiyu.mobile.glasslab.LabDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One application-owned selection for native screens and the display-only DSH client context. */
class ThemeState(application: Application) : AndroidViewModel(application) {
    private val store = ThemeStore(application)
    private val marketClient = ThemeMarketClient(application)
    private val operations = Mutex()
    internal var selection by mutableStateOf(ThemeSelection())
        private set
    internal var marketPage by mutableStateOf<ThemeMarketPage?>(null)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var importCancellable by mutableStateOf(false)
        private set
    var marketLoading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var requestedPage by mutableStateOf(1)
        private set
    var openRequest by mutableStateOf(0L)
        private set
    internal var downloadProgress by mutableStateOf<ArtifactDownloadProgress?>(null)
        private set
    internal val marketAvailable: Boolean get() = marketClient.available
    val document: LabDocument? get() = selection.active()?.document
    var webMaterialTheme by mutableStateOf<String?>(null)
        private set
    val activeTitle: String get() = selection.active()?.title ?: "默认"
    private var importJob: Job? = null
    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            operations.withLock {
                try {
                    val loaded = withContext(Dispatchers.IO) { store.load() to store.loadMarket() }
                    publishSelection(loaded.first); marketPage = loaded.second
                    requestedPage = loaded.second?.page ?: 1
                    message = store.recoveryNotice
                } catch (failure: Exception) {
                    message = "主题读取失败，原文件保留；你仍可使用默认主题。"
                } finally { isLoading = false }
            }
        }
    }

    /** Only user-selected content is opened, bounded and validated; the external URI is never stored. */
    fun importTheme(uri: Uri?) {
        if (uri == null) { message = "已取消导入，当前主题保留。"; return }
        openRequest++
        if (!busy || importCancellable) importJob?.cancel()
        importJob = viewModelScope.launch {
            operations.withLock {
                busy = true; importCancellable = true
                message = "正在读取并校验主题…"
                try {
                    require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.userInfo == null) {
                        "请选择系统文件入口或 Dafeiyu调色盘分享的主题文件"
                    }
                    val bytes = withContext(Dispatchers.IO) {
                        getApplication<Application>().contentResolver.openInputStream(uri)?.use { ThemeDocuments.read(it) }
                            ?: error("主题文件无法读取")
                    }
                    val parsed = withContext(Dispatchers.Default) { ThemeDocuments.decode(bytes) }
                    currentCoroutineContext().ensureActive()
                    val candidate = selection.copy(activeId = ThemeDocuments.CUSTOM_ID,
                        custom = InstalledTheme(ThemeDocuments.CUSTOM_ID, "自定义", 0, ThemeDocuments.sha256(bytes), parsed))
                    importCancellable = false
                    // Once commit begins, finish it and publish the same selection together.
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { store.save(candidate) }
                        publishSelection(candidate)
                        message = "自定义主题已保存并应用；浅色与暗色的四种材质均已导入。"
                    }
                } catch (cancelled: CancellationException) {
                    message = "已取消导入，当前主题保留。"
                    throw cancelled
                } catch (failure: Exception) {
                    message = "主题导入失败，当前配置保留：${failure.message?.take(180) ?: "文件不可读取"}"
                } finally { busy = false; importCancellable = false }
            }
        }
    }

    fun cancelImport() { if (importCancellable) importJob?.cancel() }

    internal fun select(id: String) {
        if (busy || isLoading) return
        viewModelScope.launch {
            operations.withLock {
                busy = true
                try {
                    val next = selection.copy(activeId = id)
                    require(id == ThemeDocuments.DEFAULT_ID || next.active() != null) { "请先下载这个主题" }
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { store.save(next) }
                        publishSelection(next)
                        message = if (id == ThemeDocuments.DEFAULT_ID) "已恢复默认主题；自定义和已下载的主题仍然保留。"
                            else "已使用 ${next.active()!!.title}"
                    }
                } catch (failure: Exception) { message = "无法切换主题：${failure.message?.take(180)}" }
                finally { busy = false }
            }
        }
    }

    internal fun refreshMarket(page: Int = requestedPage) {
        if (marketLoading) return
        viewModelScope.launch {
            marketLoading = true
            try {
                val loaded = marketClient.page(page)
                withContext(Dispatchers.IO) { store.saveMarket(loaded) }
                marketPage = loaded; requestedPage = loaded.page
                message = if (loaded.items.isEmpty()) "主题市场暂无主题。" else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message?.take(200) ?: "主题市场读取失败；已安装主题仍可使用。" }
            finally { marketLoading = false }
        }
    }

    internal fun download(entry: ThemeMarketEntry) {
        if (busy || isLoading) return
        downloadJob = viewModelScope.launch {
            operations.withLock {
                busy = true; downloadProgress = null
                try {
                    require(selection.installed.any { it.id == entry.id } || selection.installed.size < ThemeStore.MAX_INSTALLED) {
                        "已安装主题达到上限"
                    }
                    val installed = marketClient.download(entry) { progress ->
                        viewModelScope.launch { downloadProgress = progress }
                    }
                    currentCoroutineContext().ensureActive()
                    val next = selection.copy(installed = selection.installed.filterNot { it.id == installed.id } + installed)
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { store.save(next) }
                        publishSelection(next)
                        message = "主题已下载并通过摘要校验；点“使用”立即应用。"
                    }
                } catch (cancelled: CancellationException) { message = "已取消下载，当前主题保留。"; throw cancelled }
                catch (failure: Exception) { message = "主题下载失败：${failure.message?.take(180)}" }
                finally { busy = false; downloadProgress = null; downloadJob = null }
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }
    fun clearMessage() { message = null }
    fun report(text: String) { message = text.take(250) }
    fun requestOpenWithMessage(text: String) { report(text); openRequest++ }

    private fun publishSelection(next: ThemeSelection) {
        if (document != next.active()?.document) {
            webMaterialTheme = next.active()?.document?.let { ThemeDocuments.materialContext(it).toString() }
        }
        selection = next
    }
}
