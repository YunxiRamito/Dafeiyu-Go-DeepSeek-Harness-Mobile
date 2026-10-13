package com.dafeiyu.mobile

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dafeiyu.mobile.data.*
import com.dafeiyu.mobile.glasslab.LabDocument
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Android files and a real owned content URI; no model, runtime, permissions or backend. */
@RunWith(AndroidJUnit4::class)
class ThemeApplicationDeviceTest {
    @Test fun persistentThemesAndBoundedContentImport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val id = UUID.randomUUID().toString()
        val testRoot = File(app.filesDir, "qa-theme-$id").apply { mkdirs() }
        val exportRoot = File(app.getExternalFilesDir(null), "qa/app-theme-$id").apply { mkdirs() }
        val selectedFile = File(app.filesDir, "updates/qa-theme-$id.json")
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = testRoot
        }
        val fakeApplication = object : Application() {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = testRoot
            override fun getContentResolver() = app.contentResolver
        }
        val checks = JSONArray()
        fun check(name: String, action: () -> Unit) {
            try { action(); checks.put(JSONObject().put("name", name).put("passed", true)) }
            catch (failure: Throwable) {
                checks.put(JSONObject().put("name", name).put("passed", false).put("error", failure.toString()))
                throw failure
            }
        }
        fun eventually(predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                instrumentation.runOnMainSync { ready = predicate() }
                if (ready) return
                Thread.sleep(30)
            }
            fail("Expected theme operation did not complete within 10s")
        }
        try {
            val document = LabDocument.defaults().withParameters(LabDocument.defaults().current().withValue("shadowBlurDp", 3.7f))
            val custom = InstalledTheme(ThemeDocuments.CUSTOM_ID, "自定义", 0,
                ThemeDocuments.sha256(document.encode().toByteArray()), document)
            val installed = custom.copy(id = "market-demo", title = "轻玻璃", revision = 1)
            check("atomic_theme_selection_and_digest_files_restore") {
                val selection = ThemeSelection(custom.id, custom, listOf(installed))
                ThemeStore(isolated).save(selection)
                assertEquals(selection, ThemeStore(isolated).load())
                val normal = selection.copy(activeId = ThemeDocuments.DEFAULT_ID)
                ThemeStore(isolated).save(normal)
                assertEquals(normal, ThemeStore(isolated).load())
                assertNull(normal.active())
                assertNotNull(normal.custom)
            }
            lateinit var state: ThemeState
            instrumentation.runOnMainSync { state = ThemeState(fakeApplication) }
            eventually { !state.isLoading }
            check("real_content_uri_is_validated_saved_and_applied") {
                selectedFile.parentFile!!.mkdirs()
                selectedFile.writeText(document.encode())
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.attachments", selectedFile)
                assertEquals("content", uri.scheme)
                instrumentation.runOnMainSync { state.importTheme(uri) }
                eventually { !state.busy && state.document == document }
                assertEquals(document, ThemeStore(isolated).load().active()?.document)
                assertTrue(state.openRequest > 0)
            }
            check("invalid_and_cancelled_import_keep_current_theme") {
                instrumentation.runOnMainSync { state.importTheme(Uri.fromFile(selectedFile)) }
                eventually { !state.busy && state.message?.startsWith("主题导入失败") == true }
                assertEquals(document, state.document)
                assertEquals(document, ThemeStore(isolated).load().active()?.document)
                selectedFile.writeText("invalid JSON")
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.attachments", selectedFile)
                instrumentation.runOnMainSync { state.importTheme(uri) }
                eventually { !state.busy && state.message?.startsWith("主题导入失败") == true }
                assertEquals(document, state.document)
                instrumentation.runOnMainSync { state.importTheme(null) }
                assertEquals("已取消导入，当前主题保留。", state.message)
                assertEquals(document, ThemeStore(isolated).load().active()?.document)
            }
            check("restore_default_retains_custom_and_installed_themes") {
                instrumentation.runOnMainSync { state.select(ThemeDocuments.DEFAULT_ID) }
                eventually { !state.busy && state.document == null }
                val restored = ThemeStore(isolated).load()
                assertEquals(ThemeDocuments.DEFAULT_ID, restored.activeId)
                assertNotNull(restored.custom)
                assertEquals("market-demo", restored.installed.single().id)
            }
            check("market_offline_gate_preserves_files") {
                val before = ThemeStore(isolated).load()
                val offline = ThemeMarketClient(testRoot, okhttp3.OkHttpClient(), null, true)
                kotlinx.coroutines.runBlocking {
                    try {
                        offline.page(1)
                        fail("An explicitly offline fixture must reject before creating a request")
                    } catch (expected: java.io.IOException) {
                        assertTrue(expected.message.orEmpty().contains("暂时离线"))
                    }
                }
                assertFalse(offline.available)
                assertEquals(before, ThemeStore(isolated).load())
            }
        } finally {
            selectedFile.delete()
            testRoot.deleteRecursively()
            File(exportRoot, "report.json").writeText(JSONObject().put("checks", checks.length())
                .put("passed", (0 until checks.length()).all { checks.getJSONObject(it).getBoolean("passed") })
                .put("scope", "isolated Android AtomicFile and owned content URI; no UI/optical/production TLS claim")
                .put("paidCalls", 0).put("backendRequests", 0).put("results", checks).toString(2))
        }
    }
}
