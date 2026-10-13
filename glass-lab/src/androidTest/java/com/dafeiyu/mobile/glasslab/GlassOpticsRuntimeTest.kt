package com.dafeiyu.mobile.glasslab

import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.media.ImageReader
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.dafeiyu.mobile.ui.components.GlassDebugTuning
import com.dafeiyu.mobile.ui.components.GlassButtonGesture
import com.dafeiyu.mobile.ui.components.GlassPairBridgeContour
import com.dafeiyu.mobile.ui.components.GlassPairFrame
import com.dafeiyu.mobile.ui.components.GlassPairLensShader
import com.dafeiyu.mobile.ui.components.GlassPairShape
import com.dafeiyu.mobile.ui.components.LensShader
import com.dafeiyu.mobile.ui.components.glassPairBlendWidth
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Offscreen real GPU material pixels, without UI text/sliders, accounts or model calls. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class GlassOpticsRuntimeTest {
    @Test fun bothShadersCompileAndUploadEveryPressModeAndCornerEndpoint() {
        val single = LensShader()
        val pair = GlassPairLensShader()
        for (mode in listOf("none", "darken", "glow", "beam", "lift")) for (endpoint in listOf(0f, 1f)) {
            val tuning = base.copy(glassThicknessDp = 40f * endpoint,
                innerRefractionStrength = 4f * endpoint, innerRefractionWidthDp = 32f * endpoint,
                innerDispersion = endpoint, pressEffect = mode, trackingLightIntensity = 3f * endpoint,
                trackingLightWidthDp = 1f + 79f * endpoint, trackingLightColor = Color(0xFF98C4FF)).sanitized()
            assertNotNull(single.effect(128, 128, 0, 64f * endpoint, 0, 1f,
                false, 1f, 64f, 64f, true, false, tuning = tuning))
            val a = GlassPairShape(Offset(40f, 64f), 24f * endpoint, press = 1f,
                touch = Offset(40f, 64f), halfSize = Offset(24f, 24f))
            val b = a.copy(center = Offset(96f, 64f), touch = Offset(96f, 64f))
            assertNotNull(pair.effect(136, 128, 0, 0, 1f, false,
                GlassPairFrame(a, b, 24f), Rect(.5f, .5f, 135.5f, 127.5f), tuning = tuning))
        }
    }

    @Test fun innerWallAndDispersionChangeOnlyActualMaterialPixels() {
        val lens = LensShader()
        fun image(tuning: GlassDebugTuning) = render(lens.effect(128, 128, 0, 64f, 0, 1f,
            false, 0f, 64f, 64f, true, false, tuning = tuning))
        val plain = image(base)
        assertTrue("GPU fixture must contain real varied source pixels", plain.toSet().size > 8)
        val folded = image(base.copy(innerRefractionStrength = 1.7f, innerRefractionWidthDp = 9f))
        val dispersed = image(base.copy(innerRefractionStrength = 1.7f,
            innerRefractionWidthDp = 9f, innerDispersion = .65f))
        assertTrue("inner compression must resample the source image", changed(plain, folded) > 40)
        assertTrue("RGB wall dispersion must use distinct source positions", changed(folded, dispersed) > 40)
    }

    @Test fun pressNoneDarkenAndColoredTrackingHaveDistinctMaterialPixels() {
        val lens = LensShader()
        fun image(mode: String) = render(lens.effect(128, 128, 0, 64f, 0, 1f,
            false, 1f, 64f, 64f, true, false, tuning = base.copy(pressEffect = mode,
                pressDarken = .5f, highlightStrength = 2f, trackingLightWidthDp = 32f,
                trackingLightIntensity = 2f, trackingLightColor = Color(0xFFFF0044))))
        val none = image("none")
        val dark = image("darken")
        val tracking = image("glow")
        assertTrue("darken must affect real transmitted material", changed(none, dark) > 200)
        assertTrue("tracking color must affect real material pixels", changed(none, tracking) > 40)
        val center = 64 * 128 + 64
        assertTrue("darken lowers source luminance", rgbSum(dark[center]) < rgbSum(none[center]))
    }

    @Test fun releaseNeckCannotGrowFromSpringReboundAndDistanceGateIsShared() {
        val contour = GlassPairBridgeContour()
        assertEquals(28f, contour.width(28f, true), 0f)
        assertEquals(22f, contour.width(22f, false), 0f)
        assertEquals(22f, contour.width(35f, false), 0f)
        assertEquals(6f, contour.width(6f, false), 0f)
        assertEquals(0f, contour.width(0f, false), 0f)
        assertEquals(0f, contour.width(28f, false), 0f)
        assertEquals(28f, contour.width(28f, true), 0f)
        val a = GlassPairShape(Offset(40f, 40f), 24f)
        val b = GlassPairShape(Offset(96f, 40f), 24f)
        assertEquals(0f, glassPairBlendWidth(a, b, 1f, 6f), 0f)
        assertTrue(glassPairBlendWidth(a, b, 1f, 24f) > 0f)
        assertEquals(glassPairBlendWidth(a, b, 1f, 24f),
            glassPairBlendWidth(a.copy(press = 1f), b, 1f, 24f), .0001f)
    }

    @Test fun squareCornersAreTouchableAndCircleCornersRemainExcluded() {
        val corner = Offset(1f, 1f)
        val square = GlassButtonGesture(48f, 48f, false, 8f, corner)
        val circle = GlassButtonGesture(48f, 48f, true, 8f, corner)
        assertTrue(square.contains(corner))
        assertTrue(square.acceptsUp(corner))
        assertFalse(circle.contains(corner))
        assertFalse(circle.acceptsUp(corner))
        assertFalse(square.contains(Offset(-1f, 24f)))
    }

    private fun render(effect: RenderEffect): IntArray {
        val reader = ImageReader.newInstance(128, 128, PixelFormat.RGBA_8888, 2)
        val renderer = HardwareRenderer()
        try {
            val node = RenderNode("GlassOpticsFixture")
            node.setPosition(0, 0, 128, 128)
            val canvas = node.beginRecording(128, 128)
            try {
                val paint = Paint()
                val colors = intArrayOf(0xFFFFFFFF.toInt(), 0xFF151515.toInt(),
                    0xFF44CC88.toInt(), 0xFFEE66BB.toInt(), 0xFF4488EE.toInt())
                for (y in 0 until 16) for (x in 0 until 16) {
                    paint.color = colors[(x + y * 2) % colors.size]
                    canvas.drawRect(x * 8f, y * 8f, (x + 1) * 8f, (y + 1) * 8f, paint)
                }
            } finally { node.endRecording() }
            node.setRenderEffect(effect)
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val start = SystemClock.uptimeMillis()
            var image = reader.acquireLatestImage()
            while (image == null && SystemClock.uptimeMillis() - start < 1500L) {
                SystemClock.sleep(10L)
                image = reader.acquireLatestImage()
            }
            return requireNotNull(image) { "GPU ImageReader frame was not delivered" }.use { frame ->
                val plane = frame.planes[0]
                val buffer = plane.buffer
                IntArray(128 * 128) { index ->
                    val offset = index / 128 * plane.rowStride + index % 128 * plane.pixelStride
                    ((buffer.get(offset + 3).toInt() and 255) shl 24) or
                        ((buffer.get(offset).toInt() and 255) shl 16) or
                        ((buffer.get(offset + 1).toInt() and 255) shl 8) or
                        (buffer.get(offset + 2).toInt() and 255)
                }
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    private fun changed(first: IntArray, second: IntArray): Int = first.indices.count { index ->
        val a = first[index]; val b = second[index]
        abs((a shr 16 and 255) - (b shr 16 and 255)) +
            abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255)) > 12
    }

    private fun rgbSum(color: Int) = (color shr 16 and 255) + (color shr 8 and 255) + (color and 255)

    private val base = GlassDebugTuning(blurDp = 0f, refractionScale = 0f, dispersion = 0f,
        reflectionStrength = 0f, rimStrength = 0f, highlightStrength = 0f, saturation = 1f,
        borderOpacity = 0f, innerRefractionStrength = 0f, innerDispersion = 0f,
        pressEffect = "none", glassThicknessDp = 12f)
}
