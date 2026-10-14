package com.dafeiyu.mobile.data

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Repository pauses tasks, enforces local mode,
 * and resolve the durable pending journal before exposing save/sync/import. */
internal class AndroidOfficialCredentialBinding(
    context: Context,
    private val providers: ModelProviderStore,
    private val balance: BalanceStore,
    private val bridge: DshSharedPersistenceBridge,
) {
    private val app = context.applicationContext
    private val journal = AndroidOfficialCredentialJournal(app)
    private val recoveryGate = OfficialCredentialRecoveryGate(journal)
    private val rpc = DshLocalConfigurationRpc()
    private val storageScope = MessageDigest.getInstance("SHA-256").digest(
        "android-dsh-storage-v1\n${app.packageName}\n${app.filesDir.canonicalPath}\n${app.noBackupFilesDir.canonicalPath}"
            .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun hasPending(): Boolean = recoveryGate.hasPending()

    fun hasDeferredPublication(): Boolean = recoveryGate.hasDeferredPublication()
    fun requiresDshRecovery(): Boolean = recoveryGate.requiresDshRecovery()

    fun hasColdStoragePending(): Boolean = OfficialDeepSeekCredentialTransaction.validatedJournal(journal.read())
        .optJSONObject("pending")?.has("dshStorage") == true

    /** Only the authenticated, local no-backup journal can supply a recovery
     * plan. A caller's chosen port/cwd is used for a new transaction only. */
    suspend fun storageLaunchPlanForRecovery(): JSONObject? = journal.transactionMutex.withLock {
        val pending = OfficialDeepSeekCredentialTransaction.validatedJournal(journal.read()).optJSONObject("pending")
            ?: return@withLock null
        if (pending.has("kind")) return@withLock null
        check(pending.has("dshStorage")) { "DSH_DURABLE_PROOF_MISSING" }
        OfficialDshStorageProof.validatePending(pending)
        val plan = pending.getJSONObject("dshStorage").optJSONObject("launchPlan")
            ?: error("DSH_DURABLE_PROOF_MISSING")
        JSONObject(plan.toString())
    }

    suspend fun openColdStorage(factory: AndroidOfficialDshLaunchFactory, newPlan: JSONObject): AndroidOfficialDshLaunchFactory.Session {
        val plan = storageLaunchPlanForRecovery() ?: JSONObject(newPlan.toString())
        return factory.open(AndroidOfficialDshStorageAuthorizer(app, storageScope), storageScope, plan)
    }

    /** Use around conflicting vault reads/writes; a Boolean check alone would
     * race another coordinator between the check and the mutation. */
    suspend fun <T> withNativeReady(operation: suspend () -> T): T = recoveryGate.withNativeReady(operation)

    suspend fun <T> withDshReady(operation: suspend () -> T): T = recoveryGate.withDshReady(operation)

    fun coldCoordinator(port: OfficialDshColdStoragePort): OfficialDeepSeekCredentialTransaction = coordinatorFor(port)

    fun coordinator(accessUrl: String? = null): OfficialDeepSeekCredentialTransaction = coordinatorFor(
        object : OfficialDshCredentialPort {
            override suspend fun describe(): JSONObject = rpc.call(requireNotNull(accessUrl) { "DSH_UNAVAILABLE" }, "settings/describe", JSONObject())
            override suspend fun credentials(): JSONObject = bridge.readCredentialDocument()
            override suspend fun stageReferences(candidates: JSONArray, expectedRevision: String): JSONObject =
                bridge.stageCredentialReferences(candidates, expectedRevision)
            override suspend fun mutate(namespace: String, expectedRevision: Long, reference: String): JSONObject =
                rpc.call(requireNotNull(accessUrl) { "DSH_UNAVAILABLE" }, "settings/mutate", JSONObject().put("ns", namespace).put("expectedRevision", expectedRevision)
                    .put("ops", JSONArray().put(JSONObject().put("op", "set").put("path", JSONArray().put("apiKeyEnv")).put("value", reference))))
        })

    private fun coordinatorFor(port: OfficialDshCredentialPort): OfficialDeepSeekCredentialTransaction = OfficialDeepSeekCredentialTransaction(journal,
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
        port)
}
