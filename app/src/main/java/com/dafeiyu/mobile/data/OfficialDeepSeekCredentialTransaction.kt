package com.dafeiyu.mobile.data

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Tokens identify authenticated durable snapshots; they never contain the key. */
internal class OfficialNativeCredentialSnapshot(val revision: String, val key: String?, val transactionId: String?, val configurationDigest: String? = null) {
    fun privateJson(): JSONObject = JSONObject().put("revision", revision)
        .put("key", key ?: JSONObject.NULL).put("transactionId", transactionId ?: JSONObject.NULL)
        .also { configurationDigest?.let { digest -> it.put("configurationDigest", digest) } }
    override fun toString(): String = "OfficialNativeCredentialSnapshot(<private>)"
}

internal interface OfficialNativeCredentialPort {
    suspend fun read(): OfficialNativeCredentialSnapshot
    /** Must store key/tombstone and transactionId together under the vault's CAS lock. */
    suspend fun compareAndSet(expectedRevision: String, key: String?, transactionId: String)
    /** Provider-vault only. Payload and private transaction marker share one CAS. */
    suspend fun compareAndSetPortable(expectedRevision: String, configuration: JSONObject, transactionId: String) {
        error("PORTABLE_NOT_SUPPORTED")
    }
    /** Repair the ordering projection after an encrypted commit lost its
     * response; the vault remains the authority for the expected payload. */
    suspend fun confirmProjection(key: String?, transactionId: String, configurationDigest: String?) = Unit
}

internal interface OfficialCredentialJournalPort {
    /** Shared by every coordinator attached to this storage root, not per Activity. */
    val transactionMutex: Mutex
    fun read(): JSONObject
    /** Encrypted, fsynced atomic persistence; stale revision is an error. */
    fun compareAndSet(expectedRevision: Long, replacement: JSONObject): JSONObject
}

internal interface OfficialDshCredentialPort {
    suspend fun describe(): JSONObject
    suspend fun credentials(): JSONObject
    suspend fun stageReferences(candidates: JSONArray, expectedRevision: String): JSONObject
    suspend fun mutate(namespace: String, expectedRevision: Long, reference: String): JSONObject
}

internal sealed interface OfficialCredentialOutcome {
    data class Committed(val transactionId: String, val reference: String, val oldReferenceRetained: Boolean) : OfficialCredentialOutcome
    data class Pending(val transactionId: String?, val code: String) : OfficialCredentialOutcome
    /** Both Android vaults are committed; a later DSH publication is separate. */
    data class NativeCommitted(val transactionId: String, val dshPublicationPending: Boolean) : OfficialCredentialOutcome
    data object NoPending : OfficialCredentialOutcome
}

/** Explicit user choices; the selected secret stays inside the transaction. */
internal enum class OfficialNativeCredentialChoice { PROVIDER, BALANCE, CLEAR }

/**
 * Recoverable three-store transaction, not an atomic commit across Android/DSH.
 * A durable intent precedes every side effect. Only exact native ownership
 * markers and actual DSH user/effective settings can prove a lost response.
 * No failure/cancellation path overwrites or deletes an old DSH reference.
 * Retired references remain private until a separately proven exclusive
 * settings+credentials cleanup lease exists; an idle observation is not a lease.
 * Callers must block Android execution, refresh/import and further official-key
 * edits while this journal is pending (including after application restart).
 */
internal class OfficialDeepSeekCredentialTransaction(
    private val journal: OfficialCredentialJournalPort,
    private val providers: OfficialNativeCredentialPort,
    private val balance: OfficialNativeCredentialPort,
    private val dsh: OfficialDshCredentialPort,
    private val transactionId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    fun hasPending(): Boolean = validatedJournal(journal.read()).has("pending")
    fun hasDeferredPublication(): Boolean = validatedJournal(journal.read()).has("deferred")

    /** DSH need not be installed/running. Never contacts the DSH port. */
    suspend fun beginNative(value: String?): OfficialCredentialOutcome = beginNativePlan(value, null, null, null)

    /** Resolve a displayed legacy disagreement only against both exact vault
     * revisions. The durable plan retains their distinct before snapshots. */
    suspend fun beginNativeResolution(choice: OfficialNativeCredentialChoice,
                                     expectedProviderRevision: String,
                                     expectedBalanceRevision: String): OfficialCredentialOutcome =
        beginNativePlan(null, null, null, expectedProviderRevision, expectedBalanceRevision, choice)

    /** .dym import is a whole-provider CAS plus the Balance tombstone/key. */
    suspend fun beginPortableRestore(configuration: JSONObject): OfficialCredentialOutcome = beginNativePlan(
        OfficialCredentialPortable.key(configuration), configuration, null, null)

    /** The adapter supplies a revision proof from its coherent source read.
     * Recheck it before journaling, then recovery no longer needs DSH online. */
    suspend fun beginReverseImport(configuration: JSONObject, sourceProof: JSONObject, expectedNativeRevision: String,
                                   acceptDeferredConflict: Boolean = false): OfficialCredentialOutcome = beginNativePlan(
        OfficialCredentialPortable.key(configuration), configuration, sourceProof, expectedNativeRevision,
        acceptDeferredConflict = acceptDeferredConflict)

    /** acceptDshConflict is only for an explicit user choice to replace the
     * currently selected DSH key. Background refresh/sync must leave it false. */
    suspend fun begin(value: String?, acceptDshConflict: Boolean = false): OfficialCredentialOutcome = journal.transactionMutex.withLock {
        var id: String? = null
        try {
            currentCoroutineContext().ensureActive()
            val desired = normalize(value)
            val state = validatedJournal(journal.read())
            check(!state.has("pending")) { "PENDING_EXISTS" }
            check(!state.has("deferred")) { "DEFERRED_EXISTS" }
            val providerBefore = providers.read()
            val balanceBefore = balance.read()
            check(providerBefore.key == balanceBefore.key) { "NATIVE_VAULT_CONFLICT" }
            val settings = dsh.describe()
            check(settings.optBoolean("writable")) { "DSH_READ_ONLY" }
            val namespace = officialNamespace(settings)
            val oldReference = reference(namespace)
            val credentials = dsh.credentials()
            val oldDshKey = nullableString(credentials.getJSONObject("refs"), oldReference)
            check(oldDshKey == providerBefore.key || acceptDshConflict) { "OFFICIAL_KEY_CONFLICT" }
            id = transactionId().also { require(it.matches(Regex("[a-f0-9]{32}"))) { "INVALID_ID" } }
            val candidate = "DAFEIYU_PROVIDER_DEEPSEEK_$id"
            check(!credentials.getJSONObject("refs").has(candidate) && !mentions(settings, candidate)) { "REFERENCE_COLLISION" }
            val pending = JSONObject().put("id", id).put("step", "PREPARED")
                .put("desired", desired ?: JSONObject.NULL).put("providerBefore", providerBefore.privateJson())
                .put("balanceBefore", balanceBefore.privateJson()).put("namespace", namespace.getString("ns"))
                .put("settingsBefore", namespace.getLong("revision")).put("referenceBefore", oldReference)
                .put("userReferenceBefore", nullableString(namespace.getJSONObject("user"), "apiKeyEnv") ?: JSONObject.NULL)
                .put("credentialBefore", credentials.getString("revision")).put("candidate", candidate)
                .put("initialDshConflictAccepted", acceptDshConflict)
            val next = JSONObject(state.toString()).put("pending", pending)
            val persisted = journal.compareAndSet(state.getLong("revision"), next)
            advance(persisted)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { OfficialCredentialOutcome.Pending(id, safeCode(failure)) }
    }

    suspend fun recover(): OfficialCredentialOutcome = journal.transactionMutex.withLock {
        try {
            val state = validatedJournal(journal.read())
            if (!state.has("pending")) OfficialCredentialOutcome.NoPending
            else if (state.getJSONObject("pending").has("kind")) advanceNative(state) else advance(state)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { OfficialCredentialOutcome.Pending(null, safeCode(failure)) }
    }

    private suspend fun beginNativePlan(value: String?, configuration: JSONObject?, sourceProof: JSONObject?, expectedNativeRevision: String?,
                                        expectedBalanceRevision: String? = null,
                                        nativeConflictChoice: OfficialNativeCredentialChoice? = null,
                                        acceptDeferredConflict: Boolean = false): OfficialCredentialOutcome =
        journal.transactionMutex.withLock {
            var id: String? = null
            try {
                currentCoroutineContext().ensureActive()
                val requested = normalize(value)
                val portable = configuration?.let(OfficialCredentialPortable::sanitize)
                val state = validatedJournal(journal.read())
                check(!state.has("pending")) { "PENDING_EXISTS" }
                val providerBefore = providers.read()
                val balanceBefore = balance.read()
                check(expectedNativeRevision == null || providerBefore.revision == expectedNativeRevision) { "NATIVE_CAS_CONFLICT" }
                check(expectedBalanceRevision == null || balanceBefore.revision == expectedBalanceRevision) { "NATIVE_CAS_CONFLICT" }
                check(nativeConflictChoice != null || providerBefore.key == balanceBefore.key) { "NATIVE_VAULT_CONFLICT" }
                if (nativeConflictChoice != null) {
                    check(expectedNativeRevision != null && expectedBalanceRevision != null && portable == null && sourceProof == null &&
                        providerBefore.key != balanceBefore.key && !state.has("deferred")) { "NATIVE_CAS_CONFLICT" }
                }
                val desired = normalize(when (nativeConflictChoice) {
                    OfficialNativeCredentialChoice.PROVIDER -> providerBefore.key
                    OfficialNativeCredentialChoice.BALANCE -> balanceBefore.key
                    OfficialNativeCredentialChoice.CLEAR -> null
                    null -> requested
                })
                check(portable == null || OfficialCredentialPortable.key(portable) == desired) { "INVALID_PORTABLE" }
                val deferred = state.optJSONObject("deferred")
                if (deferred != null) {
                    check(owned(providerBefore, nullableString(deferred, "desired"), deferred.getString("id")) &&
                        owned(balanceBefore, nullableString(deferred, "desired"), deferred.getString("id"))) { "NATIVE_CHANGED" }
                }
                val source = sourceProof?.let { proof ->
                    validatedSourceProof(proof)
                    check(deferred == null || nullableString(deferred, "desired") == desired || acceptDeferredConflict) { "DEFERRED_EXISTS" }
                    requireSourceSnapshot(proof, desired)
                    JSONObject(proof.toString())
                }
                id = transactionId().also { require(it.matches(Regex("[a-f0-9]{32}"))) { "INVALID_ID" } }
                val pending = JSONObject().put("id", id).put("kind", when {
                    source != null -> "REVERSE"
                    portable != null -> "RESTORE"
                    else -> "NATIVE"
                }).put("step", "NATIVE_PREPARED").put("desired", desired ?: JSONObject.NULL)
                    .put("providerBefore", providerBefore.privateJson()).put("balanceBefore", balanceBefore.privateJson())
                    .put("baseline", (if (deferred != null) nullableString(deferred, "baseline") else providerBefore.key) ?: JSONObject.NULL)
                if (portable != null) pending.put("portable", portable).put("configurationDigest", OfficialCredentialPortable.digest(portable))
                if (source != null) pending.put("sourceProof", source)
                val next = JSONObject(state.toString()).put("pending", pending)
                advanceNative(journal.compareAndSet(state.getLong("revision"), next))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { OfficialCredentialOutcome.Pending(id, safeCode(failure)) }
        }

    /** Publish a completed offline save when DSH becomes available. Native use
     * does not wait for this; entering DSH must resolve it or show the conflict.
     * A stopped/absent DSH never implicitly authorizes a different live key. */
    suspend fun publishDeferred(acceptDshConflict: Boolean = false): OfficialCredentialOutcome = journal.transactionMutex.withLock {
        var id: String? = null
        try {
            currentCoroutineContext().ensureActive()
            val state = validatedJournal(journal.read())
            check(!state.has("pending")) { "PENDING_EXISTS" }
            val deferred = state.optJSONObject("deferred") ?: return@withLock OfficialCredentialOutcome.NoPending
            val desired = nullableString(deferred, "desired")
            val providerBefore = providers.read()
            val balanceBefore = balance.read()
            check(owned(providerBefore, desired, deferred.getString("id")) &&
                owned(balanceBefore, desired, deferred.getString("id"))) { "NATIVE_CHANGED" }
            val settings = dsh.describe()
            check(settings.optBoolean("writable")) { "DSH_READ_ONLY" }
            val namespace = officialNamespace(settings)
            val oldReference = reference(namespace)
            val credentials = dsh.credentials()
            val oldDshKey = nullableString(credentials.getJSONObject("refs"), oldReference)
            check(oldDshKey == nullableString(deferred, "baseline") || oldDshKey == desired || acceptDshConflict) { "OFFICIAL_KEY_CONFLICT" }
            id = transactionId().also { require(it.matches(Regex("[a-f0-9]{32}"))) { "INVALID_ID" } }
            val candidate = "DAFEIYU_PROVIDER_DEEPSEEK_$id"
            check(!credentials.getJSONObject("refs").has(candidate) && !mentions(settings, candidate)) { "REFERENCE_COLLISION" }
            val pending = JSONObject().put("id", id).put("step", "PREPARED").put("desired", desired ?: JSONObject.NULL)
                .put("providerBefore", providerBefore.privateJson()).put("balanceBefore", balanceBefore.privateJson())
                .put("namespace", namespace.getString("ns")).put("settingsBefore", namespace.getLong("revision"))
                .put("referenceBefore", oldReference).put("userReferenceBefore", nullableString(namespace.getJSONObject("user"), "apiKeyEnv") ?: JSONObject.NULL)
                .put("credentialBefore", credentials.getString("revision")).put("candidate", candidate)
                .put("initialDshConflictAccepted", acceptDshConflict).put("deferredId", deferred.getString("id"))
            advance(journal.compareAndSet(state.getLong("revision"), JSONObject(state.toString()).put("pending", pending)))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { OfficialCredentialOutcome.Pending(id, safeCode(failure)) }
    }

    private suspend fun requireSourceSnapshot(proof: JSONObject, desired: String?) {
        val settings = dsh.describe()
        val official = officialNamespace(settings)
        check(official.getString("ns") == proof.getString("namespace") && official.getLong("revision") == proof.getLong("settingsRevision") &&
            reference(official) == proof.getString("reference")) { "SOURCE_CHANGED" }
        val providerRows = settings.getJSONArray("namespaces")
        val providers = (0 until providerRows.length()).map { providerRows.getJSONObject(it) }.filter { it.optString("ns") == "llm-pi-ai" }
        check(providers.size == 1 && providers.single().getLong("revision") == proof.getLong("providerSettingsRevision")) { "SOURCE_CHANGED" }
        val credentials = dsh.credentials()
        check(credentials.getString("revision") == proof.getString("credentialRevision") &&
            nullableString(credentials.getJSONObject("refs"), reference(official)) == desired) { "SOURCE_CHANGED" }
        val checked = dsh.describe()
        val checkedOfficial = officialNamespace(checked)
        val checkedRows = checked.getJSONArray("namespaces")
        val checkedProviders = (0 until checkedRows.length()).map { checkedRows.getJSONObject(it) }.filter { it.optString("ns") == "llm-pi-ai" }
        check(checkedOfficial.getString("ns") == proof.getString("namespace") && checkedOfficial.getLong("revision") == proof.getLong("settingsRevision") &&
            reference(checkedOfficial) == proof.getString("reference") &&
            checkedProviders.size == 1 && checkedProviders.single().getLong("revision") == proof.getLong("providerSettingsRevision") &&
            dsh.credentials().getString("revision") == proof.getString("credentialRevision")) { "SOURCE_CHANGED" }
    }

    private suspend fun advanceNative(initial: JSONObject): OfficialCredentialOutcome {
        var state = validatedJournal(initial)
        val pending = state.getJSONObject("pending")
        val id = pending.getString("id")
        val desired = nullableString(pending, "desired")
        val providerBefore = snapshot(pending.getJSONObject("providerBefore"))
        val balanceBefore = snapshot(pending.getJSONObject("balanceBefore"))
        val portable = pending.optJSONObject("portable")
        val digest = nullableString(pending, "configurationDigest")
        fun providerOwned(value: OfficialNativeCredentialSnapshot): Boolean = owned(value, desired, id) &&
            (digest == null || value.configurationDigest == digest)
        fun persist(step: String, facts: (JSONObject) -> Unit = {}) {
            val next = JSONObject(state.toString())
            next.getJSONObject("pending").put("step", step).also(facts)
            state = journal.compareAndSet(state.getLong("revision"), next)
        }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                when (state.getJSONObject("pending").getString("step")) {
                    "NATIVE_PREPARED" -> {
                        unchanged(providers.read(), providerBefore); unchanged(balance.read(), balanceBefore)
                        persist("NATIVE_PROVIDER_SUBMITTING")
                    }
                    "NATIVE_PROVIDER_SUBMITTING" -> {
                        unchanged(balance.read(), balanceBefore)
                        var current = providers.read()
                        if (!providerOwned(current)) {
                            unchanged(current, providerBefore)
                            if (portable == null) providers.compareAndSet(providerBefore.revision, desired, id)
                            else providers.compareAndSetPortable(providerBefore.revision, portable, id)
                            current = providers.read()
                        }
                        check(providerOwned(current) && current.revision != providerBefore.revision) { "NATIVE_NOT_CONFIRMED" }
                        persist("NATIVE_PROVIDER_CONFIRMED") { it.put("providerCommitted", current.revision) }
                    }
                    "NATIVE_PROVIDER_CONFIRMED" -> {
                        check(providerOwned(providers.read())) { "NATIVE_CHANGED" }
                        persist("NATIVE_BALANCE_SUBMITTING")
                    }
                    "NATIVE_BALANCE_SUBMITTING" -> {
                        check(providerOwned(providers.read())) { "NATIVE_CHANGED" }
                        var current = balance.read()
                        if (!owned(current, desired, id)) {
                            unchanged(current, balanceBefore)
                            balance.compareAndSet(balanceBefore.revision, desired, id)
                            current = balance.read()
                        }
                        check(owned(current, desired, id) && current.revision != balanceBefore.revision) { "NATIVE_NOT_CONFIRMED" }
                        persist("NATIVE_COMMITTING") { it.put("balanceCommitted", current.revision) }
                    }
                    "NATIVE_COMMITTING" -> {
                        val provider = providers.read()
                        val nativeBalance = balance.read()
                        check(providerOwned(provider) && owned(nativeBalance, desired, id)) { "NATIVE_CHANGED" }
                        providers.confirmProjection(desired, id, digest)
                        val next = JSONObject(state.toString())
                        val receipt = JSONObject().put("id", id).put("kind", pending.getString("kind"))
                            .put("providerRevision", provider.revision).put("balanceRevision", nativeBalance.revision).put("keyRemoved", desired == null)
                        digest?.let { receipt.put("configurationDigest", it) }
                        next.put("nativeReceipt", receipt)
                        val publicationPending = pending.getString("kind") != "REVERSE"
                        if (publicationPending) next.put("deferred", JSONObject().put("id", id)
                            .put("desired", desired ?: JSONObject.NULL).put("baseline", nullableString(pending, "baseline") ?: JSONObject.NULL)
                            .put("providerRevision", provider.revision).put("balanceRevision", nativeBalance.revision))
                        else next.remove("deferred")
                        next.remove("pending")
                        journal.compareAndSet(state.getLong("revision"), next)
                        return OfficialCredentialOutcome.NativeCommitted(id, publicationPending)
                    }
                    else -> error("INVALID_JOURNAL")
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { return OfficialCredentialOutcome.Pending(id, safeCode(failure)) }
    }

    private suspend fun advance(initial: JSONObject): OfficialCredentialOutcome {
        var state = validatedJournal(initial)
        val pending = state.getJSONObject("pending")
        val id = pending.getString("id")
        val desired = nullableString(pending, "desired")
        val candidate = pending.getString("candidate")
        val providerBefore = snapshot(pending.getJSONObject("providerBefore"))
        val balanceBefore = snapshot(pending.getJSONObject("balanceBefore"))
        fun persist(step: String, values: (JSONObject) -> Unit = {}) {
            val next = JSONObject(state.toString())
            next.getJSONObject("pending").put("step", step).also(values)
            state = journal.compareAndSet(state.getLong("revision"), next)
            // The stable private plan above is immutable; progress facts come
            // from the current state, never from an unpersisted local result.
        }
        suspend fun nativeUnchanged() {
            unchanged(providers.read(), providerBefore)
            unchanged(balance.read(), balanceBefore)
        }
        suspend fun requireDshAdopted(): JSONObject {
            val descriptor = officialNamespace(dsh.describe())
            check(descriptor.getString("ns") == pending.getString("namespace") && adopted(descriptor, pending)) { "DSH_CHANGED" }
            val document = dsh.credentials()
            check(nullableString(document.getJSONObject("refs"), candidate) == desired) { "CANDIDATE_CHANGED" }
            return descriptor
        }
        suspend fun commitVault(port: OfficialNativeCredentialPort, before: OfficialNativeCredentialSnapshot): OfficialNativeCredentialSnapshot {
            requireDshAdopted()
            var current = port.read()
            if (!owned(current, desired, id)) {
                unchanged(current, before)
                port.compareAndSet(before.revision, desired, id)
                current = port.read()
            }
            check(owned(current, desired, id) && current.revision != before.revision) { "NATIVE_NOT_CONFIRMED" }
            return current
        }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val live = state.getJSONObject("pending")
                when (live.getString("step")) {
                    "PREPARED" -> { nativeUnchanged(); persist("STAGING") }
                    "STAGING" -> {
                        nativeUnchanged()
                        val settings = dsh.describe()
                        val descriptor = officialNamespace(settings)
                        requireOriginalOrAdopted(descriptor, pending)
                        var document = dsh.credentials()
                        val existing = nullableString(document.getJSONObject("refs"), candidate)
                        if (desired == null) check(document.getString("revision") == pending.getString("credentialBefore")) { "CREDENTIAL_CAS_CONFLICT" }
                        if (desired != null && existing == null) {
                            check(document.getString("revision") == pending.getString("credentialBefore")) { "CREDENTIAL_CAS_CONFLICT" }
                            check(!mentions(settings, candidate)) { "REFERENCE_COLLISION" }
                            val result = dsh.stageReferences(JSONArray().put(JSONObject().put("ref", candidate).put("value", desired)),
                                document.getString("revision"))
                            check(result.getInt("applied") == 1) { "STAGE_NOT_CONFIRMED" }
                            document = dsh.credentials()
                            check(result.getString("revision") == document.getString("revision")) { "STAGE_NOT_CONFIRMED" }
                        }
                        check(nullableString(document.getJSONObject("refs"), candidate) == desired) { "CANDIDATE_CHANGED" }
                        persist("STAGED") { it.put("stagedRevision", document.getString("revision")) }
                    }
                    "STAGED" -> { nativeUnchanged(); persist("SETTINGS_SUBMITTING") }
                    "SETTINGS_SUBMITTING" -> {
                        var descriptor = officialNamespace(dsh.describe())
                        requireOriginalOrAdopted(descriptor, pending)
                        if (!adopted(descriptor, pending)) {
                            nativeUnchanged()
                            val document = dsh.credentials()
                            check(document.getString("revision") == live.getString("stagedRevision") &&
                                nullableString(document.getJSONObject("refs"), candidate) == desired) { "CREDENTIAL_CAS_CONFLICT" }
                            val result = dsh.mutate(pending.getString("namespace"), pending.getLong("settingsBefore"), candidate)
                            check(result.getString("ns") == pending.getString("namespace") && adopted(result, pending)) { "SETTINGS_NOT_CONFIRMED" }
                            descriptor = officialNamespace(dsh.describe())
                        }
                        check(adopted(descriptor, pending)) { "SETTINGS_NOT_CONFIRMED" }
                        requireDshAdopted()
                        persist("DSH_CONFIRMED") { it.put("settingsCommitted", descriptor.getLong("revision")) }
                    }
                    "DSH_CONFIRMED" -> { requireDshAdopted(); persist("PROVIDER_SUBMITTING") }
                    "PROVIDER_SUBMITTING" -> {
                        val committed = commitVault(providers, providerBefore)
                        persist("PROVIDER_CONFIRMED") { it.put("providerCommitted", committed.revision) }
                    }
                    "PROVIDER_CONFIRMED" -> { requireDshAdopted(); persist("BALANCE_SUBMITTING") }
                    "BALANCE_SUBMITTING" -> {
                        check(owned(providers.read(), desired, id)) { "NATIVE_CHANGED" }
                        val committed = commitVault(balance, balanceBefore)
                        persist("BALANCE_CONFIRMED") { it.put("balanceCommitted", committed.revision) }
                    }
                    "BALANCE_CONFIRMED" -> { persist("COMMITTING") }
                    "COMMITTING" -> {
                        val descriptor = requireDshAdopted()
                        val provider = providers.read()
                        val nativeBalance = balance.read()
                        check(owned(provider, desired, id) && owned(nativeBalance, desired, id)) { "NATIVE_CHANGED" }
                        providers.confirmProjection(desired, id, null)
                        val next = JSONObject(state.toString())
                        val owned = next.getJSONObject("ownedReferences")
                        owned.put(candidate, id)
                        val retired = next.getJSONArray("retiredOwnedReferences")
                        val oldReference = pending.getString("referenceBefore")
                        if (owned.has(oldReference) && oldReference != candidate &&
                            (0 until retired.length()).none { retired.getString(it) == oldReference }) retired.put(oldReference)
                        next.put("receipt", JSONObject().put("id", id).put("reference", candidate)
                            .put("namespace", descriptor.getString("ns")).put("settingsRevision", descriptor.getLong("revision"))
                            .put("providerRevision", provider.revision).put("balanceRevision", nativeBalance.revision)
                            .put("keyRemoved", desired == null).put("oldReferenceRetained", oldReference != candidate))
                        next.remove("pending")
                        if (pending.has("deferredId")) {
                            check(next.getJSONObject("deferred").getString("id") == pending.getString("deferredId")) { "INVALID_JOURNAL" }
                            next.remove("deferred")
                        }
                        journal.compareAndSet(state.getLong("revision"), next)
                        return OfficialCredentialOutcome.Committed(id, candidate, oldReference != candidate)
                    }
                    else -> error("INVALID_JOURNAL")
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { return OfficialCredentialOutcome.Pending(id, safeCode(failure)) }
    }

    companion object {
        private val phases = setOf("PREPARED", "STAGING", "STAGED", "SETTINGS_SUBMITTING", "DSH_CONFIRMED", "PROVIDER_SUBMITTING",
            "PROVIDER_CONFIRMED", "BALANCE_SUBMITTING", "BALANCE_CONFIRMED", "COMMITTING")
        fun emptyJournal(): JSONObject = JSONObject().put("schema", 1).put("revision", 0)
            .put("ownedReferences", JSONObject()).put("retiredOwnedReferences", JSONArray())

        fun validatedJournal(document: JSONObject): JSONObject {
            require(document.getInt("schema") == 1 && document.getLong("revision") >= 0) { "INVALID_JOURNAL" }
            require(document.keys().asSequence().all { it in setOf("schema", "revision", "ownedReferences", "retiredOwnedReferences", "pending", "receipt", "nativeReceipt", "deferred") }) { "INVALID_JOURNAL" }
            val owned = document.getJSONObject("ownedReferences")
            require(owned.length() <= 4096) { "INVALID_JOURNAL" }
            owned.keys().forEach { reference -> require(owned.getString(reference).matches(Regex("[a-f0-9]{32}")) &&
                reference == "DAFEIYU_PROVIDER_DEEPSEEK_${owned.getString(reference)}") { "INVALID_JOURNAL" } }
            val retired = document.getJSONArray("retiredOwnedReferences")
            require(retired.length() <= 4096 && (0 until retired.length()).all { owned.has(retired.getString(it)) }) { "INVALID_JOURNAL" }
            document.optJSONObject("pending")?.let { pending ->
                if (pending.has("kind")) {
                    validatedNativePlan(pending)
                    return@let
                }
                val required = setOf("id", "step", "desired", "providerBefore", "balanceBefore", "namespace", "settingsBefore",
                    "referenceBefore", "userReferenceBefore", "credentialBefore", "candidate", "initialDshConflictAccepted")
                val optional = setOf("stagedRevision", "settingsCommitted", "providerCommitted", "balanceCommitted", "deferredId")
                require(required.all(pending::has) && pending.keys().asSequence().all { it in required || it in optional }) { "INVALID_JOURNAL" }
                require(pending.get("initialDshConflictAccepted") is Boolean) { "INVALID_JOURNAL" }
                require(pending.getString("id").matches(Regex("[a-f0-9]{32}")) && pending.getString("step") in phases &&
                    pending.getString("candidate") == "DAFEIYU_PROVIDER_DEEPSEEK_${pending.getString("id")}" &&
                    pending.getString("namespace") in setOf("llm-deepseek", "llm-deepseek-api-key") &&
                    pending.getLong("settingsBefore") >= 0 && pending.getString("credentialBefore").matches(Regex("[a-f0-9]{64}"))) { "INVALID_JOURNAL" }
                val desired = nullableString(pending, "desired")
                require(normalize(desired) == desired) { "INVALID_JOURNAL" }
                snapshot(pending.getJSONObject("providerBefore")); snapshot(pending.getJSONObject("balanceBefore"))
                require(pending.getString("referenceBefore").matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "INVALID_JOURNAL" }
                nullableString(pending, "userReferenceBefore")?.let { require(it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "INVALID_JOURNAL" } }
                if (pending.getString("step") !in setOf("PREPARED", "STAGING")) require(pending.getString("stagedRevision").matches(Regex("[a-f0-9]{64}"))) { "INVALID_JOURNAL" }
                if (pending.has("deferredId")) require(document.getJSONObject("deferred").getString("id") == pending.getString("deferredId")) { "INVALID_JOURNAL" }
            }
            if (document.has("pending")) require(document.optJSONObject("pending") != null) { "INVALID_JOURNAL" }
            document.optJSONObject("receipt")?.let { receipt ->
                require(receipt.getString("id").matches(Regex("[a-f0-9]{32}")) &&
                    owned.optString(receipt.getString("reference")) == receipt.getString("id") && receipt.getLong("settingsRevision") >= 0 &&
                    receipt.getString("namespace") in setOf("llm-deepseek", "llm-deepseek-api-key") && receipt.get("keyRemoved") is Boolean &&
                    receipt.get("oldReferenceRetained") is Boolean && receipt.getString("providerRevision").isNotEmpty() &&
                    receipt.getString("balanceRevision").isNotEmpty()) { "INVALID_JOURNAL" }
            }
            if (document.has("receipt")) require(document.optJSONObject("receipt") != null) { "INVALID_JOURNAL" }
            document.optJSONObject("nativeReceipt")?.let { receipt ->
                require(receipt.keys().asSequence().all { it in setOf("id", "kind", "providerRevision", "balanceRevision", "keyRemoved", "configurationDigest") } &&
                    receipt.getString("id").matches(Regex("[a-f0-9]{32}")) && receipt.getString("kind") in setOf("NATIVE", "RESTORE", "REVERSE") &&
                    receipt.getString("providerRevision").isNotEmpty() && receipt.getString("balanceRevision").isNotEmpty() &&
                    receipt.get("keyRemoved") is Boolean) { "INVALID_JOURNAL" }
                if (receipt.has("configurationDigest")) require(receipt.getString("configurationDigest").matches(Regex("[a-f0-9]{64}"))) { "INVALID_JOURNAL" }
            }
            if (document.has("nativeReceipt")) require(document.optJSONObject("nativeReceipt") != null) { "INVALID_JOURNAL" }
            document.optJSONObject("deferred")?.let { deferred ->
                require(deferred.keys().asSequence().toSet() == setOf("id", "desired", "baseline", "providerRevision", "balanceRevision") &&
                    deferred.getString("id").matches(Regex("[a-f0-9]{32}")) && deferred.getString("providerRevision").isNotEmpty() &&
                    deferred.getString("balanceRevision").isNotEmpty()) { "INVALID_JOURNAL" }
                require(normalize(nullableString(deferred, "desired")) == nullableString(deferred, "desired") &&
                    normalize(nullableString(deferred, "baseline")) == nullableString(deferred, "baseline")) { "INVALID_JOURNAL" }
            }
            if (document.has("deferred")) require(document.optJSONObject("deferred") != null) { "INVALID_JOURNAL" }
            return document
        }

        private fun validatedNativePlan(pending: JSONObject) {
            val required = setOf("id", "kind", "step", "desired", "providerBefore", "balanceBefore", "baseline")
            val optional = setOf("portable", "configurationDigest", "sourceProof", "providerCommitted", "balanceCommitted")
            require(required.all(pending::has) && pending.keys().asSequence().all { it in required || it in optional } &&
                pending.getString("id").matches(Regex("[a-f0-9]{32}")) && pending.getString("kind") in setOf("NATIVE", "RESTORE", "REVERSE") &&
                pending.getString("step") in setOf("NATIVE_PREPARED", "NATIVE_PROVIDER_SUBMITTING", "NATIVE_PROVIDER_CONFIRMED", "NATIVE_BALANCE_SUBMITTING", "NATIVE_COMMITTING")) { "INVALID_JOURNAL" }
            val desired = nullableString(pending, "desired")
            require(normalize(desired) == desired && normalize(nullableString(pending, "baseline")) == nullableString(pending, "baseline")) { "INVALID_JOURNAL" }
            snapshot(pending.getJSONObject("providerBefore")); snapshot(pending.getJSONObject("balanceBefore"))
            if (pending.getString("kind") != "NATIVE") {
                val portable = pending.getJSONObject("portable")
                require(OfficialCredentialPortable.key(portable) == desired &&
                    OfficialCredentialPortable.digest(portable) == pending.getString("configurationDigest")) { "INVALID_JOURNAL" }
            } else require(!pending.has("portable") && !pending.has("configurationDigest")) { "INVALID_JOURNAL" }
            if (pending.getString("kind") == "REVERSE") validatedSourceProof(pending.getJSONObject("sourceProof"))
            else require(!pending.has("sourceProof")) { "INVALID_JOURNAL" }
        }

        private fun validatedSourceProof(proof: JSONObject) {
            require(proof.keys().asSequence().toSet() == setOf("namespace", "settingsRevision", "reference", "providerSettingsRevision", "credentialRevision") &&
                proof.getString("namespace") in setOf("llm-deepseek", "llm-deepseek-api-key") && proof.getLong("settingsRevision") >= 0 &&
                proof.getLong("providerSettingsRevision") >= 0 && proof.getString("reference").matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) &&
                proof.getString("credentialRevision").matches(Regex("[a-f0-9]{64}"))) { "INVALID_JOURNAL" }
        }

        private fun officialNamespace(settings: JSONObject): JSONObject {
            val descriptors = settings.getJSONArray("namespaces")
            val matches = (0 until descriptors.length()).map { descriptors.getJSONObject(it) }
                .filter { it.optString("ns") in setOf("llm-deepseek", "llm-deepseek-api-key") }
            check(matches.size == 1) { "OFFICIAL_NAMESPACE_INVALID" }
            return matches.single().also { check(it.getLong("revision") >= 0); reference(it); it.getJSONObject("user") }
        }
        private fun reference(namespace: JSONObject): String = namespace.getJSONObject("value").optString("apiKeyEnv", "DEEPSEEK_API_KEY").also {
            require(it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "OFFICIAL_NAMESPACE_INVALID" }
        }
        private fun adopted(namespace: JSONObject, pending: JSONObject): Boolean = namespace.optString("ns") == pending.getString("namespace") &&
            namespace.optLong("revision", -1) > pending.getLong("settingsBefore") &&
            namespace.getJSONObject("user").optString("apiKeyEnv") == pending.getString("candidate") && reference(namespace) == pending.getString("candidate")
        private fun requireOriginalOrAdopted(namespace: JSONObject, pending: JSONObject) {
            check(adopted(namespace, pending) || namespace.getString("ns") == pending.getString("namespace") &&
                namespace.getLong("revision") == pending.getLong("settingsBefore") && reference(namespace) == pending.getString("referenceBefore") &&
                nullableString(namespace.getJSONObject("user"), "apiKeyEnv") == nullableString(pending, "userReferenceBefore")) { "SETTINGS_CAS_CONFLICT" }
        }
        private fun snapshot(value: JSONObject): OfficialNativeCredentialSnapshot {
            val required = setOf("revision", "key", "transactionId")
            require(required.all(value::has) && value.keys().asSequence().all { it in required || it == "configurationDigest" }) { "INVALID_JOURNAL" }
            val key = nullableString(value, "key")
            require(normalize(key) == key) { "INVALID_JOURNAL" }
            return OfficialNativeCredentialSnapshot(
            value.getString("revision").also { require(it.isNotEmpty() && it.length <= 128) { "INVALID_JOURNAL" } },
            key, nullableString(value, "transactionId")?.also {
                require(it.matches(Regex("[a-f0-9]{32}"))) { "INVALID_JOURNAL" }
            }, nullableString(value, "configurationDigest")?.also { require(it.matches(Regex("[a-f0-9]{64}"))) { "INVALID_JOURNAL" } })
        }
        private fun owned(value: OfficialNativeCredentialSnapshot, key: String?, id: String): Boolean = value.key == key && value.transactionId == id
        private fun unchanged(value: OfficialNativeCredentialSnapshot, before: OfficialNativeCredentialSnapshot) {
            check(value.revision == before.revision && value.key == before.key && value.transactionId == before.transactionId &&
                value.configurationDigest == before.configurationDigest) { "NATIVE_CAS_CONFLICT" }
        }
        internal fun normalize(value: String?): String? = value?.trim()?.also {
            require(it.isNotEmpty() && it.length <= 512 && it.none { char -> char.isWhitespace() || char.isISOControl() }) { "INVALID_KEY" }
        }
        internal fun nullableString(value: JSONObject, key: String): String? = if (!value.has(key) || value.isNull(key)) null else value.getString(key)
        internal fun mentions(value: Any?, reference: String): Boolean = when (value) {
            is JSONObject -> value.keys().asSequence().any { mentions(value.get(it), reference) }
            is JSONArray -> (0 until value.length()).any { mentions(value.get(it), reference) }
            else -> value == reference
        }
        private val knownCodes = setOf("PENDING_EXISTS", "NATIVE_VAULT_CONFLICT", "DSH_READ_ONLY", "INVALID_ID", "INVALID_KEY", "REFERENCE_COLLISION",
            "OFFICIAL_KEY_CONFLICT", "DSH_CHANGED", "CANDIDATE_CHANGED", "NATIVE_NOT_CONFIRMED", "CREDENTIAL_CAS_CONFLICT", "STAGE_NOT_CONFIRMED",
            "SETTINGS_NOT_CONFIRMED", "NATIVE_CHANGED", "INVALID_JOURNAL", "OFFICIAL_NAMESPACE_INVALID", "SETTINGS_CAS_CONFLICT", "NATIVE_CAS_CONFLICT",
            "DEFERRED_EXISTS", "SOURCE_CHANGED", "INVALID_PORTABLE", "PORTABLE_NOT_SUPPORTED", "DSH_UNAVAILABLE")
        private fun safeCode(failure: Exception): String = failure.message?.takeIf { it in knownCodes } ?: "STORAGE_OR_TRANSPORT_PENDING"
        internal fun encryptedRevision(payload: ByteArray?): String = if (payload == null) "absent" else
            MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    }
}
