package com.dafeiyu.mobile.data

import java.io.File
import java.nio.file.Files
import org.json.JSONObject

fun main(args: Array<String>) {
    val root = File(args[0]).apply { check(mkdirs() || isDirectory) }
    val assets = File(args[1])
    val owningMetadata = File(args[2])
    var passed = 0
    var folders = 0
    fun test(name: String, body: () -> Unit) {
        try { body() } catch (error: Throwable) { throw AssertionError(name, error) }
        passed++; println("PASS $name")
    }
    fun rejected(body: () -> Unit) { check(runCatching(body).isFailure) }
    fun fixture(): Map<String, File> {
        val folder = File(root, "mapping-${++folders}").apply { check(mkdir()) }
        val bundle = File(folder, "bundle").apply { check(mkdir()) }
        val home = File(folder, "home").apply { check(mkdir()) }
        val metadata = File(bundle, LinuxDshArchiveEnvironment.ANCHOR_RELATIVE)
        check(metadata.parentFile!!.mkdirs()); owningMetadata.copyTo(metadata)
        return mapOf(LinuxDshArchiveEnvironment.BUNDLE to bundle, LinuxDshArchiveEnvironment.HOME to home)
    }
    val environment = DshArchiveLaunchContract.environment()
    test("only the fixed owning anchor and home-derived journal enter the managed process") {
        check(environment == mapOf("DSH_HOME" to "/dafeiyu-dsh-home",
            "DAFEIYU_DSH_INSTALL_ANCHOR" to "/dafeiyu-dsh/node_modules/@deepseek-ai/dsh/package.json",
            "DAFEIYU_DSH_ARCHIVE_DIRECTORY" to "/dafeiyu-dsh-home/dafeiyu-connection-plugin/workspace-lifecycle"))
        val mounts = fixture()
        LinuxDshArchiveEnvironment.validate(environment, mounts)
        DshArchiveLaunchContract.verifyOwningBundle(mounts.getValue(LinuxDshArchiveEnvironment.BUNDLE), "0.2.1-alpha.1")
    }
    test("node loads the fixed preloader before the Android launcher and CLI arguments") {
        val command = DshArchiveLaunchContract.command("/data/app/fixed/lib/x86_64", 5432)
        check(command.take(4) == listOf("/usr/local/bin/node", "--import",
            "/dafeiyu-connection-slot/package/scripts/archive-preload.mjs", "/dafeiyu-dsh-launch/android-launch.mjs"))
        check(command.takeLast(7) == listOf("web", "--host", "127.0.0.1", "--port", "5432", "--no-open").let {
            listOf("/data/app/fixed/lib/x86_64") + it
        })
        rejected { DshArchiveLaunchContract.command("/data/app/fixed/lib/x86_64", 80) }
    }
    test("ordinary Node and legacy exact-mounted DSH_HOME retain their previous environment boundary") {
        LinuxDshArchiveEnvironment.validate(emptyMap(), emptyMap())
        val mounts = fixture()
        LinuxDshArchiveEnvironment.validate(mapOf("DSH_HOME" to "/legacy-home"), mapOf("/legacy-home" to mounts.getValue(LinuxDshArchiveEnvironment.HOME)))
        rejected { LinuxDshArchiveEnvironment.validate(mapOf("DSH_HOME" to "/missing"), mounts) }
    }
    test("NODE_OPTIONS proxy credentials and capability flags remain refused") {
        for (name in listOf("NODE_OPTIONS", "HTTPS_PROXY", "API_KEY", "DAFEIYU_ARCHIVE_CAPABILITY"))
            rejected { LinuxDshArchiveEnvironment.validate(environment + (name to "claimed"), fixture()) }
    }
    for (key in listOf(LinuxDshArchiveEnvironment.ANCHOR_ENV, LinuxDshArchiveEnvironment.DIRECTORY_ENV, "DSH_HOME"))
        test("missing $key refuses partial preload admission") {
            rejected { LinuxDshArchiveEnvironment.validate(environment - key, fixture()) }
        }
    test("plugin-home anchors traversal aliases and another home journal are refused") {
        val mutations = listOf(
            LinuxDshArchiveEnvironment.ANCHOR_ENV to "/dafeiyu-connection-slot/package/package.json",
            LinuxDshArchiveEnvironment.ANCHOR_ENV to "/dafeiyu-dsh/node_modules/../node_modules/@deepseek-ai/dsh/package.json",
            LinuxDshArchiveEnvironment.DIRECTORY_ENV to "/another-home/dafeiyu-connection-plugin/workspace-lifecycle",
            LinuxDshArchiveEnvironment.DIRECTORY_ENV to "/dafeiyu-dsh-home/dafeiyu-connection-plugin/workspace-lifecycle/../workspace-lifecycle",
        )
        for ((key, value) in mutations) rejected { LinuxDshArchiveEnvironment.validate(environment + (key to value), fixture()) }
    }
    test("both actual private mounts must be present") {
        for (mount in listOf(LinuxDshArchiveEnvironment.BUNDLE, LinuxDshArchiveEnvironment.HOME))
            rejected { LinuxDshArchiveEnvironment.validate(environment, fixture() - mount) }
    }
    test("owning package anchor cannot be missing or a directory") {
        val mounts = fixture(); val anchor = File(mounts.getValue(LinuxDshArchiveEnvironment.BUNDLE), LinuxDshArchiveEnvironment.ANCHOR_RELATIVE)
        check(anchor.delete()); rejected { LinuxDshArchiveEnvironment.validate(environment, mounts) }
        check(anchor.mkdir()); rejected { LinuxDshArchiveEnvironment.validate(environment, mounts) }
    }
    test("archive state path cannot replace a regular file") {
        val mounts = fixture(); val target = File(mounts.getValue(LinuxDshArchiveEnvironment.HOME), LinuxDshArchiveEnvironment.ARCHIVE_RELATIVE)
        check(target.parentFile!!.mkdirs()); target.writeText("not-a-directory")
        rejected { LinuxDshArchiveEnvironment.validate(environment, mounts) }
    }
    test("fixed owning metadata rejects another package version license and oversized input") {
        for ((key, value) in listOf("name" to "untrusted", "version" to "0.2.1-alpha.2", "license" to "unreviewed")) {
            val mounts = fixture(); val bundle = mounts.getValue(LinuxDshArchiveEnvironment.BUNDLE)
            val file = File(bundle, LinuxDshArchiveEnvironment.ANCHOR_RELATIVE)
            file.writeText(JSONObject(file.readText()).put(key, value).toString())
            rejected { DshArchiveLaunchContract.verifyOwningBundle(bundle, "0.2.1-alpha.1") }
        }
        val mounts = fixture(); val bundle = mounts.getValue(LinuxDshArchiveEnvironment.BUNDLE)
        rejected { DshArchiveLaunchContract.verifyOwningBundle(bundle, "0.2.1-alpha.2") }
        File(bundle, LinuxDshArchiveEnvironment.ANCHOR_RELATIVE).writeBytes(ByteArray(65537))
        rejected { DshArchiveLaunchContract.verifyOwningBundle(bundle, "0.2.1-alpha.1") }
    }
    val manifest = ConnectionPackageValidator.manifest(File(assets, "manifest.json").readBytes())
    val archive = File(assets, manifest.fileName).readBytes()
    fun prepared(): File {
        val folder = File(root, "prepared-${++folders}").apply { check(mkdir()) }
        val slot = File(folder, manifest.sha256).apply { check(mkdir()) }
        val plugin = ConnectionPackageValidator.extract(manifest, archive, slot)
        File(slot, "archive.tgz").writeBytes(archive)
        return plugin
    }
    test("actual frozen plugin is fully verified and its slot becomes the managed process mount") {
        val plugin = prepared()
        check(DshArchiveLaunchContract.verifyPreparedPackage(manifest, plugin) == plugin.parentFile)
        println("EVIDENCE preparedVersion=${manifest.version} bytes=${manifest.sizeBytes} sha256=${manifest.sha256} runtimeLoaded=false capabilityClaim=false")
    }
    test("unprepared another-version missing-entry and modified preloader are rejected") {
        val plugin = prepared()
        rejected { DshArchiveLaunchContract.verifyPreparedPackage(manifest.copy(version = "0.2.0"), plugin) }
        val preload = File(plugin, "scripts/archive-preload.mjs")
        check(preload.delete()); rejected { DshArchiveLaunchContract.verifyPreparedPackage(manifest, plugin) }
        preload.writeText("export const bypass = true;")
        rejected { DshArchiveLaunchContract.verifyPreparedPackage(manifest, plugin) }
    }
    test("extra files in the plugin slot cannot silently change its trusted content") {
        val plugin = prepared()
        File(plugin, "unreviewed.js").writeText("unreviewed")
        rejected { DshArchiveLaunchContract.verifyPreparedPackage(manifest, plugin) }
    }
    val linked = fixture(); val home = linked.getValue(LinuxDshArchiveEnvironment.HOME)
    val link = File(home, "dafeiyu-connection-plugin"); val outside = File(root, "outside").apply { check(mkdir()) }
    if (runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess) {
        test("archive journal parent link to another private tree is refused") { rejected { LinuxDshArchiveEnvironment.validate(environment, linked) } }
    } else println("SKIP symbolic-link fixture unavailable on this Windows account; Android lstat verification awaits device test")
    println("RESULT $passed/$passed PASS productionLaunchContract=true actualFrozenPackage=true managedProcessStarted=false android=false paidRequests=0")
}
