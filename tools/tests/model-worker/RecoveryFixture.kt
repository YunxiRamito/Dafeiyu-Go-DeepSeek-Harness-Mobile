package com.dafeiyu.mobile.data

import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/** JVM fixtures use the real recovery admission code with isolated memory storage.
 * This is not an Android Keystore or durable-restart verification. */
internal fun fixtureModelRecoveryAccess(): ModelRuntimeRecoveryAccess =
    JournalModelRuntimeRecoveryAccess(object : OfficialCredentialJournalPort {
        override val transactionMutex = Mutex()
        private var state = OfficialDeepSeekCredentialTransaction.emptyJournal()
        override fun read(): JSONObject = synchronized(this) { JSONObject(state.toString()) }
        override fun compareAndSet(expectedRevision: Long, replacement: JSONObject): JSONObject = synchronized(this) {
            check(state.getLong("revision") == expectedRevision && replacement.getLong("revision") == expectedRevision)
            state = JSONObject(replacement.toString()).put("revision", Math.addExact(expectedRevision, 1))
            JSONObject(state.toString())
        }
    })
