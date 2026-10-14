package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.AtomicFile
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android provider vault, Keystore and AtomicFile; no Activity, DSH, model or network calls. */
@RunWith(AndroidJUnit4::class)
class DshProviderOrderDeviceSmoke {
    @Test
    fun actualVaultCommitsPreserveUnchangedOrderAndRejectUnsafeProjection() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val run = InstrumentationRegistry.getArguments().getString("provider_order_run")
            ?: UUID.randomUUID().toString().replace("-", "")
        require(run.matches(Regex("[a-f0-9]{32}"))) { "QA_INVALID_RUN_ID" }
        val guard = CommonStorageGuard(target)
        val report = Report(target, run)
        var fixture: Fixture? = null
        var failure: Throwable? = null
        var succeeded = false
        try {
            val owned = Fixture.create(target, run)
            fixture = owned
            report.case("fresh-missing-projection-initializes-without-vault-write") {
                val context = owned.context("fresh")
                val store = ModelProviderStore(context)
                check(!orderFile(context).exists() && !vaultFile(context).exists()) { "QA_FRESH_STATE_NOT_EMPTY" }
                check(store.read().revision == 0L)
                check(store.prepareDshProviderOrder() == orderFile(context).parentFile)
                val first = readOrder(context)
                check(first.revision == 0L && first.routes == listOf(OFFICIAL_ROUTE)) { "QA_FRESH_ORDER_INVALID" }
                check(!vaultFile(context).exists()) { "QA_PREPARE_WROTE_VAULT" }
                ModelProviderStore(context).prepareDshProviderOrder()
                assertOrderUnchanged(context, first)
                JSONObject().put("projectionRevision", first.revision).put("vaultCreated", false)
                    .put("storeReopened", true)
            }
            report.case("real-official-cas-and-worker-credentials-only-advance-encrypted-vault") {
                val context = owned.context("credentials")
                val store = ModelProviderStore(context)
                store.prepareDshProviderOrder()
                atomicWrite(orderFile(context), formattedOrder(0, listOf(OFFICIAL_ROUTE)))
                val before = readOrder(context)
                val firstSecret = "fixture-provider-order-first-$run"
                val secondSecret = "fixture-provider-order-second-$run"
                store.compareAndSetOfficialCredential("0", firstSecret, UUID.randomUUID().toString().replace("-", ""))
                check(ModelProviderStore(context).officialCredentialSnapshot().key == firstSecret)
                check(store.read().revision == 1L)
                assertOrderUnchanged(context, before)
                val secondRevision = store.persistWorkerCredential("deepseek", credential(secondSecret), 1)
                check(secondRevision == 2L && ModelProviderStore(context).officialCredentialSnapshot().key == secondSecret)
                assertOrderUnchanged(context, before)
                val removedRevision = store.persistWorkerCredential("deepseek", null, secondRevision)
                check(removedRevision == 3L && !ModelProviderStore(context).hasCredential("deepseek"))
                assertOrderUnchanged(context, before)
                val encrypted = vaultFile(context).readBytes()
                expectRefused {
                    store.compareAndSetOfficialCredential("0", firstSecret, UUID.randomUUID().toString().replace("-", ""))
                }
                check(vaultFile(context).readBytes().contentEquals(encrypted)) { "QA_STALE_CAS_CHANGED_VAULT" }
                check(!encrypted.toString(Charsets.ISO_8859_1).contains(firstSecret) &&
                    !encrypted.toString(Charsets.ISO_8859_1).contains(secondSecret)) { "QA_CLEARTEXT_IN_VAULT" }
                check(keyStore().containsAlias(PROVIDER_KEY_ALIAS)) { "QA_REAL_PROVIDER_KEY_MISSING" }
                ModelProviderStore(context).prepareDshProviderOrder()
                assertOrderUnchanged(context, before)
                JSONObject().put("credentialCommits", 3).put("vaultRevision", removedRevision)
                    .put("projectionRevision", before.revision).put("exactNoncanonicalBytesPreserved", true)
                    .put("staleCredentialCasRefused", true).put("actualAndroidKeyStore", true)
            }
            report.case("add-reorder-and-remove-persist-real-routes-with-higher-revisions") {
                val context = owned.context("mutations")
                val store = ModelProviderStore(context)
                store.prepareDshProviderOrder()
                var previous = readOrder(context)
                fun committed(snapshot: ModelProviderSnapshot, expected: List<String>) {
                    val next = readOrder(context)
                    check(next.routes == expected && next.revision == snapshot.revision && next.revision > previous.revision)
                    check(!next.bytes.contentEquals(previous.bytes)) { "QA_ROUTE_CHANGE_DID_NOT_CHANGE_BYTES" }
                    check(ModelProviderStore(context).read() == snapshot) { "QA_PROVIDER_MUTATION_NOT_DURABLE" }
                    previous = next
                }
                committed(store.upsert(provider("alpha")), listOf(OFFICIAL_ROUTE, "route-alpha"))
                committed(store.upsert(provider("beta")), listOf(OFFICIAL_ROUTE, "route-alpha", "route-beta"))
                committed(store.reorder(listOf("beta", "deepseek", "alpha")), listOf("route-beta", OFFICIAL_ROUTE, "route-alpha"))
                committed(store.remove("alpha"), listOf("route-beta", OFFICIAL_ROUTE))
                check(previous.revision == 4L)
                JSONObject().put("realRouteMutations", 4).put("finalRevision", previous.revision)
                    .put("routes", JSONArray(previous.routes)).put("newStoreReadback", true)
            }
            report.case("same-revision-route-conflict-and-all-lower-revisions-preserve-committed-bytes") {
                val context = owned.context("revision-guards")
                val projection = DshProviderOrderProjection(context)
                val committed = ModelProviderSnapshot(10, listOf(ModelProviderConfig.DEEPSEEK, provider("alpha")))
                projection.publish(committed)
                atomicWrite(orderFile(context), formattedOrder(10, listOf(OFFICIAL_ROUTE, "route-alpha")))
                val before = readOrder(context)
                DshProviderOrderProjection(context).publish(committed.copy(revision = 20))
                assertOrderUnchanged(context, before)
                val changed = listOf(ModelProviderConfig.DEEPSEEK, provider("beta"))
                for (candidate in listOf(committed.copy(providers = changed), committed.copy(revision = 9),
                    ModelProviderSnapshot(9, changed))) {
                    expectRefused { DshProviderOrderProjection(context).publish(candidate) }
                    assertOrderUnchanged(context, before)
                }
                val accepted = committed.copy(revision = 11, providers = changed)
                projection.publish(accepted)
                val next = readOrder(context)
                check(next.revision == 11L && next.routes == listOf(OFFICIAL_ROUTE, "route-beta"))
                JSONObject().put("refusedCandidates", 3).put("unchangedHigherVaultRevision", 20)
                    .put("retainedCommittedRevision", before.revision).put("nextRouteRevision", next.revision)
            }
            report.case("corrupt-or-untrusted-file-is-refused-even-when-requested-routes-match") {
                val context = owned.context("invalid-files")
                val projection = DshProviderOrderProjection(context)
                val snapshot = ModelProviderSnapshot(1, listOf(ModelProviderConfig.DEEPSEEK))
                projection.publish(snapshot)
                fun valid() = JSONObject().put("schema", 1).put("revision", 1).put("routes", JSONArray().put(OFFICIAL_ROUTE))
                val invalid = listOf(
                    "malformed" to "{invalid".toByteArray(),
                    "schema-string" to valid().put("schema", "1").toString().toByteArray(),
                    "revision-string" to valid().put("revision", "1").toString().toByteArray(),
                    "fractional-revision" to valid().put("revision", 1.5).toString().toByteArray(),
                    "unsafe-revision" to valid().put("revision", MAX_SAFE_REVISION + 1).toString().toByteArray(),
                    "non-string-route" to valid().put("routes", JSONArray().put(7)).toString().toByteArray(),
                    "empty-routes" to valid().put("routes", JSONArray()).toString().toByteArray(),
                    "duplicate-routes" to valid().put("routes", JSONArray().put(OFFICIAL_ROUTE).put(OFFICIAL_ROUTE)).toString().toByteArray(),
                    "unknown-field" to valid().put("unexpected", true).toString().toByteArray(),
                    "over-size" to ByteArray(32 * 1024 + 1) { 0x20 },
                )
                val refused = JSONArray()
                for ((kind, bytes) in invalid) {
                    atomicWrite(orderFile(context), bytes)
                    expectRefused { projection.publish(snapshot) }
                    check(orderFile(context).readBytes().contentEquals(bytes)) { "QA_BAD_ORDER_REWRITTEN" }
                    check(!File(orderFile(context).path + ".bak").exists() && !File(orderFile(context).path + ".new").exists())
                    refused.put(kind)
                }
                atomicWrite(orderFile(context), formattedOrder(1, listOf(OFFICIAL_ROUTE)))
                val validBefore = readOrder(context)
                expectRefused { projection.publish(snapshot.copy(revision = MAX_SAFE_REVISION + 1)) }
                assertOrderUnchanged(context, validBefore)
                JSONObject().put("refusedFiles", refused).put("badInputBytesPreserved", true)
                    .put("unsafeIncomingRevisionRefused", true)
            }
            report.case("actual-atomicfile-backup-restores-original-route-revision-and-bytes") {
                val context = owned.context("atomic-backup")
                val projection = DshProviderOrderProjection(context)
                val snapshot = ModelProviderSnapshot(7, listOf(ModelProviderConfig.DEEPSEEK, provider("alpha")))
                projection.publish(snapshot)
                atomicWrite(orderFile(context), formattedOrder(7, listOf(OFFICIAL_ROUTE, "route-alpha")))
                val original = readOrder(context)
                val backup = File(orderFile(context).path + ".bak")
                backup.writeBytes(original.bytes)
                orderFile(context).writeText("{interrupted", Charsets.UTF_8)
                DshProviderOrderProjection(context).publish(snapshot.copy(revision = 8))
                // Plain base-file reads below cannot perform or conceal AtomicFile recovery themselves.
                assertOrderUnchanged(context, original)
                check(!backup.exists() && !File(orderFile(context).path + ".new").exists()) { "QA_ATOMIC_RECOVERY_NOT_COMPLETED" }
                JSONObject().put("actualAndroidAtomicFile", true).put("legacyBackupRecovered", true)
                    .put("retainedRevision", original.revision).put("exactOriginalBytesPreserved", true)
                    .put("actualProcessRestart", false)
            }
            report.case("directory-base-backup-and-new-symlinks-cannot-rewrite-their-target") {
                val rejected = JSONArray()
                for (surface in listOf("directory", "base", "backup", "new")) {
                    val context = owned.context("link-$surface")
                    val projection = DshProviderOrderProjection(context)
                    val snapshot = ModelProviderSnapshot(0, listOf(ModelProviderConfig.DEEPSEEK))
                    val sentinelDirectory = File(context.cacheDir, "untouched").also { check(it.mkdir()) }
                    val sentinel = File(sentinelDirectory, "order.json")
                    val sentinelBytes = formattedOrder(0, listOf(OFFICIAL_ROUTE))
                    atomicWrite(sentinel, sentinelBytes)
                    var original: Order? = null
                    val link = if (surface == "directory") projection.directory else {
                        projection.publish(snapshot)
                        original = readOrder(context)
                        when (surface) {
                            "base" -> orderFile(context).also { check(it.delete()) }
                            "backup" -> File(orderFile(context).path + ".bak")
                            else -> File(orderFile(context).path + ".new")
                        }
                    }
                    Os.symlink(if (surface == "directory") sentinelDirectory.path else sentinel.path, link.path)
                    check(Files.isSymbolicLink(link.toPath()))
                    expectRefused { projection.publish(snapshot) }
                    check(Files.isSymbolicLink(link.toPath()) && sentinel.readBytes().contentEquals(sentinelBytes)) { "QA_SYMLINK_TARGET_CHANGED" }
                    if (surface == "backup" || surface == "new") assertOrderUnchanged(context, checkNotNull(original))
                    rejected.put(surface)
                }
                JSONObject().put("refusedSurfaces", rejected).put("allSentinelTargetsPreserved", true)
            }
            succeeded = true
        } catch (error: Throwable) {
            failure = error
            report.failure(error)
        } finally {
            try {
                fixture?.cleanup()
                report.document.put("cleanupComplete", true)
                val additionalAliases = guard.verify()
                report.document.put("commonStatePreserved", true).put("commonKeystoreAliasesPreserved", true)
                    .put("additionalAliasesRetained", additionalAliases).put("keystoreAliasesDeleted", 0)
            } catch (cleanup: Throwable) {
                report.document.put("cleanupComplete", false).put("cleanupErrorClass", cleanup.javaClass.name)
                if (failure == null) failure = cleanup
                report.failure(cleanup)
            } finally {
                report.document.put("passed", succeeded && failure == null).put("completed", true)
                    .put("finishedAt", System.currentTimeMillis())
                report.persist()
            }
        }
        if (failure != null) throw AssertionError("QA_PROVIDER_ORDER_FAILED_SEE_UUID_REPORT")
    }

    private data class Order(val bytes: ByteArray, val revision: Long, val routes: List<String>)

    private class Report(target: Context, run: String) {
        val document = JSONObject().put("schema", 1).put("runId", run).put("package", target.packageName)
            .put("pid", Process.myPid()).put("sdk", Build.VERSION.SDK_INT).put("startedAt", System.currentTimeMillis())
            .put("passed", false).put("completed", false).put("fixtureOnly", true).put("actualProcessRestart", false)
            .put("explicitModelCalls", 0).put("explicitPaidModelCalls", 0).put("explicitRemoteServiceCalls", 0)
            .put("activityLaunches", 0).put("runtimeInstalls", 0).put("permissionGrants", 0)
            .put("networkTrafficInstrumentation", false).put("results", JSONArray())
        private val directory = File(checkNotNull(target.getExternalFilesDir(null)).canonicalFile, "qa/provider-order-$run")
        init {
            check(!directory.exists() && !Files.isSymbolicLink(directory.toPath()))
            check(directory.mkdirs() && directory.canonicalPath == directory.absolutePath)
            persist()
            Log.i(TAG, "Report: ${File(directory, "report.json").path}")
        }
        fun persist() = atomicWrite(File(directory, "report.json"), document.toString(2).toByteArray(Charsets.UTF_8))
        fun case(name: String, action: () -> JSONObject) {
            val row = JSONObject().put("name", name).put("passed", false).put("startedAt", System.currentTimeMillis())
            document.getJSONArray("results").put(row)
            persist()
            try {
                row.put("evidence", action()).put("passed", true)
                Log.i(TAG, "PASS $name")
            } catch (error: Throwable) { safeFailure(row, error); throw error }
            finally { row.put("finishedAt", System.currentTimeMillis()); persist() }
        }
        fun failure(error: Throwable) { safeFailure(document, error); persist() }
        private fun safeFailure(row: JSONObject, error: Throwable) {
            row.put("errorClass", error.javaClass.name)
            error.message?.takeIf { it.matches(Regex("QA_[A-Z0-9_]{1,120}")) }?.let { row.put("code", it) }
            row.put("codeLocations", JSONArray().also { frames ->
                error.stackTrace.filter { it.className.startsWith("com.dafeiyu.mobile.data") }.take(8).forEach {
                    frames.put(JSONObject().put("class", it.className).put("method", it.methodName)
                        .put("file", it.fileName ?: "unknown").put("line", it.lineNumber))
                }
            })
        }
    }

    private class CommonStorageGuard(target: Context) {
        private val files = listOf(File(target.noBackupFilesDir, "model-providers.v1.enc"),
            File(target.noBackupFilesDir, ".deepseek_api_key.v1.bin"), File(target.noBackupFilesDir, "official-deepseek-transaction.v1.enc"),
            File(target.filesDir, "dsh-provider-order/order.json"), File(target.filesDir, "dsh-home/.credentials.yaml"),
            File(target.filesDir, "dsh-home/settings.yaml"), File(target.filesDir, "dsh-home/cordis.patch.yml"),
            File(target.filesDir, "linux-runtime/active.json"), File(target.filesDir, "dsh-runtime/active.json"))
            .flatMap { listOf(it, File(it.path + ".bak"), File(it.path + ".new")) }
        // Equality-only fingerprints stay private: never emit common file bytes or credential hashes.
        private val before = files.associateWith { fingerprint(it) }
        private val aliases = keyStore().aliases().toList().toSet()
        fun verify(): Int {
            before.forEach { (file, expected) -> check(fingerprint(file) == expected) { "QA_COMMON_STATE_CHANGED" } }
            val after = keyStore().aliases().toList().toSet()
            check(after.containsAll(aliases)) { "QA_COMMON_ALIAS_REMOVED" }
            return (after - aliases).size
        }
    }

    private class Fixture private constructor(private val target: Context, private val run: String,
                                               private val files: File, private val vault: File) {
        private val marker = File(files, "owner.json")
        fun context(name: String): Context {
            require(name.matches(Regex("[a-z0-9-]{1,80}")))
            fun child(parent: File) = File(parent, name).also {
                check(it.canonicalFile.parentFile == parent.canonicalFile && !Files.isSymbolicLink(it.toPath()) && !it.exists() && it.mkdir())
            }
            val childFiles = child(files)
            val childVault = child(vault)
            val childCache = File(childFiles, "cache").also { check(it.mkdir()) }
            return object : ContextWrapper(target) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = childFiles
                override fun getNoBackupFilesDir(): File = childVault
                override fun getCacheDir(): File = childCache
            }
        }
        fun cleanup() {
            val proof = JSONObject(marker.readText(Charsets.UTF_8))
            check(proof.getString("runId") == run && proof.getString("package") == target.packageName &&
                proof.getString("files") == files.path && proof.getString("vault") == vault.path) { "QA_FIXTURE_OWNERSHIP_INVALID" }
            for ((root, base) in listOf(vault to target.noBackupFilesDir, files to target.filesDir)) {
                check(root.absolutePath == File(base.canonicalFile, "$CATEGORY/$run").absolutePath &&
                    root.canonicalPath == root.absolutePath && !Files.isSymbolicLink(root.toPath())) { "QA_FIXTURE_ROOT_INVALID" }
                deleteTreeWithoutFollowingLinks(root)
            }
        }
        companion object {
            fun create(target: Context, run: String): Fixture {
                val files = File(target.filesDir.canonicalFile, "$CATEGORY/$run")
                val vault = File(target.noBackupFilesDir.canonicalFile, "$CATEGORY/$run")
                val created = mutableListOf<File>()
                for (root in listOf(files, vault)) check(root.canonicalPath == root.absolutePath &&
                    !root.exists() && !Files.isSymbolicLink(root.toPath())) { "QA_FIXTURE_ALREADY_EXISTS" }
                try {
                    for (root in listOf(files, vault)) { check(root.mkdirs()); created += root }
                    val owned = Fixture(target, run, files, vault)
                    atomicWrite(owned.marker, JSONObject().put("runId", run).put("package", target.packageName)
                        .put("files", files.path).put("vault", vault.path).toString().toByteArray(Charsets.UTF_8))
                    return owned
                } catch (error: Throwable) {
                    created.asReversed().forEach(::deleteTreeWithoutFollowingLinks)
                    throw error
                }
            }
        }
    }

    private companion object {
        const val TAG = "DshProviderOrderDevice"
        const val CATEGORY = "qa-provider-order"
        const val OFFICIAL_ROUTE = "deepseek-official"
        const val PROVIDER_KEY_ALIAS = "dafeiyu_model_providers_aes_v1"
        const val MAX_SAFE_REVISION = 9_007_199_254_740_991L
        fun provider(id: String) = ModelProviderConfig(id, "Fixture $id", builtinProvider = "openai", dshRoute = "route-$id").validated()
        fun credential(key: String) = JSONObject().put("type", "api_key").put("key", key)
        fun orderFile(context: Context) = File(context.filesDir, "dsh-provider-order/order.json")
        fun vaultFile(context: Context) = File(context.noBackupFilesDir, "model-providers.v1.enc")
        fun readOrder(context: Context): Order {
            val bytes = orderFile(context).readBytes()
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            check(value.getInt("schema") == 1)
            val routes = value.getJSONArray("routes")
            return Order(bytes, value.getLong("revision"), List(routes.length()) { routes.getString(it) })
        }
        fun assertOrderUnchanged(context: Context, expected: Order) {
            val actual = readOrder(context)
            check(actual.bytes.contentEquals(expected.bytes) && actual.revision == expected.revision && actual.routes == expected.routes) {
                "QA_UNCHANGED_ROUTES_REWROTE_ORDER"
            }
        }
        fun formattedOrder(revision: Long, routes: List<String>): ByteArray =
            (" \n" + JSONObject().put("schema", 1).put("revision", revision).put("routes", JSONArray(routes)).toString(2) + "\n ")
                .toByteArray(Charsets.UTF_8)
        fun expectRefused(action: () -> Unit) {
            var refused = false
            try { action() } catch (_: Exception) { refused = true }
            check(refused) { "QA_UNSAFE_INPUT_ACCEPTED" }
        }
        fun atomicWrite(file: File, bytes: ByteArray) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) }
            catch (error: Throwable) { atomic.failWrite(stream); throw error }
        }
        fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun fingerprint(file: File): String? = if (file.exists())
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } else null
        fun deleteTreeWithoutFollowingLinks(root: File) {
            val path = root.toPath()
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    check(file.startsWith(path)); Files.delete(file); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    check(directory.startsWith(path)); Files.delete(directory); return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
