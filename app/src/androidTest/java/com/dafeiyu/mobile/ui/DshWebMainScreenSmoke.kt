package com.dafeiyu.mobile.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.InputDevice
import android.view.Window
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dafeiyu.mobile.DafeiyuApp
import com.dafeiyu.mobile.data.ChatMessage
import com.dafeiyu.mobile.data.ConnectPluginHost
import com.dafeiyu.mobile.data.Conversation
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.DshLocalConfigurationRpc
import com.dafeiyu.mobile.data.DshRuntimeInstaller
import com.dafeiyu.mobile.data.DshRuntimeManager
import com.dafeiyu.mobile.data.DshServiceStatus
import com.dafeiyu.mobile.data.LinuxRuntimeManager
import com.dafeiyu.mobile.data.LocalDshHistoryController
import com.dafeiyu.mobile.data.ModelRuntimeController
import com.dafeiyu.mobile.data.RuntimeStatus
import com.dafeiyu.mobile.data.SettingsRuntimeStore
import com.dafeiyu.mobile.data.ThemeState
import com.dafeiyu.mobile.data.ThemeStore
import com.dafeiyu.mobile.data.ThemeSelection
import com.dafeiyu.mobile.data.ThemeDocuments
import com.dafeiyu.mobile.data.InstalledTheme
import com.dafeiyu.mobile.glasslab.LabDocument
import com.dafeiyu.mobile.data.Workspace
import com.dafeiyu.mobile.data.WorkspaceSnapshot
import com.dafeiyu.mobile.data.WorkspaceStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Full production DafeiyuApp / FishTheme / chrome / transition / glass draw pipeline.
 * PixelCopy copies the actual Activity window, never a detached WebView or a software Canvas.
 * All history, credentials and service processes are UUID-owned. The STARTED local owner
 * excludes the unrelated RESUMED presence registration; the real Activity stays RESUMED.
 * This does not replace the separate formal-package MainActivity / RESUMED regression. */
@RunWith(AndroidJUnit4::class)
class DshWebMainScreenSmoke {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameId = 0L

    @Test fun actualMainScreenPaintAndTransitions(): Unit = runBlocking {
        val target = instrumentation.targetContext.applicationContext
        check(target.packageName == "com.dafeiyu.mobile.dev") { "Only the dedicated debug QA package is allowed" }
        check(android.os.Build.SUPPORTED_ABIS.first() in setOf("x86_64", "arm64-v8a"))
        val originalRoot = File(target.filesDir, "Dafeiyu-Works")
        val originalIndex = File(originalRoot, "workspace-index.json")
        // Do not call initialize() for a missing user index: that would create a default folder.
        if (originalIndex.exists() || File(originalRoot, "workspace-recovery.json").exists()) {
            check(WorkspaceStore(target).expiredArchiveCandidates(System.currentTimeMillis()).isEmpty()) {
                "Existing debug archives may expire; refuse to schedule the full app over them"
            }
        }
        check(LocalDshHistoryController(target).pendingPermanentDeletions().isEmpty()) {
            "Existing debug history deletion is pending; leave it untouched"
        }
        val originalIndexDigest = digest(originalIndex)
        val originalSettings = target.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE).all.toMap()
        val originalPresence = target.getSharedPreferences("developer_center_presence", Context.MODE_PRIVATE).all.toMap()
        val runtime = LinuxRuntimeManager(target)
        val installer = DshRuntimeInstaller(target, runtime)
        check(installer.current() != null) { "Install and verify the real runtime before this screen-only regression" }
        val id = UUID.randomUUID().toString()
        val files = File(target.filesDir, "main-screen-smoke/$id").apply { check(mkdirs()) }
        val vault = File(target.noBackupFilesDir, "main-screen-smoke/$id").apply { check(mkdirs()) }
        val cache = File(target.cacheDir, "main-screen-smoke/$id").apply { check(mkdirs()) }
        val preferenceNames = ConcurrentHashMap.newKeySet<String>()
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = vault
            override fun getCacheDir(): File = cache
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = "main-screen-$id-$name"
                preferenceNames.add(scoped)
                return target.getSharedPreferences(scoped, mode)
            }
        }
        check(isolated.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE).edit()
            .putString("engine_mode", "official").putString("connection_mode", "local")
            .putString("theme_mode", "light").putBoolean("notifications_enabled", false)
            .putBoolean("auto_start_dsh", false).putBoolean("glass_material", true)
            .putBoolean("page_transitions", true).putBoolean("spring_buttons", true)
            .putString(SettingsRuntimeStore.KEY_UPDATE_POLICY, "off").commit())
        // Opening native settings reads the announcement feed and needs its
        // local installation identity even without a RESUMED heartbeat. Seed
        // only the UUID-owned store and verify it never records a heartbeat.
        val scopedPresence = mapOf("installation_id" to UUID.randomUUID().toString())
        check(isolated.getSharedPreferences("developer_center_presence", Context.MODE_PRIVATE).edit()
            .putString("installation_id", scopedPresence.getValue("installation_id")).commit())
        val evidence = File(target.filesDir, "main-screen-smoke-evidence/$id").apply { check(mkdirs()) }
        val reportFile = File(target.filesDir, "main-screen-smoke-report.json")
        val checks = JSONArray()
        val report = JSONObject().put("schema", 1).put("passed", false).put("checks", checks)
            .put("actualProductionAppComposable", true).put("actualWindowPixelCopy", true)
            .put("productionServiceAndBundle", true).put("syntheticBackend", false)
            .put("syntheticPaintFixture", true).put("syntheticHistoryOnly", true)
            .put("paidModelRequests", 0).put("fixtureCredentialsConfigured", false)
            .put("modelRequestEvidence", "saved history and session/list only; no prompt endpoint called by the test")
            .put("lifecycleScope", "real Activity RESUMED; composition owner STARTED")
            .put("schedulerScope", "debug package only; production off policy cancels its fixed update job")
            .put("formalMainActivityValidated", false).put("evidenceDirectory", evidence.absolutePath)
            .put("startedAt", System.currentTimeMillis())
        fun persist() = reportFile.writeText(report.toString(2))
        val service = DshRuntimeManager(isolated, runtime, installer)
        val holder = ViewModelStore()
        val repository = withContext(Dispatchers.Main) { DemoRepository().also { holder.put("screen", it) } }
        val baselineTheme = LabDocument.defaults()
        val customTheme = baselineTheme.copy(
            light = baselineTheme.light + ("circle" to baselineTheme.light.getValue("circle")
                .withColor("surface", "#FFCF3877").withValue("shadowBlurDp", 9.4f).withValue("roundness", .4f)),
            dark = baselineTheme.dark + ("circle" to baselineTheme.dark.getValue("circle")
                .withColor("surface", "#FF31ADC2").withValue("shadowBlurDp", 4.3f).withValue("roundness", .7f)),
        )
        val installedTheme = InstalledTheme(ThemeDocuments.CUSTOM_ID, "QA isolated theme", 0,
            ThemeDocuments.sha256(customTheme.encode().toByteArray()), customTheme)
        ThemeStore(isolated).save(ThemeSelection(installedTheme.id, installedTheme))
        val themeApplication = object : Application() {
            override fun getApplicationContext(): Context = isolated
            override fun getFilesDir(): File = files
        }
        val themeState = withContext(Dispatchers.Main) { ThemeState(themeApplication).also { holder.put("theme", it) } }
        withTimeout(10_000) { while (withContext(Dispatchers.Main) { themeState.isLoading }) delay(30) }
        val host = AtomicReference<DshWebLifecycleQaActivity>()
        var scenario: ActivityScenario<DshWebLifecycleQaActivity>? = null
        val localOwner = withContext(Dispatchers.Main) { StartedOwner() }
        var access = ""
        var session = ""
        val marker = "Offline paint fixture ${id.take(8)}"
        var fixtureView: WebView? = null
        var failureEvidenceRecorded = false
        val originalAccessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags
        fun stage(name: String) { report.put("stage", name); persist() }
        suspend fun currentWeb(): WebView? = withContext(Dispatchers.Main) {
            host.get()?.window?.decorView?.let(::webViews)?.singleOrNull()
        }
        suspend fun attachedWeb(): WebView = withTimeout(20_000) {
            while (true) {
                currentWeb()?.let { view ->
                    if (withContext(Dispatchers.Main) { view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0 }) {
                        return@withTimeout view
                    }
                }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        suspend fun runtimeDiagnostic() {
            report.put("runtimeDiagnostic", withContext(Dispatchers.Main) {
                val snapshot = service.snapshot.value
                JSONObject().put("workspaceInitialized", repository.workspaceInitialized)
                    .put("engineMode", if (repository.engineMode in setOf("official", "dsh")) repository.engineMode else "unknown")
                    .put("switchBusy", repository.localEngineSwitchBusy)
                    .put("switchErrorPresent", repository.localEngineSwitchError != null)
                    .put("switchErrorCategory", safeErrorCategory(repository.localEngineSwitchError))
                    .put("runtimeStatus", repository.runtimeStatus.name)
                    .put("runtimeErrorPresent", repository.runtimeLastError != null)
                    .put("runtimeErrorCategory", safeErrorCategory(repository.runtimeLastError))
                    .put("accessPresent", repository.runtimeAccessUrl != null)
                    .put("sessionPresent", repository.localDshSessionId != null)
                    .put("serviceStatus", snapshot.status.name).put("servicePidPresent", snapshot.pid != null)
                    .put("healthCode", snapshot.healthCode ?: -1)
                    .put("serviceErrorCategory", safeErrorCategory(snapshot.lastError))
            })
            persist()
        }
        suspend fun ownsQaWindow(activity: DshWebLifecycleQaActivity): Boolean = withContext(Dispatchers.Main) {
            host.get() === activity && activity.packageName == target.packageName && !activity.isDestroyed &&
                !activity.isFinishing && activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                activity.window.decorView.let { it.isAttachedToWindow && it.isShown &&
                    it.windowToken != null && it.hasWindowFocus() }
        }
        suspend fun captureFailure(failure: Throwable) = withContext(NonCancellable) {
            if (failureEvidenceRecorded) return@withContext
            failureEvidenceRecorded = true
            val info = JSONObject().put("stage", report.optString("stage"))
                .put("failureClass", failure.javaClass.simpleName)
            report.put("failureEvidence", info)
            try {
                runtimeDiagnostic()
                val activity = host.get()
                val activeRoot = instrumentation.uiAutomation.rootInActiveWindow
                info.put("accessibilityRootPresent", activeRoot != null)
                    .put("accessibilityRootPackageMatches", activeRoot?.packageName?.toString() == target.packageName)
                val view = currentWeb()
                if (activity != null) info.put("nativeWindow", nativeWindowState(activity, view))
                if (view != null) {
                    try { info.put("dshDomLayout", dshLayoutDiagnostic(view, marker)) }
                    catch (layoutFailure: Throwable) { info.put("dshDomLayoutFailureClass", layoutFailure.javaClass.simpleName) }
                    persist()
                }
                val ownsWindow = activity != null && ownsQaWindow(activity)
                info.put("ownsActiveWindow", ownsWindow)
                if (!ownsWindow || activity == null) {
                    info.put("screenshotSkipped", true)
                    return@withContext
                }
                fixtureView?.let { info.put("fixtureCurrentViewMatches", view === it) }
                if (view != null) {
                    try {
                        val pixels = capture(activity, view, report)
                        try { saveImage(pixels, File(evidence, "failure-window-pixelcopy.png")) }
                        finally { pixels.recycle() }
                        info.put("pixelCopySaved", true)
                        return@withContext
                    } catch (copyFailure: Throwable) { info.put("pixelCopyFailureClass", copyFailure.javaClass.simpleName) }
                }
                val stillOwnsWindow = ownsQaWindow(activity)
                if (stillOwnsWindow) {
                    val pixels = instrumentation.uiAutomation.takeScreenshot()
                    if (pixels != null) {
                        try {
                            if (ownsQaWindow(activity)) {
                                saveImage(pixels, File(evidence, "failure-display-screenshot.png"))
                                info.put("displayScreenshotSaved", true)
                            } else info.put("screenshotSkipped", true)
                        }
                        finally { pixels.recycle() }
                    } else info.put("displayScreenshotSaved", false)
                } else info.put("screenshotSkipped", true)
            } catch (diagnosticFailure: Throwable) { info.put("diagnosticFailureClass", diagnosticFailure.javaClass.simpleName) }
            finally { persist() }
        }
        suspend fun case(name: String, block: suspend () -> Unit) {
            val row = JSONObject().put("name", name).put("passed", false)
            checks.put(row); stage(name)
            try { withTimeout(180_000) { block() }; row.put("passed", true) }
            catch (failure: Throwable) {
                row.put("failureClass", failure.javaClass.simpleName)
                captureFailure(failure)
                row.put("failureStage", report.getJSONObject("failureEvidence").optString("stage"))
                throw failure
            } finally { persist() }
        }
        try {
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            WorkspaceStore(isolated).save(WorkspaceSnapshot(listOf(Workspace("default", "Paint QA")),
                listOf(Conversation("paint-$id", "Paint QA", "default")),
                mapOf("paint-$id" to listOf(ChatMessage(1, "user", marker),
                    ChatMessage(2, "assistant", "Stored offline answer; no model execution."))), "default", "paint-$id"))
            case("isolated real Repo and installed bundle start a non-running history session") {
                withContext(Dispatchers.Main) { repository.initializeWorkspaces(isolated, runtime, installer) }
                withTimeout(150_000) {
                    while (!withContext(Dispatchers.Main) { repository.workspaceInitialized && repository.backgroundTasks != null }) delay(100)
                }
                withContext(Dispatchers.Main) { repository.updateEngineMode("dsh") }
                withTimeout(150_000) {
                    while (withContext(Dispatchers.Main) { repository.localEngineSwitchBusy }) {
                        runtimeDiagnostic(); delay(100)
                    }
                }
                runtimeDiagnostic()
                withContext(Dispatchers.Main) {
                    check(repository.localEngineSwitchError == null && repository.engineMode == "dsh")
                    check(repository.runtimeStatus == RuntimeStatus.Running)
                    access = checkNotNull(repository.runtimeAccessUrl)
                    session = checkNotNull(repository.localDshSessionId)
                }
                val items = DshLocalConfigurationRpc().call(access, "session/list", JSONObject().put("_request", JSONObject())).getJSONArray("items")
                val selected = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("sessionId") == session }
                check(!selected.getBoolean("running"))
            }
            case("full production app mounts an attached hardware WebView in a real Activity window") {
                val began = SystemClock.elapsedRealtime()
                scenario = ActivityScenario.launch(Intent(target, DshWebLifecycleQaActivity::class.java))
                checkNotNull(scenario).onActivity { activity ->
                    host.set(activity)
                    activity.enableEdgeToEdge()
                    val displayContext = object : ContextWrapper(activity) {
                        override fun getApplicationContext(): Context = isolated
                        override fun getFilesDir(): File = files
                        override fun getNoBackupFilesDir(): File = vault
                        override fun getCacheDir(): File = cache
                        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = isolated.getSharedPreferences(name, mode)
                    }
                    activity.setContent {
                        CompositionLocalProvider(LocalContext provides displayContext, LocalLifecycleOwner provides localOwner) {
                            DafeiyuApp(repository, themeState)
                        }
                    }
                }
                val view = attachedWeb()
                withContext(Dispatchers.Main) {
                    check(view.layerType != View.LAYER_TYPE_SOFTWARE)
                    check(view.isHardwareAccelerated && checkNotNull(host.get()).window.decorView.isHardwareAccelerated)
                    check(checkNotNull(host.get()).lifecycle.currentState == Lifecycle.State.RESUMED)
                }
                awaitBundle(view, marker, session, report, ::persist)
                report.put("mainScreenReadyElapsedMillis", SystemClock.elapsedRealtime() - began)
            }
            case("owned native custom theme reaches the real light WebView CSS") {
                val view = attachedWeb()
                val observed = withTimeout(10_000) {
                    while (true) {
                        val value = state(view, THEME_STATE)
                        report.put("lastMaterialThemeState", value)
                        if (value.optString("kind") == "circle" && value.optString("fill").contains("207, 56, 119")) return@withTimeout value
                        delay(50)
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                check(observed.getString("shadow").contains("9.4px"))
                check(observed.getBoolean("hasContextTheme"))
                report.put("lightMaterialTheme", observed)
            }
            case("the mobile webpage fills Activity content without a duplicate native home toolbar") {
                val view = attachedWeb()
                awaitBundle(view, marker, session, report, ::persist)
                val windowState = nativeWindowState(checkNotNull(host.get()), view)
                report.put("fullScreenWindow", windowState)
                val bounds = windowState.getJSONArray("locationInWindow")
                val insets = withContext(Dispatchers.Main) {
                    view.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout()) ?: android.graphics.Insets.NONE
                }
                val top = bounds.getInt(1)
                report.put("allowedSystemInsets", JSONArray(listOf(insets.left, insets.top, insets.right, insets.bottom)))
                check(bounds.getInt(0) <= insets.left + 1)
                check(top <= insets.top + 1) { "A native home toolbar still consumes webpage height" }
                check(top + windowState.getInt("viewHeight") >= windowState.getInt("decorHeight") - insets.bottom - 1)
                check(bounds.getInt(0) + windowState.getInt("viewWidth") >= windowState.getInt("decorWidth") - insets.right - 1)
                val nativeLabels = nativeVisibleLabels(target.packageName)
                report.put("nativeHomeMenuPresent", "打开菜单" in nativeLabels)
                    .put("nativeHomeNewConversationPresent", "新建对话" in nativeLabels)
                check("打开菜单" !in nativeLabels && "新建对话" !in nativeLabels) {
                    "DSH still exposes a second native home navigation"
                }
                val fullWindow = captureFullWindow(checkNotNull(host.get()))
                try { saveImage(fullWindow, File(evidence, "production-full-window.png")) }
                finally { fullWindow.recycle() }
            }
            case("actual window pixels show four canvas colours and change after real touch") {
                val view = attachedWeb()
                fixtureView = view
                suspend fun awaitPhase(phase: Int) = withTimeout(15_000) {
                    while (true) {
                        val sameView = currentWeb() === view
                        report.put("fixtureCurrentViewMatches", sameView)
                        check(sameView) { "The fixture WebView was replaced" }
                        val observed = paintFixtureState(view, id)
                        report.put("lastPhaseState", observed)
                        report.put("nativeWindow", nativeWindowState(checkNotNull(host.get()), view))
                        persist()
                        if (observed.optBoolean("present") && observed.optBoolean("ownerMatches") &&
                            observed.optBoolean("contextReady") && observed.optBoolean("connected") &&
                            observed.optInt("phase", -1) == phase && observed.optString("error") == "none") return@withTimeout
                        delay(100)
                    }
                }
                var fixtureFailure: Throwable? = null
                try {
                    stage("canvas injection into live document")
                    val inserted = installPaintFixture(view, id)
                    report.put("fixtureInsertion", inserted)
                    check(inserted.optBoolean("inserted")) { "The test-owned canvas fixture was not inserted" }
                    stage("canvas phase 0 readiness"); awaitPhase(0)
                    stage("canvas phase 0 actual pixels")
                    val first = awaitQuadrants(checkNotNull(host.get()), view, 0, report, ::persist)
                    try { saveImage(first, File(evidence, "canvas-phase-0.png")) } finally { first.recycle() }
                    stage("canvas real touch"); tapView(view)
                    stage("canvas phase 1 readiness"); awaitPhase(1)
                    check(report.getJSONObject("lastPhaseState").optInt("clickCount") == 1)
                    stage("canvas phase 1 actual pixels")
                    val second = awaitQuadrants(checkNotNull(host.get()), view, 1, report, ::persist)
                    try { saveImage(second, File(evidence, "canvas-phase-1.png")) } finally { second.recycle() }
                } catch (failure: Throwable) {
                    fixtureFailure = failure
                    captureFailure(failure)
                    throw failure
                } finally {
                    withContext(NonCancellable) {
                        val cleanup = runCatching { removePaintFixture(view, id) }
                        cleanup.onSuccess { report.put("fixtureCleanup", it) }
                            .onFailure { report.put("fixtureCleanupFailureClass", it.javaClass.simpleName) }
                        persist()
                        if (fixtureFailure == null) check(cleanup.getOrThrow().optBoolean("absent"))
                    }
                }
                stage("real bundle after fixture removal")
                check(currentWeb() === view) { "The live WebView changed during the paint fixture" }
                awaitBundle(view, marker, session, report, ::persist)
                fixtureView = null
            }
            case("real DSH history text has visible screen pixels, disappears and restores") {
                proveHistoryPixels(checkNotNull(host.get()), attachedWeb(), marker, session, evidence, "bundle-light", report)
            }
            case("real DSH settings and plus menu remain visible inside the Android viewport") {
                val view = attachedWeb()
                tapWebControl(view, "new-session")
                val quick = awaitWebPanel(view, "[data-dafeiyu-mobile-layout='quick-actions']", 100.0)
                report.put("quickMenuBounds", quick); persist()
                check(quick.getInt("buttons") == 3)
                val menuPixels = captureFullWindow(checkNotNull(host.get()))
                try { saveImage(menuPixels, File(evidence, "quick-actions.png")) } finally { menuPixels.recycle() }
                tapWebControl(view, "new-session")
                tapWebControl(view, "sidebar-toggle"); awaitWebDrawer(view, true)
                tapWebControl(view, "dsh-settings")
                val settings = awaitWebPanel(view, "[role='dialog'][data-shortcut-modal='settings']", 300.0)
                report.put("dshSettingsBounds", settings); persist()
                val settingsPixels = captureFullWindow(checkNotNull(host.get()))
                try { saveImage(settingsPixels, File(evidence, "dsh-settings.png")) } finally { settingsPixels.recycle() }
                withContext(Dispatchers.Main) { view.requestFocus() }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ESCAPE)
                withTimeout(10_000) {
                    while (state(view, "JSON.stringify({present:!!document.querySelector('[role=dialog][data-shortcut-modal=settings]')})")
                            .optBoolean("present")) delay(100)
                }
                awaitBundle(view, marker, session, report, ::persist)
            }
            case("real webpage settings control opens native settings without recording the WebView") {
                val trips = JSONArray().also { report.put("hostSettingsRoundTrips", it) }
                repeat(2) { index ->
                    val view = attachedWeb()
                    awaitBundle(view, marker, session, report, ::persist)
                    val trip = JSONObject().put("index", index).put("webControlTouchIssued", false)
                        .put("nativeSettingsVisible", false).put("liveWebViewInputBlocked", false).put("returnPainted", false)
                    trips.put(trip); persist()
                    val drawer = state(view, "JSON.stringify({open:document.documentElement.getAttribute('data-dafeiyu-drawer')==='open'})")
                    if (!drawer.getBoolean("open")) tapWebControl(view, "sidebar-toggle")
                    awaitWebDrawer(view, true)
                    tapWebControl(view, "app-settings")
                    trip.put("webControlTouchIssued", true); persist()
                    withTimeout(15_000) {
                        while (currentWeb() !== view || withContext(Dispatchers.Main) { view.isEnabled } ||
                            nativeVisibleLabels(target.packageName).none {
                                it.contains("工作区、偏好与支持，都在这里") }) delay(100)
                    }
                    trip.put("nativeSettingsVisible", true)
                    withContext(Dispatchers.Main) {
                        check(view.isAttachedToWindow && view.parent != null)
                        check(!view.isEnabled && view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                    }
                    val inertSource = nativeTagState(target.packageName, "dsh-settings-inert-source")
                    trip.put("inertTransitionSource", inertSource)
                    // The production transition clears all source semantics while
                    // blocked. An absent source tag is therefore not evidence of
                    // a missing source composition or of successful recording.
                    // Source recording remains a separate production-code audit;
                    // real interaction isolation and returned pixels are checked here.
                    if (inertSource.optBoolean("present")) check(inertSource.optInt("webViewDescendants") == 0) {
                        "Settings source contains an accessible WebView"
                    }
                    trip.put("sourceCaptureVerifiedAtRuntime", false)
                    trip.put("liveWebViewInputBlocked", true); persist()
                    val settingsWindow = captureFullWindow(checkNotNull(host.get()))
                    try { saveImage(settingsWindow, File(evidence, "native-settings-$index.png")) }
                    finally { settingsWindow.recycle() }
                    if (index == 1) {
                        val idle = idleFrameCount(checkNotNull(host.get()))
                        report.put("nativeSettingsIdleFrames", idle); persist()
                        check(idle <= 30) { "Static native settings continuously redraws: $idle frames in 2 seconds" }
                    }
                    withContext(Dispatchers.Main) { checkNotNull(host.get()).onBackPressedDispatcher.onBackPressed() }
                    withTimeout(15_000) { while (!withContext(Dispatchers.Main) { view.isEnabled }) delay(100) }
                    val returned = attachedWeb()
                    check(returned === view) { "Native settings replaced the retained live webpage" }
                    withContext(Dispatchers.Main) {
                        check(view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO)
                    }
                    awaitBundle(returned, marker, session, report, ::persist)
                    proveHistoryPixels(checkNotNull(host.get()), returned, marker, session, evidence, "settings-return-$index", report)
                    trip.put("returnPainted", true); persist()
                }
            }
            case("dark theme and real Activity stop/resume keep a visible DSH surface") {
                withContext(Dispatchers.Main) { repository.updateThemeMode("dark") }
                delay(500)
                checkNotNull(scenario).moveToState(Lifecycle.State.CREATED)
                checkNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
                awaitBundle(attachedWeb(), marker, session, report, ::persist)
                proveHistoryPixels(checkNotNull(host.get()), attachedWeb(), marker, session, evidence, "bundle-dark-resumed", report)
                val observed = state(attachedWeb(), THEME_STATE)
                check(observed.getString("fill").contains("49, 173, 194"))
                check(observed.getString("shadow").contains("4.3px"))
                report.put("darkMaterialTheme", observed)
            }
            case("restoring the native default removes only owned custom WebView styles") {
                withContext(Dispatchers.Main) { themeState.select(ThemeDocuments.DEFAULT_ID) }
                withTimeout(10_000) {
                    while (state(attachedWeb(), THEME_STATE).optString("kind").isNotEmpty()) delay(50)
                }
                val observed = state(attachedWeb(), THEME_STATE)
                check(observed.getString("fill").isEmpty() && !observed.getBoolean("hasContextTheme"))
                report.put("defaultMaterialTheme", observed)
            }
            case("fixture RPC service stays healthy and original debug settings/index/identity stay unchanged") {
                check(service.health()?.code in 200..299)
                check(digest(originalIndex) == originalIndexDigest)
                check(target.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE).all == originalSettings)
                check(target.getSharedPreferences("developer_center_presence", Context.MODE_PRIVATE).all == originalPresence)
                check(isolated.getSharedPreferences("developer_center_presence", Context.MODE_PRIVATE).all == scopedPresence) {
                    "The isolated settings identity changed or a presence heartbeat was recorded"
                }
                check(isolated.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE).getString("aries_group_id", null) == null)
            }
            report.put("passed", true).put("finishedAt", System.currentTimeMillis())
        } finally {
            withContext(NonCancellable) {
                // Dispose rendering before stopping only the UUID-bound service.
                withContext(Dispatchers.Main) {
                    host.get()?.setContent { MaterialTheme {} }
                    localOwner.registry.currentState = Lifecycle.State.DESTROYED
                }
                scenario?.close()
                withContext(Dispatchers.Main) { holder.clear() }
                service.stop(); service.close()
                ModelRuntimeController.get(isolated).shutdown()
                ConnectPluginHost.get(isolated).stop()
                withTimeout(15_000) { while (runtime.hasProcessLease(files) || runtime.hasProcessLease(vault)) delay(100) }
                check(service.snapshot.value.status == DshServiceStatus.STOPPED)
                check(files.deleteRecursively() && vault.deleteRecursively() && cache.deleteRecursively())
                preferenceNames.forEach { check(target.deleteSharedPreferences(it)) }
                check(digest(originalIndex) == originalIndexDigest)
                check(target.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE).all == originalSettings)
                check(target.getSharedPreferences("developer_center_presence", Context.MODE_PRIVATE).all == originalPresence)
                report.put("testStoresRemoved", true).put("ownedServiceStopped", true)
                persist()
                instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                    flags = originalAccessibilityFlags
                }
            }
        }
    }

    private class StartedOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val THEME_STATE = """(() => {
        const node=document.querySelector('[data-dafeiyu-mobile-control="sidebar-toggle"]');
        return {kind:node?.getAttribute('data-dafeiyu-theme-kind')||'',
            fill:node?getComputedStyle(node).getPropertyValue('--df-theme-fill'):'',
            shadow:node?getComputedStyle(node).boxShadow:'',
            hasContextTheme:!!globalThis.__DAFEIYU_DSH_CLIENT__?.materialTheme};
    })()"""

    private suspend fun evaluate(view: WebView, script: String): String = withContext(Dispatchers.Main) {
        withTimeout(5000) { suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value) }
        } }
    }

    private suspend fun state(view: WebView, script: String): JSONObject =
        JSONObject(JSONArray("[${evaluate(view, script)}]").getString(0))

    /** Only the bounded startup wait admits a missing frame; all other state probes remain strict. */
    private suspend fun startupFrame(view: WebView, script: String): JSONObject? {
        val decoded = JSONArray("[${evaluate(view, script)}]").opt(0)
        if (decoded == null || decoded === JSONObject.NULL) return null
        check(decoded is String) { "The startup frame callback was not serialized JSON" }
        return JSONObject(decoded)
    }

    private fun safeErrorCategory(message: String?): String = when {
        message == null -> "none"
        message.contains("提前退出") -> "process_exited_before_ready"
        message.contains("未在期限内") || message.contains("timeout", true) || message.contains("超时") -> "timeout"
        message.contains("健康检查失败") -> "authenticated_health_failed"
        message.contains("取消或替换") -> "launch_cancelled_or_replaced"
        message.contains("ABI") || message.contains("Node.js 组件尚未就绪") || message.contains("Node 版本") -> "runtime_component_mismatch"
        message.contains("先安装") -> "runtime_component_missing"
        message.contains("永久删除") || message.contains("归档") -> "history_lifecycle_blocked"
        message.contains("共享") || message.contains("历史") -> "shared_persistence_failed"
        message.contains("凭据") || message.contains("credential", true) -> "credential_adapter_failed"
        message.contains("Permission denied", true) || message.contains("Operation not permitted", true) -> "runtime_permission_denied"
        else -> "unclassified_redacted"
    }

    private suspend fun nativeWindowState(activity: DshWebLifecycleQaActivity, view: WebView?): JSONObject = withContext(Dispatchers.Main) {
        val decor = activity.window.decorView
        val state = JSONObject().put("activityResumed", activity.lifecycle.currentState == Lifecycle.State.RESUMED)
            .put("activityDestroyed", activity.isDestroyed).put("activityFinishing", activity.isFinishing)
            .put("decorAttached", decor.isAttachedToWindow).put("decorShown", decor.isShown)
            .put("decorVisibility", decor.visibility).put("decorWindowVisibility", decor.windowVisibility)
            .put("decorHasWindowFocus", decor.hasWindowFocus()).put("decorWindowTokenPresent", decor.windowToken != null)
            .put("decorWidth", decor.width).put("decorHeight", decor.height)
            .put("windowAlpha", activity.window.attributes.alpha).put("viewPresent", view != null)
        if (view != null) {
            val window = IntArray(2); view.getLocationInWindow(window)
            val screen = IntArray(2); view.getLocationOnScreen(screen)
            val visible = Rect(); val hasVisibleRect = view.getGlobalVisibleRect(visible)
            state.put("viewAttached", view.isAttachedToWindow).put("viewShown", view.isShown)
                .put("viewVisibility", view.visibility).put("viewWindowVisibility", view.windowVisibility)
                .put("viewAlpha", view.alpha).put("viewHasWindowFocus", view.hasWindowFocus())
                .put("viewHardwareAccelerated", view.isHardwareAccelerated).put("viewLayerType", view.layerType)
                .put("viewWidth", view.width).put("viewHeight", view.height)
                .put("locationInWindow", JSONArray(window.toList())).put("locationOnScreen", JSONArray(screen.toList()))
                .put("hasGlobalVisibleRect", hasVisibleRect)
                .put("globalVisibleRect", JSONArray(listOf(visible.left, visible.top, visible.right, visible.bottom)))
                .put("imeVisible", view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true)
            val ancestors = JSONArray()
            var ancestor: View? = view
            repeat(16) {
                val item = ancestor ?: return@repeat
                ancestors.put(JSONObject().put("visibility", item.visibility).put("alpha", item.alpha)
                    .put("translationX", item.translationX).put("translationY", item.translationY)
                    .put("scaleX", item.scaleX).put("scaleY", item.scaleY).put("rotation", item.rotation)
                    .put("width", item.width).put("height", item.height))
                ancestor = item.parent as? View
            }
            state.put("nativeAncestors", ancestors)
        }
        state
    }

    private suspend fun awaitBundle(view: WebView, marker: String, session: String,
        report: JSONObject, persist: () -> Unit = {}): JSONObject = withTimeout(45_000) {
        val touched = mutableSetOf<String>()
        var credentialImeHidden = false
        val began = SystemClock.elapsedRealtime()
        var lastLayoutMillis = Long.MIN_VALUE
        while (true) {
            withContext(Dispatchers.Main) {
                check(view.isAttachedToWindow && view.parent != null) { "The awaited WebView was retired before readiness" }
            }
            val observed = startupFrame(view, """JSON.stringify((()=>{
                if(!document.documentElement||!document.body)return {frameReady:false,
                    documentReadyState:document.readyState,documentElementPresent:!!document.documentElement,bodyPresent:!!document.body};
                try{const root=document.querySelector('#root');let chosen=null;
                try{chosen=JSON.parse(localStorage.getItem('dsh.sessions.current')||'null')?.sessionId}catch(e){}
                const visible=e=>{if(!e)return false;const s=getComputedStyle(e),r=e.getBoundingClientRect();
                    return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)>0&&r.width>0&&r.height>0};
                const dialogs=[...document.querySelectorAll('[role="dialog"][aria-modal="true"]')].filter(visible);
                const welcome=dialogs.find(e=>['Preview Notice','预览版说明'].includes(e.getAttribute('aria-label')));
                const credential=dialogs.find(e=>['Add an API key to get started','添加一个 API Key 开始使用'].includes(e.getAttribute('aria-label')));
                const action=(dialog,labels,kind)=>{const button=[...(dialog?.querySelectorAll('button')||[])].find(e=>labels.includes(e.textContent.trim()));
                    if(!button||!visible(button)||button.disabled)return null;const r=button.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;
                    const hit=document.elementFromPoint(x,y);return {kind,x,y,width:innerWidth,height:innerHeight,
                        touchReady:x>0&&x<innerWidth&&y>0&&y<innerHeight&&(hit===button||button.contains(hit))}};
                let markerVisible=false,markerBounds=null;
                if(root&&!root.inert){const walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT);let node;
                    while(node=walker.nextNode()){if(!node.nodeValue.includes(${JSONObject.quote(marker)}))continue;
                        const e=node.parentElement,s=getComputedStyle(e),range=document.createRange();range.selectNodeContents(node);const r=range.getBoundingClientRect();
                        markerBounds={left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height,
                            display:s.display,visibility:s.visibility,opacity:s.opacity};
                        if(s.visibility==='hidden'||s.display==='none'||Number(s.opacity)===0||r.width<8||r.height<8||
                            r.top<0||r.bottom>innerHeight||r.left<0||r.right>innerWidth)continue;
                        const hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);
                        if(hit===e||e.contains(hit)){markerVisible=true;break}}}
                const client=window.__DAFEIYU_DSH_CLIENT__,host=window.__DAFEIYU_DSH_HOST__,probe=window.__DAFEIYU_DSH_PRESENTATION__;
                let presentationReady=false;try{presentationReady=probe?.version===1&&probe.isReady()===true}catch(e){}
                return {frameReady:true,documentReadyState:document.readyState,bodyPresent:true,
                root:!!root?.childElementCount,live:window.__ModuleLoader__?.mode==='live',
                boot:!!root?.querySelector('[data-dsh-boot]'),marker:document.body?.innerText.includes(${JSONObject.quote(marker)}),
                markerVisible,markerBounds,rootInert:!!root?.inert,modalCount:dialogs.length,welcomeNotice:!!welcome,credentialOnboarding:!!credential,
                viewportWidth:innerWidth,viewportHeight:innerHeight,mobileSurface:document.documentElement.getAttribute('data-dafeiyu-surface'),
                mobileMode:document.documentElement.getAttribute('data-dafeiyu-mode'),
                startupState:document.documentElement.getAttribute('data-dafeiyu-startup'),presentationReady,
                clientContext:client?.version===1&&client.platform==='android'&&client.surface==='mobile'&&client.mode==='local',
                documentBound:host?.version===1&&host.platform==='android'&&host.document_bound===true,
                presentationCapability:host?.capabilities?.includes('app.web.presentation')===true,
                applicationSettingsCapability:host?.capabilities?.includes('app.settings.open')===true,
                mobileStyleCount:document.querySelectorAll('style[data-plugin="dafeiyu-mobile-ui"]').length,
                webNavigationCount:document.querySelectorAll('[data-dafeiyu-mobile-layout="navigation"]').length,
                action:welcome?action(welcome,['Continue','继续'],'preview_continue'):action(credential,['Configure later','稍后配置'],'credential_later'),
                session:chosen,buttons:root?.querySelectorAll('button,[role="button"]').length||0};
                }catch(error){return {frameReady:false,documentReadyState:document.readyState,bodyPresent:!!document.body,
                    evaluationError:{name:String(error?.name||'Error').slice(0,80),message:String(error?.message||'').slice(0,240)}}}
                })())""".trimIndent())
            if (observed == null) {
                val elapsed = SystemClock.elapsedRealtime() - began
                val pending = JSONObject().put("frameReady", false).put("callbackNull", true).put("elapsedMillis", elapsed)
                val count = report.optInt("bundleNullFrameCount") + 1
                report.put("bundleNullFrameCount", count).put("lastBundleState", pending)
                if (count == 1) report.put("firstBundleNullFrame", pending)
                // A null callback can precede the first committed document. Keep
                // it visible in the report, and use the same 45s readiness bound.
                // A destroyed/replaced renderer is rejected by ownership above.
                if (lastLayoutMillis == Long.MIN_VALUE || elapsed - lastLayoutMillis >= 2000) {
                    try { report.put("bundleNullFrameLayout", dshLayoutDiagnostic(view, marker)) }
                    catch (diagnosticFailure: Throwable) {
                        if (diagnosticFailure is kotlinx.coroutines.CancellationException) throw diagnosticFailure
                        report.put("bundleNullFrameLayoutFailureClass", diagnosticFailure.javaClass.simpleName)
                    }
                    lastLayoutMillis = elapsed
                }
                persist(); delay(200); continue
            }
            report.put("lastBundleState", observed)
            observed.optJSONObject("evaluationError")?.let { failure ->
                report.put("bundleFrameEvaluationError", failure); persist()
                error("Startup frame evaluation failed: ${failure.optString("name")}")
            }
            if (!observed.optBoolean("frameReady")) {
                report.put("lastBundlePendingDocument", observed)
                persist(); delay(200); continue
            }
            val elapsed = SystemClock.elapsedRealtime() - began
            if ((observed.optBoolean("marker") || observed.optString("mobileSurface") == "mobile") &&
                (lastLayoutMillis == Long.MIN_VALUE || elapsed - lastLayoutMillis >= 2000)) {
                // Read-only snapshots are persisted during the wait, not only after
                // its timeout. Never scroll, alter a layout, or move the fixture
                // into view to satisfy the visibility/pixel assertions below.
                val layout = dshLayoutDiagnostic(view, marker).put("elapsedMillis", elapsed)
                    .put("markerVisible", observed.optBoolean("markerVisible"))
                    .put("stage", report.optString("stage"))
                if (!report.has("firstDshLayout")) report.put("firstDshLayout", layout)
                report.put("lastDshLayout", layout)
                val samples = report.optJSONArray("recentDshLayouts") ?: JSONArray().also { report.put("recentDshLayouts", it) }
                if (samples.length() >= 4) samples.remove(0)
                samples.put(layout)
                lastLayoutMillis = elapsed
            }
            persist()
            if (observed.optBoolean("credentialOnboarding") && !credentialImeHidden) {
                withContext(Dispatchers.Main) { view.windowInsetsController?.hide(android.view.WindowInsets.Type.ime()) }
                credentialImeHidden = true
                report.put("onboardingImeHideRequested", true); persist()
                delay(250); continue
            }
            val action = observed.optJSONObject("action")
            val kind = action?.optString("kind").orEmpty()
            if (kind in setOf("preview_continue", "credential_later") && kind !in touched && action?.optBoolean("touchReady") == true) {
                val actions = report.optJSONArray("onboardingActions") ?: JSONArray().also { report.put("onboardingActions", it) }
                val entry = JSONObject().put("control", kind).put("actualTouchIssued", false).put("completed", false)
                actions.put(entry); persist()
                val readyAction = checkNotNull(action)
                val point = withContext(Dispatchers.Main) {
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    (location[0] + readyAction.getDouble("x") * view.width / readyAction.getDouble("width")).toFloat() to
                        (location[1] + readyAction.getDouble("y") * view.height / readyAction.getDouble("height")).toFloat()
                }
                tap(point.first, point.second); touched.add(kind)
                entry.put("actualTouchIssued", true); persist()
                delay(250); continue
            }
            report.optJSONArray("onboardingActions")?.let { actions ->
                for (index in 0 until actions.length()) {
                    val entry = actions.getJSONObject(index)
                    if (entry.optBoolean("actualTouchIssued") && when (entry.optString("control")) {
                            "preview_continue" -> !observed.optBoolean("welcomeNotice")
                            "credential_later" -> !observed.optBoolean("credentialOnboarding")
                            else -> false
                        }) entry.put("completed", true)
                }
            }
            if (observed.optBoolean("root") && observed.optBoolean("live") && !observed.optBoolean("boot") &&
                observed.optBoolean("marker") && observed.optBoolean("markerVisible") && !observed.optBoolean("rootInert") &&
                observed.optString("mobileSurface") == "mobile" && observed.optString("mobileMode") == "local" &&
                observed.optBoolean("clientContext") && observed.optBoolean("documentBound") &&
                observed.optBoolean("presentationReady") && observed.optBoolean("presentationCapability") &&
                observed.optBoolean("applicationSettingsCapability") && observed.optString("startupState") == "ready" &&
                observed.optInt("mobileStyleCount") == 1 && observed.optInt("webNavigationCount") == 1 &&
                observed.optInt("modalCount") == 0 && !observed.optBoolean("welcomeNotice") && !observed.optBoolean("credentialOnboarding") &&
                observed.optString("session") == session && observed.optInt("buttons") > 0) {
                persist(); return@withTimeout observed
            }
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** Layout evidence only. No DOM/style/scroll writes and no page content or credential dump. */
    private suspend fun dshLayoutDiagnostic(view: WebView, marker: String): JSONObject = state(view, """JSON.stringify((()=>{
        const sample=e=>{
            if(!e)return null;
            const r=e.getBoundingClientRect(),s=getComputedStyle(e);
            const style={};
            for(const name of ['display','box-sizing','width','height','min-width','min-height','max-width','max-height',
                'position','top','right','bottom','left','overflow','overflow-x','overflow-y','transform','transform-origin',
                'translate','scale','zoom','opacity','visibility','content-visibility','contain','z-index',
                'padding-top','padding-right','padding-bottom','padding-left','margin-top','margin-right','margin-bottom','margin-left',
                'flex','flex-direction','flex-grow','flex-shrink','flex-basis','align-items','align-self','justify-content',
                'grid-template-rows','grid-template-columns','grid-row','grid-column','gap','line-height'])style[name]=s.getPropertyValue(name);
            const attributes={};
            for(const name of ['id','class','role','data-slot','data-dafeiyu-mobile-layout','data-phase','data-content-phase',
                'data-composer-seat','data-sidebar-collapsed','data-dafeiyu-startup','data-dafeiyu-drawer']){
                const value=e.getAttribute(name);if(value!==null)attributes[name]=value.slice(0,240);
            }
            return {tag:e.tagName,attributes,inlineStyle:e.getAttribute('style'),connected:e.isConnected,hidden:e.hidden===true,inert:e.inert===true,
                bounds:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height},style,
                metrics:{clientWidth:e.clientWidth,clientHeight:e.clientHeight,scrollWidth:e.scrollWidth,scrollHeight:e.scrollHeight,
                    scrollTop:e.scrollTop,scrollLeft:e.scrollLeft,offsetWidth:e.offsetWidth,offsetHeight:e.offsetHeight,
                    offsetTop:e.offsetTop,offsetLeft:e.offsetLeft},
                offsetParent:e.offsetParent?{tag:e.offsetParent.tagName,id:e.offsetParent.id,
                    slot:e.offsetParent.getAttribute('data-slot'),layout:e.offsetParent.getAttribute('data-dafeiyu-mobile-layout')}:null};
        };
        const ancestry=e=>{const rows=[];for(let p=e;p&&rows.length<18;p=p.parentElement)rows.push(sample(p));return rows};
        const root=document.querySelector('#root');
        let markerElement=null,markerTextBounds=null;
        if(root){const walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT);let node;
            while(node=walker.nextNode()){if(!node.nodeValue.includes(${JSONObject.quote(marker)}))continue;
                markerElement=node.parentElement;const range=document.createRange();range.selectNodeContents(node);const r=range.getBoundingClientRect();
                markerTextBounds={left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height};break}}
        const composer=document.querySelector('[data-slot="conversation.composer.bar"]');
        const primary={};
        for(const [name,selector] of Object.entries({html:'html',body:'body',root:'#root',rootSlot:'[data-slot="root"]',
            main:'[data-slot="main"]',mainConversation:'[data-slot="main.conversation"]',
            frame:'[data-dafeiyu-mobile-layout="frame"]',center:'[data-dafeiyu-mobile-layout="center"]',
            conversation:'[data-dafeiyu-mobile-layout="conversation-root"]',
            composer:'[data-slot="conversation.composer.bar"]',composerStack:'[data-dafeiyu-mobile-layout="composer-stack"]',
            composerCard:'[data-dafeiyu-mobile-layout="composer-card"]',navigation:'[data-dafeiyu-mobile-layout="navigation"]'})){
            const nodes=[...document.querySelectorAll(selector)];primary[name]={count:nodes.length,nodes:nodes.slice(0,3).map(sample)};
        }
        const tree=[],queue=root?[{element:root,depth:0,parent:-1}]:[];
        while(queue.length&&tree.length<64){const entry=queue.shift(),index=tree.length;
            tree.push({index,parent:entry.parent,depth:entry.depth,...sample(entry.element)});
            if(entry.depth<7)for(const child of [...entry.element.children].slice(0,12))queue.push({element:child,depth:entry.depth+1,parent:index});
        }
        const viewport=window.visualViewport;
        const mobileStyle=document.querySelector('style[data-plugin="dafeiyu-mobile-ui"]');
        let mobileCss={present:!!mobileStyle,textLength:mobileStyle?.textContent?.length||0,
            hasViewportRule:mobileStyle?.textContent?.includes('100dvh')===true,frameRules:[]};
        try {
            for(const rule of [...(mobileStyle?.sheet?.cssRules||[])]) {
                if(!rule.selectorText?.includes('data-dafeiyu-mobile-layout')) continue;
                const text=String(rule.cssText||'');
                if(text.includes('frame')) mobileCss.frameRules.push({selector:rule.selectorText,
                    height:rule.style?.getPropertyValue('height')||'',heightPriority:rule.style?.getPropertyPriority('height')||'',
                    maxHeight:rule.style?.getPropertyValue('max-height')||'',maxHeightPriority:rule.style?.getPropertyPriority('max-height')||''});
            }
        } catch(error) { mobileCss.ruleReadError=String(error?.message||error).slice(0,240); }
        return {readOnly:true,capturedAt:Date.now(),mobileCss,viewport:{innerWidth,innerHeight,outerWidth,outerHeight,devicePixelRatio,
            scrollX,scrollY,documentReadyState:document.readyState,
            visual:viewport?{width:viewport.width,height:viewport.height,offsetLeft:viewport.offsetLeft,offsetTop:viewport.offsetTop,
                pageLeft:viewport.pageLeft,pageTop:viewport.pageTop,scale:viewport.scale}:null},
            rootAncestors:ancestry(root),markerTextBounds,markerAncestors:ancestry(markerElement),
            composerAncestors:ancestry(composer),primary,primaryTree:tree,primaryTreeCapped:queue.length>0};
        })())""".trimIndent())

    private suspend fun capture(activity: DshWebLifecycleQaActivity, view: WebView, report: JSONObject? = null): Bitmap {
        report?.put("nativeWindow", nativeWindowState(activity, view))
        withContext(Dispatchers.Main) { withTimeout(5000) { suspendCancellableCoroutine { continuation ->
            view.postVisualStateCallback(++frameId, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { if (continuation.isActive) continuation.resume(Unit) }
            })
            view.postInvalidateOnAnimation()
        } } }
        delay(60)
        val (rect, bitmap) = withContext(Dispatchers.Main) {
            check(view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0)
            val location = IntArray(2); view.getLocationInWindow(location)
            Rect(location[0], location[1], location[0] + view.width, location[1] + view.height) to
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        }
        report?.put("pixelCopySourceWindowRect", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
        return try {
            val result = withContext(Dispatchers.Main) { withTimeout(5000) { suspendCancellableCoroutine { continuation ->
                PixelCopy.request(activity.window, rect, bitmap, { code -> if (continuation.isActive) continuation.resume(code) }, mainHandler)
            } } }
            report?.put("lastPixelCopyCode", result)
            check(result == PixelCopy.SUCCESS) { "The actual Activity window did not produce a readable frame: $result" }
            bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    private suspend fun captureFullWindow(activity: DshWebLifecycleQaActivity): Bitmap {
        val bitmap = withContext(Dispatchers.Main) {
            val decor = activity.window.decorView
            check(activity.lifecycle.currentState == Lifecycle.State.RESUMED && !activity.isDestroyed && !activity.isFinishing)
            check(decor.isAttachedToWindow && decor.isShown && decor.hasWindowFocus() && decor.width > 0 && decor.height > 0)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        }
        return try {
            val code = withContext(Dispatchers.Main) { withTimeout(5000) { suspendCancellableCoroutine { continuation ->
                PixelCopy.request(activity.window, bitmap, { result -> if (continuation.isActive) continuation.resume(result) }, mainHandler)
            } } }
            check(code == PixelCopy.SUCCESS) { "Full Activity window PixelCopy failed: $code" }
            bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    /** Excludes all virtual WebView descendants, so native controls cannot be confused with web buttons. */
    private fun nativeVisibleLabels(packageName: String): Set<String> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptySet()
        check(root.packageName?.toString() == packageName) { "Refuse to inspect an unrelated active window" }
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val labels = mutableSetOf<String>()
        var visited = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            check(++visited <= 2048)
            if (node.className?.toString() == WebView::class.java.name) continue
            if (node.isVisibleToUser) {
                node.text?.toString()?.let(labels::add)
                node.contentDescription?.toString()?.let(labels::add)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return labels
    }

    private fun nativeTagState(packageName: String, tag: String): JSONObject {
        val root = instrumentation.uiAutomation.rootInActiveWindow
            ?: return JSONObject().put("present", false).put("accessibilityRootPresent", false)
        check(root.packageName?.toString() == packageName)
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst(); check(++visited <= 2048)
            val id = node.viewIdResourceName
            if (id == tag || id?.endsWith("/$tag") == true) {
                val descendants = ArrayDeque<AccessibilityNodeInfo>().apply { add(node) }
                var webCount = 0
                var total = 0
                while (descendants.isNotEmpty()) {
                    val child = descendants.removeFirst(); check(++total <= 2048)
                    if (child.className?.toString() == WebView::class.java.name) webCount++
                    for (index in 0 until child.childCount) child.getChild(index)?.let(descendants::addLast)
                }
                return JSONObject().put("present", true).put("webViewDescendants", webCount)
                    .put("nodeCount", total).put("accessibilityRootPresent", true)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return JSONObject().put("present", false).put("accessibilityRootPresent", true)
    }

    private suspend fun awaitWebDrawer(view: WebView, open: Boolean) = withTimeout(10_000) {
        while (state(view, "JSON.stringify({open:document.documentElement.getAttribute('data-dafeiyu-drawer')==='open'})")
                .optBoolean("open") != open) delay(100)
    }

    private suspend fun awaitWebPanel(view: WebView, selector: String, minimumHeight: Double): JSONObject = withTimeout(12_000) {
        while (true) {
            val panel = state(view, """JSON.stringify((()=>{
                const e=document.querySelector(${JSONObject.quote(selector)});if(!e)return {ready:false};
                const r=e.getBoundingClientRect(),s=getComputedStyle(e),x=r.left+r.width/2,y=r.top+r.height/2;
                const hit=document.elementFromPoint(x,y);
                return {ready:!e.hidden&&!e.inert&&s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)>.95&&
                    r.width>100&&r.height>${minimumHeight}&&r.top>=0&&r.bottom<=innerHeight+1&&r.left>=0&&r.right<=innerWidth+1&&
                    (hit===e||e.contains(hit)),left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height,
                    buttons:e.querySelectorAll('button').length,viewportHeight:innerHeight,
                    viewportVariable:document.documentElement.style.getPropertyValue('--df-viewport-height')}
                })())""".trimIndent())
            if (panel.optBoolean("ready")) return@withTimeout panel
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private suspend fun idleFrameCount(activity: DshWebLifecycleQaActivity): Int {
        delay(1500) // Let finite transitions and feed reads settle before sampling.
        var count = 0
        val listener = Window.OnFrameMetricsAvailableListener { _, _, _ -> count++ }
        withContext(Dispatchers.Main) { activity.window.addOnFrameMetricsAvailableListener(listener, mainHandler) }
        return try {
            delay(2000)
            withContext(Dispatchers.Main) { count }
        } finally { withContext(Dispatchers.Main) { activity.window.removeOnFrameMetricsAvailableListener(listener) } }
    }

    /** Real pointer input uses a DOM coordinate only after it passes the viewport and hit-test checks. */
    private suspend fun tapWebControl(view: WebView, control: String) {
        val point = withTimeout(15_000) {
            while (true) {
                val observed = state(view, """JSON.stringify((()=>{
                    const candidates=[...document.querySelectorAll('[data-dafeiyu-mobile-control]')]
                        .filter(e=>e.getAttribute('data-dafeiyu-mobile-control')===${JSONObject.quote(control)});
                    if(candidates.length!==1)return {ready:false,count:candidates.length};
                    const e=candidates[0],r=e.getBoundingClientRect(),s=getComputedStyle(e);
                    const x=r.left+r.width/2,y=r.top+r.height/2,hit=document.elementFromPoint(x,y);
                    return {ready:!e.disabled&&!e.closest('[inert]')&&s.display!=='none'&&s.visibility!=='hidden'&&
                        Number(s.opacity)>.95&&r.width>=8&&r.height>=8&&x>0&&x<innerWidth&&y>0&&y<innerHeight&&
                        (hit===e||e.contains(hit)),x,y,width:innerWidth,height:innerHeight}
                    })())""".trimIndent())
                if (observed.optBoolean("ready")) return@withTimeout withContext(Dispatchers.Main) {
                    check(view.isAttachedToWindow && view.isShown && view.hasWindowFocus())
                    val position = IntArray(2); view.getLocationOnScreen(position)
                    (position[0] + observed.getDouble("x") * view.width / observed.getDouble("width")).toFloat() to
                        (position[1] + observed.getDouble("y") * view.height / observed.getDouble("height")).toFloat()
                }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        tap(point.first, point.second)
    }

    private suspend fun awaitQuadrants(activity: DshWebLifecycleQaActivity, view: WebView, phase: Int,
        report: JSONObject, persist: () -> Unit): Bitmap = withTimeout(12_000) {
        val colours = intArrayOf(Color.rgb(220, 35, 35), Color.rgb(30, 165, 70), Color.rgb(35, 75, 220), Color.rgb(245, 200, 35))
        while (true) {
            val pixels = capture(activity, view, report)
            val locations = listOf(.25f to .25f, .75f to .25f, .25f to .75f, .75f to .75f)
            val samples = locations.map { point -> pixels.getPixel((pixels.width * point.first).toInt(), (pixels.height * point.second).toInt()) }
            val matches = samples.withIndex().all { (index, colour) -> colourDistance(colour, colours[(index + phase) % 4]) <= 45 }
            report.put("canvasPhase$phase", JSONObject().put("sampleArgb", JSONArray(samples)).put("phase", phase)
                .put("colourTolerance", 45).put("passed", matches))
            persist()
            if (matches) return@withTimeout pixels
            pixels.recycle(); delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private suspend fun proveHistoryPixels(activity: DshWebLifecycleQaActivity, view: WebView, marker: String,
        session: String, evidence: File, label: String, report: JSONObject) {
        awaitBundle(view, marker, session, report)
        val region = state(view, """JSON.stringify((()=>{const walker=document.createTreeWalker(document.querySelector('#root'),NodeFilter.SHOW_TEXT);
            let node;while(node=walker.nextNode()){if(!node.nodeValue.includes(${JSONObject.quote(marker)}))continue;
            const element=node.parentElement,style=getComputedStyle(element),range=document.createRange();range.selectNodeContents(node);
            const r=range.getBoundingClientRect();if(style.visibility==='hidden'||style.display==='none'||Number(style.opacity)===0||
                r.width<8||r.height<8||r.top<0||r.bottom>innerHeight||r.left<0||r.right>innerWidth)continue;
            window.__qaPaintText={element,value:element.style.getPropertyValue('visibility'),priority:element.style.getPropertyPriority('visibility')};
            return {found:true,left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:innerWidth,height:innerHeight}}
            return {found:false}})())""".trimIndent())
        check(region.optBoolean("found")) { "The synthetic stored history has no visible text region in the real DSH page" }
        val visible = capture(activity, view, report)
        var hidden: Bitmap? = null
        var restored: Bitmap? = null
        try {
            evaluate(view, "window.__qaPaintText.element.style.setProperty('visibility','hidden','important')")
            hidden = capture(activity, view, report)
            evaluate(view, "(()=>{const p=window.__qaPaintText;if(p.value)p.element.style.setProperty('visibility',p.value,p.priority);else p.element.style.removeProperty('visibility')})()")
            restored = capture(activity, view, report)
            check(visible.width == hidden.width && visible.height == hidden.height && restored.width == visible.width && restored.height == visible.height)
            val bounds = Rect(max(0, (region.getDouble("left") / region.getDouble("width") * visible.width).toInt() - 2),
                max(0, (region.getDouble("top") / region.getDouble("height") * visible.height).toInt() - 2),
                min(visible.width, (region.getDouble("right") / region.getDouble("width") * visible.width).toInt() + 2),
                min(visible.height, (region.getDouble("bottom") / region.getDouble("height") * visible.height).toInt() + 2))
            var hiddenChanges = 0
            var restoreMismatch = 0
            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                if (colourDistance(visible.getPixel(x, y), hidden.getPixel(x, y)) > 45) hiddenChanges++
                if (colourDistance(visible.getPixel(x, y), restored.getPixel(x, y)) > 45) restoreMismatch++
            }
            val minimumChanges = max(20, bounds.width() * bounds.height() / 200)
            report.put(label, JSONObject().put("changedTextPixels", hiddenChanges).put("restorationMismatchPixels", restoreMismatch)
                .put("minimumChangedPixels", minimumChanges).put("sampleWidth", bounds.width()).put("sampleHeight", bounds.height()))
            check(hiddenChanges >= minimumChanges) { "DOM text exists but its visibility has no actual window-pixel effect" }
            check(restoreMismatch <= max(12, hiddenChanges / 5)) { "The actual painted text did not restore to its prior frame" }
            saveImage(visible, File(evidence, "$label-visible.png"))
            saveImage(hidden, File(evidence, "$label-hidden.png"))
            saveImage(restored, File(evidence, "$label-restored.png"))
        } finally {
            // Never leave the live first-party document altered if PixelCopy fails.
            runCatching { evaluate(view, "(()=>{const p=window.__qaPaintText;if(!p)return;if(p.value)p.element.style.setProperty('visibility',p.value,p.priority);else p.element.style.removeProperty('visibility');delete window.__qaPaintText})()") }
            visible.recycle(); hidden?.recycle(); restored?.recycle()
        }
    }

    private suspend fun tapView(view: WebView) {
        val point = withContext(Dispatchers.Main) { val location = IntArray(2); view.getLocationOnScreen(location)
            (location[0] + view.width / 2f) to (location[1] + view.height / 2f) }
        tap(point.first, point.second)
    }

    private suspend fun tapNode(label: String) {
        val bounds = withTimeout(15_000) {
            while (true) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root != null) {
                    val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
                    var visited = 0
                    while (pending.isNotEmpty()) {
                        val node = pending.removeFirst(); check(++visited <= 2048)
                        if (node.isVisibleToUser && (node.contentDescription?.toString() == label || node.text?.toString() == label)) {
                            val rect = Rect(); node.getBoundsInScreen(rect)
                            if (!rect.isEmpty) return@withTimeout rect
                        }
                        for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(it) }
                    }
                }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        tap(bounds.exactCenterX(), bounds.exactCenterY())
        delay(650)
    }

    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up) }
        finally { down.recycle(); up.recycle() }
    }

    private fun webViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun saveImage(bitmap: Bitmap, file: File) = file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    private fun colourDistance(a: Int, b: Int) = abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
    private fun digest(file: File): String? = if (!file.exists()) null else MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private suspend fun installPaintFixture(view: WebView, owner: String): JSONObject = state(view, """JSON.stringify((()=>{
        const owner=${JSONObject.quote(owner)};
        if(window.__qaMainScreenPaint)return {inserted:false,error:'occupied'};
        if(!document.body)return {inserted:false,error:'body_missing'};
        const s={owner,phase:-1,clickCount:0,contextReady:false,error:'none',frame:0};window.__qaMainScreenPaint=s;
        try{
            const overlay=document.createElement('div');s.overlay=overlay;
            const properties={all:'initial',position:'fixed',inset:'0',width:'auto',height:'auto',display:'block',
                margin:'0',padding:'0',opacity:'1',visibility:'visible',background:'transparent',
                'z-index':'2147483647',transform:'none','pointer-events':'auto',overflow:'hidden'};
            for(const [key,value] of Object.entries(properties))overlay.style.setProperty(key,value,'important');
            const shadow=overlay.attachShadow({mode:'closed'}),canvas=document.createElement('canvas'),button=document.createElement('button');
            s.canvas=canvas;canvas.style.cssText='display:block;width:100%;height:100%;margin:0;padding:0';
            button.type='button';button.textContent='Swap paint';
            button.style.cssText='position:absolute;left:40%;top:40%;width:20%;height:20%;color:black;background:white;border:2px solid black;z-index:1;pointer-events:auto';
            shadow.append(canvas,button);document.body.append(overlay);
            const ctx=canvas.getContext('2d');s.contextReady=!!ctx;
            if(!ctx){s.error='context_missing';return {inserted:true,error:s.error}}
            const colours=['rgb(220,35,35)','rgb(30,165,70)','rgb(35,75,220)','rgb(245,200,35)'];
            function paint(phase){
                canvas.width=Math.round(innerWidth*devicePixelRatio);canvas.height=Math.round(innerHeight*devicePixelRatio);
                if(canvas.width<1||canvas.height<1){s.error='viewport_empty';return}
                for(let q=0;q<4;q++){ctx.fillStyle=colours[(q+phase)%4];
                    ctx.fillRect((q%2)*canvas.width/2,Math.floor(q/2)*canvas.height/2,canvas.width/2,canvas.height/2)}
                cancelAnimationFrame(s.frame);s.frame=requestAnimationFrame(()=>{s.phase=phase});
            }
            button.addEventListener('click',()=>{s.clickCount++;paint((Math.max(s.phase,0)+1)%4)});
            s.resize=()=>paint(Math.max(s.phase,0));window.addEventListener('resize',s.resize);paint(0);
            return {inserted:true,error:s.error};
        }catch(e){s.error='injection_failed';return {inserted:false,error:s.error}}
        })())""".trimIndent())

    private suspend fun paintFixtureState(view: WebView, owner: String): JSONObject = state(view, """JSON.stringify((()=>{
        const s=window.__qaMainScreenPaint,owner=${JSONObject.quote(owner)};
        if(!s)return {present:false,ownerMatches:false,phase:-1,error:'missing'};
        if(s.owner!==owner)return {present:true,ownerMatches:false,phase:-1,error:'owner_mismatch'};
        const rect=e=>{if(!e)return null;const r=e.getBoundingClientRect();return {left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height}};
        const style=e=>{if(!e)return null;const c=getComputedStyle(e);return {display:c.display,visibility:c.visibility,opacity:c.opacity,
            position:c.position,zIndex:c.zIndex,pointerEvents:c.pointerEvents,transform:c.transform}};
        const pointOwnership=[[.25,.25],[.75,.25],[.25,.75],[.75,.75]].map(([x,y])=>{
            const hit=document.elementFromPoint(innerWidth*x,innerHeight*y);return {hitPresent:!!hit,onFixture:hit===s.overlay}});
        return {present:true,ownerMatches:true,phase:s.phase,clickCount:s.clickCount,contextReady:s.contextReady,
            connected:!!s.overlay?.isConnected,viewportWidth:innerWidth,viewportHeight:innerHeight,
            canvasWidth:s.canvas?.width||0,canvasHeight:s.canvas?.height||0,error:s.error,
            overlayRect:rect(s.overlay),canvasRect:rect(s.canvas),overlayStyle:style(s.overlay),canvasStyle:style(s.canvas),
            rootInert:!!document.querySelector('#root')?.inert,pointOwnership}
        })())""".trimIndent())

    private suspend fun removePaintFixture(view: WebView, owner: String): JSONObject = state(view, """JSON.stringify((()=>{
        const s=window.__qaMainScreenPaint;
        if(!s)return {removed:false,absent:true,ownerMatches:true};
        if(s.owner!==${JSONObject.quote(owner)})return {removed:false,absent:false,ownerMatches:false};
        cancelAnimationFrame(s.frame);if(s.resize)window.removeEventListener('resize',s.resize);
        s.overlay?.remove();delete window.__qaMainScreenPaint;
        return {removed:true,absent:!window.__qaMainScreenPaint,ownerMatches:true}
        })())""".trimIndent())
}
