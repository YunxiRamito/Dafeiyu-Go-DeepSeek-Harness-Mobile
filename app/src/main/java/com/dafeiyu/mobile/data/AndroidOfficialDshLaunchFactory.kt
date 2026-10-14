package com.dafeiyu.mobile.data

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Concrete Android factory. Open under the actual stopped service gate;
 * the coordinator then holds its real journal transaction mutex through
 * ticket issue, helper challenges, and result CAS. This factory never reads a backup's env,
 * argv, mounts, code or authority, and never prepares a pending UI profile. */
internal class AndroidOfficialDshLaunchFactory(
    context: Context,
    private val runtime: LinuxRuntimeManager = LinuxRuntimeManager(context),
    private val installer: DshRuntimeInstaller = DshRuntimeInstaller(context, runtime),
) {
    private val app = context.applicationContext
    private val home = File(app.filesDir, "dsh-home")
    private val shared = owners.computeIfAbsent(home.canonicalPath) { Shared() }

    suspend fun open(authorizer: AndroidOfficialDshStorageAuthorizer, scopeId: String, launchPlan: JSONObject): Session =
        withContext(Dispatchers.IO) { shared.lock.withLock {
            check(scopeId.matches(HEX64) && shared.active == null && shared.ownership.retainedSessions().isEmpty() &&
                synchronized(shared.partialStarts) { shared.partialStarts.isEmpty() }) {
                "HOST_UNAVAILABLE"
            }
            val plan = OfficialDshTrustedLaunchPlan.parse(launchPlan)
            val slot = installer.current() ?: error("HOST_UNAVAILABLE")
            val prepared = installer.withCurrentSlot(slot.manifest.digest()) { current -> prepare(current, scopeId, plan) }
            val binding = AndroidOfficialDshControlledPort.TrustedLaunchBinding(
                ownership = shared.ownership,
                start = {
                    installer.withCurrentSlot(prepared.slot.manifest.digest()) { current ->
                        check(current.directory.canonicalFile == prepared.slot.directory.canonicalFile) { "HOST_UNAVAILABLE" }
                        prepared.verifyFiles()
                        // The runtime keeps startup failures in its real failed-
                        // cleanup/resource maps. There is no suspension or IO
                        // after native return before adoption by the client.
                        val process = runtime.startManagedProcess(
                            "official-dsh-store-${UUID.randomUUID()}", prepared.helperCommand,
                            mounts = prepared.mounts, workspace = prepared.workspace,
                            environment = DshArchiveLaunchContract.environment(),
                            exclusiveRuntimeUse = true, retainLeaseUntilPipesComplete = true,
                            guestWorkingDirectory = plan.cwd.takeUnless { it == "/root" },
                            beforeStart = { prepared.verifyFiles() }, expectedRuntimeIdentity = prepared.runtimeIdentity,
                            nativeProcessCreated = { actual -> synchronized(shared.partialStarts) { shared.partialStarts.add(actual); Unit } },
                        )
                        AndroidOfficialDshControlledPort.StartedHelper(runtime, process,
                            prepared.mounts.values.toList() + listOfNotNull(prepared.workspace),
                            JSONObject(prepared.initialization.toString())).also {
                            prepared.started = it
                            synchronized(shared.partialStarts) { shared.partialStarts.removeAll { partial -> partial.owns(it.process) } }
                        }
                    }
                },
                requireCurrentSlot = { helper ->
                    requireActualHelper(prepared, helper)
                    installer.withCurrentSlot(prepared.slot.manifest.digest()) { current ->
                        check(current.directory.canonicalFile == prepared.slot.directory.canonicalFile) { "HOST_UNAVAILABLE" }
                        prepared.verifyFiles()
                        runtime.requireManagedRuntimeIdentity(helper.process, prepared.runtimeIdentity)
                        // Before controlled-ready there is no authenticated
                        // Node PID yet. The client verifies that response with
                        // requireNodeTracee, then repeats this slot check before
                        // becoming ready. Every later challenge rechecks it.
                        if (prepared.tracee.verifiedPid != null) prepared.tracee.requireCurrent(helper.process)
                    }
                },
                requireNodeTracee = { helper, pid ->
                    requireActualHelper(prepared, helper)
                    prepared.tracee.verify(helper.process, pid)
                },
                startupDeadlineMillis = 60_000, requestDeadlineMillis = 90_000, cleanupDeadlineMillis = 20_000,
            )
            val client = AndroidOfficialDshControlledPort.open(authorizer, binding)
            Session(client, plan, { prepared.started?.process?.pid ?: error("HOST_UNAVAILABLE") },
                { prepared.tracee.verifiedPid }).also { shared.active = it }
        } }

    /** Keeps failed-open client sessions reachable across repository rebuilds.
     * Only actual process/pipe completion clears this host ownership. */
    suspend fun cleanupPending(): Boolean = withContext(NonCancellable + Dispatchers.IO) { shared.lock.withLock {
        var complete = true
        for (port in shared.ownership.retainedSessions()) {
            if (!port.retryCleanup()) complete = false
        }
        for (partial in synchronized(shared.partialStarts) { shared.partialStarts.toList() }) {
            if (partial.retryCleanup()) synchronized(shared.partialStarts) { shared.partialStarts.remove(partial) }
            else complete = false
        }
        if (complete && shared.ownership.retainedSessions().isEmpty()) shared.active = null
        complete
    } }

    internal inner class Session internal constructor(
        private val client: AndroidOfficialDshControlledPort,
        private val plan: OfficialDshTrustedLaunchPlan,
        private val tracerPid: () -> Int,
        private val nodePid: () -> Int?,
    ) {
        val helperTracerPid: Int get() = tracerPid()
        val verifiedNodePid: Int? get() = nodePid()
        val storage: OfficialDshColdStoragePort = object : OfficialDshColdStoragePort {
            override fun launchPlan(): JSONObject = plan.json()
            override suspend fun readStorage(candidate: String): JSONObject = client.read(candidate)
            override suspend fun stageStorage(expected: JSONObject): JSONObject = client.stage(expected)
            override suspend fun selectStorage(expected: JSONObject): JSONObject = client.select(expected)
            // A storage proof cannot impersonate a live in-process revision or
            // generic DSH RPC. The cold coordinator exclusively uses above.
            override suspend fun describe(): JSONObject = unavailable()
            override suspend fun credentials(): JSONObject = unavailable()
            override suspend fun stageReferences(candidates: JSONArray, expectedRevision: String): JSONObject = unavailable()
            override suspend fun mutate(namespace: String, expectedRevision: Long, reference: String): JSONObject = unavailable()
        }

        suspend fun close() {
            try { client.close() }
            finally { releaseIfComplete() }
        }

        suspend fun retryCleanup(): Boolean {
            val complete = client.retryCleanup()
            if (complete) releaseIfComplete()
            return complete
        }

        private suspend fun releaseIfComplete() = withContext(NonCancellable) { shared.lock.withLock {
            if (client !in shared.ownership.retainedSessions() && shared.active === this@Session) shared.active = null
        } }
        private fun unavailable(): Nothing = throw AndroidOfficialDshControlledPort.Failure("UnsupportedLaunch")
    }

    private suspend fun prepare(slot: DshRuntimeSlot, scopeId: String, plan: OfficialDshTrustedLaunchPlan): Prepared {
        // Injected real runtime/installer may own the base application's
        // installation while this Context owns an isolated private profile.
        // Extra boundaries come only from these actual local objects.
        requireOwnedDirectory(slot.directory, listOf(slot.directory))
        DshArchiveLaunchContract.verifyOwningBundle(slot.directory, slot.manifest.dshVersion)
        val linux = runtime.captureManagedRuntimeIdentity()
        val runtimeRoots = listOf(slot.directory, linux.rootfsDirectory)
        val identity = runtime.nodeIdentity()
        check(identity.platform == "linux" && identity.version == "v24.19.0" &&
            identity.version == "v${slot.manifest.nodeVersion.removePrefix("v")}" &&
            identity.architecture == slot.manifest.nodeArchitecture) { "HOST_UNAVAILABLE" }
        val pinBytes = asset("android-official-dsh-pins-${identity.architecture}.json", 512 * 1024)
        val pinAsset = JSONObject(pinBytes.toString(Charsets.UTF_8))
        check(pinAsset.getInt("schema") == 1 && pinAsset.getString("dshVersion") == slot.manifest.dshVersion &&
            pinAsset.getString("architecture") == identity.architecture &&
            pinAsset.getString("archiveSha256") == slot.manifest.archiveSha256.lowercase() &&
            pinAsset.getString("dependencyLockSha256") == slot.manifest.dependencyLockSha256?.lowercase() &&
            pinAsset.getJSONObject("node").getString("sha256") == linux.nodeSha256 &&
            pinAsset.getJSONObject("node").getLong("bytes") == linux.nodeExecutable.length()) { "HOST_UNAVAILABLE" }
        val code = prepareSignedLaunchAssets()
        val mobileManifest = app.assets.open("mobile-ui/manifest.json").use {
            JSONObject(ConnectionPackageValidator.readBounded(it, 16 * 1024).toString(Charsets.UTF_8))
        }
        val uiSources = File(app.filesDir, "dsh-extension-packages")
        val uiHash = mobileManifest.getString("sha256").also { check(it.matches(HEX64)) { "HOST_UNAVAILABLE" } }
        val ui = DshMobileUiPackage.Installed(File(uiSources, "mobile-ui/$uiHash/package"), uiSources,
            "/dafeiyu-extension-packages/mobile-ui/$uiHash/package")
        val uiInstaller = DshMobileUiPackage(app)
        uiInstaller.verifyInstalled(ui)
        val pluginManifest = app.assets.open("connection-plugin/manifest.json").use {
            ConnectionPackageValidator.manifest(ConnectionPackageValidator.readBounded(it, ConnectionPackageValidator.MAX_MANIFEST))
        }
        val plugin = ConnectionPluginPackage(app).current() ?: error("HOST_UNAVAILABLE")
        val pluginSlot = DshArchiveLaunchContract.verifyPreparedPackage(pluginManifest, plugin)
        requireOwnedDirectory(home)
        val workspaces = File(app.filesDir, "linux-workspaces").also(::requireOwnedDirectory)
        val order = File(app.filesDir, "dsh-provider-order").also(::requireOwnedDirectory)
        val mounts = linkedMapOf("/dafeiyu-dsh" to slot.directory.canonicalFile,
            "/dafeiyu-dsh-home" to home.canonicalFile, "/dafeiyu-dsh-launch" to code.directory.canonicalFile,
            "/dafeiyu-workspaces" to workspaces.canonicalFile, "/dafeiyu-provider-order" to order.canonicalFile,
            "/dafeiyu-extension-packages" to uiSources.canonicalFile,
            DshArchiveLaunchContract.CONNECT_MOUNT to pluginSlot.canonicalFile)
        val futureCwd = if (plan.cwd == "/root") File(linux.rootfsDirectory, "root")
            else File(workspaces, plan.cwd.removePrefix("/dafeiyu-workspaces").trimStart('/'))
        requireOwnedDirectory(futureCwd, runtimeRoots)
        val workspace = futureCwd.canonicalFile.takeUnless { plan.cwd == "/root" }
        val environment = runtime.managedLaunchEnvironment(DshArchiveLaunchContract.environment(), mounts)
        val helperCommand = listOf("/usr/local/bin/node", "/dafeiyu-dsh-launch/android-official-dsh-port.mjs",
            "/dafeiyu-dsh", "/dafeiyu-dsh-home")
        val futureFiles = JSONArray()
        for ((name, bytes) in code.assets) futureFiles.put(JSONObject().put("role", name.removeSuffix(".mjs"))
            .put("file", "/dafeiyu-dsh-launch/$name").put("bytes", bytes.size).put("sha256", hash(bytes)))
        for ((role, relative) in CONNECT_FILES) {
            val actual = File(plugin, relative)
            val bytes = actual.inputStream().use { ConnectionPackageValidator.readBounded(it, 8 * 1024 * 1024) }
            futureFiles.put(JSONObject().put("role", role).put("file", "${DshArchiveLaunchContract.CONNECT_MOUNT}/package/$relative")
                .put("bytes", bytes.size).put("sha256", hash(bytes)))
        }
        val binding = JSONObject().put("schema", 1).put("realm", "android-dsh-storage-v1").put("mode", "android")
            .put("architecture", identity.architecture).put("nodeSha256", linux.nodeSha256)
            .put("slotManifestDigest", slot.manifest.digest()).put("archiveSha256", slot.manifest.archiveSha256.lowercase())
            .put("dependencyLock", JSONObject().put("file", "/dafeiyu-dsh/dsh-bundle.lock.json")
                .put("sha256", pinAsset.getString("dependencyLockSha256")))
            .put("providerOrderFile", "/dafeiyu-provider-order/order.json")
            .put("mobileUi", JSONObject().put("directory", ui.guestPath).put("sourceRoot", "/dafeiyu-extension-packages/mobile-ui")
                .put("manifest", JSONObject(mobileManifest.toString())))
            .put("futureLaunch", JSONObject().put("cwd", plan.cwd)
                .put("argv", JSONArray(DshArchiveLaunchContract.command(app.applicationInfo.nativeLibraryDir, plan.port, ui.guestPath)))
                .put("environment", JSONObject(environment)).put("files", futureFiles))
            .put("nativeGraphAssetSha256", hash(pinBytes))
            .put("linuxRuntime", JSONObject().put("rootfs", linux.rootfsDirectory.path).put("receiptSha256", linux.receiptSha256)
                .put("nativeDirectory", linux.nativeDirectory.path).put("nativeHashesDigest", linux.nativeHashesDigest))
            .put("hostMounts", JSONArray((mounts + listOfNotNull(workspace?.let { "/workspace" to it }).toMap()).entries.map { (guest, file) ->
                JSONObject().put("guest", guest).put("host", file.canonicalPath)
            }))
        val initialization = JSONObject().put("scopeId", scopeId).put("binding", binding)
            .put("runtimePins", JSONObject(pinAsset.toString()).getJSONArray("runtimePins"))
        val prepared = Prepared(slot, linux, code, mobileManifest, ui, uiInstaller, pluginManifest, plugin,
            pinAsset, pinBytes, mounts.toMap(), runtimeRoots, futureCwd, workspace, environment.toMap(), helperCommand,
            AndroidDshNodeTraceeVerifier(linux, helperCommand), initialization)
        prepared.verifyFiles()
        return prepared
    }

    private inner class Prepared(
        val slot: DshRuntimeSlot,
        val runtimeIdentity: LinuxManagedRuntimeIdentity,
        val code: SignedCode,
        val mobileManifest: JSONObject,
        val mobileUi: DshMobileUiPackage.Installed,
        val uiInstaller: DshMobileUiPackage,
        val pluginManifest: ConnectionPackageValidator.Manifest,
        val plugin: File,
        val pins: JSONObject,
        val pinBytes: ByteArray,
        val mounts: Map<String, File>,
        val runtimeRoots: List<File>,
        val futureCwd: File,
        val workspace: File?,
        val futureEnvironment: Map<String, String>,
        val helperCommand: List<String>,
        val tracee: AndroidDshNodeTraceeVerifier,
        val initialization: JSONObject,
    ) {
        @Volatile var started: AndroidOfficialDshControlledPort.StartedHelper? = null

        fun verifyFiles() {
            check(hash(asset("android-official-dsh-pins-${slot.manifest.nodeArchitecture}.json", 512 * 1024)) == hash(pinBytes)) {
                "HOST_UNAVAILABLE"
            }
            for ((guest, actual) in mounts) {
                requireOwnedDirectory(actual, runtimeRoots)
                check(actual.canonicalFile == mounts.getValue(guest)) { "HOST_UNAVAILABLE" }
            }
            requireOwnedDirectory(futureCwd, runtimeRoots)
            DshExtensionTransaction.requireReady(home)
            DshArchiveLaunchContract.verifyOwningBundle(slot.directory, slot.manifest.dshVersion)
            check(hash(File(slot.directory, "dsh-bundle.lock.json"), 4 * 1024 * 1024) == pins.getString("dependencyLockSha256")) {
                "HOST_UNAVAILABLE"
            }
            for ((name, trusted) in code.assets) {
                val installed = File(code.directory, name)
                requirePlainFile(installed)
                check(installed.length() == trusted.size.toLong() && hash(installed, 8 * 1024 * 1024) == hash(trusted) &&
                    hash(asset(name, 8 * 1024 * 1024)) == hash(trusted)) { "HOST_UNAVAILABLE" }
            }
            verifyRuntimeGraph(slot.directory, pins, runtimeRoots)
            uiInstaller.verifyInstalled(mobileUi)
            DshArchiveLaunchContract.verifyPreparedPackage(pluginManifest, plugin)
            verifyExactUiProfile(home, mobileUi.guestPath)
            check(runtime.managedLaunchEnvironment(DshArchiveLaunchContract.environment(), mounts) == futureEnvironment) {
                "HOST_UNAVAILABLE"
            }
        }
    }

    private fun requireActualHelper(prepared: Prepared, helper: AndroidOfficialDshControlledPort.StartedHelper) {
        check(prepared.started === helper && helper.runtime === runtime && helper.process.isAlive &&
            helper.actualMounts.map { it.canonicalFile } == prepared.mounts.values.toList() + listOfNotNull(prepared.workspace)) { "HOST_UNAVAILABLE" }
    }

    private fun prepareSignedLaunchAssets(): SignedCode {
        val directory = File(app.noBackupFilesDir, "dsh-android-launch")
        noLinks(directory)
        require(!LinuxArchiveExtractor.isSymlink(directory) && (directory.isDirectory || directory.mkdirs())) { "HOST_UNAVAILABLE" }
        val assets = CODE_NAMES.associateWith { asset(it, 8 * 1024 * 1024) }
        for ((name, bytes) in assets) {
            val target = File(directory, name)
            for (suffix in listOf("", ".bak", ".new")) require(!LinuxArchiveExtractor.isSymlink(File(target.path + suffix))) { "HOST_UNAVAILABLE" }
            val atomic = AtomicFile(target)
            val existing = try { atomic.openRead().use { ConnectionPackageValidator.readBounded(it, 8 * 1024 * 1024) } }
                catch (missing: java.io.FileNotFoundException) { if (target.exists()) throw missing else null }
            if (existing == null) {
                val stream = atomic.startWrite()
                try { stream.write(bytes); atomic.finishWrite(stream) }
                catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
                Os.chmod(target.path, 0x180)
            } else check(MessageDigest.isEqual(existing, bytes)) { "HOST_UNAVAILABLE" }
        }
        return SignedCode(directory, assets)
    }

    private fun verifyExactUiProfile(actualHome: File, guestPath: String) {
        val profile = File(actualHome, "profiles/web")
        requireOwnedDirectory(profile)
        val manifest = File(profile, "package.json").also(::requirePlainFile)
        val document = JSONObject(manifest.inputStream().use { ConnectionPackageValidator.readBounded(it, 1024 * 1024) }.toString(Charsets.UTF_8))
        val bundles = document.getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles")
        check(bundles.length() in 1..128 && (0 until bundles.length()).count { bundles.get(it) == "dafeiyu-mobile-ui" } == 1 &&
            document.getJSONObject("dependencies").get("dafeiyu-mobile-ui") == "link:$guestPath") { "HOST_UNAVAILABLE" }
        val modules = File(profile, "node_modules").also(::requireOwnedDirectory)
        val link = File(modules, "dafeiyu-mobile-ui")
        check(LinuxArchiveExtractor.isSymlink(link) && Os.readlink(link.path) == guestPath) { "HOST_UNAVAILABLE" }
    }

    private fun verifyRuntimeGraph(slot: File, pins: JSONObject, runtimeRoots: List<File>) {
        val runtimePins = pins.getJSONArray("runtimePins")
        check(runtimePins.length() in 19..512) { "HOST_UNAVAILABLE" }
        val roots = pins.getJSONArray("graphPackages")
        val files = pins.getJSONArray("graphFiles")
        check(roots.length() in 10..128 && files.length() in 19..1536) { "HOST_UNAVAILABLE" }
        val expected = mutableSetOf<String>()
        for (index in 0 until files.length()) {
            val row = files.getJSONObject(index)
            val relative = trustedRelative(row.getString("relative"))
            check(expected.add(relative)) { "HOST_UNAVAILABLE" }
            val actual = File(slot, relative).also { requirePlainFile(it, runtimeRoots) }
            check(actual.length() == row.getLong("bytes") && hash(actual, 8 * 1024 * 1024) == row.getString("sha256")) { "HOST_UNAVAILABLE" }
        }
        val actual = mutableSetOf<String>()
        for (index in 0 until roots.length()) {
            val root = File(slot, trustedRelative(roots.getString(index))).also { requireOwnedDirectory(it, runtimeRoots) }
            for (entry in root.walkTopDown()) {
                check(!LinuxArchiveExtractor.isSymlink(entry)) { "HOST_UNAVAILABLE" }
                if (entry.isFile && GRAPH_EXTENSIONS.any(entry.name::endsWith)) actual += entry.relativeTo(slot).invariantSeparatorsPath
            }
        }
        check(actual == expected) { "HOST_UNAVAILABLE" }
        val seen = mutableSetOf<String>()
        for (index in 0 until runtimePins.length()) {
            val row = runtimePins.getJSONObject(index)
            val relative = trustedRelative(row.getString("relative"))
            check(seen.add(relative) && hash(File(slot, relative).also { requirePlainFile(it, runtimeRoots) }, 8 * 1024 * 1024) == row.getString("sha256")) {
                "HOST_UNAVAILABLE"
            }
        }
    }

    private fun requireOwnedDirectory(file: File, runtimeRoots: List<File> = emptyList()) {
        noLinks(file, runtimeRoots)
        require(file.isDirectory) { "HOST_UNAVAILABLE" }
    }

    private fun requirePlainFile(file: File, runtimeRoots: List<File> = emptyList()) {
        noLinks(file, runtimeRoots)
        require(file.isFile) { "HOST_UNAVAILABLE" }
    }

    private fun noLinks(file: File, runtimeRoots: List<File> = emptyList()) {
        // Context owns these boundaries. Android may expose their system
        // ancestors as /data/user/0 -> /data/data; those aliases are not
        // application-controlled links. Reject the boundary itself and every
        // child link, and independently require canonical containment.
        val base = OfficialDshPrivatePathRules.boundary(listOf(app.filesDir, app.noBackupFilesDir) + runtimeRoots, file)
        OfficialDshPrivatePathRules.requireParents(base.absoluteFile, base.canonicalFile,
            file.absoluteFile, file.canonicalFile, LinuxArchiveExtractor::isSymlink)
    }

    private fun trustedRelative(value: String): String = value.also {
        check(it.length in 1..512 && !it.startsWith('/') && '\\' !in it &&
            it.split('/').all { part -> part.isNotEmpty() && part !in setOf(".", "..") }) { "HOST_UNAVAILABLE" }
    }

    private fun asset(name: String, limit: Int): ByteArray = app.assets.open("dsh-runtime/$name").use {
        ConnectionPackageValidator.readBounded(it, limit)
    }.also { check(it.isNotEmpty()) { "HOST_UNAVAILABLE" } }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun hash(file: File, limit: Int): String = hash(file.inputStream().use { ConnectionPackageValidator.readBounded(it, limit) })
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private data class SignedCode(val directory: File, val assets: Map<String, ByteArray>)
    private class Shared {
        val lock = Mutex()
        val ownership = AndroidOfficialDshControlledPort.Ownership()
        val partialStarts = mutableSetOf<LinuxManagedStartupOwnership>()
        @Volatile var active: AndroidOfficialDshLaunchFactory.Session? = null
    }

    companion object {
        private val owners = ConcurrentHashMap<String, Shared>()
        private val HEX64 = Regex("[a-f0-9]{64}")
        private val GRAPH_EXTENSIONS = listOf(".js", ".mjs", ".cjs", ".json", ".yml", ".yaml", ".node")
        private val CODE_NAMES = listOf("android-launch.mjs", "android-sandbox.mjs", "android-provider-order.mjs",
            "android-persistence.mjs", "android-mobile-ui.mjs", "shared-persistence.mjs",
            "android-official-dsh-store.mjs", "android-official-dsh-port.mjs")
        private val CONNECT_FILES = mapOf("archive-preload" to "scripts/archive-preload.mjs",
            "remote-archive-dsh" to "lib/framework/remote-archive-dsh.js", "remote-archive" to "lib/framework/remote-archive.js")
    }
}

/** Pure containment rules; callers supply actual Context roots, canonical
 * resolution and lstat. No imported path can establish a trusted boundary. */
internal object OfficialDshPrivatePathRules {
    fun contains(root: File, file: File): Boolean = file == root || file.path.startsWith(root.path + File.separator)

    fun boundary(roots: List<File>, file: File): File = roots.firstOrNull { root ->
        contains(root.absoluteFile, file.absoluteFile) || contains(root.canonicalFile, file.absoluteFile)
    } ?: error("HOST_UNAVAILABLE")

    fun requireParents(rawBase: File, canonicalBase: File, file: File, resolved: File, isLink: (File) -> Boolean) {
        check(contains(canonicalBase, resolved) && !isLink(rawBase) && !isLink(canonicalBase)) { "HOST_UNAVAILABLE" }
        val sourceBase = listOf(rawBase, canonicalBase).firstOrNull { contains(it, file) } ?: error("HOST_UNAVAILABLE")
        check(file.relativeTo(sourceBase).invariantSeparatorsPath.split('/').none { it == "." || it == ".." }) { "HOST_UNAVAILABLE" }
        var cursor = file
        while (cursor != sourceBase) {
            check(!isLink(cursor)) { "HOST_UNAVAILABLE" }
            cursor = cursor.parentFile ?: error("HOST_UNAVAILABLE")
        }
    }
}

/** Exactly the only persisted future choices. Everything capable of affecting
 * executable loading is reconstructed from the local signed installation. */
internal data class OfficialDshTrustedLaunchPlan(val port: Int, val cwd: String) {
    fun json(): JSONObject = JSONObject().put("schema", 1).put("port", port).put("cwd", cwd)
    companion object {
        fun parse(value: JSONObject): OfficialDshTrustedLaunchPlan {
            check(value.keys().asSequence().toSet() == setOf("schema", "port", "cwd")) { "INVALID_JOURNAL" }
            fun integral(key: String): Long {
                val actual = value.get(key)
                check(actual is Int || actual is Long) { "INVALID_JOURNAL" }
                return (actual as Number).toLong()
            }
            check(integral("schema") == 1L) { "INVALID_JOURNAL" }
            val port = integral("port").also { check(it in 1024..65535) { "INVALID_JOURNAL" } }.toInt()
            val cwd = value.get("cwd") as? String ?: error("INVALID_JOURNAL")
            check(cwd == "/root" || cwd.length <= 180 && (cwd == "/dafeiyu-workspaces" || cwd.startsWith("/dafeiyu-workspaces/")) &&
                cwd.split('/').drop(1).all { it.matches(Regex("[A-Za-z0-9._-]+")) && it !in setOf(".", "..") }) { "INVALID_JOURNAL" }
            return OfficialDshTrustedLaunchPlan(port, cwd)
        }
    }
}
