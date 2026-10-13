package com.dafeiyu.mobile.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dafeiyu.mobile.glasslab.LabDocument
import com.dafeiyu.mobile.glasslab.LabParameters
import com.dafeiyu.mobile.ui.DshWebClientContext
import com.dafeiyu.mobile.ui.nativeThemeStyles
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android AtomicFile, owned content URI and production adapters; no Activity, model or network. */
@RunWith(AndroidJUnit4::class)
class ThemePersistenceSmoke {
    @Test
    fun isolatedPersistenceImportRecoveryAndAppearanceContext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext.applicationContext
        val runId = UUID.randomUUID().toString()
        // The existing FileProvider exposes updates/. Every fixture, including its URI, stays in this UUID root.
        val providerRoot = File(target.filesDir, "updates")
        val ownedRoot = File(providerRoot, "qa-theme-runtime-$runId")
        check(!ownedRoot.exists() && ownedRoot.mkdirs())
        val reportRoot = File(requireNotNull(target.getExternalFilesDir(null)), "qa/theme-runtime-$runId")
        check(!reportRoot.exists() && reportRoot.mkdirs())
        val results = JSONArray()
        val report = JSONObject().put("schema", 1).put("runId", runId).put("package", target.packageName)
            .put("startedAt", System.currentTimeMillis()).put("passed", false).put("completed", false)
            .put("scope", "UUID-owned Android persistence, content import and appearance serialization; no optical or backend claim")
            .put("modelCalls", 0).put("networkCalls", 0).put("results", results)
        val reportFile = File(reportRoot, "report.json")
        fun persist() { reportFile.writeText(report.toString(2), Charsets.UTF_8) }
        fun context(name: String): Context {
            require(name.matches(Regex("[a-z0-9-]+")))
            val files = File(ownedRoot, name).apply { check(mkdir()) }
            return object : ContextWrapper(target) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
                override fun getNoBackupFilesDir(): File = File(files, "no-backup")
                override fun getCacheDir(): File = File(files, "cache")
            }
        }
        fun case(name: String, action: () -> JSONObject) {
            val entry = JSONObject().put("name", name).put("passed", false)
            results.put(entry); persist()
            try {
                entry.put("evidence", action()).put("passed", true)
                Log.i(TAG, "PASS ${results.length()} $name")
            } catch (failure: Throwable) {
                entry.put("error", failure.javaClass.name).put("detail", failure.message?.take(1800))
                throw failure
            } finally { persist() }
        }
        fun eventually(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                instrumentation.runOnMainSync { ready = predicate() }
                if (ready) return
                Thread.sleep(20L)
            }
            fail("Owned theme operation did not finish within 10 seconds")
        }
        val document = distinctDocument()
        try {
            case("all-eight-independent-groups-survive-new-store-instance") {
                val isolated = context("eight-groups")
                val selected = selection(document)
                ThemeStore(isolated).save(selected)
                val restored = ThemeStore(isolated).load()
                assertEquals(selected, restored)
                val actual = requireNotNull(restored.active()).document
                val groups = listOf(actual.light, actual.dark).flatMap { it.values }
                assertEquals(8, groups.size)
                assertEquals(8, groups.map { it.values }.distinct().size)
                for ((expected, stored) in listOf(document.light to actual.light, document.dark to actual.dark)) {
                    for (type in LabParameters.types) {
                        assertEquals(expected.getValue(type), stored.getValue(type))
                        assertEquals(LabParameters.specs.map { it.key }.toSet(), stored.getValue(type).values.keys)
                        assertEquals(LabParameters.colorKeys.toSet(), stored.getValue(type).colors.keys)
                    }
                }
                JSONObject().put("groups", groups.size).put("parametersPerGroup", LabParameters.specs.size)
                    .put("colorsPerGroup", LabParameters.colorKeys.size)
            }
            case("catalog-source-digest-remains-distinct-from-canonical-storage-digest") {
                val isolated = context("digests")
                // Whitespace deliberately makes the downloaded byte digest different from canonical encode().
                val source = ("\n " + JSONObject(document.encode()).toString() + " \n").toByteArray(Charsets.UTF_8)
                val decoded = ThemeDocuments.decode(source)
                val sourceDigest = ThemeDocuments.sha256(source)
                val canonical = decoded.encode().toByteArray(Charsets.UTF_8)
                val storageDigest = ThemeDocuments.sha256(canonical)
                assertNotEquals(sourceDigest, storageDigest)
                val market = InstalledTheme("market-qa", "QA Market", 7, sourceDigest, decoded)
                val selected = ThemeSelection(activeId = market.id, installed = listOf(market))
                ThemeStore(isolated).save(selected)
                val index = JSONObject(File(isolated.filesDir, "themes/selection.json").readText())
                    .getJSONArray("installed").getJSONObject(0)
                assertEquals(sourceDigest, index.getString("sourceSha256"))
                assertEquals(storageDigest, index.getString("sha256"))
                val stored = File(isolated.filesDir, "themes/documents/$storageDigest.json")
                assertArrayEquals(canonical, stored.readBytes())
                assertEquals(selected, ThemeStore(isolated).load())
                assertFalse(File(isolated.filesDir, "themes/documents/$sourceDigest.json").exists())
                JSONObject().put("sourceSha256", sourceDigest).put("canonicalSha256", storageDigest)
                    .put("sourceBytes", source.size).put("canonicalBytes", canonical.size)
            }
            case("real-failed-and-cancelled-content-imports-preserve-existing-selection-and-files") {
                val isolated = context("failed-imports")
                val original = selection(document)
                ThemeStore(isolated).save(original)
                val fakeApplication = object : Application() {
                    override fun getApplicationContext(): Context = this
                    override fun getFilesDir(): File = isolated.filesDir
                    override fun getNoBackupFilesDir(): File = isolated.noBackupFilesDir
                    override fun getContentResolver() = target.contentResolver
                }
                val owner = ViewModelStore()
                lateinit var state: ThemeState
                instrumentation.runOnMainSync {
                    state = ThemeState(fakeApplication)
                    owner.put("qa-$runId", state)
                }
                try {
                    eventually { !state.isLoading }
                    instrumentation.runOnMainSync { assertEquals(original, state.selection) }
                    val before = fileDigests(File(isolated.filesDir, "themes"))
                    val incoming = File(isolated.filesDir, "owned-import.json")
                    val uri = FileProvider.getUriForFile(target, "${target.packageName}.attachments", incoming)
                    assertEquals("content", uri.scheme)
                    val badParameter = JSONObject(document.encode()).apply {
                        getJSONObject("light").getJSONObject("circle").getJSONObject("values").put("blurDp", 9999)
                    }.toString().toByteArray(Charsets.UTF_8)
                    val inputs = listOf("{invalid json".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28), badParameter)
                    for (bytes in inputs) {
                        incoming.writeBytes(bytes)
                        instrumentation.runOnMainSync { state.clearMessage(); state.importTheme(uri) }
                        eventually { !state.busy && state.message?.startsWith("主题导入失败") == true }
                        instrumentation.runOnMainSync { assertEquals(original, state.selection) }
                        assertEquals(original, ThemeStore(isolated).load())
                        assertEquals(before, fileDigests(File(isolated.filesDir, "themes")))
                    }
                    instrumentation.runOnMainSync { state.clearMessage(); state.importTheme(Uri.fromFile(incoming)) }
                    eventually { !state.busy && state.message?.startsWith("主题导入失败") == true }
                    instrumentation.runOnMainSync { state.importTheme(null); assertEquals(original, state.selection) }
                    assertEquals(original, ThemeStore(isolated).load())
                    assertEquals(before, fileDigests(File(isolated.filesDir, "themes")))
                    JSONObject().put("invalidContentCases", inputs.size).put("fileUriRejected", true)
                        .put("cancelPreserved", true).put("persistedFilesUnchanged", true)
                } finally {
                    instrumentation.runOnMainSync { owner.clear() }
                    eventually { !state.busy && !state.isLoading }
                }
            }
            case("corrupt-active-document-is-retained-and-default-fallback-keeps-healthy-installation") {
                val isolated = context("corrupt-document")
                val original = selection(document)
                ThemeStore(isolated).save(original)
                val index = File(isolated.filesDir, "themes/selection.json")
                val originalIndex = index.readBytes()
                val customDigest = JSONObject(index.readText()).getJSONObject("custom").getString("sha256")
                val damaged = File(isolated.filesDir, "themes/documents/$customDigest.json")
                val corruption = "QA-owned corrupted material document".toByteArray(Charsets.UTF_8)
                damaged.writeBytes(corruption)
                val store = ThemeStore(isolated)
                val restored = store.load()
                assertEquals(ThemeDocuments.DEFAULT_ID, restored.activeId)
                assertNull(restored.custom)
                assertEquals(original.installed, restored.installed)
                assertNotNull(store.recoveryNotice)
                assertArrayEquals(corruption, damaged.readBytes())
                assertArrayEquals(originalIndex, index.readBytes())
                store.save(restored)
                assertArrayEquals("A later default save must retain the corrupt document", corruption, damaged.readBytes())
                assertEquals(restored, store.load())
                JSONObject().put("corruptBytesPreserved", true).put("healthyInstalled", restored.installed.size)
                    .put("fallback", restored.activeId).put("notice", store.recoveryNotice)
            }
            case("corrupt-selection-index-is-retained-and-uses-default-without-deleting-documents") {
                val isolated = context("corrupt-index")
                ThemeStore(isolated).save(selection(document))
                val documents = File(isolated.filesDir, "themes/documents")
                val originalDocuments = fileDigests(documents)
                val index = File(isolated.filesDir, "themes/selection.json")
                val corruption = "{QA-owned broken index".toByteArray(Charsets.UTF_8)
                index.writeBytes(corruption)
                val store = ThemeStore(isolated)
                assertEquals(ThemeSelection(), store.load())
                assertNotNull(store.recoveryNotice)
                assertArrayEquals(corruption, index.readBytes())
                assertEquals(originalDocuments, fileDigests(documents))
                JSONObject().put("indexPreserved", true).put("documentFilesPreserved", originalDocuments.size)
            }
            case("android-atomicfile-backup-recovers-an-interrupted-index-write") {
                val isolated = context("atomic-backup")
                val original = selection(document)
                ThemeStore(isolated).save(original)
                val index = File(isolated.filesDir, "themes/selection.json")
                val bytes = index.readBytes()
                val backup = File(index.parentFile, "selection.json.bak")
                backup.writeBytes(bytes)
                index.writeText("{QA interrupted write")
                assertEquals(original, ThemeStore(isolated).load())
                assertArrayEquals(bytes, index.readBytes())
                assertFalse(backup.exists())
                JSONObject().put("restoredCommittedIndex", true)
                    .put("scope", "AtomicFile backup recovery; this case does not claim retention of the interrupted base file")
            }
            case("different-store-instances-share-complete-atomic-read-write-transactions") {
                val isolated = context("concurrent-stores")
                val a = selection(document)
                val b = selection(document.withParameters(document.current().withValue("blurDp", 17.25f)))
                val accepted = listOf(a, b, a.copy(activeId = ThemeDocuments.DEFAULT_ID), b.copy(activeId = "market-qa"))
                ThemeStore(isolated).save(a)
                val start = CountDownLatch(1)
                val writes = AtomicInteger()
                val reads = AtomicInteger()
                val executor = Executors.newFixedThreadPool(4)
                try {
                    val futures = (0 until 4).map { worker ->
                        executor.submit(Callable {
                            val store = ThemeStore(isolated)
                            check(start.await(5, TimeUnit.SECONDS))
                            val iterations = if (worker < 2) 10 else 25
                            repeat(iterations) { iteration ->
                                if (worker < 2) {
                                    store.save(accepted[(worker + iteration) % accepted.size]); writes.incrementAndGet()
                                } else {
                                    val restored = store.load()
                                    assertTrue("No mixed index/document generation may be visible", restored in accepted)
                                    assertNull("An owned concurrent read must not trigger recovery", store.recoveryNotice)
                                    reads.incrementAndGet()
                                }
                            }
                            iterations
                        })
                    }
                    start.countDown()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
                    futures.forEach { future -> future.get(maxOf(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }
                    assertEquals(20, writes.get())
                    assertEquals(50, reads.get())
                    assertTrue(ThemeStore(isolated).load() in accepted)
                    JSONObject().put("storeInstances", 4).put("committedWrites", writes.get())
                        .put("completeReads", reads.get())
                } finally {
                    start.countDown(); executor.shutdownNow()
                    check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Owned theme workers did not stop" }
                }
            }
            case("native-style-mapping-obeys-continuous-roundness-new-distance-and-all-press-modes") {
                var checked = 0
                for (shape in LabParameters.shapes) for (radius in listOf(0f, .37f, 1f)) {
                    for (mode in LabParameters.pressEffects) {
                        val p = LabParameters.defaults("circle").copy(shape = shape, pressEffect = mode)
                            .withValue("roundness", radius).withValue("fusionDistanceDp", 2f)
                            .withValue("bridgeReachDp", 39.5f).withValue("bridgeReleaseMillis", 713f)
                        val d = LabDocument.defaults().withParameters(p)
                        val mapped = requireNotNull(d.nativeThemeStyles().style("circle", false))
                        assertEquals("Legacy shape must not override the slider", radius, requireNotNull(mapped.roundness), .00001f)
                        assertEquals(39.5f, mapped.tuning.bridgeReachDp, .00001f)
                        assertEquals(713f, mapped.tuning.bridgeReleaseMillis, .00001f)
                        assertEquals(mode, mapped.tuning.pressEffect)
                        checked++
                    }
                }
                val styles = document.nativeThemeStyles()
                for ((dark, groups) in listOf(false to document.light, true to document.dark)) {
                    for ((type, p) in groups) {
                        val mapped = requireNotNull(styles.style(type, dark))
                        assertEquals(p["roundness"], requireNotNull(mapped.roundness), .00001f)
                        assertEquals(p["bridgeReachDp"], mapped.tuning.bridgeReachDp, .00001f)
                        assertEquals(p.pressEffect, mapped.tuning.pressEffect)
                        assertEquals(p.glassEnabled, mapped.glassEnabled)
                        assertEquals(p.motionEnabled, mapped.motionEnabled)
                        assertEquals(p.springEnabled, mapped.springEnabled)
                    }
                }
                JSONObject().put("shapeRadiusPressCombinations", checked).put("themeTypeMappings", 8)
            }
            case("dsh-client-material-context-is-validated-appearance-only-without-preview-photo") {
                val marker = "QA_PREVIEW_ONLY_$runId"
                val photo = jpegFixture()
                val photoDocument = document.copy(previewBackgroundImage = "photo", previewBackgroundImageData = photo,
                    previewBackgroundLabel = marker, previewBackgroundColors = listOf("#FF010203", "#FF040506"))
                val material = ThemeDocuments.materialContext(photoDocument)
                val permitted = setOf("schema", "renderer", "light", "dark")
                assertEquals(permitted, material.keys().asSequence().toSet())
                assertFalse(material.toString().contains(marker))
                assertFalse(material.toString().contains(photo))
                var modes = 0
                for (mode in listOf("local", "online")) {
                    val client = DshWebClientContext(mode, "dark", true, false, material.toString()).toJson()
                    assertEquals(setOf("version", "platform", "surface", "mode", "theme", "material", "motion", "materialTheme"),
                        client.keys().asSequence().toSet())
                    assertEquals(mode, client.getString("mode"))
                    val display = client.getJSONObject("materialTheme")
                    assertJsonEquals(material, display)
                    for (theme in LabDocument.themes) for (type in LabParameters.types) {
                        val p = display.getJSONObject(theme).getJSONObject(type)
                        assertEquals(LabParameters.specs.map { it.key }.toSet(), p.getJSONObject("values").keys().asSequence().toSet())
                        assertEquals(LabParameters.colorKeys.toSet(), p.getJSONObject("colors").keys().asSequence().toSet())
                    }
                    modes++
                }
                val stripped = JSONObject(material.toString()).apply {
                    getJSONObject("light").getJSONObject("circle").put("script", "QA_MUST_NOT_LEAK")
                        .put("apiKey", "QA_MUST_NOT_LEAK").put("endpoint", "QA_MUST_NOT_LEAK")
                }
                val sanitized = DshWebClientContext("online", "system", true, true, stripped.toString()).toJson()
                assertFalse(sanitized.toString().contains("QA_MUST_NOT_LEAK"))
                var rejected = 0
                for (key in listOf("account", "credentials", "script", "previewBackgroundImageData")) {
                    val attempted = JSONObject(material.toString()).put(key, "QA_MUST_NOT_LEAK")
                    val failure = runCatching {
                        DshWebClientContext("online", "dark", true, true, attempted.toString())
                    }.exceptionOrNull()
                    assertTrue("$key must not enter display context", failure is IllegalArgumentException)
                    rejected++
                }
                JSONObject().put("executionModes", modes).put("appearanceGroups", 8)
                    .put("privatePreviewExcluded", true).put("unknownNestedFieldsStripped", true)
                    .put("authorityFieldsRejected", rejected)
            }
            report.put("completed", true).put("passed", true)
        } finally {
            // No user theme, update package, account, permission or external report is removed.
            check(ownedRoot.name == "qa-theme-runtime-$runId" && requireNotNull(ownedRoot.parentFile).canonicalFile == providerRoot.canonicalFile)
            val removed = ownedRoot.deleteRecursively()
            report.put("finishedAt", System.currentTimeMillis()).put("checks", results.length())
                .put("ownedFixtureCleanup", removed).put("fixtureRoot", ownedRoot.absolutePath)
            persist()
            Log.i(TAG, "Theme runtime report: ${reportFile.absolutePath}")
            check(removed) { "Unable to remove the UUID-owned theme fixture root" }
        }
    }

    private fun distinctDocument(): LabDocument {
        fun groups(dark: Boolean): Map<String, LabParameters> = LabParameters.types.mapIndexed { typeIndex, type ->
            val index = typeIndex + if (dark) 4 else 0
            val ratio = (index + 1f) / 9f
            val values = LabParameters.specs.associate { it.key to (it.min + (it.max - it.min) * ratio) }
            val colors = LabParameters.colorKeys.mapIndexed { colorIndex, key ->
                key to String.format(Locale.ROOT, "#%02X%02X%02X%02X", 128 + index * 11,
                    (index * 31 + colorIndex * 13) % 256, (index * 19 + colorIndex * 23) % 256,
                    (index * 47 + colorIndex * 7) % 256)
            }.toMap()
            type to LabParameters.defaults(type, dark).copy(values = values, colors = colors,
                icon = LabParameters.icons[index], iconBlend = LabParameters.blendModes[index % LabParameters.blendModes.size],
                glassEnabled = index % 2 == 0, motionEnabled = index % 3 != 0, springEnabled = index % 3 == 0,
                disabled = index % 2 != 0, shape = LabParameters.shapes[index % LabParameters.shapes.size],
                pressEffect = LabParameters.pressEffects[index % LabParameters.pressEffects.size])
        }.toMap()
        return LabDocument(groups(false), groups(true), selectedTheme = "dark", selectedType = "panel").snapshot()
    }

    private fun selection(document: LabDocument): ThemeSelection {
        val custom = InstalledTheme(ThemeDocuments.CUSTOM_ID, "QA Custom", 0,
            ThemeDocuments.sha256(document.encode().toByteArray(Charsets.UTF_8)), document)
        val other = document.withParameters(document.current().withValue("blurDp", 9.25f))
        val market = InstalledTheme("market-qa", "QA Market", 4,
            ThemeDocuments.sha256(other.encode().toByteArray(Charsets.UTF_8)), other)
        return ThemeSelection(custom.id, custom, listOf(market))
    }

    private fun fileDigests(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to ThemeDocuments.sha256(it.readBytes()) }

    private fun jpegFixture(): String {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(0xffcc55aa.toInt())
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output))
            Base64.getEncoder().encodeToString(output.toByteArray())
        } finally { bitmap.recycle() }
    }

    private fun assertJsonEquals(expected: Any?, actual: Any?) {
        when (expected) {
            is JSONObject -> {
                assertTrue(actual is JSONObject)
                val objectActual = actual as JSONObject
                val keys = expected.keys().asSequence().toSet()
                assertEquals(keys, objectActual.keys().asSequence().toSet())
                keys.forEach { assertJsonEquals(expected.get(it), objectActual.get(it)) }
            }
            is JSONArray -> {
                assertTrue(actual is JSONArray)
                val arrayActual = actual as JSONArray
                assertEquals(expected.length(), arrayActual.length())
                for (index in 0 until expected.length()) assertJsonEquals(expected.get(index), arrayActual.get(index))
            }
            is Number -> {
                assertTrue(actual is Number)
                assertEquals(expected.toDouble(), (actual as Number).toDouble(), .000001)
            }
            else -> assertEquals(expected, actual)
        }
    }

    companion object { private const val TAG = "ThemePersistenceSmoke" }
}
