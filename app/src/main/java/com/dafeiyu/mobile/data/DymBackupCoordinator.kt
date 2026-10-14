package com.dafeiyu.mobile.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DymBackupSelection(
    val conversations: Boolean = true,
    val providers: Boolean = true,
    val resources: Boolean = true,
    val preferences: Boolean = true,
)

data class PreparedDymBackup(
    val archive: DymImportResult?,
    val conversations: AndroidBackupStore.ValidatedBackup?,
    val preferences: AndroidDymPreferencePlan?,
    val warnings: List<String>,
    val legacy: Boolean,
    val convertedPcSessions: Int = 0,
) {
    val resourceCount: Int get() = archive?.groupFiles?.values?.sumOf { it.size } ?: 0
    val containsCredentials: Boolean get() = archive?.providerConfiguration != null || archive?.sharedCredentials != null
    val canImport: Boolean get() = conversations != null || containsCredentials || resourceCount > 0 ||
        preferences?.groups?.isNotEmpty() == true || archive?.pcPreferences != null
}

data class DymRestoreReport(val descriptions: List<String>, val warnings: List<String>)
data class DymExportReport(val bytes: Long, val warnings: List<String>)

/** SAF only supplies the chosen stream; archive decoding and retention stay inside app storage. */
class DymBackupCoordinator(
    context: Context,
    private val runtime: LinuxRuntimeManager = LinuxRuntimeManager(context),
    private val installer: DshRuntimeInstaller = DshRuntimeInstaller(context, runtime),
    private val persistence: DshSharedPersistenceBridge = DshSharedPersistenceBridge(context, runtime, installer),
) {
    private val app = context.applicationContext
    private val root = app.filesDir
    private val nativeStore = AndroidBackupStore()
    private val archiveStore = DymArchiveStore(root)
    private val retainedStore = DymRetainedResourceStore(root)
    private val resourceStore = DymRuntimeResourceStore(root)
    private val historyConverter = DymHistoryConverter(nativeStore)
    private val nativeHistory = DymNativeHistoryStore(root)

    suspend fun export(uri: Uri, repository: DemoRepository, selection: DymBackupSelection): Long =
        exportReport(uri, repository, selection).bytes

    suspend fun exportReport(uri: Uri, repository: DemoRepository, selection: DymBackupSelection): DymExportReport {
        require(selection.conversations || selection.providers || selection.resources || selection.preferences)
        val warnings = mutableListOf<String>()
        // Capture visible repository state on Main before the IO codec begins.
        val backup = if (selection.conversations) nativeStore.export(nativeStore.capture(repository, app)) else null
        val preferences = if (selection.preferences) AndroidDymPreferences.capture(repository) else null
        val configuration = if (selection.providers) repository.exportDymProviderConfiguration() else null
        val retained = retainedStore.read()
        // Even without DSH, normalize retained credentials to the current
        // committed native value/tombstone. Never export a stale retained Key
        // as authority over an explicit native deletion.
        var sharedCredentials = configuration?.let { DymSharedCredentials.captureWithoutDsh(it, retained.sharedCredentials) }
        if (selection.providers && configuration != null) {
            try { sharedCredentials = repository.captureDymOfficialCredentials(configuration, retained.sharedCredentials) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warnings += "DSH 当前选择器未完成读取；已使用当前手机凭据或明确缺失，未恢复旧官方 Key" }
        }
        val directories = retained.directories.filterKeys { prefix ->
            when {
                prefix.contains("sessions") || prefix.contains("storages") -> selection.conversations
                prefix == DymNativeHistoryStore.PREFIX -> selection.conversations
                prefix == ".dsh/profiles" || prefix == "profiles" -> selection.providers
                else -> selection.resources
            }
        }.toMutableMap()
        val installed = resourceStore.installedDirectories(selection.providers)
        installed.filterKeys { prefix -> when {
            prefix.contains("sessions") || prefix.contains("storages") -> selection.conversations
            prefix == ".dsh/profiles" -> selection.providers
            else -> selection.resources
        } }.forEach { (prefix, folder) ->
            if (prefix in setOf(".dsh/skills", ".dsh/plugins", ".dsh/profiles")) directories.remove(prefix.removePrefix(".dsh/"))
            directories[prefix] = folder
        }
        val file = operationFile("export.dym")
        val portable = if (selection.resources) operationFile("portable-plugins") else null
        val nativeProtocol = if (selection.conversations) operationFile("native-history") else null
        try {
            if (portable != null) {
                val snapshot = resourceStore.portablePlugins(portable)
                if (snapshot.listFiles().orEmpty().isNotEmpty()) directories[".dsh/plugin-profiles"] = snapshot
            }
            if (nativeProtocol != null) {
                val snapshot = nativeHistory.snapshot(nativeProtocol, repository.conversations.map { it.id }.toSet())
                warnings += snapshot.warnings
                directories.remove(DymNativeHistoryStore.PREFIX)
                if (snapshot.files > 0) directories[DymNativeHistoryStore.PREFIX] = nativeProtocol
            }
            archiveStore.export(file, DymExportInput(directories, backup, preferences,
                if (selection.preferences) retained.pcPreferences else null, configuration, sharedCredentials))
            return withContext(Dispatchers.IO) {
                val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开备份目标")
                output.use { sink -> file.inputStream().use { source -> copyBounded(source, sink, MAX_ARCHIVE_BYTES) } }
                DymExportReport(file.length(), warnings.distinct())
            }
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            file.delete()
            portable?.let(::discardOperationDirectory)
            nativeProtocol?.let(::discardOperationDirectory)
        } }
    }

    suspend fun retainedSnapshots(): List<DymRetainedSnapshot> = retainedStore.snapshots()

    /** Export the exact retained resource version, without installing or executing it. */
    suspend fun exportRetained(uri: Uri, snapshotId: String): Long {
        val snapshot = retainedStore.readSnapshot(snapshotId)
        val file = operationFile("retained.dym")
        try {
            archiveStore.export(file, DymExportInput(directories = snapshot.directories,
                pcPreferences = snapshot.pcPreferences, sharedCredentials = snapshot.sharedCredentials))
            return withContext(Dispatchers.IO) {
                val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开原始资源导出目标")
                output.use { sink -> file.inputStream().use { source -> copyBounded(source, sink, MAX_ARCHIVE_BYTES) } }
                file.length()
            }
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { file.delete() } }
    }

    suspend fun prepare(uri: Uri): PreparedDymBackup {
        val file = operationFile("import.dym")
        var staged: DymImportResult? = null
        try {
            withContext(Dispatchers.IO) {
                val input = app.contentResolver.openInputStream(uri) ?: error("无法读取备份文件")
                input.use { source -> file.outputStream().use { sink -> copyBounded(source, sink, MAX_ARCHIVE_BYTES) } }
            }
            val sevenZip = withContext(Dispatchers.IO) {
                file.inputStream().use { input ->
                    val signature = ByteArray(6)
                    input.read(signature) == signature.size && signature.contentEquals(SEVEN_ZIP_MAGIC)
                }
            }
            if (!sevenZip) {
                val backup = withContext(Dispatchers.IO) { file.inputStream().use(nativeStore::import) }
                return PreparedDymBackup(null, backup, null, emptyList(), legacy = true)
            }
            staged = archiveStore.stageImport(file, { nativeStore.import(it) })
            val plan = staged.androidPreferences?.let(AndroidDymPreferences::parse)
            val native = staged.androidBackup?.let(nativeStore::import)
            val firstPartyAvailable = withContext(Dispatchers.IO) { installer.current() != null }
            val reader: (suspend (DymStagedFile) -> org.json.JSONObject)? = if (firstPartyAvailable)
                { resource -> persistence.readPortableArtifact(resource.file) } else null
            val converted = historyConverter.convert(staged, native, reader)
            val warnings = staged.warnings + plan?.warnings.orEmpty() + converted.warnings
            return PreparedDymBackup(staged, converted.conversations, plan, warnings.distinct(), legacy = false,
                convertedPcSessions = converted.convertedSessions)
        } catch (error: Exception) {
            staged?.let { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { archiveStore.discard(it) } }
            throw error
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { file.delete() } }
    }

    /** Per-group outcomes are explicit; copying resources never resumes or dispatches old work. */
    suspend fun restore(prepared: PreparedDymBackup, repository: DemoRepository): DymRestoreReport {
        require(prepared.canImport)
        check(repository.connectionMode == "local") { "请先切换到本地模式再恢复本机备份" }
        check(!repository.isResponding && !repository.sendPending && !repository.deepSeekConfigurationBusy &&
            repository.backgroundTasks?.tasks?.value.orEmpty().none { task ->
                task.status in setOf(BackgroundTaskStatus.QUEUED, BackgroundTaskStatus.RUNNING, BackgroundTaskStatus.STOPPING)
            }) { "请先停止当前任务，再恢复本机备份" }
        repository.suppressDshAutostartAfterImport()
        val warnings = prepared.warnings.toMutableList()
        val descriptions = mutableListOf<String>()
        prepared.conversations?.let { backup ->
            val actual = if (prepared.legacy) backup else backup.copy(settings = nativeStore.capture(repository, app).settings)
            currentCoroutineContext().ensureActive()
            repository.restoreBackup(actual).getOrThrow()
            descriptions += "${backup.workspaces.size} 个工作区、${backup.conversations.size} 个可兼容会话"
            if (prepared.convertedPcSessions > 0) descriptions += "已转换 ${prepared.convertedPcSessions} 个 PC DSH 会话"
        }
        try {
            val portableConfiguration = prepared.archive?.providerConfiguration ?: prepared.archive?.sharedCredentials?.let {
                DymSharedCredentials.nativeConfiguration(it, repository.exportDymProviderConfiguration())
            }
            currentCoroutineContext().ensureActive()
            portableConfiguration?.let { configuration ->
                repository.restoreDymProviderConfiguration(configuration)
                    .onSuccess { descriptions += "提供商配置与凭据" }
                    .onFailure { warnings += "提供商配置导入失败，其他有效内容已继续导入" }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            warnings += "提供商配置或官方凭据尚未确认，其他有效内容已继续导入"
        }
        prepared.preferences?.let { plan ->
            currentCoroutineContext().ensureActive()
            // Engine changes can launch services; preferences are stored only after all data groups finish.
            val failures = AndroidDymPreferences.apply(repository,
                plan.copy(groups = plan.groups.filterKeys { it != "engine" }))
            warnings += failures
            if (plan.groups.isNotEmpty()) descriptions += "手机偏好（${plan.groups.size - failures.size} 组）"
        }
        prepared.archive?.let { archive ->
            try {
                currentCoroutineContext().ensureActive()
                val files = retainedStore.retain(archive)
                if (files > 0) descriptions += "已独立保留 $files 个原始 PC DSH 文件"
                if (archive.pcPreferences != null) descriptions += "PC 偏好已独立保留"
                val target = File(root, "dsh-home")
                val restored = runtime.withIdleDataMutation(listOf(target)) {
                    resourceStore.restore(archive) {
                        check(!runtime.hasProcessLease(target)) { "DSH 数据目录仍有进程占用" }
                    }
                }
                if (restored.restoredFiles > 0) descriptions += "已恢复 ${restored.restoredFiles} 个技能、插件与配置文件（未执行）"
                if (restored.preservedFiles > 0) descriptions += "已保留 ${restored.preservedFiles} 个已有资源文件"
                warnings += restored.warnings
                archive.sharedCredentials?.let { credentials ->
                    if (installer.current() == null) warnings += "DSH 凭据已保留；安装匹配运行组件后重新导入以恢复到 DSH"
                    else try {
                        repository.withDymCredentialMutation {
                        val before = persistence.readCredentialDocument()
                        // Official refs are excluded by updates(). Their
                        // value/tombstone was committed to the native vaults
                        // above and is published through a later fresh-ref CAS.
                        persistence.writeCredentialDocument(DymSharedCredentials.updates(credentials, before.getString("revision"), archive.providerConfiguration),
                            exclusiveRuntimeUse = true)
                        }
                        descriptions += "其他 DSH 私有凭据（实际平台存储）；官方 Key 等待安全同步"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { warnings += "DSH 凭据恢复未完成，原始凭据已保留，其他有效内容已继续导入" }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warnings += "PC DSH 资源恢复未完成，其他有效内容已继续导入；请停止服务和模型运行器后重试" }
            val protocol = nativeHistory.restore(archive, prepared.conversations)
            if (protocol.files > 0) descriptions += "已独立恢复 ${protocol.files} 条原始模型协议历史"
            warnings += protocol.warnings
        }
        prepared.preferences?.groups?.get("engine")?.let { engine ->
            currentCoroutineContext().ensureActive()
            warnings += AndroidDymPreferences.apply(repository,
                AndroidDymPreferencePlan(mapOf("engine" to engine), emptyList()))
        }
        repository.suppressDshAutostartAfterImport()
        return DymRestoreReport(descriptions, warnings.distinct())
    }

    suspend fun discard(prepared: PreparedDymBackup) = withContext(Dispatchers.IO) {
        prepared.archive?.let(archiveStore::discard)
    }

    private fun operationFile(name: String): File {
        val directory = File(root, "dym-operations")
        require(!Files.isSymbolicLink(directory.toPath()))
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "${UUID.randomUUID()}-$name")
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        return file
    }

    private fun discardOperationDirectory(folder: File) {
        val expected = File(root, "dym-operations").canonicalFile
        require(folder.canonicalFile.parentFile == expected && !Files.isSymbolicLink(folder.toPath()))
        if (folder.exists()) Files.walk(folder.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private suspend fun copyBounded(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= limit) { "备份文件超过 1 GiB 上限" }
            output.write(buffer, 0, count)
        }
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024
        val SEVEN_ZIP_MAGIC = byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)
    }
}
