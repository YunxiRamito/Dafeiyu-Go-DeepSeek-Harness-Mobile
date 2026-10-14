package com.dafeiyu.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

enum class OfficialCredentialUiPhase {
    CHECKING, WORKING, READY, NATIVE_RECOVERY_REQUIRED, DSH_RECOVERY_REQUIRED,
    DSH_PUBLICATION_PENDING, DSH_UNAVAILABLE, NATIVE_VAULT_CONFLICT, NATIVE_PLAN_CONFLICT, DSH_KEY_CONFLICT, FAILED,
}

/** Register before the first instruction; retain a cancelled caller until its real cleanup completes.
 * Completion may run off Main, so callers must be a concurrent set. */
internal fun CoroutineScope.launchOfficialCredentialCaller(
    callers: MutableSet<Job>,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val caller = launch(start = CoroutineStart.LAZY, block = block)
    callers.add(caller)
    caller.invokeOnCompletion { callers.remove(caller) }
    caller.start()
    return caller
}

/** Actual caller jobs must finish cleanup before any runtime or credential mutation lock is acquired. */
internal suspend fun awaitOfficialCredentialCallers(
    response: Job?,
    callers: List<Job>,
    stopBackground: suspend () -> Unit,
    awaitBackground: suspend () -> Unit,
    timeoutMillis: Long = 30_000L,
) {
    try {
        withTimeout(timeoutMillis) {
            response?.cancel()
            stopBackground()
            callers.forEach { it.cancel() }
            response?.join()
            callers.forEach { it.join() }
            awaitBackground()
        }
    } catch (_: TimeoutCancellationException) { error("TASKS_NOT_STOPPED")
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { error("TASKS_NOT_STOPPED") }
}

enum class OfficialCredentialUiOperation { INITIALIZE, SAVE, REMOVE, RECOVER, RESTORE, START_DSH, KEEP_PHONE, KEEP_DSH, RESOLVE_NATIVE }
enum class OfficialCredentialNativeChoice { PROVIDER, BALANCE, CLEAR }

enum class OfficialCredentialUiProblem {
    NONE, CANCELLED, NATIVE_CONFLICT, DSH_CONFLICT, DSH_UNAVAILABLE, STORE_CHANGED,
    INVALID_JOURNAL, TASKS_NOT_STOPPED, UNCONFIRMED,
}

/** Public state contains enums only. Messages are fixed and never include exceptions or private snapshots. */
data class OfficialCredentialUiState(
    val phase: OfficialCredentialUiPhase = OfficialCredentialUiPhase.CHECKING,
    val operation: OfficialCredentialUiOperation = OfficialCredentialUiOperation.INITIALIZE,
    val problem: OfficialCredentialUiProblem = OfficialCredentialUiProblem.NONE,
) {
    val busy: Boolean get() = phase in setOf(OfficialCredentialUiPhase.CHECKING, OfficialCredentialUiPhase.WORKING)
    val nativeReady: Boolean get() = phase in setOf(OfficialCredentialUiPhase.READY,
        OfficialCredentialUiPhase.DSH_PUBLICATION_PENDING, OfficialCredentialUiPhase.DSH_KEY_CONFLICT, OfficialCredentialUiPhase.DSH_UNAVAILABLE)
    val dshReady: Boolean get() = phase == OfficialCredentialUiPhase.READY
    val retryAllowed: Boolean get() = !busy && phase != OfficialCredentialUiPhase.READY
    val phoneChoiceAllowed: Boolean get() = phase == OfficialCredentialUiPhase.DSH_KEY_CONFLICT
    val dshChoiceAllowed: Boolean get() = phase == OfficialCredentialUiPhase.DSH_KEY_CONFLICT
    val nativeChoiceAllowed: Boolean get() = phase == OfficialCredentialUiPhase.NATIVE_VAULT_CONFLICT
    val title: String get() = when (phase) {
        OfficialCredentialUiPhase.CHECKING -> "正在检查官方凭据"
        OfficialCredentialUiPhase.WORKING -> when (operation) {
            OfficialCredentialUiOperation.SAVE -> "正在安全保存"
            OfficialCredentialUiOperation.REMOVE -> "正在安全清除"
            OfficialCredentialUiOperation.RESTORE -> "正在恢复备份凭据"
            OfficialCredentialUiOperation.START_DSH -> "正在确认 DSH 凭据"
            else -> "正在恢复官方凭据"
        }
        OfficialCredentialUiPhase.READY -> "官方凭据已确认"
        OfficialCredentialUiPhase.NATIVE_RECOVERY_REQUIRED -> "手机凭据保存待恢复"
        OfficialCredentialUiPhase.DSH_RECOVERY_REQUIRED -> "DSH 凭据事务待恢复"
        OfficialCredentialUiPhase.DSH_PUBLICATION_PENDING -> "手机凭据已确认，DSH 待同步"
        OfficialCredentialUiPhase.DSH_UNAVAILABLE -> "手机凭据已确认，DSH 暂时不可用"
        OfficialCredentialUiPhase.NATIVE_VAULT_CONFLICT -> "手机的两个安全存储不一致"
        OfficialCredentialUiPhase.NATIVE_PLAN_CONFLICT -> "已保存的凭据意图与手机存储不一致"
        OfficialCredentialUiPhase.DSH_KEY_CONFLICT -> "手机与 DSH 的凭据不同"
        OfficialCredentialUiPhase.FAILED -> "官方凭据尚未确认"
    }
    val message: String get() = when {
        problem == OfficialCredentialUiProblem.TASKS_NOT_STOPPED -> "任务或运行进程尚未确认停止，未开始新的凭据写入；请停止任务后重试。"
        phase == OfficialCredentialUiPhase.NATIVE_VAULT_CONFLICT ->
            "没有自动选择任一存储。执行与新的凭据修改已暂停；请明确选择模型配置、官方请求存储或清除两边，原值不会展示。"
        phase == OfficialCredentialUiPhase.NATIVE_PLAN_CONFLICT ->
            "已有保存意图尚待 DSH 同步，但手机安全存储随后发生变化。不会用新的来源选择解除旧意图；执行已暂停，请重新检查原事务。"
        phase == OfficialCredentialUiPhase.DSH_KEY_CONFLICT ->
            "两个来源都已保留。请选择保留手机或 DSH；确认后会停止真实任务，并通过可恢复事务同步，后台不会代替你选择。"
        phase == OfficialCredentialUiPhase.NATIVE_RECOVERY_REQUIRED ->
            "已持久化的保存或删除尚未完成确认。可重试原事务；相关执行与新的凭据修改已暂停。"
        phase == OfficialCredentialUiPhase.DSH_RECOVERY_REQUIRED ->
            "原事务需要本机 DSH 的实际确认。重试可能启动恢复服务；不会提交模型任务。"
        phase == OfficialCredentialUiPhase.DSH_PUBLICATION_PENDING ->
            "官方轻引擎可使用已确认的手机凭据。进入本机 DSH 前需要完成同步；后台不会覆盖不同的 DSH 凭据。"
        problem == OfficialCredentialUiProblem.CANCELLED -> "操作已取消；如有未完成持久事务，可在此重新检查并恢复。"
        problem == OfficialCredentialUiProblem.DSH_UNAVAILABLE -> "本机 DSH 暂时不可用；已保存内容保留，请检查运行组件后重试。"
        phase == OfficialCredentialUiPhase.FAILED -> "无法确认安全存储或同步结果，原内容保留；请重新检查。"
        else -> "不会显示、复制或上传凭据值。"
    }
}

internal enum class OfficialCredentialJournalStatus { CLEAR, NATIVE_PENDING, DSH_PENDING, DEFERRED }
internal enum class OfficialCredentialNativePairStatus { MATCHED, CONFLICT }
internal data class OfficialCredentialUiObservation(
    val journal: OfficialCredentialJournalStatus,
    val nativePair: OfficialCredentialNativePairStatus,
)

/** Snapshot proofs are private to the repository and never part of its public presentation state. */
internal class OfficialCredentialNativeChoiceProof(val providerRevision: String, val balanceRevision: String) {
    fun requireCurrent(provider: OfficialNativeCredentialSnapshot, balance: OfficialNativeCredentialSnapshot) {
        check(provider.revision == providerRevision && balance.revision == balanceRevision && provider.key != balance.key) { "NATIVE_CAS_CONFLICT" }
    }
    override fun toString(): String = "OfficialCredentialNativeChoiceProof(<private>)"
}

internal fun sameOfficialNativeSnapshot(first: OfficialNativeCredentialSnapshot, second: OfficialNativeCredentialSnapshot): Boolean =
    first.revision == second.revision && first.transactionId == second.transactionId && first.key == second.key &&
        first.configurationDigest == second.configurationDigest

/** This controller tracks results; the frozen coordinator remains the sole transaction implementation. */
internal class OfficialCredentialRecoveryUiController(
    private val observe: suspend () -> OfficialCredentialUiObservation,
) {
    private val operationGate = Mutex()
    private val mutableState = MutableStateFlow(OfficialCredentialUiState())
    val state: StateFlow<OfficialCredentialUiState> = mutableState.asStateFlow()

    suspend fun refresh(operation: OfficialCredentialUiOperation = OfficialCredentialUiOperation.INITIALIZE): OfficialCredentialUiState =
        operationGate.withLock { refreshLocked(operation, OfficialCredentialUiProblem.NONE) }

    suspend fun perform(operation: OfficialCredentialUiOperation, action: suspend () -> OfficialCredentialOutcome): OfficialCredentialOutcome =
        operationGate.withLock {
            mutableState.value = OfficialCredentialUiState(OfficialCredentialUiPhase.WORKING, operation)
            try {
                val outcome = action()
                val problem = (outcome as? OfficialCredentialOutcome.Pending)?.code?.let(::safeProblem) ?: OfficialCredentialUiProblem.NONE
                refreshLocked(operation, problem)
                outcome
            } catch (cancelled: CancellationException) {
                // Do not hide cancellation or wrap observation/network/lease waits in NonCancellable.
                mutableState.value = OfficialCredentialUiState(OfficialCredentialUiPhase.FAILED, operation, OfficialCredentialUiProblem.CANCELLED)
                throw cancelled
            } catch (failure: Exception) {
                refreshLocked(operation, safeProblem(failure.message))
                throw failure
            }
        }

    suspend fun recordFailure(operation: OfficialCredentialUiOperation, code: String?) = operationGate.withLock {
        val mapped = safeProblem(code)
        val problem = if (mapped == OfficialCredentialUiProblem.UNCONFIRMED && mutableState.value.problem != OfficialCredentialUiProblem.NONE)
            mutableState.value.problem else mapped
        refreshLocked(operation, problem)
    }

    private suspend fun refreshLocked(operation: OfficialCredentialUiOperation, problem: OfficialCredentialUiProblem): OfficialCredentialUiState {
        val next = try {
            val observation = observe()
            val phase = when {
                observation.journal == OfficialCredentialJournalStatus.NATIVE_PENDING -> OfficialCredentialUiPhase.NATIVE_RECOVERY_REQUIRED
                observation.journal == OfficialCredentialJournalStatus.DSH_PENDING -> OfficialCredentialUiPhase.DSH_RECOVERY_REQUIRED
                observation.nativePair == OfficialCredentialNativePairStatus.CONFLICT && observation.journal == OfficialCredentialJournalStatus.DEFERRED ->
                    OfficialCredentialUiPhase.NATIVE_PLAN_CONFLICT
                observation.nativePair == OfficialCredentialNativePairStatus.CONFLICT -> OfficialCredentialUiPhase.NATIVE_VAULT_CONFLICT
                problem == OfficialCredentialUiProblem.DSH_CONFLICT -> OfficialCredentialUiPhase.DSH_KEY_CONFLICT
                observation.journal == OfficialCredentialJournalStatus.DEFERRED -> OfficialCredentialUiPhase.DSH_PUBLICATION_PENDING
                problem == OfficialCredentialUiProblem.DSH_UNAVAILABLE -> OfficialCredentialUiPhase.DSH_UNAVAILABLE
                problem != OfficialCredentialUiProblem.NONE -> OfficialCredentialUiPhase.FAILED
                else -> OfficialCredentialUiPhase.READY
            }
            OfficialCredentialUiState(phase, operation, problem)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            OfficialCredentialUiState(OfficialCredentialUiPhase.FAILED, operation, safeProblem(failure.message))
        }
        mutableState.value = next
        return next
    }

    private fun safeProblem(code: String?): OfficialCredentialUiProblem = when (code) {
        "NATIVE_VAULT_CONFLICT" -> OfficialCredentialUiProblem.NATIVE_CONFLICT
        "OFFICIAL_KEY_CONFLICT" -> OfficialCredentialUiProblem.DSH_CONFLICT
        "DSH_UNAVAILABLE" -> OfficialCredentialUiProblem.DSH_UNAVAILABLE
        "NATIVE_CHANGED", "DSH_CHANGED", "SOURCE_CHANGED", "NATIVE_CAS_CONFLICT", "SETTINGS_CAS_CONFLICT",
        "CREDENTIAL_CAS_CONFLICT", "CANDIDATE_CHANGED" -> OfficialCredentialUiProblem.STORE_CHANGED
        "INVALID_JOURNAL" -> OfficialCredentialUiProblem.INVALID_JOURNAL
        "TASKS_NOT_STOPPED" -> OfficialCredentialUiProblem.TASKS_NOT_STOPPED
        else -> OfficialCredentialUiProblem.UNCONFIRMED
    }
}
