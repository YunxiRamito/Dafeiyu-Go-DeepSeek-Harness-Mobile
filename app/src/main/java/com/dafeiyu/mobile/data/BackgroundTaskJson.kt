package com.dafeiyu.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object BackgroundTaskJson {
    fun encode(tasks: List<BackgroundTask>): ByteArray {
        require(tasks.size <= BackgroundTaskLimits.MAX_TASKS)
        require(tasks.map { it.id }.distinct().size == tasks.size)
        val array = JSONArray()
        tasks.forEach { task ->
            BackgroundTaskLimits.validate(task)
            array.put(JSONObject().put("id", task.id).put("title", task.title).put("model", task.model)
                .put("prompt", task.prompt).put("status", task.status.name)
                .put("created_at", task.createdAtMillis).put("updated_at", task.updatedAtMillis)
                .put("result", task.result).put("progress", task.progress)
                .put("error_code", task.errorCode ?: JSONObject.NULL).put("kind", task.kind.name)
                .put("parent_conversation_id", task.parentConversationId ?: JSONObject.NULL))
        }
        return JSONObject().put("version", 1).put("tasks", array).toString().toByteArray(Charsets.UTF_8).also {
            require(it.size <= BackgroundTaskLimits.MAX_STORE_BYTES)
        }
    }

    fun decode(bytes: ByteArray): List<BackgroundTask> {
        require(bytes.size <= BackgroundTaskLimits.MAX_STORE_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val tokenizer = JSONTokener(text)
        val json = tokenizer.nextValue() as? JSONObject ?: error("invalid_task_store")
        require(tokenizer.nextClean() == '\u0000')
        require(json.get("version") is Number && json.get("version").toString() == "1")
        val array = json.getJSONArray("tasks")
        require(array.length() <= BackgroundTaskLimits.MAX_TASKS)
        val tasks = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            fun text(name: String): String = item.get(name) as? String ?: error("invalid_task_field")
            fun timestamp(name: String): Long {
                val value = item.get(name)
                require(value is Int || value is Long) { "invalid_task_timestamp" }
                return (value as Number).toLong()
            }
            BackgroundTask(text("id"), text("title"), text("model"), text("prompt"),
                BackgroundTaskStatus.valueOf(text("status")), timestamp("created_at"), timestamp("updated_at"),
                text("result"), text("progress"), if (item.isNull("error_code")) null else text("error_code"),
                if (item.has("kind")) BackgroundTaskKind.valueOf(text("kind")) else BackgroundTaskKind.BACKGROUND,
                if (item.isNull("parent_conversation_id")) null else text("parent_conversation_id"))
                .also(BackgroundTaskLimits::validate)
        }
        require(tasks.map { it.id }.distinct().size == tasks.size)
        return tasks
    }
}
