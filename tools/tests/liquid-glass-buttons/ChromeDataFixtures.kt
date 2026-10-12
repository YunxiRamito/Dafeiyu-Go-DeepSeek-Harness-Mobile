package com.dafeiyu.mobile.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Compile-only repository/engine boundary; task models and both action components are real sources. */
class BackgroundTaskEngine {
    val tasks: StateFlow<List<BackgroundTask>> = MutableStateFlow(emptyList())
    val storageIssue: StateFlow<String?> = MutableStateFlow(null)
}

class DemoRepository {
    val backgroundTasks: BackgroundTaskEngine? = null
    val conversations = emptyList<ChromeConversation>()
    val selectedConversationId: String? = null
}

data class ChromeConversation(val id: String, val title: String)
