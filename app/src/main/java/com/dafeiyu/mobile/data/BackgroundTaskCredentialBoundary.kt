package com.dafeiyu.mobile.data

import kotlinx.coroutines.CancellationException

/** Keep task admission closed if any credential-change stop could not be committed. */
internal class BackgroundTaskCredentialBoundary {
    @Volatile var blocked = false
        private set

    suspend fun stop(engine: BackgroundTaskEngine?, cancelClients: () -> Unit) {
        blocked = true
        var failure: Exception? = null
        try {
            cancelClients()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }
        val activeIds = engine?.tasks?.value.orEmpty().filter {
            it.status in setOf(BackgroundTaskStatus.QUEUED, BackgroundTaskStatus.RUNNING, BackgroundTaskStatus.STOPPING)
        }.map { it.id }
        for (id in activeIds) {
            try {
                engine?.stop(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (failure == null) failure = error
            }
        }
        failure?.let { throw it }
        blocked = false
    }
}
