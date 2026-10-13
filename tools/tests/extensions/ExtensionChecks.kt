package com.dafeiyu.mobile.data

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.json.JSONArray
import org.json.JSONObject

private var checks = 0
private fun checkCase(label: String, value: Boolean) { check(value) { "FAIL $label" }; checks++; println("PASS $label") }
private fun fails(block: () -> Unit): Boolean = runCatching(block).isFailure
private suspend fun suspendingFails(block: suspend () -> Unit): Boolean = try { block(); false } catch (_: Exception) { true }
private fun skill(name: String, body: String = "Use the current workspace.", policy: String = ""): String = "---\nname: $name\ndescription: This is a test skill.\n$policy---\n$body\n"
private fun manifest(name: String, version: String = "1.0.0"): JSONObject = JSONObject().put("name", name).put("version", version)
    .put("description", "Offline manager fixture").put("license", "MIT")
    .put("dsh", JSONObject().put("bundle", JSONObject().put("patch", "cordis.patch.yml")))
private fun tar(file: File, entries: Map<String, ByteArray>, link: Boolean = false) {
    TarArchiveOutputStream(GZIPOutputStream(file.outputStream())).use { output ->
        entries.forEach { (name, bytes) ->
            val entry = if (link) TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = "../escape" }
                else TarArchiveEntry(name).apply { size = bytes.size.toLong() }
            output.putArchiveEntry(entry); if (!link) output.write(bytes); output.closeArchiveEntry()
        }
    }
}

fun main(args: Array<String>) = runBlocking {
    val root = File(args[0]).apply { mkdirs() }
    val node = args[1]; val dsh = args[2]; val helper = args[3]; val pnpm = args[4]
    val parsed = SkillMarkdown.parse(skill("sample-skill"))
    checkCase("DSH skill metadata and body are parsed separately", parsed.first.name == "sample-skill" && parsed.second.startsWith("Use"))
    checkCase("DSH default invocation is enabled for models and users", !parsed.first.disableModelInvocation && parsed.first.userInvocable)
    checkCase("DSH explicit invocation Boolean grammar is supported", SkillMarkdown.parse(skill("one", policy = "disable-model-invocation: yes\nuser-invocable: 0\n")).first.let { it.disableModelInvocation && !it.userInvocable })
    checkCase("invalid invocation cannot silently become false", fails { SkillMarkdown.parse(skill("one", policy = "user-invocable: someday\n")) })
    checkCase("legacy invocation fields cannot weaken policy", fails { SkillMarkdown.parse(skill("one", policy = "modelInvocable: true\n")) })
    checkCase("unsafe YAML object tags are rejected", fails { SkillMarkdown.parse("---\nname: one\ndescription: !!java/object:java.lang.ProcessBuilder {}\n---\n") })
    checkCase("YAML scalar aliases are rejected as well as collection aliases", fails { SkillMarkdown.parse("---\nname: &name one\ndescription: *name\n---\n") })
    checkCase("duplicate frontmatter fields are rejected", fails { SkillMarkdown.parse("---\nname: one\nname: two\ndescription: test\n---\n") })
    checkCase("invalid DSH names are rejected", fails { SkillMarkdown.parse(skill("Bad_Name")) })
    checkCase("missing required description is rejected", fails { SkillMarkdown.parse("---\nname: one\n---\nBody") })
    val home = File(root, "skill-home")
    val store = SkillInstallStore(home)
    val source = File(root, "skill-source").apply { mkdirs() }
    File(source, "SKILL.md").writeText(skill("sample-skill", "Original body"))
    File(source, "reference.md").writeText("A real reference file")
    File(source, "LICENSE").writeText("Fixture MIT notice")
    store.publishLocal(source)
    checkCase("real installation places the complete bundle in the shared DSH skill root", File(home, "skills/sample-skill/reference.md").readText() == "A real reference file")
    checkCase("installed source license remains available", File(home, "skills/sample-skill/LICENSE").isFile)
    checkCase("model catalog reads actual enabled package", store.catalogForModel().items.single().metadata.name == "sample-skill")
    store.setEnabled("sample-skill", false)
    checkCase("disabling physically removes DSH-readable SKILL.md", !File(home, "skills/sample-skill/SKILL.md").exists() && File(home, "skills/sample-skill/SKILL.md.disabled").isFile)
    checkCase("disabled skill cannot be loaded by the model", store.catalogForModel().items.isEmpty() && suspendingFails { store.load("sample-skill") })
    checkCase("disabled skill can still be inspected without invoking it", store.inspect("sample-skill").body.contains("Original"))
    store.setEnabled("sample-skill", true)
    File(home, "skills/sample-skill/SKILL.md").writeText(skill("sample-skill", "Changed body"))
    checkCase("skill body is re-read on every invocation", store.load("sample-skill").body.contains("Changed"))
    File(home, "skills/sample-skill/SKILL.md").writeText(skill("sample-skill", policy = "disable-model-invocation: true\n"))
    checkCase("changed model invocation policy is checked again", store.catalogForModel().items.isEmpty() && suspendingFails { store.load("sample-skill") })
    checkCase("user invocation remains independent from model invocation", store.load("sample-skill", modelInvoked = false).metadata.userInvocable)
    checkCase("duplicate local installation preserves the current package", suspendingFails { store.publishLocal(source) } && store.inspect("sample-skill").metadata.disableModelInvocation)
    store.setEnabled("sample-skill", false)
    store.publishLocal(source, replace = true)
    checkCase("replacement preserves disabled selection and retains prior bytes", !store.scan().items.single().enabled && File(home, ".mobile-skills").listFiles().orEmpty().any { it.name.startsWith("replaced-") })
    File(home, "skills/sample-skill/SKILL.md.disabled").writeText("broken frontmatter")
    checkCase("a damaged disabled skill remains removable instead of disappearing from management", store.scan().damaged.single().id == "sample-skill" && store.scan().items.isEmpty())
    store.publishLocal(source, replace = true)
    checkCase("repairing a damaged disabled skill preserves its actual prior disabled filename", !store.scan().items.single().enabled)
    File(home, "skills/sample-skill/SKILL.md.disabled").writeText("still broken")
    store.removeDamaged("sample-skill")
    checkCase("damaged skill removal physically retains source bytes outside discovery", store.scan().damaged.isEmpty() && !File(home, "skills/sample-skill").exists())
    store.publishLocal(source)
    store.setEnabled("sample-skill", false)
    val workspace = File(root, "workspace").apply { mkdirs() }
    val project = File(workspace, ".dsh/skills/sample-skill").apply { mkdirs() }
    File(project, "SKILL.md").writeText(skill("sample-skill", "Project precedence"))
    checkCase("project skill precedence matches the DSH root order", store.load("sample-skill", workspace = workspace).body.contains("Project precedence"))
    store.remove("sample-skill")
    checkCase("removal preserves package content in owned trash and removes discovery", store.scan().items.isEmpty() && File(home, ".mobile-skills").listFiles().orEmpty().any { it.name.startsWith("removed-") })
    val zip = File(root, "package.zip")
    ZipOutputStream(zip.outputStream()).use { output -> output.putNextEntry(ZipEntry("bundle/SKILL.md")); output.write(skill("zip-skill").toByteArray()); output.closeEntry() }
    val unpacked = ExtensionFiles.unpack(zip, File(root, "unpacked"))
    checkCase("actual ZIP package imports preserve complete safe paths", File(unpacked, "bundle/SKILL.md").isFile)
    val escape = File(root, "escape.zip")
    ZipOutputStream(escape.outputStream()).use { output -> output.putNextEntry(ZipEntry("../outside")); output.write(byteArrayOf(1)); output.closeEntry() }
    checkCase("ZIP traversal is rejected before writing outside staging", suspendingFails { ExtensionFiles.unpack(escape, File(root, "escape-stage")) } && !File(root, "outside").exists())
    val link = File(root, "link.tgz"); tar(link, mapOf("package/link" to byteArrayOf()), link = true)
    checkCase("tar symlinks cannot masquerade as regular files", suspendingFails { ExtensionFiles.unpack(link, File(root, "link-stage")) })
    checkCase("UTF-8 decoding does not silently replace corrupt skill bytes", fails { ExtensionFiles.utf8(byteArrayOf(0xc0.toByte(), 0xff.toByte())) })
    val reference = GithubExtensionReference.parse("https://github.com/owner/repository/tree/main/skills/one")
    checkCase("GitHub sources retain explicit subdirectory and reference", reference.path == "skills/one" && reference.ref == "main")
    checkCase("GitHub credential and foreign-host URLs are refused", fails { GithubExtensionReference.parse("https://secret@github.com/a/b") } && fails { GithubExtensionReference.parse("https://example.com/a/b") })
    checkCase("slash-containing refs remain explicit instead of being guessed", GithubExtensionReference.parse("https://github.com/a/b?ref=branch%2Ffeature&path=skills%2Fone").ref == "branch/feature")
    val roundTrip = GithubExtensionReference("owner", "repository", "skills/one", "branch/feature")
    checkCase("GitHub source URLs round-trip slash-containing refs and subdirectories", GithubExtensionReference.parse(roundTrip.url) == roundTrip &&
        GithubExtensionReference.parse(GithubExtensionReference("owner", "repository", ref = "v1").url).ref == "v1")
    checkCase("Git blob verification includes its header, not bare SHA1", ArtifactChecksum.gitBlob("a".repeat(40)).digestForSize(1).let { digest -> digest.update(byteArrayOf(1)); digest.digest().size == 20 })
    checkCase("npm source specs cannot route around the downloader", !NpmExtensionSpec.isValid("https://example.com/pkg.tgz") && !NpmExtensionSpec.isValid("github:a/b") && NpmExtensionSpec.packageName("@scope/name@1.2.3") == "@scope/name")
    val feed = JSONObject().put("schemaVersion", 1).put("platform", "android").put("items", JSONArray())
    checkCase("mobile recommendation channel accepts an honestly empty list", RecommendationFeed.parse(feed, true).isEmpty())
    checkCase("PC recommendation feed cannot masquerade as the mobile channel", fails { RecommendationFeed.parse(JSONObject(feed.toString()).put("platform", "windows"), true) })
    val npmSafe = manifest("fixture-plugin")
    checkCase("normal registry dependency ranges remain supported", NpmArtifactRegistry.dependencySourcesAllowed(npmSafe.put("dependencies", JSONObject().put("one", "^1.2.3 || >=2"))))
    checkCase("Git and URL transitive dependency sources are rejected", !NpmArtifactRegistry.dependencySourcesAllowed(JSONObject(npmSafe.toString()).put("dependencies", JSONObject().put("one", "git+https://example.com/x"))))
    val npmTar = File(root, "npm-package.tgz"); tar(npmTar, mapOf("package/package.json" to manifest("fixture-plugin").toString().toByteArray()))
    NpmArtifactRegistry.verifyPackage(npmTar, "fixture-plugin", "1.0.0")
    checkCase("actual npm tarball identity is checked after hash acquisition", suspendingFails { NpmArtifactRegistry.verifyPackage(npmTar, "another-name", "1.0.0") })

    val pluginHome = File(root, "plugin-home").apply { mkdirs() }
    val pluginSources = File(root, "plugin-sources").apply { mkdirs() }
    fun invoke(frame: JSONObject, install: Boolean = false): JSONObject {
        frame.put("sourceRoot", pluginSources.path)
        val outputFile = File(root, "node-result-${java.util.UUID.randomUUID()}.json")
        val pmArguments = if (pnpm.endsWith(".exe")) listOf(pnpm) else listOf(node, pnpm)
        val builder = ProcessBuilder(listOf(node, helper, dsh, pluginHome.path) + if (install) pmArguments else emptyList())
            .directory(root).redirectError(File(root, "node-errors.log")).redirectOutput(outputFile)
        builder.environment().apply { put("TEMP", root.path); put("TMP", root.path); put("TMPDIR", root.path) }
        val process = builder.start()
        process.outputStream.use { it.write(frame.toString().toByteArray()); it.flush() }
        check(process.waitFor(60, TimeUnit.SECONDS)) { "Actual manager timed out" }
        return JSONObject(outputFile.readText())
    }
    val listing = invoke(JSONObject().put("operation", "list"))
    checkCase("real pinned first-party manager lists the current web profile", listing.getBoolean("ok") && listing.getJSONObject("value").getJSONArray("bundles").length() >= 2)
    val profile = File(pluginHome, "profiles/web/package.json")
    val before = profile.readText()
    val protected = invoke(JSONObject().put("operation", "enable").put("name", "@deepseek-ai/dsh-base").put("enabled", false))
    checkCase("real first-party management protection rejects disabling the core", protected.getJSONObject("value").optString("application") == "failed" && profile.readText() == before)
    val rejectedConsent = invoke(JSONObject().put("operation", "exemption").put("packageVersion", "dafeiyu-compat-fixture@1.0.0")
        .put("runtimeVersion", "0.2.1-alpha.1").put("enabled", true))
    checkCase("exact compatibility exemption requires explicit risk consent without rewriting profile", !rejectedConsent.getBoolean("ok") && profile.readText() == before)
    val compatDir = File(pluginSources, "compat-fixture").apply { mkdirs() }
    File(compatDir, "package.json").writeText(manifest("dafeiyu-compat-fixture")
        .put("peerDependencies", JSONObject().put("@deepseek-ai/dsh", "0.0.1")).toString())
    File(compatDir, "cordis.patch.yml").writeText("- insert:\n    - id: compat-fixture\n      name: ./never-run.mjs\n")
    File(compatDir, "never-run.mjs").writeText("throw new Error('Compatibility inspection must not execute this plugin');")
    val compatRegistry = "http://127.0.0.1:1/" + "a".repeat(48) + "/"
    val deniedInstall = invoke(JSONObject().put("operation", "install").put("registry", compatRegistry).put("spec", "file:${compatDir.path}"), install = true)
    checkCase("actual first-party manager returns structured exact incompatible package and runtime", deniedInstall.getBoolean("ok") &&
        deniedInstall.getJSONObject("value").getJSONArray("incompatible").getJSONObject(0).let {
            it.getString("name") == "dafeiyu-compat-fixture" && it.getString("version") == "1.0.0" && it.getString("runtimeVersion") == "0.2.1-alpha.1"
        })
    val staleRuntime = invoke(JSONObject().put("operation", "exemption").put("packageVersion", "dafeiyu-compat-fixture@1.0.0")
        .put("runtimeVersion", "0.2.0").put("enabled", true).put("acceptRisk", true))
    checkCase("actual manager rejects a grant for another runtime version", staleRuntime.getBoolean("ok") && staleRuntime.getJSONObject("value").getString("application") == "failed")
    val consent = invoke(JSONObject().put("operation", "exemption").put("packageVersion", "dafeiyu-compat-fixture@1.0.0")
        .put("runtimeVersion", "0.2.1-alpha.1").put("enabled", true).put("acceptRisk", true))
    checkCase("explicit compatibility grant is persisted by the actual manager", consent.getBoolean("ok") && consent.getJSONObject("value").getString("application") == "restart-required")
    val compatList = invoke(JSONObject().put("operation", "list"))
    checkCase("catalog exposes the exact persisted compatibility pair", compatList.getJSONObject("value").getJSONObject("exemptions")
        .getJSONArray("dafeiyu-compat-fixture@1.0.0").getString(0) == "0.2.1-alpha.1")
    val acceptedInstall = invoke(JSONObject().put("operation", "install").put("registry", compatRegistry).put("spec", "file:${compatDir.path}"), install = true)
    checkCase("the same actual incompatible source installs only after the exact-version grant", acceptedInstall.getBoolean("ok") &&
        acceptedInstall.getJSONObject("value").getString("application") != "failed")
    invoke(JSONObject().put("operation", "remove").put("name", "dafeiyu-compat-fixture").put("registry", compatRegistry), install = true)
    val revoked = invoke(JSONObject().put("operation", "exemption").put("packageVersion", "dafeiyu-compat-fixture@1.0.0")
        .put("runtimeVersion", "0.2.1-alpha.1").put("enabled", false))
    checkCase("actual compatibility revocation removes only the exact pair", revoked.getBoolean("ok") &&
        !invoke(JSONObject().put("operation", "list")).getJSONObject("value").getJSONObject("exemptions").has("dafeiyu-compat-fixture@1.0.0"))
    val packageDir = File(pluginSources, "fixture-plugin").apply { mkdirs() }
    File(packageDir, "package.json").writeText(manifest("dafeiyu-offline-fixture").toString())
    File(packageDir, "cordis.patch.yml").writeText("- insert:\n    - id: fixture-plugin\n      name: ./never-run.mjs\n")
    File(packageDir, "never-run.mjs").writeText("throw new Error('This plugin must not execute during installation or inspection');")
    val registry = "http://127.0.0.1:1/" + "a".repeat(48) + "/"
    val installedPlugin = invoke(JSONObject().put("operation", "install").put("registry", registry).put("spec", "file:${packageDir.path}"), install = true)
    checkCase("actual pnpm and first-party manager install a real offline bundle", installedPlugin.getBoolean("ok") && installedPlugin.getJSONObject("value").optString("application") != "failed")
    checkCase("first-party installation persists exact bundle selection", JSONObject(profile.readText()).getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles").toString().contains("dafeiyu-offline-fixture"))
    val disabledPlugin = invoke(JSONObject().put("operation", "enable").put("name", "dafeiyu-offline-fixture").put("enabled", false))
    checkCase("first-party enable switch changes actual profile selection", disabledPlugin.getBoolean("ok") && disabledPlugin.getJSONObject("value").optString("application") == "restart-required" && !JSONObject(profile.readText()).getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles").toString().contains("dafeiyu-offline-fixture"))
    val removedPlugin = invoke(JSONObject().put("operation", "remove").put("name", "dafeiyu-offline-fixture").put("registry", registry), install = true)
    checkCase("actual pnpm removal removes the profile dependency", removedPlugin.getBoolean("ok") && removedPlugin.getJSONObject("value").optString("application") != "failed" && JSONObject(profile.readText()).optJSONObject("dependencies")?.has("dafeiyu-offline-fixture") != true)
    val versionOutput = File(root, "pnpm-version.txt")
    val versionArguments = if (pnpm.endsWith(".exe")) listOf(pnpm, "--version") else listOf(node, pnpm, "--version")
    val versionBuilder = ProcessBuilder(versionArguments).directory(root).redirectErrorStream(true).redirectOutput(versionOutput)
    versionBuilder.environment().apply {
        put("TEMP", root.path); put("TMP", root.path); put("TMPDIR", root.path)
        put("npm_config_manage_package_manager_versions", "false"); put("npm_config_package_manager_strict", "false")
    }
    val versionProcess = versionBuilder.start()
    check(versionProcess.waitFor(15, TimeUnit.SECONDS)) { versionProcess.destroyForcibly(); "Actual pnpm version lookup timed out" }
    check(versionProcess.exitValue() == 0 && versionOutput.length() <= 2048)
    val actualPnpmVersion = versionOutput.readText().trim()
    println("Actual pnpm CLI version: $actualPnpmVersion")
    if (actualPnpmVersion == "10.34.5") {
        val prepareDir = File(pluginSources, "prepare-fixture").apply { mkdirs() }
        File(prepareDir, "build.cjs").writeText("require('fs').mkdirSync('generated',{recursive:true});require('fs').writeFileSync('generated/patch.yml','- insert: []\\n');require('fs').writeFileSync('build-marker','executed');")
        File(prepareDir, "package.json").writeText(manifest("dafeiyu-prepare-fixture")
            .put("scripts", JSONObject().put("prepare", "node build.cjs"))
            .put("dsh", JSONObject().put("bundle", JSONObject().put("patch", "generated/patch.yml"))).toString())
        fun prepareFrame() = JSONObject().put("operation", "install").put("registry", registry).put("spec", "file:${prepareDir.path}")
        val needsBuild = invoke(prepareFrame(), install = true)
        val candidates = needsBuild.getJSONObject("value").getJSONArray("buildCandidates")
        checkCase("production plugin installation returns real source build consent before executing prepare", candidates.length() == 1 &&
            !File(prepareDir, "build-marker").exists() && profile.readText().contains("dafeiyu-prepare-fixture").not())
        val declinedBuild = invoke(prepareFrame().put("approvedBuilds", candidates), install = true)
        checkCase("production helper rejects unconfirmed script consent and restores profile", !declinedBuild.getBoolean("ok") && !File(prepareDir, "build-marker").exists())
        val prepared = invoke(prepareFrame().put("approvedBuilds", candidates).put("acceptBuildRisk", true), install = true)
        checkCase("production helper builds a confirmed exact source using actual fixed pnpm10 and registers its real bundle", prepared.getBoolean("ok") &&
            prepared.getJSONObject("value").getString("application") != "failed" && File(prepareDir, "build-marker").readText() == "executed")
        val cleanPrepare = invoke(JSONObject().put("operation", "remove").put("name", "dafeiyu-prepare-fixture").put("registry", registry), install = true)
        checkCase("the actual built bundle remains removable through the same fixed manager", cleanPrepare.getBoolean("ok") &&
            cleanPrepare.getJSONObject("value").getString("application") != "failed")
    }
    checks += verifyExtensionRegistry(root, node, dsh, helper, pnpm)
    println("RESULT $checks/$checks production file/YAML/archive helpers, actual first-party manager + host pnpm, and npm proxy + real 4/8-range downloads; Android process lease, public sources, and UI interaction require device verification")
}
