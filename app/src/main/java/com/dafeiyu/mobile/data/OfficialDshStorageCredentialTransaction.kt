package com.dafeiyu.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** A host-owned storage helper, bound to the actual stopped DSH runtime and
 * its exclusive process lease. Live settings revisions cannot implement this
 * port. Every write must additionally authenticate the concrete local journal. */
internal interface OfficialDshColdStoragePort : OfficialDshCredentialPort {
    /** Host-owned choices reused when constructing a helper after restart.
     * Code, pins, mount paths and full environment are never backup inputs. */
    fun launchPlan(): JSONObject
    suspend fun readStorage(candidate: String): JSONObject
    suspend fun stageStorage(expected: JSONObject): JSONObject
    suspend fun selectStorage(expected: JSONObject): JSONObject
}

/** This class is invoked under the existing journal transactionMutex. The
 * caller owns the helper/slot lease for the entire operation; it keeps DSH
 * stopped until all native CAS writes and durable receipts have settled. */
internal class OfficialDshStorageCredentialTransaction(
    private val journal: OfficialCredentialJournalPort,
    private val providers: OfficialNativeCredentialPort,
    private val balance: OfficialNativeCredentialPort,
    private val dsh: OfficialDshColdStoragePort,
    private val transactionId: () -> String,
) {
    suspend fun beginLocked(state: JSONObject, desired: String?, acceptConflict: Boolean): OfficialCredentialOutcome {
        val provider = providers.read()
        val nativeBalance = balance.read()
        check(provider.key == nativeBalance.key) { "NATIVE_VAULT_CONFLICT" }
        return prepareAndAdvance(state, provider, nativeBalance, desired, provider.key, acceptConflict, null)
    }

    suspend fun publishDeferredLocked(state: JSONObject, acceptConflict: Boolean): OfficialCredentialOutcome {
        val deferred = state.getJSONObject("deferred")
        val desired = nullable(deferred, "desired")
        val provider = providers.read()
        val nativeBalance = balance.read()
        check(owned(provider, desired, deferred.getString("id")) && owned(nativeBalance, desired, deferred.getString("id"))) { "NATIVE_CHANGED" }
        return prepareAndAdvance(state, provider, nativeBalance, desired, nullable(deferred, "baseline"), acceptConflict, deferred.getString("id"))
    }

    private suspend fun prepareAndAdvance(state: JSONObject, provider: OfficialNativeCredentialSnapshot,
        nativeBalance: OfficialNativeCredentialSnapshot, desired: String?, baseline: String?, acceptConflict: Boolean,
        deferredId: String?): OfficialCredentialOutcome {
        currentCoroutineContext().ensureActive()
        val id = transactionId().also { require(it.matches(Regex("[a-f0-9]{32}"))) { "INVALID_ID" } }
        val candidate = "DAFEIYU_PROVIDER_DEEPSEEK_$id"
        check(provider.transactionId != id && nativeBalance.transactionId != id && !state.getJSONObject("ownedReferences").has(candidate)) { "REFERENCE_COLLISION" }
        val before = OfficialDshStorageProof.validateSnapshot(dsh.readStorage(candidate), candidate)
        check(!before.getBoolean("candidatePresent") && !before.getBoolean("candidateMentioned") && before.isNull("candidateKey")) { "REFERENCE_COLLISION" }
        check(nullable(before, "selectedKey") == baseline || deferredId != null && nullable(before, "selectedKey") == desired || acceptConflict) { "OFFICIAL_KEY_CONFLICT" }
        unchanged(providers.read(), provider); unchanged(balance.read(), nativeBalance)
        val pending = JSONObject().put("id", id).put("step", "PREPARED").put("desired", desired ?: JSONObject.NULL)
            .put("providerBefore", provider.privateJson()).put("balanceBefore", nativeBalance.privateJson())
            .put("namespace", before.getString("namespace"))
            .put("referenceBefore", before.getString("effectiveReference")).put("userReferenceBefore", before.get("userReference"))
            .put("credentialBefore", before.getString("credentialRevision")).put("candidate", candidate)
            .put("initialDshConflictAccepted", acceptConflict)
            .put("dshStorage", JSONObject().put("schema", 1).put("realm", "android-dsh-storage-v1")
                .put("bindingDigest", before.getString("bindingDigest")).put("before", JSONObject(before.toString()))
                .put("launchPlan", JSONObject(dsh.launchPlan().toString())))
        if (deferredId != null) pending.put("deferredId", deferredId)
        val persisted = journal.compareAndSet(state.getLong("revision"), JSONObject(state.toString()).put("pending", pending))
        return advanceLocked(persisted)
    }

    suspend fun advanceLocked(initial: JSONObject): OfficialCredentialOutcome {
        var state = OfficialDeepSeekCredentialTransaction.validatedJournal(initial)
        val plan = state.getJSONObject("pending")
        val id = plan.getString("id")
        val desired = nullable(plan, "desired")
        val candidate = plan.getString("candidate")
        val providerBefore = nativeSnapshot(plan.getJSONObject("providerBefore"))
        val balanceBefore = nativeSnapshot(plan.getJSONObject("balanceBefore"))
        fun persist(step: String, facts: (JSONObject) -> Unit = {}) {
            val next = JSONObject(state.toString())
            facts(next.getJSONObject("pending").put("step", step))
            state = journal.compareAndSet(state.getLong("revision"), next)
        }
        suspend fun current(): JSONObject = OfficialDshStorageProof.validateSnapshot(dsh.readStorage(candidate), candidate)
        suspend fun nativeUnchanged() { unchanged(providers.read(), providerBefore); unchanged(balance.read(), balanceBefore) }
        suspend fun requireSelected(): JSONObject = current().also {
            check(OfficialDshStorageRecovery.classify(state.getJSONObject("pending"), it) == OfficialDshStorageRecovery.State.SELECTED) { "DSH_CHANGED" }
        }
        suspend fun commit(port: OfficialNativeCredentialPort, before: OfficialNativeCredentialSnapshot): OfficialNativeCredentialSnapshot {
            requireSelected()
            var actual = port.read()
            if (!owned(actual, desired, id)) {
                unchanged(actual, before)
                port.compareAndSet(before.revision, desired, id)
                actual = port.read()
            }
            check(owned(actual, desired, id) && actual.revision != before.revision) { "NATIVE_NOT_CONFIRMED" }
            return actual
        }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val live = state.getJSONObject("pending")
                when (live.getString("step")) {
                    "PREPARED" -> { nativeUnchanged(); persist("STAGING") }
                    "STAGING" -> {
                        nativeUnchanged()
                        val actual = current()
                        val phase = OfficialDshStorageRecovery.classify(live, actual)
                        val staged = when (phase) {
                            OfficialDshStorageRecovery.State.ORIGINAL -> dsh.stageStorage(live.getJSONObject("dshStorage").getJSONObject("before"))
                            OfficialDshStorageRecovery.State.STAGED -> actual
                            else -> error("DSH_CHANGED")
                        }
                        OfficialDshStorageProof.validateSnapshot(staged, candidate)
                        val checked = JSONObject(live.toString()).put("stagedRevision", staged.getString("credentialRevision"))
                        checked.getJSONObject("dshStorage").put("staged", JSONObject(staged.toString()))
                        OfficialDshStorageProof.validatePending(checked)
                        check(OfficialCredentialJournalCodec.same(staged, current())) { "STAGE_NOT_CONFIRMED" }
                        persist("STAGED") {
                            it.put("stagedRevision", staged.getString("credentialRevision"))
                            it.getJSONObject("dshStorage").put("staged", JSONObject(staged.toString()))
                        }
                    }
                    "STAGED" -> {
                        nativeUnchanged()
                        check(OfficialCredentialJournalCodec.same(current(), live.getJSONObject("dshStorage").getJSONObject("staged"))) { "DSH_CHANGED" }
                        persist("SETTINGS_SUBMITTING")
                    }
                    "SETTINGS_SUBMITTING" -> {
                        val actual = current()
                        if (OfficialDshStorageRecovery.classify(live, actual) != OfficialDshStorageRecovery.State.SELECTED) {
                            nativeUnchanged()
                            val staged = live.getJSONObject("dshStorage").getJSONObject("staged")
                            check(OfficialCredentialJournalCodec.same(actual, staged)) { "DSH_CHANGED" }
                            val selected = dsh.selectStorage(staged)
                            check(OfficialDshStorageRecovery.classify(live, selected) == OfficialDshStorageRecovery.State.SELECTED) { "SETTINGS_NOT_CONFIRMED" }
                        }
                        requireSelected()
                        persist("DSH_CONFIRMED")
                    }
                    "DSH_CONFIRMED" -> { requireSelected(); persist("PROVIDER_SUBMITTING") }
                    "PROVIDER_SUBMITTING" -> { val result = commit(providers, providerBefore); persist("PROVIDER_CONFIRMED") { it.put("providerCommitted", result.revision) } }
                    "PROVIDER_CONFIRMED" -> { requireSelected(); check(owned(providers.read(), desired, id)) { "NATIVE_CHANGED" }; persist("BALANCE_SUBMITTING") }
                    "BALANCE_SUBMITTING" -> {
                        check(owned(providers.read(), desired, id)) { "NATIVE_CHANGED" }
                        val result = commit(balance, balanceBefore)
                        persist("BALANCE_CONFIRMED") { it.put("balanceCommitted", result.revision) }
                    }
                    "BALANCE_CONFIRMED" -> { requireSelected(); persist("COMMITTING") }
                    "COMMITTING" -> {
                        val selected = requireSelected()
                        val provider = providers.read(); val nativeBalance = balance.read()
                        check(owned(provider, desired, id) && owned(nativeBalance, desired, id)) { "NATIVE_CHANGED" }
                        providers.confirmProjection(desired, id, null)
                        val next = JSONObject(state.toString())
                        val owned = next.getJSONObject("ownedReferences").put(candidate, id)
                        val old = plan.getString("referenceBefore")
                        val retired = next.getJSONArray("retiredOwnedReferences")
                        if (owned.has(old) && old != candidate && (0 until retired.length()).none { retired.getString(it) == old }) retired.put(old)
                        val proof = JSONObject().put("schema", 1).put("realm", "android-dsh-storage-v1")
                        for (field in listOf("bindingDigest", "profileRevision", "launchDigest", "environmentDigest", "runtimePinsDigest")) proof.put(field, selected.getString(field))
                        next.put("receipt", JSONObject().put("id", id).put("reference", candidate).put("namespace", selected.getString("namespace"))
                            .put("providerRevision", provider.revision).put("balanceRevision", nativeBalance.revision)
                            .put("keyRemoved", desired == null).put("oldReferenceRetained", old != candidate).put("dshStorage", proof))
                        next.remove("pending")
                        if (plan.has("deferredId")) {
                            check(next.getJSONObject("deferred").getString("id") == plan.getString("deferredId")) { "INVALID_JOURNAL" }
                            next.remove("deferred")
                        }
                        journal.compareAndSet(state.getLong("revision"), next)
                        return OfficialCredentialOutcome.Committed(id, candidate, old != candidate)
                    }
                    else -> error("INVALID_JOURNAL")
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val known = setOf("INVALID_JOURNAL", "NATIVE_CHANGED", "NATIVE_NOT_CONFIRMED", "DSH_CHANGED", "STAGE_NOT_CONFIRMED", "SETTINGS_NOT_CONFIRMED")
            return OfficialCredentialOutcome.Pending(id, failure.message?.takeIf { it in known } ?: "STORAGE_OR_TRANSPORT_PENDING")
        }
    }

    private fun nullable(value: JSONObject, field: String) = OfficialDeepSeekCredentialTransaction.nullableString(value, field)
    private fun owned(value: OfficialNativeCredentialSnapshot, key: String?, id: String) = value.key == key && value.transactionId == id
    private fun unchanged(actual: OfficialNativeCredentialSnapshot, before: OfficialNativeCredentialSnapshot) {
        check(actual.revision == before.revision && actual.key == before.key && actual.transactionId == before.transactionId &&
            actual.configurationDigest == before.configurationDigest) { "NATIVE_CHANGED" }
    }
    private fun nativeSnapshot(value: JSONObject) = OfficialNativeCredentialSnapshot(value.getString("revision"), nullable(value, "key"), nullable(value, "transactionId"), nullable(value, "configurationDigest"))
}

/** Exactly the persisted raw profile and full launch proof must match. The
 * effective selector alone never establishes ownership of a lost response. */
internal object OfficialDshStorageRecovery {
    enum class State { ORIGINAL, STAGED, SELECTED, CONFLICT }
    fun classify(pending: JSONObject, live: JSONObject): State {
        OfficialDshStorageProof.validatePending(pending)
        val candidate = pending.getString("candidate")
        OfficialDshStorageProof.validateSnapshot(live, candidate)
        val proof = pending.getJSONObject("dshStorage")
        val before = proof.getJSONObject("before")
        if (OfficialCredentialJournalCodec.same(live, before)) return State.ORIGINAL
        val shared = listOf("bindingDigest", "namespace", "launchInputs", "launchDigest", "environmentDigest", "runtimePinsDigest", "credentialsExceptCandidateRevision")
        if (shared.any { !OfficialCredentialJournalCodec.same(live.get(it), before.get(it)) } ||
            live.getBoolean("candidatePresent") != !pending.isNull("desired") ||
            !OfficialCredentialJournalCodec.same(live.get("candidateKey"), pending.get("desired"))) return State.CONFLICT
        val stage = listOf("profileRevision", "plannedSelectedProfileRevision", "rawProfileInputs", "plannedProfileInputs", "compositionDigest", "plannedCompositionDigest",
            "effectiveReference", "userReference", "candidateMentioned", "selectedKey")
        if (stage.all { OfficialCredentialJournalCodec.same(live.get(it), before.get(it)) }) {
            return if (pending.getString("step") == "STAGING" || proof.has("staged") && OfficialCredentialJournalCodec.same(live, proof.getJSONObject("staged"))) State.STAGED else State.CONFLICT
        }
        if (!proof.has("staged") || pending.getString("step") !in setOf("SETTINGS_SUBMITTING", "DSH_CONFIRMED", "PROVIDER_SUBMITTING", "PROVIDER_CONFIRMED", "BALANCE_SUBMITTING", "BALANCE_CONFIRMED", "COMMITTING")) return State.CONFLICT
        val staged = proof.getJSONObject("staged")
        return if (live.getString("profileRevision") == before.getString("plannedSelectedProfileRevision") &&
            OfficialCredentialJournalCodec.same(live.get("rawProfileInputs"), before.get("plannedProfileInputs")) &&
            live.getString("compositionDigest") == before.getString("plannedCompositionDigest") &&
            live.getString("credentialRevision") == staged.getString("credentialRevision") && live.getString("credentialRevision") == pending.getString("stagedRevision") &&
            live.getString("effectiveReference") == candidate && live.optString("userReference") == candidate &&
            OfficialCredentialJournalCodec.same(live.get("selectedKey"), pending.get("desired"))) State.SELECTED else State.CONFLICT
    }
}
