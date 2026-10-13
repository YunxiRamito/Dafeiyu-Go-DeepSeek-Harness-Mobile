package com.dafeiyu.mobile.glasslab

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dafeiyu.mobile.ui.components.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Real app UI, durable Android files and runtime AGSL, using no network or model calls. */
@RunWith(AndroidJUnit4::class)
class MaterialLabDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val checks = JSONArray()
    private val output by lazy { File(ui.activity.getExternalFilesDir(null), "qa/material-lab").apply { mkdirs() } }

    private fun check(name: String, body: () -> Unit) {
        File(output, "progress.json").writeText(JSONObject().put("stage", name).put("completed", checks).toString(2))
        try { body(); checks.put(JSONObject().put("name", name).put("passed", true)) }
        catch (error: Throwable) {
            checks.put(JSONObject().put("name", name).put("passed", false).put("error", error.toString()))
            throw error
        }
        File(output, "progress.json").writeText(JSONObject().put("stage", "after:$name").put("completed", checks).toString(2))
    }

    @Test fun appControlsRuntimeAndPersistence() {
        val model = ViewModelProvider(ui.activity)[LabState::class.java]
        var original: LabDocument? = null
        val testId = UUID.randomUUID().toString()
        val testRoot = File(ui.activity.filesDir, "qa-store-$testId")
        val isolated = object : ContextWrapper(ui.activity) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = testRoot
        }
        try {
            ui.waitUntil(6000) { !model.isLoading }
            original = model.document
            ui.runOnIdle { model.update(LabDocument.defaults()) }
            ui.waitForIdle()
            check("real_main_activity_visible") {
                ui.onNodeWithText("Dafeiyu调色盘").assertIsDisplayed()
                ui.onNodeWithTag("material-preview").assertIsDisplayed()
            }
            check("preview_background_presets_and_palette_are_independent_of_material") {
                ui.onNodeWithTag("preview-backgrounds").performClick()
                ui.onNodeWithTag("background-solid-cloud").performScrollTo().performClick()
                assertEquals(listOf("#FFF2F2F7"), model.document.previewBackgroundColors)
                assertEquals(LabParameters.defaults("circle", false), model.document.current())
                ui.onNodeWithTag("background-gradient-sky").performScrollTo().performClick()
                assertEquals(LabGradientBackgrounds.first().colors, model.document.previewBackgroundColors)
                ui.onNodeWithText("完成").performClick()
                ui.onNodeWithTag("preview-palette").performClick()
                val before = model.document.previewBackgroundColors
                ui.onNodeWithTag("color-spectrum").performTouchInput { click(Offset(width * .5f, height * .35f)) }
                ui.waitForIdle()
                assertNotEquals(before, model.document.previewBackgroundColors)
                assertEquals(LabParameters.defaults("circle", false), model.document.current())
                ui.onNodeWithText("完成").performClick()
                ui.runOnIdle { model.update(LabDocument.defaults()) }
            }
            check("reference_image_background_is_loaded_and_drawn_under_controls") {
                ui.runOnIdle { model.update(LabDocument.defaults().copy(backgroundPreset = "solid",
                    previewBackgroundColors = listOf("#FFF2F2F7"), previewBackgroundLabel = "云灰")) }
                ui.waitForIdle()
                val before = screenshot("image-background-before.png")
                ui.onNodeWithTag("preview-backgrounds").performClick()
                ui.onNodeWithTag("background-image-reference").performScrollTo().performClick()
                assertEquals("reference", model.document.previewBackgroundImage)
                assertTrue(model.document.previewBackgroundImageData.isEmpty())
                assertEquals(LabDocument.defaults().light, model.document.light)
                assertEquals(LabDocument.defaults().dark, model.document.dark)
                ui.onNodeWithText("完成").performClick()
                awaitPreviewImage()
                val image = runBlocking { LabBackgroundImages.load(ui.activity, "reference", "") }
                assertNotNull("bundled reference must decode", image)
                val decoded = image!!.asAndroidBitmap()
                assertTrue(decoded.width in 1..1280 && decoded.height in 1..1280)
                val sampledColors = listOf(.1f, .3f, .6f, .9f).flatMap { y ->
                    listOf(.1f, .3f, .6f, .9f).map { x -> decoded.getPixel((decoded.width * x).toInt(), (decoded.height * y).toInt()) }
                }.toSet()
                assertTrue("reference must retain image details", sampledColors.size > 8)
                val after = screenshot("reference-background.png")
                val changed = changedPreviewEdgePixels(before, after)
                assertTrue("preview background pixels must change beyond the button, changed=$changed", changed > 300)
                ui.onNodeWithTag("preview-backgrounds").performClick()
                ui.onNodeWithTag("background-solid-cloud").performScrollTo().performClick()
                assertEquals("none", model.document.previewBackgroundImage)
                assertTrue(model.document.previewBackgroundImageData.isEmpty())
                ui.onNodeWithText("完成").performClick()
                ui.runOnIdle { model.update(LabDocument.defaults()) }
            }
            check("photo_import_is_bounded_and_portable_without_external_uri") {
                testRoot.mkdirs()
                val selected = File(testRoot, "photo-reference.png")
                ui.activity.assets.open("preview-backgrounds/reference.png").use { source ->
                    selected.outputStream().use { source.copyTo(it) }
                }
                val imported = runBlocking { LabBackgroundImages.importPhoto(ui.activity, Uri.fromFile(selected)) }
                assertTrue(imported.width in 1..1280 && imported.height in 1..1280)
                assertTrue(imported.data.length <= LabDocument.MAX_IMAGE_DATA_CHARACTERS)
                val jpeg = Base64.decode(imported.data, Base64.NO_WRAP)
                assertTrue(jpeg.size in 10..LabDocument.MAX_IMAGE_BYTES)
                assertEquals(0xFF, jpeg[0].toInt() and 255); assertEquals(0xD8, jpeg[1].toInt() and 255)
                assertEquals(0xFF, jpeg[jpeg.lastIndex - 1].toInt() and 255); assertEquals(0xD9, jpeg.last().toInt() and 255)
                val saved = LabDocument.defaults().copy(previewBackgroundImage = "photo",
                    previewBackgroundImageData = imported.data, previewBackgroundLabel = "相册图片")
                assertEquals(LabDocument.defaults().light, saved.light)
                assertEquals(LabDocument.defaults().dark, saved.dark)
                assertEquals(76, LabParameters.specs.size); assertEquals(16, LabParameters.colorKeys.size)
                val export = File(output, "photo-profile.json").apply { writeText(saved.encode()) }
                assertTrue(export.length() <= LabDocument.MAX_IMPORT_BYTES)
                assertFalse(export.readText().contains(selected.absolutePath))
                val restored = LabDocument.decode(export.readText())
                assertEquals(saved, restored)
                assertArrayEquals(jpeg, Base64.decode(restored.previewBackgroundImageData, Base64.NO_WRAP))
                assertEquals(4, JSONObject(export.readText()).getJSONObject("light").length())
                assertEquals(4, JSONObject(export.readText()).getJSONObject("dark").length())
                val photoStoreContext = object : ContextWrapper(isolated) {
                    override fun getApplicationContext(): Context = this
                    override fun getFilesDir(): File = File(testRoot, "photo-store")
                }
                LabPresetStore(photoStoreContext).save(restored)
                val durable = LabPresetStore(photoStoreContext).load()!!
                assertEquals(restored, durable)
                val image = runBlocking { LabBackgroundImages.load(ui.activity, durable.previewBackgroundImage,
                    durable.previewBackgroundImageData) }
                assertNotNull("exported and saved photo must reload", image)
                assertEquals(imported.width, image!!.width); assertEquals(imported.height, image.height)
                ui.runOnIdle { model.update(durable) }
                awaitPreviewImage()
                screenshot("photo-background.png")
                for (theme in listOf("dark", "light")) {
                    ui.onNodeWithContentDescription("切换浅色与暗色").performClick()
                    assertEquals(theme, model.document.selectedTheme)
                    for (type in LabParameters.types) {
                        ui.onNodeWithTag("类型-$type").performScrollTo().performClick()
                        awaitPreviewImage()
                        assertEquals(type, model.document.selectedType)
                        assertEquals("photo", model.document.previewBackgroundImage)
                        assertEquals(imported.data, model.document.previewBackgroundImageData)
                    }
                }
                ui.runOnIdle { model.persistNow() }
                runBlocking { model.awaitPersisted() }
                val persistedPhoto = LabPresetStore(ui.activity).load()!!
                assertEquals(imported.data, persistedPhoto.previewBackgroundImageData)
                assertNotNull(runBlocking { LabBackgroundImages.load(ui.activity, persistedPhoto.previewBackgroundImage,
                    persistedPhoto.previewBackgroundImageData) })
                ui.runOnIdle { model.update(LabDocument.defaults()) }
            }
            check("photo_import_resizes_large_images_before_portable_export") {
                val largeFile = File(testRoot, "photo-large.png")
                val originalImage = ui.activity.assets.open("preview-backgrounds/reference.png").use { BitmapFactory.decodeStream(it) }
                assertNotNull(originalImage)
                val large = Bitmap.createScaledBitmap(originalImage!!, 2560, 1920, true)
                try {
                    largeFile.outputStream().use { assertTrue(large.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    if (large !== originalImage) large.recycle()
                    originalImage.recycle()
                }
                val imported = runBlocking { LabBackgroundImages.importPhoto(ui.activity, Uri.fromFile(largeFile)) }
                assertTrue(imported.width in 1..1280 && imported.height in 1..1280)
                assertEquals(2560f / 1920f, imported.width.toFloat() / imported.height, .015f)
                assertTrue(Base64.decode(imported.data, Base64.NO_WRAP).size <= LabDocument.MAX_IMAGE_BYTES)
                val restored = LabDocument.decode(LabDocument.defaults().copy(previewBackgroundImage = "photo",
                    previewBackgroundImageData = imported.data).encode())
                val image = runBlocking { LabBackgroundImages.load(ui.activity, "photo", restored.previewBackgroundImageData) }
                assertNotNull(image)
                assertEquals(imported.width, image!!.width); assertEquals(imported.height, image.height)
            }
            check("slider_changes_current_theme_type_only") {
                ui.runOnIdle { model.update(LabDocument.defaults()) }
                ui.onNodeWithTag("slider-shadowBlurDp").performTouchInput {
                    down(Offset(width * .12f, center.y)); moveTo(Offset(0f, center.y), delayMillis = 250); up()
                }
                ui.waitForIdle()
                assertTrue("slider value=${model.document.current()["shadowBlurDp"]}", model.document.current()["shadowBlurDp"] < 1f)
                assertEquals(5.76f, model.document.dark.getValue("circle")["shadowBlurDp"], 0f)
                assertEquals(5.76f, model.document.light.getValue("regular")["shadowBlurDp"], 0f)
            }
            check("undo_and_redo_restore_slider_gesture") {
                ui.onNodeWithTag("undo").performClick()
                ui.waitForIdle()
                assertEquals(5.76f, model.document.current()["shadowBlurDp"], .001f)
                ui.onNodeWithTag("redo").performClick()
                ui.waitForIdle()
                assertTrue(model.document.current()["shadowBlurDp"] < 1f)
            }
            check("precise_numeric_input_is_connected") {
                ui.onNodeWithTag("value-shadowBlurDp").performClick()
                ui.onNodeWithTag("precise-input").performTextReplacement("4.2")
                ui.onNodeWithText("应用", useUnmergedTree = true).performClick()
                ui.waitForIdle()
                assertEquals(4.2f, model.document.current()["shadowBlurDp"], .001f)
            }
            check("theme_switch_is_independent_and_visible") {
                ui.onNodeWithContentDescription("切换浅色与暗色").performClick()
                ui.waitForIdle()
                assertEquals("dark", model.document.selectedTheme)
                assertEquals(5.76f, model.document.current()["shadowBlurDp"], 0f)
                ui.runOnIdle {
                    val bars = WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView)
                    assertFalse(bars.isAppearanceLightStatusBars)
                    assertFalse(bars.isAppearanceLightNavigationBars)
                }
                screenshot("dark.png")
                ui.onNodeWithContentDescription("切换浅色与暗色").performClick()
                assertEquals("light", model.document.selectedTheme)
                ui.runOnIdle {
                    val bars = WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView)
                    assertTrue(bars.isAppearanceLightStatusBars)
                    assertTrue(bars.isAppearanceLightNavigationBars)
                }
            }
            check("atomic_file_save_new_store_and_named_preset_roundtrip") {
                val store = LabPresetStore(isolated)
                val saved = model.document.copy(selectedTheme = "dark", selectedType = "accent")
                store.save(saved)
                assertEquals(saved, LabPresetStore(isolated).load())
                val preset = store.savePreset("QA $testId", saved)
                assertEquals(preset, store.listPresets().single())
                assertEquals(saved, LabPresetStore(isolated).loadPreset(preset.id))
                assertTrue(store.deletePreset(preset.id))
                assertTrue(store.listPresets().isEmpty())
                val broken = File(testRoot, "material-lab/current.json")
                broken.writeText("invalid")
                assertEquals(saved, store.load()); assertNotNull(store.lastError)
                val continued = saved.withParameters(saved.current().withValue("blurDp", 8f))
                store.save(continued)
                assertEquals("invalid", broken.readText())
                assertEquals(continued, LabPresetStore(isolated).load())
            }
            check("concurrent_store_readers_cannot_delete_active_atomic_writes") {
                val baseline = model.document
                LabPresetStore(isolated).save(baseline)
                val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                val writer = Thread {
                    try { repeat(20) { index -> LabPresetStore(isolated).save(baseline.withParameters(
                        baseline.current().withValue("blurDp", index.toFloat()))) } }
                    catch (error: Throwable) { failure.set(error) }
                }
                writer.start()
                repeat(25) { assertNotNull(LabPresetStore(isolated).load()) }
                writer.join(10_000)
                assertFalse("writer must finish", writer.isAlive)
                failure.get()?.let { throw it }
                assertEquals(19f, LabPresetStore(isolated).load()!!.current()["blurDp"], 0f)
            }
            check("merged_json_contains_all_eight_profiles") {
                val document = model.document
                val file = File(output, "profile.json").apply { writeText(document.encode()) }
                val imported = LabDocument.decode(file.readText())
                assertEquals(document, imported)
                assertEquals(4, JSONObject(file.readText()).getJSONObject("light").length())
                assertEquals(4, JSONObject(file.readText()).getJSONObject("dark").length())
                assertFalse(file.readText().contains("api_key", ignoreCase = true))
            }
            check("auto_save_and_fresh_viewmodel_restore") {
                ui.runOnIdle { model.persistNow() }
                runBlocking { model.awaitPersisted() }
                assertEquals(model.document, LabPresetStore(ui.activity).load())
                lateinit var fresh: LabState
                ui.runOnIdle { fresh = LabState(ui.activity.application) }
                ui.waitUntil(6000) { !fresh.isLoading }
                assertEquals(model.document, fresh.document)
            }
            check("all_shader_parameters_compile_and_upload_at_extremes") {
                if (Build.VERSION.SDK_INT >= 33) ui.runOnIdle {
                    val single = LensShader(); val pair = GlassPairLensShader()
                    val base = LabParameters.defaults("circle", false)
                    val frame = GlassPairFrame(GlassPairShape(Offset(68f, 88f), 24f, press = .7f, touch = Offset(70f, 86f)),
                        GlassPairShape(Offset(124f, 88f), 24f), 22f)
                    for (spec in LabParameters.specs) for (edge in listOf(spec.min, spec.max)) {
                        val tuning = base.withValue(spec.key, edge).tuning()
                        single.effect(96, 96, 80, 24f, 0x20FFFFFF, 1f, false, .7f, 48f, 48f,
                            true, false, false, tuning.dimAmount, Rect(.5f, .5f, 255.5f, 255.5f), tuning)
                        pair.effect(192, 176, 80, 0x20FFFFFF, 1f, false, frame,
                            Rect(.5f, .5f, 351.5f, 335.5f), GlassMaterialProfile.Button, tuning)
                    }
                }
            }
            check("material_pixels_change_with_opacity_and_outline") {
                ui.runOnIdle { model.update(LabDocument.defaults()) }
                val before = screenshot("light.png")
                ui.runOnIdle { model.parameters(model.document.current().withValue("tintAlpha", 1f)
                    .withValue("borderWidthDp", 4f).withValue("borderOpacity", 1f).withColor("surface", "#FFEC596C")) }
                ui.waitForIdle()
                val after = screenshot("changed.png")
                var changed = 0
                val bounds = ui.onNodeWithContentDescription("圆形按钮预览").fetchSemanticsNode().boundsInRoot
                val offset = IntArray(2)
                ui.runOnIdle { ui.activity.findViewById<android.view.View>(android.R.id.content).getLocationInWindow(offset) }
                val left = (bounds.left + offset[0]).toInt().coerceIn(0, before.width - 1)
                val top = (bounds.top + offset[1]).toInt().coerceIn(0, before.height - 1)
                val right = (bounds.right + offset[0]).toInt().coerceIn(left + 1, before.width)
                val bottom = (bounds.bottom + offset[1]).toInt().coerceIn(top + 1, before.height)
                for (y in top until bottom step 2) for (x in left until right step 2)
                    if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
                assertTrue("actual button pixels must reflect material changes, changed=$changed", changed > 150)
            }
            check("pair_preview_press_drag_and_static_release") {
                ui.runOnIdle { model.update(LabDocument.defaults().copy(backgroundPreset = "stripes")) }
                ui.onNodeWithTag("toggle-pair").performClick()
                val first = ui.onAllNodesWithContentDescription("圆形按钮预览")[0]
                fun strength() = ui.onNodeWithTag("pair-host").fetchSemanticsNode().config[LabPairConnection]
                ui.waitUntil(6000) { ui.onNodeWithTag("pair-host").fetchSemanticsNode().config[LabPairReady] }
                assertEquals(0f, strength(), .001f)
                first.performTouchInput { down(center) }
                ui.waitUntil(3000) { strength() > .9f }
                screenshot("pair-pressed.png")
                first.performTouchInput { moveBy(Offset(width * .3f, 0f)) }
                ui.waitUntil(4000) { strength() < .01f }
                screenshot("pair-drag-static.png")
                first.performTouchInput { up() }
                ui.waitUntil(4000) { strength() < .01f }
                ui.waitForIdle()
                screenshot("pair-released.png")
                ui.onNodeWithTag("material-preview").assertIsDisplayed()
            }
            check("all_four_types_and_reduced_material_render") {
                ui.onNodeWithTag("toggle-pair").performClick()
                for (type in LabParameters.types) {
                    ui.runOnIdle { model.update(model.document.copy(selectedType = type)) }
                    ui.waitForIdle()
                    ui.onNodeWithTag("material-preview").assertIsDisplayed()
                }
                ui.runOnIdle { model.parameters(model.document.current().copy(glassEnabled = false, motionEnabled = false)) }
                ui.waitForIdle(); screenshot("reduced.png")
            }
            check("activity_recreation_restores_portable_document") {
                val expected = model.document
                ui.activityRule.scenario.recreate()
                ui.waitForIdle()
                assertEquals(expected, ViewModelProvider(ui.activity)[LabState::class.java].document)
            }
        } finally {
            ui.runOnIdle { original?.let { model.update(it); model.persistNow() } }
            testRoot.deleteRecursively()
            File(output, "report.json").writeText(JSONObject().put("scope", "Android app UI, runtime AGSL, AtomicFile and recreated Activity")
                .put("paidCalls", 0).put("checks", checks.length()).put("passed", (0 until checks.length()).all { checks.getJSONObject(it).getBoolean("passed") })
                .put("results", checks).toString(2))
        }
    }

    private fun screenshot(name: String): Bitmap {
        val activity = ui.activity
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            PixelCopy.request(activity.window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(done.await(6, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun awaitPreviewImage() {
        ui.waitUntil(6000) {
            ui.onAllNodesWithTag("preview-background-image").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("preview-background-image").assertIsDisplayed()
        ui.waitForIdle()
    }

    /** Compare the side strips, outside the circular button, to prove the backdrop was drawn. */
    private fun changedPreviewEdgePixels(before: Bitmap, after: Bitmap): Int {
        assertEquals(before.width, after.width); assertEquals(before.height, after.height)
        val bounds = ui.onNodeWithTag("material-preview").fetchSemanticsNode().boundsInRoot
        val offset = IntArray(2)
        ui.runOnIdle { ui.activity.findViewById<android.view.View>(android.R.id.content).getLocationInWindow(offset) }
        val left = (bounds.left + offset[0] + 16).toInt().coerceIn(0, before.width - 1)
        val top = (bounds.top + offset[1] + 16).toInt().coerceIn(0, before.height - 1)
        val right = (bounds.right + offset[0] - 16).toInt().coerceIn(left + 1, before.width)
        val bottom = (bounds.bottom + offset[1] - 24).toInt().coerceIn(top + 1, before.height)
        val strip = ((right - left) / 6).coerceAtLeast(1)
        var changed = 0
        for (y in top until bottom step 3) {
            for (x in left until left + strip step 3) if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
            for (x in right - strip until right step 3) if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
        }
        return changed
    }
}
