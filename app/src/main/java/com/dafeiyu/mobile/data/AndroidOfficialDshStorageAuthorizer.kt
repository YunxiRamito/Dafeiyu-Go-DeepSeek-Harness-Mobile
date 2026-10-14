package com.dafeiyu.mobile.data

import android.content.Context
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Candidate integration boundary. This class deliberately constructs the
 * concrete AndroidKeyStore/AtomicFile reader. An imported backup, interface
 * implementation or caller-supplied plaintext cannot authorize a helper write.
 *
 * The transaction coordinator must keep its existing transactionMutex across
 * issue -> helper challenge -> result journal CAS. It must also hold the REAL
 * exclusive LinuxManagedProcess lease. approveCurrent() does not replace that
 * process/slot check; both checks run for every lock-held helper challenge.
 *
 * Integration prerequisite: validatedJournal explicitly allows dshStorage and
 * calls OfficialDshStorageProof.validatePending(); old proof-less pending plans
 * remain readable but cannot authorize this route or be adopted after restart.
 */
internal class AndroidOfficialDshStorageAuthorizer(context: Context, private val scopeId: String) {
    private val journal = AndroidOfficialCredentialJournal(context.applicationContext)
    private val ticketOwner = Any()

    init { require(scopeId.matches(Regex("[a-f0-9]{64}"))) }

    internal class Ticket private constructor(
        private val owner: Any,
        val operation: String,
        val candidate: String,
        val revision: Long,
        val journalDigest: String,
        private val value: JSONObject,
    ) {
        fun projection(): JSONObject = JSONObject(value.toString())
        internal fun ownedBy(candidateOwner: Any): Boolean = owner === candidateOwner

        companion object {
            internal fun create(owner: Any, operation: String, candidate: String, revision: Long, journalDigest: String, value: JSONObject) =
                Ticket(owner, operation, candidate, revision, journalDigest, JSONObject(value.toString()))
        }
    }

    /** Called only by the local coordinator, never by .dym deserialization.
     * Full journal bytes stay local; a small authenticated pipe projection
     * avoids forwarding an unrelated 8 MiB portable provider configuration. */
    fun issue(operation: String, expected: JSONObject): Ticket {
        require(operation in setOf("stage", "select")) { "HOST_UNAVAILABLE" }
        val state = journal.read() // actual authenticated local AES-GCM read
        val pending = state.getJSONObject("pending")
        OfficialDshStorageProof.validatePending(pending)
        val requiredStep = if (operation == "stage") "STAGING" else "SETTINGS_SUBMITTING"
        require(pending.getString("step") == requiredStep) { "INVALID_JOURNAL" }
        val storage = pending.getJSONObject("dshStorage")
        val wanted = storage.getJSONObject(if (operation == "stage") "before" else "staged")
        require(OfficialCredentialJournalCodec.same(expected, wanted)) { "DSH_CHANGED" }
        val revision = state.getLong("revision")
        require(revision > 0 && revision <= 9_007_199_254_740_991L) { "INVALID_JOURNAL" }
        val hash = digest(state)
        val projectionPending = JSONObject().put("id", pending.getString("id")).put("step", pending.getString("step"))
            .put("candidate", pending.getString("candidate")).put("desired", pending.get("desired"))
            .put("dshStorage", JSONObject(storage.toString()))
        if (pending.has("stagedRevision")) projectionPending.put("stagedRevision", pending.getString("stagedRevision"))
        val projection = JSONObject().put("kind", "android-local-journal-projection").put("schema", 1).put("scopeId", scopeId)
            .put("revision", revision).put("journalDigest", hash).put("pending", projectionPending)
        return Ticket.create(ticketOwner, operation, pending.getString("candidate"), revision, hash, projection)
    }

    /** Preparing a complete before snapshot also requires a concrete local
     * read ticket and the actual exclusive helper lease. No null-ticket read
     * bypass is required before a pending DSH plan has been persisted. */
    fun issueRead(candidate: String): Ticket {
        require(candidate.matches(Regex("DAFEIYU_PROVIDER_DEEPSEEK_[a-f0-9]{32}"))) { "INVALID_JOURNAL" }
        val state = journal.read()
        if (state.has("pending")) {
            val pending = state.getJSONObject("pending")
            require(!pending.has("kind") && pending.getString("candidate") == candidate) { "INVALID_JOURNAL" }
            // Recovery may inspect a legacy pending plan to report the lack of
            // proof. It may never issue stage/select authority from that plan.
        }
        val revision = state.getLong("revision")
        require(revision in 0..9_007_199_254_740_991L) { "INVALID_JOURNAL" }
        val hash = digest(state)
        val projection = JSONObject().put("kind", "android-local-journal-read").put("schema", 1).put("scopeId", scopeId)
            .put("revision", revision).put("journalDigest", hash).put("candidate", candidate)
        return Ticket.create(ticketOwner, "read", candidate, revision, hash, projection)
    }

    /** Called while the helper holds the actual fixed SDK storage lock. The
     * caller also invokes requireExclusiveManagedLease(helper, actualMounts)
     * and withCurrentSlot checks before sending any positive pipe approval. */
    fun approveCurrent(ticket: Ticket): Boolean = try {
        require(ticket.ownedBy(ticketOwner)) { "HOST_UNAVAILABLE" }
        val state = journal.read()
        if (state.getLong("revision") != ticket.revision || digest(state) != ticket.journalDigest) false
        else if (ticket.operation == "read") true
        else {
            val pending = state.getJSONObject("pending")
            OfficialDshStorageProof.validatePending(pending)
            pending.getString("candidate") == ticket.candidate &&
                pending.getString("step") == if (ticket.operation == "stage") "STAGING" else "SETTINGS_SUBMITTING"
        }
    } catch (_: Exception) { false }

    private fun digest(value: JSONObject): String {
        val clear = value.toString().toByteArray(Charsets.UTF_8)
        return try { MessageDigest.getInstance("SHA-256").digest(clear).joinToString("") { "%02x".format(it) } }
        finally { clear.fill(0) }
    }
}

/** Bounded structural validation used by the AES-GCM codec integration.
 * The helper recomputes these complete disk proofs before each mutation. */
internal object OfficialDshStorageProof {
    private const val REALM = "android-dsh-storage-v1"
    private val hash = Regex("[a-f0-9]{64}")
    private val ref = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val snapshotKeys = setOf("schema", "realm", "bindingDigest", "namespace", "profileRevision", "plannedSelectedProfileRevision",
        "rawProfileInputs", "plannedProfileInputs", "compositionDigest", "plannedCompositionDigest", "launchInputs", "launchDigest",
        "environmentDigest", "runtimePinsDigest", "credentialRevision", "credentialsExceptCandidateRevision", "selectedKey",
        "candidatePresent", "candidateKey", "effectiveReference", "userReference", "candidateReference", "candidateMentioned")
    private val sameProfileKeys = setOf("bindingDigest", "namespace", "profileRevision", "plannedSelectedProfileRevision", "rawProfileInputs",
        "plannedProfileInputs", "compositionDigest", "plannedCompositionDigest", "launchInputs", "launchDigest", "environmentDigest",
        "runtimePinsDigest", "effectiveReference", "userReference", "candidateMentioned")

    fun validatePending(pending: JSONObject) {
        require(!pending.has("kind") && pending.getString("id").matches(Regex("[a-f0-9]{32}")) &&
            pending.getString("candidate") == "DAFEIYU_PROVIDER_DEEPSEEK_${pending.getString("id")}") { "INVALID_JOURNAL" }
        val storage = pending.getJSONObject("dshStorage")
        val required = setOf("schema", "realm", "bindingDigest", "before")
        require(required.all(storage::has) && storage.keys().asSequence().all { it in required || it in setOf("staged", "launchPlan") } && storage.getInt("schema") == 1 &&
            storage.getString("realm") == REALM && storage.getString("bindingDigest").matches(hash)) { "INVALID_JOURNAL" }
        if (storage.has("launchPlan")) {
            val launch = storage.getJSONObject("launchPlan")
            require(launch.keys().asSequence().toSet() == setOf("schema", "port", "cwd") && launch.getInt("schema") == 1 &&
                launch.getLong("port") in 1024..65535 &&
                OfficialCredentialJournalCodec.same(launch.get("port"), launch.getLong("port"))) { "INVALID_JOURNAL" }
            val cwd = launch.getString("cwd")
            require(cwd == "/root" || cwd.matches(Regex("/dafeiyu-workspaces(?:/[A-Za-z0-9._-]+)*")) &&
                cwd.split('/').none { it in setOf(".", "..") }) { "INVALID_JOURNAL" }
        }
        val before = validateSnapshot(storage.getJSONObject("before"), pending.getString("candidate"))
        require(before.getString("bindingDigest") == storage.getString("bindingDigest") &&
            before.getString("namespace") == pending.getString("namespace") &&
            before.getString("credentialRevision") == pending.getString("credentialBefore") &&
            !before.getBoolean("candidatePresent") && !before.getBoolean("candidateMentioned") && before.isNull("candidateKey")) { "INVALID_JOURNAL" }
        if (storage.has("staged")) {
            val staged = validateSnapshot(storage.getJSONObject("staged"), pending.getString("candidate"))
            require(sameProfileKeys.all { OfficialCredentialJournalCodec.same(before.get(it), staged.get(it)) } &&
                before.getString("credentialsExceptCandidateRevision") == staged.getString("credentialsExceptCandidateRevision") &&
                OfficialCredentialJournalCodec.same(before.get("selectedKey"), staged.get("selectedKey")) &&
                staged.getBoolean("candidatePresent") == !pending.isNull("desired") &&
                OfficialCredentialJournalCodec.same(staged.get("candidateKey"), pending.get("desired")) &&
                pending.getString("stagedRevision") == staged.getString("credentialRevision")) { "INVALID_JOURNAL" }
        }
        if (pending.getString("step") !in setOf("PREPARED", "STAGING")) require(storage.has("staged")) { "INVALID_JOURNAL" }
    }

    fun validateSnapshot(value: JSONObject, candidate: String): JSONObject {
        require(value.keys().asSequence().toSet() == snapshotKeys && value.getInt("schema") == 1 && value.getString("realm") == REALM &&
            value.getString("candidateReference") == candidate && value.getString("namespace") in setOf("llm-deepseek", "llm-deepseek-api-key")) { "INVALID_JOURNAL" }
        for (field in setOf("bindingDigest", "profileRevision", "plannedSelectedProfileRevision", "compositionDigest", "plannedCompositionDigest",
            "launchDigest", "environmentDigest", "runtimePinsDigest", "credentialRevision", "credentialsExceptCandidateRevision"))
            require(value.getString(field).matches(hash)) { "INVALID_JOURNAL" }
        require(value.get("candidatePresent") is Boolean && value.get("candidateMentioned") is Boolean &&
            value.getString("effectiveReference").matches(ref)) { "INVALID_JOURNAL" }
        if (!value.isNull("userReference")) require(value.getString("userReference").matches(ref)) { "INVALID_JOURNAL" }
        for (field in listOf("selectedKey", "candidateKey")) if (!value.isNull(field)) {
            val key = value.getString(field)
            require(key.length in 1..512 && key.none { it.isWhitespace() || it.isISOControl() }) { "INVALID_JOURNAL" }
        }
        inputs(value.getJSONArray("rawProfileInputs")); inputs(value.getJSONArray("plannedProfileInputs")); inputs(value.getJSONArray("launchInputs"))
        require(value.toString().toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "INVALID_JOURNAL" }
        return value
    }

    private fun inputs(values: JSONArray) {
        require(values.length() in 1..512) { "INVALID_JOURNAL" }
        for (index in 0 until values.length()) {
            val value = values.getJSONObject(index)
            require(value.keys().asSequence().toSet() == setOf("kind", "file", "bytes", "sha256") &&
                value.getString("kind") in setOf("file", "link", "mobile-ui", "launch-code", "dependency-lock", "provider-order", "project-env", "user-env") &&
                value.getString("file").length in 1..1024 && value.getString("file").none { it.isISOControl() }) { "INVALID_JOURNAL" }
            if (value.isNull("bytes")) require(value.isNull("sha256") && value.getString("kind") != "link") { "INVALID_JOURNAL" }
            else require(value.getLong("bytes") in 0..8L * 1024 * 1024 && value.getString("sha256").matches(hash)) { "INVALID_JOURNAL" }
        }
    }
}
