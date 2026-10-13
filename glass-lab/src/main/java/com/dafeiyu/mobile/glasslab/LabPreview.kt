package com.dafeiyu.mobile.glasslab

import android.os.Build
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.dafeiyu.mobile.ui.components.*
import kotlin.math.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal fun LabParameters.color(key: String): Color = Color(android.graphics.Color.parseColor(colors.getValue(key)))

/** This explicit mapping is also the exported profile's production adapter. */
internal fun LabParameters.tuning() = GlassDebugTuning(
    blurDp = this["blurDp"], refractionScale = this["refractionScale"],
    refractionFalloff = this["refractionFalloff"], dispersion = this["dispersion"], bevelScale = this["bevelScale"],
    reflectionStrength = this["reflectionStrength"], lightAngleDegrees = this["lightAngleDegrees"],
    highlightStrength = this["highlightStrength"], rimStrength = this["rimStrength"],
    borderWidthDp = this["borderWidthDp"], borderOpacity = this["borderOpacity"],
    saturation = this["saturation"], dimAmount = this["dimAmount"], surfaceAlpha = this["surfaceAlpha"],
    pressScale = this["pressScale"], dragStretch = this["dragStretch"], dragCompression = this["dragCompression"],
    dragDistanceDp = this["dragDistanceDp"], dragResistance = this["dragResistance"],
    pressMillis = this["pressMillis"], releaseMillis = this["releaseMillis"],
    shadowBlurDp = this["shadowBlurDp"], shadowSpreadDp = this["shadowSpreadDp"],
    shadowOffsetXDp = this["shadowOffsetXDp"], shadowOffsetYDp = this["shadowOffsetYDp"], shadowOpacity = this["shadowOpacity"],
    shadowPressScale = this["shadowPressScale"], springDamping = this["springDamping"], springStiffness = this["springStiffness"],
    highlightColor = color("highlight"), borderColor = color("border"), shadowColor = color("shadow"),
    glassThicknessDp = this["glassThicknessDp"], innerRefractionStrength = this["innerRefractionStrength"],
    innerRefractionWidthDp = this["innerRefractionWidthDp"], innerDispersion = this["innerDispersion"],
    trackingLightWidthDp = this["trackingLightWidthDp"], trackingLightIntensity = this["trackingLightIntensity"],
    pressDarken = this["pressDarken"], bridgeReleaseMillis = this["bridgeReleaseMillis"],
    bridgeReachDp = this["bridgeReachDp"], pressEffect = pressEffect, trackingLightColor = color("trackingLight"),
).sanitized()

private sealed interface LabPreviewImageState {
    data object None : LabPreviewImageState
    data object Loading : LabPreviewImageState
    data class Ready(val bitmap: ImageBitmap) : LabPreviewImageState
    data object Failed : LabPreviewImageState
}

@Composable
internal fun LabPreview(
    document: LabDocument,
    kind: String,
    playId: Int,
    focused: Boolean,
    hovered: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = document.current()
    val dark = document.selectedTheme == "dark"
    val capture = rememberPageBackdropCapture()
    var taps by remember { mutableIntStateOf(0) }
    val context = LocalContext.current.applicationContext
    val imageSource = document.previewBackgroundImage
    val imageData = document.previewBackgroundImageData
    // A new selection owns a new loading state, so its first frame cannot display the old image.
    // Sliders, theme changes and draw frames retain this state and never trigger another decode.
    val imageState by key(context, imageSource, imageData) {
        produceState<LabPreviewImageState>(
            initialValue = if (imageSource == "none") LabPreviewImageState.None else LabPreviewImageState.Loading,
            context, imageSource, imageData,
        ) {
            if (imageSource != "none") {
                value = try {
                    LabBackgroundImages.load(context, imageSource, imageData)?.let { LabPreviewImageState.Ready(it) }
                        ?: LabPreviewImageState.Failed
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    LabPreviewImageState.Failed
                }
            }
        }
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val moving = imageSource == "none" && document.backgroundPreset == "aurora" &&
        document.animateBackground && p.motionEnabled && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val phase: State<Float> = if (moving) {
        val animation = rememberInfiniteTransition(label = "labBackground")
        animation.animateFloat(0f, 1f,
                infiniteRepeatable(GlassMotion.scalar(6000), RepeatMode.Reverse), label = "labBackgroundPhase")
    } else remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density
    val sceneEffect = remember(p["backgroundBlurDp"], density) {
        if (Build.VERSION.SDK_INT >= 31 && p["backgroundBlurDp"] > 0f)
            AndroidRenderEffect.createBlurEffect(p["backgroundBlurDp"] * density, p["backgroundBlurDp"] * density,
                Shader.TileMode.CLAMP).asComposeRenderEffect() else null
    }
    Box(modifier.clip(RoundedCornerShape(26.dp))) {
        // Record the composited, opaque scene: translucent patterns must not be blended twice
        // when the sampled glass is subsequently drawn over this same scene.
        Box(Modifier.matchParentSize().graphicsLayer {}.capturePageBackdrop(capture)
            .background(if (dark) Color(0xFF161820) else Color(0xFFF1F2F6))) {
            val backdropModifier = Modifier.matchParentSize()
                .graphicsLayer { alpha = p["backgroundAlpha"]; renderEffect = sceneEffect }
            when (val loaded = imageState) {
                is LabPreviewImageState.Ready -> Image(loaded.bitmap,
                    contentDescription = if (imageSource == "reference") "参考图预览底图" else "相册图片预览底图",
                    modifier = backdropModifier.testTag("preview-background-image"), contentScale = ContentScale.Crop)
                else -> LabBackground(p, document.backgroundPreset, document.previewBackgroundColors,
                    { phase.value }, backdropModifier)
            }
        }
        CompositionLocalProvider(LocalPageGlassBackdrop provides capture) {
            when {
                kind == "pair" -> LabPair(p, dark, playId, focused, hovered, Modifier.fillMaxSize(), { taps++ })
                document.compare -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("默认", color = p.color("text"), fontSize = 12.sp)
                        Spacer(Modifier.height(20.dp))
                        LabControl(LabParameters.defaults(document.selectedType, dark), document.selectedType, playId, false, false,
                            onClick = { taps++ })
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("自定义", color = p.color("text"), fontSize = 12.sp)
                        Spacer(Modifier.height(20.dp))
                        LabControl(p, document.selectedType, playId, focused, hovered, onClick = { taps++ })
                    }
                }
                kind == "page" -> {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Dafeiyu-GO", color = p.color("text"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            LabControl(p.withValue("widthDp", 48f).withValue("heightDp", 48f), "circle", playId,
                                focused, hovered, onClick = { taps++ })
                        }
                        Text("在真实背景上观察透明度、折射和文字对比。", color = p.color("text").copy(alpha = .6f), fontSize = 14.sp)
                        LabControl(p, if (document.selectedType == "circle") "regular" else document.selectedType,
                            playId, focused, hovered, onClick = { taps++ })
                    }
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LabControl(p, document.selectedType, playId, focused, hovered, onClick = { taps++ })
                }
            }
        }
        val interactionHint = if (taps == 0) "按住、拖动，再松手" else "点击 $taps 次 · 长按可拖动"
        val imageLabel = if (imageSource == "reference") "参考图" else "相册图片"
        val imageNotice = when (imageState) {
            LabPreviewImageState.None -> null
            LabPreviewImageState.Loading -> "正在加载$imageLabel · 暂用原底图"
            LabPreviewImageState.Failed -> "$imageLabel 加载失败 · 请重新选择，暂用原底图"
            is LabPreviewImageState.Ready -> "$imageLabel · 调整背景透明度与模糊可观察材质"
        }
        val hintModifier = Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
        // This readable status sits outside the capture, so glass samples only the real backdrop.
        Text(if (imageNotice == null) interactionHint else "$imageNotice\n$interactionHint",
            if (imageNotice == null) hintModifier else hintModifier.clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = .6f)).padding(horizontal = 10.dp, vertical = 5.dp)
                .testTag("preview-image-status"),
            color = if (imageNotice == null) p.color("text").copy(alpha = .6f) else Color.White,
            fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LabBackground(p: LabParameters, preset: String, previewColors: List<String>, phase: () -> Float, modifier: Modifier) {
    val colors = previewColors.map { Color(android.graphics.Color.parseColor(it)) }
    val bg = colors.firstOrNull() ?: p.color("background")
    val accent = colors.lastOrNull() ?: p.color("backgroundAccent")
    val dark = bg.luminance() < .4f
    val scale = p["backgroundPatternScale"]
    val textColor = if (dark) Color.White.copy(alpha = .45f) else Color.Black.copy(alpha = .24f)
    Box(modifier.drawWithCache {
        val linear = Brush.linearGradient(if (colors.size > 1) colors else listOf(bg, accent, bg), Offset.Zero, Offset(size.width, size.height))
        val radial = Brush.radialGradient(listOf(accent, Color.Transparent),
            Offset(size.width * .32f, size.height * .38f), size.maxDimension * .7f)
        val second = Brush.radialGradient(listOf(Color(0xFFDA88E8).copy(alpha = .55f), Color.Transparent),
            Offset(size.width * .82f, size.height * .75f), size.maxDimension * .52f)
        val tile = (24.dp.toPx() * scale).coerceAtLeast(1f)
        onDrawBehind {
            drawRect(bg)
            when (preset) {
                "solid" -> Unit
                "checker" -> {
                    val columns = ceil(size.width / tile).toInt()
                    val rows = ceil(size.height / tile).toInt()
                    for (y in 0..rows) for (x in 0..columns) if ((x + y) % 2 == 0)
                        drawRect(accent, Offset(x * tile, y * tile), Size(tile, tile))
                }
                "stripes" -> for (x in 0..ceil(size.width / tile).toInt()) if (x % 2 == 0)
                    drawRect(accent, Offset(x * tile, 0f), Size(tile, size.height))
                "aurora" -> {
                    val shift = phase() * size.width * .25f
                    translate(shift, -shift * .35f) { drawRect(radial, topLeft = Offset(-shift, 0f), size = size * 1.5f) }
                    translate(-shift, shift * .25f) { drawRect(second, size = size * 1.4f) }
                }
                else -> drawRect(linear)
            }
        }
    }) {
        if (preset == "text") Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            repeat(5) { index -> Text("Dafeiyu GO   ${index + 1}   Aa  0123456789", color = textColor,
                fontSize = (16f * scale).sp, maxLines = 1, overflow = TextOverflow.Clip) }
        }
    }
}

@Composable
internal fun LabControl(
    p: LabParameters,
    type: String,
    playId: Int,
    focused: Boolean,
    hovered: Boolean,
    modifier: Modifier = Modifier,
    state: GlassButtonDeformationState = rememberGlassButtonDeformationState(),
    drawMaterial: Boolean = true,
    onClick: () -> Unit,
) {
    val circular = p.shape == "circle" || (type == "circle" && p.shape == "rounded")
    val w = p["widthDp"]
    val h = if (circular) w else p["heightDp"]
    val radius = min(w, h) * .5f * p["roundness"].coerceIn(0f, 1f)
    // A zero-roundness Lab specimen is a real square, including its visible corners.
    // Imported application themes still retain each application's original gesture contract.
    val circularTouch = circular && p["roundness"] >= .9999f
    val tuning = remember(p) { p.tuning() }
    val density = LocalDensity.current.density
    LaunchedEffect(playId, p.motionEnabled, p.disabled) {
        if (playId > 0 && !p.disabled) {
            try {
                state.touchPosition = Offset(w * .5f * density, h * .5f * density)
                state.pressed = true
                delay(GlassMotion.MorphMillis.toLong())
                state.dragging = true
                state.delta = Offset(28f * density, 8f * density)
                delay(GlassMotion.MorphMillis.toLong())
            } finally { state.reset() }
        }
    }
    DisposableEffect(state) { onDispose { state.reset() } }
    val profile = when (type) {
        "accent" -> GlassMaterialProfile.AccentButton
        "panel" -> GlassMaterialProfile.ReadablePanel
        else -> GlassMaterialProfile.Button
    }
    CompositionLocalProvider(LocalGlassDebugTuning provides tuning, LocalReduceGlassTransparency provides !p.glassEnabled,
        LocalReduceGlassMotion provides !p.motionEnabled, LocalGlassButtonSpring provides p.springEnabled) {
        GlassSurface(modifier = modifier.requiredSize(w.dp, h.dp).graphicsLayer {
            rotationZ = p["rotationDegrees"]
            alpha = if (p.disabled) p["disabledAlpha"] else 1f
        }.applyButtonDeformation(enabled = !p.disabled, state = state, circularHit = circularTouch,
            drawShadow = drawMaterial, shadowShape = RoundedCornerShape(radius.dp))
            .semantics { contentDescription = "${LabTypeNames.getValue(type)}预览" },
            cornerRadius = radius.dp, buttonDeformation = state, materialProfile = profile,
            tint = p.color("surface").let { it.copy(alpha = it.alpha * p["tintAlpha"]) }, drawMaterial = drawMaterial) {
            Box(Modifier.matchParentSize().labOverlays(p, radius, state, focused, hovered)
                .clickable(enabled = !p.disabled, onClick = onClick), contentAlignment = Alignment.Center) {
                LabContent(p, type, circular)
            }
        }
    }
}

@Composable
private fun LabContent(p: LabParameters, type: String, circular: Boolean) {
    val contentModifier = Modifier.offset(p["contentOffsetXDp"].dp, p["contentOffsetYDp"].dp).padding(p["paddingDp"].dp)
    if (type == "panel") Column(contentModifier, verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        LabGlyph(p.icon, p, Modifier.size(p["iconSizeDp"].dp))
        LabLabel("可读的玻璃面板", p)
        LabLabel("文字与图标保持独立透明度", p, .8f)
    } else Row(contentModifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(p["iconTextGapDp"].dp)) {
        LabGlyph(p.icon, p, Modifier.size(p["iconSizeDp"].dp))
        if (!circular) LabLabel(if (type == "accent") "发送" else "选择模型", p)
    }
}

@Composable
private fun LabLabel(text: String, p: LabParameters, scale: Float = 1f) {
    val style = TextStyle(fontSize = (p["textSizeSp"] * scale).sp,
        fontWeight = FontWeight(p["textWeight"].toInt().coerceIn(100, 900)), letterSpacing = p["textLetterSpacingSp"].sp)
    Box {
        if (p["contentOutlineWidthDp"] > 0f && p["contentOutlineAlpha"] > 0f)
            Text(text, color = p.color("contentOutline").let { it.copy(alpha = it.alpha * p["contentOutlineAlpha"]) },
                style = style.copy(drawStyle = Stroke(p["contentOutlineWidthDp"] * LocalDensity.current.density)),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (p["contentHighlightAlpha"] > 0f)
            Text(text, Modifier.offset((-1).dp, (-1).dp), color = p.color("contentHighlight").let { it.copy(alpha = it.alpha * p["contentHighlightAlpha"]) },
                style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(text, color = p.color("text").let { it.copy(alpha = it.alpha * p["textAlpha"]) },
            style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

internal val LabTypeNames = mapOf("accent" to "主题按钮", "circle" to "圆形按钮", "regular" to "普通按钮", "panel" to "玻璃面板")
internal val LabIconResources = mapOf("add" to R.drawable.sf_add, "send" to R.drawable.sf_send,
    "settings" to R.drawable.sf_settings, "folder" to R.drawable.sf_folder, "agent" to R.drawable.sf_groupchat,
    "archive" to R.drawable.sf_archive, "trash" to R.drawable.sf_trash, "shield" to R.drawable.sf_lock)

@Composable
internal fun LabGlyph(icon: String, p: LabParameters, modifier: Modifier = Modifier) {
    val painter = painterResource(LabIconResources.getValue(icon))
    val tint = p.color("icon").let { it.copy(alpha = it.alpha * p["iconAlpha"]) }
    val outline = p.color("contentOutline").let { it.copy(alpha = it.alpha * p["contentOutlineAlpha"]) }
    val shine = p.color("contentHighlight").let { it.copy(alpha = it.alpha * p["contentHighlightAlpha"]) }
    val blend = when (p.iconBlend) {
        "multiply" -> BlendMode.Multiply; "screen" -> BlendMode.Screen; "overlay" -> BlendMode.Overlay
        "difference" -> BlendMode.Difference; "plus" -> BlendMode.Plus; "srcIn" -> BlendMode.SrcIn
        else -> BlendMode.SrcOver
    }
    Box(modifier.drawWithCache {
        val paint = Paint().apply { blendMode = blend }
        val rect = Rect(Offset.Zero, size)
        val iconFilter = ColorFilter.tint(tint)
        val outlineFilter = ColorFilter.tint(outline)
        val shineFilter = ColorFilter.tint(shine)
        val width = p["contentOutlineWidthDp"].dp.toPx()
        val dirs = (0 until 8).map { angle -> Offset(cos(angle * PI / 4).toFloat() * width, sin(angle * PI / 4).toFloat() * width) }
        onDrawBehind {
            if (width > 0f && outline.alpha > 0f) dirs.forEach { d -> translate(d.x, d.y) {
                with(painter) { draw(size, colorFilter = outlineFilter) }
            } }
            if (shine.alpha > 0f) translate(-1.dp.toPx(), -1.dp.toPx()) { with(painter) { draw(size, colorFilter = shineFilter) } }
            drawIntoCanvas { it.saveLayer(rect, paint) }
            with(painter) { draw(size, colorFilter = iconFilter) }
            drawIntoCanvas { it.restore() }
        }
    })
}

/** Extra surface effects are cached per size/parameter change; pressure is draw-only state. */
private fun Modifier.labOverlays(p: LabParameters, radiusDp: Float, state: GlassButtonDeformationState,
    focused: Boolean, hovered: Boolean): Modifier = drawWithCache {
    val radius = radiusDp.dp.toPx()
    val direction = p["sheenAngleDegrees"] * PI.toFloat() / 180f
    val normal = Offset(cos(direction), sin(direction))
    val center = Offset(size.width * .5f, size.height * .5f)
    val distance = size.maxDimension * p["sheenWidth"].coerceAtLeast(.001f)
    val sheenColor = p.color("sheen").let { it.copy(alpha = it.alpha * p["sheenAlpha"]) }
    val sheen = Brush.linearGradient(listOf(Color.Transparent, sheenColor, Color.Transparent), center - normal * distance, center + normal * distance)
    val innerColor = p.color("innerShadow").let { it.copy(alpha = it.alpha * p["innerShadowAlpha"]) }
    val innerWidth = p["innerShadowWidthDp"].dp.toPx()
    val innerBands = List(16) { index ->
        val width = innerWidth * (1f - index / 16f)
        width to (innerColor.alpha / 16f * (index / 16f).pow(2))
    }
    val grainSize = p["grainSizeDp"].dp.toPx().coerceAtLeast(1f)
    val grain = java.util.Random(7)
    val noise = List(900) { Triple(Offset(grain.nextFloat() * size.width, grain.nextFloat() * size.height),
        if (grain.nextBoolean()) Color.White else Color.Black, grain.nextFloat() * p["grainAlpha"]) }
    val focusWidth = p["focusBorderWidthDp"].dp.toPx()
    val focusColor = p.color("focus").let { it.copy(alpha = it.alpha * p["focusBorderAlpha"]) }
    val hover = p.color("hover").let { it.copy(alpha = it.alpha * p["hoverAlpha"]) }
    val pressed = p.color("pressed").let { it.copy(alpha = it.alpha * p["pressedTintAlpha"]) }
    onDrawWithContent {
        if (p["sheenAlpha"] > 0f) drawRoundRect(sheen, cornerRadius = CornerRadius(radius))
        if (innerWidth > 0f && innerColor.alpha > 0f) innerBands.forEach { (width, alpha) ->
            val inset = width * .5f
            drawRoundRect(innerColor.copy(alpha = alpha), Offset(inset, inset), Size((size.width - width).coerceAtLeast(0f),
                (size.height - width).coerceAtLeast(0f)), CornerRadius((radius - inset).coerceAtLeast(0f)), style = Stroke(width))
        }
        if (p["grainAlpha"] > 0f) noise.forEach { (point, color, alpha) -> drawRect(color, point, Size(grainSize, grainSize), alpha = alpha) }
        if (hovered) drawRoundRect(hover, cornerRadius = CornerRadius(radius))
        val press = if (p.motionEnabled) state.visualFrame.opticalPress else if (state.pressed) 1f else 0f
        if (press > 0f && p.pressEffect != "none" && p.pressEffect != "darken")
            drawRoundRect(pressed, cornerRadius = CornerRadius(radius), alpha = press)
        drawContent()
        if (focused && focusWidth > 0f && focusColor.alpha > 0f) {
            val inset = focusWidth * .5f
            drawRoundRect(focusColor, Offset(inset, inset), Size((size.width - focusWidth).coerceAtLeast(0f),
                (size.height - focusWidth).coerceAtLeast(0f)), CornerRadius((radius - inset).coerceAtLeast(0f)), style = Stroke(focusWidth))
        }
    }
}

private data class PairKey(val size: IntSize, val margin: Int, val frame: GlassPairFrame,
    val tint: Int, val tuning: GlassDebugTuning, val bounds: Rect, val dark: Boolean)
private class PairEffects {
    val bridge = GlassPairBridgeContour()
    var key: PairKey? = null
    var effect: androidx.compose.ui.graphics.RenderEffect? = null
    var failed = false
}

internal val LabPairConnection = SemanticsPropertyKey<Float>("LabPairConnection")
internal val LabPairReady = SemanticsPropertyKey<Boolean>("LabPairReady")
private var SemanticsPropertyReceiver.labPairConnection by LabPairConnection
private var SemanticsPropertyReceiver.labPairReady by LabPairReady

@Composable
private fun LabPair(p: LabParameters, dark: Boolean, playId: Int, focused: Boolean, hovered: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val a = rememberGlassButtonDeformationState()
    val b = rememberGlassButtonDeformationState()
    val density = LocalDensity.current.density
    val tuning = remember(p) { p.tuning() }
    val gap = p["buttonGapDp"]
    val capture = LocalPageGlassBackdrop.current
    val lens = remember { if (Build.VERSION.SDK_INT >= 33) runCatching { GlassPairLensShader() }.getOrNull() else null }
    val copy = rememberGraphicsLayer()
    val effects = remember(tuning, p.glassEnabled, p.motionEnabled) { PairEffects() }
    val blur = remember(tuning.blurDp, density) {
        if (Build.VERSION.SDK_INT >= 31) if (tuning.blurDp > 0f)
            AndroidRenderEffect.createBlurEffect(tuning.blurDp * density, tuning.blurDp * density, Shader.TileMode.CLAMP)
        else AndroidRenderEffect.createOffsetEffect(0f, 0f) else null
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var ready by remember(effects) { mutableStateOf(false) }
    val connection = rememberGlassPairConnection(a, b, p.motionEnabled && p.springEnabled,
        density, appearMillis = min(40, p["fusionMillis"].toInt()),
        releaseMillis = tuning.bridgeReleaseMillis.toInt())
    val supported = p.glassEnabled && p.motionEnabled && lens != null && capture != null && !effects.failed && p["rotationDegrees"] == 0f
    val tinted = p.color("surface").let { it.copy(alpha = it.alpha * p["tintAlpha"]).toArgb() }
    BoxWithConstraints(modifier.semantics {
        labPairConnection = connection.value
        labPairReady = supported && ready
    }.testTag("pair-host"), contentAlignment = Alignment.Center) {
        val diameter = min(p["widthDp"], ((maxWidth.value - gap - 24f) / 2f).coerceAtLeast(32f))
        // Include spring overshoot, stretch and translation, independently of blur/shadow margin.
        val guard = ceil(max(64f, diameter * .5f * (2f * (1f + tuning.dragStretch * 2f) - 1f) +
            tuning.dragDistanceDp * 2f + 4f))
        Box(Modifier.requiredSize((diameter * 2f + gap + guard * 2f).dp, (diameter + guard * 2f).dp)
            .onGloballyPositioned { origin = it.localToRoot(Offset.Zero) }) {
            if (supported && Build.VERSION.SDK_INT >= 33 && capture != null && lens != null && blur != null) {
                // Keep the consumer in its own display layer. Its capture revision observation
                // must not re-record the sibling producer through the preview's outer clip layer.
                Box(Modifier.matchParentSize().graphicsLayer {}.drawWithCache {
                    onDrawBehind {
                        capture.revision
                        val width = ceil(size.width).toInt(); val height = ceil(size.height).toInt()
                        val shadowMargin = ceil((tuning.shadowBlurDp + tuning.shadowSpreadDp +
                            max(abs(tuning.shadowOffsetXDp), abs(tuning.shadowOffsetYDp)) + 1) * density).toInt()
                        val margin = max(glassSampleMargin(density, tuning.blurDp), shadowMargin)
                        val source = capture.origin - origin + Offset(margin.toFloat(), margin.toFloat())
                        val bounds = glassSampleBounds(width, height, margin, source.x, source.y,
                            capture.layer.size.width, capture.layer.size.height)
                        if (bounds == null || !drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                            if (ready) ready = false
                            return@onDrawBehind
                        }
                        fun shape(state: GlassButtonDeformationState, x: Float): GlassPairShape {
                            val frame = state.visualFrame
                            val center = Offset(x, guard + diameter / 2f) * density + frame.translation
                            val localTouch = glassGlowCenter(state.touchPosition, diameter * density, diameter * density) -
                                Offset(diameter / 2f, diameter / 2f) * density
                            return GlassPairShape(center, diameter / 2f * density * p["roundness"], frame.scaleX, frame.scaleY,
                                frame.opticalPress, center + Offset(localTouch.x * frame.scaleX, localTouch.y * frame.scaleY),
                                if (p.disabled) p["disabledAlpha"] else 1f,
                                halfSize = Offset(diameter / 2f, diameter / 2f) * density)
                        }
                        val first = shape(a, guard + diameter / 2f)
                        val second = shape(b, guard + diameter * 1.5f + gap)
                        val desiredBlend = glassPairBlendWidth(first, second, connection.value,
                            tuning.bridgeReachDp * density, 1.25f * p["fusionStrength"])
                        val blend = effects.bridge.width(desiredBlend, a.pressed || b.pressed || a.dragging || b.dragging)
                        val frame = GlassPairFrame(first, second, blend)
                        val key = PairKey(IntSize(width, height), margin, frame, tinted, tuning, bounds, dark)
                        if (effects.key != key) {
                            val effect = runCatching { AndroidRenderEffect.createChainEffect(
                                lens.effect(width, height, margin, tinted, density, dark, frame, bounds,
                                    GlassMaterialProfile.Button, tuning), blur).asComposeRenderEffect() }
                                .getOrElse { effects.failed = true; ready = false; return@onDrawBehind }
                            effects.key = key; effects.effect = effect
                        }
                        if (!ready) { ready = true; return@onDrawBehind }
                        copy.record(size = IntSize(width + 2 * margin, height + 2 * margin)) {
                            translate(source.x, source.y) { drawLayer(capture.layer) }
                        }
                        copy.renderEffect = effects.effect
                        translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(copy) }
                    }
                })
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                val parameters = p.withValue("widthDp", diameter).withValue("heightDp", diameter).copy(shape = "circle")
                LabControl(parameters, "circle", playId, focused, hovered, state = a,
                    drawMaterial = !supported || !ready, onClick = onClick)
                LabControl(parameters.copy(icon = if (p.icon == "settings") "add" else "settings"), "circle", 0,
                    focused, hovered, state = b, drawMaterial = !supported || !ready, onClick = onClick)
            }
        }
        if (!supported) Text(if (!p.glassEnabled || !p.motionEnabled) "材质／动态关闭：两个独立按钮" else "当前条件使用独立材质",
            Modifier.align(Alignment.TopCenter).padding(12.dp), color = p.color("text").copy(alpha = .6f), fontSize = 11.sp)
        if (diameter < p["widthDp"]) Text("双按钮已适配预览宽度；完整尺寸请查看单组件",
            Modifier.align(Alignment.TopCenter).padding(top = 34.dp), color = p.color("text").copy(alpha = .6f), fontSize = 11.sp)
    }
}
