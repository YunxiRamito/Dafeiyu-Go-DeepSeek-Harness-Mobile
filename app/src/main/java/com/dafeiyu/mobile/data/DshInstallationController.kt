package com.dafeiyu.mobile.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DshInstallationState(
    val checking: Boolean = false,
    val installing: Boolean = false,
    val available: DshRuntimeManifest? = null,
    val installedVersion: String? = null,
    val updateAvailable: Boolean = false,
    val progress: DshInstallProgress? = null,
    val error: String? = null,
    val channel: String? = null,
)

/** The signed feed and candidate installer are the only route to a new local runtime. */
class DshInstallationController(context: Context) {
    private val app = context.applicationContext
    private val runtime = LinuxRuntimeManager(app)
    private val installer = DshRuntimeInstaller(app, runtime)
    private val releases = AndroidReleaseClient(app)
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(DshInstallationState())
    val state: StateFlow<DshInstallationState> = mutableState.asStateFlow()

    suspend fun check(channel: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = installer.current()
            mutableState.value = mutableState.value.copy(checking = true, available = null,
                installedVersion = current?.manifest?.dshVersion, updateAvailable = false,
                progress = null, error = null, channel = channel)
            try {
                val abi = Build.SUPPORTED_ABIS.firstOrNull()
                    ?.takeIf { it in setOf("arm64-v8a", "x86_64") }
                    ?: error("当前主架构没有已审核的 DSH 运行包")
                val manifest = releases.getDshCurrent(abi, channel).getOrThrow()
                mutableState.value = mutableState.value.copy(available = manifest,
                    updateAvailable = current?.manifest?.digest() != manifest.digest())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(error = failure.message ?: "DSH 版本检查失败")
            } finally {
                mutableState.value = mutableState.value.copy(checking = false)
            }
        }
    }

    suspend fun install(source: String): DshRuntimeSlot = withContext(Dispatchers.IO) {
        lock.withLock {
            val manifest = mutableState.value.available ?: error("请先检查并验证 DSH 版本")
            mutableState.value = mutableState.value.copy(installing = true, error = null, progress = null)
            try {
                runtime.refresh()
                check(runtime.snapshot.value.any { it.id == "ubuntu" && it.status == LinuxComponentStatus.READY } &&
                    runtime.snapshot.value.any { it.id == "node" && it.status == LinuxComponentStatus.READY }) {
                    "请先在组件页面安装 Ubuntu 和 Node.js"
                }
                val slot = installer.install(manifest, SettingsContract.downloadSource(source)) { progress ->
                    mutableState.value = mutableState.value.copy(progress = progress)
                }
                mutableState.value = mutableState.value.copy(installedVersion = slot.manifest.dshVersion,
                    updateAvailable = false, progress = DshInstallProgress("安装与网页验证完成"))
                slot
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(progress = DshInstallProgress("安装已取消，保留原运行包"))
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(error = failure.message ?: "DSH 安装失败")
                throw failure
            } finally {
                mutableState.value = mutableState.value.copy(installing = false)
            }
        }
    }
}
