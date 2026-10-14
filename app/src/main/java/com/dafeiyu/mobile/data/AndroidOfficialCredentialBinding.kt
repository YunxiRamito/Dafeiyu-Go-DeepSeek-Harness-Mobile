package com.dafeiyu.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Candidate binding only. Repository must pause tasks, enforce local mode,
 * and resolve the durable pending journal before exposing save/sync/import. */
internal class AndroidOfficialCredentialBinding(
    context: Context,
    private val providers: ModelProviderStore,
    private val balance: BalanceStore,
    private val bridge: DshSharedPersistenceBridge,
) {
    private val journal = AndroidOfficialCredentialJournal(context)
    private val recoveryGate = OfficialCredentialRecoveryGate(journal)
    private val rpc = DshLocalConfigurationRpc()

    fun hasPending(): Boolean = recoveryGate.hasPending()

    fun hasDeferredPublication(): Boolean = recoveryGate.hasDeferredPublication()
    fun requiresDshRecovery(): Boolean = recoveryGate.requiresDshRecovery()

    /** Use around conflicting vault reads/writes; a Boolean check alone would
     * race another coordinator between the check and the mutation. */
    suspend fun <T> withNativeReady(operation: suspend () -> T): T = recoveryGate.withNativeReady(operation)

    suspend fun <T> withDshReady(operation: suspend () -> T): T = recoveryGate.withDshReady(operation)

    fun coordinator(accessUrl: String? = null): OfficialDeepSeekCredentialTransaction = OfficialDeepSeekCredentialTransaction(journal,
        object : OfficialNativeCredentialPort {
            override suspend fun read() = providers.officialCredentialSnapshot()
            override suspend fun compareAndSet(expectedRevision: String, key: String?, transactionId: String) {
                providers.compareAndSetOfficialCredential(expectedRevision, key, transactionId)
            }
            override suspend fun compareAndSetPortable(expectedRevision: String, configuration: JSONObject, transactionId: String) {
                providers.compareAndSetPortableOfficialCredential(expectedRevision, configuration, transactionId)
            }
            override suspend fun confirmProjection(key: String?, transactionId: String, configurationDigest: String?) {
                providers.confirmOfficialCredentialProjection(key, transactionId, configurationDigest)
            }
        },
        object : OfficialNativeCredentialPort {
            override suspend fun read() = balance.officialCredentialSnapshot()
            override suspend fun compareAndSet(expectedRevision: String, key: String?, transactionId: String) {
                balance.compareAndSetOfficialCredential(expectedRevision, key, transactionId)
            }
        },
        object : OfficialDshCredentialPort {
            override suspend fun describe(): JSONObject = rpc.call(requireNotNull(accessUrl) { "DSH_UNAVAILABLE" }, "settings/describe", JSONObject())
            override suspend fun credentials(): JSONObject = bridge.readCredentialDocument()
            override suspend fun stageReferences(candidates: JSONArray, expectedRevision: String): JSONObject =
                bridge.stageCredentialReferences(candidates, expectedRevision)
            override suspend fun mutate(namespace: String, expectedRevision: Long, reference: String): JSONObject =
                rpc.call(requireNotNull(accessUrl) { "DSH_UNAVAILABLE" }, "settings/mutate", JSONObject().put("ns", namespace).put("expectedRevision", expectedRevision)
                    .put("ops", JSONArray().put(JSONObject().put("op", "set").put("path", JSONArray().put("apiKeyEnv")).put("value", reference))))
        })
}
