package com.dafeiyu.mobile.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView

/** QWEA0 Regular/Clear readability, with tint kept stable for the app's theme-coloured foreground. */
enum class GlassMaterialProfile(
    val blurDp: Float,
    val lightTintAlpha: Float,
    val darkTintAlpha: Float,
    val dimAmount: Float = 0f,
    val useRefraction: Boolean = true,
    val useHighlights: Boolean = false,
) {
    Standard(3.5f, .16f, .16f),
    ReadablePanel(8f, .28f, .28f),
    Button(2f, .12f, .10f),
    AccentButton(2f, .78f, .74f),
    FloatingMenu(3.5f, .16f, .16f),
    SidebarPanel(3f, .12f, .14f),
    MediaButton(2f, .14f, .14f, dimAmount = .16f);

    internal val buttonStyle: Boolean
        get() = this == Button || this == AccentButton || this == MediaButton
}

/**
 * Keep Clear's media dim separate from tint so it also darkens the transmitted page.
 * Dark buttons keep the same thin white tint (no extra alpha): a thicker white body turns into a
 * grey slab and lowers contrast for light foreground text. Dark visibility comes from the brighter
 * rim instead (edgeAppearance / glassRimFloor / glassSpecScale), as listed in 清单 "暗玻璃边缘高光提亮".
 */
internal fun GlassMaterialProfile.materialTint(tint: Color, darkTheme: Boolean): Color {
    // Existing accent callers pass their theme colour through Button. Preserve that intent; the
    // shader models a coloured medium before surface reflection instead of replacing it with white.
    val chroma = maxOf(tint.red, tint.green, tint.blue) - minOf(tint.red, tint.green, tint.blue)
    if (this == GlassMaterialProfile.Button && tint.alpha > .24f && chroma > .08f) {
        return tint.copy(alpha = minOf(tint.alpha, if (darkTheme) .74f else .78f))
    }
    val alpha = minOf(tint.alpha, if (darkTheme) darkTintAlpha else lightTintAlpha)
    return when (this) {
        GlassMaterialProfile.SidebarPanel ->
            (if (darkTheme) Color(0xFF1C1C1E) else Color.White).copy(alpha = alpha)
        GlassMaterialProfile.Button -> Color.White.copy(alpha = alpha)
        else -> tint.copy(alpha = alpha)
    }
}

/** Preserve an accent control's foreground contrast when transmission is unavailable or disabled. */
internal fun GlassMaterialProfile.opaqueMaterial(tint: Color, surface: Color): Color {
    val chroma = maxOf(tint.red, tint.green, tint.blue) - minOf(tint.red, tint.green, tint.blue)
    return if (this == GlassMaterialProfile.AccentButton ||
        (buttonStyle && tint.alpha > .24f && chroma > .08f)) tint.copy(alpha = 1f) else surface
}

/** Directional reflection strength (AGSL specScale). Dark surfaces get a brighter rim, not more tint. */
internal fun glassSpecScale(buttonStyle: Boolean, darkTheme: Boolean, quietPanel: Boolean): Float = when {
    quietPanel -> if (darkTheme) .60f else .45f
    buttonStyle -> if (darkTheme) 1.05f else .80f
    else -> if (darkTheme) .80f else .60f
}

/** Direction-independent hairline floor (AGSL rimFloor); only dark glass needs it to stay visible. */
internal fun glassRimFloor(buttonStyle: Boolean, darkTheme: Boolean, quietPanel: Boolean): Float = when {
    !darkTheme -> 0f
    quietPanel -> .04f
    buttonStyle -> .10f
    else -> .06f
}

/**
 * AGSL satFactor for the QWEA0 protected vibrancy curve. Upstream default is 1.40; we stay lower
 * because the page already carries theme colour, and keep quiet sidebars closer to neutral.
 */
internal fun glassVibrancy(quietPanel: Boolean): Float = if (quietPanel) 1.10f else 1.25f

/** Dark accents scatter more of their own hue; neutral tint and transmission alpha stay unchanged. */
internal fun glassAccentScatter(darkTheme: Boolean): Float = if (darkTheme) .64f else .38f

/** Hairline used when transparency is reduced / liquid glass is off: keeps opaque controls separable. */
internal fun glassReducedEdgeColor(darkTheme: Boolean): Color =
    if (darkTheme) Color.White.copy(alpha = .18f) else Color.Black.copy(alpha = .10f)

val LocalGlassMaterialProfile = staticCompositionLocalOf { GlassMaterialProfile.Standard }
val LocalFloatingGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }

internal data class GlassEdgeAppearance(val front: Float, val middle: Float, val back: Float, val widthDp: Float)

/** Identical readable rims for page capture, native blur and bitmap fallback. */
internal fun GlassMaterialProfile.edgeAppearance(darkTheme: Boolean): GlassEdgeAppearance = when {
    this == GlassMaterialProfile.SidebarPanel ->
        if (darkTheme) GlassEdgeAppearance(.24f, .04f, .12f, .65f) else GlassEdgeAppearance(.14f, 0f, .06f, .65f)
    useHighlights -> GlassEdgeAppearance(.80f, .12f, .42f, .82f)
    buttonStyle && darkTheme -> GlassEdgeAppearance(.72f, .16f, .52f, .90f)
    buttonStyle -> GlassEdgeAppearance(.44f, .08f, .30f, .86f)
    darkTheme -> GlassEdgeAppearance(.62f, .12f, .46f, 1.15f)
    else -> GlassEdgeAppearance(.50f, .08f, .38f, 1.15f)
}

@Composable
fun FloatingBackdropSource(capture: PageBackdropCapture, state: GlassBackdropState,
    modifier: Modifier = Modifier, modalProgress: () -> Float = { 0f }) {
    val currentModalProgress by rememberUpdatedState(modalProgress)
    AndroidView(factory = { ctx -> PagePixelsView(ctx, state).also { state.source = it } },
        modifier = modifier.graphicsLayer { alpha = 0f }, update = { leaf ->
            leaf.capture = capture
            leaf.modalProgress = modalProgress
            state.source = leaf
            leaf.postInvalidateOnAnimation()
        })
    LaunchedEffect(state) {
        snapshotFlow { currentModalProgress().coerceIn(0f, 1f) }.collect {
            state.changed()
        }
    }
    DisposableEffect(state) { onDispose { state.source = null; state.changed() } }
}
private class PagePixelsView(
    context: Context,
    private val backdropState: GlassBackdropState,
) : View(context) {
    private var captureValue: PageBackdropCapture? = null
    var capture: PageBackdropCapture?
        get() = captureValue
        set(value) {
            if (captureValue === value) return
            captureValue?.removeRevisionListener(revisionListener)
            captureValue = value
            if (isAttachedToWindow) value?.addRevisionListener(revisionListener)
            postInvalidateOnAnimation()
            backdropState.changed()
        }
    var modalProgress: () -> Float = { 0f }
    private val scope = CanvasDrawScope()
    private val dimPaint = Paint()
    private val node = if (Build.VERSION.SDK_INT >= 31) RenderNode("FloatingModalBackdrop") else null
    private val blurSteps = if (Build.VERSION.SDK_INT >= 31) (0..4).map { index ->
        if (index == 0) null else RenderEffect.createBlurEffect(2.8f * resources.displayMetrics.density * index / 4f,
            2.8f * resources.displayMetrics.density * index / 4f, Shader.TileMode.CLAMP)
    } else emptyList()
    private val revisionListener: () -> Unit = {
        // Popup consumers live in a separate root. Invalidating the source root on every
        // capture revision would cause a capture -> invalidate -> capture feedback loop.
        backdropState.changed(excludeRoot = rootView)
    }
    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        captureValue?.addRevisionListener(revisionListener)
        postInvalidateOnAnimation()
        backdropState.changed()
    }
    override fun onDetachedFromWindow() {
        captureValue?.removeRevisionListener(revisionListener)
        super.onDetachedFromWindow()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        backdropState.changed()
        postInvalidateOnAnimation()
    }
    override fun onDraw(canvas: Canvas) {
        val source = capture ?: return
        if (source.layer.size.width <= 0 || source.layer.size.height <= 0) return
        val strength = modalProgress().coerceIn(0f, 1f)
        val renderNode = node
        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated && renderNode != null) {
            renderNode.setPosition(0, 0, width, height)
            val recording = renderNode.beginRecording(width, height)
            try {
                scope.draw(Density(resources.displayMetrics.density), LayoutDirection.Ltr,
                    androidx.compose.ui.graphics.Canvas(recording), Size(width.toFloat(), height.toFloat())) { drawLayer(source.layer) }
            } finally { renderNode.endRecording() }
            renderNode.setRenderEffect(blurSteps[(strength * 4f).toInt().coerceIn(0, 4)])
            canvas.drawRenderNode(renderNode)
        } else {
            scope.draw(Density(resources.displayMetrics.density), LayoutDirection.Ltr,
                androidx.compose.ui.graphics.Canvas(canvas), Size(width.toFloat(), height.toFloat())) { drawLayer(source.layer) }
        }
        dimPaint.color = android.graphics.Color.argb((strength * .12f * 255).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
    }
}
