package com.dafeiyu.mobile.data

import kotlinx.coroutines.sync.withLock

/** Same root Mutex as the persisted transaction. Avoid check-then-mutate
 * races in adapters, exports and authenticated worker refreshes. */
internal class OfficialCredentialRecoveryGate(private val journal: OfficialCredentialJournalPort) {
    fun hasPending(): Boolean = OfficialDeepSeekCredentialTransaction.validatedJournal(journal.read()).has("pending")
    fun hasDeferredPublication(): Boolean = OfficialDeepSeekCredentialTransaction.validatedJournal(journal.read()).has("deferred")
    fun requiresDshRecovery(): Boolean = OfficialDeepSeekCredentialTransaction.validatedJournal(journal.read())
        .optJSONObject("pending")?.let { !it.has("kind") } == true

    suspend fun <T> withNativeReady(operation: suspend () -> T): T = journal.transactionMutex.withLock {
        check(!hasPending()) { "PENDING_EXISTS" }
        operation()
    }

    suspend fun <T> withDshReady(operation: suspend () -> T): T = withNativeReady {
        check(!hasDeferredPublication()) { "DEFERRED_EXISTS" }
        operation()
    }
}
