/*
 * Lens SDF/refraction adapted from QWEA0/Liquid-Glass-Android, commit
 * 16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d.
 * Copyright (c) 2025-2026 pandadog. MIT; see third_party/liquid-glass/LICENSE.
 */
package com.dafeiyu.mobile.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.util.Log
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.withClip
import androidx.core.graphics.createBitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object GlassRendererDiagnostics {
    var status by mutableStateOf("等待首次绘制")
        internal set
}

@Stable
class GlassBackdropState internal constructor() {
    private var sourceView: View? = null
    internal var source: View?
        get() = sourceView
        set(value) {
            if (sourceView === value) return
            sourceView = value
            changed()
        }
    private val consumers = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    internal fun register(view: View) { consumers.add(view) }
    internal fun unregister(view: View) { consumers.remove(view) }
    internal fun changed(excludeRoot: View? = null) {
        consumers.toList().filter { excludeRoot == null || it.rootView !== excludeRoot }
            .forEach { it.postInvalidateOnAnimation() }
    }
}

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }
val LocalReduceGlassTransparency = staticCompositionLocalOf { false }
val LocalReduceGlassMotion = staticCompositionLocalOf { false }

private object GlassNoRipple : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() { drawContent() }
    }
    override fun equals(other: Any?) = other === this
    override fun hashCode() = javaClass.hashCode()
}

/** Scope indication suppression to glass; interaction sources and click/focus semantics stay intact. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassInteractionProvider(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIndication provides GlassNoRipple, LocalRippleConfiguration provides null,
        content = content)
}

internal fun glassGlowCenter(touch: Offset, width: Float, height: Float): Offset = Offset(
    if (touch.x.isFinite()) touch.x.coerceIn(0f, width.coerceAtLeast(0f)) else width / 2f,
    if (touch.y.isFinite()) touch.y.coerceIn(0f, height.coerceAtLeast(0f)) else height / 2f,
)

internal fun glassFallbackSampleSize(width: Int, height: Int, useRefraction: Boolean): IntSize {
    val limit = if (useRefraction) 120 else 48
    val scale = min(1.0, limit.toDouble() / maxOf(width, height, 1))
    return IntSize(
        ceil(width.coerceAtLeast(1) * scale).toInt().coerceIn(1, limit),
        ceil(height.coerceAtLeast(1) * scale).toInt().coerceIn(1, limit),
    )
}

@Composable
fun rememberGlassBackdropState(): GlassBackdropState = remember { GlassBackdropState() }

/** Legacy independent page colour. Live controls should receive the recorded page capture. */
@Composable
fun GlassBackdrop(
    state: GlassBackdropState,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.background,
) {
    AndroidView(
        factory = { context -> BackdropTextureView(context, state).also { state.source = it } },
        modifier = modifier,
        update = { view ->
            state.source = view
            view.setBaseColor(baseColor.toArgb())
        },
    )
    DisposableEffect(state) {
        onDispose { state.source = null; state.changed() }
    }
}

/** Foreground stays in Compose. Draggable panels reserve an 18dp top grip, keeping controls independent. */
@Composable
fun GlassSurface(
    backdrop: GlassBackdropState? = LocalGlassBackdrop.current,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = .54f),
    reduceTransparency: Boolean = LocalReduceGlassTransparency.current,
    draggable: Boolean = false,
    buttonDeformation: GlassButtonDeformationState? = null,
    dragState: GlassDragState = rememberGlassDragState(),
    drawMaterial: Boolean = true,
    materialProfile: GlassMaterialProfile = LocalGlassMaterialProfile.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val providedProfile = materialProfile
    val profile = if (providedProfile == GlassMaterialProfile.Standard && buttonDeformation != null) GlassMaterialProfile.Button else providedProfile
    val buttonStyle = profile.buttonStyle
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < .5f
    val materialTint = profile.materialTint(tint, darkTheme)
    val radiusPx = with(density) { cornerRadius.toPx() }
    val gripHeight = with(density) { 18.dp.toPx() }
    val maxX = with(density) { 24.dp.toPx() }
    val maxY = with(density) { 40.dp.toPx() }
    val opaqueColor = profile.opaqueMaterial(materialTint, MaterialTheme.colorScheme.surface).toArgb()
    val reduceMotion = LocalReduceGlassMotion.current
    val scope = rememberCoroutineScope()
    // Material and foreground read the same published animation frame, just like the pair lens.
    val opticalPress = if (reduceMotion) 0f else buttonDeformation?.visualFrame?.opticalPress ?: 0f
    val dragOffset = if (draggable) dragState.offset else androidx.compose.ui.geometry.Offset.Zero
    val pressedScale by animateFloatAsState(
        targetValue = if (draggable && dragState.active && !reduceMotion) 1.015f else 1f,
        animationSpec = GlassMotion.scalar(180, reduceMotion),
        label = "glassGripScale",
    )
    Box(
        modifier
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                scaleX = pressedScale
                scaleY = pressedScale
                rotationY = if (reduceMotion) 0f else dragOffset.x / maxX * 2f
                cameraDistance = 16f * density.density
            }
            .then(if (drawMaterial && buttonStyle && buttonDeformation == null)
                Modifier.glassButtonShadow(RoundedCornerShape(cornerRadius)) else Modifier)
            .clip(RoundedCornerShape(cornerRadius))
            .glassPanelDrag(draggable, dragState, scope, gripHeight, maxX, maxY, reduceMotion),
    ) {
        if (drawMaterial) {
            val pageCapture = LocalPageGlassBackdrop.current
            if (reduceTransparency) {
                // Reduced transparency / liquid glass off: opaque theme surface plus a hairline so the
                // control stays separable from same-coloured pages. No sampling, blur or shader.
                val edgeColor = glassReducedEdgeColor(darkTheme)
                Box(Modifier.matchParentSize().drawWithCache {
                    val stroke = Stroke(1.dp.toPx())
                    val inset = stroke.width / 2f
                    val corner = CornerRadius((radiusPx - inset).coerceAtLeast(0f))
                    val solid = Color(opaqueColor)
                    onDrawBehind {
                        drawRoundRect(solid, cornerRadius = CornerRadius(radiusPx))
                        drawRoundRect(edgeColor, topLeft = Offset(inset, inset),
                            size = Size((size.width - stroke.width).coerceAtLeast(0f), (size.height - stroke.width).coerceAtLeast(0f)),
                            cornerRadius = corner, style = stroke)
                    }
                })
            } else if (pageCapture != null) {
                LivePageGlassMaterial(pageCapture, Modifier.matchParentSize(), radiusPx,
                    materialTint, opticalPress, buttonDeformation?.touchPosition, reduceMotion, profile)
            } else AndroidView(
                factory = { GlassDrawingView(it) },
                modifier = Modifier.matchParentSize(),
                update = { view ->
                    view.configure(backdrop, radiusPx, materialTint.toArgb(), opaqueColor, reduceTransparency, reduceMotion, if (buttonDeformation != null) opticalPress else if (draggable) dragState.interactionStrength else 0f,
                        buttonDeformation?.touchPosition?.x ?: -1f, buttonDeformation?.touchPosition?.y ?: -1f, profile.blurDp, buttonStyle, darkTheme, profile.dimAmount, profile.useRefraction, profile.useHighlights, profile == GlassMaterialProfile.SidebarPanel)
                },
            )
        }
        if (drawMaterial && buttonStyle && !reduceTransparency && !reduceMotion && opticalPress > 0f &&
            (Build.VERSION.SDK_INT < 33 || !profile.useRefraction)) {
            // Touch glow: brush is built once per size (drawWithCache); per frame only the translation
            // and alpha change. The parent clip keeps the glow inside the button outline.
            Box(Modifier.matchParentSize().drawWithCache {
                val glowRadius = maxOf(size.width, size.height).coerceAtLeast(1f) * .65f
                val mid = Offset(size.width / 2f, size.height / 2f)
                val glow = Brush.radialGradient(listOf(Color.White.copy(alpha = .18f), Color.Transparent),
                    center = mid, radius = glowRadius)
                onDrawBehind {
                    val center = glassGlowCenter(buttonDeformation?.touchPosition ?: Offset(size.width / 2f, size.height / 2f),
                        size.width, size.height)
                    // Gradient is anchored at the layout centre; translate shifts it onto the touch point.
                    translate(center.x - mid.x, center.y - mid.y) {
                        drawRect(glow, topLeft = Offset(mid.x - glowRadius, mid.y - glowRadius),
                            size = Size(glowRadius * 2f, glowRadius * 2f), alpha = opticalPress.coerceIn(0f, 1f))
                    }
                }
            })
        }
        GlassInteractionProvider {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                if (draggable) {
                    Box(Modifier.padding(top = 18.dp), content = content)
                    Box(
                        Modifier.align(Alignment.TopCenter).padding(top = 7.dp).size(32.dp, 3.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .22f), RoundedCornerShape(2.dp)),
                    )
                } else {
                    content()
                }
            }
        }
    }
}

private class BackdropTextureView @JvmOverloads constructor(
    context: Context,
    private val state: GlassBackdropState = GlassBackdropState(),
) : View(context) {
    private var base = android.graphics.Color.WHITE
    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun setBaseColor(color: Int) {
        if (base != color) { base = color; invalidate(); state.changed() }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        state.changed()
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(base)
    }
}
private interface BackdropRenderer {
    val refractionActive: Boolean
    fun prepare(width: Int, height: Int, radius: Float, tint: Int, density: Float, reducedMotion: Boolean, interaction: Float, touchX: Float, touchY: Float, blurDp: Float, buttonStyle: Boolean, darkTheme: Boolean, dimAmount: Float, useRefraction: Boolean, quietPanel: Boolean)
    fun draw(canvas: Canvas, source: View, dx: Float, dy: Float, width: Int, height: Int): Boolean
}

private class GlassDrawingView(context: Context) : View(context) {
    private var backdrop: GlassBackdropState? = null
    private var radius = 0f
    private var tint = android.graphics.Color.WHITE
    private var opaque = android.graphics.Color.WHITE
    private var reduced = false
    private var reducedMotion = false
    private var interaction = 0f
    private var touchX = -1f
    private var touchY = -1f
    private var blurDp = 3.5f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val bounds = RectF()
    private val ownLocation = IntArray(2)
    private val sourceLocation = IntArray(2)
    private var renderer: BackdropRenderer? = null
    private var rendererFailed = false
    private var fallbackBitmap: Bitmap? = null
    private var fallbackCanvas: Canvas? = null
    private var reportedBackdropStatus: String? = null
    init {
        isClickable = false; isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        if (Build.VERSION.SDK_INT >= 31) {
            try { renderer = HardwareGlassRenderer() } catch (failure: RuntimeException) { rendererFailed = true; Log.w("DafeiyuGlass", "Hardware material failed; using bitmap", failure) }
        }
    }
    private var buttonStyle = false
    private var darkTheme = false
    private var useRefraction = true
    private var useHighlights = false
    private var quietPanel = false
    private var dimAmount = 0f
    private var highlightFill: LinearGradient? = null
    private var highlightWidth = -1
    private var highlightHeight = -1
    private var rimShader: LinearGradient? = null
    private var rimWidth = -1
    private var rimHeight = -1
    private var rimAppearance: GlassEdgeAppearance? = null
    fun configure(state: GlassBackdropState?, radius: Float, tint: Int, opaque: Int, reduced: Boolean, motion: Boolean, interactionStrength: Float = 0f, touchX: Float = -1f, touchY: Float = -1f, blurDp: Float = 3.5f, buttonStyle: Boolean = false, darkTheme: Boolean = false, dimAmount: Float = 0f, useRefraction: Boolean = true, useHighlights: Boolean = false, quietPanel: Boolean = false) {
        if (backdrop !== state) { backdrop?.unregister(this); backdrop = state; state?.register(this) }
        if (this.buttonStyle != buttonStyle || this.darkTheme != darkTheme) highlightWidth = -1
        this.radius = radius; this.tint = tint; this.opaque = opaque; this.reduced = reduced; reducedMotion = motion; interaction = interactionStrength.coerceIn(0f, 1f); this.touchX = touchX; this.touchY = touchY; this.blurDp = blurDp; this.buttonStyle = buttonStyle; this.darkTheme = darkTheme; this.dimAmount = dimAmount.coerceIn(0f, 1f); this.useRefraction = useRefraction
        if (this.useHighlights != useHighlights) highlightWidth = -1
        this.useHighlights = useHighlights
        this.quietPanel = quietPanel
        if (reduced || state == null) releaseBitmapBackdrop()
        updateHighlightShader()
        prepareRenderer()
        invalidate()
    }
    private fun prepareRenderer() {
        if (width <= 0 || height <= 0 || rendererFailed) return
        try {
            renderer?.prepare(width, height, radius.coerceIn(0f, min(width, height) / 2f), tint,
                resources.displayMetrics.density, reducedMotion, interaction, touchX, touchY, blurDp, buttonStyle, darkTheme, dimAmount, useRefraction, quietPanel)
        } catch (failure: RuntimeException) { rendererFailed = true; Log.w("DafeiyuGlass", "Hardware material failed; using bitmap", failure) }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        highlightWidth = -1
        updateHighlightShader()
        prepareRenderer()
        postInvalidateOnAnimation()
    }
    private fun updateHighlightShader() {
        if (!useHighlights || width <= 0 || height <= 0) {
            highlightFill = null
            return
        }
        if (highlightWidth == width && highlightHeight == height) return
        val colors = intArrayOf(0x20FFFFFF, 0x0AFFFFFF, 0x00FFFFFF)
        highlightFill = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            colors, floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
        highlightWidth = width
        highlightHeight = height
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backdrop?.register(this)
        updateHighlightShader()
        postInvalidateOnAnimation()
    }
    private fun releaseBitmapBackdrop() {
        fallbackBitmap?.recycle(); fallbackBitmap = null; fallbackCanvas = null
    }
    override fun onDetachedFromWindow() {
        backdrop?.unregister(this)
        releaseBitmapBackdrop()
        rimShader = null
        highlightFill = null
        highlightWidth = -1
        super.onDetachedFromWindow()
    }
    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = radius.coerceIn(0f, min(width, height) / 2f)
        path.reset(); path.addRoundRect(bounds, r, r, Path.Direction.CW)
        var opticalRendered = false
        canvas.withClip(path) {
        val source = backdrop?.source
        val backdropStatus = when {
            reduced -> "opaque: reduced transparency"
            source == null -> "opaque: no source"
            !source.isAttachedToWindow -> "opaque: source detached"
            source.width <= 0 || source.height <= 0 -> "opaque: source not measured"
            else -> "source=${source.javaClass.simpleName}@${System.identityHashCode(source)}, " +
                "size=${source.width}x${source.height}, crossWindow=${source.rootView !== rootView}"
        }
        if (backdropStatus != reportedBackdropStatus) {
            reportedBackdropStatus = backdropStatus
            if (source != null) {
                getLocationOnScreen(ownLocation)
                source.getLocationOnScreen(sourceLocation)
            }
            Log.d("DafeiyuGlass", "native backdrop $backdropStatus, " +
                "control=${width}x${height}, offset=${ownLocation[0] - sourceLocation[0]},${ownLocation[1] - sourceLocation[1]}")
        }
        if (reduced || source == null || !source.isAttachedToWindow || source.width <= 0 || source.height <= 0) {
            releaseBitmapBackdrop()
            paint.shader = null; paint.color = opaque; canvas.drawRect(bounds, paint)
        } else {
            getLocationOnScreen(ownLocation); source.getLocationOnScreen(sourceLocation)
            val dx = (ownLocation[0] - sourceLocation[0]).toFloat()
            val dy = (ownLocation[1] - sourceLocation[1]).toFloat()
            var rendered = false
            if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated && !rendererFailed) {
                try {
                    rendered = renderer?.draw(canvas, source, dx, dy, width, height) == true
                } catch (failure: RuntimeException) { rendererFailed = true; Log.w("DafeiyuGlass", "Hardware material failed; using bitmap", failure) }
            }
            if (!rendered) drawBitmapBackdrop(canvas, source, dx, dy)
            opticalRendered = rendered && renderer?.refractionActive == true
            if (!opticalRendered) {
                paint.shader = null; paint.color = tint; canvas.drawRect(bounds, paint)
                if (dimAmount > 0f) {
                    paint.color = android.graphics.Color.argb((dimAmount * 255f).toInt(), 0, 0, 0)
                    canvas.drawRect(bounds, paint)
                }
            }
        }
        if (!reduced && useHighlights && highlightFill != null) {
            paint.style = Paint.Style.FILL
            paint.shader = highlightFill
            paint.color = android.graphics.Color.WHITE
            canvas.drawRoundRect(bounds, r, r, paint)
            paint.shader = null
        }
        }
        if (opticalRendered || reduced) return
        paint.shader = null; paint.style = Paint.Style.STROKE
        val edgeProfile = when {
            quietPanel -> GlassMaterialProfile.SidebarPanel
            buttonStyle -> GlassMaterialProfile.Button
            else -> GlassMaterialProfile.Standard
        }
        val edge = if (useHighlights) GlassEdgeAppearance(.80f, .12f, .42f, .82f) else edgeProfile.edgeAppearance(darkTheme)
        paint.strokeWidth = resources.displayMetrics.density * edge.widthDp
        if (rimShader == null || rimWidth != width || rimHeight != height || rimAppearance != edge) {
            val edgeColors = floatArrayOf(edge.front, edge.middle, edge.back).map {
                android.graphics.Color.argb((it * 255f).toInt(), 255, 255, 255)
            }.toIntArray()
            rimShader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                edgeColors, floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
            rimWidth = width; rimHeight = height; rimAppearance = edge
        }
        paint.shader = rimShader
        val half = paint.strokeWidth / 2f
        bounds.inset(half, half); canvas.drawRoundRect(bounds, max(0f, r - half), max(0f, r - half), paint)
        paint.style = Paint.Style.FILL; paint.shader = null
    }
    /** API30 fallback samples the same texture at low resolution; no optical refraction is claimed. */
    private fun drawBitmapBackdrop(canvas: Canvas, source: View, dx: Float, dy: Float) {
        // API 30 lacks RenderEffect; a much smaller resample gives the blur-only profile
        // a soft frosted fallback without adding a per-frame CPU blur pass.
        val sampleSize = glassFallbackSampleSize(width, height, useRefraction)
        val bw = sampleSize.width; val bh = sampleSize.height
        val scale = min(1f, (if (useRefraction) 120f else 48f) / max(width, height))
        var bitmap = fallbackBitmap
        if (bitmap == null || bitmap.width != bw || bitmap.height != bh) {
            releaseBitmapBackdrop()
            bitmap = createBitmap(bw, bh, Bitmap.Config.ARGB_8888); fallbackBitmap = bitmap
            fallbackCanvas = Canvas(bitmap)
        }
        bitmap.eraseColor(opaque)
        val sampled = fallbackCanvas ?: return
        val saved = sampled.save()
        sampled.scale(scale, scale); sampled.translate(-dx, -dy)
        try { source.draw(sampled); GlassRendererDiagnostics.status = "背景采样降级" } finally { sampled.restoreToCount(saved) }
        paint.shader = null; paint.color = android.graphics.Color.WHITE
        canvas.drawBitmap(bitmap, null, bounds, paint)
    }
}

@RequiresApi(31)
private class HardwareGlassRenderer : BackdropRenderer {
    override val refractionActive: Boolean get() = lens != null && !lensFailed && cachedUseRefraction
    private val node = RenderNode("DafeiyuGlassBackdrop")
    private var lens: LensShader? = null
    private var lensFailed = false
    private var effect: RenderEffect? = null
    private var blurEffect: RenderEffect? = null
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedRadius = -1f
    private var cachedTint = 0
    private var cachedDensity = -1f
    private var cachedBlurDp = -1f
    private var cachedMotion = false
    private var cachedInteraction = -1f
    private var cachedTouchX = -2f
    private var cachedTouchY = -2f
    private var cachedButtonStyle = false
    private var cachedDarkTheme = false
    private var cachedDimAmount = -1f
    private var cachedUseRefraction = true
    private var cachedQuietPanel = false
    private var margin = 0
    private var cachedSampleBounds: androidx.compose.ui.geometry.Rect? = null
    private var drawLogged = false
    override fun prepare(width: Int, height: Int,
        radius: Float, tint: Int, density: Float, reducedMotion: Boolean, interaction: Float, touchX: Float, touchY: Float, blurDp: Float, buttonStyle: Boolean, darkTheme: Boolean, dimAmount: Float, useRefraction: Boolean, quietPanel: Boolean) {
        if (width == cachedWidth && height == cachedHeight && radius == cachedRadius &&
            tint == cachedTint && density == cachedDensity && reducedMotion == cachedMotion && interaction == cachedInteraction && touchX == cachedTouchX && touchY == cachedTouchY && blurDp == cachedBlurDp && buttonStyle == cachedButtonStyle && darkTheme == cachedDarkTheme && dimAmount == cachedDimAmount && useRefraction == cachedUseRefraction && quietPanel == cachedQuietPanel) return
        val blur = blurDp * density
        if (blurEffect == null || density != cachedDensity || blurDp != cachedBlurDp) {
            blurEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
        }
        margin = glassSampleMargin(density, blurDp)
        val activeBlur = requireNotNull(blurEffect)
        effect = activeBlur
        if (Build.VERSION.SDK_INT >= 33 && !lensFailed && useRefraction) {
            try {
                if (lens == null) {
                    lens = LensShader()
                    Log.i("DafeiyuGlass", "AGSL lens compiled, sdk=${Build.VERSION.SDK_INT}")
                    GlassRendererDiagnostics.status = "AGSL 已编译"
                }
                effect = RenderEffect.createChainEffect(
                    requireNotNull(lens).effect(width, height, margin, radius, tint, density, reducedMotion,
                        interaction, touchX, touchY, buttonStyle, darkTheme, quietPanel, dimAmount), activeBlur,
                )
            } catch (failure: RuntimeException) {
                lensFailed = true
                GlassRendererDiagnostics.status = "AGSL 初始化失败，已降级模糊"
                Log.w("DafeiyuGlass", "AGSL effect failed; using blur", failure)
            }
        }
        node.setRenderEffect(effect)
        cachedSampleBounds = null
        cachedWidth = width; cachedHeight = height; cachedRadius = radius
        cachedTint = tint; cachedDensity = density; cachedBlurDp = blurDp; cachedMotion = reducedMotion; cachedInteraction = interaction; cachedTouchX = touchX; cachedTouchY = touchY; cachedButtonStyle = buttonStyle; cachedDarkTheme = darkTheme; cachedDimAmount = dimAmount; cachedUseRefraction = useRefraction; cachedQuietPanel = quietPanel
    }
    override fun draw(canvas: Canvas, source: View, dx: Float, dy: Float, width: Int, height: Int): Boolean {
        if (effect == null) return false
        val sampleBounds = glassSampleBounds(width, height, margin, margin - dx, margin - dy,
            source.width, source.height) ?: return false
        if (Build.VERSION.SDK_INT >= 33 && refractionActive && sampleBounds != cachedSampleBounds) {
            // RuntimeShader effects retain a uniform snapshot; changed safe bounds need a new effect.
            try {
                effect = RenderEffect.createChainEffect(requireNotNull(lens).effect(width, height, margin,
                    cachedRadius, cachedTint, cachedDensity, cachedMotion, cachedInteraction,
                    cachedTouchX, cachedTouchY, cachedButtonStyle, cachedDarkTheme, cachedQuietPanel, cachedDimAmount,
                    sampleBounds), requireNotNull(blurEffect))
                node.setRenderEffect(effect)
                cachedSampleBounds = sampleBounds
            } catch (failure: RuntimeException) {
                lensFailed = true
                effect = blurEffect
                node.setRenderEffect(effect)
                Log.w("DafeiyuGlass", "AGSL bounds failed; using blur", failure)
            }
        }
        val rw = width + margin * 2; val rh = height + margin * 2
        node.setPosition(-margin, -margin, width + margin, height + margin)
        val recording = node.beginRecording(rw, rh)
        try {
            recording.translate(margin - dx, margin - dy)
            // source is an independent leaf view, never a Compose ancestor or this surface.
            source.draw(recording)
        } finally { node.endRecording() }
        canvas.drawRenderNode(node)
        if (!drawLogged) { drawLogged = true; GlassRendererDiagnostics.status = if (lens != null && !lensFailed && cachedUseRefraction) "AGSL 折射 · 硬件绘制" else "硬件模糊 · 无光学折射"; Log.i("DafeiyuGlass", "hardware draw sdk=${Build.VERSION.SDK_INT}, lens=${lens != null && !lensFailed}, reducedMotion=$cachedMotion") }
        return true
    }
}

@RequiresApi(33)
internal class LensShader {
    private val shader = RuntimeShader(SOURCE)
    private fun sampleBounds(bounds: androidx.compose.ui.geometry.Rect) {
        shader.setFloatUniform("sampleLo", bounds.left, bounds.top)
        shader.setFloatUniform("sampleHi", bounds.right, bounds.bottom)
    }
    fun effect(width: Int, height: Int, margin: Int, radius: Float, tint: Int, density: Float, reducedMotion: Boolean, interaction: Float, touchX: Float, touchY: Float, buttonStyle: Boolean, darkTheme: Boolean, quietPanel: Boolean = false, dimAmount: Float = 0f,
        sampleBounds: androidx.compose.ui.geometry.Rect = requireNotNull(glassSampleBounds(width, height, margin,
            margin.toFloat(), margin.toFloat(), width, height))): RenderEffect {
        val geometry = glassLensGeometry(width, height, density)
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("margin", margin.toFloat())
        shader.setFloatUniform("radius", radius.coerceIn(0f, min(width, height) / 2f))
        shader.setFloatUniform("bevel", geometry.bevel)
        shader.setFloatUniform("falloff", 2f)
        shader.setFloatUniform("hairWidth", geometry.hairWidth)
        shader.setFloatUniform("refractPx", if (reducedMotion) 0f else geometry.refraction)
        shader.setFloatUniform("interaction", if (reducedMotion) 0f else interaction.coerceIn(0f, 1f))
        shader.setFloatUniform("touchPos", if (touchX < 0f) width / 2f else touchX, if (touchY < 0f) height / 2f else touchY)
        shader.setFloatUniform("dispersion", if (reducedMotion) 0f else .10f)
        shader.setFloatUniform("specScale", glassSpecScale(buttonStyle, darkTheme, quietPanel))
        shader.setFloatUniform("rimFloor", glassRimFloor(buttonStyle, darkTheme, quietPanel))
        shader.setFloatUniform("dimAmount", dimAmount.coerceIn(0f, 1f))
        shader.setFloatUniform("satFactor", glassVibrancy(quietPanel))
        shader.setFloatUniform("accentScatter", glassAccentScatter(darkTheme))
        shader.setFloatUniform("tint", android.graphics.Color.red(tint) / 255f,
            android.graphics.Color.green(tint) / 255f, android.graphics.Color.blue(tint) / 255f,
            android.graphics.Color.alpha(tint) / 255f)
        this.sampleBounds(sampleBounds)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
    companion object {
        // Small, adapted portion of upstream GlassLensRenderer.LENS_AGSL: rounded SDF,
        // numeric normal, inward inverse-power refraction, RGB dispersion and directional specular.
        private const val SOURCE = """
            uniform shader content;
            uniform float2 size;
            uniform float margin;
            uniform float radius;
            uniform float bevel;
            uniform float falloff;
            uniform float hairWidth;
            uniform float refractPx;
            uniform float dispersion;
            uniform float interaction;
            uniform float specScale;
            uniform float rimFloor;
            uniform float dimAmount;
            uniform float satFactor;
            uniform float accentScatter;
            uniform float2 touchPos;
            uniform float2 sampleLo;
            uniform float2 sampleHi;
            uniform float4 tint;
            float sdf(float2 p) {
                float2 q = abs(p - size * 0.5) - size * 0.5 + radius;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
            }

            float4 readContent(float2 p) {
                float4 tap = float4(content.eval(clamp(p, sampleLo, sampleHi)));
                float3 rgb = tap.a > 0.00001 ? tap.rgb / tap.a : float3(0.0);
                return float4(clamp(rgb, float3(0.0), float3(1.0)), clamp(tap.a, 0.0, 1.0));
            }

            float3 transmitColour(float3 col, float4 shade) {
                float chroma = max(shade.r, max(shade.g, shade.b)) - min(shade.r, min(shade.g, shade.b));
                if (shade.a > 0.24 && chroma > 0.08) {
                    float luminance = dot(col, float3(0.2126, 0.7152, 0.0722));
                    float3 absorbed = col * mix(float3(1.0), shade.rgb, 0.85);
                    float3 scattered = shade.rgb * (accentScatter * (1.0 - luminance));
                    return mix(col, clamp(absorbed + scattered, float3(0.0), float3(1.0)), shade.a);
                }
                return mix(col, shade.rgb, shade.a);
            }

            half4 main(float2 coord) {
                float2 p = coord - float2(margin);
                if (p.x < 0.0 || p.y < 0.0 || p.x > size.x || p.y > size.y) return half4(0.0);
                float d = sdf(p);
                float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
                if (cov < 0.004) return half4(0.0);
                float2 gradient = float2(sdf(p + float2(1.0, 0.0)) - sdf(p - float2(1.0, 0.0)),
                    sdf(p + float2(0.0, 1.0)) - sdf(p - float2(0.0, 1.0))) * 0.5;
                float gradientLength = length(gradient);
                float2 n = gradient / max(gradientLength, 0.0001);
                float normalStrength = smoothstep(0.0001, 0.05, gradientLength);
                float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
                // Upstream inverse-power profile; falloff is a live uniform (set in effect()).
                float gB = pow(5.0, -falloff);
                float slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / max(1.0 - gB, 0.0001);
                float2 touchDelta = p - touchPos;
                float dist = length(touchDelta);
                float sigma = max(min(size.x, size.y) * 0.5, 1.0);
                float bounded = 1.0 - smoothstep(sigma * 0.8, sigma * 1.4, dist);
                float localPress = interaction * cov * bounded *
                    exp(-dot(touchDelta, touchDelta) / max(sigma * sigma * 0.30, 0.000001));
                float refr = refractPx * (1.0 + 0.6 * localPress);
                float2 offset = -n * slope * refr * normalStrength;
                if (interaction > 0.001) {
                    offset -= touchDelta / max(dist, 1.0) * (localPress * refr * 0.5);
                }
                // Match the shared surface's total-displacement budget, including touch and colour taps.
                float maxOffset = min(refractPx, max(bevel, 1.0) * 0.50) / 1.10;
                offset *= min(1.0, maxOffset / max(length(offset), 0.0001));
                float4 red = readContent(coord + offset * (1.0 - dispersion * slope));
                float4 green = readContent(coord + offset);
                float4 blue = readContent(coord + offset * (1.0 + dispersion * slope));
                float transmission = min(red.a, min(green.a, blue.a));
                float3 col = float3(red.r, green.g, blue.b);
                // QWEA0 vibrancy curve (GlassLensRenderer.kt:213-224 @16afe4d): boost low-chroma
                // pixels, spare saturated and near-white ones, then clamp, so light pages cannot clip.
                float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
                float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
                float room = 1.0 - smoothstep(0.2, 0.85, satNow);
                float hl = 1.0 - smoothstep(0.75, 0.98, lum);
                float amount = 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl);
                col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));
                // Readability belongs to transmission, before the narrow surface reflection.
                col = transmitColour(col, tint) * (1.0 - dimAmount);
                float materialAlpha = transmission + tint.a * (1.0 - transmission);
                float3 rgb = col * transmission + tint.rgb * tint.a * (1.0 - transmission);
                // The same normal gives two directional reflection lobes; the centre is transmitted page.
                float facing = dot(n, normalize(float2(-0.6, -0.8)));
                float lobeF = pow(max(facing, 0.0), 4.5);
                float lobeB = pow(max(-facing, 0.0), 4.5);
                float hair = clamp(1.0 - abs(d + hairWidth * 0.5) / hairWidth, 0.0, 1.0);
                float inside = 1.0 - smoothstep(-0.75, 0.0, d);
                float glow = clamp((-d - hairWidth) / hairWidth, 0.0, 1.0) *
                    pow(clamp(1.0 + d / max(bevel * 0.5, 1.0), 0.0, 1.0), 1.5);
                float spec = inside * normalStrength *
                    (hair * (rimFloor + 0.70 * (lobeF + lobeB) * specScale) +
                        glow * 0.10 * lobeF * specScale) * (1.0 - localPress * 0.20);
                float front = inside * localPress * 0.10;
                float reflection = clamp(spec + front, 0.0, 0.90);
                rgb = rgb * (1.0 - reflection) + float3(reflection);
                materialAlpha += reflection * (1.0 - materialAlpha);
                rgb = clamp(rgb, float3(0.0), float3(materialAlpha)) * cov;
                return half4(half3(rgb), half(clamp(materialAlpha * cov, 0.0, 1.0)));
            }
        """
    }
}







