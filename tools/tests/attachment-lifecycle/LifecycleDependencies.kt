package com.dafeiyu.mobile.data

import java.io.File
import kotlin.reflect.KProperty

enum class WorkspaceMode { Lite, Dsh }
data class ChatMessage(val attachmentIds: List<String>)
class WorkspaceStore(val root: File) {
    companion object { val INDEX_LOCK = Any() }
}
class TestIntState(var value: Int) {
    operator fun getValue(owner: Any?, property: KProperty<*>): Int = value
    operator fun setValue(owner: Any?, property: KProperty<*>, next: Int) { value = next }
}
