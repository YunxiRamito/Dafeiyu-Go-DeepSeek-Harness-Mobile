package com.dafeiyu.mobile.data

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

private const val OLD_KEY = "SYNTHETIC_OLD_KEY"
private const val NEW_KEY = "SYNTHETIC_NEW_KEY"
private const val THIRD_KEY = "SYNTHETIC_THIRD_KEY"
private const val ID = "11111111111111111111111111111111"
private const val REF = "DAFEIYU_PROVIDER_DEEPSEEK_$ID"
private fun copy(value: JSONObject) = JSONObject(value.toString())
private fun verify(condition: Boolean, name: String) { check(condition) { "CHECK_FAILED: $name" } }
private class SyntheticHooks {
    val once = mutableMapOf<String, () -> Unit>()
    val events = mutableListOf<String>()
    fun hit(name: String) { events += name; once.remove(name)?.invoke() }
    fun fail(name: String) { once[name] = { throw IllegalStateException("SYNTHETIC_STORAGE_FAILURE") } }
    fun cancel(name: String) { once[name] = { throw CancellationException("SYNTHETIC_CANCEL") } }
}

/** Uses the candidate production AES-GCM codec with a synthetic JVM key.
 * NIO file persistence is a JVM fixture, not Android AtomicFile/Keystore proof. */
private class FileJournal(private val directory: File, private val hooks: SyntheticHooks) : OfficialCredentialJournalPort {
    val file = File(directory, "journal.enc")
    private val key = SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES")
    override val transactionMutex = locks.computeIfAbsent(file.canonicalPath) { Mutex() }
    override fun read(): JSONObject = if (!file.isFile) OfficialDeepSeekCredentialTransaction.emptyJournal()
        else OfficialCredentialJournalCodec.open(file.readBytes(), key)
    override fun compareAndSet(expectedRevision: Long, replacement: JSONObject): JSONObject {
        check(read().getLong("revision") == expectedRevision && replacement.getLong("revision") == expectedRevision) { "NATIVE_CAS_CONFLICT" }
        val phase = replacement.optJSONObject("pending")?.getString("step") ?: "FINISHED"
        hooks.hit("journal.before.$phase")
        val next = copy(replacement).put("revision", Math.addExact(expectedRevision, 1))
        val sealed = OfficialCredentialJournalCodec.seal(next, key)
        val staged = File(directory, "journal.new")
        requireNotNull(staged.parentFile).mkdirs()
        FileChannel.open(staged.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use {
            val bytes = java.nio.ByteBuffer.wrap(sealed)
            while (bytes.hasRemaining()) it.write(bytes)
            it.force(true)
        }
        Files.move(staged.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        sealed.fill(0)
        hooks.hit("journal.after.$phase")
        return read().also { check(OfficialCredentialJournalCodec.same(next, it)) { "INVALID_JOURNAL" } }
    }
    companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}

private class NativeVault(private val label: String, initial: String?, private val hooks: SyntheticHooks) : OfficialNativeCredentialPort {
    var currentKey = initial
    var marker: String? = null
    var revision = 8L
    var writes = 0
    var configuration: JSONObject? = null
    override suspend fun read() = OfficialNativeCredentialSnapshot(revision.toString(), currentKey, marker,
        configuration?.let(OfficialCredentialPortable::digest))
    override suspend fun compareAndSet(expectedRevision: String, key: String?, transactionId: String) {
        hooks.hit("$label.before")
        check(revision.toString() == expectedRevision) { "NATIVE_CAS_CONFLICT" }
        currentKey = key; marker = transactionId; revision++; writes++
        configuration?.getJSONObject("credentials")?.let { credentials ->
            if (key == null) credentials.remove("deepseek") else credentials.put("deepseek", JSONObject().put("type", "api_key").put("key", key))
        }
        hooks.hit("$label.after")
    }
    override suspend fun compareAndSetPortable(expectedRevision: String, configuration: JSONObject, transactionId: String) {
        hooks.hit("$label.before")
        check(revision.toString() == expectedRevision) { "NATIVE_CAS_CONFLICT" }
        this.configuration = OfficialCredentialPortable.sanitize(configuration)
        currentKey = OfficialCredentialPortable.key(configuration); marker = transactionId; revision++; writes++
        hooks.hit("$label.after")
    }
    override suspend fun confirmProjection(key: String?, transactionId: String, configurationDigest: String?) {
        hooks.hit("$label.projection")
        verify(currentKey == key && marker == transactionId &&
            (configurationDigest == null || configuration?.let(OfficialCredentialPortable::digest) == configurationDigest), "actual projection owner")
    }
    fun externalEdit(key: String?) { currentKey = key; revision++; marker = null }
}

private class SyntheticDsh(initial: String?, private val hooks: SyntheticHooks) : OfficialDshCredentialPort {
    var settingsRevision = 7L
    var userReference: String? = null
    var activeReference = "DEEPSEEK_API_KEY"
    val refs = JSONObject().also { if (initial != null) it.put(activeReference, initial) }
    val records = JSONObject().put("llm-pi-ai/other", JSONObject().put("kind", "api-key").put("key", "SYNTHETIC_UNRELATED"))
    var stageCalls = 0
    var settingsCalls = 0
    var namespaces = 1
    var writable = true
    var invalidAck = false
    var ephemeralAck = false
    var externalMention: String? = null
    var available = true
    var providerSettingsRevision = 12L
    private fun descriptor(): JSONObject = JSONObject().put("ns", "llm-deepseek-api-key").put("revision", settingsRevision)
        .put("value", JSONObject().put("apiKeyEnv", activeReference).put("baseURL", "https://example.invalid/v1").put("thinking", "enabled"))
        .put("user", JSONObject().put("thinking", "enabled").also { if (userReference != null) it.put("apiKeyEnv", userReference) })
    override suspend fun describe(): JSONObject {
        check(available) { "SYNTHETIC_DSH_OFFLINE" }
        hooks.hit("dsh.describe")
        val values = JSONArray()
        repeat(namespaces) { values.put(descriptor()) }
        values.put(JSONObject().put("ns", "llm-pi-ai").put("revision", providerSettingsRevision)
            .put("value", JSONObject().put("providers", JSONObject())).put("user", JSONObject()))
        values.put(JSONObject().put("ns", "unrelated-plugin").put("value", JSONObject().put("reference", externalMention ?: "FOREIGN_KEEP")))
        return JSONObject().put("writable", writable).put("namespaces", values)
    }
    private fun credentialRevision(): String = MessageDigest.getInstance("SHA-256").digest((refs.toString() + records.toString()).toByteArray())
        .joinToString("") { "%02x".format(it) }
    override suspend fun credentials(): JSONObject {
        check(available) { "SYNTHETIC_DSH_OFFLINE" }
        hooks.hit("dsh.credentials")
        return JSONObject().put("version", 1).put("revision", credentialRevision())
            .put("refs", copy(refs)).put("records", copy(records)).put("portableFiles", JSONObject())
    }
    override suspend fun stageReferences(candidates: JSONArray, expectedRevision: String): JSONObject {
        hooks.hit("stage.before")
        check(credentialRevision() == expectedRevision) { "CREDENTIAL_CAS_CONFLICT" }
        verify(candidates.length() == 1, "one fresh reference")
        val item = candidates.getJSONObject(0)
        check(!refs.has(item.getString("ref"))) { "REFERENCE_COLLISION" }
        refs.put(item.getString("ref"), item.getString("value")); stageCalls++
        hooks.hit("stage.after")
        return JSONObject().put("revision", credentialRevision()).put("applied", 1)
    }
    override suspend fun mutate(namespace: String, expectedRevision: Long, reference: String): JSONObject {
        hooks.hit("settings.before")
        check(namespace == "llm-deepseek-api-key" && expectedRevision == settingsRevision) { "SETTINGS_CAS_CONFLICT" }
        settingsCalls++
        if (ephemeralAck) return descriptor().put("revision", settingsRevision + 1)
            .put("user", JSONObject().put("apiKeyEnv", reference)).put("value", JSONObject().put("apiKeyEnv", reference))
        settingsRevision++; userReference = reference; activeReference = reference
        hooks.hit("settings.after")
        return if (invalidAck) descriptor().put("ns", "foreign") else descriptor()
    }
}

private class Scenario(val root: File, initial: String? = OLD_KEY) {
    val hooks = SyntheticHooks()
    val provider = NativeVault("provider", initial, hooks)
    val balance = NativeVault("balance", initial, hooks)
    val dsh = SyntheticDsh(initial, hooks)
    val journal get() = FileJournal(root, hooks)
    fun coordinator(id: String = ID) = OfficialDeepSeekCredentialTransaction(journal, provider, balance, dsh) { id }
    fun oldDshPreserved(initial: String? = OLD_KEY) { verify(OfficialDeepSeekCredentialTransaction.nullableString(dsh.refs, "DEEPSEEK_API_KEY") == initial, "old DSH ref retained") }
    fun nativeOld(initial: String? = OLD_KEY) { verify(provider.currentKey == initial && balance.currentKey == initial, "native old keys retained") }
    fun pending(): JSONObject = journal.read().getJSONObject("pending")
    fun noNativeWrites() { verify(provider.writes == 0 && balance.writes == 0, "native untouched") }
    suspend fun finish(): OfficialCredentialOutcome.Committed {
        val result = coordinator().recover()
        verify(result is OfficialCredentialOutcome.Committed, "recover commits")
        verify(!coordinator().hasPending(), "journal completed")
        return result as OfficialCredentialOutcome.Committed
    }
    suspend fun finishNative(): OfficialCredentialOutcome.NativeCommitted {
        val result = coordinator().recover()
        verify(result is OfficialCredentialOutcome.NativeCommitted && !coordinator().hasPending(), "native recover commits")
        return result as OfficialCredentialOutcome.NativeCommitted
    }
    suspend fun sourceProof(): JSONObject = JSONObject().put("namespace", "llm-deepseek-api-key").put("settingsRevision", dsh.settingsRevision)
        .put("reference", dsh.activeReference).put("providerSettingsRevision", dsh.providerSettingsRevision)
        .put("credentialRevision", dsh.credentials().getString("revision"))
}

private fun portable(key: String?, providerName: String = "Another provider"): JSONObject = JSONObject().put("schema", 1).put("revision", 91)
    .put("providers", JSONArray().put(ModelProviderConfig.DEEPSEEK.toJson()).put(ModelProviderConfig("other", providerName, "openai").toJson()))
    .put("credentials", JSONObject().put("other", JSONObject().put("type", "api_key").put("key", "SYNTHETIC_OTHER")).also {
        if (key != null) it.put("deepseek", JSONObject().put("type", "api_key").put("key", key))
    })

fun main(args: Array<String>) = runBlocking {
    val evidence = File(args.single()).apply { mkdirs() }
    val names = mutableListOf<String>()
    suspend fun test(name: String, body: suspend (Scenario) -> Unit) {
        val scenario = Scenario(File(evidence, "case-${names.size + 1}"))
        body(scenario); names += name; println("PASS $name")
    }
    test("JSON numeric narrowing preserves exact journal revision semantics") { _ ->
        val written = JSONObject().put("revision", 1L).put("nested", JSONArray().put(0L).put(JSONObject().put("count", 7L)))
        val reopened = JSONObject(written.toString())
        verify(OfficialCredentialJournalCodec.same(written, reopened), "Android-style narrowed integers compare by exact value")
        verify(OfficialCredentialJournalCodec.same(1L, 1), "Long and Integer have the same numeric value")
    }
    test("journal equality does not round large revisions through floating point") { _ ->
        val first = 9007199254740992L
        val second = 9007199254740993L
        verify(!OfficialCredentialJournalCodec.same(first, second), "adjacent values beyond Double precision differ")
        val maximum = JSONObject().put("revision", Long.MAX_VALUE)
        verify(OfficialCredentialJournalCodec.same(maximum, JSONObject(maximum.toString())), "maximum Long survives JSON")
        verify(!OfficialCredentialJournalCodec.same(second, first.toDouble()), "rounded Double cannot impersonate revision")
    }
    test("journal equality still rejects changed structure types and non-finite numbers") { _ ->
        verify(!OfficialCredentialJournalCodec.same(1L, "1"), "string is not a number")
        verify(!OfficialCredentialJournalCodec.same(1, true), "Boolean is not a number")
        verify(!OfficialCredentialJournalCodec.same(Double.NaN, Double.NaN), "nonfinite values are refused")
        verify(!OfficialCredentialJournalCodec.same(JSONObject().put("revision", 1L), JSONObject().put("revision", 2)), "changed numeric value differs")
        verify(!OfficialCredentialJournalCodec.same(JSONArray().put(1L), JSONArray().put(1).put(2)), "array shape differs")
    }
    test("authenticated journal ciphertext readback admits narrowed numeric wrappers") { _ ->
        val written = OfficialDeepSeekCredentialTransaction.emptyJournal().put("revision", 1L)
        val key = SecretKeySpec(ByteArray(32) { (it + 9).toByte() }, "AES")
        val ciphertext = OfficialCredentialJournalCodec.seal(written, key)
        try {
            verify(OfficialCredentialJournalCodec.same(written, OfficialCredentialJournalCodec.open(ciphertext, key)), "real AES-GCM exact readback")
        } finally { ciphertext.fill(0) }
    }
    test("add key to both empty native vaults and first-party reference without overwriting defaults") { base ->
        val env = Scenario(base.root, null)
        verify(env.coordinator().begin(NEW_KEY) is OfficialCredentialOutcome.Committed, "add committed")
        verify(env.provider.currentKey == NEW_KEY && env.balance.currentKey == NEW_KEY && env.dsh.activeReference == REF, "all chosen keys match")
        env.oldDshPreserved(null)
    }
    test("replace selects a fresh ref and retains old ref unrelated records and original settings") { env ->
        val unrelated = env.dsh.records.toString()
        verify(env.coordinator().begin(NEW_KEY) is OfficialCredentialOutcome.Committed, "replace committed")
        env.oldDshPreserved(); verify(env.dsh.refs.getString(REF) == NEW_KEY && env.dsh.records.toString() == unrelated, "keys and records")
        verify(env.journal.read().getJSONObject("ownedReferences").getString(REF) == ID, "ownership receipt")
        verify(!env.journal.read().toString().contains(OLD_KEY), "completed journal releases old secrets")
    }
    test("delete uses explicit absent fresh reference and native tombstones without default fallback") { env ->
        verify(env.coordinator().begin(null) is OfficialCredentialOutcome.Committed, "remove committed")
        env.oldDshPreserved(); verify(!env.dsh.refs.has(REF) && env.dsh.activeReference == REF && env.dsh.userReference == REF, "absence selected explicitly")
        verify(env.provider.currentKey == null && env.balance.currentKey == null && env.provider.marker == ID && env.balance.marker == ID, "durable native tombstones")
        verify(env.journal.read().getJSONObject("receipt").getBoolean("keyRemoved"), "removal receipt")
    }
    test("invalid key fails before intent or side effects") { env ->
        val result = env.coordinator().begin("has whitespace")
        verify(result is OfficialCredentialOutcome.Pending && result.code == "INVALID_KEY", "invalid rejected")
        verify(!env.journal.file.exists() && env.dsh.stageCalls == 0, "no intent or staging"); env.noNativeWrites()
    }
    test("existing native vault disagreement refuses to guess an authoritative key") { env ->
        env.balance.externalEdit(THIRD_KEY)
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "NATIVE_VAULT_CONFLICT", "native mismatch")
        verify(!env.journal.file.exists() && env.balance.currentKey == THIRD_KEY, "third-party edit kept"); env.noNativeWrites()
    }
    for (choice in OfficialNativeCredentialChoice.entries) test("explicit legacy native resolution $choice commits both exact vault snapshots without DSH") { env ->
        env.balance.externalEdit(THIRD_KEY)
        val providerRevision = env.provider.read().revision
        val balanceRevision = env.balance.read().revision
        env.dsh.available = false
        val result = env.coordinator().beginNativeResolution(choice, providerRevision, balanceRevision)
        val selected = when (choice) {
            OfficialNativeCredentialChoice.PROVIDER -> OLD_KEY
            OfficialNativeCredentialChoice.BALANCE -> THIRD_KEY
            OfficialNativeCredentialChoice.CLEAR -> null
        }
        verify(result is OfficialCredentialOutcome.NativeCommitted && result.dshPublicationPending, "explicit choice committed")
        verify(env.provider.currentKey == selected && env.balance.currentKey == selected && !env.coordinator().hasPending(), "two native vaults agree")
        verify(env.hooks.events.none { it.startsWith("dsh.") || it.startsWith("stage.") || it.startsWith("settings.") }, "no runtime access")
    }
    for (changed in listOf("provider", "balance")) test("native resolution refuses a stale displayed $changed revision before writing") { env ->
        env.balance.externalEdit(THIRD_KEY)
        val providerRevision = env.provider.read().revision
        val balanceRevision = env.balance.read().revision
        if (changed == "provider") env.provider.externalEdit(NEW_KEY) else env.balance.externalEdit(NEW_KEY)
        val result = env.coordinator().beginNativeResolution(OfficialNativeCredentialChoice.PROVIDER, providerRevision, balanceRevision)
        verify(result is OfficialCredentialOutcome.Pending && result.code == "NATIVE_CAS_CONFLICT", "stale choice rejected")
        verify(!env.journal.file.exists(), "no durable intent or partial mutation"); env.noNativeWrites()
    }
    test("explicit native resolution retains distinct before keys when the second vault fails and recovers offline") { env ->
        env.balance.externalEdit(THIRD_KEY)
        env.dsh.available = false
        env.hooks.fail("balance.before")
        val result = env.coordinator().beginNativeResolution(OfficialNativeCredentialChoice.PROVIDER,
            env.provider.read().revision, env.balance.read().revision)
        verify(result is OfficialCredentialOutcome.Pending && env.coordinator().hasPending(), "recoverable durable intent")
        verify(env.pending().getJSONObject("providerBefore").getString("key") == OLD_KEY &&
            env.pending().getJSONObject("balanceBefore").getString("key") == THIRD_KEY, "distinct before snapshots retained privately")
        env.finishNative()
        verify(env.provider.currentKey == OLD_KEY && env.balance.currentKey == OLD_KEY, "chosen provider restored to both vaults")
    }
    test("native resolution cannot replace an existing completed deferred plan over a later foreign edit") { env ->
        verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.NativeCommitted, "native deferred exists")
        env.balance.externalEdit(THIRD_KEY)
        val before = env.journal.read().toString()
        val result = env.coordinator("22222222222222222222222222222222").beginNativeResolution(OfficialNativeCredentialChoice.PROVIDER,
            env.provider.read().revision, env.balance.read().revision)
        verify(result is OfficialCredentialOutcome.Pending && result.code == "NATIVE_CAS_CONFLICT", "foreign deferred state not guessed")
        verify(env.journal.read().toString() == before && env.balance.currentKey == THIRD_KEY, "prior plan and external value preserved")
    }
    test("different DSH official key is a visible preflight conflict") { env ->
        env.dsh.refs.put("DEEPSEEK_API_KEY", THIRD_KEY)
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "OFFICIAL_KEY_CONFLICT", "DSH mismatch")
        verify(!env.journal.file.exists() && env.dsh.refs.getString("DEEPSEEK_API_KEY") == THIRD_KEY, "no ambiguous overwrite"); env.noNativeWrites()
    }
    test("explicit user selection can resolve a DSH key disagreement while retaining its old reference") { env ->
        env.dsh.refs.put("DEEPSEEK_API_KEY", THIRD_KEY)
        verify(env.coordinator().begin(NEW_KEY, acceptDshConflict = true) is OfficialCredentialOutcome.Committed, "explicit selection commits")
        verify(env.dsh.refs.getString("DEEPSEEK_API_KEY") == THIRD_KEY && env.dsh.refs.getString(REF) == NEW_KEY, "prior DSH value preserved")
        verify(env.provider.currentKey == NEW_KEY && env.balance.currentKey == NEW_KEY, "native choice confirmed")
    }
    test("duplicate official namespaces fail before writing") { env ->
        env.dsh.namespaces = 2
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "OFFICIAL_NAMESPACE_INVALID", "duplicate refused")
        env.oldDshPreserved(); env.noNativeWrites()
    }
    test("read-only DSH settings refuse a new transaction") { env ->
        env.dsh.writable = false
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "DSH_READ_ONLY", "read-only rejected")
        verify(!env.journal.file.exists(), "no intent")
    }
    test("allocated ref collision is not overwritten even with the desired value") { env ->
        env.dsh.refs.put(REF, NEW_KEY)
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "REFERENCE_COLLISION", "collision rejected")
        verify(env.dsh.refs.getString(REF) == NEW_KEY && !env.journal.file.exists(), "existing candidate kept")
    }
    test("durable initial-intent failure performs no credential or settings write") { env ->
        env.hooks.fail("journal.before.PREPARED")
        verify(env.coordinator().begin(NEW_KEY) is OfficialCredentialOutcome.Pending, "write pending")
        verify(env.dsh.stageCalls == 0 && env.dsh.settingsCalls == 0, "no downstream effects"); env.nativeOld(); env.oldDshPreserved()
    }
    test("stage failure before storage retains old keys and can recover from disk") { env ->
        env.hooks.fail("stage.before")
        verify(env.coordinator().begin(NEW_KEY) is OfficialCredentialOutcome.Pending, "stage pending")
        env.nativeOld(); env.oldDshPreserved(); verify(env.pending().getString("step") == "STAGING", "stage intent durable")
        env.finish(); verify(env.dsh.stageCalls == 1, "one successful stage")
    }
    test("lost stage response keeps candidate and recovery does not overwrite or restage it") { env ->
        env.hooks.fail("stage.after")
        verify(env.coordinator().begin(NEW_KEY) is OfficialCredentialOutcome.Pending, "lost response pending")
        verify(env.dsh.refs.getString(REF) == NEW_KEY && env.dsh.settingsCalls == 0, "candidate exists but settings old")
        env.nativeOld(); env.oldDshPreserved(); env.finish(); verify(env.dsh.stageCalls == 1, "candidate reuse")
    }
    test("credential writer CAS conflict preserves old values and an external write") { env ->
        env.hooks.once["stage.before"] = { env.dsh.refs.put("FOREIGN_KEEP", THIRD_KEY) }
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "CREDENTIAL_CAS_CONFLICT", "stale doc revision")
        env.nativeOld(); env.oldDshPreserved(); verify(env.dsh.refs.getString("FOREIGN_KEEP") == THIRD_KEY && !env.dsh.refs.has(REF), "foreign write kept")
    }
    test("deletion observes credential CAS even though no reference is staged") { env ->
        env.hooks.once["journal.after.STAGING"] = { env.dsh.refs.put("FOREIGN_KEEP", THIRD_KEY) }
        verify((env.coordinator().begin(null) as OfficialCredentialOutcome.Pending).code == "CREDENTIAL_CAS_CONFLICT", "delete snapshot stale")
        verify(env.dsh.settingsCalls == 0 && env.dsh.refs.getString("FOREIGN_KEEP") == THIRD_KEY, "delete does not switch settings")
        env.nativeOld(); env.oldDshPreserved(); env.noNativeWrites()
    }
    test("settings CAS conflict retains both refs and never changes native credentials") { env ->
        env.hooks.once["settings.before"] = { env.dsh.settingsRevision++ }
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "SETTINGS_CAS_CONFLICT", "settings conflict")
        env.noNativeWrites(); env.nativeOld(); env.oldDshPreserved(); verify(env.dsh.refs.getString(REF) == NEW_KEY, "candidate retained")
    }
    test("settings response loss recovers from exact user-layer persistence without repeated execution") { env ->
        env.hooks.fail("settings.after")
        verify(env.coordinator().begin(NEW_KEY) is OfficialCredentialOutcome.Pending, "response pending")
        env.noNativeWrites(); env.oldDshPreserved(); verify(env.dsh.activeReference == REF, "DSH committed before loss")
        env.finish(); verify(env.dsh.settingsCalls == 1, "settings mutation not repeated")
    }
    test("an effective or transient acknowledgement alone cannot prove durable settings") { env ->
        env.dsh.ephemeralAck = true
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "SETTINGS_NOT_CONFIRMED", "ephemeral rejected")
        env.noNativeWrites(); env.nativeOld(); env.oldDshPreserved()
    }
    test("mismatched settings acknowledgement is pending until an exact live reread proves commit") { env ->
        env.dsh.invalidAck = true
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "SETTINGS_NOT_CONFIRMED", "bad acknowledgement")
        env.noNativeWrites(); env.finish(); verify(env.dsh.settingsCalls == 1, "live proof avoids duplicate CAS")
    }
    test("native edit between staging and DSH submission is not overwritten") { env ->
        env.hooks.once["journal.after.STAGED"] = { env.provider.externalEdit(THIRD_KEY) }
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "NATIVE_CAS_CONFLICT", "native conflict")
        verify(env.dsh.settingsCalls == 0 && env.provider.currentKey == THIRD_KEY && env.balance.currentKey == OLD_KEY, "external native value preserved"); env.oldDshPreserved()
    }
    test("native edit after DSH CAS leaves the journal and both DSH refs for manual resolution") { env ->
        env.hooks.once["settings.after"] = { env.provider.externalEdit(THIRD_KEY) }
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "NATIVE_CAS_CONFLICT", "post-CAS conflict")
        verify(env.provider.currentKey == THIRD_KEY && env.balance.currentKey == OLD_KEY && env.dsh.refs.getString(REF) == NEW_KEY, "all values retained")
        verify(env.coordinator().hasPending(), "execution recovery gate required"); env.oldDshPreserved()
    }
    test("provider vault response loss is resolved by transaction ownership not a repeated write") { env ->
        env.hooks.fail("provider.after")
        verify(env.coordinator().begin(NEW_KEY) is OfficialCredentialOutcome.Pending, "provider response pending")
        verify(env.provider.currentKey == NEW_KEY && env.balance.currentKey == OLD_KEY, "half native state explicit")
        verify(env.pending().getJSONObject("providerBefore").getString("key") == OLD_KEY, "old provider key in encrypted journal")
        env.finish(); verify(env.provider.writes == 1 && env.balance.writes == 1, "one CAS each")
    }
    test("balance vault failure retains previous and desired keys until recovery") { env ->
        env.hooks.fail("balance.before")
        verify(env.coordinator().begin(NEW_KEY) is OfficialCredentialOutcome.Pending, "balance pending")
        verify(env.provider.currentKey == NEW_KEY && env.balance.currentKey == OLD_KEY, "separate commit boundary")
        verify(env.pending().getString("desired") == NEW_KEY && env.pending().getJSONObject("balanceBefore").getString("key") == OLD_KEY, "both values recoverable")
        env.finish()
    }
    test("lost balance response uses the durable tombstone ownership marker") { env ->
        env.hooks.fail("balance.after")
        verify(env.coordinator().begin(null) is OfficialCredentialOutcome.Pending, "lost deletion response")
        verify(env.balance.currentKey == null && env.balance.marker == ID, "tombstone exists")
        env.finish(); verify(env.balance.writes == 1, "no repeated tombstone commit"); env.oldDshPreserved()
    }
    test("external balance edit after provider CAS is retained and prevents false success") { env ->
        env.hooks.once["balance.before"] = { env.balance.externalEdit(THIRD_KEY) }
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "NATIVE_CAS_CONFLICT", "balance stale revision")
        verify(env.balance.currentKey == THIRD_KEY && env.provider.currentKey == NEW_KEY && env.coordinator().hasPending(), "external value and journal survive")
    }
    test("final journal commit failure leaves completed vault evidence recoverable") { env ->
        env.hooks.fail("journal.before.FINISHED")
        verify(env.coordinator().begin(NEW_KEY) is OfficialCredentialOutcome.Pending, "completion pending")
        verify(env.provider.currentKey == NEW_KEY && env.balance.currentKey == NEW_KEY && env.coordinator().hasPending(), "evidence survives")
        env.finish(); verify(env.provider.writes == 1 && env.balance.writes == 1 && env.dsh.settingsCalls == 1, "no duplicate effects")
    }
    test("lost final journal acknowledgement preserves a completed receipt on restart") { env ->
        env.hooks.fail("journal.after.FINISHED")
        verify(env.coordinator().begin(NEW_KEY) is OfficialCredentialOutcome.Pending, "caller saw ambiguous outcome")
        verify(env.coordinator().recover() == OfficialCredentialOutcome.NoPending && env.journal.read().getJSONObject("receipt").getString("id") == ID,
            "persisted completed receipt, no new transaction")
    }
    test("candidate tampering after DSH CAS cannot replace native keys") { env ->
        env.hooks.once["settings.after"] = { env.dsh.refs.put(REF, THIRD_KEY) }
        verify((env.coordinator().begin(NEW_KEY) as OfficialCredentialOutcome.Pending).code == "CANDIDATE_CHANGED", "tampered reference")
        env.noNativeWrites(); env.nativeOld(); env.oldDshPreserved(); verify(env.dsh.refs.getString(REF) == THIRD_KEY, "foreign candidate not overwritten")
    }
    test("referenced old key is preserved in another settings namespace") { env ->
        env.dsh.externalMention = "DEEPSEEK_API_KEY"
        verify(env.coordinator().begin(null) is OfficialCredentialOutcome.Committed, "remove chosen key")
        env.oldDshPreserved(); verify(env.dsh.externalMention == "DEEPSEEK_API_KEY", "other plugin intact")
    }
    test("a second transaction cannot overwrite an unresolved pending intent") { env ->
        env.hooks.fail("settings.after")
        env.coordinator().begin(NEW_KEY)
        val before = env.journal.read().toString()
        verify((env.coordinator("22222222222222222222222222222222").begin(THIRD_KEY) as OfficialCredentialOutcome.Pending).code == "PENDING_EXISTS", "one active transaction")
        verify(env.journal.read().toString() == before, "journal not replaced")
        env.finish()
    }
    test("authenticated encrypted intent survives coordinator recreation without plaintext secrets") { env ->
        env.hooks.fail("balance.before"); env.coordinator().begin(NEW_KEY)
        val bytes = env.journal.file.readBytes().toString(Charsets.ISO_8859_1)
        verify(!bytes.contains(OLD_KEY) && !bytes.contains(NEW_KEY), "encrypted on disk")
        env.finish(); verify(!env.journal.read().toString().contains(OLD_KEY), "old secret removed only at durable completion")
    }
    test("corrupt encrypted journal fails closed and preserves original bytes") { env ->
        env.hooks.fail("settings.before"); env.coordinator().begin(NEW_KEY)
        val bytes = env.journal.file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); env.journal.file.writeBytes(bytes)
        verify((env.coordinator().recover() as OfficialCredentialOutcome.Pending).code == "INVALID_JOURNAL", "corruption not a fresh journal")
        verify(env.journal.file.readBytes().contentEquals(bytes), "corrupt evidence retained"); env.nativeOld(); env.oldDshPreserved()
    }
    test("missing desired-key field and invalid receipt types cannot become an implicit deletion") { env ->
        env.hooks.fail("settings.before"); env.coordinator().begin(NEW_KEY)
        val pending = env.journal.read()
        pending.getJSONObject("pending").remove("desired")
        var rejected = false
        try { OfficialDeepSeekCredentialTransaction.validatedJournal(pending) } catch (_: Exception) { rejected = true }
        verify(rejected, "missing intent rejected")
        rejected = false
        try { OfficialDeepSeekCredentialTransaction.validatedJournal(OfficialDeepSeekCredentialTransaction.emptyJournal().put("receipt", "bad")) }
        catch (_: Exception) { rejected = true }
        verify(rejected, "receipt type rejected")
        env.nativeOld(); env.oldDshPreserved()
    }
    test("native snapshots redact credentials from accidental toString diagnostics") { env ->
        verify(!env.provider.read().toString().contains(OLD_KEY), "snapshot redacted")
    }
    val cancellationPoints = listOf("journal.after.PREPARED", "stage.after", "settings.after", "provider.after", "balance.after", "journal.after.COMMITTING")
    for (point in cancellationPoints) test("cancellation at $point keeps a durable recovery boundary") { env ->
        env.hooks.cancel(point)
        var cancelled = false
        try { env.coordinator().begin(NEW_KEY) } catch (_: CancellationException) { cancelled = true }
        verify(cancelled && env.coordinator().hasPending(), "cancellation propagated with pending intent")
        env.oldDshPreserved(); env.finish()
        verify(env.provider.currentKey == NEW_KEY && env.balance.currentKey == NEW_KEY, "recovery converged")
    }
    test("only receipt-owned retired references enter deferred cleanup without deleting any key") { env ->
        verify(env.coordinator().begin(NEW_KEY) is OfficialCredentialOutcome.Committed, "first rotation")
        val secondId = "22222222222222222222222222222222"
        verify(env.coordinator(secondId).begin(THIRD_KEY) is OfficialCredentialOutcome.Committed, "second rotation")
        val state = env.journal.read()
        verify(state.getJSONArray("retiredOwnedReferences").getString(0) == REF, "owned retirement recorded")
        verify(env.dsh.refs.getString(REF) == NEW_KEY && env.dsh.refs.getString("DEEPSEEK_API_KEY") == OLD_KEY, "both old references kept")
    }
    for (desired in listOf(NEW_KEY, null)) test("native-only ${if (desired == null) "delete" else "save"} commits with DSH offline without touching runtime") { env ->
        env.dsh.available = false
        val result = env.coordinator().beginNative(desired)
        verify(result is OfficialCredentialOutcome.NativeCommitted && result.dshPublicationPending, "native is available")
        verify(env.provider.currentKey == desired && env.balance.currentKey == desired && !env.coordinator().hasPending(), "vaults committed")
        verify(env.coordinator().hasDeferredPublication() && env.hooks.events.none { it.startsWith("dsh.") || it.startsWith("stage.") || it.startsWith("settings.") }, "no runtime access")
    }
    test("native-only first-use does not require installed DSH or a previous key") { base ->
        val env = Scenario(base.root, null)
        env.dsh.available = false
        verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.NativeCommitted, "native first-use")
        verify(env.provider.currentKey == NEW_KEY && env.balance.currentKey == NEW_KEY, "both stores")
    }
    for (boundary in listOf("journal.after.NATIVE_PREPARED", "provider.before", "provider.after", "journal.after.NATIVE_PROVIDER_CONFIRMED",
        "balance.before", "balance.after", "journal.before.NATIVE_COMMITTING", "journal.before.FINISHED")) {
        test("native-only failure at $boundary keeps a recoverable encrypted intent") { env ->
            env.dsh.available = false
            env.hooks.fail(boundary)
            verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.Pending && env.coordinator().hasPending(), "failure pending")
            env.finishNative()
            verify(env.provider.currentKey == NEW_KEY && env.balance.currentKey == NEW_KEY && env.coordinator().hasDeferredPublication(), "fresh coordinator native recovery")
        }
    }
    for (boundary in listOf("journal.after.NATIVE_PREPARED", "provider.after", "balance.after", "journal.before.FINISHED")) {
        test("native-only cancellation at $boundary propagates and resumes without DSH") { env ->
            env.dsh.available = false
            env.hooks.cancel(boundary)
            var cancelled = false
            try { env.coordinator().beginNative(NEW_KEY) } catch (_: CancellationException) { cancelled = true }
            verify(cancelled && env.coordinator().hasPending(), "cancellation retains intent")
            env.finishNative()
            verify(env.provider.currentKey == NEW_KEY && env.balance.currentKey == NEW_KEY, "cancellation recovered")
        }
    }
    test("offline edits retain the earliest destination baseline and latest desired value") { env ->
        env.dsh.available = false
        verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.NativeCommitted, "first edit")
        val secondId = "22222222222222222222222222222222"
        verify(env.coordinator(secondId).beginNative(THIRD_KEY) is OfficialCredentialOutcome.NativeCommitted, "second edit")
        val deferred = env.journal.read().getJSONObject("deferred")
        verify(deferred.getString("baseline") == OLD_KEY && deferred.getString("desired") == THIRD_KEY && deferred.getString("id") == secondId, "baseline is not accidentally moved")
        env.dsh.available = true
        val thirdId = "33333333333333333333333333333333"
        verify(env.coordinator(thirdId).publishDeferred() is OfficialCredentialOutcome.Committed && !env.coordinator().hasDeferredPublication(), "latest publishes")
        verify(env.dsh.refs.getString(env.dsh.activeReference) == THIRD_KEY && env.dsh.refs.getString("DEEPSEEK_API_KEY") == OLD_KEY, "fresh latest without legacy overwrite")
    }
    test("deferred publication refuses a foreign live DSH key until explicit user resolution") { env ->
        verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.NativeCommitted, "native commit")
        env.dsh.refs.put("DEEPSEEK_API_KEY", THIRD_KEY)
        val secondId = "22222222222222222222222222222222"
        verify((env.coordinator(secondId).publishDeferred() as OfficialCredentialOutcome.Pending).code == "OFFICIAL_KEY_CONFLICT", "foreign conflict")
        verify(!env.coordinator().hasPending() && env.coordinator().hasDeferredPublication() && env.dsh.stageCalls == 0, "native remains usable with deferred conflict")
        verify(env.coordinator(secondId).publishDeferred(true) is OfficialCredentialOutcome.Committed, "explicit resolution")
        verify(env.dsh.refs.getString("DEEPSEEK_API_KEY") == THIRD_KEY, "foreign original retained")
    }
    test("deferred removal selects missing fresh ref rather than falling back to a retained default") { env ->
        verify(env.coordinator().beginNative(null) is OfficialCredentialOutcome.NativeCommitted, "offline removal")
        val secondId = "22222222222222222222222222222222"
        verify(env.coordinator(secondId).publishDeferred() is OfficialCredentialOutcome.Committed, "missing published")
        verify(!env.dsh.refs.has(env.dsh.activeReference) && env.dsh.userReference == env.dsh.activeReference, "explicit missing")
        env.oldDshPreserved()
    }
    for (boundary in listOf("stage.after", "settings.after", "provider.after", "balance.after")) {
        test("deferred publication lost reply at $boundary is recovered using live receipts") { env ->
            verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.NativeCommitted, "native saved")
            env.hooks.fail(boundary)
            val secondId = "22222222222222222222222222222222"
            verify(env.coordinator(secondId).publishDeferred() is OfficialCredentialOutcome.Pending && env.coordinator().hasPending(), "publication pending")
            verify(env.coordinator(secondId).recover() is OfficialCredentialOutcome.Committed, "publication recovered")
            verify(!env.coordinator().hasDeferredPublication() && env.provider.currentKey == NEW_KEY && env.balance.currentKey == NEW_KEY, "complete sync")
        }
    }
    test("pending native transaction blocks replacement and retains an external native edit") { env ->
        env.hooks.fail("provider.before")
        verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.Pending, "pending native")
        val secondId = "22222222222222222222222222222222"
        verify((env.coordinator(secondId).beginNative(THIRD_KEY) as OfficialCredentialOutcome.Pending).code == "PENDING_EXISTS", "cannot replace intent")
        env.provider.externalEdit(THIRD_KEY)
        verify(env.coordinator().recover() is OfficialCredentialOutcome.Pending && env.coordinator().hasPending(), "external CAS conflict stays pending")
        verify(env.provider.currentKey == THIRD_KEY && env.balance.currentKey == OLD_KEY, "foreign key is retained")
    }
    test("portable restore owns actual metadata and stripped foreign receipt in one provider CAS") { env ->
        env.dsh.available = false
        val config = portable(NEW_KEY, "Restored provider").put("officialCredentialReceipt", JSONObject().put("schema", 1).put("transactionId", "ffffffffffffffffffffffffffffffff"))
        env.hooks.fail("provider.after")
        verify(env.coordinator().beginPortableRestore(config) is OfficialCredentialOutcome.Pending, "lost whole-config reply")
        verify(env.provider.marker == ID && env.provider.configuration?.has("officialCredentialReceipt") == false, "local own marker")
        env.finishNative()
        verify(env.provider.configuration?.getJSONArray("providers")?.getJSONObject(1)?.getString("name") == "Restored provider" && env.balance.currentKey == NEW_KEY, "whole config restored")
        verify(env.journal.read().getJSONObject("nativeReceipt").getString("configurationDigest") == OfficialCredentialPortable.digest(config), "actual content proof")
    }
    test("same key and marker cannot prove a whole-config restore after a foreign metadata edit") { env ->
        env.hooks.fail("provider.after")
        verify(env.coordinator().beginPortableRestore(portable(NEW_KEY)) is OfficialCredentialOutcome.Pending, "lost config reply")
        env.provider.configuration!!.getJSONArray("providers").getJSONObject(1).put("name", "Foreign edit")
        env.provider.revision++
        verify(env.coordinator().recover() is OfficialCredentialOutcome.Pending && env.coordinator().hasPending(), "full-config digest mismatch")
        verify(env.provider.currentKey == NEW_KEY && env.provider.marker == ID && env.balance.currentKey == OLD_KEY, "key marker alone rejected")
        verify(env.provider.configuration!!.getJSONArray("providers").getJSONObject(1).getString("name") == "Foreign edit", "metadata is not overwritten")
    }
    test("portable removal is a native tombstone and preserves all other restored provider data") { env ->
        verify(env.coordinator().beginPortableRestore(portable(null)) is OfficialCredentialOutcome.NativeCommitted, "portable deletion")
        verify(env.provider.currentKey == null && env.balance.currentKey == null && env.provider.configuration!!.getJSONObject("credentials").has("other"), "other credentials retained")
    }
    test("reverse coherent DSH import commits both native vaults and needs no publication") { env ->
        env.dsh.refs.put("DEEPSEEK_API_KEY", NEW_KEY)
        val proof = env.sourceProof()
        verify(env.coordinator().beginReverseImport(portable(NEW_KEY), proof, "8") is OfficialCredentialOutcome.NativeCommitted, "reverse imported")
        verify(env.provider.currentKey == NEW_KEY && env.balance.currentKey == NEW_KEY && !env.coordinator().hasDeferredPublication(), "reverse complete")
        verify(env.dsh.stageCalls == 0 && env.dsh.settingsCalls == 0, "source untouched")
    }
    test("reverse lost provider reply recovers from captured source after DSH stops") { env ->
        env.dsh.refs.put("DEEPSEEK_API_KEY", NEW_KEY)
        val proof = env.sourceProof()
        env.hooks.fail("provider.after")
        verify(env.coordinator().beginReverseImport(portable(NEW_KEY), proof, "8") is OfficialCredentialOutcome.Pending, "reverse pending")
        env.dsh.available = false
        val result = env.finishNative()
        verify(!result.dshPublicationPending && env.balance.currentKey == NEW_KEY, "captured source does not need live DSH")
    }
    test("reverse source changing after adapter capture performs no destination write") { env ->
        val proof = env.sourceProof()
        env.dsh.providerSettingsRevision++
        verify((env.coordinator().beginReverseImport(portable(OLD_KEY), proof, "8") as OfficialCredentialOutcome.Pending).code == "SOURCE_CHANGED", "source revision conflict")
        verify(!env.journal.file.exists(), "no intent for mixed snapshot"); env.noNativeWrites()
    }
    test("reverse source credential and selected key must match captured proof") { env ->
        val proof = env.sourceProof()
        verify((env.coordinator().beginReverseImport(portable(NEW_KEY), proof, "8") as OfficialCredentialOutcome.Pending).code == "SOURCE_CHANGED", "payload does not match selected source")
        verify(!env.journal.file.exists(), "no mixed snapshot"); env.noNativeWrites()
    }
    test("reverse cannot silently overwrite a completed unpublished phone edit") { env ->
        verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.NativeCommitted, "unpublished native")
        val secondId = "22222222222222222222222222222222"
        verify((env.coordinator(secondId).beginReverseImport(portable(OLD_KEY), env.sourceProof(), env.provider.revision.toString()) as OfficialCredentialOutcome.Pending).code == "DEFERRED_EXISTS", "source versus deferred conflict")
        verify(env.provider.currentKey == NEW_KEY && env.balance.currentKey == NEW_KEY && env.coordinator().hasDeferredPublication(), "phone chosen key kept")
    }
    test("explicit keep DSH resolves a deferred phone conflict without writing source references or settings") { env ->
        verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.NativeCommitted, "unpublished phone value")
        val second = env.coordinator("22222222222222222222222222222222")
        val result = second.beginReverseImport(portable(OLD_KEY), env.sourceProof(), env.provider.read().revision,
            acceptDeferredConflict = true)
        verify(result is OfficialCredentialOutcome.NativeCommitted && !result.dshPublicationPending, "explicit source choice committed")
        verify(env.provider.currentKey == OLD_KEY && env.balance.currentKey == OLD_KEY && !second.hasDeferredPublication(), "deferred plan resolved")
        verify(env.dsh.stageCalls == 0 && env.dsh.settingsCalls == 0, "DSH source never modified")
    }
    test("explicit keep DSH lost destination reply keeps both before snapshots and can finish with source offline") { env ->
        verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.NativeCommitted, "deferred phone value")
        env.hooks.fail("balance.after")
        val second = env.coordinator("22222222222222222222222222222222")
        val result = second.beginReverseImport(portable(OLD_KEY), env.sourceProof(), env.provider.read().revision,
            acceptDeferredConflict = true)
        verify(result is OfficialCredentialOutcome.Pending && second.hasPending(), "source choice intent remains")
        verify(env.pending().getJSONObject("providerBefore").getString("key") == NEW_KEY &&
            env.pending().getJSONObject("balanceBefore").getString("key") == NEW_KEY, "phone prior values retained privately")
        env.dsh.available = false
        val recovered = env.finishNative()
        verify(!recovered.dshPublicationPending && env.provider.currentKey == OLD_KEY && env.balance.currentKey == OLD_KEY &&
            !second.hasDeferredPublication(), "captured choice recovered without source")
    }
    test("explicit keep DSH still rejects a changed source and preserves the completed phone intent") { env ->
        verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.NativeCommitted, "deferred phone value")
        val before = env.journal.read().toString()
        val proof = env.sourceProof()
        env.dsh.refs.put("DEEPSEEK_API_KEY", THIRD_KEY)
        val result = env.coordinator("22222222222222222222222222222222").beginReverseImport(portable(OLD_KEY), proof,
            env.provider.read().revision, acceptDeferredConflict = true)
        verify(result is OfficialCredentialOutcome.Pending && result.code == "SOURCE_CHANGED", "stale confirmed source rejected")
        verify(env.journal.read().toString() == before && env.provider.currentKey == NEW_KEY && env.balance.currentKey == NEW_KEY,
            "phone intent and both keys remain")
    }
    test("explicit keep DSH cannot overwrite a foreign destination edit after the deferred commit") { env ->
        verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.NativeCommitted, "deferred phone value")
        env.balance.externalEdit(THIRD_KEY)
        val before = env.journal.read().toString()
        val result = env.coordinator("22222222222222222222222222222222").beginReverseImport(portable(OLD_KEY), env.sourceProof(),
            env.provider.read().revision, acceptDeferredConflict = true)
        verify(result is OfficialCredentialOutcome.Pending && result.code == "NATIVE_VAULT_CONFLICT", "foreign destination rejected")
        verify(env.journal.read().toString() == before && env.balance.currentKey == THIRD_KEY, "foreign value not replaced")
    }
    test("DYM capture uses actual selector and removal round trip cannot recover retained old default") { env ->
        val selected = "DAFEIYU_PROVIDER_DEEPSEEK_22222222222222222222222222222222"
        val raw = env.dsh.credentials().also { it.getJSONObject("refs").put(selected, THIRD_KEY) }
        val retained = JSONObject().put("version", 1).put("officialRef", "DEEPSEEK_API_KEY").put("refs", JSONObject().put("DEEPSEEK_API_KEY", OLD_KEY))
            .put("records", JSONObject()).put("launcherDeepSeekKey", OLD_KEY)
        val captured = DymSharedCredentials.capture(raw, portable(null), retained, selected)
        verify(captured.getString("officialRef") == selected && !captured.has("launcherDeepSeekKey") && !captured.getJSONObject("refs").has(selected), "actual explicit missing captured")
        val restored = requireNotNull(DymSharedCredentials.nativeConfiguration(JSONObject(captured.toString()), portable(NEW_KEY)))
        verify(!restored.getJSONObject("credentials").has("deepseek") && restored.getJSONObject("credentials").has("other"), "selected missing clears only official")
        val updates = DymSharedCredentials.updates(captured, "a".repeat(64), portable(null))
        verify((0 until updates.getJSONArray("refs").length()).none { updates.getJSONArray("refs").getJSONObject(it).getString("ref") == "DEEPSEEK_API_KEY" }, "helper cannot write stale official default")
    }
    test("DYM offline capture normalizes stale retained official credentials to current native absence") { _ ->
        val retained = JSONObject().put("version", 1).put("officialRef", "DEEPSEEK_API_KEY").put("refs", JSONObject().put("DEEPSEEK_API_KEY", OLD_KEY))
            .put("records", JSONObject()).put("launcherDeepSeekKey", OLD_KEY)
        val captured = DymSharedCredentials.captureWithoutDsh(portable(null), retained)
        verify(!captured.has("launcherDeepSeekKey") && !captured.getJSONObject("refs").has("DEEPSEEK_API_KEY"), "offline absence authoritative")
        val restored = requireNotNull(DymSharedCredentials.nativeConfiguration(captured, portable(NEW_KEY)))
        verify(!restored.getJSONObject("credentials").has("deepseek"), "no resurrection")
    }
    test("DYM legacy unknown absence keeps live key while legacy default and launcher keys still restore") { _ ->
        val unknown = JSONObject().put("version", 1).put("refs", JSONObject()).put("records", JSONObject())
        verify(DymSharedCredentials.nativeConfiguration(unknown, portable(NEW_KEY)) == null, "legacy unknown is not deletion")
        val withDefault = copy(unknown).put("refs", JSONObject().put("DEEPSEEK_API_KEY", OLD_KEY))
        verify(OfficialCredentialPortable.key(requireNotNull(DymSharedCredentials.nativeConfiguration(withDefault, portable(NEW_KEY)))) == OLD_KEY, "legacy default restored")
        val launcher = copy(unknown).put("launcherDeepSeekKey", THIRD_KEY)
        verify(OfficialCredentialPortable.key(requireNotNull(DymSharedCredentials.nativeConfiguration(launcher, portable(NEW_KEY)))) == THIRD_KEY, "launcher key restored")
    }
    test("reverse import cannot replace native metadata edited after the adapter baseline") { env ->
        val proof = env.sourceProof()
        env.provider.externalEdit(OLD_KEY)
        verify((env.coordinator().beginReverseImport(portable(OLD_KEY), proof, "8") as OfficialCredentialOutcome.Pending).code == "NATIVE_CAS_CONFLICT", "adapter stale native baseline")
        verify(!env.journal.file.exists(), "no stale import plan"); env.noNativeWrites()
    }
    test("lost ordering projection is retried before a whole-config native success receipt") { env ->
        env.hooks.fail("provider.projection")
        verify(env.coordinator().beginPortableRestore(portable(NEW_KEY)) is OfficialCredentialOutcome.Pending && env.coordinator().hasPending(), "projection failure pending")
        verify(env.provider.currentKey == NEW_KEY && env.balance.currentKey == NEW_KEY, "vault commits preserved")
        env.finishNative()
        verify(env.hooks.events.count { it == "provider.projection" } == 2, "projection repaired exactly on recovery")
    }
    test("common recovery gate blocks imports and credential reads while native journal is pending") { env ->
        env.hooks.fail("balance.before")
        verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.Pending, "partial native")
        val gate = OfficialCredentialRecoveryGate(env.journal)
        var visited = false
        val failure = runCatching { gate.withNativeReady { visited = true } }.exceptionOrNull()
        verify(failure?.message == "PENDING_EXISTS" && !visited, "pending gate rejects operation")
        env.finishNative()
        gate.withNativeReady { visited = true }
        verify(visited, "native committed gate opens")
        visited = false
        verify(runCatching { gate.withDshReady { visited = true } }.exceptionOrNull()?.message == "DEFERRED_EXISTS" && !visited, "DSH gate retains deferred publication")
    }
    test("common root gate holds the check through the mutation and serializes a new encrypted intent") { env ->
        coroutineScope {
            val gate = OfficialCredentialRecoveryGate(env.journal)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val protected = async { gate.withNativeReady { entered.complete(Unit); release.await() } }
            entered.await()
            val started = CompletableDeferred<Unit>()
            val transaction = async { started.complete(Unit); env.coordinator().beginNative(NEW_KEY) }
            started.await(); yield()
            verify(!env.journal.file.exists() && env.provider.writes == 0 && !transaction.isCompleted, "no check-then-write race")
            release.complete(Unit); protected.await()
            verify(transaction.await() is OfficialCredentialOutcome.NativeCommitted, "transaction follows protected mutation")
        }
    }
    test("native journal corruption closes the common gate and keeps the encrypted original") { env ->
        env.hooks.fail("balance.before")
        verify(env.coordinator().beginNative(NEW_KEY) is OfficialCredentialOutcome.Pending, "native journal exists")
        val bytes = env.journal.file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        env.journal.file.writeBytes(bytes)
        var visited = false
        val gate = OfficialCredentialRecoveryGate(env.journal)
        verify(runCatching { gate.withNativeReady { visited = true } }.isFailure && !visited, "corrupt cannot open gate")
        verify(env.journal.file.readBytes().contentEquals(bytes), "corrupt original retained")
    }
    test("malformed DYM selector cannot become a silent tombstone or default-key fallback") { _ ->
        for (bad in listOf(JSONObject.NULL, 7, false, "bad selector")) {
            val malformed = JSONObject().put("version", 1).put("officialRef", bad).put("refs", JSONObject().put("DEEPSEEK_API_KEY", OLD_KEY))
                .put("records", JSONObject())
            verify(runCatching { DymSharedCredentials.nativeConfiguration(malformed, portable(NEW_KEY)) }.isFailure, "malformed selector refused")
        }
    }
    val report = JSONObject().put("passed", names.size).put("failures", 0).put("tests", JSONArray(names))
        .put("candidateCoreFreshlyCompiled", true).put("realAndroidKeystoreValidated", false)
        .put("realDshSettingsCasValidated", false).put("jvmEncryptedFileRestartValidated", true)
        .put("publicRequests", 0).put("paidModelRequests", 0)
    File(evidence, "report.json").writeText(report.toString(2))
    File(evidence, "official-stage-frame.json").writeText(JSONObject().put("refs", JSONArray().put(JSONObject().put("ref", REF).put("value", NEW_KEY)))
        .put("records", JSONArray()).toString(2))
    println("Official credential transaction: ${names.size} PASS; candidate code and synthetic JVM file recovery only.")
}
