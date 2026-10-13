package com.dafeiyu.mobile.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Genuine PC sevenzip -> actual Android production codec -> genuine Android sevenzip. */
fun main(args: Array<String>) = runBlocking {
    val root = File(args[0]).canonicalFile.apply { check(isDirectory || mkdirs()) }
    val source = File(root, "pc-input.dym")
    Files.copy(File(args[1]).toPath(), source.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    val codec = DymArchiveStore(root)
    val native = AndroidBackupStore()
    val staged = codec.stageImport(source, { native.import(it) })
    File(root, "stage-warnings.json").writeText(org.json.JSONArray(staged.warnings).toString())
    var passed = 0
    fun checkCase(value: Boolean, label: String) { check(value) { label }; passed++; println("PASS $label") }
    checkCase(staged.warnings.isEmpty(), "PC-produced sevenzip accepted without codec warnings")
    checkCase(native.import(checkNotNull(staged.androidBackup)).conversations.single().title == "Phone history", "actual native history document survives PC packing")
    checkCase(staged.providerConfiguration?.getJSONObject("credentials")?.getJSONObject("deepseek")?.getString("key") == "fixture-launcher-key",
        "provider Key survives actual Android decoder")
    checkCase(staged.sharedCredentials?.getJSONObject("refs")?.getString("FIXTURE_PROVIDER_KEY") == "fixture-other-key",
        "home credentials survive actual Android decoder")
    checkCase(staged.sharedCredentials?.toString()?.contains("UNRELATED_IDENTITY") == false,
        "unrelated device reference excluded")
    val retained = DymRetainedResourceStore(root)
    retained.retain(staged)
    val snapshot = retained.read()
    checkCase(snapshot.pcPreferences?.getJSONObject("groups")?.getJSONObject("appearance")?.getString("Theme") == "malformed-theme",
        "foreign PC preference remains opaque on Android")
    checkCase(snapshot.sharedCredentials?.getJSONObject("refs")?.getString("FIXTURE_PROVIDER_KEY") == "fixture-other-key",
        "retained credentials preserved without runtime components")
    val destination = File(root, "android-reexport.dym")
    codec.export(destination, DymExportInput(snapshot.directories, staged.androidBackup, staged.androidPreferences,
        snapshot.pcPreferences, staged.providerConfiguration, snapshot.sharedCredentials))
    val bytes = destination.readBytes()
    checkCase(bytes.take(6) == listOf(0x37, 0x7a, 0xbc.toByte().toInt(), 0xaf.toByte().toInt(), 0x27, 0x1c).map { it.toByte() },
        "actual Android output uses genuine 7z magic")
    val second = codec.stageImport(destination, { native.import(it) })
    checkCase(second.androidBackup!!.contentEquals(staged.androidBackup!!) && second.providerConfiguration.toString() == staged.providerConfiguration.toString(),
        "native compatible body and provider credentials roundtrip exactly")
    checkCase(second.pcPreferences.toString() == staged.pcPreferences.toString() && second.sharedCredentials.toString() == staged.sharedCredentials.toString(),
        "PC preferences and home credentials roundtrip exactly")
    val copied = JSONObject(staged.sharedCredentials.toString()).put("device_id", "illegal-identity")
    checkCase(runCatching { DymSharedCredentials.validate(copied) }.isFailure, "malicious identity namespace rejected before credential restore")
    val target = DymSharedCredentials.nativeConfiguration(checkNotNull(staged.sharedCredentials), checkNotNull(staged.providerConfiguration))
    checkCase(target?.getJSONObject("credentials")?.getJSONObject("deepseek")?.getString("key") == "fixture-launcher-key", "PC launcher key can target native platform store without DSH components")
    val unrelated = JSONObject(staged.sharedCredentials.toString())
    unrelated.getJSONObject("refs").put("UNRELATED_IDENTITY", "must-stay-metadata")
    val updates = DymSharedCredentials.updates(unrelated, "0".repeat(64), staged.providerConfiguration).getJSONArray("refs")
    checkCase((0 until updates.length()).none { updates.getJSONObject(it).getString("ref") == "UNRELATED_IDENTITY" } &&
        (0 until updates.length()).any { updates.getJSONObject(it).getString("ref") == "FIXTURE_PROVIDER_KEY" },
        "restore applies only official and declared provider refs while unknown reference stays opaque")
    codec.discard(staged); codec.discard(second)
    File(root, "result.json").writeText(JSONObject().put("passed", passed).put("paidModelCalls", 0).put("productionCodec", true)
        .put("androidDevice", false).put("output", destination.canonicalPath).toString())
    println("$passed Android production codec cross-platform checks passed")
}
