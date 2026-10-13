package com.dafeiyu.mobile.glasslab

import android.app.Application
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** Owns the portable document; preview gestures never mutate saved parameters. */
class LabState(application: Application) : AndroidViewModel(application) {
    val store = LabPresetStore(application)
    private val persistence = LabPersistenceQueue.forStore(store)
    private val processSnapshot = persistence.latestSnapshot()
    var document by mutableStateOf(processSnapshot ?: LabDocument.defaults())
        private set
    var isLoading by mutableStateOf(processSnapshot == null)
        private set
    private var history = LabUndoHistory(document)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var pendingSave: Job? = null
    private var dirty = persistence.hasUnpersistedSnapshot()
    private var lastTicket: LabSaveTicket? = null
    private var saveObserver: Job? = null
    private var editRevision = persistence.revision()
    private val loadCompletion = CompletableDeferred<Unit>()

    init {
        if (processSnapshot == null) {
            viewModelScope.launch {
                val loaded = try { withContext(Dispatchers.IO) { store.load() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = "读取失败，原件保留：${error.message}"; null }
                val loadNotice = store.recoveryNotice ?: store.lastError ?: message
                document = persistence.adoptLoaded(loaded ?: LabDocument.defaults(), loadNotice)
                history = LabUndoHistory(document)
                dirty = persistence.hasUnpersistedSnapshot()
                editRevision = persistence.revision()
                isLoading = false
                reflectHistory()
                message = loadNotice
                loadCompletion.complete(Unit)
                persistence.latestTicket()?.let { observe(it) }
            }
        } else {
            message = persistence.notice()
            loadCompletion.complete(Unit)
            persistence.latestTicket()?.let { observe(it) }
        }
    }

    @MainThread
    fun update(next: LabDocument) {
        if (isLoading) { message = "正在读取上次参数，请稍候"; return }
        if (history.update(next)) {
            document = history.current
            dirty = true
            editRevision = persistence.recordEdited(document)
            reflectHistory()
            scheduleSave()
        }
    }

    fun parameters(next: LabParameters) = update(document.withParameters(next))
    fun value(key: String, value: Float) = parameters(document.current().withValue(key, value))
    fun color(key: String, value: String) = parameters(document.current().withColor(key, value))

    fun beginGesture() { if (!isLoading) history.beginGesture() }
    fun endGesture() { if (!isLoading) { history.endGesture(); reflectHistory(); scheduleSave() } }
    fun undo() { if (!isLoading) { history.undo()?.let { document = it; dirty = true; editRevision = persistence.recordEdited(it) }; reflectHistory(); scheduleSave() } }
    fun redo() { if (!isLoading) { history.redo()?.let { document = it; dirty = true; editRevision = persistence.recordEdited(it) }; reflectHistory(); scheduleSave() } }
    fun resetCurrent() = parameters(LabParameters.defaults(document.selectedType, document.selectedTheme == "dark"))
    fun resetAll() = update(LabDocument.defaults())

    fun notice(text: String?) { message = text }

    suspend fun import(text: String) {
        val result = withContext(Dispatchers.Default) { LabDocument.decodeWithWarnings(text) }
        withContext(Dispatchers.Main.immediate) {
            if (isLoading) { message = "正在读取上次参数，请稍候再导入"; return@withContext }
            update(result.document)
            message = if (result.warnings.isEmpty()) "已导入浅色、暗色和四种组件的全部参数"
                else "已导入；兼容提示 ${result.warnings.size} 项：${result.warnings.take(3).joinToString()}"
        }
    }

    /** Enqueues synchronously; its process-owned writer survives Activity/ViewModel destruction. */
    @MainThread
    fun persistNow() {
        pendingSave?.cancel()
        pendingSave = null
        if (isLoading) return
        history.endGesture()
        reflectHistory()
        if (!dirty) return
        observe(persistence.submit(store, document, editRevision))
    }

    /** Test/review completion point, bounded to 15s; normal UI uses nonblocking autosave. */
    suspend fun awaitPersisted() {
        withTimeout(15_000) {
            loadCompletion.await()
            withContext(Dispatchers.Main.immediate) { persistNow() }
            persistence.awaitLatest()
        }
    }

    override fun onCleared() {
        pendingSave?.cancel()
        // Some owners clear without onStop. Queue the same immutable final snapshot directly,
        // not via a scope that AndroidViewModel is in the process of cancelling.
        if (!isLoading && dirty) persistence.submit(store, document, editRevision)
        super.onCleared()
    }

    private fun reflectHistory() { canUndo = history.canUndo; canRedo = history.canRedo }
    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = viewModelScope.launch {
            delay(450)
            pendingSave = null
            // Persist an intermediate position without ending an active slider gesture.
            // Pausing while holding the thumb must still produce a single undo record.
            if (!isLoading && dirty) observe(persistence.submit(store, document, editRevision))
        }
    }

    private fun observe(ticket: LabSaveTicket) {
        saveObserver?.cancel()
        lastTicket = ticket
        saveObserver = viewModelScope.launch {
            val outcome = ticket.completion.await()
            // This coroutine resumes on Main. A late result for an older value must not clear
            // dirtiness or show an obsolete failure over a newer, successfully saved document.
            if (lastTicket !== ticket || document != ticket.document || editRevision != ticket.revision) return@launch
            when (outcome) {
                is LabSaveOutcome.Saved -> { dirty = false; outcome.notice?.let { message = it } }
                is LabSaveOutcome.Failed -> message = "保存失败，调节结果仍在内存中：${outcome.error}"
                LabSaveOutcome.Superseded -> Unit
            }
        }
    }
}

private sealed interface LabSaveOutcome {
    data class Saved(val notice: String?) : LabSaveOutcome
    data class Failed(val error: String) : LabSaveOutcome
    data object Superseded : LabSaveOutcome
}

private data class LabSaveTicket(
    val document: LabDocument,
    val store: LabPresetStore,
    val revision: Long,
    val completion: CompletableDeferred<LabSaveOutcome> = CompletableDeferred(),
)

/** One active write plus one latest pending snapshot per private storage root. */
private class LabPersistenceQueue private constructor() {
    private val monitor = Any()
    private var pending: LabSaveTicket? = null
    private var running = false
    private var latest: LabDocument? = null
    private var committed: LabDocument? = null
    private var ticket: LabSaveTicket? = null
    private var savedNotice: String? = null
    private var latestRevision = 0L

    fun latestSnapshot(): LabDocument? = synchronized(monitor) { latest }
    fun latestTicket(): LabSaveTicket? = synchronized(monitor) { ticket }
    fun revision(): Long = synchronized(monitor) { latestRevision }
    fun notice(): String? = synchronized(monitor) { savedNotice }
    fun hasUnpersistedSnapshot(): Boolean = synchronized(monitor) { latest != null && latest != committed }
    fun recordEdited(document: LabDocument): Long = synchronized(monitor) {
        latest = document
        ++latestRevision
    }
    fun adoptLoaded(loaded: LabDocument, notice: String?): LabDocument = synchronized(monitor) {
        if (latest == null) { latest = loaded.snapshot(); committed = latest; savedNotice = notice }
        latest!!
    }

    fun submit(store: LabPresetStore, document: LabDocument, revision: Long): LabSaveTicket {
        val snapshot = document.snapshot()
        var startWriter = false
        val submitted = synchronized(monitor) {
            if (revision != latestRevision || snapshot != latest) {
                // A previous Activity can finish after its replacement has already edited.
                // Its final lifecycle flush must not overwrite those newer values.
                return@synchronized LabSaveTicket(snapshot, store, revision).also {
                    it.completion.complete(LabSaveOutcome.Superseded)
                }
            }
            // onStop and onCleared can submit the exact same state. Reuse an existing in-flight
            // ticket, while allowing a failed completion to be explicitly retried.
            ticket?.takeIf { it.document == snapshot && it.revision == revision && !it.completion.isCompleted }?.let { return@synchronized it }
            val next = LabSaveTicket(snapshot, store, revision)
            pending?.completion?.complete(LabSaveOutcome.Superseded)
            pending = next
            latest = snapshot
            ticket = next
            if (!running) { running = true; startWriter = true }
            next
        }
        if (startWriter) processScope.launch { drain() }
        return submitted
    }

    suspend fun awaitLatest() {
        while (true) {
            val observedTicket = latestTicket() ?: return
            val result = observedTicket.completion.await()
            if (observedTicket !== latestTicket()) continue
            when (result) {
                is LabSaveOutcome.Failed -> throw IOException(result.error)
                is LabSaveOutcome.Saved -> return
                LabSaveOutcome.Superseded -> Unit
            }
        }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(monitor) {
                pending.also { if (it == null) running = false else pending = null }
            } ?: return
            val outcome = try {
                next.store.save(next.document)
                synchronized(monitor) { committed = next.document; savedNotice = next.store.recoveryNotice }
                LabSaveOutcome.Saved(next.store.recoveryNotice)
            } catch (error: Exception) {
                LabSaveOutcome.Failed(error.message?.take(240) ?: "无法保存参数")
            }
            next.completion.complete(outcome)
        }
    }

    private fun isIdle(): Boolean = synchronized(monitor) { !running && pending == null }

    companion object {
        // This scope has process lifetime, no Activity/ViewModel parent and no periodic work.
        private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val roots = LinkedHashMap<String, LabPersistenceQueue>()
        @Synchronized
        fun forStore(store: LabPresetStore): LabPersistenceQueue {
            roots[store.storageKey]?.let { return it }
            // Production has one application-private root. Bound synthetic context roots too,
            // and never evict a root whose writer is still finishing its durable snapshot.
            if (roots.size >= 4) {
                val removable = roots.entries.firstOrNull { it.value.isIdle() }?.key
                    ?: throw IllegalStateException("已有四个独立保存任务，请等待完成后再读取新存储目录")
                roots.remove(removable)
            }
            return LabPersistenceQueue().also { roots[store.storageKey] = it }
        }
    }
}
