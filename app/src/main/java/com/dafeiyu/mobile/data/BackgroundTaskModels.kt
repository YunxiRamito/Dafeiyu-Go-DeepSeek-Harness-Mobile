package com.dafeiyu.mobile.data

enum class BackgroundTaskStatus {
    DRAFT, QUEUED, RUNNING, STOPPING, SUCCEEDED, FAILED, STOPPED, INTERRUPTED,
}

enum class BackgroundTaskKind { BACKGROUND, SUBAGENT }

data class BackgroundTask(
    val id: String,
    val title: String,
    val model: String,
    val prompt: String,
    val status: BackgroundTaskStatus,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val result: String = "",
    val progress: String = "",
    val errorCode: String? = null,
    val kind: BackgroundTaskKind = BackgroundTaskKind.BACKGROUND,
    val parentConversationId: String? = null,
)

data class BackgroundTaskRequest(
    val id: String,
    val model: String,
    val prompt: String,
    val kind: BackgroundTaskKind = BackgroundTaskKind.BACKGROUND,
    val parentConversationId: String? = null,
    // Delegated runners must not advertise or execute task-creation tools.
    val allowTaskCreation: Boolean = false,
)
data class BackgroundTaskResult(val text: String)
data class BackgroundTaskAvailability(val runnable: Boolean, val reason: String = "")
data class BackgroundTaskModel(val id: String, val name: String)

fun interface BackgroundTaskRunner {
    suspend fun run(request: BackgroundTaskRequest, emit: suspend (String) -> Unit): BackgroundTaskResult
}

interface BackgroundTaskStore {
    suspend fun load(): List<BackgroundTask>
    suspend fun save(tasks: List<BackgroundTask>)
}

internal object BackgroundTaskLimits {
    const val MAX_TASKS = 50
    const val MAX_CONCURRENT = 2
    const val MAX_INPUT = 32_768
    const val MAX_RESULT = 65_536
    const val MAX_PROGRESS = 4096
    const val MAX_STORE_BYTES = 24 * 1024 * 1024
    private val credentials = Regex(
        """(?i)(?:\bsk-[a-z0-9_-]{12,}|\bBearer\s+[a-z0-9._~+/-]{8,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:api[_-]?key|access[_-]?token|authorization|password)\s*[:=]\s*["']?[a-z0-9._~+/-]{8,})""",
    )
    fun safe(text: String): Boolean = !credentials.containsMatchIn(text)
    fun validate(task: BackgroundTask) {
        require(task.id.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")))
        require(task.title.isNotBlank() && task.title.length <= 80)
        require(task.model.isNotBlank() && task.model.length <= 128)
        require(task.prompt.isNotBlank() && task.prompt.length <= MAX_INPUT)
        require(task.createdAtMillis >= 0 && task.updatedAtMillis >= 0)
        require(task.result.length <= MAX_RESULT && task.progress.length <= MAX_PROGRESS)
        require(listOf(task.title, task.model, task.prompt, task.result, task.progress).all(::safe)) {
            "任务内容包含凭据，请移除后再保存"
        }
        require(task.errorCode == null || task.errorCode.matches(Regex("[a-z_]{1,48}")))
        require(task.parentConversationId == null || (
            task.parentConversationId.isNotBlank() && task.parentConversationId.length <= 128 &&
                task.parentConversationId.none { it.isISOControl() } && safe(task.parentConversationId)
            ))
    }
}
